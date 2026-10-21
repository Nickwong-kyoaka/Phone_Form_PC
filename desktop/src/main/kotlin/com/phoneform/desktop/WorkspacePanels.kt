package com.phoneform.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.phoneform.ai.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File

@Composable fun TemplatePanel(controller: Controller, state: AppSnapshot, language: UiLanguage) {
    val draft = state.templateDraft
    val document = draft ?: state.template?.document
    val projectId = state.project?.project?.id ?: return
    val ui by controller.ui.collectAsState()
    val presentation = ui.projects[projectId] ?: ProjectUiState()
    val page = document?.pages?.firstOrNull { it.id == presentation.templatePageId } ?: document?.pages?.minByOrNull { it.position }
    var region by remember(page?.id) { mutableStateOf<NormalizedRect?>(null) }
    val editing = draft?.questions?.firstOrNull { it.id == presentation.templateQuestionId && it.pageId == page?.id }
    var importFile by remember { mutableStateOf<File?>(null) }
    var matrix by remember { mutableStateOf(false) }
    var correcting by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<TemplateAnalysisRun?>(null) }
    LaunchedEffect(draft?.id, state.busy) { suggestions = draft?.let { controller.repositories.intelligence.observeAnalysis(it.id).first() } }
    LaunchedEffect(editing?.id,page?.id) { if(editing != null) region = editing.region }
    fun selectQuestion(question: QuestionDefinition?) {
        controller.updateProjectUi(projectId) { it.copy(templateQuestionId = question?.id, templateInspectorTab = if (question == null) 0 else 1) }
        region = question?.region
    }
    fun addQuestion() {
        if (draft == null || page == null || region == null) return
        val question = QuestionDefinition(newId(), page.id, (draft.questions.filter { it.pageId == page.id }.maxOfOrNull { it.position } ?: -1) + 1, "", "", QuestionType.TEXT, region, region)
        controller.perform { saveTemplate(draft.copy(questions = draft.questions + question)); selectQuestion(question) }
    }
    FileDropTarget(language, enabled = draft != null && !state.busy, onFile = { importFile = it }) {
    Column(Modifier.fillMaxSize()) {
        WorkspaceToolbar(
            tr(language,"範本設定","模板设置","Template setup"),
            if (draft != null) tr(language,"草稿 r${draft.revision} · ${draft.pages.size} 頁","草稿 r${draft.revision} · ${draft.pages.size} 页","Draft r${draft.revision} · ${draft.pages.size} pages")
            else state.template?.let { tr(language,"已確認 v${it.version}","已确认 v${it.version}","Confirmed v${it.version}") }
        ) {
            if (draft == null) Button(onClick = { controller.perform { templateDraft() } }, enabled = !state.busy && state.submissions.none { it.currentRevisionId != null } && state.drafts.isEmpty()) { Text(tr(language,"建立／修改範本","建立／修改模板","Create / edit template")) }
            else {
                OutlinedButton(onClick = { chooseFile(language)?.let { importFile = it } }, enabled = !state.busy) { Text(tr(language,"加入頁面","添加页面","Add pages")) }
                Box {
                    TextButton(onClick = { more = true }) { Text(tr(language,"更多","更多","More") + " ▾") }
                    DropdownMenu(more, { more = false }) {
                        DropdownMenuItem(text = { Text(tr(language,"放棄草稿","放弃草稿","Discard draft")) }, onClick = { more = false; controller.perform { discardTemplate() } }, enabled = !state.busy)
                    }
                }
                Button(onClick = { controller.perform { confirmTemplate() } }, enabled = !state.busy && draft.pages.isNotEmpty()) { Text(tr(language,"確認範本","确认模板","Confirm template")) }
            }
        }
        if (document == null || document.pages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                EmptyState(tr(language,"從一份空白問卷開始","从一份空白问卷开始","Start with a blank questionnaire"),
                    tr(language,"加入 PDF、JPG 或 PNG，確認頁序，再圈選題目和答案區域。","添加 PDF、JPG 或 PNG，确认页序，再圈选题目和答案区域。","Add a PDF, JPG or PNG, arrange its pages, then mark question and answer regions."),
                    if (draft != null) ({ OutlinedButton(onClick = { chooseFile(language)?.let { importFile = it } }) { Text(tr(language,"選擇檔案或拖入此處","选择文件或拖入此处","Choose a file or drop it here")) } }) else null)
            }
        } else Box(Modifier.weight(1f)) {
        DesktopWorkspace(controller, state, language, "template", navigation = {
            Column(Modifier.fillMaxSize()) {
            PanelHeading(tr(language,"頁面","页面","Pages"), tr(language,"依範本固定頁序","按模板固定页序","Fixed template order"))
            LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(document.pages.sortedBy { it.position }, key = { it.id }) { item ->
                    PageThumbnail(controller, item, tr(language,"第 ${item.position+1} 頁","第 ${item.position+1} 页","Page ${item.position+1}") + if(item.noExtraction) tr(language," · 說明"," · 说明"," · Info") else "", item.id == page?.id,
                        { controller.updateProjectUi(projectId) { it.copy(templatePageId = item.id, templateQuestionId = null) }; region = null }, Modifier.fillMaxWidth())
                }
            }
            }
        }, canvas = {
            if (page != null) Column(Modifier.fillMaxSize()) {
                if(draft != null) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick={ addQuestion() },enabled=region!=null && !state.busy) { Text(tr(language,"＋ 題目","＋ 题目","＋ Question")) }
                    TextButton(onClick={matrix=true},enabled=region!=null && !state.busy) { Text(tr(language,"矩陣","矩阵","Matrix")) }
                    VerticalDivider(Modifier.height(18.dp))
                    TextButton(onClick={controller.perform { analyze(page.id,null) }}, enabled = !state.busy) { Text(tr(language,"分析整頁","分析整页","Analyze page")) }
                    TextButton(onClick={controller.perform { analyze(page.id,region) }},enabled=region!=null && !state.busy) { Text(tr(language,"分析選區","分析选区","Analyze selection")) }
                    TextButton(onClick={controller.perform { rotateTemplatePage(page.id) }}, enabled = !state.busy) { Text(tr(language,"旋轉","旋转","Rotate")) }
                    TextButton(onClick={correcting=true}, enabled = !state.busy) { Text(tr(language,"四角校正","四角校正","Perspective")) }
                }
                PageCanvas(controller,page,document.questions.filter { it.pageId==page.id }.mapNotNull { it.region },region,{region=it},language,Modifier.weight(1f).fillMaxWidth(),editable=draft!=null)
                Text(tr(language,"拖動框選題目或答案區域；使用平移工具移動影像。","拖动框选题目或答案区域；使用平移工具移动图像。","Drag to select a question or answer region; use Pan to move the image."), Modifier.padding(10.dp), style=MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }, inspector = {
            Column(Modifier.fillMaxSize()) {
            InspectorTabs(listOf(tr(language,"題目","题目","Questions"), tr(language,"屬性","属性","Properties"), tr(language,"品質","质量","Quality")), presentation.templateInspectorTab.coerceIn(0,2)) { tab -> controller.updateProjectUi(projectId) { it.copy(templateInspectorTab = tab) } }
            val editingQuestion = presentation.templateInspectorTab == 1 && editing != null && draft != null
            Column(Modifier.weight(1f).fillMaxWidth().then(if (editingQuestion) Modifier else Modifier.verticalScroll(rememberScrollState())).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                when (presentation.templateInspectorTab.coerceIn(0,2)) {
                1 -> if(editing!=null && draft!=null) QuestionEditor(controller,draft.projectId,editing,region,language,onCancel={selectQuestion(null)}) { q -> controller.perform {
                    val old = draft.questions.firstOrNull {it.id==q.id}
                    val dissolve = old?.matrixId?.takeIf { old.type!=q.type || old.options.size!=q.options.size }
                    saveTemplate(draft.copy(questions=(draft.questions.filterNot { it.id==q.id }+q).map {if(dissolve!=null&&it.matrixId==dissolve)it.copy(matrixId=null)else it},matrices=draft.matrices.filterNot {it.id==dissolve})); clearEditor("question:${q.id}"); selectQuestion(null)
                } }
                else {
                    Text(tr(language,"頁面屬性","页面属性","Page properties"), style = MaterialTheme.typography.titleSmall)
                    if (page != null) {
                        Text(tr(language,"第 ${page.position+1} 頁 · ${document.questions.count { it.pageId == page.id }} 題","第 ${page.position+1} 页 · ${document.questions.count { it.pageId == page.id }} 题","Page ${page.position+1} · ${document.questions.count { it.pageId == page.id }} questions"))
                        if (draft != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(page.noExtraction, { value -> controller.perform { saveTemplate(draft.copy(pages=draft.pages.map { if(it.id==page.id) it.copy(noExtraction=value) else it })) } }); Text(tr(language,"說明頁，不擷取答案","说明页，不提取答案","Information page; no extraction")) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(enabled = page.position > 0 && !state.busy, onClick = { controller.perform {
                                    val sorted = draft.pages.sortedBy { it.position }.toMutableList(); val index = sorted.indexOfFirst { it.id == page.id }
                                    if(index > 0) { java.util.Collections.swap(sorted,index,index-1); saveTemplate(draft.copy(pages = sorted.mapIndexed { i,p -> p.copy(position=i) })) }
                                } }) { Text(tr(language,"上移","上移","Move up")) }
                                TextButton(enabled = !state.busy, onClick = { controller.perform {
                                    saveTemplate(draft.copy(pages = draft.pages.filterNot { it.id == page.id }.sortedBy { it.position }.mapIndexed { i,p -> p.copy(position=i) },
                                        questions = draft.questions.filterNot { it.pageId == page.id }, matrices = draft.matrices.filterNot { it.pageId == page.id },
                                        mappings = draft.mappings.filterNot { m -> draft.questions.any { it.pageId == page.id && it.id == m.questionId } }))
                                } }) { Text(tr(language,"移除頁面","移除页面","Remove page"), color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                    if (draft != null) {
                        HorizontalDivider()
                        Text(tr(language,"問卷欄位對應","问卷字段映射","Questionnaire field mapping"), style = MaterialTheme.typography.titleSmall)
                        state.project?.fields?.filter { it.source == FieldSource.QUESTIONNAIRE }?.forEach { field ->
                            var expanded by remember(field.id) { mutableStateOf(false) }
                            Text(field.title, style = MaterialTheme.typography.labelLarge)
                            Box { OutlinedButton(onClick={expanded=true}, modifier = Modifier.fillMaxWidth()) { Text(draft.questions.firstOrNull { it.id==draft.mappings.firstOrNull { m->m.fieldId==field.id }?.questionId }?.title?.ifBlank { tr(language,"未命名題目","未命名题目","Untitled question") } ?: tr(language,"選擇題目","选择题目","Choose question"), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                DropdownMenu(expanded,{expanded=false}) { draft.questions.forEach { q -> DropdownMenuItem(text={Text(q.title.ifBlank { q.label.ifBlank { tr(language,"未命名題目","未命名题目","Untitled question") } })},onClick={expanded=false;controller.perform {
                                    saveTemplate(draft.copy(mappings=draft.mappings.filterNot { it.fieldId==field.id }+FieldMapping(field.id,FieldMappingState.BOUND,q.id)))
                                }}) } }
                            }
                        }
                        Text(tr(language,"在「題目」選取項目，可編輯原文、題型及區域。","在“题目”选取项目，可编辑原文、题型和区域。","Select an item in Questions to edit its text, type and regions."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                2 -> {
                    Text(tr(language,"頁面品質","页面质量","Page quality"), style = MaterialTheme.typography.titleSmall)
                    if (draft != null) QualityPanel(controller,PageProcessingOwner(draft.projectId,PageProcessingOwnerKind.TEMPLATE_DRAFT,draft.id,draft.revision),draft.pages,language,state.busy)
                    else Text(tr(language,"已確認範本。建立草稿後可重新檢查頁面。","已确认模板。创建草稿后可重新检查页面。","This template is confirmed. Create a draft to check pages again."), style = MaterialTheme.typography.bodySmall)
                }
                else -> {
                    Text(tr(language,"本頁題目","本页题目","Questions on this page"),style=MaterialTheme.typography.titleSmall)
                    if (document.questions.none { it.pageId == page?.id }) Text(tr(language,"尚未加入題目。在影像上框選區域，然後點選「＋ 題目」。","尚未添加题目。在图像上框选区域，然后点击“＋ 题目”。","No questions yet. Select a region on the image, then choose ＋ Question."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    document.questions.filter { page==null || it.pageId==page.id }.sortedBy { it.position }.forEach { q ->
                        Surface(Modifier.fillMaxWidth().clickable { if(draft!=null) selectQuestion(q) else region = q.region }, shape = MaterialTheme.shapes.small, color = if (q.id == editing?.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f)) { Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text("${q.label} ${q.title}".trim().ifBlank { tr(language,"未命名題目","未命名题目","Untitled question") }, maxLines = 2, overflow = TextOverflow.Ellipsis); Text(enumLabel(language,q.type.name), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            if(draft!=null) TextButton(onClick={controller.perform { saveTemplate(draft.copy(questions=draft.questions.filterNot {it.id==q.id}.map {if(q.matrixId!=null&&it.matrixId==q.matrixId)it.copy(matrixId=null)else it},matrices=draft.matrices.filterNot {it.id==q.matrixId},mappings=draft.mappings.filterNot {it.questionId==q.id})) }}) {Text("×")}
                        } }
                    }
                    suggestions?.suggestions?.filter { it.disposition==SuggestionDisposition.PENDING && it.question.pageId == page?.id }?.forEach { suggestion ->
                        OutlinedCard { Column(Modifier.padding(12.dp)) { Text(tr(language,"辨識候選","识别候选","Recognition suggestion"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary); Text(suggestion.question.title)
                            Row { TextButton(onClick={controller.perform { repositories.intelligence.decideSuggestion(draft!!.id,suggestion.id,true) }}) { Text(tr(language,"加入題目","加入题目","Add question")) }
                                TextButton(onClick={controller.perform { repositories.intelligence.decideSuggestion(draft!!.id,suggestion.id,false) }}) {Text(tr(language,"刪除","删除","Dismiss"))} }
                        } }
                    }
                }
                }
            }
            }
        })
        }
    }
    }
    importFile?.let { file -> ImportDialog(controller,file,null,language,{importFile=null}) { indices,_ -> controller.perform { importTemplate(file,indices); importFile=null } } }
    if(matrix && draft!=null && page!=null && region!=null) MatrixDialog(controller,draft,page.id,region!!,language,{matrix=false}) { updated -> controller.perform { saveTemplate(updated); clearEditor("matrix:${draft.id}:${page.id}"); matrix=false } }
    if(correcting && page!=null) CornerDialog(controller,page,language,{correcting=false}) { corners -> controller.perform {rectifyTemplatePage(page.id,corners);correcting=false} }
}

@Composable private fun QuestionEditor(controller:Controller,projectId:String,question: QuestionDefinition, selected: NormalizedRect?, language: UiLanguage, onCancel:()->Unit,onSave:(QuestionDefinition)->Unit) {
    val session="question:${question.id}"
    var title by encryptedText(controller,session,projectId,"title",question.title); var label by encryptedText(controller,session,projectId,"label",question.label)
    var type by encryptedState(controller,session,projectId,"type",question.type,{QuestionType.valueOf(it)},{it.name}); var region by encryptedState(controller,session,projectId,"region",question.region,{editorJson.fromJson(it,NormalizedRect::class.java)},{editorJson.toJson(it)})
    var answer by encryptedState(controller,session,projectId,"answer",question.answerRegion,{editorJson.fromJson(it,NormalizedRect::class.java)},{editorJson.toJson(it)}); var options by encryptedState(controller,session,projectId,"options",question.options,{editorJson.fromJson(it,Array<OptionDefinition>::class.java).toList()},{editorJson.toJson(it)})
    fun save() { onSave(question.copy(title=title,label=label,type=type,region=region,answerRegion=answer,options=if(type in listOf(QuestionType.SINGLE_CHOICE,QuestionType.MULTIPLE_CHOICE))options else emptyList())) }
    RegisterSaveAction { save() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Text(tr(language,"編輯題目","编辑题目","Edit question"),style=MaterialTheme.typography.titleSmall)
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Field(tr(language,"紙面題號","纸面题号","Printed label"),label,{label=it}); Field(tr(language,"題目原文","题目原文","Question text"),title,{title=it},false)
        EnumChoice(tr(language,"題型","题型","Type"),QuestionType.entries,type,{type=it},language)
        HorizontalDivider()
        Text(tr(language,"影像區域","图像区域","Image regions"), style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick={region=selected},enabled=selected!=null, modifier = Modifier.fillMaxWidth()) {Text(tr(language,"選區 → 題目框","选区 → 题目框","Selection → question")+if(region!=null) " ✓" else "")}
        OutlinedButton(onClick={answer=selected},enabled=selected!=null, modifier = Modifier.fillMaxWidth()) {Text(tr(language,"選區 → 答案框","选区 → 答案框","Selection → answer")+if(answer!=null) " ✓" else "")}
        if(type==QuestionType.SINGLE_CHOICE || type==QuestionType.MULTIPLE_CHOICE) {
            HorizontalDivider()
            Text(tr(language,"選項","选项","Options"), style = MaterialTheme.typography.labelLarge)
            options.forEachIndexed { index,option ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Field(tr(language,"選項 ${index+1} 文字","选项 ${index+1} 文字","Option ${index+1} text"),option.title,{text->options=options.mapIndexed {i,o->if(i==index)o.copy(title=text) else o}})
                    Field(tr(language,"編碼","编码","Code"),option.code,{text->options=options.mapIndexed {i,o->if(i==index)o.copy(code=text) else o}})
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick={options=options.mapIndexed {i,o->if(i==index)o.copy(region=selected) else o}},enabled=selected!=null) {Text(tr(language,"指定選區","指定选区","Assign selection")+if(option.region!=null)" ✓" else "")}
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick={options=options.filterNot {it.id==option.id}}) {Text(tr(language,"移除","移除","Remove"))}
                    }
                }
            }
            OutlinedButton(onClick={options=options+OptionDefinition(newId(),"",(options.size+1).toString())}, modifier = Modifier.fillMaxWidth()) {Text(tr(language,"＋ 新增選項","＋ 新增选项","＋ Add option"))}
        }
    }
    HorizontalDivider()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {TextButton(onClick=onCancel) {Text(tr(language,"返回","返回","Back"))}; Button(onClick={save()}) {Text(tr(language,"保存題目","保存题目","Save question"))}}
    }
}

@Composable private fun MatrixDialog(controller:Controller,draft:TemplateDraft,pageId:String,region:NormalizedRect,language:UiLanguage,close:()->Unit,save:(TemplateDraft)->Unit) {
    val session="matrix:${draft.id}:$pageId"
    var rows by encryptedText(controller,session,draft.projectId,"rows"); var columns by encryptedText(controller,session,draft.projectId,"columns"); var rowWeights by encryptedText(controller,session,draft.projectId,"rowWeights"); var colWeights by encryptedText(controller,session,draft.projectId,"colWeights")
    fun weights(text:String,count:Int):List<Float>? {if(count==0)return null;val values=if(text.isBlank())List(count){1f}else text.split(',').map {it.trim().toFloatOrNull()?:return null};if(values.size!=count||values.any {!it.isFinite()||it<=0})return null;val total=values.sum();var sum=0f;return listOf(0f)+values.map {sum+=it;sum/total}}
    val rowTitles=rows.lines().filter {it.isNotBlank()};val colTitles=columns.lines().filter {it.isNotBlank()};val rb=weights(rowWeights,rowTitles.size);val cb=weights(colWeights,colTitles.size)
    AppDialog(tr(language,"建立矩陣","建立矩阵","Create matrix"),close,width=700.dp,height=720.dp) {Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text(tr(language,"建立矩陣","建立矩阵","Create matrix"), style = MaterialTheme.typography.titleLarge)
        Text(tr(language,"將選區拆分為行題目與選項，生成後仍可逐題調整。","将选区拆分为行题目和选项，生成后仍可逐题调整。","Split the selected grid into questions and options. You can adjust each generated region."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Field(tr(language,"行題目（一行一個，1–20）","行题目（一行一个，1–20）","Row questions (one per line, 1–20)"),rows,{rows=it},false)
        Field(tr(language,"選項（一行一個，2–20）","选项（一行一个，2–20）","Options (one per line, 2–20)"),columns,{columns=it},false)
        Field(tr(language,"行高比例（逗號分隔，留空平均）","行高比例（逗号分隔，留空平均）","Row height weights (comma separated, blank = equal)"),rowWeights,{rowWeights=it})
        Field(tr(language,"列寬比例（逗號分隔，留空平均）","列宽比例（逗号分隔，留空平均）","Column width weights (comma separated, blank = equal)"),colWeights,{colWeights=it})
        Text(tr(language,"選區只包括選項格；行題目在此輸入。","选区只包括选项格；行题目在此输入。","The selected region should contain the option grid only."), style = MaterialTheme.typography.bodySmall)
        if (rows.isNotBlank() && (rowTitles.size !in 1..20 || rb == null) || columns.isNotBlank() && (colTitles.size !in 2..20 || cb == null)) Text(tr(language,"請檢查行列數量及比例；比例須為正數並與行列數一致。","请检查行列数量和比例；比例须为正数并与行列数一致。","Check the counts and weights. Weights must be positive and match the number of rows or columns."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        HorizontalDivider(Modifier.padding(top = 12.dp))
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { Text("${rowTitles.size} × ${colTitles.size}", style = MaterialTheme.typography.labelLarge); Spacer(Modifier.weight(1f)); TextButton(onClick=close){Text(tr(language,"取消","取消","Cancel"))}; Button(enabled=rowTitles.size in 1..20&&colTitles.size in 2..20&&rb!=null&&cb!=null,onClick={
            val matrixId=newId(); val width=region.right-region.left;val height=region.bottom-region.top
            val questions=rowTitles.mapIndexed {r,title->
                val row=NormalizedRect(region.left,region.top+height*rb!![r],region.right,region.top+height*rb[r+1])
                QuestionDefinition(newId(),pageId,draft.questions.size+r,"",title,QuestionType.SINGLE_CHOICE,row,row,colTitles.mapIndexed {c,label->
                    OptionDefinition(newId(),label,(c+1).toString(),NormalizedRect(region.left+width*cb!![c],row.top,region.left+width*cb[c+1],row.bottom))
                },matrixId)
            }
            save(draft.copy(questions=draft.questions+questions,matrices=draft.matrices+MatrixDefinition(matrixId,pageId,region,rb!!,cb!!,questions.map {it.id})))
        }) {Text(tr(language,"生成","生成","Generate"))}}
    }}
}

@Composable fun PageCanvas(controller:Controller,page:SamplePage,boxes:List<NormalizedRect>,selected:NormalizedRect?,onSelect:(NormalizedRect)->Unit,language:UiLanguage,modifier:Modifier=Modifier,editable:Boolean=true) {
    val projectId = controller.state.value.project?.project?.id
    val savedView = remember(projectId,page.id) { controller.ui.value.projects[projectId]?.canvasViews?.get(page.id)?.normalized() ?: CanvasViewState(pan=!editable) }
    var source by remember(projectId,page.id){mutableStateOf(if(editable) "BASE" else savedView.source)}
    var sourceMenu by remember(page.id){mutableStateOf(false)}
    var reload by remember(page.id){mutableIntStateOf(0)}
    var bitmap by remember(page.id,page.revision,page.renderPath) {mutableStateOf<ImageBitmap?>(null)};var error by remember(page.id){mutableStateOf(false)}
    LaunchedEffect(page.id,page.revision,page.renderPath,source,reload) {error=false;bitmap=null;try{bitmap=withContext(Dispatchers.IO){(when(source){"ORIGINAL"->controller.loadOriginalImage(page);"ENHANCED"->controller.loadImage(page,true);else->controller.loadImage(page)}).toComposeImageBitmap()}}catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){error=true}}
    var zoom by remember(projectId,page.id){mutableStateOf(savedView.zoom)};var pan by remember(projectId,page.id){mutableStateOf(savedView.pan)};var offset by remember(projectId,page.id){mutableStateOf(Offset(savedView.offsetX,savedView.offsetY))}
    var pending by remember(page.id){mutableStateOf<NormalizedRect?>(null)};var anchor by remember{mutableStateOf(Offset.Zero)}
    fun persistView() {
        if(projectId != null) {
            val view = CanvasViewState(zoom,offset.x,offset.y,pan,source).normalized()
            controller.updateProjectUi(projectId) { it.copy(canvasViews=it.canvasViews+(page.id to view)) }
        }
    }
    LaunchedEffect(editable) { if (editable && source != "BASE") { source = "BASE"; persistView() } }
    DisposableEffect(projectId,page.id) { onDispose { persistView() } }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment=Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            TextButton(onClick={zoom=(zoom/1.25f).coerceAtLeast(.5f);persistView()}, modifier = Modifier.widthIn(min = 36.dp), contentPadding = PaddingValues(horizontal = 8.dp)){Text("−")}
            Text("${(zoom*100).toInt()}%",style = MaterialTheme.typography.labelMedium)
            TextButton(onClick={zoom=(zoom*1.25f).coerceAtMost(5f);persistView()}, modifier = Modifier.widthIn(min = 36.dp), contentPadding = PaddingValues(horizontal = 8.dp)){Text("+")}
            TextButton(onClick={zoom=1f;offset=Offset.Zero;persistView()}){Text(tr(language,"適合視窗","适合窗口","Fit"))}
            VerticalDivider(Modifier.height(18.dp))
            if(editable) {
                TextButton(onClick = {pan=false;persistView()}, colors = ButtonDefaults.textButtonColors(contentColor = if (!pan) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)) { Text(tr(language,"圈選","圈选","Select")) }
                TextButton(onClick = {pan=true;persistView()}, colors = ButtonDefaults.textButtonColors(contentColor = if (pan) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)) { Text(tr(language,"平移","平移","Pan")) }
            } else Box {
                TextButton(onClick = {sourceMenu=true}) {Text(when(source){"ORIGINAL"->tr(language,"原件","原件","Original");"ENHANCED"->tr(language,"增強圖","增强图","Enhanced");else->tr(language,"校正圖","校正图","Corrected")}+" ▾")}
                DropdownMenu(sourceMenu,{sourceMenu=false}) {
                    listOf("ORIGINAL","BASE","ENHANCED").forEach { value -> DropdownMenuItem(text={Text(when(value){"ORIGINAL"->tr(language,"原件","原件","Original");"ENHANCED"->tr(language,"增強圖","增强图","Enhanced");else->tr(language,"校正圖","校正图","Corrected")})},onClick={source=value;sourceMenu=false;persistView()}) }
                }
            }
        }
        HorizontalDivider()
        val image=bitmap
        if(image==null) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.4f)), contentAlignment = Alignment.Center) {
            if(error) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) { Text(tr(language,"影像無法開啟","图像无法打开","Image unavailable")); TextButton(onClick={reload++}) { Text(tr(language,"重試","重试","Retry")) } } else CircularProgressIndicator(Modifier.size(28.dp),strokeWidth=3.dp)
        }
        if(image!=null) Canvas(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.55f)).pointerInput(page.id,zoom,pan,selected,editable,image) {
            fun fit():Pair<Offset,Size> {val scale=minOf(size.width.toFloat()/image.width,size.height.toFloat()/image.height)*zoom;val sz=Size(image.width*scale,image.height*scale);return Offset((size.width-sz.width)/2+offset.x*size.width,(size.height-sz.height)/2+offset.y*size.height) to sz}
            fun normalized(point:Offset):Offset {val (origin,sz)=fit();return Offset(((point.x-origin.x)/sz.width).coerceIn(0f,1f),((point.y-origin.y)/sz.height).coerceIn(0f,1f))}
            detectDragGestures(onDragStart={position->
                anchor=normalized(position)
                if(editable && !pan && selected!=null){val corners=listOf(Offset(selected.left,selected.top),Offset(selected.right,selected.top),Offset(selected.right,selected.bottom),Offset(selected.left,selected.bottom));val (origin,sz)=fit();val index=corners.indexOfFirst {val px=Offset(origin.x+it.x*sz.width,origin.y+it.y*sz.height);(px-position).getDistance()<14};if(index>=0)anchor=corners[(index+2)%4]}
            },onDrag={change,drag->change.consume();if(pan || !editable)offset=Offset((offset.x+drag.x/size.width.coerceAtLeast(1)).coerceIn(-5f,5f),(offset.y+drag.y/size.height.coerceAtLeast(1)).coerceIn(-5f,5f)) else{val end=normalized(change.position);pending=NormalizedRect(minOf(anchor.x,end.x),minOf(anchor.y,end.y),maxOf(anchor.x,end.x),maxOf(anchor.y,end.y))}},onDragEnd={if(editable)pending?.takeIf {it.isValid()}?.let(onSelect);pending=null;persistView()},onDragCancel={pending=null;persistView()})
        }) {
            val scale=minOf(size.width/image.width,size.height/image.height)*zoom;val w=image.width*scale;val h=image.height*scale
            val left=(size.width-w)/2+offset.x*size.width;val top=(size.height-h)/2+offset.y*size.height
            clipRect { drawImage(image,dstOffset=IntOffset(left.toInt(),top.toInt()),dstSize=IntSize(w.toInt().coerceAtLeast(1),h.toInt().coerceAtLeast(1)))
                fun box(rect:NormalizedRect,color:Color,stroke:Float){drawRect(color,Offset(left+rect.left*w,top+rect.top*h),Size((rect.right-rect.left)*w,(rect.bottom-rect.top)*h),style=Stroke(stroke))}
                if(source!="ORIGINAL") { boxes.forEach {box(it,Color(0xff2563eb),1.5f)}; (pending?:selected)?.let {box(it,Color(0xfff59e0b),3f);listOf(Offset(it.left,it.top),Offset(it.right,it.top),Offset(it.right,it.bottom),Offset(it.left,it.bottom)).forEach {p->drawCircle(Color(0xfff59e0b),4f,Offset(left+p.x*w,top+p.y*h))}} }
            }
        }
    }
}

