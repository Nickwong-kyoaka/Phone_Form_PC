package com.phoneform.desktop.data

import com.phoneform.ai.core.model.*

internal class DesktopProjectRepository(private val db: DesktopRepositories) : ProjectRepository {
    override fun observeProjects() = db.observe { list<ProjectDetails>("project").filter { get<ProjectDeletionStatus>("deletion", it.project.id) == null }.map { it.project }.sortedByDescending { it.updatedAtEpochMillis } }
    override fun observeProject(id: String) = db.observe { project(id) }
    override suspend fun getProject(id: String) = db.read { project(id) }
    override fun observeProjectSummaries() = db.observe {
        list<ProjectDetails>("project").filter { get<ProjectDeletionStatus>("deletion", it.project.id) == null }.sortedByDescending { it.project.updatedAtEpochMillis }.map { details ->
            val id = details.project.id; val submissions = list<Submission>("submission", id).filterNot { it.discarded }
            val submitted = submissions.filter { it.currentRevisionId != null }
            val preparation = list<ProcessingJob>("preparation", id).filter { p -> submitted.any { it.currentRevisionId == p.submissionRevisionId } }
            val recognition = submitted.mapNotNull { s -> s.currentRevisionId?.let { recognition(it) } }
            val confirmed = submitted.filter { s -> latestAnswers(s.currentRevisionId!!)?.let { a -> confirmationForAnswer(a.id) != null } == true }.map { it.currentRevisionId }.toSet()
            val prepByRevision = preparation.associateBy { it.submissionRevisionId }
            val recognitionByRevision = recognition.associateBy { it.submissionRevisionId }
            ProjectListSummary(details.project, submitted.size, list<SubmissionDraft>("submission_draft", id).size,
                submitted.count { get<SubmissionRevision>("revision", it.currentRevisionId!!)?.missingTemplatePageIds?.isNotEmpty() == true },
                submitted.count { s -> prepByRevision[s.currentRevisionId]?.status in listOf(PreparationJobStatus.QUEUED, PreparationJobStatus.RUNNING, PreparationJobStatus.PAUSED) || recognitionByRevision[s.currentRevisionId]?.status in listOf(RecognitionJobStatus.QUEUED, RecognitionJobStatus.RUNNING, RecognitionJobStatus.PAUSED) },
                recognition.count { it.status == RecognitionJobStatus.READY && it.submissionRevisionId !in confirmed },
                confirmed.size,
                submitted.count { s -> prepByRevision[s.currentRevisionId]?.status == PreparationJobStatus.FAILED || recognitionByRevision[s.currentRevisionId]?.status == RecognitionJobStatus.FAILED })
        }
    }
    override suspend fun saveProject(details: ProjectDetails) { db.write { persist(details, false, false) } }
    override suspend fun saveProjectSection(details: ProjectDetails, section: ProjectEditSection, expectedOutputVersion: Int?) = db.write {
        val current = requireProject(details.project.id)
        val merged = when (section) {
            ProjectEditSection.PROJECT_INFO -> current.copy(project = current.project.copy(name = details.project.name, expectedCount = details.project.expectedCount))
            ProjectEditSection.INPUT_RULES -> current.copy(project = current.project.copy(questionnaireLanguage = details.project.questionnaireLanguage, numberingStrategy = details.project.numberingStrategy))
            ProjectEditSection.PROJECT_SETTINGS -> current.copy(project = current.project.copy(name = details.project.name, expectedCount = details.project.expectedCount, questionnaireLanguage = details.project.questionnaireLanguage, numberingStrategy = details.project.numberingStrategy))
            ProjectEditSection.OUTPUT -> { if (current.output.version != expectedOutputVersion) throw StaleOutputConfigurationException(); current.copy(output = details.output, fields = details.fields) }
        }
        persist(merged, section == ProjectEditSection.OUTPUT, false)
    }
    override suspend fun saveOutputConfiguration(details: ProjectDetails, policies: List<OutputFieldPolicy>, expectedVersion: Int) = db.write {
        val old = requireProject(details.project.id)
        if (old.output.version != expectedVersion) throw StaleOutputConfigurationException()
        require(policies.all { it.projectId == old.project.id } && policies.map { it.targetKey }.distinct().size == policies.size)
        var saved = persist(old.copy(output = details.output, fields = details.fields), true, true)
        val questions = saved.project.templateVersionId?.let { get<FormSchemaVersion>("template", it)?.document?.questions?.map { q -> q.id } }.orEmpty()
        val grouped = policies.groupBy { policy ->
            val field = saved.fields.find { "field:${it.id}" == policy.targetKey }
            require(field != null || policy.targetKey.startsWith("question:") && policy.targetKey.removePrefix("question:") in questions)
            field?.questionId?.takeIf { field.source == FieldSource.QUESTIONNAIRE && field.mappingState == FieldMappingState.BOUND }?.let { "question:$it" } ?: policy.targetKey
        }
        var changed = false
        grouped.forEach { (key, entries) ->
            val next = OutputFieldPolicy(old.project.id, key, entries.any { it.personalIdentifier }, entries.all { it.exportIncluded })
            val recordId = "${old.project.id}:$key"
            if (get<OutputFieldPolicy>("policy", recordId) != next) { put("policy", recordId, old.project.id, next); changed = true }
            entries.filter { it.targetKey != key }.forEach { remove("policy", "${old.project.id}:${it.targetKey}") }
        }
        if (changed && saved.output.version == expectedVersion) { saved = saved.copy(output = saved.output.copy(version = expectedVersion + 1)); put("project", saved.project.id, saved.project.id, saved) }
        saved
    }
    private fun Records.persist(incoming: ProjectDetails, layout: Boolean, bindings: Boolean): ProjectDetails {
        active(incoming.project.id); val old = project(incoming.project.id)
        if (old == null) require(incoming.project.templateVersionId == null && incoming.project.preparationStatus == PreparationStatus.AWAITING_SAMPLE)
        if (old != null && locked(old.project.id)) {
            if (incoming.project.questionnaireLanguage != old.project.questionnaireLanguage || incoming.project.numberingStrategy != old.project.numberingStrategy ||
                incoming.fields.associate { it.id to it.source } != old.fields.associate { it.id to it.source } ||
                incoming.output.extractionScope != old.output.extractionScope || incoming.output.format != old.output.format ||
                bindings && incoming.fields.associate { it.id to (it.mappingState to it.questionId) } != old.fields.associate { it.id to (it.mappingState to it.questionId) }) throw CollectionLockedException()
        }
        val questionIds = old?.project?.templateVersionId?.let { get<FormSchemaVersion>("template", it)?.document?.questions?.map { q -> q.id } }.orEmpty()
        val fields = incoming.fields.sortedBy { it.position }.map { field ->
            val previous = old?.fields?.find { it.id == field.id && it.source == field.source }
            when {
                field.source == FieldSource.MANUAL_METADATA -> field.copy(mappingState = FieldMappingState.MANUAL_ENTRY, questionId = null)
                bindings -> { require(field.mappingState != FieldMappingState.BOUND || field.questionId in questionIds); require(field.mappingState == FieldMappingState.BOUND || field.questionId == null); field }
                previous != null -> field.copy(mappingState = previous.mappingState, questionId = previous.questionId)
                else -> { require(field.questionId == null && field.mappingState != FieldMappingState.BOUND); field.copy(mappingState = FieldMappingState.UNMAPPED) }
            }
        }
        val normalized = incoming.copy(project = incoming.project.copy(
            createdAtEpochMillis = old?.project?.createdAtEpochMillis ?: incoming.project.createdAtEpochMillis,
            updatedAtEpochMillis = old?.project?.updatedAtEpochMillis ?: incoming.project.updatedAtEpochMillis,
            templateVersionId = old?.project?.templateVersionId, preparationStatus = old?.project?.preparationStatus ?: incoming.project.preparationStatus),
            output = incoming.output.copy(version = old?.output?.version ?: 1, columnLayout = if (layout) incoming.output.columnLayout else old?.output?.columnLayout ?: incoming.output.columnLayout), fields = fields)
        ProjectValidation.validate(normalized)
        if (old == normalized) return old
        val saved = normalized.copy(project = normalized.project.copy(updatedAtEpochMillis = old?.let { nextTime(it.project.updatedAtEpochMillis) } ?: normalized.project.updatedAtEpochMillis), output = normalized.output.copy(version = (old?.output?.version ?: 0) + 1))
        put("project", saved.project.id, saved.project.id, saved)
        draft(saved.project.id)?.let { draft ->
            val mappings = fields.map { field ->
                if (field.source == FieldSource.MANUAL_METADATA) FieldMapping(field.id, FieldMappingState.MANUAL_ENTRY)
                else if (bindings) FieldMapping(field.id, field.mappingState, field.questionId)
                else if (old?.fields?.find { it.id == field.id }?.source != field.source) FieldMapping(field.id, FieldMappingState.UNMAPPED)
                else draft.mappings.find { it.fieldId == field.id } ?: FieldMapping(field.id, FieldMappingState.UNMAPPED)
            }
            if (draft.mappings != mappings) put("template_draft", draft.id, draft.projectId, draft.copy(revision = draft.revision + 1, mappings = mappings, updatedAtEpochMillis = nextTime(draft.updatedAtEpochMillis)))
        }
        migratePolicies(saved.project.id, fields)
        return saved
    }
}

