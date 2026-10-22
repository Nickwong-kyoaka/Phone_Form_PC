package com.phoneform.desktop.data

import com.google.gson.Gson
import com.phoneform.ai.core.model.*
import com.phoneform.desktop.security.SecureWorkspace
import java.sql.Connection
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** JSON is stored only inside the authenticated encrypted database. */
internal class Records(val connection: Connection) {
    val lists = mutableMapOf<Pair<String, String?>, List<Any>>()
    inline fun <reified T> get(kind: String, id: String): T? = connection.prepareStatement("SELECT payload FROM pc_records WHERE kind=? AND id=?").use {
        it.setString(1, kind); it.setString(2, id)
        it.executeQuery().use { rows -> if (rows.next()) RecordCodec.decode(rows.getString(1), T::class.java) else null }
    }
    inline fun <reified T> list(kind: String, projectId: String? = null): List<T> {
        @Suppress("UNCHECKED_CAST")
        (lists[kind to projectId] as? List<T>)?.let { return it }
        val sql = "SELECT payload FROM pc_records WHERE kind=?" + if (projectId != null) " AND project_id=?" else ""
        val values = connection.prepareStatement(sql).use {
            it.setString(1, kind); if (projectId != null) it.setString(2, projectId)
            it.executeQuery().use { rows -> buildList { while (rows.next()) add(RecordCodec.decode(rows.getString(1), T::class.java)) } }
        }
        @Suppress("UNCHECKED_CAST")
        lists[kind to projectId] = values as List<Any>
        return values
    }
    fun put(kind: String, id: String, projectId: String?, value: Any) {
        lists.keys.filter { it.first == kind }.toList().forEach(lists::remove)
        connection.prepareStatement("INSERT INTO pc_records(kind,id,project_id,payload) VALUES(?,?,?,?) ON CONFLICT(kind,id) DO UPDATE SET project_id=excluded.project_id,payload=excluded.payload").use {
            it.setString(1, kind); it.setString(2, id); it.setString(3, projectId); it.setString(4, RecordCodec.encode(value)); it.executeUpdate()
        }
        // Immutable history keeps operation IDs; compact heads avoid loading that history on every edit.
        when (value) {
            is AnswerSetRevision -> { put("answer_head", value.submissionRevisionId, projectId, RecordReference(id)); put("answer_operation", value.operationId, projectId, RecordReference(id)) }
            is ReviewConfirmation -> { put("confirmation_answer", value.answerRevisionId, projectId, RecordReference(id)); put("confirmation_operation", value.operationId, projectId, RecordReference(id)) }
            is ProcessingJob -> put("preparation_head", value.submissionRevisionId, projectId, RecordReference(id))
            // Recognition heads are changed only by explicit creation/CAS operations. Updating an
            // old job's status must never make that job current again.
            is PageProcessingJob -> put("image_job_head", "${value.owner.kind}:${value.owner.id}:${value.owner.revision}:${value.page.id}:${value.inputFingerprint}", projectId, RecordReference(id))
        }
    }
    fun remove(kind: String, id: String) {
        lists.keys.filter { it.first == kind }.toList().forEach(lists::remove)
        connection.prepareStatement("DELETE FROM pc_records WHERE kind=? AND id=?").use {
            it.setString(1, kind); it.setString(2, id); it.executeUpdate()
        }
    }
    fun active(projectId: String) {
        if (get<ProjectDeletionStatus>("deletion", projectId) != null) throw ProjectDeletedException()
    }
    fun project(id: String): ProjectDetails? = get("project", id)
    fun requireProject(id: String): ProjectDetails { active(id); return requireNotNull(project(id)) { "PROJECT_NOT_FOUND" } }
    fun locked(id: String): Boolean = list<Submission>("submission", id).any { !it.discarded }
    fun draft(id: String): TemplateDraft? = list<TemplateDraft>("template_draft", id).singleOrNull()
    fun collection(id: String) = (get<ProjectCollectionState>("collection", id) ?: ProjectCollectionState(id)).copy(locked = locked(id))
    fun wake(id: String) { val state = collection(id); put("collection", id, id, state.copy(wakeGeneration = state.wakeGeneration + 1)) }
    fun collecting(id: String) {
        active(id); val state = collection(id)
        put("collection", id, id, state.copy(mode = CollectionMode.COLLECTING,
            policyGeneration = state.policyGeneration + if (state.mode != CollectionMode.COLLECTING) 1 else 0, schedulingError = null))
    }
    fun readyTemplate(id: String): FormSchemaVersion {
        val project = requireProject(id)
        if (draft(id) != null) throw PendingTemplateDraftException()
        return requireNotNull(project.project.templateVersionId?.let { get<FormSchemaVersion>("template", it) }) { "TEMPLATE_REQUIRED" }
    }
    fun prep(revisionId: String): ProcessingJob? = get<RecordReference>("preparation_head", revisionId)?.let { get("preparation", it.id) }
    fun recognition(revisionId: String): RecognitionJob? = get<RecordReference>("recognition_head", revisionId)?.let { get("recognition", it.id) }
    fun latestAnswers(revisionId: String): AnswerSetRevision? = get<RecordReference>("answer_head", revisionId)?.let { get("answers", it.id) }
    fun answerOperation(operationId: String): AnswerSetRevision? = get<RecordReference>("answer_operation", operationId)?.let { get("answers", it.id) }
    fun confirmationOperation(operationId: String): ReviewConfirmation? = get<RecordReference>("confirmation_operation", operationId)?.let { get("confirmation", it.id) }
    fun confirmationForAnswer(answerId: String): ReviewConfirmation? = get<RecordReference>("confirmation_answer", answerId)?.let { get("confirmation", it.id) }
    fun imageJob(owner: PageProcessingOwner, pageId: String, fingerprint: String): PageProcessingJob? = get<RecordReference>("image_job_head", "${owner.kind}:${owner.id}:${owner.revision}:$pageId:$fingerprint")?.let { get("image_job", it.id) }
    fun anyRunning(): Boolean = list<ProcessingJob>("preparation").any { it.status == PreparationJobStatus.RUNNING } ||
        list<RecognitionJob>("recognition").any { it.status == RecognitionJobStatus.RUNNING && recognition(it.submissionRevisionId)?.id == it.id } ||
        list<PageProcessingJob>("image_job").any { it.status == PageProcessingStatus.RUNNING }
    fun eligible(projectId: String, foreground: Boolean): Boolean = project(projectId) != null &&
        get<ProjectDeletionStatus>("deletion", projectId) == null && (foreground || collection(projectId).mode == CollectionMode.BACKGROUND_ALLOWED)
    fun enqueueRecognition(revision: SubmissionRevision) {
        val pointer = get<RecordReference>("recognition_head", revision.id)
        if (pointer != null && get<RecognitionJob>("recognition", pointer.id) == null || pointer == null && list<RecognitionJob>("recognition", revision.projectId).any { it.submissionRevisionId == revision.id })
            throw IllegalStateException("RECOGNITION_HEAD_INVALID")
        if (recognition(revision.id) == null && latestAnswers(revision.id)?.let { confirmationForAnswer(it.id) } == null) {
            val profile = profileHead(revision.templateVersionId)
            val supported = profile != null && profile.engineVersion == RecognitionContracts.ENGINE_VERSION && profile.parametersVersion == RecognitionContracts.PARAMETERS_VERSION
            val job = RecognitionJob(newId(), revision.projectId, revision.submissionId, revision.id,
                status = if (supported) RecognitionJobStatus.QUEUED else RecognitionJobStatus.BLOCKED,
                profileId = profile?.id, engineVersion = profile?.engineVersion.orEmpty(),
                inputFingerprint = profile?.let { RecognitionBindings.jobFingerprint(revision, it) }.orEmpty(),
                provenance = if (profile != null) RecognitionProvenance.VERSIONED else RecognitionProvenance.LEGACY_UNBOUND,
                error = if (supported) null else if (profile == null) "RECOGNITION_PROFILE_REQUIRED" else "RECOGNITION_ENGINE_UNAVAILABLE")
            put("recognition", job.id, job.projectId, job)
            put("recognition_head", revision.id, revision.projectId, RecordReference(job.id))
            if (supported) wake(job.projectId)
        }
    }
    companion object { val gson = Gson() }
}

