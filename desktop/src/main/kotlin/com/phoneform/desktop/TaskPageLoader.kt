package com.phoneform.desktop

import com.phoneform.ai.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class TaskListState(val query:TaskPageQuery=TaskPageQuery(),val page:TaskQueryPage?=null,
    val loading:Boolean=false,val stale:Boolean=false,val error:String?=null,val dataRevision:Long=-1)

/** JDBC reads may finish after cancellation; every publication checks the current request. */
internal class TaskPageLoader(private val scope:CoroutineScope,
    private val read:suspend(TaskPageQuery)->TaskQueryPage,private val errorCode:(Exception)->String) {
    private val mutable=MutableStateFlow(TaskListState())
    val state:StateFlow<TaskListState> = mutable.asStateFlow()
    private var generation=0L
    private var requested:Pair<TaskPageQuery,Long>?=null
    private var worker:Job?=null
    private var searchReadyAtNanos=0L
    @Synchronized private fun publish(ticket:Long,request:Pair<TaskPageQuery,Long>,change:(TaskListState)->TaskListState) {
        if(ticket==generation && requested?.first==request.first)mutable.value=change(mutable.value)
    }
    @Synchronized fun request(raw:TaskPageQuery,revision:Long,searchDelayMillis:Long=0,force:Boolean=false) {
        val next=raw.normalized() to revision
        if(!force && requested==next)return
        val sameQuery=requested?.first==next.first
        if(force || requested?.first?.search!=next.first.search)searchReadyAtNanos=if(searchDelayMillis>0)
            System.nanoTime()+searchDelayMillis*1_000_000 else 0L
        val waitMillis=((searchReadyAtNanos-System.nanoTime())/1_000_000).coerceAtLeast(0)
        requested=next
        // A busy worker may invalidate faster than JDBC can read. Let this atomic snapshot
        // publish, then coalesce all same-query writes into one follow-up read.
        if(!force && sameQuery && worker?.isActive==true)return
        val ticket=++generation;worker?.cancel()
        mutable.value=mutable.value.copy(query=next.first,loading=true,
            stale=mutable.value.page?.query?.let {it!=next.first}==true,error=null,dataRevision=revision)
        worker=scope.launch {
            try {
                if(waitMillis>0)delay(waitMillis)
                var current=synchronized(this@TaskPageLoader) {
                    requested?.takeIf {ticket==generation && it.first==next.first}
                } ?: return@launch
                while(true) {
                    try {
                        val page=read(current.first)
                        publish(ticket,current){TaskListState(current.first,page,dataRevision=current.second)}
                    }catch(failure:CancellationException){throw failure}
                    catch(failure:Exception){publish(ticket,current){it.copy(loading=false,stale=it.page!=null,error=errorCode(failure))}}
                    val following=synchronized(this@TaskPageLoader) {
                        if(ticket!=generation || requested?.first!=current.first)null
                        else requested?.takeIf {it!=current}.also {if(it==null)worker=null}
                    } ?: break
                    current=following
                }
            }catch(failure:CancellationException){throw failure}
            finally {publish(ticket,next){if(it.loading)it.copy(loading=false)else it}}
        }
    }
}

internal fun DesktopUiState.taskQuery()=TaskPageQuery(taskSearch,
    TaskListFilter.entries.firstOrNull {it.name==taskFilter} ?: TaskListFilter.PENDING,
    taskPageIndex.coerceAtLeast(0),taskPageSize.takeIf {it in 1..100} ?: 50).normalized()
