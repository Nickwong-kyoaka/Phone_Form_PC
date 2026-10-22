package com.phoneform.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.gson.GsonBuilder
import com.phoneform.ai.core.model.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.util.Locale

@Composable fun DialogFooter(content: @Composable RowScope.() -> Unit) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

/** A dismissed editor keeps its encrypted recovery buffer; a failed flush leaves the dialog visible. */
@Composable private fun safeDialogClose(controller: Controller, close: () -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val currentClose by rememberUpdatedState(close)
    var saving by remember { mutableStateOf(false) }
    return {
        if (!saving && !controller.state.value.busy) {
            saving = true
            scope.launch {
                try { withContext(Dispatchers.IO) { controller.flushEdits() }; currentClose() }
                catch (_: Exception) { /* Controller retains the buffer and exposes the save failure. */ }
                finally { saving = false }
            }
        }
    }
}

@Composable private fun DialogProblem(state: AppSnapshot, language: UiLanguage) {
    state.error?.let { error ->
        Text(message(language, error), Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(12.dp),
            color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable fun NewProjectWizard(controller: Controller, language: UiLanguage, close: () -> Unit) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val dismiss = safeDialogClose(controller, close)
    var step by encryptedState(controller, "create-project", null, "wizardStep", 0, { it.toInt().coerceIn(0, 2) }, { it.toString() })
    var name by encryptedText(controller, "create-project", null, "name")
    var expected by encryptedText(controller, "create-project", null, "expected")
    var questionnaire by encryptedState(controller, "create-project", null, "questionnaire", QuestionnaireLanguage.MIXED_CHINESE, { QuestionnaireLanguage.valueOf(it) }, { it.name })
    var numbering by encryptedState(controller, "create-project", null, "numbering", NumberingStrategy.GENERATED, { NumberingStrategy.valueOf(it) }, { it.name })
    var output by encryptedState(controller, "create-project", null, "output", OutputHeaderLanguage.valueOf(language.name), { OutputHeaderLanguage.valueOf(it) }, { it.name })
    var manual by encryptedText(controller, "create-project", null, "manual")
    var targets by encryptedText(controller, "create-project", null, "targets")
    var extraction by encryptedState(controller, "create-project", null, "scope", ExtractionScope.ALL, { ExtractionScope.valueOf(it) }, { it.name })
    var attempted by remember { mutableStateOf(false) }
    val expectedValid = expected.isBlank() || expected.trim().toIntOrNull()?.let { it > 0 } == true
    val basicValid = name.isNotBlank() && expectedValid
    fun finish() {
        attempted = true
        if (!basicValid) { step = 0; return }
        controller.perform {
            val fields = targets.lineSequence().map(String::trim).filter(String::isNotBlank).map { it to FieldSource.QUESTIONNAIRE }.toList() +
                manual.lineSequence().map(String::trim).filter(String::isNotBlank).map { it to FieldSource.MANUAL_METADATA }.toList()
            createProject(name, expected.trim().toIntOrNull(), questionnaire, numbering, output, fields, extraction)
            scope.launch { close() }
        }
    }
    AppDialog(tr(language, "建立專案", "创建项目", "New project"), dismiss, width = 760.dp, height = 740.dp) {
        PanelHeading(tr(language, "建立問卷專案", "创建问卷项目", "Create a questionnaire project"),
            tr(language, "一個專案使用一種問卷範本。", "一个项目使用一种问卷模板。", "Use one questionnaire design per project."))
        val steps = listOf(tr(language, "專案資料", "项目信息", "Project"), tr(language, "問卷與編號", "问卷与编号", "Questionnaire"), tr(language, "輸出設定", "输出设置", "Output"))
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            steps.forEachIndexed { index, label ->
                FilterChip(selected = step == index, onClick = {
                    if (index == 0 || basicValid) step = index else { attempted = true; step = 0 }
                }, enabled = !state.busy, label = { Text("${index + 1}  $label") })
            }
        }
        DialogProblem(state, language)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            when (step) {
                0 -> {
                    OutlinedTextField(name, { name = it }, enabled = !state.busy, label = { Text(tr(language, "專案名稱", "项目名称", "Project name")) },
                        singleLine = true, isError = attempted && name.isBlank(), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(expected, { expected = it }, enabled = !state.busy, label = { Text(tr(language, "預計份數（選填）", "预计份数（可选）", "Expected submissions (optional)")) },
                        singleLine = true, isError = !expectedValid, modifier = Modifier.fillMaxWidth())
                    if (attempted && name.isBlank()) Text(tr(language, "請輸入專案名稱。", "请输入项目名称。", "Enter a project name."), color = MaterialTheme.colorScheme.error)
                    if (!expectedValid) Text(tr(language, "份數須為大於零的整數。", "份数须为大于零的整数。", "Use a positive whole number."), color = MaterialTheme.colorScheme.error)
                    Text(tr(language, "預計份數用於顯示進度，不限制實際采集份數。", "预计份数用于显示进度，不限制实际采集份数。", "The expected count helps track progress and does not limit collection."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                1 -> {
                    EnumChoice(tr(language, "問卷語言", "问卷语言", "Questionnaire language"), QuestionnaireLanguage.entries, questionnaire, { if (!state.busy) questionnaire = it }, language)
                    Text(tr(language, "用於本地辨識；不會翻譯題目或答案。", "用于本地识别；不会翻译题目或答案。", "Used for local recognition. Question text and answers are preserved."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    EnumChoice(tr(language, "問卷編號", "问卷编号", "Submission numbering"), NumberingStrategy.entries, numbering, { if (!state.busy) numbering = it }, language)
                    Text(if (numbering == NumberingStrategy.PAPER_ID) tr(language, "每份問卷提交前須填寫紙面編號；保留前導零。", "每份问卷提交前须填写纸面编号；保留前导零。", "Enter the printed ID before each submission. Leading zeros are preserved.")
                        else tr(language, "程式按提交順序產生編號。", "程序按提交顺序生成编号。", "The app assigns numbers in submission order."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                2 -> {
                    EnumChoice(tr(language, "Excel 標題語言", "Excel 标题语言", "Excel header language"), OutputHeaderLanguage.entries, output, { if (!state.busy) output = it }, language)
                    EnumChoice(tr(language, "提取範圍", "提取范围", "Extraction scope"), ExtractionScope.entries, extraction, { if (!state.busy) extraction = it }, language)
                    OutlinedTextField(targets, { targets = it }, enabled = !state.busy, label = { Text(tr(language, "問卷輸出欄位（每行一個）", "问卷输出字段（每行一个）", "Questionnaire output fields (one per line)")) }, minLines = 3, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(manual, { manual = it }, enabled = !state.busy, label = { Text(tr(language, "人工資料欄位（每行一個）", "人工信息字段（每行一个）", "Manual metadata fields (one per line)")) }, minLines = 3, modifier = Modifier.fillMaxWidth())
                    Text(tr(language, "問卷欄位在範本內綁定題目；個人標識預設不匯出。", "问卷字段在模板中绑定题目；个人标识默认不导出。", "Map questionnaire fields to template questions. Personal identifiers are excluded by default."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        DialogFooter {
            Text(tr(language, "第 ${step + 1} / 3 步", "第 ${step + 1} / 3 步", "Step ${step + 1} of 3"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = dismiss, enabled = !state.busy) { Text(tr(language, "稍後繼續", "稍后继续", "Continue later")) }
            if (step > 0) OutlinedButton(onClick = { step-- }, enabled = !state.busy) { Text(tr(language, "上一步", "上一步", "Back")) }
            Button(onClick = { if (step == 2) finish() else { attempted = true; if (basicValid) step++ } }, enabled = !state.busy) {
                Text(if (step == 2) tr(language, "建立專案", "创建项目", "Create project") else tr(language, "下一步", "下一步", "Next"))
            }
        }
    }
}

@Composable fun EditProjectDialog(controller: Controller, project: ProjectDetails, language: UiLanguage, close: () -> Unit) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val session = "project:${project.project.id}"
    var name by encryptedText(controller, session, project.project.id, "name", project.project.name)
    var expected by encryptedText(controller, session, project.project.id, "expected", project.project.expectedCount?.toString().orEmpty())
    val valid = name.isNotBlank() && (expected.isBlank() || expected.trim().toIntOrNull()?.let { it > 0 } == true)
    val dismiss = safeDialogClose(controller, close)
    AppDialog(tr(language, "專案設定", "项目设置", "Project settings"), dismiss, width = 620.dp, height = 480.dp) {
        PanelHeading(tr(language, "專案資料", "项目信息", "Project information"))
        DialogProblem(state, language)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            OutlinedTextField(name, { name = it }, enabled = !state.busy, label = { Text(tr(language, "專案名稱", "项目名称", "Project name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(expected, { expected = it }, enabled = !state.busy, label = { Text(tr(language, "預計份數（選填）", "预计份数（可选）", "Expected submissions (optional)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (!valid) Text(tr(language, "請填寫名稱，份數須留空或填正整數。", "请填写名称，份数须留空或填正整数。", "Enter a name and leave the count blank or use a positive whole number."), color = MaterialTheme.colorScheme.error)
            Text(tr(language, "問卷、編號與輸出設定保持各自的版本。", "问卷、编号和输出设置分别管理版本。", "Questionnaire, numbering and output settings retain their own versions."), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DialogFooter {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = dismiss, enabled = !state.busy) { Text(tr(language, "取消", "取消", "Cancel")) }
            Button(onClick = { controller.perform {
                repositories.projects.saveProjectSection(project.copy(project = project.project.copy(name = name.trim(), expectedCount = expected.trim().toIntOrNull())), ProjectEditSection.PROJECT_INFO)
                clearEditor(session); scope.launch { close() }
            } }, enabled = valid && !state.busy) { Text(tr(language, "保存變更", "保存更改", "Save changes")) }
        }
    }
}

@Composable fun DesktopSettings(controller: Controller, state: AppSnapshot, language: UiLanguage, close: () -> Unit) {
    val ui by controller.ui.collectAsState()
    var tab by remember { mutableStateOf(0) }
    var usage by remember { mutableStateOf<StorageUsage?>(null) }
    var report by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    val dismiss = safeDialogClose(controller, close)
    val tabs = listOf(tr(language, "外觀與語言", "外观与语言", "Appearance"), tr(language, "視窗", "窗口", "Window"), tr(language, "儲存與診斷", "存储与诊断", "Storage"), tr(language, "關於", "关于", "About"))
    AppDialog(tr(language, "設定", "设置", "Settings"), dismiss, width = 780.dp, height = 720.dp) {
        PanelHeading(tr(language, "應用程式設定", "应用设置", "Application settings"))
        InspectorTabs(tabs, tab) { tab = it }
        DialogProblem(state, language)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            when (tab) {
                0 -> {
                    Text(tr(language, "主題", "主题", "Theme"), style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { theme -> FilterChip(ui.theme == theme, { controller.updateUi { it.copy(theme = theme) } }, label = { Text(when (theme) {
                            ThemeMode.LIGHT -> tr(language, "淺色", "浅色", "Light")
                            ThemeMode.DARK -> tr(language, "深色", "深色", "Dark")
                            ThemeMode.SYSTEM -> tr(language, "跟隨系統", "跟随系统", "System")
                        }) }) }
                    }
                    EnumChoice(tr(language, "介面語言", "界面语言", "Interface language"), UiLanguage.entries, state.settings.language ?: language, { controller.perform { setLanguage(it) } }, language)
                    Text(tr(language, "介面、問卷辨識及 Excel 標題語言分開設定。更改介面語言不會翻譯資料。", "界面、问卷识别和 Excel 标题语言独立设置。更改界面语言不会翻译数据。", "Interface, questionnaire recognition and Excel headers have independent languages. Changing this setting preserves your content."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                1 -> {
                    Text(tr(language, "關閉主視窗時", "关闭主窗口时", "When closing the main window"), style = MaterialTheme.typography.titleMedium)
                    val tray = remember { java.awt.SystemTray.isSupported() }
                    CloseBehavior.entries.forEach { behavior ->
                        val enabled = !state.busy && (behavior != CloseBehavior.SYSTEM_TRAY || tray)
                        Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { controller.perform { setCloseBehavior(behavior) } }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(state.settings.closeBehavior == behavior, { controller.perform { setCloseBehavior(behavior) } }, enabled = enabled)
                            Column(Modifier.weight(1f)) {
                                Text(enumLabel(language, behavior.name))
                                Text(if (behavior == CloseBehavior.SAVE_AND_EXIT) tr(language, "保存編輯並停止工作；保存失敗時保持視窗開啟。", "保存编辑并停止任务；保存失败时保持窗口打开。", "Save edits and stop work. A save failure keeps the window open.")
                                    else tr(language, "背景處理繼續；從系統匣開啟、暫停或退出。", "后台任务继续；可从托盘打开、暂停或退出。", "Continue processing in the background. Open, pause or exit from the tray."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (!tray) Text(tr(language, "目前環境不支援系統匣。", "当前环境不支持系统托盘。", "The system tray is unavailable in this environment."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(tr(language, "縮小視窗會繼續處理。預設不開機自啟。", "最小化窗口会继续处理。默认不开机自启。", "Minimizing keeps processing active. Automatic startup is disabled by default."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                2 -> {
                    Text(tr(language, "本機加密儲存", "本地加密存储", "Encrypted local storage"), style = MaterialTheme.typography.titleMedium)
                    usage?.let { data ->
                        StorageLine(tr(language, "總用量", "总用量", "Total"), data.totalBytes)
                        StorageLine(tr(language, "原件", "原件", "Originals"), data.originalBytes)
                        StorageLine(tr(language, "衍生影像", "派生图像", "Derived images"), data.derivedBytes)
                        StorageLine(tr(language, "內部 Excel", "内部 Excel", "Internal Excel files"), data.exportBytes)
                        StorageLine(tr(language, "資料庫", "数据库", "Database"), data.databaseBytes)
                        StorageLine(tr(language, "暫存", "临时文件", "Temporary files"), data.temporaryBytes)
                    }
                    OutlinedButton(onClick = { controller.perform {
                        usage = repositories.storageUsage()
                        val runtime = vision.initialize()
                        val knownStates = setOf("QUEUED", "RUNNING", "PAUSED", "BLOCKED", "FAILED", "READY", "SUPERSEDED")
                        val counts = state.taskStatusCounts.filterKeys { it in knownStates }
                        val safeReport = linkedMapOf<String, Any>(
                            "application" to "PhoneForm AI PC", "version" to "0.3.1-dev",
                            "os" to System.getProperty("os.name"), "osVersion" to System.getProperty("os.version"),
                            "architecture" to System.getProperty("os.arch"), "javaVersion" to System.getProperty("java.version"),
                            "ocrAvailable" to runtime.ocrAvailable, "openCvAvailable" to runtime.openCvAvailable,
                            "missingModels" to runtime.missingModels.filter { it in setOf("eng", "chi_sim", "chi_tra") },
                            "errorCodes" to runtime.errorCodes.filter { it in setOf("OPENCV_UNAVAILABLE", "OCR_NATIVE_UNAVAILABLE", "OCR_MODELS_MISSING") },
                            "projectCount" to state.projects.size, "taskCounts" to counts, "taskTotal" to state.taskTotal,
                            "storageBytes" to mapOf("total" to usage!!.totalBytes, "database" to usage!!.databaseBytes, "original" to usage!!.originalBytes,
                                "derived" to usage!!.derivedBytes, "export" to usage!!.exportBytes, "temporary" to usage!!.temporaryBytes))
                        report = GsonBuilder().setPrettyPrinting().create().toJson(safeReport); saved = false
                    } }, enabled = !state.busy) { Text(tr(language, "更新用量並產生診斷", "刷新用量并生成诊断", "Refresh storage and diagnostics")) }
                    Text(tr(language, "診斷只包含系統版本、元件狀態、統計與固定錯誤碼，不包含問卷內容、名稱或檔案路徑。", "诊断仅包含系统版本、组件状态、统计和固定错误码，不含问卷内容、名称或文件路径。", "Diagnostics include system versions, component status, counts and approved error codes. Questionnaire text, names and file paths are excluded."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    report?.let { text ->
                        Text(tr(language, "報告預覽", "报告预览", "Report preview"), style = MaterialTheme.typography.titleMedium)
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) { Text(text, Modifier.fillMaxWidth().padding(12.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                        OutlinedButton(onClick = { chooseDiagnosticOutput(language)?.let { target -> controller.perform { Files.writeString(target, text); saved = true } } }, enabled = !state.busy) { Text(tr(language, "另存診斷報告", "另存诊断报告", "Save diagnostic report")) }
                    }
                    if (saved) Text(tr(language, "診斷報告已保存。", "诊断报告已保存。", "Diagnostic report saved."), color = MaterialTheme.colorScheme.primary)
                }
                3 -> {
                    Text("PhoneForm AI PC", style = MaterialTheme.typography.headlineSmall)
                    StatusBadge("0.3.1-dev")
                    Text(tr(language, "Windows 離線問卷辨識與人工覆核", "Windows 离线问卷识别与人工复核", "Offline questionnaire recognition and review for Windows"))
                    Text(tr(language, "PDF、JPG、PNG → 本地處理 → 人工覆核 → Excel 四表輸出。", "PDF、JPG、PNG → 本地处理 → 人工复核 → Excel 四表导出。", "PDF, JPG, PNG → local processing → human review → four-sheet Excel export."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(tr(language, "內部試用版本。資料保存在目前 Windows 使用者的加密儲存空間。", "内部试用版本。数据保存在当前 Windows 用户的加密存储中。", "Internal preview. Data is kept in encrypted storage for the current Windows user."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        DialogFooter {
            Text(tr(language, "變更自動保存", "更改自动保存", "Changes save automatically"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f)); Button(onClick = dismiss, enabled = !state.busy) { Text(tr(language, "完成", "完成", "Done")) }
        }
    }
}

@Composable private fun StorageLine(label: String, bytes: Long) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(when { bytes >= 1_073_741_824L -> String.format(Locale.ROOT, "%.2f GB", bytes / 1_073_741_824.0)
            bytes >= 1_048_576L -> String.format(Locale.ROOT, "%.1f MB", bytes / 1_048_576.0)
            bytes >= 1024L -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
            else -> "$bytes B" })
    }
}

fun taskPhase(language: UiLanguage, phase: String): String = when (phase) {
    "IMAGE" -> tr(language, "影像處理", "图像处理", "Image processing")
    "PREPARATION" -> tr(language, "準備問卷", "准备问卷", "Preparing submission")
    "RECOGNITION" -> tr(language, "本地辨識", "本地识别", "Local recognition")
    else -> tr(language, "處理", "处理", "Processing")
}
fun taskStatus(language: UiLanguage, status: String): String = when (status) {
    "QUEUED" -> tr(language, "排隊中", "排队中", "Queued")
    "RUNNING" -> tr(language, "處理中", "处理中", "Running")
    "PAUSED" -> tr(language, "已暫停", "已暂停", "Paused")
    "BLOCKED" -> tr(language, "需要確認辨識設定", "需要确认识别设置", "Recognition settings required")
    "FAILED" -> tr(language, "失敗", "失败", "Failed")
    "READY" -> tr(language, "已完成", "已完成", "Completed")
    "SUPERSEDED" -> tr(language, "已被新修訂取代", "已被新修订替代", "Superseded")
    else -> tr(language, "等待更新", "等待更新", "Awaiting update")
}

@Composable fun TaskCenter(controller: Controller, language: UiLanguage, close: () -> Unit) {
    val tasks by controller.tasks.collectAsState()
    val state by controller.state.collectAsState()
    var tab by remember { mutableStateOf(0) }
    var search by remember { mutableStateOf("") }
    var details by remember { mutableStateOf<String?>(null) }
    val active = pendingTaskStatuses
    val activeCount = pendingTaskCount(state.taskStatusCounts)
    val failedCount = state.taskStatusCounts["FAILED"] ?: 0
    val visible = tasks.filter { task ->
        (when (tab) { 0 -> task.status in active; 1 -> task.status == "FAILED"; 2 -> task.status == "READY"; else -> true }) &&
            (search.isBlank() || task.projectName.contains(search, true) || taskPhase(language, task.phase).contains(search, true) || task.submissionNumber?.toString()?.contains(search) == true)
    }
    val dismiss = safeDialogClose(controller, close)
    AppDialog(tr(language, "任務中心", "任务中心", "Task center"), dismiss, width = 980.dp, height = 720.dp) {
        WorkspaceToolbar(tr(language, "任務中心", "任务中心", "Task center"),
            tr(language, "$activeCount 項待處理 · $failedCount 項失敗", "$activeCount 项待处理 · $failedCount 项失败", "$activeCount pending / action needed · $failedCount failed")) {
            OutlinedButton(onClick = controller::togglePaused) { Text(if (state.paused) tr(language, "繼續處理", "继续处理", "Resume processing") else tr(language, "暫停處理", "暂停处理", "Pause processing")) }
        }
        InspectorTabs(listOf(tr(language, "待處理", "待处理", "Pending / action needed"), tr(language, "失敗", "失败", "Failed"), tr(language, "已完成", "已完成", "Completed"), tr(language, "全部", "全部", "All")), tab) { tab = it }
        DialogProblem(state, language)
        if (state.taskTotal > tasks.size) Text(tr(language,
            "顯示前 ${tasks.size} 項，共 ${state.taskTotal} 項；搜尋與篩選只套用於這批任務。",
            "显示前 ${tasks.size} 项，共 ${state.taskTotal} 项；搜索与筛选仅适用于这批任务。",
            "Showing the first ${tasks.size} of ${state.taskTotal} tasks. Search and filters apply to this displayed batch."),
            Modifier.padding(horizontal=20.dp,vertical=8.dp),style=MaterialTheme.typography.bodySmall)
        OutlinedTextField(search, { search = it }, label = { Text(tr(language, "搜尋專案或問卷編號", "搜索项目或问卷编号", "Search project or submission number")) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp))
        if (visible.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth()) {
            EmptyState(tr(language, "沒有符合條件的任務", "没有符合条件的任务", "No matching tasks"),
                tr(language, "匯入或提交後，實際處理任務會顯示在這裡。", "导入或提交后，实际处理任务会显示在这里。", "Processing tasks appear here after an import or submission."))
        } else LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp)) {
            items(visible, key = { it.id }) { task ->
                Column {
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AppIcon(if (task.phase == "RECOGNITION") "review" else "document", Modifier.size(20.dp))
                        Column(Modifier.weight(1f)) {
                            Text(task.projectName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(taskPhase(language, task.phase) + listOfNotNull(
                                task.submissionNumber?.let { tr(language, "第 $it 份", "第 $it 份", "Submission $it") },
                                task.pageNumber?.let { tr(language, "第 $it 頁", "第 $it 页", "Page $it") }).joinToString(" · ", prefix = if (task.submissionNumber != null || task.pageNumber != null) " · " else ""),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        StatusBadge(taskStatus(language, task.status), good = task.status == "READY", warning = task.status == "FAILED")
                        if (task.retrySupported) TextButton(onClick = { controller.retryTask(task.id) }, enabled = !state.busy) { Text(tr(language, "重試", "重试", "Retry")) }
                        if (!task.error.isNullOrBlank()) TextButton(onClick = { details = if (details == task.id) null else task.id }) { Text(tr(language, "詳情", "详情", "Details")) }
                    }
                    if (details == task.id) Text(task.error.orEmpty(), Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp), style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
        DialogFooter {
            Text(if (state.paused) tr(language, "已暫停；目前單頁步驟會保存檢查點。", "已暂停；当前单页步骤会保存检查点。", "Paused. The current page step will checkpoint.")
                else tr(language, "縮小視窗後會繼續處理。", "最小化窗口后继续处理。", "Processing continues while minimized."), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = dismiss, enabled = !state.busy) { Text(tr(language, "關閉", "关闭", "Close")) }
        }
    }
}

@Composable fun HelpDialog(controller: Controller, language: UiLanguage, close: () -> Unit) {
    val dismiss = safeDialogClose(controller, close)
    AppDialog(tr(language, "使用說明", "使用说明", "Help"), dismiss, width = 700.dp, height = 580.dp) {
        PanelHeading(tr(language, "完成一個問卷專案", "完成一个问卷项目", "Complete a questionnaire project"))
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            listOf(
                tr(language, "1. 建立專案，設定問卷、編號與輸出語言。", "1. 创建项目，设置问卷、编号和输出语言。", "1. Create a project and choose questionnaire, numbering and output settings."),
                tr(language, "2. 匯入範本，核對頁序、題目及欄位映射，再確認版本。", "2. 导入模板，核对页序、题目和字段映射后确认版本。", "2. Import a template, check page order, questions and field mappings, then confirm it."),
                tr(language, "3. 每次匯入一份問卷，逐頁對應固定頁槽並提交。", "3. 每次导入一份问卷，将页面对应固定页槽后提交。", "3. Import one submission at a time, assign fixed page slots and submit."),
                tr(language, "4. 查看紙面證據，保存人工答案，完成整份確認。", "4. 查看原始证据，保存人工答案并确认整份问卷。", "4. Inspect evidence, save reviewed answers and confirm the whole submission."),
                tr(language, "5. 設定輸出欄位，產生固定 Excel 快照並另存。", "5. 设置输出字段，生成固定 Excel 快照并另存。", "5. Configure output fields, generate a frozen Excel snapshot and save a copy.")
            ).forEach { Text(it) }
            HorizontalDivider()
            Text(tr(language, "手寫與自由文字可人工輸入。機器候選需經覆核；缺頁不會令後面頁面前移。", "手写和自由文本可人工录入。机器候选须经复核；缺页不会使后续页面前移。", "Handwritten and free-text answers can be entered manually. Review machine candidates; missing pages retain their fixed slots."), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DialogFooter { Spacer(Modifier.weight(1f)); Button(onClick = dismiss) { Text(tr(language, "知道了", "知道了", "Got it")) } }
    }
}
