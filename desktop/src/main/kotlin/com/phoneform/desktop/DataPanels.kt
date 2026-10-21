@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.phoneform.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phoneform.ai.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable fun CollectionPanel(controller: Controller, state: AppSnapshot, language: UiLanguage) {
    val projectId = state.project?.project?.id ?: return
    val desktopUi by controller.ui.collectAsState()
    val ui = desktopUi.projects[projectId] ?: ProjectUiState()
    val template = state.template
    var file by remember(projectId) { mutableStateOf<File?>(null) }
    val draft = state.drafts.firstOrNull { it.id == ui.collectionDraftId }
        ?: state.drafts.firstOrNull { it.baseRevisionId == null } ?: state.drafts.firstOrNull()
    val selectedSlot = template?.document?.pages?.firstOrNull { it.id == ui.collectionPageId }
        ?: template?.document?.pages?.minByOrNull { it.position }
    val page = draft?.pages?.firstOrNull { it.templatePageId == selectedSlot?.id }
    val respondentSession = "respondent:${draft?.id}"
    var respondent by encryptedText(controller, respondentSession, projectId, "value", draft?.respondentId.orEmpty())
    var confirmation by remember(draft?.id) { mutableStateOf(false) }
    var discard by remember(draft?.id) { mutableStateOf(false) }
    var acceptMissing by remember(draft?.id) { mutableStateOf(false) }
    var acceptDuplicates by remember(draft?.id) { mutableStateOf(false) }
    val confirmationSession = "collection-confirm:${draft?.id}:${draft?.editRevision}"
    var repeatReason by encryptedText(controller, confirmationSession, projectId, "repeatReason")
    val paperIdRequired = state.project?.project?.numberingStrategy == NumberingStrategy.PAPER_ID
    val saveId: () -> Unit = {
        draft?.let { current -> controller.perform {
            if (respondent != current.respondentId) repositories.submissions.saveDraft(current.copy(respondentId = respondent))
            clearEditor(respondentSession)
        } }
    }
    RegisterSaveAction(draft != null && !state.busy, saveId)

    if (template == null || state.templateDraft != null) {
        EmptyState(tr(language,"先完成範本設定","先完成模板设置","Finish the template first"),
            tr(language,"確認範本並處理未確認的草稿後，就可以逐份匯入問卷。","确认模板并处理未确认的草稿后，就可以逐份导入问卷。","Confirm the template and resolve its draft before importing questionnaires."))
        return
    }
    Column(Modifier.fillMaxSize()) {
        WorkspaceToolbar(tr(language,"匯入問卷","导入问卷","Import questionnaires"),
            tr(language,"已提交 ${state.submissions.count { it.currentRevisionId != null }} 份 · 每次匯入一份","已提交 ${state.submissions.count { it.currentRevisionId != null }} 份 · 每次导入一份","${state.submissions.count { it.currentRevisionId != null }} submitted · one questionnaire per import")) {
            if (state.drafts.size > 1) {
                var expanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { expanded = true }, enabled = !state.busy) { Text(tr(language,"切換草稿","切换草稿","Switch draft")) }
                    DropdownMenu(expanded, { expanded = false }) {
                        state.drafts.forEach { item ->
                            val number = state.submissions.firstOrNull { it.id == item.submissionId }?.number
                            DropdownMenuItem(text = { Text((if (item.baseRevisionId == null) tr(language,"新問卷","新问卷","New questionnaire") else tr(language,"補頁修訂","补页修订","Page revision")) + " · $number") },
                                onClick = { expanded = false; controller.updateProjectUi(projectId) { it.copy(collectionDraftId = item.id, collectionPageId = null) } })
                        }
                    }
                }
            }
            if (draft != null) OutlinedButton(onClick = { chooseFile(language)?.let { file = it } }, enabled = !state.busy) {
                Text(tr(language,"加入頁面","添加页面","Add pages"))
            } else Button(onClick = { controller.perform { beginCollection() } }, enabled = !state.busy) {
                Text(tr(language,"開始新問卷","开始新问卷","Start questionnaire"))
            }
        }
        if (draft == null) {
            EmptyState(tr(language,"準備匯入問卷","准备导入问卷","Ready to import a questionnaire"),
                tr(language,"開始一份問卷，然後加入 PDF、JPG 或 PNG。範本頁槽固定，缺頁不會令後面的頁面前移。","开始一份问卷，然后添加 PDF、JPG 或 PNG。模板页槽固定，缺页不会令后面的页面前移。","Start a questionnaire, then add PDF, JPG or PNG pages. Missing pages keep their original template slots.")) {
                Button(onClick = { controller.perform { beginCollection() } }, enabled = !state.busy) { Text(tr(language,"開始新問卷","开始新问卷","Start questionnaire")) }
            }
        } else {
            val number = state.submissions.firstOrNull { it.id == draft.submissionId }?.number
            val missingSlots = template.document.pages.filter { slot -> draft.pages.none { it.templatePageId == slot.id } }
            Box(Modifier.weight(1f)) { FileDropTarget(language, !state.busy, { file = it }) {
                DesktopWorkspace(controller, state, language, "collection", navigation = {
                    Column(Modifier.fillMaxSize()) {
                        PanelHeading(tr(language,"頁面","页面","Pages"), "${draft.pages.size} / ${template.document.pages.size}")
                        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(template.document.pages.sortedBy { it.position }, key = { it.id }) { slot ->
                                val captured = draft.pages.firstOrNull { it.templatePageId == slot.id }
                                PageThumbnail(controller, captured?.image,
                                    tr(language,"第 ${slot.position + 1} 頁","第 ${slot.position + 1} 页","Page ${slot.position + 1}") + if (captured == null) " · " + tr(language,"缺頁","缺页","Missing") else "",
                                    selectedSlot?.id == slot.id, { if (!state.busy) controller.updateProjectUi(projectId) { it.copy(collectionPageId = slot.id, collectionDraftId = draft.id) } })
                            }
                        }
                    }
                }, canvas = {
                    if (page != null) PageCanvas(controller, page.image, emptyList(), null, {}, language, Modifier.fillMaxSize(), editable = false)
                    else EmptyState(tr(language,"此頁槽尚未加入頁面","此页槽尚未添加页面","This page slot is empty"),
                        tr(language,"拖入一個 PDF 或圖片，或按「加入頁面」確認來源頁與頁槽。","拖入一个 PDF 或图片，或按“添加页面”确认来源页和页槽。","Drop one PDF or image, or choose Add pages to confirm its source pages and template slots.")) {
                        OutlinedButton(onClick = { chooseFile(language)?.let { file = it } }, enabled = !state.busy) { Text(tr(language,"加入頁面","添加页面","Add pages")) }
                    }
                }, inspector = {
                    Column(Modifier.fillMaxSize()) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            PanelHeading(tr(language,"第 $number 份問卷","第 $number 份问卷","Questionnaire $number"), tr(language,"草稿 r${draft.editRevision}","草稿 r${draft.editRevision}","Draft r${draft.editRevision}"))
                            Field(tr(language,"紙面編號","纸面编号","Paper ID"), respondent, { respondent = it })
                            Text(if (paperIdRequired && respondent.isBlank()) tr(language,"此專案需要紙面編號。請先填寫，再提交。","此项目需要纸面编号。请先填写，再提交。","Paper ID is required for this project. Enter it before submitting.") else tr(language,"以文字保存，保留前導零。","以文字保存，保留前导零。","Stored as text, including leading zeros."), style = MaterialTheme.typography.bodySmall, color = if (paperIdRequired && respondent.isBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(onClick = saveId, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(tr(language,"保存編號","保存编号","Save ID")) }
                            HorizontalDivider()
                            PanelHeading(tr(language,"頁面完整性","页面完整性","Completeness"))
                            Text(if (missingSlots.isEmpty()) tr(language,"全部頁槽已加入頁面","全部页槽已添加页面","All page slots are filled") else tr(language,"缺少第 ${missingSlots.joinToString { "${it.position + 1}" }} 頁","缺少第 ${missingSlots.joinToString { "${it.position + 1}" }} 页","Missing pages ${missingSlots.joinToString { "${it.position + 1}" }}"),
                                color = if (missingSlots.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                            if (page != null) TextButton(onClick = { controller.perform { repositories.submissions.saveDraft(draft.copy(pages = draft.pages.filterNot { it.templatePageId == selectedSlot?.id })) } }, enabled = !state.busy) {
                                Text(tr(language,"移除此頁","移除此页","Remove this page"))
                            }
                            HorizontalDivider()
                            PanelHeading(tr(language,"影像品質","图像质量","Image quality"))
                            QualityPanel(controller, PageProcessingOwner(draft.projectId, PageProcessingOwnerKind.SUBMISSION_DRAFT, draft.id, draft.editRevision), draft.pages.map { it.image }, language, state.busy)
                            Text(tr(language,"提交後保留原件與修訂。補頁或替換頁面會建立新修訂。","提交后保留原件与修订。补页或替换页面会创建新修订。","Submission keeps the originals and revision history. Later page changes create a new revision."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { discard = true }, enabled = !state.busy) { Text(tr(language,"放棄這份草稿","放弃这份草稿","Discard draft"), color = MaterialTheme.colorScheme.error) }
                        }
                        HorizontalDivider()
                        Column(Modifier.padding(16.dp)) {
                            Button(onClick = { confirmation = true; acceptMissing = false; acceptDuplicates = false }, enabled = draft.pages.isNotEmpty() && !state.busy && (!paperIdRequired || respondent.isNotBlank()), modifier = Modifier.fillMaxWidth()) {
                                Text(tr(language,"提交並開始下一份","提交并开始下一份","Submit and start next"))
                            }
                        }
                    }
                })
            } }
        }
    }
    file?.let { source -> ImportDialog(controller, source, template, language, { file = null }) { indices, slots ->
        controller.perform { importSubmission(source, indices, slots, draft?.id); file = null }
    } }
    if (discard && draft != null) AlertDialog(onDismissRequest = { discard = false },
        title = { Text(tr(language,"放棄草稿？","放弃草稿？","Discard this draft?")) },
        text = { Text(tr(language,"目前草稿的頁面與編號將被放棄。已提交的問卷與修訂會保留。","当前草稿的页面和编号将被放弃。已提交的问卷和修订会保留。","The draft pages and ID will be discarded. Submitted questionnaires and revisions remain available.")) },
        confirmButton = { Button(onClick = { controller.perform { repositories.submissions.discardDraft(draft.id, draft.editRevision); clearEditor(respondentSession); clearEditor(confirmationSession); discard = false } }, enabled = !state.busy) { Text(tr(language,"放棄草稿","放弃草稿","Discard draft")) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(tr(language,"取消","取消","Cancel")) } })
    if (confirmation && draft != null) AlertDialog(onDismissRequest = { confirmation = false },
        title = { Text(tr(language,"核對並提交","核对并提交","Check and submit")) },
        text = { Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val missingSlots = template.document.pages.filter { slot -> draft.pages.none { it.templatePageId == slot.id } }
            Text(tr(language,"紙面編號：","纸面编号：","Paper ID: ") + respondent.ifBlank { "—" })
            if (missingSlots.isNotEmpty()) {
                Text(tr(language,"缺少頁槽：","缺少页槽：","Missing slots: ") + missingSlots.joinToString { "${it.position + 1}" }, color = MaterialTheme.colorScheme.error)
                CheckLine(acceptMissing, { acceptMissing = it }, tr(language,"接受以上缺頁，保持固定頁槽","接受以上缺页，保持固定页槽","Accept these missing pages and keep their slots"))
            } else Text(tr(language,"頁槽完整","页槽完整","All page slots are filled"))
            CheckLine(acceptDuplicates, { acceptDuplicates = it }, tr(language,"已核對重複來源提示","已核对重复来源提示","I checked duplicate source pages"))
            Field(tr(language,"重複紙面編號的提交理由（如適用）","重复纸面编号的提交理由（如适用）","Reason for a repeated paper ID, if applicable"), repeatReason, { repeatReason = it }, false)
            Text(tr(language,"影像品質提示仍須先處理。提交成功後會自動開始下一份。","图像质量提示仍须先处理。提交成功后会自动开始下一份。","Resolve any image quality checks first. A new questionnaire starts after submission."), style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { Button(onClick = { controller.perform {
            val saved = if (respondent != draft.respondentId) repositories.submissions.saveDraft(draft.copy(respondentId = respondent)) else draft
            repositories.submissions.submitSubmission(saved.id, saved.editRevision, SubmissionConfirmation(acceptMissing, acceptDuplicates, repeatReason))
            clearEditor(respondentSession); clearEditor(confirmationSession); confirmation = false; beginCollection()
            controller.updateProjectUi(projectId) { it.copy(collectionDraftId = null, collectionPageId = null) }
        } }, enabled = !state.busy && (template.document.pages.all { slot -> draft.pages.any { it.templatePageId == slot.id } } || acceptMissing)) { Text(tr(language,"確認提交","确认提交","Submit")) } },
        dismissButton = { TextButton(onClick = { confirmation = false }) { Text(tr(language,"返回檢查","返回检查","Back")) } })
}

@Composable fun ReviewPanel(controller: Controller, state: AppSnapshot, language: UiLanguage) {
    val projectId = state.project?.project?.id ?: return
    val desktopUi by controller.ui.collectAsState()
    val ui = desktopUi.projects[projectId] ?: ProjectUiState()
    val searchFocus = remember { FocusRequester() }
    if (!ui.reviewWorkspace) RegisterSearchAction { searchFocus.requestFocus() }
    val submissionsById = remember(state.submissions) { state.submissions.associateBy { it.id } }
    val orderedSummaries = remember(state.reviews) { state.reviews.sortedBy { it.number } }
    val summaries = remember(orderedSummaries, submissionsById, ui.reviewFilter, ui.reviewSearch) {
        orderedSummaries.filter { summary ->
            val filterMatch = when (ui.reviewFilter) { "CONFIRMED" -> summary.confirmed; "EXCEPTIONS" -> summary.hasExceptions || summary.missingPageCount > 0; "ALL" -> true; else -> !summary.confirmed }
            val submission = submissionsById[summary.submissionId]
            filterMatch && (ui.reviewSearch.isBlank() || summary.number.toString().contains(ui.reviewSearch.trim(), true) || submission?.respondentId.orEmpty().contains(ui.reviewSearch.trim(), true))
        }
    }
    val activeId = ui.reviewSubmissionId
    var workspace by remember(projectId) { mutableStateOf<ReviewWorkspace?>(null) }
    var loadError by remember(projectId) { mutableStateOf(false) }
    var loading by remember(projectId) { mutableStateOf(false) }
    var reload by remember(projectId) { mutableStateOf(0) }
    var invalidKeys by remember(projectId) { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(activeId, state.reviews, state.busy, reload, ui.reviewWorkspace) {
        if (!ui.reviewWorkspace || activeId == null) { workspace = null; loading = false }
        else {
            loading = true; loadError = false
            try { workspace = withContext(Dispatchers.IO) { controller.repositories.intelligence.getReview(activeId) } }
            catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; workspace = null; loadError = true }
            finally { loading = false }
        }
    }
    fun open(id: String) { controller.updateProjectUi(projectId) { it.copy(reviewSubmissionId = id, reviewTargetKey = null, reviewWorkspace = true) } }
    if (!ui.reviewWorkspace) {
        Column(Modifier.fillMaxSize()) {
            WorkspaceToolbar(tr(language,"人工覆核","人工复核","Review questionnaires"), tr(language,"選擇一份問卷，逐題核對證據與答案。","选择一份问卷，逐题核对证据和答案。","Open a questionnaire to check its evidence and answers.")) {
                Text("${state.reviews.count { !it.confirmed }} " + tr(language,"份待覆核","份待复核","pending"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(ui.reviewSearch, { text -> controller.updateProjectUi(projectId) { it.copy(reviewSearch = text) } },
                    placeholder = { Text(tr(language,"搜尋問卷編號或紙面編號","搜索问卷编号或纸面编号","Search number or paper ID")) }, singleLine = true, modifier = Modifier.weight(1f).focusRequester(searchFocus))
                var expanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { expanded = true }) { Text(reviewFilterLabel(ui.reviewFilter, language)) }
                    DropdownMenu(expanded, { expanded = false }) { listOf("PENDING", "ALL", "CONFIRMED", "EXCEPTIONS").forEach { filter ->
                        DropdownMenuItem(text = { Text(reviewFilterLabel(filter, language)) }, onClick = { expanded = false; controller.updateProjectUi(projectId) { it.copy(reviewFilter = filter) } })
                    } }
                }
            }
            if (summaries.isEmpty()) EmptyState(if (state.reviews.isEmpty()) tr(language,"尚未有已提交問卷","尚无已提交问卷","No submitted questionnaires") else tr(language,"沒有符合條件的問卷","没有符合条件的问卷","No matching questionnaires"),
                if (state.reviews.isEmpty()) tr(language,"提交問卷後，即可在此人工覆核。","提交问卷后，即可在此人工复核。","Submit a questionnaire to begin reviewing.") else tr(language,"更改搜尋文字或篩選條件。","更改搜索文字或筛选条件。","Change the search or filter to see more questionnaires."))
            else BoxWithConstraints(Modifier.weight(1f).padding(horizontal = 20.dp)) {
                val width = maxOf(maxWidth, 800.dp)
                Column(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                    Column(Modifier.width(width).fillMaxHeight()) {
                        TableHeader {
                            TableCell(tr(language,"問卷","问卷","Number"), Modifier.width(88.dp), true)
                            TableCell(tr(language,"紙面編號","纸面编号","Paper ID"), Modifier.weight(1f), true)
                            TableCell(tr(language,"頁面","页面","Pages"), Modifier.width(100.dp), true)
                            TableCell(tr(language,"影像準備","图像准备","Preparation"), Modifier.width(138.dp), true)
                            TableCell(tr(language,"辨識","识别","Recognition"), Modifier.width(138.dp), true)
                            TableCell(tr(language,"覆核狀態","复核状态","Review"), Modifier.width(118.dp), true)
                            Spacer(Modifier.width(72.dp))
                        }
                        LazyColumn(Modifier.weight(1f)) { items(summaries, key = { it.submissionId }) { summary ->
                            val submission = submissionsById[summary.submissionId]
                            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).background(if (activeId == summary.submissionId) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .42f) else MaterialTheme.colorScheme.surface)
                                .combinedClickable(onClick = { controller.updateProjectUi(projectId) { it.copy(reviewSubmissionId = summary.submissionId) } }, onDoubleClick = { open(summary.submissionId) })
                                .onPreviewKeyEvent { event -> if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) { open(summary.submissionId); true } else false }.focusable().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                TableCell(summary.number.toString(), Modifier.width(88.dp))
                                TableCell(submission?.respondentId?.ifBlank { "—" } ?: "—", Modifier.weight(1f))
                                TableCell(if (summary.missingPageCount > 0) tr(language,"缺 ${summary.missingPageCount} 頁","缺 ${summary.missingPageCount} 页","${summary.missingPageCount} missing") else tr(language,"完整","完整","Complete"), Modifier.width(100.dp))
                                TableCell(preparationLabel(summary.preparationStatus, language), Modifier.width(138.dp))
                                TableCell(recognitionLabel(summary.recognitionStatus, language), Modifier.width(138.dp))
                                Box(Modifier.width(118.dp)) { StatusLabel(if (summary.confirmed) tr(language,"已確認","已确认","Confirmed") else tr(language,"待覆核","待复核","Pending"), summary.confirmed) }
                                TextButton(onClick = { open(summary.submissionId) }, modifier = Modifier.width(72.dp)) { Text(tr(language,"打開","打开","Open")) }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
                        } }
                    }
                }
            }
            Text(tr(language,"共 ${summaries.size} 份 · 雙擊或按 Enter 打開","共 ${summaries.size} 份 · 双击或按 Enter 打开","${summaries.size} questionnaires · double-click or press Enter to open"), Modifier.padding(20.dp, 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val review = workspace?.takeIf { it.submission.id == activeId }
    val imagesReady = state.reviews.firstOrNull { it.submissionId == activeId }?.preparationStatus == PreparationJobStatus.READY
    var editorDirty by remember(activeId, review?.revision?.id, review?.answers?.version) { mutableStateOf(false) }
    var saveCurrent by remember(activeId, review?.revision?.id, review?.answers?.version) { mutableStateOf<(() -> Unit)?>(null) }
    var pendingRecoveryKeys by remember(activeId, review?.revision?.id, review?.answers?.version) { mutableStateOf<List<String>>(emptyList()) }
    var recoveryBlocks by remember(activeId, review?.revision?.id, review?.answers?.version) { mutableStateOf<List<AnswerRecoveryBlock>>(emptyList()) }
    var blockedValues by remember(activeId, review?.revision?.id, review?.answers?.version) { mutableStateOf<Map<AnswerRecoveryBlock, String>>(emptyMap()) }
    var showRecovery by remember(activeId) { mutableStateOf(false) }
    var recoveryChecking by remember(activeId, review?.revision?.id, review?.answers?.version) { mutableStateOf(true) }
    var recoveryFailed by remember(activeId, review?.revision?.id, review?.answers?.version) { mutableStateOf(false) }
    var recoveryRetry by remember(activeId) { mutableStateOf(0) }
    LaunchedEffect(review?.revision?.id, review?.answers?.version, recoveryRetry) {
        if (review == null) { recoveryChecking = false; return@LaunchedEffect }
        recoveryChecking = true; recoveryFailed = false
        try {
            val (recovered, blocks, blocked) = withContext(Dispatchers.IO) {
                val current = review.targets.mapNotNull { target ->
                    controller.loadEditorField(answerEditorSession(review, target.key), "value")?.let { target.key to it }
                }.toMap()
                val blocks = controller.loadEditorField(answerRecoveryMarker(review.revision.id), "blocked")?.let { editorJson.fromJson(it, Array<AnswerRecoveryBlock>::class.java).toList() }.orEmpty().filter { it.sourceVersion != (review.answers?.version ?: 0) }
                Triple(current, blocks, blocks.mapNotNull { block -> controller.loadEditorField(answerEditorSession(review, block.targetKey, block.sourceVersion), "value")?.let { block to it } }.toMap())
            }
            pendingRecoveryKeys = dirtyReviewRecoveryTargets(review, recovered)
            recoveryBlocks = blocks; blockedValues = blocked
            val current = controller.ui.value.projects[projectId]?.reviewTargetKey
            if (!editorDirty && pendingRecoveryKeys.isNotEmpty() && current !in pendingRecoveryKeys)
                controller.updateProjectUi(projectId) { it.copy(reviewTargetKey = pendingRecoveryKeys.first()) }
        } catch (failure: kotlinx.coroutines.CancellationException) { throw failure }
        catch (_: Exception) { recoveryFailed = true }
        finally { recoveryChecking = false }
    }
    val guardNavigation = editorDirty || pendingRecoveryKeys.isNotEmpty() || recoveryBlocks.isNotEmpty() || recoveryChecking || recoveryFailed
    Column(Modifier.fillMaxSize()) {
        WorkspaceToolbar(review?.let { tr(language,"第 ${it.submission.number} 份問卷","第 ${it.submission.number} 份问卷","Questionnaire ${it.submission.number}") } ?: tr(language,"人工覆核","人工复核","Review"),
            review?.let { tr(language,"提交修訂 ${it.revision.number} · ${if (it.confirmation != null) "已確認" else "待覆核"}","提交修订 ${it.revision.number} · ${if (it.confirmation != null) "已确认" else "待复核"}","Revision ${it.revision.number} · ${if (it.confirmation != null) "Confirmed" else "Pending review"}") }) {
            TextButton(onClick = { controller.updateProjectUi(projectId) { it.copy(reviewWorkspace = false) } }, enabled = !state.busy && (review == null || !guardNavigation)) { Text(tr(language,"返回列表","返回列表","Back to list")) }
            val ordered = orderedSummaries
            val index = ordered.indexOfFirst { it.submissionId == activeId }
            OutlinedButton(onClick = { ordered.getOrNull(index - 1)?.let { open(it.submissionId) } }, enabled = index > 0 && !state.busy && !guardNavigation) { Text(tr(language,"上一份","上一份","Previous")) }
            OutlinedButton(onClick = { ordered.getOrNull(index + 1)?.let { open(it.submissionId) } }, enabled = index >= 0 && index < ordered.lastIndex && !state.busy && !guardNavigation) { Text(tr(language,"下一份","下一份","Next")) }
            if (review != null) {
                var more by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { more = true }, enabled = !state.busy) { Text(tr(language,"更多","更多","More")) }
                    DropdownMenu(more, { more = false }) {
                        DropdownMenuItem(text = { Text(tr(language,"補頁／替換修訂","补页／替换修订","Create page revision")) }, onClick = { more = false; controller.perform { val draft = repositories.submissions.beginRevision(review.submission.id); controller.updateProjectUi(projectId) { it.copy(collectionDraftId = draft.id) }; navigate(WorkspaceSection.COLLECTION) } }, enabled = !state.busy && !guardNavigation)
                        DropdownMenuItem(text = { Text(tr(language,"重試辨識","重试识别","Retry recognition")) }, onClick = { more = false; controller.perform { review.job?.let { repositories.intelligence.retryRecognition(it.id) } } }, enabled = !state.busy && review.job?.status in listOf(RecognitionJobStatus.FAILED, RecognitionJobStatus.PAUSED))
                    }
                }
                Button(onClick = {
                    val invalid = unresolvedReviewTargets(review)
                    if (invalid.isNotEmpty()) invalidKeys = invalid else controller.perform {
                        try {
                            val buffers = review.targets.mapNotNull { target -> loadEditorField(answerEditorSession(review, target.key), "value")?.let { target.key to it } }.toMap()
                            val unsaved = dirtyReviewRecoveryTargets(review, buffers)
                            val blocked = loadEditorField(answerRecoveryMarker(review.revision.id), "blocked")?.let { editorJson.fromJson(it, Array<AnswerRecoveryBlock>::class.java).toList() }.orEmpty().any { it.sourceVersion != (review.answers?.version ?: 0) }
                            if (unsaved.isNotEmpty()) {
                                pendingRecoveryKeys = unsaved
                                controller.updateProjectUi(projectId) { it.copy(reviewTargetKey = unsaved.first()) }
                            } else if (blocked) recoveryRetry++
                            else { repositories.intelligence.confirmReview(review.submission.id, review.revision.id, review.answers?.version ?: 0, newId()); reload++ }
                        }
                        catch (e: ReviewValidationException) { invalidKeys = e.targetKeys }
                    }
                }, enabled = !state.busy && !loading && imagesReady && !guardNavigation && review.confirmation == null) { Text(tr(language,"確認整份","确认整份","Confirm submission")) }
            }
        }
        if (review == null) {
            if (loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else EmptyState(tr(language,"無法打開這份問卷","无法打开这份问卷","Questionnaire unavailable"),
                if (loadError) tr(language,"讀取失敗。可以重試或返回列表，未保存輸入會保留。","读取失败。可以重试或返回列表，未保存输入会保留。","Loading failed. Retry or return to the list; saved editor recovery data is retained.") else tr(language,"問卷可能已刪除或尚未完成提交。","问卷可能已删除或尚未完成提交。","The questionnaire may have been deleted or is not yet submitted.")) {
                OutlinedButton(onClick = { reload++ }) { Text(tr(language,"重試","重试","Retry")) }
            }
        } else {
            if (editorDirty || pendingRecoveryKeys.isNotEmpty() || recoveryBlocks.isNotEmpty() || recoveryFailed) Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (recoveryFailed) tr(language,"未能讀取編輯恢復資料。請重試後才確認。","未能读取编辑恢复资料。请重试后再确认。","Editor recovery could not be checked. Retry before confirming.") else if (recoveryBlocks.isNotEmpty()) tr(language,"先前的編輯輸入需要核對，原恢復資料已保留。","先前的编辑输入需要核对，原恢复资料已保留。","Earlier editor drafts need attention. Their original recovery data is retained.") else tr(language,"請先保存目前答案，再切換題目或確認整份。","请先保存当前答案，再切换题目或确认整份。","Save the current answer before changing targets or confirming."), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                if (recoveryFailed) OutlinedButton(onClick = { recoveryRetry++ }, enabled = !state.busy) { Text(tr(language,"重試","重试","Retry")) }
                else if (recoveryBlocks.isNotEmpty()) OutlinedButton(onClick = { showRecovery = true }, enabled = !state.busy) { Text(tr(language,"查看恢復輸入","查看恢复输入","View recovered drafts")) }
                else Button(onClick = { saveCurrent?.invoke() }, enabled = !state.busy && imagesReady && saveCurrent != null) { Text(tr(language,"保存目前答案","保存当前答案","Save current answer")) }
            }
            if (!imagesReady) Text(tr(language,"影像準備尚未完成；完成後即可保存答案與確認。失敗的任務可在任務面板重試。","图像准备尚未完成；完成后即可保存答案和确认。失败的任务可在任务面板重试。","Image preparation must finish before answers can be saved or confirmed. Retry failed tasks in the task panel."), Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(12.dp), style = MaterialTheme.typography.bodySmall)
            val target = review.targets.firstOrNull { it.key == ui.reviewTargetKey } ?: review.targets.firstOrNull()
            Box(Modifier.weight(1f)) { DesktopWorkspace(controller, state, language, "review", navigation = {
                Column(Modifier.fillMaxSize()) {
                    PanelHeading(tr(language,"題目與欄位","题目和字段","Questions & fields"), "${review.targets.size}")
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(8.dp)) { items(review.targets, key = { it.key }) { item ->
                        val answer = review.answers?.answers?.firstOrNull { it.targetKey == item.key }
                        val complete = answer?.let { ReviewTargets.valid(it, item) } == true || item.question?.pageId in review.revision.missingTemplatePageIds
                        Surface(color = if (target?.key == item.key) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).clickable(enabled = !state.busy && !guardNavigation) { controller.updateProjectUi(projectId) { it.copy(reviewTargetKey = item.key) } }) {
                            Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(if (complete) "✓" else "○", color = if (complete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(item.title, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    } }
                }
            }, canvas = {
                val image = review.revision.pages.firstOrNull { it.templatePageId == target?.question?.pageId }?.image
                val observation = review.job?.observations?.firstOrNull { it.targetKey == target?.key }
                if (image != null) PageCanvas(controller, image, listOfNotNull(observation?.evidenceRegion), null, {}, language, Modifier.fillMaxSize(), editable = false)
                else EmptyState(if (target?.question == null) tr(language,"人工欄位","人工字段","Manual field") else tr(language,"此頁未拍到","此页未拍到","Page not captured"),
                    if (target?.question == null) tr(language,"在右側輸入資料，這個欄位沒有紙面證據。","在右侧输入资料，这个字段没有纸面证据。","Enter the value in the answer panel. This field has no paper evidence.") else tr(language,"保持「未拍到」狀態，或透過補頁修訂加入此頁。","保持“未拍到”状态，或通过补页修订添加此页。","This answer remains Not captured. Add the page through a page revision if needed."))
            }, inspector = {
                if (target != null) {
                    val index = review.targets.indexOf(target)
                    AnswerEditor(controller, review, target, review.job?.observations?.firstOrNull { it.targetKey == target.key }, language, state.busy, imagesReady,
                        previous = if (guardNavigation) null else review.targets.getOrNull(index - 1)?.let { item -> { controller.updateProjectUi(projectId) { it.copy(reviewTargetKey = item.key) } } },
                        next = if (guardNavigation) null else review.targets.getOrNull(index + 1)?.let { item -> { controller.updateProjectUi(projectId) { it.copy(reviewTargetKey = item.key) } } },
                        position = "${index + 1} / ${review.targets.size}", onDirtyChanged = { dirty ->
                            editorDirty = dirty
                            if (dirty) pendingRecoveryKeys = (pendingRecoveryKeys + target.key).distinct()
                        }, onSaveAction = { saveCurrent = it }) {
                        reload++; controller.updateProjectUi(projectId) { it.copy(reviewTargetKey = review.targets.getOrNull(index + 1)?.key ?: target.key) }
                    }
                } else EmptyState(tr(language,"沒有覆核欄位","没有复核字段","No review fields"), tr(language,"此範本沒有需要覆核的題目或人工欄位。","此模板没有需要复核的题目或人工字段。","This template has no questions or manual fields to review."))
            }) }
        }
    }
    invalidKeys?.let { keys -> AlertDialog(onDismissRequest = { invalidKeys = null },
        title = { Text(tr(language,"還有 ${keys.size} 項待完成","还有 ${keys.size} 项待完成","${keys.size} items need attention")) },
        text = { Column {
            Text(tr(language,"先保存目前輸入，再完成以下題目。按題目可直接定位。","先保存当前输入，再完成以下题目。点击题目可直接定位。","Save current edits and resolve the following items. Select an item to open it."))
            LazyColumn(Modifier.heightIn(max = 320.dp)) { items(keys) { key -> TextButton(onClick = { controller.updateProjectUi(projectId) { it.copy(reviewTargetKey = key) }; invalidKeys = null }) { Text(workspace?.targets?.firstOrNull { it.key == key }?.title ?: key) } } }
        } }, confirmButton = { TextButton(onClick = { invalidKeys = null }) { Text(tr(language,"繼續覆核","继续复核","Continue review")) } }) }
    if (showRecovery && review != null) AlertDialog(onDismissRequest = { showRecovery = false },
        title = { Text(tr(language,"保留的編輯輸入","保留的编辑输入","Retained editor drafts")) },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr(language,"已保存答案發生改變，以下輸入未自動套用。可複製需要保留的文字，再放棄這些恢復草稿；已保存答案不受影響。","已保存答案发生改变，以下输入未自动应用。可复制需要保留的文字，再放弃这些恢复草稿；已保存答案不受影响。","Saved answers changed, so these drafts were not applied. Copy any text you need before discarding the recovery drafts. Saved answers remain unchanged."))
            recoveryBlocks.forEach { block ->
                Text(review.targets.firstOrNull { it.key == block.targetKey }?.title ?: block.targetKey, style = MaterialTheme.typography.titleSmall)
                val draft = runCatching { editorJson.fromJson(blockedValues[block], AnswerValue::class.java) }.getOrNull()
                SelectionContainer { Text(draft?.let { it.text.ifBlank { it.optionIds.joinToString().ifBlank { enumLabel(language, it.state.name) } } } ?: tr(language,"無法讀取此草稿","无法读取此草稿","Draft could not be read")) }
            }
        } },
        confirmButton = { Button(onClick = { controller.perform {
            recoveryBlocks.forEach { block -> clearEditor(answerEditorSession(review, block.targetKey, block.sourceVersion)) }
            clearEditor(answerRecoveryMarker(review.revision.id)); showRecovery = false; recoveryRetry++
        } }, enabled = !state.busy) { Text(tr(language,"放棄以上恢復草稿","放弃以上恢复草稿","Discard these drafts")) } },
        dismissButton = { TextButton(onClick = { showRecovery = false }) { Text(tr(language,"繼續保留","继续保留","Keep drafts")) } })
}

