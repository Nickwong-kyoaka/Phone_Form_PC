package com.phoneform.ai.core.model

enum class ReviewListFilter { PENDING, ALL, CONFIRMED, EXCEPTIONS }

/** Stable number/id ordering. Search is a literal, Unicode case-insensitive substring. */
data class ReviewPageQuery(val search: String = "", val filter: ReviewListFilter = ReviewListFilter.PENDING,
    val pageIndex: Int = 0, val pageSize: Int = 50) {
    fun normalized(): ReviewPageQuery {
        require(pageIndex >= 0 && pageSize in 1..100) { "REVIEW_PAGE_INVALID" }
        return copy(search = search.trim())
    }
}

/** Counts and items come from the same database read; pageIndex is clamped after deletions. */
data class ReviewPage(val query: ReviewPageQuery, val items: List<ReviewListSummary> = emptyList(),
    val totalMatched: Int = 0, val totalSubmitted: Int = 0, val pendingCount: Int = 0,
    val confirmedCount: Int = 0, val exceptionCount: Int = 0)

data class ReviewNeighbors(val previousSubmissionId: String? = null, val nextSubmissionId: String? = null,
    val totalMatched: Int = 0, val position: Int? = null)

/** A batch action must not silently switch to a newer revision or job after its confirmation. */
data class RecognitionRerunTarget(val submissionId: String, val submissionRevisionId: String,
    val currentJobId: String?, val templateVersionId: String)

enum class BackgroundTaskPhase { IMAGE, PREPARATION, RECOGNITION }
data class BackgroundTaskSummary(val id: String, val projectId: String, val phase: BackgroundTaskPhase,
    val status: String, val submissionNumber: Int? = null, val pageNumber: Int? = null,
    val error: String? = null, val retrySupported: Boolean = false, val createdAtEpochMillis: Long = 0)
data class BackgroundTaskPage(val items: List<BackgroundTaskSummary>, val total: Int,
    val statusCounts: Map<String, Int>)

/** Export history must not eagerly load the frozen four-sheet cell payloads. */
data class ExportListSummary(val id: String, val projectId: String, val projectName: String,
    val outputVersion: Int, val includeUnresolved: Boolean, val createdAtEpochMillis: Long,
    val recordCount: Int, val excludedCount: Int)
