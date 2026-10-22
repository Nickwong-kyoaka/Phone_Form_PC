package com.phoneform.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phoneform.ai.core.model.*
import com.phoneform.desktop.vision.RecognitionResult
import kotlinx.coroutines.*

@Composable internal fun RecognitionSettingsDialog(controller:Controller,template:FormSchemaVersion,language:UiLanguage,close:()->Unit) {
    var initial by remember(template.id){mutableStateOf<RecognitionProfileDraft?>(null)}
    var failure by remember(template.id){mutableStateOf(false)}
    var retry by remember(template.id){mutableIntStateOf(0)}
    LaunchedEffect(template.id,retry) {
        failure=false
        try { initial=withContext(Dispatchers.IO){controller.loadRecognitionDraft()} }
        catch(e:CancellationException){throw e}catch(_:Exception){failure=true}
    }
    AppDialog(tr(language,"辨識設定","识别设置","Recognition settings"),close,width=1240.dp,height=820.dp) {
        val draft=initial
        if(draft==null) Box(Modifier.fillMaxSize().padding(24.dp),contentAlignment=Alignment.Center) {
            if(failure)Column(horizontalAlignment=Alignment.CenterHorizontally) {
                Text(tr(language,"設定無法讀取，已有輸入仍保留。","无法读取设置，已有输入仍保留。","Settings could not be loaded. Existing drafts are retained."))
                TextButton(onClick={retry++}){Text(tr(language,"重試","重试","Retry"))}
            } else CircularProgressIndicator()
        } else key(draft.id) { RecognitionSettingsEditor(controller,template,draft,language,{initial=it},close) }
    }
}

