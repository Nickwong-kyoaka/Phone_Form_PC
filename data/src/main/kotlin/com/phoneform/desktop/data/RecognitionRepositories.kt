package com.phoneform.desktop.data

import com.phoneform.ai.core.data.ExportSheetBuilder
import com.phoneform.ai.core.data.ExportQualityFlag
import com.phoneform.ai.core.model.*

internal class DesktopProcessingRepository(private val db: DesktopRepositories) : ProcessingJobRepository {
    override suspend fun initializeProcess(processSessionId: String) { require(processSessionId.isNotBlank()); db.write {
        list<ProcessingJob>("preparation").filter { it.status == PreparationJobStatus.RUNNING && it.processSessionId != processSessionId }.forEach {
            put("preparation", it.id, it.projectId, it.copy(status = PreparationJobStatus.PAUSED, runToken = null, processSessionId = null, error = "INTERRUPTED"))
        }
    } }
    override suspend fun claimNext(processSessionId: String, appInForeground: Boolean) = db.write {
        require(processSessionId.isNotBlank()); if (anyRunning()) return@write null
        val next = list<ProcessingJob>("preparation").filter { it.status in listOf(PreparationJobStatus.QUEUED, PreparationJobStatus.PAUSED) && eligible(it.projectId, appInForeground) && get<Submission>("submission", it.submissionId)?.currentRevisionId == it.submissionRevisionId }.minByOrNull { it.createdAtEpochMillis } ?: return@write null
        next.copy(status = PreparationJobStatus.RUNNING, attempt = next.attempt + 1, processSessionId = processSessionId, runToken = newId(), error = null).also { put("preparation", it.id, it.projectId, it) }
    }
    private fun Records.valid(job: ProcessingJob): ProcessingJob? {
        if (job.runToken == null || job.processSessionId == null || !eligible(job.projectId, true)) return null
        return get<ProcessingJob>("preparation", job.id)?.takeIf { it.status == PreparationJobStatus.RUNNING && it.runToken == job.runToken && it.processSessionId == job.processSessionId && it.submissionRevisionId == job.submissionRevisionId && get<Submission>("submission", it.submissionId)?.currentRevisionId == it.submissionRevisionId }
    }
    override suspend fun isClaimValid(job: ProcessingJob, appInForeground: Boolean) = db.read { valid(job) != null && eligible(job.projectId, appInForeground) }
    override suspend fun checkpoint(job: ProcessingJob, preparedPages: List<PageRevision>) = db.write {
        val stored = valid(job) ?: return@write false
        val revision = requireNotNull(get<SubmissionRevision>("revision", job.submissionRevisionId))
        require(preparedPages.map { it.templatePageId }.distinct().size == preparedPages.size)
        preparedPages.forEach { page -> require(revision.pages.any { it.templatePageId == page.templatePageId && sameEvidence(it.image, page.image) }) { "PREPARATION_EVIDENCE_CHANGED" } }
        put("preparation", stored.id, stored.projectId, stored.copy(preparedPages = preparedPages, stage = PreparationStage.PREPARE_IMAGES)); true
    }
    override suspend fun complete(job: ProcessingJob) = db.write {
        val stored = valid(job) ?: return@write false
        val revision = requireNotNull(get<SubmissionRevision>("revision", job.submissionRevisionId))
        if (stored.preparedPages.map { it.templatePageId }.toSet() != revision.pages.map { it.templatePageId }.toSet()) return@write false
        put("preparation", stored.id, stored.projectId, stored.copy(status = PreparationJobStatus.READY, stage = PreparationStage.READY, runToken = null, processSessionId = null, error = null)); enqueueRecognition(revision); true
    }
    override suspend fun pause(job: ProcessingJob, reason: String) { db.write { valid(job)?.let { put("preparation", it.id, it.projectId, it.copy(status = PreparationJobStatus.PAUSED, error = reason, runToken = null, processSessionId = null)); if (collection(it.projectId).mode == CollectionMode.BACKGROUND_ALLOWED) wake(it.projectId) } } }
    override suspend fun fail(job: ProcessingJob, reason: String) { db.write { valid(job)?.let { put("preparation", it.id, it.projectId, it.copy(status = PreparationJobStatus.FAILED, error = reason, runToken = null, processSessionId = null)) } } }
    override suspend fun pendingSchedules() = db.read { list<ProjectCollectionState>("collection").filter { it.wakeGeneration > it.scheduledGeneration && eligible(it.projectId, false) }.map { it.copy(locked = locked(it.projectId)) } }
    override suspend fun markScheduled(projectId: String, generation: Long) { db.write { active(projectId); val state = collection(projectId); require(generation <= state.wakeGeneration); put("collection", projectId, projectId, state.copy(scheduledGeneration = maxOf(generation, state.scheduledGeneration), schedulingError = if (generation >= state.wakeGeneration) null else state.schedulingError)) } }
    override suspend fun schedulingFailed(projectId: String, generation: Long, reason: String) { db.write { active(projectId); val state = collection(projectId); if (generation > state.scheduledGeneration && generation <= state.wakeGeneration) put("collection", projectId, projectId, state.copy(schedulingError = reason)) } }
    override suspend fun requestBackgroundContinuation() { db.write {
        val candidates = list<ProcessingJob>("preparation").filter { it.status in listOf(PreparationJobStatus.QUEUED, PreparationJobStatus.PAUSED, PreparationJobStatus.RUNNING) }.map { it.projectId } + list<RecognitionJob>("recognition").filter { it.status in listOf(RecognitionJobStatus.QUEUED, RecognitionJobStatus.PAUSED, RecognitionJobStatus.RUNNING) }.map { it.projectId }
        candidates.distinct().filter { eligible(it, false) }.forEach { wake(it) }
    } }
}

