package com.phoneform.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phoneform.ai.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun taskFilterLabel(filter:TaskListFilter,language:UiLanguage)=when(filter) {
    TaskListFilter.PENDING->tr(language,"待處理","待处理","Pending / action needed")
    TaskListFilter.FAILED->tr(language,"失敗","失败","Failed")
    TaskListFilter.COMPLETED->tr(language,"已完成","已完成","Completed")
    TaskListFilter.ALL->tr(language,"全部","全部","All")
}

@Composable fun TaskCenter(controller:Controller,language:UiLanguage,close:()->Unit) {
    val app by controller.state.collectAsState()
    val preferences by controller.ui.collectAsState()
    val scope=rememberCoroutineScope()
    val dismiss=safeDialogClose(controller,close)
    val searchFocus=remember {FocusRequester()}
    val loader=remember(controller){TaskPageLoader(scope,
        {query->withContext(Dispatchers.IO){controller.repositories.queryTaskPage(query)}},Controller::errorCode)}
    val result by loader.state.collectAsState()
    val query=preferences.taskQuery()
    var details by remember {mutableStateOf<Pair<String,BackgroundTaskPhase>?>(null)}
    LaunchedEffect(query,app.dataRevision) {
        val searchChanged=result.query.search!=query.search
        loader.request(query,app.dataRevision,if(searchChanged)300 else 0)
    }
    LaunchedEffect(result) {
        val loaded=result.page
        if(loaded!=null && !result.loading && !result.stale && result.error==null &&
            controller.ui.value.taskQuery()==result.query && loaded.query.pageIndex!=result.query.pageIndex)
            controller.updateUi {it.copy(taskPageIndex=loaded.query.pageIndex)}
    }
    fun change(search:String=query.search,filter:TaskListFilter=query.filter,pageIndex:Int=0) {
        controller.updateUi {it.copy(taskSearch=search,taskFilter=filter.name,taskPageIndex=pageIndex.coerceAtLeast(0))}
    }
    val page=result.page
    val interactive=page!=null && !result.stale && result.error==null
    val counts=page?.statusCounts ?: app.taskStatusCounts
    val activeCount=pendingTaskCount(counts)
    val failedCount=counts["FAILED"] ?: 0
    AppDialog(tr(language,"任務中心","任务中心","Task center"),dismiss,width=980.dp,height=720.dp,
        onPreviewKeyEvent={event->if(event.type==KeyEventType.KeyDown && event.isCtrlPressed && !event.isAltPressed &&
            !event.isShiftPressed && event.key==Key.F){searchFocus.requestFocus();true}else false}) {
        WorkspaceToolbar(tr(language,"任務中心","任务中心","Task center"),
            tr(language,"$activeCount 項待處理 · $failedCount 項失敗","$activeCount 项待处理 · $failedCount 项失败","$activeCount pending / action needed · $failedCount failed")) {
            OutlinedButton(onClick=controller::togglePaused) {Text(if(app.paused)tr(language,"繼續處理","继续处理","Resume processing")else tr(language,"暫停處理","暂停处理","Pause processing"))}
        }
        val filters=TaskListFilter.entries
        InspectorTabs(filters.map {taskFilterLabel(it,language)},filters.indexOf(query.filter)) {change(filter=filters[it])}
        DialogProblem(app,language)
        OutlinedTextField(preferences.taskSearch,{change(search=it)},singleLine=true,
            label={Text(tr(language,"搜尋全部任務的專案或問卷編號","搜索全部任务的项目或问卷编号","Search all tasks by project or submission number"))},
            modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp).focusRequester(searchFocus))
        if(result.loading)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(result.error!=null)Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(tr(language,"任務讀取失敗，原搜尋條件已保留。請重試。","任务读取失败，原搜索条件已保留。请重试。","Tasks could not be loaded. Your search is retained. Retry."),Modifier.weight(1f),color=MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick={loader.request(query,app.dataRevision,force=true)}){Text(tr(language,"重試","重试","Retry"))}
        }
        if(page?.items.isNullOrEmpty())Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) {
            if(result.loading)CircularProgressIndicator()
            else EmptyState(tr(language,"沒有符合條件的任務","没有符合条件的任务","No matching tasks"),
                tr(language,"搜尋與篩選涵蓋所有目前任務，可改變條件或稍後重試。","搜索与筛选覆盖所有当前任务，可更改条件或稍后重试。","Search and filters cover all current tasks. Change the query or retry later."))
        }else LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal=20.dp)) {
            items(page!!.items,key={it.phase.name+":"+it.id}) {task->
                val key=task.id to task.phase
                Column {
                    Row(Modifier.fillMaxWidth().heightIn(min=64.dp).padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        AppIcon(if(task.phase==BackgroundTaskPhase.RECOGNITION)"review"else "document",Modifier.size(20.dp))
                        Column(Modifier.weight(1f)) {
                            Text(task.projectName,maxLines=1,overflow=TextOverflow.Ellipsis)
                            Text(taskPhase(language,task.phase.name)+listOfNotNull(
                                task.submissionNumber?.let {tr(language,"第 $it 份","第 $it 份","Submission $it")},
                                task.pageNumber?.let {tr(language,"第 $it 頁","第 $it 页","Page $it")})
                                .joinToString(" · ",prefix=if(task.submissionNumber!=null || task.pageNumber!=null)" · "else ""),
                                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        StatusBadge(taskStatus(language,task.status),good=task.status=="READY",warning=task.status in setOf("FAILED","BLOCKED"))
                        if(task.submissionId!=null || task.collectionDraftId!=null)TextButton(onClick={controller.perform {
                            openTaskQuestionnaire(task.id,task.phase);scope.launch {close()}
                        }},enabled=interactive && !app.busy){Text(if(task.collectionDraftId!=null)tr(language,"開啟草稿","打开草稿","Open draft")else tr(language,"開啟問卷","打开问卷","Open"))}
                        if(task.retrySupported)TextButton(onClick={controller.retryTask(task.id,task.phase)},enabled=interactive && !app.busy){Text(tr(language,"重試","重试","Retry"))}
                        if(!task.error.isNullOrBlank())TextButton(onClick={details=if(details==key)null else key}){Text(tr(language,"詳情","详情","Details"))}
                    }
                    if(details==key)Text(message(language,task.error.orEmpty()),Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),style=MaterialTheme.typography.bodySmall)
                    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            val offset=(page?.query?.pageIndex ?: 0)*(page?.query?.pageSize ?: 50)
            val first=if(page?.items.isNullOrEmpty())0 else offset+1
            val last=offset+page?.items.orEmpty().size
            Text(if(page==null)"—"else tr(language,"第 $first–$last 項，共 ${page.totalMatched} 項 · 全部 ${page.total}","第 $first–$last 项，共 ${page.totalMatched} 项 · 全部 ${page.total}","$first–$last of ${page.totalMatched} matches · ${page.total} total"),Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick={change(pageIndex=(page?.query?.pageIndex ?: 0)-1)},enabled=interactive && (page?.query?.pageIndex ?: 0)>0){Text(tr(language,"上一頁","上一页","Previous page"))}
            OutlinedButton(onClick={change(pageIndex=(page?.query?.pageIndex ?: 0)+1)},enabled=interactive && last<(page?.totalMatched ?: 0)){Text(tr(language,"下一頁","下一页","Next page"))}
        }
        DialogFooter {
            Text(if(app.paused)tr(language,"已暫停；目前單頁步驟會保存檢查點。","已暂停；当前单页步骤会保存检查点。","Paused. The current page step will checkpoint.")else tr(language,"縮小視窗後會繼續處理。","最小化窗口后继续处理。","Processing continues while minimized."),Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
            Button(onClick=dismiss,enabled=!app.busy){Text(tr(language,"關閉","关闭","Close"))}
        }
    }
}