@Composable private fun RecognitionSettingsEditor(controller:Controller,template:FormSchemaVersion,initial:RecognitionProfileDraft,
    language:UiLanguage,onSaved:(RecognitionProfileDraft)->Unit,close:()->Unit) {
    val binding=CalibrationBinding(initial.id,template.id,RecognitionBindings.pageFingerprints(template.document))
    val session="recognition-profile:${initial.id}"
    val start=CalibrationEnvelope(binding,initial.copy(settings=template.document.questions.map { q->calibrationSettings(q,initial.settings.firstOrNull { it.questionId==q.id }) }))
    val recovery=recoverableEncryptedState(controller,session,initial.projectId,"envelope",start,::decodeCalibrationRecovery,{editorJson.toJson(it)})
    var envelope by recovery
    val state by controller.state.collectAsState();val ui by controller.ui.collectAsState()
    val presentation=ui.projects[initial.projectId] ?: ProjectUiState()
    val bound=calibrationEnvelopeMatches(envelope,binding)
    val published=envelope.publishedProfileId!=null
    val document=template.document
    val pages=document.pages.sortedBy { it.position }
    val page=pages.firstOrNull { it.id==presentation.recognitionPageId } ?: pages.firstOrNull { p->document.questions.any { it.pageId==p.id } } ?: pages.first()
    val questions=document.questions.filter { it.pageId==page.id }.sortedBy { it.position }
    val selected=questions.firstOrNull { it.id==presentation.recognitionQuestionId } ?: questions.firstOrNull()
    val matrix=selected?.matrixId?.let { id->document.matrices.firstOrNull { it.id==id } }
    val groupQuestions=matrix?.questionIds?.mapNotNull { id->questions.firstOrNull { it.id==id } } ?: listOfNotNull(selected)
    val groupId=matrix?.id ?: selected?.id.orEmpty()
    val groupSettings=groupQuestions.map { q->calibrationSettings(q,envelope.draft.settings.firstOrNull { it.questionId==q.id }) }
    val grid=envelope.grids[groupId] ?: calibrationGrid(groupQuestions,groupSettings,matrix)
    val optionId=presentation.recognitionOptionId?.takeIf { id->groupSettings.any { s->s.options.any { it.optionId==id } } }
        ?: groupSettings.firstOrNull()?.options?.firstOrNull()?.optionId
    var preview by remember(initial.id,page.id){mutableStateOf<RecognitionResult?>(null)}
    var previewFingerprint by remember(initial.id,page.id){mutableStateOf<String?>(null)}
    var previewGroupId by remember(initial.id,page.id){mutableStateOf<String?>(null)}
    var previewTab by remember { mutableIntStateOf(0) }
    var narrowTab by remember { mutableIntStateOf(0) }
    var pageMenu by remember { mutableStateOf(false) };var groupMenu by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var copyMenu by remember {mutableStateOf(false)}
    var statusMessage by remember { mutableStateOf<String?>(null) }
    fun change(settings:List<QuestionRecognitionSettings>,nextGrid:CalibrationGrid?=null) {
        val regionsChanged=settings.any {s->s.options.map {it.region}!=groupSettings.firstOrNull {it.questionId==s.questionId}?.options?.map {it.region}}
        val all=envelope.draft.settings.map { s->settings.firstOrNull { it.questionId==s.questionId } ?: s }+settings.filter { s->envelope.draft.settings.none { it.questionId==s.questionId } }
        envelope=envelope.edit(all,if(nextGrid!=null)envelope.grids+(groupId to nextGrid)else if(regionsChanged)envelope.grids-groupId else envelope.grids)
        preview=null;statusMessage=null
    }
    val errors=RecognitionBindings.validate(document,envelope.draft.settings)
    val currentFingerprint=editorJson.toJson(envelope.draft.settings)
    val previewCurrent=preview!=null && previewFingerprint==currentFingerprint && previewGroupId==groupId
    fun cleanupPublished() {controller.perform {
        recovery.requireReady()
        require(envelope.publishedProfileId!=null)
        // Publication already committed. A cleanup retry must never save the removed draft again.
        clearEditor(session);close()
    }}
    fun save() { if(bound && !published)controller.perform { recovery.requireReady();val saved=saveRecognitionDraft(envelope.draft);envelope=envelope.copy(draft=saved,undo=emptyList(),redo=emptyList());onSaved(saved);flushEdits();statusMessage=tr(language,"設定草稿已保存，尚未確認套用。","设置草稿已保存，尚未确认应用。","Settings draft saved. Confirm it before use.") } }
    RegisterSaveAction(recovery.ready && (bound || published) && !state.busy) { if(published)cleanupPublished()else save() }
    if(!recovery.ready) {
        RecoveryLoadProblem(recovery,language){controller.perform {clearEditor(session);recovery.resetAfterDiscard(start)}}
        return
    }
    Column(Modifier.fillMaxSize()) {
        WorkspaceToolbar(tr(language,"辨識設定 · 草稿 r${envelope.draft.revision}","识别设置 · 草稿 r${envelope.draft.revision}","Recognition settings · draft r${envelope.draft.revision}"),
            tr(language,"只調整辨識範圍，不改題目、選項代碼或已保存人工答案。","仅调整识别区域，不改变题目、选项代码或已保存人工答案。","Adjust recognition regions without changing question codes or saved human answers.")) {
            OutlinedButton(onClick={envelope=envelope.undoEdit();preview=null},enabled=bound && !published && envelope.undo.isNotEmpty() && !state.busy){Text(tr(language,"復原","撤销","Undo"))}
            OutlinedButton(onClick={envelope=envelope.redoEdit();preview=null},enabled=bound && !published && envelope.redo.isNotEmpty() && !state.busy){Text(tr(language,"重做","重做","Redo"))}
            Button(onClick={save()},enabled=bound && !published && !state.busy){Text(tr(language,"保存草稿","保存草稿","Save draft"))}
            Box {TextButton(onClick={more=true},enabled=!state.busy && !published){Text(tr(language,"更多","更多","More"))}
                DropdownMenu(more,{more=false}) {
                    DropdownMenuItem(text={Text(tr(language,"重新載入設定（放棄未保存編輯）","重新加载设置（放弃未保存编辑）","Reload settings · discard unsaved edits"))},onClick={more=false;controller.perform {
                        val loaded=loadRecognitionDraft();clearEditor(session)
                        envelope=CalibrationEnvelope(CalibrationBinding(loaded.id,template.id,loaded.pageFingerprints),loaded);onSaved(loaded);preview=null
                    }})
                    DropdownMenuItem(text={Text(tr(language,"放棄設定草稿並重新開始","放弃设置草稿并重新开始","Discard settings draft and start again"))},onClick={more=false;controller.perform {
                        discardRecognitionDraft(envelope.draft);clearEditor(session);val loaded=loadRecognitionDraft();onSaved(loaded);preview=null
                    }})
                }
            }
        }
        if(!bound)Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(12.dp)) {
            Text(tr(language,"先前的設定草稿與目前頁面版本不一致，未套用或覆蓋。可查看保留內容後重新開始。","恢复草稿与当前页面版本不一致，未应用或覆盖。可查看保留内容后重新开始。","Recovered settings belong to another page/version and were not applied. Review the retained content before starting again."))
            SelectionContainer { Text(envelope.draft.settings.joinToString { it.questionId },style=MaterialTheme.typography.bodySmall) }
            TextButton(onClick={controller.perform { clearEditor(session);envelope=start }}){Text(tr(language,"放棄恢復草稿，使用目前設定","放弃恢复草稿，使用当前设置","Discard recovery and use current settings"))}
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Box {OutlinedButton(onClick={pageMenu=true}){Text(tr(language,"第 ${page.position+1} 頁","第 ${page.position+1} 页","Page ${page.position+1}")+" ▾")}
                DropdownMenu(pageMenu,{pageMenu=false}) {pages.forEach { p->DropdownMenuItem(text={Text(tr(language,"第 ${p.position+1} 頁","第 ${p.position+1} 页","Page ${p.position+1}"))},onClick={pageMenu=false;controller.updateProjectUi(initial.projectId){it.copy(recognitionPageId=p.id,recognitionQuestionId=null,recognitionOptionId=null)}})} }
            }
            Box(Modifier.weight(1f)) {OutlinedButton(onClick={groupMenu=true},modifier=Modifier.fillMaxWidth(),enabled=questions.isNotEmpty()) {Text(selected?.let { "${it.label} ${it.title}".trim() } ?: tr(language,"此頁無擷取題目","此页无提取题目","No extraction questions"),maxLines=1,overflow=TextOverflow.Ellipsis)}
                DropdownMenu(groupMenu,{groupMenu=false}) {questions.forEach { q->DropdownMenuItem(text={Text("${q.label} ${q.title}".trim(),maxLines=2)},onClick={groupMenu=false;controller.updateProjectUi(initial.projectId){it.copy(recognitionQuestionId=q.id,recognitionOptionId=null)}})} }
            }
            OutlinedButton(onClick={controller.perform { preview=previewRecognition(envelope.draft,page.id,groupQuestions.map {it.id}.toSet());previewFingerprint=currentFingerprint;previewGroupId=groupId;previewTab=1 }},enabled=bound && !published && !state.busy && questions.isNotEmpty() && errors.none { issue->groupQuestions.any { issue.endsWith(":${it.id}") || it.options.any { o->issue.endsWith(":${o.id}") } } }){Text(tr(language,"範本頁本組預覽","模板页本组预览","Preview this template group"))}
        }
        Text(tr(language,"預覽只使用這份範本頁，不寫入正式候選；不能作不同掃描的準確率證明。","预览仅使用这份模板页，不写入正式候选；不能作为不同扫描的准确率证明。","Preview uses this template page only and does not save production candidates or prove accuracy on other scans."),Modifier.padding(horizontal=14.dp,vertical=6.dp),style=MaterialTheme.typography.bodySmall)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val inspector:@Composable ()->Unit = {
                Column(Modifier.fillMaxSize()) {
                    InspectorTabs(listOf(tr(language,"選項／核心","选项／核心","Options / cores"),tr(language,"預覽證據","预览证据","Preview evidence")),previewTab){previewTab=it}
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        if(previewTab==1) {
                            if(!previewCurrent)Text(tr(language,"設定已改變或尚未預覽，請重新預覽。","设置已改变或尚未预览，请重新预览。","Settings changed or have not been previewed. Run preview again."))
                            else preview!!.observations.filter { observation->groupQuestions.any { "question:${it.id}"==observation.targetKey } }.forEach { observation->OptionEvidenceList(observation,groupQuestions,optionId,language,{ id->controller.updateProjectUi(initial.projectId){it.copy(recognitionOptionId=id)}}) }
                        } else {
                            CalibrationSettingsInspector(controller,page,groupQuestions,groupSettings,optionId,language,bound && !published && !state.busy,
                                { id->controller.updateProjectUi(initial.projectId){it.copy(recognitionOptionId=id)} },{change(it)})
                            val source=selected?.takeIf {it.options.size>=2}
                            val targets=if(source==null)emptyList()else questions.filter {it.id!=source.id && it.type==source.type && it.options.size==source.options.size}
                            if(source!=null) {
                                HorizontalDivider()
                                Text(tr(language,"只複製同頁同題型、相同選項數的設定，依選項順序映射；不複製答案或候選，核心須重新確認。","仅复制到同页同题型且选项数相同的题，按选项顺序映射；不复制答案或候选，核心须重新确认。","Copy settings only to the same page, question type and option count, by option order. Answers/candidates are not copied; cores need rechecking."),style=MaterialTheme.typography.bodySmall)
                                Box {
                                    OutlinedButton(onClick={copyMenu=true},enabled=bound && !published && !state.busy && targets.isNotEmpty(),modifier=Modifier.fillMaxWidth()){Text(tr(language,"複製目前題目設定至…","复制当前题目设置到…","Copy current question settings to…"))}
                                    DropdownMenu(copyMenu,{copyMenu=false}) {targets.forEach {target->DropdownMenuItem(text={Text("${target.label} ${target.title}".trim(),maxLines=2)},onClick={
                                        copyMenu=false
                                        val sourceSettings=calibrationSettings(source,envelope.draft.settings.firstOrNull {it.questionId==source.id})
                                        val targetSettings=calibrationSettings(target,envelope.draft.settings.firstOrNull {it.questionId==target.id})
                                        val copied=copySamePageRecognitionSettings(source,target,sourceSettings,targetSettings,envelope.draft.pageFingerprints[page.id],PageProcessingFingerprint.of(page))
                                        envelope=envelope.edit(envelope.draft.settings.map {if(it.questionId==target.id)copied else it},envelope.grids-(target.matrixId ?: target.id))
                                        preview=null;statusMessage=tr(language,"已複製辨識設定；請切換目標題目核對所有範圍及核心。","已复制识别设置；请切换目标题目核对全部区域和核心。","Recognition settings copied. Open the target question and check every region and core.")
                                    })} }
                                }
                            }
                        }
                    }
                }
            }
            val canvas:@Composable ()->Unit = { CalibrationCanvas(controller,page,grid,groupSettings,optionId,language,
                { id->controller.updateProjectUi(initial.projectId){it.copy(recognitionOptionId=id)} },{ settings,next->change(settings,next) },Modifier.fillMaxSize(),bound && !published && !state.busy,
                scalarQuestionId=selected?.takeIf { it.type in listOf(QuestionType.NUMBER,QuestionType.DATE) }?.id) }
            if(maxWidth>=960.dp)Row(Modifier.fillMaxSize()){Box(Modifier.weight(1f)){canvas()};VerticalDivider();Box(Modifier.width(330.dp)){inspector()}}
            else Column(Modifier.fillMaxSize()){InspectorTabs(listOf(tr(language,"影像","图像","Image"),tr(language,"設定／證據","设置／证据","Settings / evidence")),narrowTab){narrowTab=it};Box(Modifier.weight(1f)){if(narrowTab==0)canvas()else inspector()}}
        }
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            statusMessage?.let {Text(it,color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.bodySmall)}
            if(published)Text(tr(language,"辨識設定已發布。若編輯草稿清理失敗，只需重試清理；設定不會再次發布或被覆蓋。","识别设置已发布。若编辑草稿清理失败，仅需重试清理；不会再次发布或覆盖设置。","Recognition settings are published. If editor cleanup failed, retry cleanup only; the settings will not be republished or overwritten."),color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.bodySmall)
            state.error?.let {Text(message(language,it),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
            if(errors.isNotEmpty())Text(tr(language,"還有 ${errors.size} 項設定未完成：","还有 ${errors.size} 项设置未完成：","${errors.size} settings need attention: ")+recognitionIssueText(errors.first(),document,language),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(tr(language,"關閉會保留加密編輯草稿。","关闭会保留加密编辑草稿。","Closing retains the encrypted editor draft."),Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick=close,enabled=!state.busy){Text(tr(language,"關閉","关闭","Close"))}
                Button(onClick={if(published)cleanupPublished()else controller.perform {
                    recovery.requireReady()
                    val saved=saveRecognitionDraft(envelope.draft);envelope=envelope.copy(draft=saved,undo=emptyList(),redo=emptyList());onSaved(saved);flushEdits()
                    val confirmed=confirmSavedRecognitionDraft(saved)
                    envelope=envelope.copy(publishedProfileId=confirmed.id)
                    statusMessage=tr(language,"設定已發布，正在清理編輯草稿。","设置已发布，正在清理编辑草稿。","Settings published. Cleaning up the editor draft.")
                    flushEdits();clearEditor(session);close()
                }},enabled=(published || bound && errors.isEmpty()) && !state.busy){Text(if(published)tr(language,"重試清理並關閉","重试清理并关闭","Retry cleanup and close")else tr(language,"確認設定","确认设置","Confirm settings"))}
            }
        }
    }
}