internal fun newId(): String = UUID.randomUUID().toString()
internal fun nextTime(previous: Long) = maxOf(System.currentTimeMillis(), previous + 1)
internal fun sameEvidence(a: SamplePage, b: SamplePage) = a.copy(renderPath = b.renderPath, thumbnailPath = b.thumbnailPath) == b
internal fun SubmissionDraft.owner() = PageProcessingOwner(projectId, PageProcessingOwnerKind.SUBMISSION_DRAFT, id, editRevision)
internal fun SubmissionRevision.owner() = PageProcessingOwner(projectId, PageProcessingOwnerKind.SUBMISSION_REVISION, id, number)
internal fun TemplateDraft.owner() = PageProcessingOwner(projectId, PageProcessingOwnerKind.TEMPLATE_DRAFT, id, revision)

class DesktopRepositories(val workspace: SecureWorkspace) {
    internal fun <T> read(block: Records.() -> T): T = workspace.database.read { Records(it).block() }
    internal fun <T> write(block: Records.() -> T): T = workspace.database.transaction { Records(it).block() }
    internal fun <T> observe(block: Records.() -> T) = workspace.database.changes.map { read(block) }.distinctUntilChanged()
    val projects: ProjectRepository = DesktopProjectRepository(this)
    val templates: TemplateRepository = DesktopTemplateRepository(this)
    val submissions: SubmissionRepository = DesktopSubmissionRepository(this)
    val jobs: ProcessingJobRepository = DesktopProcessingRepository(this)
    val intelligence: IntelligenceRepository = DesktopIntelligenceRepository(this)
    val imageProcessing: ImageProcessingRepository = DesktopImageProcessingRepository(this)
    val settings: SettingsRepository = DesktopSettingsRepository(this)
    val editors: EditorSessionRepository = DesktopEditorRepository(this)
    val maintenance: ProjectMaintenanceRepository = DesktopMaintenanceRepository(this)

