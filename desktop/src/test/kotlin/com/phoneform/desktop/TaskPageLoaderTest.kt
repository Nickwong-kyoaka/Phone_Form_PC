package com.phoneform.desktop

import com.phoneform.ai.core.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TaskPageLoaderTest {
    private suspend fun awaitState(loader:TaskPageLoader,condition:(TaskListState)->Boolean):TaskListState =
        withTimeout(5_000) {while(!condition(loader.state.value))delay(5);loader.state.value}

    @Test fun oldNonCancellableReadCannotReplaceANewerFilterOrClearItsLoadingState()=runBlocking {
        val oldStarted=CompletableDeferred<Unit>();val oldDone=CompletableDeferred<Unit>()
        val newStarted=CompletableDeferred<Unit>()
        val oldRelease=CountDownLatch(1);val newRelease=CountDownLatch(1)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val loader=TaskPageLoader(scope,{query->
                if(query.filter==TaskListFilter.ALL) {
                    oldStarted.complete(Unit);check(oldRelease.await(5,TimeUnit.SECONDS));oldDone.complete(Unit)
                    TaskQueryPage(query,total=111)
                }else {
                    newStarted.complete(Unit);check(newRelease.await(5,TimeUnit.SECONDS));TaskQueryPage(query,total=222)
                }
            },{it.message.orEmpty()})
            loader.request(TaskPageQuery(filter=TaskListFilter.ALL),1)
            withTimeout(5_000){oldStarted.await()}
            loader.request(TaskPageQuery(filter=TaskListFilter.FAILED),2)
            withTimeout(5_000){newStarted.await()}
            oldRelease.countDown();withTimeout(5_000){oldDone.await()};delay(30)
            assertEquals(TaskListFilter.FAILED,loader.state.value.query.filter)
            assertTrue(loader.state.value.loading);assertNull(loader.state.value.page);assertNull(loader.state.value.error)
            newRelease.countDown()
            assertEquals(222,awaitState(loader){!it.loading && it.page!=null}.page!!.total)
        }finally {oldRelease.countDown();newRelease.countDown();scope.cancel()}
    }

    @Test fun lateFailureCannotReplaceANewerSuccessfulSearch()=runBlocking {
        val started=CompletableDeferred<Unit>();val finished=CompletableDeferred<Unit>();val release=CountDownLatch(1)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val loader=TaskPageLoader(scope,{query->
                if(query.search=="old") {
                    started.complete(Unit);check(release.await(5,TimeUnit.SECONDS));finished.complete(Unit)
                    throw IllegalStateException("LATE_READ_FAILURE")
                }
                TaskQueryPage(query,total=321,totalMatched=7)
            },{it.message.orEmpty()})
            loader.request(TaskPageQuery("old"),1);withTimeout(5_000){started.await()}
            loader.request(TaskPageQuery("new"),2)
            awaitState(loader){!it.loading && it.page?.totalMatched==7}
            release.countDown();withTimeout(5_000){finished.await()};delay(30)
            assertEquals("new",loader.state.value.query.search);assertEquals(321,loader.state.value.page!!.total)
            assertNull(loader.state.value.error);assertFalse(loader.state.value.stale)
        }finally {release.countDown();scope.cancel()}
    }

    @Test fun failedRefreshKeepsItsLastPageUntilExplicitRetrySucceeds()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            var fail=false
            val loader=TaskPageLoader(scope,{query->
                if(fail)throw IllegalStateException("TASK_READ_FAILED")
                TaskQueryPage(query,total=520,totalMatched=260)
            },{it.message.orEmpty()})
            loader.request(TaskPageQuery(filter=TaskListFilter.COMPLETED),1)
            val original=awaitState(loader){!it.loading && it.page!=null}.page
            fail=true;loader.request(TaskPageQuery(filter=TaskListFilter.COMPLETED),2)
            val failed=awaitState(loader){!it.loading && it.error!=null}
            assertEquals(original,failed.page);assertTrue(failed.stale);assertEquals("TASK_READ_FAILED",failed.error)
            fail=false;loader.request(TaskPageQuery(filter=TaskListFilter.COMPLETED),2,force=true)
            val restored=awaitState(loader){!it.loading && !it.stale && it.error==null}
            assertEquals(520,restored.page!!.total);assertEquals(2,restored.dataRevision)
        }finally {scope.cancel()}
    }

    @Test fun searchDebounceSurvivesDatabaseInvalidationAndOnlyLoadsLatestInput()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val seen=java.util.concurrent.CopyOnWriteArrayList<String>()
        try {
            val loader=TaskPageLoader(scope,{query->seen+=query.search;TaskQueryPage(query)},{"FAILED"})
            loader.request(TaskPageQuery("a"),1,300)
            loader.request(TaskPageQuery("ab"),1,300)
            loader.request(TaskPageQuery("ab"),2)
            delay(60);assertTrue(seen.isEmpty())
            awaitState(loader){!it.loading && it.page!=null}
            assertEquals(listOf("ab"),seen.toList())
        }finally {scope.cancel()}
    }
}