@Composable internal fun CalibrationSettingsInspector(controller:Controller,page:SamplePage,questions:List<QuestionDefinition>,settings:List<QuestionRecognitionSettings>,
    selectedOptionId:String?,language:UiLanguage,enabled:Boolean,onSelect:(String)->Unit,onChange:(List<QuestionRecognitionSettings>)->Unit) {
    val choice=questions.any { it.type in listOf(QuestionType.SINGLE_CHOICE,QuestionType.MULTIPLE_CHOICE) }
    val supported=questions.none { it.type==QuestionType.TEXT }
    val allEnabled=settings.isNotEmpty() && settings.all { it.enabled }
    Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(allEnabled,{checked->onChange(settings.map { it.copy(enabled=checked,style=if(checked && choice && it.style==MarkStyle.MANUAL_ONLY)MarkStyle.CHECKBOX_TICK_CROSS else it.style) })},enabled=enabled && supported);Text(tr(language,"啟用本組本地辨識","启用本组本地识别","Enable recognition for this group"))}
    if(!supported)Text(tr(language,"自由文字保留人工輸入。","自由文本保留人工输入。","Free text uses human entry."),style=MaterialTheme.typography.bodySmall)
    if(choice) {
        Text(tr(language,"讀取框須包住完整方框或圈選筆跡，並保留少量外圍留白，不要沿印刷框邊裁切。數字核心只框印刷字形，不包含手寫外圈或表格線。","读取框须包含完整方框或圈选笔迹，并留少量外围空白，勿沿印刷框边裁切。数字核心仅框印刷字形，不包含手写外圈或表格线。","Include the entire checkbox or handwritten circle plus a small outer margin. Do not crop along the printed border. The printed core should contain only the printed glyph, excluding the handwritten circle and grid lines."),style=MaterialTheme.typography.bodySmall)
        MarkStyle.entries.forEach { style->Row(Modifier.fillMaxWidth().clickable(enabled=enabled){onChange(settings.map { it.copy(style=style,enabled=style!=MarkStyle.MANUAL_ONLY) })},verticalAlignment=Alignment.CenterVertically) {
            RadioButton(settings.all { it.style==style },null,enabled=enabled);Text(calibrationStyleLabel(style,language),Modifier.padding(start=6.dp))
        } }
        if(settings.any { it.style==MarkStyle.CIRCLED_PRINTED_NUMBER }) {
            OutlinedButton(onClick={controller.perform {
                val suggested=suggestPrintedCores(page.id,settings.flatMap { it.options })
                onChange(settings.map { s->s.copy(options=s.options.map { o->suggested.firstOrNull { it.optionId==o.optionId } ?: o.copy(coreConfirmed=false) }) })
            }},enabled=enabled,modifier=Modifier.fillMaxWidth()){Text(tr(language,"建議印刷核心（須確認）","建议印刷核心（须确认）","Suggest printed cores · verify"))}
            Text(tr(language,"紅框為未確認核心；綠框為已人工確認。沒有數字建議時，選取選項並用「數字核心」框選。","红框为未确认核心；绿框为已人工确认。无数字建议时，选中选项并用“数字核心”框选。","Red cores are unconfirmed; green cores were checked. When no digit is suggested, select an option and draw its core."),style=MaterialTheme.typography.bodySmall)
        }
        if(questions.size==1 && questions.first().matrixId==null && settings.singleOrNull()?.options?.isNotEmpty()==true)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf(false,true).forEach {vertical->OutlinedButton(onClick={val s=settings.single();val bounds=unionRegions(s.options.map {it.region})!!;onChange(listOf(arrangeCalibrationOptions(s,bounds,vertical)))},enabled=enabled,modifier=Modifier.weight(1f)){Text(if(vertical)tr(language,"改為直排","改为竖排","Vertical")else tr(language,"改為橫排","改为横排","Horizontal"))}}
        }
        questions.forEach { q->
            if(questions.size>1)Text("${q.label} ${q.title}".trim(),style=MaterialTheme.typography.labelLarge)
            val setting=settings.firstOrNull { it.questionId==q.id } ?: return@forEach
            q.options.forEach optionLoop@ { option->val geometry=setting.options.firstOrNull { it.optionId==option.id } ?: return@optionLoop
                Surface(Modifier.fillMaxWidth().clickable {onSelect(option.id)},shape=MaterialTheme.shapes.small,color=if(selectedOptionId==option.id)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                        Text("${option.title} (${option.code})",style=MaterialTheme.typography.bodyMedium)
                        if(setting.style==MarkStyle.CIRCLED_PRINTED_NUMBER) {
                            val valid=geometry.printedCore?.let {it.isValid() && RecognitionBindings.contains(geometry.region,it)}==true
                            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(geometry.coreConfirmed,{checked->onChange(settings.map { s->if(s.questionId!=q.id)s else s.copy(options=s.options.map { o->if(o.optionId==option.id)o.copy(coreConfirmed=checked)else o }) })},enabled=enabled && valid);Text(if(valid)tr(language,"已檢查這個數字核心","已检查该数字核心","I checked this printed core")else tr(language,"缺少有效的數字核心","缺少有效数字核心","A valid printed core is required"),style=MaterialTheme.typography.bodySmall)}
                        }
                    }
                }
            }
        }
    }
}

