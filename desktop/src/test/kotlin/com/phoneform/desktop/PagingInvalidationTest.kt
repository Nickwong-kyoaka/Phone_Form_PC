package com.phoneform.desktop

import com.phoneform.ai.core.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PagingInvalidationTest {
    private suspend fun until(condition:()->Boolean)=withTimeout(5_000){while(!condition())delay(5)}

    @Test fun taskWritesDuringASlowReadDoNotStarveItsAtomicSnapshotOrRestartThirtyReads()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val calls=AtomicInteger();val firstStarted=CompletableDeferred<Unit>();val secondStarted=CompletableDeferred<Unit>()
        val firstRelease=CompletableDeferred<Unit>();val secondRelease=CompletableDeferred<Unit>()
        try {
            val loader=TaskPageLoader(scope,{query->
                val call=calls.incrementAndGet()
                withContext(NonCancellable){if(call==1){firstStarted.complete(Unit);firstRelease.await()}else{secondStarted.complete(Unit);secondRelease.await()}}
                TaskQueryPage(query,totalMatched=call,total=call)
            },{"FAILED"})
            loader.request(TaskPageQuery(),1);withTimeout(5_000){firstStarted.await()}
            (2L..30L).forEach {loader.request(TaskPageQuery(),it)}
            firstRelease.complete(Unit);withTimeout(5_000){secondStarted.await()}
            assertEquals(1,loader.state.value.page!!.totalMatched)
            assertFalse(loader.state.value.stale);assertFalse(loader.state.value.loading)
            assertEquals(2,calls.get())
            secondRelease.complete(Unit)
            until {loader.state.value.page?.totalMatched==2 && loader.state.value.dataRevision==30L}
            assertEquals(2,calls.get())
        }finally{firstRelease.complete(Unit);secondRelease.complete(Unit);scope.cancel()}
    }

    @Test fun reviewWritesDuringASlowReadPublishThenCoalesceWithoutLosingTheLatestRevision()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val calls=AtomicInteger();val firstStarted=CompletableDeferred<Unit>();val secondStarted=CompletableDeferred<Unit>()
        val firstRelease=CompletableDeferred<Unit>();val secondRelease=CompletableDeferred<Unit>()
        try {
            val loader=ReviewPageLoader(scope,{_,query->
                val call=calls.incrementAndGet()
                withContext(NonCancellable){if(call==1){firstStarted.complete(Unit);firstRelease.await()}else{secondStarted.complete(Unit);secondRelease.await()}}
                ReviewPage(query,totalSubmitted=call)
            },{"FAILED"})
            loader.request("anonymous-project",ReviewPageQuery(),1);withTimeout(5_000){firstStarted.await()}
            (2L..30L).forEach {loader.request("anonymous-project",ReviewPageQuery(),it)}
            firstRelease.complete(Unit);withTimeout(5_000){secondStarted.await()}
            assertEquals(1,loader.state.value.page!!.totalSubmitted)
            assertFalse(loader.state.value.stale);assertFalse(loader.state.value.loading)
            assertEquals(2,calls.get())
            secondRelease.complete(Unit)
            until {loader.state.value.page?.totalSubmitted==2 && loader.state.value.dataRevision==30L}
            assertEquals(2,calls.get())
        }finally{firstRelease.complete(Unit);secondRelease.complete(Unit);scope.cancel()}
    }
}
