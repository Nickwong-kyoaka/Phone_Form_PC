package com.phoneform.desktop.data

import com.phoneform.ai.core.model.*
import java.security.MessageDigest

internal fun Records.ownerPages(owner: PageProcessingOwner): List<SamplePage>? {
    if (!eligible(owner.projectId, true)) return null
    return when (owner.kind) {
        PageProcessingOwnerKind.TEMPLATE_DRAFT -> get<TemplateDraft>("template_draft", owner.id)?.takeIf { it.projectId == owner.projectId && it.revision == owner.revision }?.pages
        PageProcessingOwnerKind.SUBMISSION_DRAFT -> get<SubmissionDraft>("submission_draft", owner.id)?.takeIf { draft ->
            draft.projectId == owner.projectId && draft.editRevision == owner.revision && get<Submission>("submission",draft.submissionId)?.let {
                it.projectId == owner.projectId && !it.discarded && it.currentRevisionId == draft.baseRevisionId
            } == true
        }?.pages?.map { it.image }
        PageProcessingOwnerKind.SUBMISSION_REVISION -> get<SubmissionRevision>("revision", owner.id)?.takeIf { revision ->
            revision.projectId == owner.projectId && revision.number == owner.revision && get<Submission>("submission",revision.submissionId)?.let {
                it.projectId == owner.projectId && !it.discarded && it.currentRevisionId == revision.id
            } == true
        }?.pages?.map { it.image }
    }
}
internal fun pageHash(db: DesktopRepositories, page: SamplePage): String = MessageDigest.getInstance("SHA-256").digest(db.workspace.assets.readBytes(page.renderPath)).joinToString("") { "%02x".format(it.toInt() and 255) }
internal fun decisionId(owner: PageProcessingOwner, pageId: String, fingerprint: String) = "${owner.kind}:${owner.id}:$pageId:$fingerprint"
internal fun Records.copyQuality(from: PageProcessingOwner, to: PageProcessingOwner, pages: List<SamplePage>) {
    pages.forEach { page ->
        val fingerprint = PageProcessingFingerprint.of(page)
        get<PageQualityKeepDecision>("quality_keep", decisionId(from, page.id, fingerprint))?.let { decision ->
            put("quality_keep", decisionId(to, page.id, fingerprint), to.projectId, decision.copy(owner = to))
        }
    }
}
internal fun Records.queueImages(owner: PageProcessingOwner, pages: List<SamplePage>, db: DesktopRepositories) {
    pages.forEach { page ->
        val fingerprint = PageProcessingFingerprint.of(page)
        if (imageJob(owner, page.id, fingerprint) == null) {
            val sha = runCatching { pageHash(db, page) }.getOrDefault("")
            val job = PageProcessingJob(newId(), owner, page, fingerprint, inputSha256 = sha)
            put("image_job", job.id, owner.projectId, job)
        }
    }
}
internal fun Records.checkQuality(owner: PageProcessingOwner, pages: List<SamplePage>, db: DesktopRepositories) {
    val warnings = pages.filter { page ->
        val fingerprint = PageProcessingFingerprint.of(page)
        val jobs = list<PageProcessingJob>("image_job", owner.projectId).filter { it.owner.kind == owner.kind && it.owner.id == owner.id && it.page.id == page.id && it.inputFingerprint == fingerprint }
        val result = get<PageProcessingResult>("image_result", fingerprint)
        val decision = get<PageQualityKeepDecision>("quality_keep", decisionId(owner, page.id, fingerprint))
        val actualSha = runCatching { pageHash(db, page) }.getOrNull()
        val hasWarnings = result?.report?.let { it.inputSha256 != actualSha || it.assessment == PageQualityAssessment.UNKNOWN || it.issues.any { issue -> issue.severity != PageQualitySeverity.INFO } || page.processing.mode == PageProcessingMode.AUTO_LIGHT && result.enhanced == null } ?: jobs.isNotEmpty()
        hasWarnings && (decision == null || decision.inputSha256.isEmpty() || decision.inputSha256 != actualSha)
    }.map { it.id }
    if (warnings.isNotEmpty()) throw PageQualityAcknowledgementRequired(warnings)
}

