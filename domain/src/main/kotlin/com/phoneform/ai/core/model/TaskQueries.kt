package com.phoneform.ai.core.model

enum class TaskListFilter { PENDING, FAILED, COMPLETED, ALL }

/** Search is a literal Unicode case-insensitive project name or submission number substring. */
data class TaskPageQuery(val search: String = "", val filter: TaskListFilter = TaskListFilter.PENDING,
    val pageIndex: Int = 0, val pageSize: Int = 50) {
    fun normalized(): TaskPageQuery {
        require(pageIndex >= 0 && pageSize in 1..100) { "TASK_PAGE_INVALID" }
        return copy(search = search.trim())
    }
}

/** Global counts and the filtered page are read together; deletions clamp the requested page. */
data class TaskQueryPage(val query: TaskPageQuery, val items: List<BackgroundTaskSummary> = emptyList(),
    val totalMatched: Int = 0, val total: Int = 0, val statusCounts: Map<String, Int> = emptyMap())