@Composable fun ImportDialog(controller:Controller,file:File,template:FormSchemaVersion?,language:UiLanguage,close:()->Unit,submit:(List<Int>,List<String>)->Unit) {
    var count by remember(file){mutableStateOf<Int?>(null)};var error by remember{mutableStateOf<String?>(null)};var pages by remember{mutableStateOf("1")}
    var selectedSlots by remember{mutableStateOf<List<String>>(emptyList())};var replace by remember{mutableStateOf(false)}
    var details by remember { mutableStateOf(false) }
    LaunchedEffect(file){try{count=withContext(Dispatchers.IO){if(file.extension.equals("pdf",true))controller.pdfCount(file) else 1};pages=if(count==1)"1" else "1-${count}"}catch(cancelled:CancellationException){throw cancelled}catch(e:Exception){error=Controller.errorCode(e)}}
    val indices=runCatching {parsePages(pages, count?:0)}.getOrNull()
    LaunchedEffect(indices){selectedSlots=indices?.indices?.map {template?.document?.pages?.getOrNull(it)?.id.orEmpty()}.orEmpty()}
    val slotsValid = template == null || (replace && selectedSlots.size==indices?.size && selectedSlots.none {it.isBlank()} && selectedSlots.distinct().size==selectedSlots.size)
    AppDialog(tr(language,"確認匯入","确认导入","Confirm import"),close,width=700.dp,height=740.dp) { Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text(tr(language,"確認匯入","确认导入","Confirm import"), style = MaterialTheme.typography.titleLarge)
        Text(file.name, Modifier.padding(top = 8.dp, bottom = 16.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        if(error!=null) {
            Text(tr(language,"無法讀取來源檔案。請檢查檔案是否損壞或受到密碼保護。","无法读取源文件。请检查文件是否损坏或受到密码保护。","Could not read the source file. Check whether it is damaged or password protected."), color = MaterialTheme.colorScheme.error)
            TextButton(onClick={details=!details}) { Text(tr(language,"技術詳情","技术详情","Technical details")+if(details)" ▴" else " ▾") }; if(details)Text(error!!,style=MaterialTheme.typography.bodySmall)
        } else if(count==null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { CircularProgressIndicator(Modifier.size(24.dp)); Text(tr(language,"正在讀取頁面…","正在读取页面…","Reading pages…")) } else {
            Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha=.45f), shape = MaterialTheme.shapes.small) { Text(if(template == null) tr(language,"共 $count 頁。按所填順序加入範本。","共 $count 页。按填写的顺序加入模板。","$count pages. Add them to the template in the order below.") else tr(language,"共 $count 頁。此次匯入只屬於目前這一份問卷；缺頁保留空頁槽。","共 $count 页。此次导入只属于当前这一份问卷；缺页保留空页槽。","$count pages. This import belongs to the current submission. Missing slots stay empty."), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
            OutlinedTextField(pages,{pages=it}, modifier=Modifier.fillMaxWidth(), singleLine=true,label={Text(tr(language,"來源頁與順序","来源页和顺序","Source pages and order"))},supportingText={Text(if(indices==null)tr(language,"輸入有效頁碼，例如 1,3-4；不可重複。","输入有效页码，例如 1,3-4；不可重复。","Enter valid pages, e.g. 1,3-4, without duplicates.") else tr(language,"已選 ${indices.size} 頁","已选 ${indices.size} 页","${indices.size} pages selected"))},isError=indices==null)
            if(template!=null&&indices!=null) {
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal=8.dp), horizontalArrangement=Arrangement.spacedBy(16.dp)) { Text(tr(language,"來源頁","来源页","Source page"), Modifier.weight(1f), style=MaterialTheme.typography.labelMedium); Text(tr(language,"對應範本頁槽","对应模板页槽","Template slot"), Modifier.weight(1.4f), style=MaterialTheme.typography.labelMedium) }
                indices.forEachIndexed {position,index->var expanded by remember(position){mutableStateOf(false)}
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                        Text(tr(language,"第 ${index+1} 頁","第 ${index+1} 页","Page ${index+1}"), Modifier.weight(1f).padding(start=8.dp))
                        Box(Modifier.weight(1.4f)){OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth()){Text(template.document.pages.firstOrNull {it.id==selectedSlots.getOrNull(position)}?.let {tr(language,"第 ${it.position+1} 頁","第 ${it.position+1} 页","Page ${it.position+1}")}?:tr(language,"選擇頁槽","选择页槽","Choose slot"));Spacer(Modifier.weight(1f));Text("▾")}
                            DropdownMenu(expanded,{expanded=false}){template.document.pages.sortedBy {it.position}.forEach {slot->DropdownMenuItem(text={Text(tr(language,"範本第 ${slot.position+1} 頁","模板第 ${slot.position+1} 页","Template page ${slot.position+1}"))},onClick={expanded=false;selectedSlots=selectedSlots.mapIndexed {i,old->if(i==position)slot.id else old}})}}}
                    }
                }
                if(selectedSlots.distinct().size!=selectedSlots.size)Text(tr(language,"每個來源頁須指定不同的頁槽。","每个源页面须指定不同的页槽。","Assign a different slot to each source page."),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                Row(verticalAlignment=Alignment.Top){Checkbox(replace,{replace=it});Text(tr(language,"我已確認頁序和頁槽；已存在的頁面會由此次匯入取代。","我已确认页序和页槽；已有页面会被此次导入替换。","I have checked the page order and slots. Imported pages will replace existing pages in those slots."),Modifier.padding(top=10.dp),style=MaterialTheme.typography.bodySmall)}
            }
        }
        }
        HorizontalDivider(Modifier.padding(top=12.dp))
        Row(Modifier.fillMaxWidth().padding(top=12.dp),horizontalArrangement=Arrangement.End,verticalAlignment=Alignment.CenterVertically){TextButton(onClick=close){Text(tr(language,"取消","取消","Cancel"))}; Spacer(Modifier.width(8.dp)); Button(enabled=error==null&&indices!=null&&indices.isNotEmpty()&&slotsValid,onClick={submit(indices!!,selectedSlots)}){Text(tr(language,"確認加入","确认加入","Import pages"))}}
    }}
}
fun parsePages(text:String,count:Int):List<Int> {
    require(count>0);val indices=text.split(',').flatMap {part->val range=part.trim().split('-');require(range.size in 1..2);val first=range[0].trim().toInt();val last=range.getOrNull(1)?.trim()?.toInt()?:first;require(first in 1..count&&last in first..count);(first..last).map {it-1}}
    require(indices.isNotEmpty()&&indices.distinct().size==indices.size);return indices
}