@Composable internal fun OptionEvidenceList(observation:FieldObservation,questions:List<QuestionDefinition>,selectedOptionId:String?,language:UiLanguage,onSelect:(String)->Unit) {
    var details by remember(observation.evidenceHash){mutableStateOf(false)}
    Text(tr(language,"機器觀察，與人工答案分開","机器观察，与人工答案分开","Machine observations · separate from human answers"),style=MaterialTheme.typography.labelMedium)
    if(questions.none {it.options.isNotEmpty()}) {
        Text(observation.candidate?.text?.ifBlank {"—"} ?: tr(language,"沒有可用候選，保留人工處理。","无可用候选，保留人工处理。","No usable candidate. Human review is required."))
        return
    }
    if(observation.optionObservations.isEmpty())Text(tr(language,"沒有逐選項證據；需以新設定重新辨識。","没有逐选项证据；需用新设置重新识别。","No per-option evidence. Rerun with confirmed settings."),style=MaterialTheme.typography.bodySmall)
    observation.optionObservations.forEach { item->val option=questions.flatMap { it.options }.firstOrNull { it.id==item.optionId }
        Surface(Modifier.fillMaxWidth().clickable { onSelect(item.optionId) },color=if(selectedOptionId==item.optionId)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,shape=MaterialTheme.shapes.small) {
            Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text((option?.let { "${it.title} (${it.code})" } ?: item.optionId)+" · "+optionDecisionLabel(item.decision,language))
                Text(optionReasonText(item.reason,language),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(details)SelectionContainer {Text((listOf(item.reason)+item.metrics.entries.sortedBy { it.key }.map { "${it.key}: ${it.value}" }).joinToString("\n"),style=MaterialTheme.typography.bodySmall)}
            }
        }
    }
    TextButton(onClick={details=!details}){Text(tr(language,"量測與技術詳情","测量与技术详情","Measurements and technical details"))}
    if(details)SelectionContainer {Text(listOf(observation.reason,observation.engineVersion,observation.profileId.orEmpty(),observation.evidenceHash).filter { it.isNotBlank() }.joinToString("\n"),style=MaterialTheme.typography.bodySmall)}
}

