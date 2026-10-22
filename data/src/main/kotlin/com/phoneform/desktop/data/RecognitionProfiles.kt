package com.phoneform.desktop.data

import com.phoneform.ai.core.model.*

internal data class RecognitionOperation(val requestHash: String, val resultId: String)
internal fun Records.profileHead(templateId: String): TemplateRecognitionProfile? =
    get<RecordReference>("recognition_profile_head", templateId)?.let { get("recognition_profile_v2", it.id) }
internal fun Records.requireProfile(template: FormSchemaVersion, profile: TemplateRecognitionProfile) {
    require(profile.id.isNotBlank() && profile.version >= 1 && profile.projectId == template.projectId && profile.templateVersionId == template.id)
    require(profile.pageFingerprints == RecognitionBindings.pageFingerprints(template.document)) { "RECOGNITION_PAGE_CHANGED" }
    require(profile.engineVersion == RecognitionContracts.ENGINE_VERSION && profile.parametersVersion == RecognitionContracts.PARAMETERS_VERSION) { "RECOGNITION_ENGINE_UNAVAILABLE" }
    require(RecognitionBindings.validate(template.document, profile.settings).isEmpty()) { "RECOGNITION_SETTINGS_INVALID" }
}
internal fun completeSettings(document: TemplateDraft, settings: List<QuestionRecognitionSettings>): List<QuestionRecognitionSettings> {
    require(settings.map { it.questionId }.distinct().size == settings.size && settings.all { s -> document.questions.any { it.id == s.questionId } }) { "RECOGNITION_SETTINGS_INVALID" }
    val byId = settings.associateBy { it.questionId }
    return document.questions.map { byId[it.id] ?: QuestionRecognitionSettings(it.id) }
}
internal fun Records.installInitialProfile(template: FormSchemaVersion) {
    if (profileHead(template.id) != null) return
    val profile = RecognitionBindings.initial(template)
    requireProfile(template, profile)
    put("recognition_profile_v2", profile.id, template.projectId, profile)
    put("recognition_profile_head", template.id, template.projectId, RecordReference(profile.id))
}

