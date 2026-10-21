package com.phoneform.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.phoneform.ai.core.model.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun sectionLabel(language:UiLanguage,section:WorkspaceSection):String = when(section) {
    WorkspaceSection.TEMPLATE->tr(language,"範本設定","模板设置","Template")
    WorkspaceSection.COLLECTION->tr(language,"匯入問卷","导入问卷","Import questionnaires")
    WorkspaceSection.REVIEW->tr(language,"人工覆核","人工复核","Review")
    WorkspaceSection.EXPORT->tr(language,"Excel 匯出","Excel 导出","Excel export")
}
fun formatTime(time:Long):String=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))

@Composable fun DesktopApp(controller:Controller,state:AppSnapshot,language:UiLanguage) {
    val ui by controller.ui.collectAsState()
    val actions=LocalDesktopActions.current
    var settings by remember {mutableStateOf(false)}
    var wizard by remember {mutableStateOf(false)}
    var projectSettings by remember {mutableStateOf(false)}
    var tasks by remember {mutableStateOf(false)}
    var help by remember {mutableStateOf(false)}
    val projectUi=ui.projects[state.project?.project?.id]?:ProjectUiState()
    val imageMode=state.project!=null&&(ui.section==WorkspaceSection.TEMPLATE||ui.section==WorkspaceSection.COLLECTION||
        (ui.section==WorkspaceSection.REVIEW&&projectUi.reviewWorkspace))
    val expanded=!imageMode&&ui.sidebarExpanded
    Row(Modifier.fillMaxSize().onPreviewKeyEvent {event->
        if(event.type==KeyEventType.KeyDown&&event.isCtrlPressed&&!event.isAltPressed) {
            when(event.key) {
                Key.S->{actions.save?.let {it();true}?:false}
                Key.F->{actions.search?.let {it();true}?:false}
                else->false
            }
        } else false
    }) {
        Surface(Modifier.width(if(expanded)208.dp else 64.dp).fillMaxHeight(),color=MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.fillMaxSize().padding(horizontal=8.dp)) {
                Row(Modifier.fillMaxWidth().height(66.dp).clickable {controller.select(null)},verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.padding(start=6.dp).size(34.dp).background(BrandBlue,RoundedCornerShape(9.dp)),contentAlignment=Alignment.Center) {
                        AppIcon("review",Modifier.size(24.dp),androidx.compose.ui.graphics.Color.White)
                    }
                    if(expanded)Column {Text("PhoneForm",fontWeight=FontWeight.Bold,fontSize=17.sp);Text("AI · PC",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                }
                NavItem(tr(language,"全部專案","全部项目","All projects"),"projects",state.project==null,expanded){controller.select(null)}
                if(state.project!=null) {
                    Spacer(Modifier.height(14.dp))
                    if(expanded)Text(tr(language,"目前專案","当前项目","CURRENT PROJECT"),Modifier.padding(10.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    WorkspaceSection.entries.forEach {section->
                        NavItem(sectionLabel(language,section),when(section){
                            WorkspaceSection.TEMPLATE->"template";WorkspaceSection.COLLECTION->"collection"
                            WorkspaceSection.REVIEW->"review";WorkspaceSection.EXPORT->"export"
                        },ui.section==section,expanded){controller.navigate(section)}
                    }
                }
                Spacer(Modifier.weight(1f))
                NavItem(tr(language,"任務中心","任务中心","Tasks"),"tasks",false,expanded){tasks=true}
                NavItem(tr(language,"使用說明","使用说明","Help"),"help",false,expanded){help=true}
                NavItem(tr(language,"設定","设置","Settings"),"settings",false,expanded){settings=true}
                if(!imageMode)NavItem(tr(language,"收合側欄","收起侧栏","Toggle sidebar"),"list",false,expanded){controller.updateUi {it.copy(sidebarExpanded=!it.sidebarExpanded)}}
                Spacer(Modifier.height(8.dp))
            }
        }
        VerticalDivider(color=MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.background)) {
            Breadcrumb(controller,state,language){projectSettings=true}
            if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            if(state.error!=null||state.notice!=null||actions.dropError!=null)MessageStrip(language,state.error,state.notice?:actions.dropError){controller.clearMessage();actions.dropError=null}
            Box(Modifier.weight(1f)) {
                if(state.project==null)ProjectsTable(controller,state,language){wizard=true}
                else Column(Modifier.fillMaxSize()) {
                    if(!state.settings.onboardingCompleted)Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal=16.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text(tr(language,"先確認範本，再逐份匯入、覆核及匯出。","先确认模板，再逐份导入、复核和导出。","Confirm a template, then import, review and export."),Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                        TextButton(onClick={controller.perform {finishOnboarding()}}){Text(tr(language,"知道了","知道了","Got it"))}
                    }
                    Box(Modifier.weight(1f)) {when(ui.section) {
                        WorkspaceSection.TEMPLATE->TemplatePanel(controller,state,language)
                        WorkspaceSection.COLLECTION->CollectionPanel(controller,state,language)
                        WorkspaceSection.REVIEW->ReviewPanel(controller,state,language)
                        WorkspaceSection.EXPORT->ExportPanel(controller,state,language)
                    }}
                }
            }
            StatusBar(controller,state,language){tasks=true}
        }
    }
    if(wizard)NewProjectWizard(controller,language){wizard=false}
    if(settings)DesktopSettings(controller,state,language){settings=false}
    if(projectSettings&&state.project!=null)EditProjectDialog(controller,state.project,language){projectSettings=false}
    if(tasks)TaskCenter(controller,language){tasks=false}
    if(help)HelpDialog(controller,language){help=false}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun NavItem(label:String,icon:String,selected:Boolean,expanded:Boolean,action:()->Unit) {
    TooltipBox(positionProvider=TooltipDefaults.rememberPlainTooltipPositionProvider(),tooltip={PlainTooltip {Text(label)}},state=rememberTooltipState()) {
        Row(Modifier.fillMaxWidth().height(44.dp).padding(vertical=2.dp).background(
            if(selected)MaterialTheme.colorScheme.primary.copy(alpha=.1f) else androidx.compose.ui.graphics.Color.Transparent,RoundedCornerShape(6.dp))
            .clickable(onClick=action).semantics {contentDescription=label}.padding(horizontal=14.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            AppIcon(icon,Modifier.size(20.dp),if(selected)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            if(expanded)Text(label,color=if(selected)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                style=MaterialTheme.typography.bodyMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
    }
}
@Composable private fun Breadcrumb(controller:Controller,state:AppSnapshot,language:UiLanguage,onSettings:()->Unit) {
    var projects by remember {mutableStateOf(false)}
    val ui by controller.ui.collectAsState()
    Row(Modifier.fillMaxWidth().height(54.dp).background(MaterialTheme.colorScheme.surface).padding(horizontal=16.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        TextButton(onClick={controller.select(null)}){Text(tr(language,"專案","项目","Projects"))}
        if(state.project!=null) {
            AppIcon("chevron",Modifier.size(14.dp))
            Box {
                TextButton(onClick={projects=true}){Text(state.project.project.name,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.widthIn(max=240.dp));Text(" ▾")}
                DropdownMenu(projects,{projects=false}) {
                    state.projects.take(30).forEach {item->DropdownMenuItem(text={Text(item.project.name)},onClick={projects=false;controller.select(item.project.id)})}
                    DropdownMenuItem(text={Text(tr(language,"全部專案…","全部项目…","All projects…"))},onClick={projects=false;controller.select(null)})
                }
            }
            AppIcon("chevron",Modifier.size(14.dp))
            Text(sectionLabel(language,ui.section),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,
                modifier=Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis)
            TextButton(onClick=onSettings){Text(tr(language,"專案設定","项目设置","Project settings"))}
        } else {Spacer(Modifier.weight(1f));Text(tr(language,"離線工作空間","离线工作空间","Offline workspace"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
}
@Composable private fun MessageStrip(language:UiLanguage,error:String?,notice:String?,close:()->Unit) {
    var details by remember(error){mutableStateOf(false)}
    Column(Modifier.fillMaxWidth().background(if(error!=null)MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer)) {
        Row(Modifier.padding(horizontal=16.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(if(error!=null)message(language,error) else if(notice=="EXPORT_SAVED")message(language,notice)else notice.orEmpty(),Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
            if(error!=null)TextButton(onClick={details=!details}){Text(tr(language,"詳情","详情","Details"))}
            TextButton(onClick=close){Text("×")}
        }
        if(details)Text(error.orEmpty(),Modifier.padding(start=16.dp,end=16.dp,bottom=8.dp),style=MaterialTheme.typography.bodySmall)
    }
}
@Composable private fun StatusBar(controller:Controller,state:AppSnapshot,language:UiLanguage,onTasks:()->Unit) {
    val tasks by controller.tasks.collectAsState()
    val active=tasks.count {it.status in setOf("QUEUED","PENDING","RUNNING","PAUSED")}
    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
    Row(Modifier.fillMaxWidth().height(30.dp).background(MaterialTheme.colorScheme.surface).padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(when(state.editorSaveState) {
            EditorSaveState.SAVED->tr(language,"✓ 已保存","✓ 已保存","✓ Saved")
            EditorSaveState.SAVING->tr(language,"正在保存…","正在保存…","Saving…")
            EditorSaveState.FAILED->tr(language,"保存失敗 · 輸入已保留","保存失败 · 输入已保留","Save failed · input retained")
        },style=MaterialTheme.typography.bodySmall,color=if(state.editorSaveState==EditorSaveState.FAILED)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text((if(state.paused)tr(language,"已暫停","已暂停","Paused")else if(state.worker.isNotEmpty())tr(language,"處理中","处理中","Processing")else tr(language,"就緒","就绪","Ready"))+" · "+
            tr(language,"$active 個任務","$active 个任务","$active tasks"),Modifier.clickable(onClick=onTasks),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
        Text(tr(language,"離線","离线","Offline"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun ProjectsTable(controller:Controller,state:AppSnapshot,language:UiLanguage,onNew:()->Unit) {
    val ui by controller.ui.collectAsState()
    var selected by remember {mutableStateOf(setOf<String>())}
    var deleteStep by remember {mutableStateOf(0)}
    val searchFocus=remember {FocusRequester()}
    RegisterSearchAction {searchFocus.requestFocus()}
    val filtered=state.projects.filter {it.project.name.contains(ui.projectSearch,true)}
    val sorted=when(ui.projectSort) {
        "NAME"->filtered.sortedBy {it.project.name.lowercase()}
        "SUBMITTED"->filtered.sortedBy {it.submittedCount}
        "REVIEW"->filtered.sortedBy {it.readyForReviewCount}
        "CONFIRMED"->filtered.sortedBy {it.confirmedCount}
        else->filtered.sortedBy {it.project.updatedAtEpochMillis}
    }.let {if(ui.projectSortAscending)it else it.reversed()}
    LaunchedEffect(state.projects){selected=selected.intersect(state.projects.map {it.project.id}.toSet())}
    fun sort(key:String){controller.updateUi {it.copy(projectSort=key,projectSortAscending=if(it.projectSort==key)!it.projectSortAscending else key=="NAME")}}
    Column(Modifier.fillMaxSize()) {
        WorkspaceToolbar(tr(language,"全部專案","全部项目","All projects"),state.projects.size.toString()+" "+tr(language,"個專案","个项目","projects")) {
            if(selected.isNotEmpty())OutlinedButton(onClick={deleteStep=1},enabled=!state.busy){Text(tr(language,"刪除所選","删除所选","Delete selected")+" ("+selected.size+")")}
            Button(onClick=onNew,enabled=!state.busy){Text("+ "+tr(language,"建立專案","创建项目","New project"))}
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(20.dp)) {
            OutlinedTextField(ui.projectSearch,{value->controller.updateUi {it.copy(projectSearch=value)}},placeholder={Text(tr(language,"搜尋專案名稱","搜索项目名称","Search project names"))},
                leadingIcon={AppIcon("search")},singleLine=true,modifier=Modifier.widthIn(max=420.dp).weight(1f).focusRequester(searchFocus))
            Text(tr(language,"待覆核 ","待复核 ","Review ")+state.projects.sumOf {it.readyForReviewCount},color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
        }
        if(sorted.isEmpty())Box(Modifier.weight(1f)){EmptyState(
            if(state.projects.isEmpty())tr(language,"建立第一個問卷專案","创建第一个问卷项目","Create your first project")else tr(language,"沒有符合的專案","没有匹配的项目","No matching projects"),
            if(state.projects.isEmpty())tr(language,"從範本開始，逐份匯入問卷並覆核答案。","从模板开始，逐份导入问卷并复核答案。","Start with a template, then import questionnaires and review answers.")else tr(language,"試試其他名稱，或清除搜尋。","试试其他名称，或清除搜索。","Try a different name or clear your search."),
            action={if(state.projects.isEmpty())Button(onClick=onNew){Text(tr(language,"建立專案","创建项目","New project"))}else TextButton(onClick={controller.updateUi {it.copy(projectSearch="")}}){Text(tr(language,"清除搜尋","清除搜索","Clear search"))}})}
        else BoxWithConstraints(Modifier.weight(1f).padding(horizontal=20.dp)) {
            val horizontal=rememberScrollState()
            val width=maxOf(maxWidth,940.dp)
            val lazy=rememberLazyListState()
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).horizontalScroll(horizontal)) {
                Row(Modifier.width(width).height(44.dp).background(MaterialTheme.colorScheme.surfaceVariant),verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(selected.containsAll(sorted.map {it.project.id}),{checked->selected=if(checked)selected+sorted.map {it.project.id}else selected-sorted.map {it.project.id}},Modifier.width(44.dp))
                    SortHeader(tr(language,"名稱","名称","Name"),Modifier.weight(1f),ui,"NAME"){sort("NAME")}
                    SortHeader(tr(language,"已提交","已提交","Submitted"),Modifier.width(88.dp),ui,"SUBMITTED"){sort("SUBMITTED")}
                    SortHeader(tr(language,"待覆核","待复核","Review"),Modifier.width(88.dp),ui,"REVIEW"){sort("REVIEW")}
                    SortHeader(tr(language,"已確認","已确认","Confirmed"),Modifier.width(88.dp),ui,"CONFIRMED"){sort("CONFIRMED")}
                    Text(tr(language,"狀態","状态","Status"),Modifier.width(136.dp),style=MaterialTheme.typography.bodySmall)
                    SortHeader(tr(language,"更新時間","更新时间","Updated"),Modifier.width(158.dp),ui,"UPDATED"){sort("UPDATED")}
                    Spacer(Modifier.width(44.dp))
                }
                LazyColumn(Modifier.width(width).weight(1f),state=lazy) {
                    items(sorted,key={it.project.id}) {summary->
                        var more by remember {mutableStateOf(false)}
                        Row(Modifier.fillMaxWidth().height(48.dp).background(if(summary.project.id in selected)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                            .combinedClickable(onClick={selected=setOf(summary.project.id)},onDoubleClick={controller.select(summary.project.id)})
                            .onKeyEvent {event->if(event.type==KeyEventType.KeyDown&&event.key==Key.Enter){controller.select(summary.project.id);true}else false}.focusable(),
                            verticalAlignment=Alignment.CenterVertically) {
                            Checkbox(summary.project.id in selected,{checked->selected=if(checked)selected+summary.project.id else selected-summary.project.id},Modifier.width(44.dp))
                            Row(Modifier.weight(1f),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                AppIcon("projects",tint=MaterialTheme.colorScheme.primary);Text(summary.project.name,maxLines=1,overflow=TextOverflow.Ellipsis)
                            }
                            Text(summary.submittedCount.toString(),Modifier.width(88.dp))
                            Text(summary.readyForReviewCount.toString(),Modifier.width(88.dp),color=MaterialTheme.colorScheme.primary)
                            Text(summary.confirmedCount.toString(),Modifier.width(88.dp))
                            Box(Modifier.width(136.dp)) {StatusBadge(when {
                                summary.failedCount>0->tr(language,"需處理","需处理","Needs attention")
                                summary.project.preparationStatus==PreparationStatus.TEMPLATE_CONFIRMED->tr(language,"範本已確認","模板已确认","Template ready")
                                else->tr(language,"設定中","设置中","Setting up")
                            },good=summary.project.preparationStatus==PreparationStatus.TEMPLATE_CONFIRMED,warning=summary.failedCount>0)}
                            Text(formatTime(summary.project.updatedAtEpochMillis),Modifier.width(158.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Box(Modifier.width(44.dp)) {
                                TextButton(onClick={more=true},contentPadding=PaddingValues(4.dp)){Text("⋯")}
                                DropdownMenu(more,{more=false}) {
                                    DropdownMenuItem(text={Text(tr(language,"開啟專案","打开项目","Open project"))},onClick={more=false;controller.select(summary.project.id)})
                                    DropdownMenuItem(text={Text(tr(language,"刪除","删除","Delete"))},onClick={more=false;selected=setOf(summary.project.id);deleteStep=1})
                                }
                            }
                        }
                        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                }
                HorizontalScrollbar(rememberScrollbarAdapter(horizontal),Modifier.fillMaxWidth().height(10.dp))
            }
            VerticalScrollbar(rememberScrollbarAdapter(lazy),Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(bottom=10.dp))
        }
        Text(sorted.size.toString()+" "+tr(language,"個專案 · 雙擊或 Enter 開啟","个项目 · 双击或 Enter 打开","projects · Double-click or Enter to open"),Modifier.padding(20.dp,10.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if(deleteStep>0)AppDialog(tr(language,"永久刪除專案","永久删除项目","Permanently delete projects"),{deleteStep=0},600.dp,340.dp) {
        PanelHeading(tr(language,"刪除 ","删除 ","Delete ")+selected.size+" "+tr(language,"個專案","个项目","projects"))
        Text(if(deleteStep==1)tr(language,"將刪除所選專案的內部資料與歷史。","将删除所选项目的内部数据和历史。","Selected projects and their internal history will be deleted.")else tr(language,"最後確認：此操作無法復原。外部原件及已另存 Excel 會保留。","最后确认：此操作无法恢复。外部原件和已另存 Excel 会保留。","Final confirmation: this cannot be undone. External originals and saved Excel files are preserved."),
            Modifier.weight(1f).padding(24.dp))
        DialogFooter {
            TextButton(onClick={deleteStep=0}){Text(tr(language,"取消","取消","Cancel"))}
            Button(onClick={if(deleteStep==1)deleteStep=2 else {val ids=selected.toSet();controller.perform {repositories.deleteProjects(ids,newId());selected=emptySet();deleteStep=0}}},enabled=!state.busy,
                colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)) {Text(if(deleteStep==1)tr(language,"繼續","继续","Continue")else tr(language,"永久刪除","永久删除","Delete permanently"))}
        }
    }
}
@Composable private fun SortHeader(title:String,modifier:Modifier,ui:DesktopUiState,key:String,onClick:()->Unit) {
    Text(title+(if(ui.projectSort==key)if(ui.projectSortAscending)" ↑"else" ↓"else""),modifier.clickable(onClick=onClick).padding(vertical=10.dp),style=MaterialTheme.typography.bodySmall)
}