    internal fun pageAvailable(page: SamplePage): Boolean = runCatching {
        if (!workspace.assets.exists(page.renderPath) || !workspace.assets.exists(page.thumbnailPath)) return@runCatching false
        val digest = MessageDigest.getInstance("SHA-256"); var count = 0L
        workspace.assets.open(page.asset.relativePath).use { input ->
            val buffer = ByteArray(65536)
            while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size); count += size }
        }
        count == page.asset.byteCount && digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }.equals(page.asset.sha256, true)
    }.getOrDefault(false)

    suspend fun saveEditorSession(id: String, projectId: String?, payload: String, expectedRevision: Int? = null) = editors.save(id, projectId, payload, expectedRevision)
    suspend fun loadEditorSession(id: String) = editors.read(id)
    suspend fun discardEditorSession(id: String) = editors.delete(id)
    suspend fun storageUsage() = maintenance.storageUsage()
    suspend fun deleteProjects(ids: Set<String>, operationId: String): List<ProjectDeletionStatus> {
        require(operationId.isNotBlank())
        write {
            val prior = get<DeletionBatch>("deletion_batch", operationId)
            require(prior == null || prior.projectIds == ids.sorted()) { "OPERATION_ID_REUSED" }
            if (prior == null) put("deletion_batch", operationId, null, DeletionBatch(ids.sorted()))
        }
        maintenance.requestDeletions(ids); maintenance.resumePendingDeletions()
        return read { ids.sorted().map { requireNotNull(get<ProjectDeletionStatus>("deletion", it)) } }
    }
}

internal data class DeletionBatch(val projectIds: List<String>)
internal data class RecordReference(val id: String)
