package com.phoneform.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phoneform.ai.core.model.*

/** Initial template geometry; persisted as one encrypted envelope and saved into q.recognition. */
@Composable internal fun CalibrationGroupDialog(controller:Controller,draft:TemplateDraft,page:SamplePage,
    questions:List<QuestionDefinition>,matrix:MatrixDefinition?,language:UiLanguage,close:()->Unit,
    save:suspend (List<QuestionDefinition>,MatrixDefinition?)->Unit) {
    val groupId=matrix?.id ?: questions.first().id
    val binding=CalibrationBinding("${draft.id}:$groupId:${RecognitionBindings.hash(editorJson.toJson(questions))}",draft.id,mapOf(page.id to PageProcessingFingerprint.of(page)))
    val initial=CalibrationEnvelope(binding,RecognitionProfileDraft("initial:$groupId",draft.projectId,draft.id,
        pageFingerprints=binding.pageFingerprints,settings=questions.map { calibrationSettings(it) }))
    val session="initial-calibration:${draft.id}:$groupId"
    val recovery=recoverableEncryptedState(controller,session,draft.projectId,"envelope",initial,::decodeCalibrationRecovery,{editorJson.toJson(it)})
    var envelope by recovery
    val state by controller.state.collectAsState()
    val bound=envelope.binding==binding
    val selectedState=controller.ui.collectAsState().value.projects[draft.projectId] ?: ProjectUiState()
    val selectedId=selectedState.recognitionOptionId?.takeIf { id->envelope.draft.settings.any { it.options.any {o->o.optionId==id} } }
        ?: envelope.draft.settings.firstOrNull()?.options?.firstOrNull()?.optionId
    val grid=envelope.grids[groupId] ?: calibrationGrid(questions,envelope.draft.settings,matrix)
    var tab by remember { mutableIntStateOf(0) }
    var closePrompt by remember {mutableStateOf(false)}
    val dirty=envelope.draft.settings!=initial.draft.settings || envelope.grids.isNotEmpty()
    fun requestClose(){if(!state.busy){if(dirty || !bound)closePrompt=true else close()}}
    fun commit() {controller.perform {
        recovery.requireReady()
        require(bound){"RECOGNITION_PAGE_CHANGED"}
        val next=questions.map { q->val s=envelope.draft.settings.single { it.questionId==q.id }
            q.copy(options=q.options.map { o->o.copy(region=s.options.firstOrNull { it.optionId==o.id }?.region ?: o.region) },recognition=s,
                answerRegion=if(q.options.isEmpty())s.answerRegion ?: q.answerRegion else q.answerRegion)
        }
        val updatedMatrix=if(matrix!=null && grid!=null)matrix.copy(region=grid.region,rowBoundaries=grid.rows,columnBoundaries=grid.columns)else matrix
        save(next,updatedMatrix);clearEditor(session);close()
    }}
    if(!recovery.ready) {
        AppDialog(tr(language,"整組選項與矩陣","整组选项与矩阵","Option group and matrix"),close,width=1180.dp,height=780.dp) {
            RecoveryLoadProblem(recovery,language){controller.perform {clearEditor(session);recovery.resetAfterDiscard(initial)}}
        }
        return
    }
    AppDialog(tr(language,"整組選項與矩陣","整组选项与矩阵","Option group and matrix"),{requestClose()},width=1180.dp,height=780.dp) {
        RegisterSaveAction(bound && !state.busy){commit()}
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(tr(language,"組移動、分隔線與印刷核心","组移动、分隔线与印刷核心","Group position, dividers and printed cores"),Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick={envelope=envelope.undoEdit()},enabled=bound && envelope.undo.isNotEmpty() && !state.busy){Text(tr(language,"復原","撤销","Undo"))}
                OutlinedButton(onClick={envelope=envelope.redoEdit()},enabled=bound && envelope.redo.isNotEmpty() && !state.busy){Text(tr(language,"重做","重做","Redo"))}
            }
            if(!bound)Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(12.dp)) {
                Text(tr(language,"恢復輸入屬於舊頁面版本，未套用。","恢复输入属于旧页面版本，未应用。","The recovered draft belongs to an older page and was not applied."))
                TextButton(onClick={controller.perform {clearEditor(session);envelope=initial}}){Text(tr(language,"放棄舊恢復輸入","放弃旧恢复输入","Discard old recovery"))}
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val inspector:@Composable ()->Unit={Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    CalibrationSettingsInspector(controller,page,questions,envelope.draft.settings,selectedId,language,bound && !state.busy,
                        {id->controller.updateProjectUi(draft.projectId){it.copy(recognitionOptionId=id)}},
                        {settings->val changed=settings.any {s->s.options.map {it.region}!=envelope.draft.settings.firstOrNull {it.questionId==s.questionId}?.options?.map {it.region}};envelope=envelope.edit(settings,if(changed)envelope.grids-groupId else envelope.grids)})
                }}
                val canvas:@Composable ()->Unit={CalibrationCanvas(controller,page,grid,envelope.draft.settings,selectedId,language,
                    {id->controller.updateProjectUi(draft.projectId){it.copy(recognitionOptionId=id)}},
                    {settings,next->envelope=envelope.edit(settings,if(next!=null)envelope.grids+(groupId to next)else envelope.grids)},Modifier.fillMaxSize(),bound && !state.busy,
                    scalarQuestionId=questions.singleOrNull()?.takeIf {it.type in listOf(QuestionType.NUMBER,QuestionType.DATE)}?.id)}
                if(maxWidth>=960.dp)Row(Modifier.fillMaxSize()){Box(Modifier.weight(1f)){canvas()};VerticalDivider();Box(Modifier.width(330.dp)){inspector()}}
                else Column(Modifier.fillMaxSize()){InspectorTabs(listOf(tr(language,"影像","图像","Image"),tr(language,"設定","设置","Settings")),tab){tab=it};Box(Modifier.weight(1f)){if(tab==0)canvas()else inspector()}}
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(tr(language,"可先保存未完成設定；範本確認會檢查啟用的辨識配置。","可先保存未完成设置；模板确认会检查启用的识别配置。","Incomplete settings may be saved; template confirmation validates enabled recognition settings."),Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick={requestClose()},enabled=!state.busy){Text(tr(language,"返回","返回","Back"))}
                Button(onClick={commit()},enabled=bound && !state.busy){Text(tr(language,"保存本組","保存本组","Save group"))}
            }
        }
        if(closePrompt)AlertDialog(onDismissRequest={closePrompt=false},title={Text(tr(language,"本組仍有未套用編輯","本组仍有未应用编辑","This group has unapplied edits"))},
            text={Text(tr(language,"先保存本組，或明確放棄後返回。繼續編輯會保留加密草稿。","请先保存本组，或明确放弃后返回。继续编辑会保留加密草稿。","Save this group or explicitly discard its edits before returning. Continue editing to retain the encrypted draft."))},
            confirmButton={Button(onClick={closePrompt=false;commit()},enabled=bound && !state.busy){Text(tr(language,"保存本組並返回","保存本组并返回","Save group and return"))}},
            dismissButton={Row {TextButton(onClick={closePrompt=false}){Text(tr(language,"繼續編輯","继续编辑","Continue editing"))};TextButton(onClick={controller.perform {clearEditor(session);closePrompt=false;close()}},enabled=!state.busy){Text(tr(language,"放棄本組編輯","放弃本组编辑","Discard group edits"))}}})
    }
}