/** Matches confirmation's safe-candidate resolution; the repository remains authoritative. */
internal fun unresolvedReviewTargets(review: ReviewWorkspace): List<String> {
    val safe = review.job?.takeIf { it.status == RecognitionJobStatus.READY }?.observations.orEmpty().filterNot { it.requiresReview }.associateBy { it.targetKey }
    return review.targets.filter { target ->
        val initial = initialReviewAnswer(review, target)
        val resolved = if (initial.state == AnswerState.UNHANDLED && !initial.humanEdited) safe[target.key]?.candidate ?: initial else initial
        !ReviewTargets.valid(resolved, target)
    }.map { it.key }
}

internal fun initialReviewAnswer(review: ReviewWorkspace, target: AnswerTarget): AnswerValue =
    if (target.question?.pageId in review.revision.missingTemplatePageIds) AnswerValue(target.key, state = AnswerState.NOT_CAPTURED)
    else review.answers?.answers?.firstOrNull { it.targetKey == target.key } ?: AnswerValue(target.key)

internal fun answerEditorSession(review: ReviewWorkspace, targetKey: String, version: Int = review.answers?.version ?: 0) = "answer:${review.revision.id}:$targetKey:$version"
private fun answerRecoveryMarker(revisionId: String) = "answer-recovery:$revisionId"
internal data class AnswerRecoveryBlock(val targetKey: String, val sourceVersion: Int)
internal data class AnswerRecoveryCarryForward(val copies: Map<String, String>, val blockedKeys: List<String>)

