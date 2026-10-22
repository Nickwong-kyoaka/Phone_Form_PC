package com.phoneform.desktop

import com.phoneform.ai.core.model.ReviewPage
import com.phoneform.ai.core.model.ReviewPageQuery
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

data class ReviewListState(val projectId: String? = null, val query: ReviewPageQuery = ReviewPageQuery(),
    val page: ReviewPage? = null, val loading: Boolean = false, val error: String? = null,
    val stale: Boolean = false, val dataRevision: Long = -1)

/** Cancelling a coroutine cannot cancel a JDBC read already in progress. Check the request again
 * before every publication, including failure/finally, so an older read cannot win a race. */
internal class ReviewPageLoader(private val scope: CoroutineScope,
    private val read: suspend (String, ReviewPageQuery) -> ReviewPage,
    private val errorCode: (Exception) -> String) {
    private val mutable = MutableStateFlow(ReviewListState())
    val state: StateFlow<ReviewListState> = mutable.asStateFlow()
    private val generation = AtomicLong()
    private var worker: Job? = null
    @Volatile private var requested: Triple<String?, ReviewPageQuery, Long>? = null
    private var searchReadyAtNanos = 0L

    @Synchronized private fun publish(ticket: Long, request: Triple<String?, ReviewPageQuery, Long>,
        change: (ReviewListState) -> ReviewListState) {
        if (generation.get() == ticket && requested?.let { it.first == request.first && it.second == request.second } == true)
            mutable.value = change(mutable.value)
    }

    @Synchronized fun request(projectId: String?, query: ReviewPageQuery, dataRevision: Long,
        searchDelayMillis: Long = 0, force: Boolean = false) {
        val next = Triple(projectId, query.normalized(), dataRevision)
        if (!force && next == requested) return
        val sameQuery = requested?.let { it.first == next.first && it.second == next.second } == true
        val sameSearch = requested?.let { it.first == next.first && it.second.search == next.second.search } == true
        if (force || !sameSearch) searchReadyAtNanos = if (searchDelayMillis > 0)
            System.nanoTime() + searchDelayMillis * 1_000_000 else 0L
        val waitMillis = ((searchReadyAtNanos - System.nanoTime()) / 1_000_000).coerceAtLeast(0)
        requested = next
        if (!force && sameQuery && worker?.isActive == true) return
        val ticket = generation.incrementAndGet()
        worker?.cancel()
        if (projectId == null) { mutable.value = ReviewListState(); return }
        val previous = mutable.value
        mutable.value = ReviewListState(projectId, next.second,
            previous.page.takeIf { previous.projectId == projectId }, true, null,
            previous.page != null && previous.projectId == projectId && previous.page.query != next.second, dataRevision)
        worker = scope.launch {
            try {
                if (waitMillis > 0) delay(waitMillis)
                var current = synchronized(this@ReviewPageLoader) {
                    requested?.takeIf { generation.get() == ticket && it.first == projectId && it.second == next.second }
                } ?: return@launch
                while (true) {
                    try {
                        val page = read(projectId, current.second)
                        publish(ticket, current) { ReviewListState(projectId, current.second, page, dataRevision = current.third) }
                    } catch (failure: CancellationException) { throw failure }
                    catch (failure: Exception) {
                        publish(ticket, current) { it.copy(loading = false, error = errorCode(failure), stale = it.page != null) }
                    }
                    val following = synchronized(this@ReviewPageLoader) {
                        if (generation.get() != ticket || requested?.let { it.first != projectId || it.second != current.second } != false) null
                        else requested?.takeIf { it != current }.also { if (it == null) worker = null }
                    } ?: break
                    current = following
                }
            } catch (failure: CancellationException) { throw failure }
            finally {
                publish(ticket, next) { if (it.loading) it.copy(loading = false) else it }
            }
        }
    }
}

internal fun ProjectUiState.reviewQuery() = ReviewPageQuery(reviewSearch,
    com.phoneform.ai.core.model.ReviewListFilter.valueOf(reviewFilter), reviewPageIndex, reviewPageSize).normalized()
