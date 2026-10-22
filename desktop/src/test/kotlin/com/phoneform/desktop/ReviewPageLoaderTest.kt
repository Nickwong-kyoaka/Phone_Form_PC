package com.phoneform.desktop

import com.phoneform.ai.core.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ReviewPageLoaderTest {
    private suspend fun awaitState(loader: ReviewPageLoader, condition: (ReviewListState) -> Boolean): ReviewListState =
        withTimeout(5_000) { while (!condition(loader.state.value)) delay(5); loader.state.value }

    @Test fun oldUncancellableReadCannotOverwriteAnotherProjectOrItsLoadingState() = runBlocking {
        val oldStarted = CompletableDeferred<Unit>()
        val oldRelease = CompletableDeferred<Unit>()
        val newStarted = CompletableDeferred<Unit>()
        val newRelease = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val loader = ReviewPageLoader(scope, { projectId, query ->
                withContext(NonCancellable) {
                    if (projectId == "old") { oldStarted.complete(Unit); oldRelease.await() }
                    else { newStarted.complete(Unit); newRelease.await() }
                }
                ReviewPage(query, totalSubmitted = if (projectId == "old") 111 else 222)
            }, { it.message.orEmpty() })
            loader.request("old", ReviewPageQuery(), 1)
            withTimeout(5_000) { oldStarted.await() }
            loader.request("new", ReviewPageQuery(), 2)
            withTimeout(5_000) { newStarted.await() }
            oldRelease.complete(Unit)
            delay(30)
            assertEquals("new", loader.state.value.projectId)
            assertTrue(loader.state.value.loading)
            assertNull(loader.state.value.page)
            newRelease.complete(Unit)
            assertEquals(222, awaitState(loader) { !it.loading && it.page != null }.page!!.totalSubmitted)
        } finally { oldRelease.complete(Unit); newRelease.complete(Unit); scope.cancel() }
    }

    @Test fun failedRefreshRetainsPreviousPageUntilExplicitRetrySucceeds() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var fail = false
            val loader = ReviewPageLoader(scope, { _, query ->
                if (fail) throw IllegalStateException("READ_FAILED")
                ReviewPage(query, totalMatched = 71, totalSubmitted = 100)
            }, { it.message.orEmpty() })
            loader.request("project", ReviewPageQuery(), 1)
            val previous = awaitState(loader) { !it.loading && it.page != null }.page
            fail = true
            loader.request("project", ReviewPageQuery(), 2)
            val failed = awaitState(loader) { !it.loading && it.error != null }
            assertEquals(previous, failed.page)
            assertTrue(failed.stale)
            assertEquals("READ_FAILED", failed.error)
            fail = false
            loader.request("project", ReviewPageQuery(), 2, force = true)
            val restored = awaitState(loader) { !it.loading && it.error == null && !it.stale }
            assertEquals(71, restored.page!!.totalMatched)
        } finally { scope.cancel() }
    }

    @Test fun searchDebounceSurvivesDatabaseInvalidationAndLoadsOnlyLatestInput() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val seen = java.util.concurrent.CopyOnWriteArrayList<String>()
        try {
            val loader = ReviewPageLoader(scope, { _, query -> seen += query.search; ReviewPage(query) }, { "FAILED" })
            loader.request("project", ReviewPageQuery(search = "a"), 1, 300)
            loader.request("project", ReviewPageQuery(search = "ab"), 1, 300)
            // A background write must not flush a still-debounced search prematurely.
            loader.request("project", ReviewPageQuery(search = "ab"), 2)
            delay(60)
            assertTrue(seen.isEmpty())
            awaitState(loader) { !it.loading && it.page != null }
            assertEquals(listOf("ab"), seen.toList())
        } finally { scope.cancel() }
    }

    @Test fun clearingProjectRejectsALateReadAndDropsPrivateResults() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            val loader = ReviewPageLoader(scope, { _, query ->
                withContext(NonCancellable) { started.complete(Unit); release.await() }
                ReviewPage(query, totalSubmitted = 400)
            }, { "FAILED" })
            loader.request("removed", ReviewPageQuery(), 1)
            withTimeout(5_000) { started.await() }
            loader.request(null, ReviewPageQuery(), 2)
            release.complete(Unit)
            delay(30)
            assertEquals(ReviewListState(), loader.state.value)
        } finally { release.complete(Unit); scope.cancel() }
    }
}