/** Recovery buffers are drafts. Inspecting or carrying one never applies it as an answer. */
internal fun dirtyReviewRecoveryTargets(review: ReviewWorkspace, raw: Map<String, String>): List<String> = review.targets.filter { target ->
    raw[target.key]?.let { serialized ->
        val value = requireNotNull(editorJson.fromJson(serialized, AnswerValue::class.java))
        require(value.targetKey == target.key) { "RECOVERY_TARGET_CHANGED" }
        value != initialReviewAnswer(review, target)
    } ?: false
}.map { it.key }

internal fun planAnswerRecoveryCarryForward(before: ReviewWorkspace, after: AnswerSetRevision, pending: Map<String, String>): AnswerRecoveryCarryForward {
    val sameTransaction = after.submissionId == before.submission.id && after.submissionRevisionId == before.revision.id &&
        after.projectId == before.project.project.id && after.version == (before.answers?.version ?: 0) + 1
    val unchanged = before.targets.filter { target -> sameTransaction && after.answers.firstOrNull { it.targetKey == target.key } == initialReviewAnswer(before, target) }.map { it.key }.toSet()
    return AnswerRecoveryCarryForward(pending.filterKeys { it in unchanged }, pending.keys.filterNot { it in unchanged })
}

@Composable private fun AnswerEditor(controller: Controller, review: ReviewWorkspace, target: AnswerTarget, observation: FieldObservation?, language: UiLanguage, busy: Boolean, imagesReady: Boolean, previous: (() -> Unit)?, next: (() -> Unit)?, position: String, onDirtyChanged: (Boolean) -> Unit, onSaveAction: ((() -> Unit) -> Unit), afterSave: () -> Unit) {
    val existing = review.answers?.answers?.firstOrNull { it.targetKey == target.key }
    val missing = target.question?.pageId in review.revision.missingTemplatePageIds
    val initial = initialReviewAnswer(review, target)
    val session = answerEditorSession(review, target.key)
    var value by encryptedState(controller, session, review.project.project.id, "value", initial, { editorJson.fromJson(it, AnswerValue::class.java) }, { editorJson.toJson(it) })
    var details by remember(target.key) { mutableStateOf(false) }
    val save: () -> Unit = { controller.perform {
        val pending = review.targets.filterNot { it.key == target.key }.mapNotNull { other -> loadEditorField(answerEditorSession(review, other.key), "value")?.let { other.key to it } }.toMap()
        val dirtyKeys = dirtyReviewRecoveryTargets(review, pending)
        val marker = answerRecoveryMarker(review.revision.id)
        val oldVersion = review.answers?.version ?: 0
        val prior = loadEditorField(marker, "blocked")?.let { editorJson.fromJson(it, Array<AnswerRecoveryBlock>::class.java).toList() }.orEmpty()
        // Persist the source locations first, so a crash after the answer save cannot hide them.
        if (dirtyKeys.isNotEmpty()) {
            queueEditorField(marker, review.project.project.id, "blocked", editorJson.toJson((prior + dirtyKeys.map { AnswerRecoveryBlock(it, oldVersion) }).distinct()))
            flushEdits()
        }
        val saved = saveAnswer(review, value.copy(humanEdited = true))
        val plan = planAnswerRecoveryCarryForward(review, saved, pending.filterKeys { it in dirtyKeys })
        plan.copies.forEach { (key, raw) -> queueEditorField(answerEditorSession(review, key, saved.version), review.project.project.id, "value", raw) }
        // Keep every source recovery buffer if this encrypted flush fails.
        flushEdits()
        val outstanding = (prior.filterNot { it.sourceVersion == oldVersion && it.targetKey in plan.copies } + plan.blockedKeys.map { AnswerRecoveryBlock(it, oldVersion) }).distinct()
        if (outstanding.isEmpty()) clearEditor(marker) else {
            queueEditorField(marker, review.project.project.id, "blocked", editorJson.toJson(outstanding)); flushEdits()
        }
        clearEditor(session)
        plan.copies.keys.forEach { key -> clearEditor(answerEditorSession(review, key)) }
        onDirtyChanged(false); afterSave()
    } }
    val latestSave by rememberUpdatedState(save)
    val registeredSave = remember(session) { { latestSave() } }
    SideEffect { onDirtyChanged(!missing && value != initial); onSaveAction(registeredSave) }
    RegisterSaveAction(!missing && !busy && imagesReady, save)
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            PanelHeading(target.title, position)
            if (review.confirmation != null) Text(tr(language,"此份已確認。修改答案會撤銷目前確認。","此份已确认。修改答案会撤销当前确认。","This questionnaire is confirmed. Saving an edit invalidates its current confirmation."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            if (missing) {
                StatusLabel(tr(language,"未拍到","未拍到","Not captured"), false)
                Text(tr(language,"此狀態由缺頁決定。要輸入答案，請先補頁。","此状态由缺页决定。要输入答案，请先补页。","The missing page determines this state. Create a page revision before entering an answer."))
            } else {
                if (observation != null) {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f), shape = RoundedCornerShape(8.dp)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(tr(language,"機器候選 · 未確認","机器候选 · 未确认","Machine candidate · unconfirmed"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(observation.candidate?.let { candidate -> candidate.text.ifEmpty { candidate.optionIds.joinToString { id -> target.question?.options?.firstOrNull { it.id == id }?.title ?: id } } }?.ifBlank { "—" } ?: "—")
                            observation.candidate?.let { candidate -> OutlinedButton(onClick = { value = candidate.copy(source = AnswerSource.MACHINE_ACCEPTED, humanEdited = true) }, enabled = !busy) { Text(tr(language,"採用候選","采用候选","Use candidate")) } }
                            if (observation.requiresReview) Text(tr(language,"需要人工核對後保存","需要人工核对后保存","Check this candidate before saving"), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Text(tr(language,"人工答案","人工答案","Reviewed answer"), style = MaterialTheme.typography.titleSmall)
                EnumChoice(tr(language,"答案狀態","答案状态","Answer state"), AnswerState.entries.filterNot { it == AnswerState.NOT_CAPTURED }, value.state, { value = value.copy(state = it, text = "", optionIds = emptyList(), source = AnswerSource.MANUAL, humanEdited = true) }, language)
                if (value.state == AnswerState.VALUE) {
                    val question = target.question
                    if (question?.type in listOf(QuestionType.SINGLE_CHOICE, QuestionType.MULTIPLE_CHOICE)) question!!.options.forEach { option ->
                        val selected = option.id in value.optionIds
                        Row(Modifier.fillMaxWidth().clickable(enabled = !busy) {
                            value = value.copy(optionIds = if (question.type == QuestionType.SINGLE_CHOICE) listOf(option.id) else if (selected) value.optionIds - option.id else value.optionIds + option.id, text = "", source = AnswerSource.MANUAL, humanEdited = true)
                        }, verticalAlignment = Alignment.CenterVertically) {
                            if (question.type == QuestionType.SINGLE_CHOICE) RadioButton(selected, null) else Checkbox(selected, null)
                            Text(option.title + " (${option.code})", Modifier.padding(start = 8.dp))
                        }
                    } else Field(if (question?.type == QuestionType.DATE) tr(language,"日期 YYYY-MM-DD","日期 YYYY-MM-DD","Date YYYY-MM-DD") else tr(language,"答案","答案","Answer"), value.text, { value = value.copy(text = it, source = AnswerSource.MANUAL, humanEdited = true) }, false)
                }
                Text(tr(language,"可以保存未完成的輸入；整份確認時檢查所有答案。","可以保存未完成的输入；整份确认时检查所有答案。","You can save incomplete input. Confirmation checks every answer."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
            TextButton(onClick = { details = !details }) { Text(tr(language,"來源與辨識詳情","来源和识别详情","Source and recognition details") + if (details) " −" else " +") }
            if (details) {
                Text(tr(language,"提交修訂：","提交修订：","Submission revision: ") + review.revision.number, style = MaterialTheme.typography.bodySmall)
                Text(tr(language,"答案來源：","答案来源：","Answer source: ") + answerSourceLabel(existing?.source, language), style = MaterialTheme.typography.bodySmall)
                if (observation != null) SelectionContainer {
                    Text(listOf(observation.reason, observation.rawText, observation.enhancedRawText, observation.engineVersion).filter { it.isNotBlank() }.joinToString("\n\n"), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        HorizontalDivider()
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!missing) Button(onClick = save, enabled = !busy && imagesReady, modifier = Modifier.fillMaxWidth()) { Text(tr(language,"保存並下一題","保存并下一题","Save and next")) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { previous?.invoke() }, enabled = previous != null && !busy, modifier = Modifier.weight(1f)) { Text(tr(language,"上一題","上一题","Previous")) }
                OutlinedButton(onClick = { next?.invoke() }, enabled = next != null && !busy, modifier = Modifier.weight(1f)) { Text(tr(language,"下一題","下一题","Next")) }
            }
        }
    }
}

@Composable fun ExportPanel(controller: Controller, state: AppSnapshot, language: UiLanguage) {
    val project = state.project ?: return
    val projectId = project.project.id
    val desktopUi by controller.ui.collectAsState()
    val ui = desktopUi.projects[projectId] ?: ProjectUiState()
    val session = "output:$projectId:${project.output.version}"
    var headerLanguage by encryptedState(controller, session, projectId, "language", project.output.language, { OutputHeaderLanguage.valueOf(it) }, { it.name })
    var personal by encryptedState(controller, session, projectId, "personal", project.output.includePersonalIdentifiers, { it.toBooleanStrict() }, { it.toString() })
    var policies by encryptedState(controller, session, projectId, "policies", state.policies, { editorJson.fromJson(it, Array<OutputFieldPolicy>::class.java).toList() }, { editorJson.toJson(it) })
    var layout by encryptedState(controller, session, projectId, "layout", project.output.columnLayout, { editorJson.fromJson(it, Array<OutputColumnLayout>::class.java).toList() }, { editorJson.toJson(it) })
    var includeUnresolved by remember(projectId) { mutableStateOf(false) }
    var newSnapshot by remember(projectId) { mutableStateOf<ExportSnapshot?>(null) }
    var fieldsTab by remember(projectId) { mutableStateOf(0) }
    val searchFocus = remember { FocusRequester() }
    if (ui.exportTab == 1) RegisterSearchAction { searchFocus.requestFocus() }
    val targets = state.template?.let { ReviewTargets.build(project, it) }.orEmpty()
    val preview = TemplateColumns.build(project.copy(output = project.output.copy(language = headerLanguage, includePersonalIdentifiers = personal, columnLayout = layout)), state.template?.document, policies)
    val dirty = headerLanguage != project.output.language || personal != project.output.includePersonalIdentifiers || policies != state.policies || layout != project.output.columnLayout
    val save: () -> Unit = { controller.perform {
        repositories.projects.saveOutputConfiguration(project.copy(output = project.output.copy(language = headerLanguage, includePersonalIdentifiers = personal, columnLayout = layout)), policies, project.output.version)
        clearEditor(session)
    } }
    RegisterSaveAction(dirty && !state.busy, save)
    Column(Modifier.fillMaxSize()) {
        WorkspaceToolbar(tr(language,"Excel 匯出","Excel 导出","Export to Excel"), tr(language,"設定欄位與隱私，建立固定的四表快照。","设置字段和隐私，创建固定的四表快照。","Configure fields and privacy, then freeze a four-sheet snapshot.")) {
            Button(onClick = { controller.perform {
                if (dirty) {
                    repositories.projects.saveOutputConfiguration(project.copy(output = project.output.copy(language = headerLanguage, includePersonalIdentifiers = personal, columnLayout = layout)), policies, project.output.version)
                    clearEditor(session)
                }
                newSnapshot = export(projectId, includeUnresolved)
                controller.updateProjectUi(projectId) { it.copy(exportTab = 2) }
            } }, enabled = state.template != null && state.submissions.any { it.currentRevisionId != null } && !state.busy) { Text(tr(language,"產生 Excel 快照","生成 Excel 快照","Generate Excel snapshot")) }
        }
        InspectorTabs(listOf(tr(language,"輸出設定","输出设置","Output settings"), tr(language,"欄位與列","字段和列","Fields & columns"), tr(language,"歷史快照","历史快照","Snapshot history")), ui.exportTab.coerceIn(0, 2)) { index -> controller.updateProjectUi(projectId) { it.copy(exportTab = index) } }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (ui.exportTab) {
                1 -> Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(ui.exportSearch, { text -> controller.updateProjectUi(projectId) { it.copy(exportSearch = text) } }, placeholder = { Text(tr(language,"搜尋欄位或標題","搜索字段或标题","Search fields or headers")) }, singleLine = true, modifier = Modifier.weight(1f).focusRequester(searchFocus))
                        TextButton(onClick = { fieldsTab = 0 }) { Text(tr(language,"包含與隱私","包含和隐私","Inclusion & privacy"), color = if (fieldsTab == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                        TextButton(onClick = { fieldsTab = 1 }) { Text(tr(language,"Excel 列順序","Excel 列顺序","Excel columns"), color = if (fieldsTab == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    if (fieldsTab == 0) {
                        val visible = targets.filter { ui.exportSearch.isBlank() || it.title.contains(ui.exportSearch.trim(), true) }
                        TableHeader {
                            TableCell(tr(language,"欄位／題目","字段／题目","Field / question"), Modifier.weight(1f), true)
                            TableCell(tr(language,"包含","包含","Include"), Modifier.width(90.dp), true)
                            TableCell(tr(language,"個人標識","个人标识","Personal ID"), Modifier.width(120.dp), true)
                        }
                        LazyColumn(Modifier.weight(1f)) { items(visible, key = { it.key }) { target ->
                            val policy = policies.firstOrNull { it.targetKey == target.key } ?: OutputFieldPolicy(projectId, target.key, false)
                            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                    Text(target.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (policy.personalIdentifier && !personal && policy.exportIncluded) Text(tr(language,"隱私設定會排除此欄位","隐私设置会排除此字段","Excluded by the privacy setting"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Box(Modifier.width(90.dp), contentAlignment = Alignment.CenterStart) { Checkbox(policy.exportIncluded, { included -> policies = policies.filterNot { it.targetKey == target.key } + policy.copy(exportIncluded = included) }, enabled = !state.busy) }
                                Box(Modifier.width(120.dp), contentAlignment = Alignment.CenterStart) { Checkbox(policy.personalIdentifier, { private -> policies = policies.filterNot { it.targetKey == target.key } + policy.copy(personalIdentifier = private) }, enabled = !state.busy) }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
                        } }
                        if (visible.isEmpty()) Text(tr(language,"沒有符合的欄位。","没有符合的字段。","No matching fields."), Modifier.padding(16.dp))
                    } else {
                        val visible = preview.withIndex().filter { ui.exportSearch.isBlank() || it.value.title.contains(ui.exportSearch.trim(), true) }
                        Text(tr(language,"前六列固定；其餘列可改標題與排序。紙面編號由隱私設定控制。","前六列固定；其余列可改标题和排序。纸面编号由隐私设置控制。","The first six columns are fixed. Rename or reorder data columns. The privacy setting controls Paper ID."), Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TableHeader {
                            TableCell("#", Modifier.width(40.dp), true)
                            TableCell(tr(language,"Excel 標題","Excel 标题","Excel header"), Modifier.weight(1f), true)
                            TableCell(tr(language,"順序","顺序","Order"), Modifier.width(100.dp), true)
                        }
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(vertical = 8.dp)) { items(visible, key = { it.value.key }) { (index, column) ->
                            val locked = column.key in fixedOutputKeys
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${index + 1}", Modifier.width(32.dp), style = MaterialTheme.typography.bodySmall)
                                if (locked) Row(Modifier.weight(1f).heightIn(min = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(column.title, Modifier.weight(1f))
                                    StatusLabel(tr(language,"固定","固定","Locked"), false)
                                } else OutlinedTextField(layout.firstOrNull { it.key == column.key }?.titleOverride ?: column.title,
                                    { title -> layout = layout.filterNot { it.key == column.key } + OutputColumnLayout(column.key, title, layout.firstOrNull { it.key == column.key }?.position ?: preview.filterNot { it.key in fixedOutputKeys }.indexOf(column)) },
                                    singleLine = true, enabled = !state.busy, modifier = Modifier.weight(1f))
                                Row(Modifier.width(100.dp)) {
                                    val data = preview.filterNot { it.key in fixedOutputKeys }
                                    val dataIndex = data.indexOfFirst { it.key == column.key }
                                    TextButton(onClick = { layout = moveOutputColumn(layout, preview, column.key, -1) }, enabled = !state.busy && !locked && dataIndex > 0, contentPadding = PaddingValues(0.dp), modifier = Modifier.width(46.dp)) { Text("↑") }
                                    TextButton(onClick = { layout = moveOutputColumn(layout, preview, column.key, 1) }, enabled = !state.busy && !locked && dataIndex >= 0 && dataIndex < data.lastIndex, contentPadding = PaddingValues(0.dp), modifier = Modifier.width(46.dp)) { Text("↓") }
                                }
                            }
                        } }
                    }
                }
                2 -> Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    newSnapshot?.let { snapshot -> Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f), shape = RoundedCornerShape(8.dp)) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) { Text(tr(language,"快照已就緒","快照已就绪","Snapshot ready"), fontWeight = FontWeight.Medium); Text(tr(language,"包含 ${snapshot.records.size} 份問卷，可另存 Excel。","包含 ${snapshot.records.size} 份问卷，可另存 Excel。","${snapshot.records.size} questionnaires are ready to save as Excel."), style = MaterialTheme.typography.bodySmall) }
                            Button(onClick = { chooseOutput(language, project.project.name + "-${snapshot.id.take(8)}.xlsx")?.let { target -> controller.perform { saveExternal(snapshot, target) } } }, enabled = !state.busy) { Text(tr(language,"另存 Excel","另存 Excel","Save Excel")) }
                        }
                    } }
                    Text(tr(language,"快照固定四表內容與修訂引用。後續修改只影響新快照。","快照固定四表内容和修订引用。后续修改只影响新快照。","Each snapshot freezes all four sheets and revision references. Later edits affect new snapshots only."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.exports.isEmpty()) EmptyState(tr(language,"尚未產生快照","尚未生成快照","No snapshots yet"), tr(language,"檢查設定後，按上方「產生 Excel 快照」。","检查设置后，点击上方“生成 Excel 快照”。","Check the output settings, then choose Generate Excel snapshot."))
                    else {
                        TableHeader {
                            TableCell(tr(language,"建立時間","创建时间","Created"), Modifier.weight(1f), true)
                            TableCell(tr(language,"問卷數","问卷数","Records"), Modifier.width(90.dp), true)
                            TableCell(tr(language,"排除數","排除数","Excluded"), Modifier.width(90.dp), true)
                            Spacer(Modifier.width(140.dp))
                        }
                        LazyColumn(Modifier.weight(1f)) { items(state.exports.sortedByDescending { it.createdAtEpochMillis }, key = { it.id }) { snapshot ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(formatSnapshotTime(snapshot.createdAtEpochMillis))
                                    Text(tr(language,"輸出設定 v${snapshot.outputVersion}","输出设置 v${snapshot.outputVersion}","Output settings v${snapshot.outputVersion}"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TableCell(snapshot.records.size.toString(), Modifier.width(90.dp))
                                TableCell(snapshot.excludedReasons.size.toString(), Modifier.width(90.dp))
                                OutlinedButton(onClick = { chooseOutput(language, project.project.name + "-${snapshot.id.take(8)}.xlsx")?.let { target -> controller.perform { saveExternal(snapshot, target) } } }, enabled = !state.busy, modifier = Modifier.width(140.dp)) { Text(tr(language,"另存／重試","另存／重试","Save / retry")) }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
                        } }
                    }
                }
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    PanelHeading(tr(language,"標題語言","标题语言","Header language"), tr(language,"不翻譯題目或答案。","不翻译题目或答案。","Question and answer text is never translated."))
                    EnumChoice(tr(language,"Excel 標題語言","Excel 标题语言","Excel header language"), OutputHeaderLanguage.entries, headerLanguage, { headerLanguage = it }, language)
                    HorizontalDivider()
                    PanelHeading(tr(language,"隱私","隐私","Privacy"))
                    CheckLine(personal, { personal = it }, tr(language,"允許匯出個人標識與紙面編號","允许导出个人标识和纸面编号","Allow personal identifiers and Paper ID"))
                    Text(tr(language,"關閉時，四張工作表一致排除標記為個人標識的欄位及紙面編號。","关闭时，四张工作表一致排除标记为个人标识的字段和纸面编号。","When disabled, all four sheets exclude fields marked as personal identifiers and the Paper ID."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider()
                    PanelHeading(tr(language,"包含哪些問卷","包含哪些问卷","Questionnaire inclusion"))
                    Text(tr(language,"預設只包含影像準備完成、頁面完整且整份確認的問卷。","默认只包含图像准备完成、页面完整且整份确认的问卷。","By default, include only prepared, complete and confirmed questionnaires."))
                    CheckLine(includeUnresolved, { includeUnresolved = it }, tr(language,"包含未決或缺頁問卷，附狀態標記","包含未决或缺页问卷，附状态标记","Include unresolved or incomplete questionnaires, with status flags"))
                    HorizontalDivider()
                    PanelHeading(tr(language,"四張工作表","四张工作表","Four linked sheets"))
                    Text(tr(language,"答案、問卷摘要、品質與覆核、欄位字典。","答案、问卷摘要、质量和复核、字段字典。","Answers, questionnaire summary, quality and review, and field dictionary."))
                    Text(tr(language,"預覽共 ${preview.size} 列；可在「欄位與列」設定包含、隱私、標題及順序。","预览共 ${preview.size} 列；可在“字段和列”设置包含、隐私、标题和顺序。","${preview.size} preview columns. Use Fields & columns to change inclusion, privacy, headers and order."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (dirty) tr(language,"輸出設定有未保存修改","输出设置有未保存修改","Unsaved output settings") else tr(language,"輸出設定已保存","输出设置已保存","Output settings saved"), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = save, enabled = dirty && !state.busy) { Text(tr(language,"保存輸出設定","保存输出设置","Save output settings")) }
        }
    }
}

internal val fixedOutputKeys = setOf("submission_id", "record_id", "review_status", "submission_revision", "template_version", "completeness", "respondent_id")

/** Preserve aliases and currently hidden column preferences while moving visible data columns. */
internal fun moveOutputColumn(layout: List<OutputColumnLayout>, preview: List<OutputColumn>, key: String, direction: Int): List<OutputColumnLayout> {
    val keys = preview.filterNot { it.key in fixedOutputKeys }.map { it.key }.toMutableList()
    val index = keys.indexOf(key)
    val destination = index + direction
    if (index < 0 || destination !in keys.indices) return layout
    java.util.Collections.swap(keys, index, destination)
    return layout.filterNot { it.key in keys } + keys.mapIndexed { position, item -> OutputColumnLayout(item, layout.firstOrNull { it.key == item }?.titleOverride, position) }
}

@Composable private fun CheckLine(checked: Boolean, onChange: (Boolean) -> Unit, text: String) {
    val enabled = LocalEditorEnabled.current
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, null, enabled = enabled)
        Text(text, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable private fun TableHeader(content: @Composable RowScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .48f), shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

@Composable private fun TableCell(text: String, modifier: Modifier, header: Boolean = false) {
    Text(text, modifier.padding(end = 8.dp), style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
        color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable private fun StatusLabel(text: String, positive: Boolean) {
    Surface(color = if (positive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .65f) else MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(4.dp)) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = if (positive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun reviewFilterLabel(filter: String, language: UiLanguage) = when (filter) {
    "ALL" -> tr(language,"全部問卷","全部问卷","All questionnaires")
    "CONFIRMED" -> tr(language,"已確認","已确认","Confirmed")
    "EXCEPTIONS" -> tr(language,"有例外或缺頁","有异常或缺页","Exceptions / missing pages")
    else -> tr(language,"待覆核","待复核","Pending review")
}

private fun preparationLabel(status: PreparationJobStatus?, language: UiLanguage) = when (status) {
    PreparationJobStatus.QUEUED -> tr(language,"等待準備","等待准备","Queued")
    PreparationJobStatus.RUNNING -> tr(language,"準備中","准备中","Preparing")
    PreparationJobStatus.PAUSED -> tr(language,"已暫停","已暂停","Paused")
    PreparationJobStatus.FAILED -> tr(language,"準備失敗","准备失败","Failed")
    PreparationJobStatus.READY -> tr(language,"已就緒","已就绪","Ready")
    PreparationJobStatus.SUPERSEDED -> tr(language,"已有新修訂","已有新修订","Superseded")
    null -> "—"
}

private fun recognitionLabel(status: RecognitionJobStatus?, language: UiLanguage) = when (status) {
    RecognitionJobStatus.QUEUED -> tr(language,"等待辨識","等待识别","Queued")
    RecognitionJobStatus.RUNNING -> tr(language,"辨識中","识别中","Recognizing")
    RecognitionJobStatus.PAUSED -> tr(language,"已暫停","已暂停","Paused")
    RecognitionJobStatus.FAILED -> tr(language,"辨識失敗","识别失败","Failed")
    RecognitionJobStatus.READY -> tr(language,"可覆核","可复核","Ready for review")
    RecognitionJobStatus.SUPERSEDED -> tr(language,"已有新修訂","已有新修订","Superseded")
    null -> "—"
}

private fun answerSourceLabel(source: AnswerSource?, language: UiLanguage) = when (source) {
    AnswerSource.MANUAL -> tr(language,"人工輸入","人工输入","Manual")
    AnswerSource.MACHINE_ACCEPTED -> tr(language,"採用機器候選","采用机器候选","Accepted machine candidate")
    AnswerSource.INHERITED -> tr(language,"沿用先前修訂","沿用先前修订","Inherited from a revision")
    null -> tr(language,"尚未保存","尚未保存","Not saved yet")
}

private fun formatSnapshotTime(epoch: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()))