internal fun optionReasonText(reason:String,language:UiLanguage):String {
    val code=reason.substringBefore(':').trim()
    return when {
        code.startsWith("CHECKBOX_CROP_TOUCHES_BORDER") || code.startsWith("CIRCLE_OR_INK_CROPPED")->tr(language,"框線或筆跡碰到讀取邊緣；請保留完整圖案及外圍留白，再人工核對。","框线或笔迹触及读取边缘；请保留完整图案及外围空白后人工核对。","The border or ink touches the crop edge. Include the whole shape with an outer margin, then check it.")
        code.startsWith("CHECKBOX_BORDER_NOT_LOCALIZED")->tr(language,"未找到可靠的完整方框；請檢查範圍和影像清晰度。","未找到可靠完整方框；请检查区域及图像清晰度。","A complete checkbox border could not be located reliably. Check the region and image clarity.")
        code.startsWith("PRINTED_CORE_NOT_CONFIRMED") || code.startsWith("CORE_CONFIRMATION_REQUIRED")->tr(language,"印刷數字核心尚未確認；請在影像中核對後逐項確認。","印刷数字核心尚未确认；请在图像中核对并逐项确认。","The printed-number core is not confirmed. Check it in the image and confirm this option.")
        code.startsWith("PRINTED_CORE_UNRELIABLE")->tr(language,"核心內字形不足或碰到核心邊緣；請只框完整印刷字形並重新核對。","核心内字形不足或触及核心边缘；请仅框完整印刷字形并重新核对。","The printed glyph is missing or touches the core edge. Frame the complete printed glyph and check it again.")
        code.startsWith("PRINTED_CORE_TOO_LARGE")->tr(language,"核心過大，可能含外圈；請縮至印刷字形並重新確認。","核心过大，可能包含外圈；请缩至印刷字形并重新确认。","The core is too large and may include the circle. Limit it to the printed glyph and reconfirm.")
        code.startsWith("MULTIPLE_SURROUNDING_CURVES") || code.startsWith("MULTIPLE_SINGLE_CHOICE_MARKS")->tr(language,"出現多個圈線或單選標記，無法安全判定；請人工查看原圖。","出现多个圈线或单选标记，无法安全判定；请人工查看原图。","Multiple curves or single-choice marks were found. Check the original image manually.")
        code.startsWith("FILLED_OR_CANCELLED_CHECKBOX") || code.startsWith("CLOSED_LOOP_OR_CANCELLED_CHECKBOX_INK") || code.startsWith("PARTIAL_CANCELLED_OR_STRAY_CIRCLE_INK")->tr(language,"筆跡可能為塗滿、取消、殘圈或雜線；請人工判定。","笔迹可能为涂满、取消、残圈或杂线；请人工判断。","The ink may be filled, cancelled, incomplete or stray. Decide from the image manually.")
        code.startsWith("OPTION_TOO_BLURRED")->tr(language,"此選項影像較模糊，暫不自動判定；請查看原圖或重新匯入。","此选项图像较模糊，暂不自动判断；请查看原图或重新导入。","This option is too blurred for a reliable decision. Check the original or import a clearer image.")
        code.startsWith("OPTION_RESOLUTION_TOO_LOW") || code.startsWith("CHECKBOX_INTERIOR_TOO_SMALL")->tr(language,"有效圖案太小，細節不足；請檢查讀取框或使用較清晰影像。","有效图案太小，细节不足；请检查读取框或使用更清晰图像。","The usable shape is too small. Check the region or use a clearer image.")
        code.startsWith("ISOLATED_OR_SMALL_CHECKBOX_INK") || code.startsWith("ISOLATED_OUTER_INK")->tr(language,"只見少量或零散筆跡，不足以確認標記；請人工核對。","仅见少量或零散笔迹，不足以确认标记；请人工核对。","Only small or isolated ink was found. Check whether it is an intentional mark.")
        code.startsWith("NO_RELIABLE_SURROUNDING_CURVE") || code.startsWith("CHECKBOX_MARK_NOT_CONFIDENT")->tr(language,"未找到可靠的完整圈選或勾叉形狀；請人工查看影像。","未找到可靠完整圈选或勾叉形状；请人工查看图像。","No reliable circle, tick or cross could be identified. Check the image manually.")
        code.startsWith("OVERLAPPING_OPTION_REGIONS") || code.startsWith("INVALID_OPTION_REGION")->tr(language,"選項範圍無效或互相重疊；請先修正設定再預覽。","选项区域无效或相互重叠；请先修正设置再预览。","Option regions are invalid or overlap. Correct the settings before previewing.")
        code.startsWith("MANUAL_MARK_STYLE") || code.startsWith("RECOGNITION_DISABLED")->tr(language,"此項設定為人工處理，未自動判定。","此项设置为人工处理，未自动判断。","This option is configured for human review; no automatic decision was made.")
        code.startsWith("VISIBLE_EMPTY_CHECKBOX")->tr(language,"可見空方框，未找到內部勾叉；仍可人工核對。","可见空方框，未发现内部勾叉；仍可人工核对。","An empty checkbox is visible with no internal tick or cross. Check it if needed.")
        code.startsWith("NO_SURROUNDING_CIRCLE")->tr(language,"印刷數字外未找到圈線；請核對是否確實未圈選。","印刷数字外未发现圈线；请核对是否确实未圈选。","No circle was found around the printed number. Check that it is unmarked.")
        code.startsWith("CHECKBOX_TICK_OR_CROSS")->tr(language,"找到勾選或交叉形狀，仍需與人工答案核對。","找到勾选或交叉形状，仍须与人工答案核对。","A tick or cross shape was found. Compare it with the reviewed answer.")
        code.startsWith("CIRCLE_AROUND_PRINTED_CORE")->tr(language,"找到環繞印刷數字的圈線，仍需人工核對。","找到环绕印刷数字的圈线，仍须人工核对。","A circle around the printed number was found. Human checking is still required.")
        else->tr(language,"暫不能解釋此判定；請人工查看影像，技術詳情可展開。","暂无法解释该判断；请人工查看图像，可展开技术详情。","Check this option in the image manually. Expand technical details for the original reason.")
    }
}

