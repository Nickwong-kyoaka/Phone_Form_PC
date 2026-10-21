package com.phoneform.ai.core.model

import java.io.Serializable
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow

enum class PageProcessingMode { OFF, AUTO_LIGHT }
data class PageProcessingSpec(val mode: PageProcessingMode = PageProcessingMode.OFF,
    val parameterVersion: Int = 1) : Serializable
typealias PageProcessingConfig = PageProcessingSpec

/** Enhancement preserves the base page unit square and never changes its geometry revision. */
object PageProcessingFingerprint {
    fun base(page: SamplePage): String = hash(listOf(page.id, page.revision, page.asset.sha256.lowercase(), page.asset.kind.name,
        page.sourcePageIndex, page.width, page.height, page.transform.rotationDegrees,
        page.transform.sourceToPage.joinToString(",") { it.toBits().toString() }).joinToString("|"))
    fun of(page: SamplePage): String = hash("${base(page)}|${page.processing.mode}|${page.processing.parameterVersion}")
    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
}

enum class PageProcessingOwnerKind { TEMPLATE_DRAFT, SUBMISSION_DRAFT, SUBMISSION_REVISION }
data class PageProcessingOwner(val projectId: String, val kind: PageProcessingOwnerKind,
    val id: String, val revision: Int)
enum class PageProcessingStatus { QUEUED, RUNNING, PAUSED, FAILED, READY, SUPERSEDED }
enum class PageQualitySeverity { INFO, WARNING, SEVERE }
enum class PageQualityIssueCode { BLUR, GLARE, SHADOW, LOW_LIGHT, LOW_CONTRAST, CLIPPED_CONTENT, LOW_RESOLUTION, ENHANCEMENT_UNAVAILABLE }
enum class PageQualityAssessment { UNKNOWN, ASSESSED }
data class PageQualityIssue(val code: PageQualityIssueCode, val severity: PageQualitySeverity,
    val region: NormalizedRect? = null, val detail: String = "")
data class PageQualityReport(val inputFingerprint: String, val engineVersion: String,
    val issues: List<PageQualityIssue> = emptyList(), val metrics: Map<String, Double> = emptyMap(),
    val assessment: PageQualityAssessment = PageQualityAssessment.UNKNOWN, val inputSha256: String = "")
data class EnhancedPageArtifact(val relativePath: String, val thumbnailPath: String,
    val sha256: String, val byteCount: Long, val width: Int, val height: Int,
    val inputFingerprint: String, val engineVersion: String, val inputSha256: String = "",
    val processingSpec: PageProcessingSpec = PageProcessingSpec())
data class PageProcessingResult(val inputFingerprint: String, val report: PageQualityReport,
    val enhanced: EnhancedPageArtifact? = null, val createdAtEpochMillis: Long = System.currentTimeMillis())
data class PageProcessingJob(val id: String, val owner: PageProcessingOwner, val page: SamplePage,
    val inputFingerprint: String, val status: PageProcessingStatus = PageProcessingStatus.QUEUED,
    val processSessionId: String? = null, val runToken: String? = null,
    val attempt: Int = 0, val error: String? = null, val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val inputSha256: String = "")
data class PageQualityKeepDecision(val owner: PageProcessingOwner, val pageId: String,
    val inputFingerprint: String, val reason: String, val decidedAtEpochMillis: Long = System.currentTimeMillis(),
    val inputSha256: String = "")
data class PageProcessingState(val job: PageProcessingJob?, val result: PageProcessingResult?,
    val keepDecision: PageQualityKeepDecision?)
class StalePageProcessingException : IllegalStateException("Page processing input changed; reload")
class PageQualityAcknowledgementRequired(val pageIds: List<String>) : IllegalStateException("Review page quality warnings before continuing")

interface ImageProcessingRepository {
    /** Fetches the saved owner/page in Room; callers cannot enqueue an unsaved bitmap or page. */
    suspend fun request(owner: PageProcessingOwner, pageId: String): PageProcessingJob
    fun observeJobs(): Flow<List<PageProcessingJob>>
    fun observePage(owner: PageProcessingOwner, pageId: String): Flow<PageProcessingState>
    suspend fun getPageState(owner: PageProcessingOwner, pageId: String): PageProcessingState
    suspend fun getResult(page: SamplePage): PageProcessingResult?
    suspend fun initialize(processSessionId: String)
    suspend fun claim(processSessionId: String, appInForeground: Boolean,
        owner: PageProcessingOwner? = null, pageId: String? = null): PageProcessingJob?
    suspend fun isClaimValid(job: PageProcessingJob, appInForeground: Boolean): Boolean
    suspend fun complete(job: PageProcessingJob, result: PageProcessingResult): Boolean
    suspend fun pause(job: PageProcessingJob, reason: String)
    suspend fun fail(job: PageProcessingJob, reason: String)
    suspend fun retry(jobId: String)
    suspend fun keepDespiteWarning(owner: PageProcessingOwner, pageId: String,
        inputFingerprint: String, reason: String): PageQualityKeepDecision
    suspend fun keepAllWarnings(owner: PageProcessingOwner, fingerprints: Map<String, String>,
        reason: String): List<PageQualityKeepDecision>
}

/** Null region means the whole selected page; null run scope is the legacy whole-document run. */
data class TemplateAnalysisScope(val pageId: String, val region: NormalizedRect? = null) : Serializable