internal fun Records.migratePolicies(projectId: String, fields: List<RequestedOutputField>) {
    fields.filter { it.source == FieldSource.QUESTIONNAIRE && it.mappingState == FieldMappingState.BOUND }.forEach { field ->
        val alias = "$projectId:field:${field.id}"; val policy = get<OutputFieldPolicy>("policy", alias) ?: return@forEach
        val target = "question:${field.questionId}"; val canonical = get<OutputFieldPolicy>("policy", "$projectId:$target")
        put("policy", "$projectId:$target", projectId, policy.copy(targetKey = target, personalIdentifier = policy.personalIdentifier || canonical?.personalIdentifier == true, exportIncluded = policy.exportIncluded && canonical?.exportIncluded != false)); remove("policy", alias)
    }
}

internal class DesktopTemplateRepository(private val db: DesktopRepositories) : TemplateRepository {
    override suspend fun getVersion(versionId: String) = db.read { get<FormSchemaVersion>("template", versionId) }
    override fun observeDraft(projectId: String) = db.observe { draft(projectId) }
    override fun observeConfirmed(projectId: String) = db.observe { project(projectId)?.project?.templateVersionId?.let { get<FormSchemaVersion>("template", it) } }
    override suspend fun beginDraft(projectId: String) = db.write {
        val project = requireProject(projectId); if (locked(projectId)) throw CollectionLockedException()
        draft(projectId)?.let { return@write it }
        val active = project.project.templateVersionId?.let { get<FormSchemaVersion>("template", it) }
        val draft = (active?.document ?: TemplateDraft(newId(), projectId)).copy(id = newId(), revision = 0, baseVersionId = active?.id,
            mappings = project.fields.map { FieldMapping(it.id, if (it.source == FieldSource.MANUAL_METADATA) FieldMappingState.MANUAL_ENTRY else it.mappingState, it.questionId) }, updatedAtEpochMillis = System.currentTimeMillis())
        put("template_draft", draft.id, projectId, draft)
        put("project", projectId, projectId, project.copy(project = project.project.copy(preparationStatus = PreparationStatus.TEMPLATE_DRAFT, updatedAtEpochMillis = nextTime(project.project.updatedAtEpochMillis))))
        draft
    }
    override suspend fun saveDraft(draft: TemplateDraft) = db.write {
        requireProject(draft.projectId); if (locked(draft.projectId)) throw CollectionLockedException()
        val old = draft(draft.projectId) ?: throw StaleTemplateException()
        if (old.id != draft.id || old.revision != draft.revision || old.baseVersionId != draft.baseVersionId) throw StaleTemplateException()
        draft.pages.forEach { page -> old.pages.find { it.id == page.id && it.revision == page.revision }?.let { previous ->
            if (previous.copy(position = page.position, noExtraction = page.noExtraction, renderPath = page.renderPath, thumbnailPath = page.thumbnailPath, processing = page.processing) != page) throw StaleTemplateException()
        } }
        val saved = draft.copy(revision = old.revision + 1, updatedAtEpochMillis = nextTime(old.updatedAtEpochMillis))
        put("template_draft", saved.id, saved.projectId, saved); queueImages(saved.owner(), saved.pages, db); saved
    }
    override suspend fun confirmDraft(projectId: String, draftId: String, expectedRevision: Int) = db.write {
        val project = requireProject(projectId)
        list<FormSchemaVersion>("template", projectId).find { it.sourceDraftId == draftId }?.let { prior ->
            if (prior.document.revision != expectedRevision) throw StaleTemplateException(); return@write prior
        }
        if (locked(projectId)) throw CollectionLockedException()
        val draft = draft(projectId) ?: throw StaleTemplateException()
        if (draft.id != draftId || draft.revision != expectedRevision || draft.baseVersionId != project.project.templateVersionId) throw StaleTemplateException()
        val issues = TemplateValidation.validate(draft, project) + draft.pages.filterNot { db.pageAvailable(it) }.map { TemplateIssue(TemplateIssueCode.PAGE_FILE_MISSING, it.id) }
        if (issues.isNotEmpty()) throw TemplateValidationException(issues.distinct())
        checkQuality(draft.owner(), draft.pages, db)
        val mappings = project.fields.map { field -> if (field.source == FieldSource.MANUAL_METADATA) FieldMapping(field.id, FieldMappingState.MANUAL_ENTRY) else draft.mappings.single { it.fieldId == field.id } }
        val version = FormSchemaVersion(newId(), projectId, (list<FormSchemaVersion>("template", projectId).maxOfOrNull { it.version } ?: 0) + 1, draftId, System.currentTimeMillis(), draft.copy(mappings = mappings))
        put("template", version.id, projectId, version)
        installInitialProfile(version)
        val fields = project.fields.map { field -> mappings.single { it.fieldId == field.id }.let { field.copy(mappingState = it.state, questionId = it.questionId) } }
        migratePolicies(projectId, fields)
        put("project", projectId, projectId, project.copy(project = project.project.copy(templateVersionId = version.id, preparationStatus = PreparationStatus.TEMPLATE_CONFIRMED, updatedAtEpochMillis = nextTime(project.project.updatedAtEpochMillis)), output = project.output.copy(version = project.output.version + 1), fields = fields))
        remove("template_draft", draftId); version
    }
    override suspend fun discardDraft(projectId: String, draftId: String) { db.write {
        val project = requireProject(projectId); val draft = draft(projectId) ?: return@write
        if (draft.id != draftId) throw StaleTemplateException()
        remove("template_draft", draftId)
        put("project", projectId, projectId, project.copy(project = project.project.copy(preparationStatus = if (project.project.templateVersionId == null) PreparationStatus.AWAITING_SAMPLE else PreparationStatus.TEMPLATE_CONFIRMED, updatedAtEpochMillis = nextTime(project.project.updatedAtEpochMillis))))
    } }
}