internal fun recognitionIssueText(issue:String,document:TemplateDraft,language:UiLanguage):String {
    val id=issue.substringAfter(':',"");val q=document.questions.firstOrNull { it.id==id || it.options.any { option->option.id==id } }
    val context=q?.let { "${it.label} ${it.title}".trim() }.orEmpty()
    val text=when(issue.substringBefore(':')) {
        "CORE_CONFIRMATION_REQUIRED"->tr(language,"確認每個印刷數字核心","确认每个印刷数字核心","Check each printed-number core")
        "OPTION_SETTINGS_REQUIRED"->tr(language,"補齊所有選項的辨識範圍","补齐全部选项识别区域","Set a region for every option")
        "INVALID_OPTION_REGION"->tr(language,"選項範圍需位於頁面內","选项区域须在页面内","Keep option regions inside the page")
        "MARK_STYLE_REQUIRED"->tr(language,"選擇標記方式或停用辨識","选择标记方式或停用识别","Select a mark style or disable recognition")
        "ANSWER_REGION_REQUIRED"->tr(language,"圈選有效答案範圍","框选有效答案区域","Draw a valid answer region")
        "TEXT_REQUIRES_MANUAL"->tr(language,"自由文字須使用人工輸入","自由文本须人工输入","Use human entry for free text")
        "OVERLAPPING_OPTION_REGIONS"->tr(language,"選項範圍不能互相重疊","选项区域不能相互重叠","Option regions must not overlap")
        else->tr(language,"請重新檢查設定與題目版本","请重新检查设置及题目版本","Check settings and question versions")
    }
    return if(context.isBlank())text else "$context · $text"
}