internal class DesktopIntelligenceRepository(private val db: DesktopRepositories) : IntelligenceRepository {
    private val profiles = RecognitionProfiles(db)
    override fun observeReviewSummaries(projectId: String) = db.observe { ListQueries.reviewSummaries(this,projectId) }
    override suspend fun queryReviewPage(projectId: String, query: ReviewPageQuery) = db.read { ListQueries.reviewPage(this,projectId,query) }
    override suspend fun getReviewSummary(submissionId: String) = db.read { ListQueries.reviewSummary(this,submissionId) }
    override suspend fun reviewNeighbors(projectId: String, submissionId: String, query: ReviewPageQuery) = db.read { ListQueries.neighbors(this,projectId,submissionId,query) }
    override suspend fun unconfirmedRerunTargets(projectId: String) = db.read { ListQueries.rerunTargets(this,projectId) }
    override fun observeAnalysis(draftId: String) = db.observe {
        val run = list<TemplateAnalysisRun>("analysis").filter { it.draftId == draftId }.maxByOrNull { it.createdAtEpochMillis } ?: return@observe null
        val draft = get<TemplateDraft>("template_draft", draftId)
        run.copy(suggestions = run.suggestions.filter { suggestion -> draft?.pages?.any { it.id == suggestion.pageId && it.revision == suggestion.imageRevision && (suggestion.disposition != SuggestionDisposition.PENDING || suggestion.processingFingerprint == PageProcessingFingerprint.of(it) || suggestion.processingFingerprint.isEmpty() && it.processing.mode == PageProcessingMode.OFF) } == true })
    }
    override suspend fun saveAnalysis(run: TemplateAnalysisRun) { db.write {
        requireProject(run.projectId); if (locked(run.projectId)) throw CollectionLockedException()
        get<TemplateAnalysisRun>("analysis", run.id)?.let { stored -> require(stored == run || get<TemplateAnalysisRun>("analysis_request", run.id) == run) { "ANALYSIS_OPERATION_REUSED" }; return@write }
        val draft = draft(run.projectId) ?: throw StaleTemplateException()
        if (draft.id != run.draftId || draft.revision != run.draftRevision) throw StaleTemplateException()
        require(run.suggestions.map { it.id }.distinct().size == run.suggestions.size)
        run.scope?.let { scope -> require(draft.pages.any { it.id == scope.pageId } && scope.region?.isValid() != false) }
        run.processingFingerprints.forEach { (id, fingerprint) -> if (draft.pages.find { it.id == id }?.let { PageProcessingFingerprint.of(it) } != fingerprint) throw StaleTemplateException() }
        run.suggestions.forEach { suggestion ->
            val page = draft.pages.find { it.id == suggestion.pageId } ?: throw StaleTemplateException()
            if (page.revision != suggestion.imageRevision || suggestion.question.pageId != page.id || suggestion.processingFingerprint.isNotEmpty() && suggestion.processingFingerprint != PageProcessingFingerprint.of(page)) throw StaleTemplateException()
            require(suggestion.sourceAnchor?.isValid() != false)
            run.scope?.let { scope -> require(page.id == scope.pageId); scope.region?.let { region -> val anchor = suggestion.sourceAnchor ?: suggestion.question.region ?: throw StaleTemplateException(); require(intersects(region, anchor)) } }
        }
        val history = list<TemplateAnalysisRun>("analysis", run.projectId).filter { it.draftId == run.draftId }
        val decisions = history.flatMap { it.suggestions }.filter { it.disposition != SuggestionDisposition.PENDING }
        val suggestions = run.suggestions.map { suggestion ->
            (decisions.find { it.pageId == suggestion.pageId && it.imageRevision == suggestion.imageRevision && (it.id == suggestion.id || sameAnchor(it, suggestion)) } ?: suggestion).copy(scope = run.scope)
        }
        val latest = history.maxByOrNull { it.createdAtEpochMillis }
        val retained = latest?.suggestions.orEmpty().filter { old ->
            draft.pages.any { it.id == old.pageId && it.revision == old.imageRevision } && suggestions.none { it.id == old.id } &&
                (old.disposition != SuggestionDisposition.PENDING || !scopeContains(run.scope, old))
        }
        put("analysis_request", run.id, run.projectId, run)
        put("analysis", run.id, run.projectId, run.copy(suggestions = (retained + suggestions).distinctBy { it.id }, createdAtEpochMillis = maxOf(run.createdAtEpochMillis, (latest?.createdAtEpochMillis ?: 0) + 1)))
    } }
    override suspend fun decideSuggestion(draftId: String, suggestionId: String, accept: Boolean) = db.write {
        val run = list<TemplateAnalysisRun>("analysis").filter { it.draftId == draftId && it.suggestions.any { s -> s.id == suggestionId } }.maxByOrNull { it.createdAtEpochMillis } ?: throw StaleTemplateException()
        requireProject(run.projectId); if (locked(run.projectId)) throw CollectionLockedException()
        val draft = draft(run.projectId) ?: throw StaleTemplateException(); if (draft.id != draftId) throw StaleTemplateException()
        val suggestion = run.suggestions.single { it.id == suggestionId }; val page = draft.pages.find { it.id == suggestion.pageId } ?: throw StaleTemplateException()
        if (page.revision != suggestion.imageRevision) throw StaleTemplateException()
        if (suggestion.disposition != SuggestionDisposition.PENDING) return@write draft
        if (suggestion.processingFingerprint.isNotEmpty() && suggestion.processingFingerprint != PageProcessingFingerprint.of(page)) throw StaleTemplateException()
        if (suggestion.processingFingerprint.isEmpty() && page.processing.mode != PageProcessingMode.OFF) throw StaleTemplateException()
        val next = if (accept) {
            require(!page.noExtraction && draft.questions.none { it.id == suggestion.question.id })
            draft.copy(revision = draft.revision + 1, questions = draft.questions + suggestion.question.copy(position = (draft.questions.filter { it.pageId == page.id }.maxOfOrNull { it.position } ?: -1) + 1), updatedAtEpochMillis = nextTime(draft.updatedAtEpochMillis))
        } else draft
        if (accept) put("template_draft", next.id, next.projectId, next)
        put("analysis", run.id, run.projectId, run.copy(suggestions = run.suggestions.map { if (it.id == suggestionId) it.copy(disposition = if (accept) SuggestionDisposition.ACCEPTED else SuggestionDisposition.DISMISSED) else it })); next
    }
    override suspend fun saveRecognitionProfile(profile: TemplateRecognitionProfile) { db.write { with(profiles) { immutableSave(profile) } } }
    override suspend fun getRecognitionProfile(templateVersionId: String) = db.read {
        get<FormSchemaVersion>("template", templateVersionId)?.let { active(it.projectId) }
        profileHead(templateVersionId) ?: get<TemplateRecognitionProfile>("recognition_profile", templateVersionId)
    }
    override suspend fun getRecognitionProfileById(profileId: String) = db.read {
        get<TemplateRecognitionProfile>("recognition_profile_v2", profileId)?.also { active(it.projectId) }
    }
    override suspend fun beginRecognitionProfileDraft(templateVersionId: String) = db.write { with(profiles) { begin(templateVersionId) } }
    override suspend fun saveRecognitionProfileDraft(draft: RecognitionProfileDraft) = db.write { with(profiles) { save(draft) } }
    override suspend fun confirmRecognitionProfileDraft(draftId: String, expectedRevision: Int, expectedHeadId: String?, operationId: String) =
        db.write { with(profiles) { confirm(draftId, expectedRevision, expectedHeadId, operationId) } }
    override suspend fun discardRecognitionProfileDraft(draftId: String, expectedRevision: Int) { db.write { with(profiles) { discard(draftId, expectedRevision) } } }
    override suspend fun rerunRecognition(submissionId: String, expectedRevisionId: String, profileId: String, expectedHeadId: String?, operationId: String) =
        db.write { with(profiles) { rerun(submissionId, expectedRevisionId, profileId, expectedHeadId, operationId) } }
    override fun observeRecognitionJobs(projectId: String) = db.observe { list<RecognitionJob>("recognition", projectId).sortedBy { it.createdAtEpochMillis } }
    override suspend fun initializeRecognition(processSessionId: String) { require(processSessionId.isNotBlank()); db.write {
        list<RecognitionJob>("recognition").filter { it.status in setOf(RecognitionJobStatus.RUNNING, RecognitionJobStatus.QUEUED, RecognitionJobStatus.PAUSED, RecognitionJobStatus.FAILED) }.forEach { job ->
            val reason = blockReason(job)
            when {
                recognition(job.submissionRevisionId)?.id != job.id || confirmed(job.submissionRevisionId) -> put("recognition", job.id, job.projectId, job.copy(status = RecognitionJobStatus.SUPERSEDED, runToken = null, processSessionId = null))
                reason != null -> put("recognition", job.id, job.projectId, job.copy(status = RecognitionJobStatus.BLOCKED, runToken = null, processSessionId = null, error = reason))
                job.status == RecognitionJobStatus.RUNNING && job.processSessionId != processSessionId -> put("recognition", job.id, job.projectId, job.copy(status = RecognitionJobStatus.PAUSED, runToken = null, processSessionId = null, error = "INTERRUPTED"))
            }
        }
        list<ProcessingJob>("preparation").filter { it.status == PreparationJobStatus.READY && eligible(it.projectId, true) && get<Submission>("submission", it.submissionId)?.currentRevisionId == it.submissionRevisionId }.forEach { job -> get<SubmissionRevision>("revision", job.submissionRevisionId)?.let { enqueueRecognition(it) } }
    } }
    override suspend fun claimRecognition(processSessionId: String, appInForeground: Boolean) = db.write {
        require(processSessionId.isNotBlank()); if (anyRunning()) return@write null
        val candidates = list<RecognitionJob>("recognition").filter { it.status in listOf(RecognitionJobStatus.QUEUED, RecognitionJobStatus.PAUSED) && eligible(it.projectId, appInForeground) }.sortedBy { it.createdAtEpochMillis }
        for (next in candidates) {
            if (recognition(next.submissionRevisionId)?.id != next.id || get<Submission>("submission", next.submissionId)?.currentRevisionId != next.submissionRevisionId || confirmed(next.submissionRevisionId)) {
                put("recognition", next.id, next.projectId, next.copy(status = RecognitionJobStatus.SUPERSEDED, runToken = null, processSessionId = null)); continue
            }
            blockReason(next)?.let { reason -> put("recognition", next.id, next.projectId, next.copy(status = RecognitionJobStatus.BLOCKED, runToken = null, processSessionId = null, error = reason)) } ?: return@write next.copy(status = RecognitionJobStatus.RUNNING, attempt = next.attempt + 1, runToken = newId(), processSessionId = processSessionId, error = null).also { put("recognition", it.id, it.projectId, it) }
        }
        null
    }
    private fun Records.confirmed(revisionId: String) = latestAnswers(revisionId)?.let { confirmationForAnswer(it.id) } != null
    private fun Records.blockReason(job: RecognitionJob): String? {
        if (job.provenance != RecognitionProvenance.VERSIONED) return "RECOGNITION_PROFILE_REQUIRED"
        val profileId = job.profileId ?: return "RECOGNITION_PROFILE_REQUIRED"
        val profile = get<TemplateRecognitionProfile>("recognition_profile_v2", profileId) ?: return "RECOGNITION_PROFILE_REQUIRED"
        if (profile.engineVersion != RecognitionContracts.ENGINE_VERSION || profile.parametersVersion != RecognitionContracts.PARAMETERS_VERSION || job.engineVersion != profile.engineVersion) return "RECOGNITION_ENGINE_UNAVAILABLE"
        val revision = get<SubmissionRevision>("revision", job.submissionRevisionId) ?: return "REVISION_UNAVAILABLE"
        if (profile.projectId != job.projectId || profile.templateVersionId != revision.templateVersionId || job.inputFingerprint != RecognitionBindings.jobFingerprint(revision, profile)) return "RECOGNITION_INPUT_CHANGED"
        val template = get<FormSchemaVersion>("template", revision.templateVersionId) ?: return "REVISION_UNAVAILABLE"
        if (profile.pageFingerprints != RecognitionBindings.pageFingerprints(template.document) || RecognitionBindings.validate(template.document, profile.settings).isNotEmpty()) return "RECOGNITION_PROFILE_INVALID"
        return null
    }
    private fun Records.valid(job: RecognitionJob): RecognitionJob? {
        if (job.runToken == null || job.processSessionId == null || !eligible(job.projectId, true) || recognition(job.submissionRevisionId)?.id != job.id || confirmed(job.submissionRevisionId)) return null
        return get<RecognitionJob>("recognition", job.id)?.takeIf { it.status == RecognitionJobStatus.RUNNING && it.runToken == job.runToken && it.processSessionId == job.processSessionId && it.submissionRevisionId == job.submissionRevisionId && it.profileId == job.profileId && it.engineVersion == job.engineVersion && it.inputFingerprint == job.inputFingerprint && blockReason(it) == null && get<Submission>("submission", it.submissionId)?.currentRevisionId == it.submissionRevisionId }
    }
    override suspend fun isRecognitionClaimValid(job: RecognitionJob, appInForeground: Boolean) = db.read { valid(job) != null && eligible(job.projectId, appInForeground) }
    private fun Records.checkAlignment(job: RecognitionJob, alignment: PageAlignment): PageRevision {
        val revision = requireNotNull(get<SubmissionRevision>("revision", job.submissionRevisionId))
        val original = requireNotNull(revision.pages.singleOrNull { it.templatePageId == alignment.templatePageId })
        val page = prep(revision.id)?.preparedPages?.singleOrNull { it.templatePageId == original.templatePageId && sameEvidence(it.image, original.image) } ?: original
        require(page.image.id == alignment.imageId && page.image.revision == alignment.imageRevision)
        require(alignment.engineVersion == job.engineVersion) { "RECOGNITION_ENGINE_UNAVAILABLE" }
        if (alignment.status == AlignmentStatus.NOT_REQUIRED) {
            val template = requireNotNull(get<FormSchemaVersion>("template", revision.templateVersionId))
            val profile = requireNotNull(get<TemplateRecognitionProfile>("recognition_profile_v2", job.profileId!!))
            val ids = template.document.questions.filter { it.pageId == page.templatePageId }.map { it.id }.toSet()
            require(profile.settings.none { it.questionId in ids && it.enabled }) { "RECOGNITION_ALIGNMENT_REQUIRED" }
        }
        if (alignment.status !in setOf(AlignmentStatus.FAILED, AlignmentStatus.NOT_REQUIRED)) {
            val m = alignment.templateToImage; require(m.size == 9 && m.all { it.isFinite() })
            val determinant = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])
            require(determinant.isFinite() && kotlin.math.abs(determinant) > 1E-12)
        }
        return page
    }
    override suspend fun checkpointAlignment(job: RecognitionJob, alignment: PageAlignment) = db.write {
        val stored = valid(job) ?: return@write false; checkAlignment(job, alignment)
        require(alignment.templatePageId !in stored.completedPageIds)
        put("recognition", stored.id, stored.projectId, stored.copy(alignments = stored.alignments.filterNot { it.templatePageId == alignment.templatePageId } + alignment)); true
    }
    override suspend fun checkpointRecognition(job: RecognitionJob, alignment: PageAlignment, observations: List<FieldObservation>) = db.write {
        val stored = valid(job) ?: return@write false; val page = checkAlignment(job, alignment)
        val revision = get<SubmissionRevision>("revision", job.submissionRevisionId)!!
        val template = get<FormSchemaVersion>("template", revision.templateVersionId)!!
        val keys = template.document.questions.filter { it.pageId == page.templatePageId }.map { "question:${it.id}" }
        val profile = requireNotNull(get<TemplateRecognitionProfile>("recognition_profile_v2", stored.profileId!!))
        require(observations.map { it.targetKey }.distinct().size == observations.size && observations.map { it.targetKey }.toSet() == keys.toSet())
        val actualSha = pageHash(db, page.image)
        observations.forEach {
            require(it.targetKey in keys && it.templatePageId == page.templatePageId && it.imageId == page.image.id && it.imageRevision == page.image.revision && it.candidate?.targetKey?.let { candidateKey -> candidateKey == it.targetKey } != false && it.evidenceRegion?.isValid() != false)
            require(it.processingFingerprint == PageProcessingFingerprint.of(page.image)) { "RECOGNITION_INPUT_CHANGED" }
            require(alignment.status != AlignmentStatus.FAILED || it.candidate == null) { "FAILED_ALIGNMENT_HAS_CANDIDATE" }
            require(it.provenance == RecognitionProvenance.VERSIONED && it.profileId == stored.profileId && it.engineVersion == stored.engineVersion && it.sourceSha256 == actualSha) { "RECOGNITION_EVIDENCE_CHANGED" }
            require(it.evidenceHash.isNotEmpty() && it.evidenceHash == RecognitionBindings.evidenceHash(it)) { "RECOGNITION_EVIDENCE_CHANGED" }
            val question = template.document.questions.single { q -> "question:${q.id}" == it.targetKey }
            val settings = profile.settings.find { s -> s.questionId == question.id }
            require(it.optionObservations.map { o -> o.optionId }.distinct().size == it.optionObservations.size)
            require(it.optionObservations.all { o -> o.optionId in question.options.map { option -> option.id } && o.region.isValid() && o.printedCore?.isValid() != false && o.submittedRegion?.isValid() != false && o.metrics.values.all(Double::isFinite) })
            val candidate = it.candidate
            if (candidate != null) {
                require(settings?.enabled == true && alignment.status !in setOf(AlignmentStatus.FAILED, AlignmentStatus.NOT_REQUIRED)) { "UNREQUESTED_CANDIDATE" }
                require(it.requiresReview && candidate.source == AnswerSource.MACHINE_ACCEPTED && !candidate.humanEdited && ReviewTargets.valid(candidate, AnswerTarget(it.targetKey, question.title, question)))
                require(candidate.candidateRef == CandidateRef(stored.id, profile.id, it.targetKey, it.evidenceHash)) { "CANDIDATE_REFERENCE_CHANGED" }
                if (question.type in setOf(QuestionType.SINGLE_CHOICE, QuestionType.MULTIPLE_CHOICE)) {
                    require(it.optionObservations.map { o -> o.optionId }.toSet() == question.options.map { o -> o.id }.toSet() && it.optionObservations.all { o -> o.decision in setOf(OptionDecision.SELECTED, OptionDecision.EMPTY) })
                    val selected = it.optionObservations.filter { o -> o.decision == OptionDecision.SELECTED }.map { o -> o.optionId }.toSet()
                    require(if (candidate.state == AnswerState.BLANK) selected.isEmpty() else selected == candidate.optionIds.toSet())
                }
            }
        }
        if (alignment.templatePageId in stored.completedPageIds) { require(stored.alignments.single { it.templatePageId == alignment.templatePageId } == alignment && stored.observations.filter { it.templatePageId == alignment.templatePageId } == observations); return@write true }
        put("recognition", stored.id, stored.projectId, stored.copy(completedPageIds = stored.completedPageIds + alignment.templatePageId, alignments = stored.alignments.filterNot { it.templatePageId == alignment.templatePageId } + alignment, observations = stored.observations.filterNot { it.templatePageId == alignment.templatePageId } + observations)); true
    }
    override suspend fun completeRecognition(job: RecognitionJob) = db.write {
        val stored = valid(job) ?: return@write false
        if (stored.completedPageIds.toSet() != get<SubmissionRevision>("revision", job.submissionRevisionId)!!.pages.map { it.templatePageId }.toSet()) return@write false
        put("recognition", stored.id, stored.projectId, stored.copy(status = RecognitionJobStatus.READY, runToken = null, processSessionId = null, error = null)); true
    }
    override suspend fun pauseRecognition(job: RecognitionJob, reason: String) { db.write { valid(job)?.let { put("recognition", it.id, it.projectId, it.copy(status = RecognitionJobStatus.PAUSED, error = reason, runToken = null, processSessionId = null)); if (collection(it.projectId).mode == CollectionMode.BACKGROUND_ALLOWED) wake(it.projectId) } } }
    override suspend fun failRecognition(job: RecognitionJob, reason: String) { db.write { valid(job)?.let { put("recognition", it.id, it.projectId, it.copy(status = RecognitionJobStatus.FAILED, error = reason, runToken = null, processSessionId = null)) } } }
    override suspend fun retryRecognition(jobId: String) { db.write {
        val job = requireNotNull(get<RecognitionJob>("recognition", jobId)); active(job.projectId)
        if (recognition(job.submissionRevisionId)?.id != job.id || confirmed(job.submissionRevisionId) || get<Submission>("submission", job.submissionId)?.currentRevisionId != job.submissionRevisionId) throw StaleRecognitionException()
        blockReason(job)?.let { throw IllegalStateException(it) }
        if (job.status in listOf(RecognitionJobStatus.FAILED, RecognitionJobStatus.PAUSED)) { put("recognition", job.id, job.projectId, job.copy(status = RecognitionJobStatus.QUEUED, runToken = null, processSessionId = null, error = null)); wake(job.projectId) }
    } }
    override fun observeReview(submissionId: String) = db.observe { review(submissionId) }
    override suspend fun getReview(submissionId: String) = db.read { review(submissionId) }
    private fun Records.editable(submissionId: String, revisionId: String, expectedVersion: Int): ReviewWorkspace {
        val workspace = review(submissionId) ?: throw StaleAnswerException()
        if (workspace.revision.id != revisionId || (workspace.answers?.version ?: 0) != expectedVersion) throw StaleAnswerException()
        require(prep(revisionId)?.status == PreparationJobStatus.READY) { "IMAGES_NOT_READY" }; return workspace
    }
    override suspend fun saveAnswer(submissionId: String, submissionRevisionId: String, expectedVersion: Int, answer: AnswerValue, operationId: String) = db.write {
        val submission = get<Submission>("submission", submissionId) ?: throw StaleAnswerException(); active(submission.projectId); require(operationId.isNotBlank())
        val saved = answer.copy(source = if (answer.source == AnswerSource.MACHINE_ACCEPTED) AnswerSource.MACHINE_ACCEPTED else AnswerSource.MANUAL,
            humanEdited = true, candidateRef = answer.candidateRef.takeIf { answer.source == AnswerSource.MACHINE_ACCEPTED })
        answerOperation(operationId)?.let { prior -> require(prior.submissionId == submissionId && prior.submissionRevisionId == submissionRevisionId && prior.answers.any { it == saved }); return@write prior }
        val workspace = editable(submissionId, submissionRevisionId, expectedVersion)
        if (saved.source == AnswerSource.MACHINE_ACCEPTED) {
            val job = recognition(submissionRevisionId)
            val observation = job?.observations?.find { it.targetKey == answer.targetKey }
            val candidate = observation?.candidate
            if (job == null || job.provenance != RecognitionProvenance.VERSIONED || blockReason(job) != null || observation == null || candidate == null ||
                saved.candidateRef == null || saved.candidateRef != candidate.candidateRef || saved.candidateRef != CandidateRef(job.id, job.profileId.orEmpty(), observation.targetKey, observation.evidenceHash) ||
                observation.evidenceHash != RecognitionBindings.evidenceHash(observation) || candidate.state != answer.state || candidate.text != answer.text || candidate.optionIds != answer.optionIds)
                throw StaleRecognitionException()
            val evidence = workspace.revision.pages.singleOrNull { it.templatePageId == observation.templatePageId && it.image.id == observation.imageId && it.image.revision == observation.imageRevision }
                ?: throw StaleRecognitionException()
            if (observation.processingFingerprint != PageProcessingFingerprint.of(evidence.image) || observation.sourceSha256 != pageHash(db, evidence.image)) throw StaleRecognitionException()
        }
        val target = requireNotNull(workspace.targets.find { it.key == answer.targetKey })
        require(target.question?.pageId !in workspace.revision.missingTemplatePageIds && answer.state != AnswerState.NOT_CAPTURED)
        val next = AnswerSetRevision(newId(), workspace.project.project.id, submissionId, submissionRevisionId, expectedVersion + 1, operationId, workspace.baseAnswers().map { if (it.targetKey == saved.targetKey) saved else it })
        put("answers", next.id, next.projectId, next); next
    }
    override suspend fun confirmReview(submissionId: String, submissionRevisionId: String, expectedVersion: Int, operationId: String) = db.write {
        val submission = get<Submission>("submission", submissionId) ?: throw StaleAnswerException(); active(submission.projectId); require(operationId.isNotBlank())
        confirmationOperation(operationId)?.let { require(it.submissionRevisionId == submissionRevisionId); return@write it }
        val workspace = editable(submissionId, submissionRevisionId, expectedVersion)
        workspace.confirmation?.let { return@write it }
        val safe = workspace.job?.takeIf { it.status == RecognitionJobStatus.READY && it.provenance == RecognitionProvenance.VERSIONED }?.observations.orEmpty().filterNot { it.requiresReview }.associateBy { it.targetKey }
        val resolved = workspace.baseAnswers().map { if (it.state != AnswerState.UNHANDLED || it.humanEdited) it else safe[it.targetKey]?.candidate?.copy(source = AnswerSource.MACHINE_ACCEPTED) ?: it }
        val invalid = workspace.targets.filter { target -> !ReviewTargets.valid(resolved.single { it.targetKey == target.key }, target) }.map { it.key }
        if (invalid.isNotEmpty()) throw ReviewValidationException(invalid)
        val answers = workspace.answers?.takeIf { it.answers == resolved } ?: AnswerSetRevision(newId(), submission.projectId, submissionId, submissionRevisionId, expectedVersion + 1, "confirmation:$operationId", resolved).also { put("answers", it.id, it.projectId, it) }
        val confirmation = ReviewConfirmation(newId(), submissionRevisionId, answers.id, operationId); put("confirmation", confirmation.id, submission.projectId, confirmation)
        recognition(submissionRevisionId)?.takeIf { it.status in setOf(RecognitionJobStatus.RUNNING, RecognitionJobStatus.PAUSED, RecognitionJobStatus.QUEUED, RecognitionJobStatus.BLOCKED) }?.let { job ->
            put("recognition", job.id, job.projectId, job.copy(status = RecognitionJobStatus.SUPERSEDED, runToken = null, processSessionId = null, error = "QUESTIONNAIRE_CONFIRMED"))
        }
        confirmation
    }
    override fun observeAnswerHistory(submissionId: String) = db.observe { list<AnswerSetRevision>("answers").filter { it.submissionId == submissionId }.sortedByDescending { it.createdAtEpochMillis } }
    override fun observePolicies(projectId: String) = db.observe { list<OutputFieldPolicy>("policy", projectId) }
    override suspend fun savePolicy(policy: OutputFieldPolicy) { db.write {
        val project = requireProject(policy.projectId)
        val field = project.fields.find { "field:${it.id}" == policy.targetKey }
        val template = project.project.templateVersionId?.let { get<FormSchemaVersion>("template", it) }
        require(field != null || template?.document?.questions?.any { "question:${it.id}" == policy.targetKey } == true)
        val key = field?.questionId?.takeIf { field.source == FieldSource.QUESTIONNAIRE && field.mappingState == FieldMappingState.BOUND }?.let { "question:$it" } ?: policy.targetKey
        val old = get<OutputFieldPolicy>("policy", "${policy.projectId}:$key")
        val next = policy.copy(targetKey = key, exportIncluded = old?.exportIncluded ?: policy.exportIncluded)
        if (old != next) { put("policy", "${policy.projectId}:$key", policy.projectId, next); put("project", policy.projectId, policy.projectId, project.copy(output = project.output.copy(version = project.output.version + 1))) }
        if (key != policy.targetKey) remove("policy", "${policy.projectId}:${policy.targetKey}")
    } }
    override suspend fun createExportSnapshot(projectId: String, includeUnresolved: Boolean, operationId: String) = db.write {
        val project = requireProject(projectId); require(operationId.isNotBlank())
        get<ExportSnapshot>("export", operationId)?.let { require(it.projectId == projectId && it.includeUnresolved == includeUnresolved); return@write it }
        val template = requireNotNull(project.project.templateVersionId?.let { get<FormSchemaVersion>("template", it) })
        val excluded = linkedMapOf<String, String>()
        val workspaces = list<Submission>("submission", projectId).filter { !it.discarded && it.currentRevisionId != null }.sortedBy { it.number }.mapNotNull { review(it.id) }.filter { w ->
            val reason = when {
                prep(w.revision.id)?.status != PreparationJobStatus.READY -> "IMAGES_NOT_READY"
                !includeUnresolved && w.revision.missingTemplatePageIds.isNotEmpty() -> "MISSING_PAGES"
                !includeUnresolved && w.confirmation == null -> "UNCONFIRMED"
                includeUnresolved && w.answers == null && w.job?.observations?.any { it.candidate != null } != true && w.targets.isNotEmpty() -> "NO_ANSWER_DATA"
                else -> null
            }
            if (reason != null) excluded[w.submission.id] = reason; reason == null
        }
        require(workspaces.isNotEmpty()) { "NO_ELIGIBLE_RECORDS" }
        val hashes = mutableMapOf<String, String?>()
        val quality = workspaces.flatMap { workspace -> workspace.revision.pages.mapNotNull { page ->
            val fingerprint = PageProcessingFingerprint.of(page.image)
            val result = get<PageProcessingResult>("image_result", fingerprint)
            val job = imageJob(workspace.revision.owner(), page.image.id, fingerprint)
            if (result == null && job == null) return@mapNotNull null
            val actualSha = hashes.getOrPut(page.image.renderPath) { runCatching { pageHash(db, page.image) }.getOrNull() }
            val decision = get<PageQualityKeepDecision>("quality_keep", decisionId(workspace.revision.owner(), page.image.id, fingerprint))?.takeIf { it.inputSha256 == actualSha }
            val codes = when {
                result == null -> listOf(if (job?.status == PageProcessingStatus.FAILED) "QUALITY_FAILED" else "QUALITY_PENDING")
                actualSha == null || result.report.inputSha256 != actualSha -> listOf("QUALITY_UNVERIFIED")
                else -> result.report.issues.filter { it.severity != PageQualitySeverity.INFO }.map { "${it.code}:${it.severity}" } +
                    listOfNotNull("QUALITY_UNKNOWN".takeIf { result.report.assessment == PageQualityAssessment.UNKNOWN }, "ENHANCEMENT_UNAVAILABLE".takeIf { page.image.processing.mode == PageProcessingMode.AUTO_LIGHT && result.enhanced == null })
            }
            if (codes.isEmpty()) null else ExportQualityFlag(workspace.submission.id, template.document.pages.indexOfFirst { it.id == page.templatePageId } + 1, codes, decision != null)
        } }
        val snapshot = ExportSheetBuilder.build(operationId, project, template, list<OutputFieldPolicy>("policy", projectId), workspaces, excluded, includeUnresolved, quality)
        put("export", snapshot.id, projectId, snapshot); snapshot
    }
    override fun observeExports(projectId: String) = db.observe { list<ExportSnapshot>("export", projectId).sortedByDescending { it.createdAtEpochMillis } }
    override fun observeExportSummaries(projectId: String) = db.observe { ListQueries.exports(this,projectId) }
    override suspend fun getExportSnapshot(snapshotId: String) = db.read { get<ExportSnapshot>("export", snapshotId) }
    override suspend fun saveExportArtifact(artifact: ExportArtifact) { db.write {
        val snapshot = requireNotNull(get<ExportSnapshot>("export", artifact.snapshotId)); active(snapshot.projectId)
        if (artifact.status == ExportArtifactStatus.READY) require(!artifact.relativePath.isNullOrBlank() && artifact.byteCount > 0 && artifact.sha256?.matches(Regex("[a-fA-F0-9]{64}")) == true && db.workspace.assets.exists(artifact.relativePath!!))
        put("export_artifact", artifact.snapshotId, snapshot.projectId, artifact)
    } }
    override suspend fun getExportArtifact(snapshotId: String) = db.read { get<ExportArtifact>("export_artifact", snapshotId) }
}