internal class DesktopImageProcessingRepository(private val db: DesktopRepositories) : ImageProcessingRepository {
    override suspend fun request(owner: PageProcessingOwner, pageId: String) = db.write {
        active(owner.projectId); val page = ownerPages(owner)?.singleOrNull { it.id == pageId } ?: throw StalePageProcessingException()
        queueImages(owner, listOf(page), db)
        requireNotNull(imageJob(owner, page.id, PageProcessingFingerprint.of(page)))
    }
    override fun observeJobs() = db.observe { list<PageProcessingJob>("image_job").sortedBy { it.createdAtEpochMillis } }
    override fun observePage(owner: PageProcessingOwner, pageId: String) = db.observe { pageState(owner, pageId) }
    override suspend fun getPageState(owner: PageProcessingOwner, pageId: String) = db.read { pageState(owner, pageId) }
    private fun Records.pageState(owner: PageProcessingOwner, pageId: String): PageProcessingState {
        val page = ownerPages(owner)?.find { it.id == pageId } ?: return PageProcessingState(null, null, null)
        val fingerprint = PageProcessingFingerprint.of(page)
        return PageProcessingState(imageJob(owner, pageId, fingerprint), get("image_result", fingerprint), get("quality_keep", decisionId(owner, pageId, fingerprint)))
    }
    override suspend fun getResult(page: SamplePage) = db.read { get<PageProcessingResult>("image_result", PageProcessingFingerprint.of(page)) }
    override suspend fun initialize(processSessionId: String) { require(processSessionId.isNotBlank()); db.write {
        list<PageProcessingJob>("image_job").filter { it.status == PageProcessingStatus.RUNNING && it.processSessionId != processSessionId }.forEach { put("image_job", it.id, it.owner.projectId, it.copy(status = PageProcessingStatus.PAUSED, runToken = null, processSessionId = null, error = "INTERRUPTED")) }
    } }
    override suspend fun claim(processSessionId: String, appInForeground: Boolean, owner: PageProcessingOwner?, pageId: String?) = db.write {
        require(processSessionId.isNotBlank()); if (anyRunning()) return@write null
        val candidates = list<PageProcessingJob>("image_job").filter { it.status in listOf(PageProcessingStatus.QUEUED, PageProcessingStatus.PAUSED) && (owner == null || it.owner == owner) && (pageId == null || it.page.id == pageId) && eligible(it.owner.projectId, appInForeground) }.sortedBy { it.createdAtEpochMillis }
        candidates.forEach { candidate ->
            if (ownerPages(candidate.owner)?.any { it.id == candidate.page.id && PageProcessingFingerprint.of(it) == candidate.inputFingerprint } != true) { put("image_job", candidate.id, candidate.owner.projectId, candidate.copy(status = PageProcessingStatus.SUPERSEDED)); return@forEach }
            val sha = runCatching { pageHash(db, candidate.page) }.getOrDefault("")
            val claimed = candidate.copy(status = PageProcessingStatus.RUNNING, processSessionId = processSessionId, runToken = newId(), attempt = candidate.attempt + 1, error = null, inputSha256 = sha)
            put("image_job", claimed.id, claimed.owner.projectId, claimed); return@write claimed
        }
        null
    }
    private fun Records.valid(job: PageProcessingJob): PageProcessingJob? {
        if (job.runToken == null || job.processSessionId == null || !eligible(job.owner.projectId, true)) return null
        return get<PageProcessingJob>("image_job", job.id)?.takeIf { it.status == PageProcessingStatus.RUNNING && it.runToken == job.runToken && it.processSessionId == job.processSessionId && it.inputFingerprint == job.inputFingerprint && ownerPages(it.owner)?.any { page -> page.id == it.page.id && PageProcessingFingerprint.of(page) == it.inputFingerprint } == true }
    }
    override suspend fun isClaimValid(job: PageProcessingJob, appInForeground: Boolean) = db.read { valid(job) != null && eligible(job.owner.projectId, appInForeground) }
    override suspend fun complete(job: PageProcessingJob, result: PageProcessingResult) = db.write {
        val stored = valid(job) ?: return@write false
        require(result.inputFingerprint == stored.inputFingerprint && result.report.inputFingerprint == stored.inputFingerprint && stored.inputSha256.isNotEmpty() && result.report.inputSha256 == stored.inputSha256 && pageHash(db, stored.page) == stored.inputSha256)
        require(result.report.issues.all { it.region?.isValid() != false } && result.report.metrics.values.all { it.isFinite() })
        result.enhanced?.let {
            require(it.inputFingerprint == stored.inputFingerprint && it.inputSha256 == stored.inputSha256 && it.processingSpec == stored.page.processing && it.width == stored.page.width && it.height == stored.page.height && it.byteCount > 0)
            require(db.workspace.assets.exists(it.relativePath) && db.workspace.assets.exists(it.thumbnailPath))
            val bytes = db.workspace.assets.readBytes(it.relativePath)
            require(bytes.size.toLong() == it.byteCount && MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { b -> "%02x".format(b.toInt() and 255) }.equals(it.sha256, true))
        }
        put("image_result", result.inputFingerprint, null, result)
        put("image_job", stored.id, stored.owner.projectId, stored.copy(status = PageProcessingStatus.READY, runToken = null, processSessionId = null, error = null)); true
    }
    override suspend fun pause(job: PageProcessingJob, reason: String) { db.write { valid(job)?.let { put("image_job", it.id, it.owner.projectId, it.copy(status = PageProcessingStatus.PAUSED, error = reason, runToken = null, processSessionId = null)) } } }
    override suspend fun fail(job: PageProcessingJob, reason: String) { db.write { valid(job)?.let { put("image_job", it.id, it.owner.projectId, it.copy(status = PageProcessingStatus.FAILED, error = reason, runToken = null, processSessionId = null)) } } }
    override suspend fun retry(jobId: String) { db.write {
        val job = requireNotNull(get<PageProcessingJob>("image_job", jobId)); active(job.owner.projectId)
        if (imageJob(job.owner,job.page.id,job.inputFingerprint)?.id != job.id) throw StalePageProcessingException()
        if (ownerPages(job.owner)?.any { it.id == job.page.id && PageProcessingFingerprint.of(it) == job.inputFingerprint } != true) throw StalePageProcessingException()
        if (job.status in listOf(PageProcessingStatus.PAUSED, PageProcessingStatus.FAILED)) put("image_job", job.id, job.owner.projectId, job.copy(status = PageProcessingStatus.QUEUED, error = null, runToken = null, processSessionId = null))
    } }
    override suspend fun keepDespiteWarning(owner: PageProcessingOwner, pageId: String, inputFingerprint: String, reason: String) = db.write { keep(owner, pageId, inputFingerprint, reason) }
    override suspend fun keepAllWarnings(owner: PageProcessingOwner, fingerprints: Map<String, String>, reason: String) = db.write { fingerprints.map { (pageId, fingerprint) -> keep(owner, pageId, fingerprint, reason) } }
    private fun Records.keep(owner: PageProcessingOwner, pageId: String, fingerprint: String, reason: String): PageQualityKeepDecision {
        active(owner.projectId); require(reason.isNotBlank())
        val page = ownerPages(owner)?.find { it.id == pageId } ?: throw StalePageProcessingException()
        if (PageProcessingFingerprint.of(page) != fingerprint) throw StalePageProcessingException()
        val result = PageQualityKeepDecision(owner, pageId, fingerprint, reason, inputSha256 = pageHash(db, page))
        put("quality_keep", decisionId(owner, pageId, fingerprint), owner.projectId, result); return result
    }
}