/** All functions execute inside the caller's single authenticated database transaction. */
internal class RecognitionProfiles(private val db: DesktopRepositories) {
    fun Records.begin(templateVersionId: String): RecognitionProfileDraft {
        val template = requireNotNull(get<FormSchemaVersion>("template", templateVersionId)); active(template.projectId)
        list<RecognitionProfileDraft>("recognition_profile_draft", template.projectId).singleOrNull { it.templateVersionId == templateVersionId }?.let { return it }
        val head = profileHead(templateVersionId)
        return RecognitionProfileDraft(newId(), template.projectId, templateVersionId, baseProfileId = head?.id,
            pageFingerprints = RecognitionBindings.pageFingerprints(template.document), settings = completeSettings(template.document, head?.settings ?: RecognitionBindings.settings(template.document)))
            .also { put("recognition_profile_draft", it.id, it.projectId, it) }
    }
    fun Records.save(draft: RecognitionProfileDraft): RecognitionProfileDraft {
        active(draft.projectId)
        val old = get<RecognitionProfileDraft>("recognition_profile_draft", draft.id) ?: throw StaleRecognitionException()
        if (old.projectId != draft.projectId || old.templateVersionId != draft.templateVersionId || old.baseProfileId != draft.baseProfileId || old.revision != draft.revision) throw StaleRecognitionException()
        val template = requireNotNull(get<FormSchemaVersion>("template", draft.templateVersionId))
        require(template.projectId == draft.projectId && draft.pageFingerprints == RecognitionBindings.pageFingerprints(template.document)) { "RECOGNITION_PAGE_CHANGED" }
        val normalized = draft.copy(settings = completeSettings(template.document, draft.settings), revision = old.revision + 1)
        put("recognition_profile_draft", normalized.id, normalized.projectId, normalized); return normalized
    }
    fun Records.confirm(draftId: String, expectedRevision: Int, expectedHeadId: String?, operationId: String): TemplateRecognitionProfile {
        require(operationId.isNotBlank())
        val request = RecognitionBindings.hash(draftId, expectedRevision.toString(), expectedHeadId.orEmpty())
        get<RecognitionOperation>("recognition_profile_operation", operationId)?.let { prior ->
            require(prior.requestHash == request) { "OPERATION_ID_REUSED" }
            return requireNotNull(get<TemplateRecognitionProfile>("recognition_profile_v2", prior.resultId)).also { active(it.projectId) }
        }
        val draft = get<RecognitionProfileDraft>("recognition_profile_draft", draftId) ?: throw StaleRecognitionException()
        active(draft.projectId)
        val head = profileHead(draft.templateVersionId)
        if (draft.revision != expectedRevision || head?.id != expectedHeadId || draft.baseProfileId != expectedHeadId) throw StaleRecognitionException()
        val template = requireNotNull(get<FormSchemaVersion>("template", draft.templateVersionId))
        val profile = TemplateRecognitionProfile(template.id, draft.engineVersion, draft.pageFingerprints,
            id = newId(), projectId = draft.projectId, version = (head?.version ?: 0) + 1,
            settings = completeSettings(template.document, draft.settings), parametersVersion = draft.parametersVersion, sourceDraftId = draft.id)
        requireProfile(template, profile)
        put("recognition_profile_v2", profile.id, profile.projectId, profile)
        put("recognition_profile_head", template.id, profile.projectId, RecordReference(profile.id))
        put("recognition_profile_operation", operationId, profile.projectId, RecognitionOperation(request, profile.id))
        remove("recognition_profile_draft", draftId); return profile
    }
    fun Records.discard(draftId: String, expectedRevision: Int) {
        val draft = get<RecognitionProfileDraft>("recognition_profile_draft", draftId) ?: return
        active(draft.projectId); if (draft.revision != expectedRevision) throw StaleRecognitionException()
        remove("recognition_profile_draft", draftId)
    }
    fun Records.immutableSave(incoming: TemplateRecognitionProfile) {
        val template = requireNotNull(get<FormSchemaVersion>("template", incoming.templateVersionId)); active(template.projectId)
        val profile = incoming.copy(projectId = template.projectId, settings = completeSettings(template.document, incoming.settings))
        get<TemplateRecognitionProfile>("recognition_profile_v2", profile.id)?.let { require(it == profile) { "PROFILE_IMMUTABLE" }; return }
        val head = profileHead(template.id)
        if (profile.version != (head?.version ?: 0) + 1) throw StaleRecognitionException()
        requireProfile(template, profile)
        put("recognition_profile_v2", profile.id, profile.projectId, profile)
        put("recognition_profile_head", template.id, profile.projectId, RecordReference(profile.id))
    }
    fun Records.rerun(submissionId: String, expectedRevisionId: String, profileId: String, expectedHeadId: String?, operationId: String): RecognitionJob {
        require(operationId.isNotBlank())
        val submission = requireNotNull(get<Submission>("submission", submissionId)); active(submission.projectId)
        val request = RecognitionBindings.hash(submissionId, expectedRevisionId, profileId, expectedHeadId.orEmpty())
        get<RecognitionOperation>("recognition_job_operation", operationId)?.let { prior ->
            require(prior.requestHash == request) { "OPERATION_ID_REUSED" }
            return requireNotNull(get<RecognitionJob>("recognition", prior.resultId))
        }
        if (submission.currentRevisionId != expectedRevisionId || submission.discarded || recognition(expectedRevisionId)?.id != expectedHeadId) throw StaleRecognitionException()
        val revision = requireNotNull(get<SubmissionRevision>("revision", expectedRevisionId))
        if (latestAnswers(revision.id)?.let { confirmationForAnswer(it.id) } != null) throw StaleRecognitionException()
        require(prep(revision.id)?.status == PreparationJobStatus.READY) { "IMAGES_NOT_READY" }
        val template = requireNotNull(get<FormSchemaVersion>("template", revision.templateVersionId))
        val profile = requireNotNull(get<TemplateRecognitionProfile>("recognition_profile_v2", profileId))
        requireProfile(template, profile)
        recognition(revision.id)?.takeIf { it.status != RecognitionJobStatus.READY }?.let { old ->
            put("recognition", old.id, old.projectId, old.copy(status = RecognitionJobStatus.SUPERSEDED, runToken = null, processSessionId = null))
        }
        val job = RecognitionJob(newId(), submission.projectId, submissionId, revision.id,
            profileId = profile.id, engineVersion = profile.engineVersion, inputFingerprint = RecognitionBindings.jobFingerprint(revision, profile), provenance = RecognitionProvenance.VERSIONED)
        put("recognition", job.id, job.projectId, job)
        put("recognition_head", revision.id, job.projectId, RecordReference(job.id))
        put("recognition_job_operation", operationId, job.projectId, RecognitionOperation(request, job.id)); wake(job.projectId)
        return job
    }
}