@Composable fun QualityPanel(controller:Controller,owner:PageProcessingOwner,pages:List<SamplePage>,language:UiLanguage,refresh:Boolean) {
    var states by remember(owner){mutableStateOf<List<Pair<SamplePage,PageProcessingState>>>(emptyList())}
    val session = "quality:${owner.kind}:${owner.id}:${owner.revision}"
    var reason by encryptedText(controller,session,owner.projectId,"reason")
    var details by remember(owner) { mutableStateOf(false) }
    LaunchedEffect(owner,pages,refresh){while(isActive){states=pages.map {it to controller.repositories.imageProcessing.getPageState(owner,it.id)};delay(800)}}
    val unresolved=states.filter {(_,s)->val result=s.result;s.keepDecision==null&&(result==null||result.report.assessment==PageQualityAssessment.UNKNOWN||result.report.issues.isNotEmpty())}
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        if(states.size != pages.size) Text(tr(language,"正在讀取品質檢查…","正在读取质量检查…","Loading quality checks…"),style=MaterialTheme.typography.bodySmall)
        else if(unresolved.isEmpty()) Text(tr(language,"✓ 所有頁面品質已檢查或已確認保留。","✓ 所有页面质量已检查或已确认保留。","✓ All page checks are resolved."),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
        states.forEach { (page, state) ->
            val result = state.result
            Surface(color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.35f),shape=MaterialTheme.shapes.small) {
                Column(Modifier.fillMaxWidth().padding(10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(tr(language,"第 ${page.position+1} 頁","第 ${page.position+1} 页","Page ${page.position+1}"),style=MaterialTheme.typography.labelLarge)
                    val message = when {
                        state.keepDecision != null -> tr(language,"已確認保留影像","已确认保留图像","Image accepted for use")
                        result != null && result.report.issues.isNotEmpty() -> result.report.issues.joinToString(" · ") { qualityIssueLabel(it.code,language) }
                        result?.report?.assessment == PageQualityAssessment.ASSESSED -> tr(language,"品質檢查完成","质量检查完成","Quality check complete")
                        result != null -> tr(language,"品質尚未確定，請檢查影像","质量尚未确定，请检查图像","Quality unknown; inspect the image")
                        else -> when(state.job?.status) {
                            PageProcessingStatus.RUNNING -> tr(language,"正在檢查影像","正在检查图像","Checking image")
                            PageProcessingStatus.PAUSED -> tr(language,"已暫停","已暂停","Paused")
                            PageProcessingStatus.FAILED -> tr(language,"檢查失敗，請檢查原頁","检查失败，请检查原页","Check failed; inspect the source")
                            PageProcessingStatus.SUPERSEDED -> tr(language,"影像已更新，等待重新檢查","图像已更新，等待重新检查","Image changed; waiting for a new check")
                            else -> tr(language,"等待檢查","等待检查","Waiting for a check")
                        }
                    }
                    Text(message,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if(unresolved.isNotEmpty()) {
            Text(tr(language,"檢查上述影像後，可記錄理由並保留目前版本。","检查以上图像后，可记录理由并保留当前版本。","Inspect these images, then record a reason if you want to keep the current versions."),style=MaterialTheme.typography.bodySmall)
            Field(tr(language,"仍要使用的理由","仍要使用的理由","Reason to keep these pages"),reason,{reason=it},false)
            OutlinedButton(enabled=reason.isNotBlank(),onClick={controller.perform {repositories.imageProcessing.keepAllWarnings(owner,unresolved.associate {(p,_)->p.id to PageProcessingFingerprint.of(p)},reason); clearEditor(session)}}){Text(tr(language,"確認保留當前影像","确认保留当前影像","Keep current images"))}
        }
        if(states.isNotEmpty()) {
            TextButton(onClick={details=!details}) {Text(tr(language,"技術詳情","技术详情","Technical details")+if(details)" ▴" else " ▾")}
            if(details)Text(states.joinToString("\n") { (p,s)->"${p.position+1}: ${s.job?.status?.name ?: "—"} · ${s.result?.report?.engineVersion ?: "—"}" },style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun qualityIssueLabel(code:PageQualityIssueCode,language:UiLanguage):String = when(code) {
    PageQualityIssueCode.BLUR -> tr(language,"影像模糊","图像模糊","Blur")
    PageQualityIssueCode.GLARE -> tr(language,"反光","反光","Glare")
    PageQualityIssueCode.SHADOW -> tr(language,"陰影","阴影","Shadow")
    PageQualityIssueCode.LOW_LIGHT -> tr(language,"光線不足","光线不足","Low light")
    PageQualityIssueCode.LOW_CONTRAST -> tr(language,"對比偏低","对比度低","Low contrast")
    PageQualityIssueCode.CLIPPED_CONTENT -> tr(language,"內容可能被裁切","内容可能被裁切","Content may be clipped")
    PageQualityIssueCode.LOW_RESOLUTION -> tr(language,"解析度偏低","分辨率低","Low resolution")
    PageQualityIssueCode.ENHANCEMENT_UNAVAILABLE -> tr(language,"增強影像不可用","增强图像不可用","Enhancement unavailable")
}