internal class DesktopSubmissionRepository(private val db: DesktopRepositories) : SubmissionRepository {
    override fun observeSubmissions(projectId: String) = db.observe { list<Submission>("submission", projectId).sortedBy { it.number } }
    override fun observeDrafts(projectId: String) = db.observe { list<SubmissionDraft>("submission_draft", projectId) }
    override fun observeDraft(draftId: String) = db.observe { get<SubmissionDraft>("submission_draft", draftId) }
    override fun observeRevisions(submissionId: String) = db.observe { list<SubmissionRevision>("revision").filter { it.submissionId == submissionId }.sortedBy { it.number } }
    override fun observeJobs(projectId: String) = db.observe { list<ProcessingJob>("preparation", projectId).sortedBy { it.createdAtEpochMillis } }
    override fun observeCollectionState(projectId: String) = db.observe { collection(projectId) }
    override suspend fun getSubmission(id: String) = db.read { get<Submission>("submission", id) }
    override suspend fun getDraft(id: String) = db.read { get<SubmissionDraft>("submission_draft", id) }
    override suspend fun getDraftForSubmission(submissionId: String) = db.read { list<SubmissionDraft>("submission_draft").singleOrNull { it.submissionId == submissionId } }
    override suspend fun getRevision(id: String) = db.read { get<SubmissionRevision>("revision", id) }
    override suspend fun beginSubmission(projectId: String) = db.write {
        val template = readyTemplate(projectId); collecting(projectId)
        list<SubmissionDraft>("submission_draft", projectId).find { it.baseRevisionId == null } ?: newDraft(projectId, template, false)
    }
    private fun Records.newDraft(projectId: String, template: FormSchemaVersion, sample: Boolean): SubmissionDraft {
        val project = requireProject(projectId)
        val submission = Submission(newId(), projectId, (list<Submission>("submission", projectId).maxOfOrNull { it.number } ?: 0) + 1, template.id, project.project.numberingStrategy, System.currentTimeMillis(), sourceSampleVersionId = template.id.takeIf { sample })
        val draft = SubmissionDraft(newId(), submission.id, projectId, template.id, pages = if (sample) template.document.pages.sortedBy { it.position }.map { PageRevision(it.id, it.copy(id = newId())) } else emptyList())
        put("submission", submission.id, projectId, submission); put("submission_draft", draft.id, projectId, draft); queueImages(draft.owner(), draft.pages.map { it.image }, db); return draft
    }
    override suspend fun convertSampleToFirst(projectId: String) = db.write {
        val template = readyTemplate(projectId); collecting(projectId)
        val existing = list<Submission>("submission", projectId).find { it.sourceSampleVersionId != null }
        if (existing != null && !existing.discarded) return@write existing
        require(list<Submission>("submission", projectId).none { it.currentRevisionId != null })
        require(list<SubmissionDraft>("submission_draft", projectId).none { it.baseRevisionId == null })
        if (existing == null) { val draft = newDraft(projectId, template, true); requireNotNull(get<Submission>("submission", draft.submissionId)) }
        else {
            val restored = existing.copy(discarded = false, templateVersionId = template.id, sourceSampleVersionId = template.id, numberingStrategy = requireProject(projectId).project.numberingStrategy)
            val draft = SubmissionDraft(newId(), existing.id, projectId, template.id, pages = template.document.pages.map { PageRevision(it.id, it.copy(id = newId())) })
            put("submission", restored.id, projectId, restored); put("submission_draft", draft.id, projectId, draft); queueImages(draft.owner(), draft.pages.map { it.image }, db); restored
        }
    }
    override suspend fun beginRevision(submissionId: String) = db.write {
        val submission = requireNotNull(get<Submission>("submission", submissionId)); requireProject(submission.projectId); require(!submission.discarded); collecting(submission.projectId)
        list<SubmissionDraft>("submission_draft", submission.projectId).find { it.submissionId == submissionId }?.let { return@write it }
        val revision = requireNotNull(submission.currentRevisionId?.let { get<SubmissionRevision>("revision", it) })
        val draft = SubmissionDraft(newId(), submissionId, submission.projectId, submission.templateVersionId, revision.id, respondentId = revision.respondentId, pages = revision.pages)
        put("submission_draft", draft.id, draft.projectId, draft); queueImages(draft.owner(), draft.pages.map { it.image }, db); copyQuality(revision.owner(), draft.owner(), draft.pages.map { it.image }); draft
    }
    override suspend fun saveDraft(draft: SubmissionDraft) = db.write {
        requireProject(draft.projectId); val old = get<SubmissionDraft>("submission_draft", draft.id) ?: throw StaleSubmissionException()
        if (old.copy(respondentId = draft.respondentId, pages = draft.pages, updatedAtEpochMillis = draft.updatedAtEpochMillis) != draft) throw StaleSubmissionException()
        val submission = requireNotNull(get<Submission>("submission", draft.submissionId)); if (submission.currentRevisionId != draft.baseRevisionId || submission.discarded) throw StaleSubmissionException()
        val template = requireNotNull(get<FormSchemaVersion>("template", draft.templateVersionId))
        val structural = SubmissionValidation.issues(draft, submission, template, SubmissionConfirmation(true, true)).filter { it.code in setOf(SubmissionIssueCode.UNKNOWN_SLOT, SubmissionIssueCode.DUPLICATE_SLOT, SubmissionIssueCode.INVALID_PAGE) }
        if (structural.isNotEmpty()) throw SubmissionValidationException(structural)
        val base = draft.baseRevisionId?.let { get<SubmissionRevision>("revision", it)?.pages }.orEmpty()
        draft.pages.forEach { candidate -> (old.pages + base).filter { it.image.id == candidate.image.id && it.image.revision == candidate.image.revision }.takeIf { it.isNotEmpty() }?.let { evidence ->
            if (evidence.none { it.image.copy(processing = candidate.image.processing) == candidate.image }) throw StaleSubmissionException()
        } }
        val saved = draft.copy(editRevision = old.editRevision + 1, updatedAtEpochMillis = nextTime(old.updatedAtEpochMillis)); put("submission_draft", saved.id, saved.projectId, saved); queueImages(saved.owner(), saved.pages.map { it.image }, db); saved
    }
    override suspend fun discardDraft(draftId: String, expectedEditRevision: Int) { db.write {
        val draft = get<SubmissionDraft>("submission_draft", draftId) ?: return@write; active(draft.projectId)
        if (draft.editRevision != expectedEditRevision) throw StaleSubmissionException()
        remove("submission_draft", draftId)
        if (draft.baseRevisionId == null) { val submission = requireNotNull(get<Submission>("submission", draft.submissionId)); put("submission", submission.id, submission.projectId, submission.copy(discarded = true)) }
    } }
    override suspend fun submitSubmission(draftId: String, expectedEditRevision: Int, confirmation: SubmissionConfirmation) = db.write {
        list<SubmissionRevision>("revision").find { it.sourceDraftId == draftId }?.let { prior -> active(prior.projectId); if (prior.sourceEditRevision != expectedEditRevision || prior.confirmation != confirmation) throw StaleSubmissionException(); return@write prior }
        val draft = get<SubmissionDraft>("submission_draft", draftId) ?: throw StaleSubmissionException(); requireProject(draft.projectId)
        if (draft.editRevision != expectedEditRevision) throw StaleSubmissionException()
        val submission = requireNotNull(get<Submission>("submission", draft.submissionId)); if (submission.currentRevisionId != draft.baseRevisionId || submission.discarded) throw StaleSubmissionException()
        if (draft(draft.projectId) != null) throw PendingTemplateDraftException()
        val template = requireNotNull(get<FormSchemaVersion>("template", draft.templateVersionId))
        val others = list<Submission>("submission", draft.projectId).filter { it.id != submission.id && it.currentRevisionId != null }
        val repeated = if (draft.respondentId.isBlank()) emptyList() else others.filter { it.respondentId == draft.respondentId }.map { it.id }
        val issues = SubmissionValidation.issues(draft, submission, template, confirmation, repeated).toMutableList()
        if (!confirmation.duplicatePageSourcesConfirmed) {
            val duplicates = others.mapNotNull { other -> val revision = get<SubmissionRevision>("revision", other.currentRevisionId!!)!!
                val matches = draft.pages.filter { a -> revision.pages.any { b -> a.image.asset.sha256.equals(b.image.asset.sha256, true) && a.image.sourcePageIndex == b.image.sourcePageIndex } }.map { it.templatePageId }
                if (matches.isEmpty()) null else other.id to matches
            }
            if (duplicates.isNotEmpty()) issues += SubmissionIssue(SubmissionIssueCode.DUPLICATE_PAGE_SOURCE, duplicates.flatMap { it.second }.distinct(), duplicates.map { it.first })
        }
        draft.pages.filterNot { db.pageAvailable(it.image) }.forEach { issues += SubmissionIssue(SubmissionIssueCode.FILE_UNAVAILABLE, listOf(it.templatePageId)) }
        if (issues.isNotEmpty()) throw SubmissionValidationException(issues)
        checkQuality(draft.owner(), draft.pages.map { it.image }, db)
        val old = draft.baseRevisionId?.let { get<SubmissionRevision>("revision", it) }
        val slots = template.document.pages.sortedBy { it.position }.map { it.id }
        val revision = SubmissionRevision(newId(), submission.id, submission.projectId, template.id, (old?.number ?: 0) + 1, draft.id, draft.editRevision, draft.respondentId, draft.pages.sortedBy { slots.indexOf(it.templatePageId) }, slots.filterNot { slot -> draft.pages.any { it.templatePageId == slot } }, confirmation, System.currentTimeMillis())
        put("revision", revision.id, revision.projectId, revision)
        put("submission", submission.id, submission.projectId, submission.copy(currentRevisionId = revision.id, respondentId = revision.respondentId))
        queueImages(revision.owner(), revision.pages.map { it.image }, db)
        copyQuality(draft.owner(), revision.owner(), revision.pages.map { it.image })
        inheritAnswers(old, revision)
        list<ProcessingJob>("preparation", submission.projectId).filter { it.submissionId == submission.id && it.status != PreparationJobStatus.READY }.forEach { put("preparation", it.id, it.projectId, it.copy(status = PreparationJobStatus.SUPERSEDED, runToken = null, processSessionId = null)) }
        list<RecognitionJob>("recognition", submission.projectId).filter { it.submissionId == submission.id && it.status != RecognitionJobStatus.READY }.forEach { put("recognition", it.id, it.projectId, it.copy(status = RecognitionJobStatus.SUPERSEDED, runToken = null, processSessionId = null)) }
        val job = ProcessingJob(newId(), submission.projectId, submission.id, revision.id, PreparationJobStatus.QUEUED, createdAtEpochMillis = nextTime(list<ProcessingJob>("preparation").maxOfOrNull { it.createdAtEpochMillis } ?: 0))
        put("preparation", job.id, job.projectId, job); remove("submission_draft", draftId); wake(job.projectId); revision
    }
    override suspend fun setCollectionMode(projectId: String, mode: CollectionMode) { db.write {
        requireProject(projectId); val old = collection(projectId)
        put("collection", projectId, projectId, old.copy(mode = mode, policyGeneration = old.policyGeneration + 1, wakeGeneration = old.wakeGeneration + if (mode == CollectionMode.BACKGROUND_ALLOWED) 1 else 0, schedulingError = null))
    } }
    override suspend fun retryJob(jobId: String) { db.write {
        val job = requireNotNull(get<ProcessingJob>("preparation", jobId)); active(job.projectId)
        require(get<Submission>("submission", job.submissionId)?.currentRevisionId == job.submissionRevisionId)
        if (job.status in listOf(PreparationJobStatus.FAILED, PreparationJobStatus.PAUSED)) { put("preparation", job.id, job.projectId, job.copy(status = PreparationJobStatus.QUEUED, runToken = null, processSessionId = null, error = null)); wake(job.projectId) }
    } }
}

internal fun Records.inheritAnswers(old: SubmissionRevision?, next: SubmissionRevision) {
    if (old == null) return
    val answers = latestAnswers(old.id) ?: return
    val questions = get<FormSchemaVersion>("template", next.templateVersionId)!!.document.questions.associateBy { "question:${it.id}" }
    val unchanged = next.pages.filter { page -> old.pages.any { it.templatePageId == page.templatePageId && sameEvidence(it.image, page.image) } }.map { it.templatePageId }.toSet()
    val inherited = answers.answers.mapNotNull { answer -> val question = questions[answer.targetKey]
        when { question?.pageId in next.missingTemplatePageIds -> AnswerValue(answer.targetKey, AnswerState.NOT_CAPTURED)
            question == null || question.pageId in unchanged -> answer.copy(source = AnswerSource.INHERITED)
            else -> null }
    }
    if (inherited.isNotEmpty()) { val revision = AnswerSetRevision(newId(), next.projectId, next.submissionId, next.id, 1, "inherit:${next.id}", inherited); put("answers", revision.id, next.projectId, revision) }
}