internal fun copySamePageRecognitionSettings(source:QuestionDefinition,target:QuestionDefinition,
    sourceSettings:QuestionRecognitionSettings,targetSettings:QuestionRecognitionSettings,
    expectedPageFingerprint:String?,actualPageFingerprint:String):QuestionRecognitionSettings {
    require(source.pageId==target.pageId && expectedPageFingerprint==actualPageFingerprint){"RECOGNITION_PAGE_CHANGED"}
    require(source.type==target.type && source.options.size>=2 && source.options.size==target.options.size){"RECOGNITION_SETTINGS_INVALID"}
    val sourceRegion=requireNotNull(unionRegions(sourceSettings.options.map {it.region}))
    val targetRegion=requireNotNull(unionRegions(targetSettings.options.map {it.region}))
    val options=target.options.mapIndexed {index,option->
        val from=sourceSettings.options.single {it.optionId==source.options[index].id}
        OptionMarkRegion(option.id,remapRegion(from.region,sourceRegion,targetRegion),
            from.printedCore?.let {remapRegion(it,sourceRegion,targetRegion)},false)
    }
    return QuestionRecognitionSettings(target.id,sourceSettings.enabled,sourceSettings.style,options,
        sourceSettings.answerRegion?.let {remapRegion(it,sourceRegion,targetRegion)})
}