internal fun Records.review(submissionId: String): ReviewWorkspace? {
    val submission = get<Submission>("submission", submissionId) ?: return null
    if (get<ProjectDeletionStatus>("deletion", submission.projectId) != null) return null
    val revision = submission.currentRevisionId?.let { get<SubmissionRevision>("revision", it) } ?: return null
    val project = project(submission.projectId) ?: return null
    val template = get<FormSchemaVersion>("template", revision.templateVersionId) ?: return null
    val prepared = prep(revision.id)?.preparedPages.orEmpty()
    val evidence = revision.copy(pages = revision.pages.map { page -> prepared.find { it.templatePageId == page.templatePageId && sameEvidence(it.image, page.image) } ?: page })
    val answers = latestAnswers(revision.id)
    val confirmation = answers?.let { confirmationForAnswer(it.id) }
    return ReviewWorkspace(project, submission, evidence, template, recognition(revision.id), answers, confirmation, ReviewTargets.build(project, template))
}
private fun ReviewWorkspace.baseAnswers() = targets.map { target -> if (target.question?.pageId in revision.missingTemplatePageIds) AnswerValue(target.key, AnswerState.NOT_CAPTURED) else answers?.answers?.find { it.targetKey == target.key } ?: AnswerValue(target.key) }
private fun intersects(a: NormalizedRect, b: NormalizedRect) = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
private fun sameAnchor(a: TemplateSuggestion, b: TemplateSuggestion): Boolean {
    val first = a.sourceAnchor ?: a.question.region ?: return false
    val second = b.sourceAnchor ?: b.question.region ?: return false
    if (!intersects(first, second)) return false
    val intersection = (minOf(first.right, second.right) - maxOf(first.left, second.left)) * (minOf(first.bottom, second.bottom) - maxOf(first.top, second.top))
    val smaller = minOf((first.right - first.left) * (first.bottom - first.top), (second.right - second.left) * (second.bottom - second.top))
    return smaller > 0 && intersection / smaller >= .65f && kotlin.math.abs(first.left - second.left) <= .035f && kotlin.math.abs(first.top - second.top) <= .025f
}
private fun scopeContains(scope: TemplateAnalysisScope?, suggestion: TemplateSuggestion): Boolean {
    if (scope == null) return true
    if (scope.pageId != suggestion.pageId) return false
    val region = scope.region ?: return true
    val anchor = suggestion.sourceAnchor ?: suggestion.question.region ?: return false
    return (anchor.left + anchor.right) / 2 in region.left..region.right && (anchor.top + anchor.bottom) / 2 in region.top..region.bottom
}
