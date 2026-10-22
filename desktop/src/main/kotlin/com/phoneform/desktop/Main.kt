package com.phoneform.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import com.phoneform.ai.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Path
import java.util.Locale

fun tr(language: UiLanguage?, traditional: String, simplified: String, english: String): String = when(language) {
    UiLanguage.TRADITIONAL -> traditional; UiLanguage.ENGLISH -> english; else -> simplified
}
fun main() {
    javax.imageio.ImageIO.setUseCache(false)
    val applicationDir = System.getProperty("compose.application.resources.dir")?.let { Path.of(it).parent }
    if (applicationDir != null) {
        val jars = java.nio.file.Files.walk(applicationDir, 3).use { paths -> paths.filter { it.toString().endsWith(".jar") }.toList() }
        if (jars.isNotEmpty()) System.setProperty("phoneform.pdf.classpath", jars.joinToString(File.pathSeparator))
    }
    application {
    var controller by remember { mutableStateOf<Controller?>(null) }
    var startupError by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf(0) }
    var visible by remember { mutableStateOf(true) }
    var exiting by remember { mutableStateOf(false) }
    var exitError by remember { mutableStateOf<String?>(null) }
    val appScope = rememberCoroutineScope()
    val desktopActions = remember { DesktopActions() }
    val snapshot = controller?.state?.collectAsState()?.value ?: AppSnapshot()
    val desktopUi = controller?.ui?.collectAsState()?.value ?: DesktopUiState()
    val interactionBlocked by rememberUpdatedState(exiting || snapshot.busy)
    val language = snapshot.settings.language ?: if (Locale.getDefault().language == "en") UiLanguage.ENGLISH else UiLanguage.TRADITIONAL
    LaunchedEffect(retry) {
        startupError = null
        try {
            controller = withContext(Dispatchers.IO) {
                val root = System.getProperty("phoneform.data.dir")?.let(Path::of) ?: System.getenv("PHONEFORM_DATA_DIR")?.let(Path::of)
                    ?: Path.of(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "PhoneFormAI-PC")
                val resourceRoot = System.getProperty("compose.application.resources.dir")?.let(Path::of)
                    ?: Path.of(System.getProperty("user.dir"), "runtime")
                Controller.open(root, resourceRoot.resolve("models"))
            }
        } catch (error: Exception) { startupError = Controller.errorCode(error) }
    }
    fun exitSafely() {
        if (exiting) return
        if (snapshot.busy) { exitError = "ACTION_BUSY"; return }
        exiting = true
        appScope.launch {
            try { withContext(Dispatchers.IO) { controller?.close() }; exitApplication() }
            catch (error: Exception) { exiting = false; exitError = Controller.errorCode(error); visible = true }
        }
    }
    val systemTraySupported = java.awt.SystemTray.isSupported()
    val workArea = remember { java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }
    if (!visible && systemTraySupported) {
        Tray(icon = remember { trayIcon() }, tooltip = "PhoneForm AI PC", onAction = { visible = true }, menu = {
            Item(tr(language,"開啟","打开","Open"), onClick = { visible = true })
            Item(tr(language,"暫停／繼續","暂停／继续","Pause / resume"), onClick = { controller?.togglePaused() })
            Separator()
            Item(tr(language,"退出","退出","Exit"), onClick = { exitSafely() })
        })
    }
    Window(visible = visible, title = "PhoneForm AI PC · 0.3.1-dev",
        state = rememberWindowState(width = minOf(1280,workArea.width).dp, height = minOf(860,workArea.height-36).dp),
        onCloseRequest = {
            if (snapshot.settings.closeBehavior == CloseBehavior.SYSTEM_TRAY && systemTraySupported) visible = false else exitSafely()
        }) {
        DisposableEffect(window, desktopActions) {
            window.minimumSize = java.awt.Dimension(840,480)
            val target = java.awt.dnd.DropTarget(window, java.awt.dnd.DnDConstants.ACTION_COPY, object : java.awt.dnd.DropTargetAdapter() {
                override fun drop(event: java.awt.dnd.DropTargetDropEvent) {
                    try {
                        if (interactionBlocked || desktopActions.drop == null || !event.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) {
                            event.rejectDrop(); return
                        }
                        event.acceptDrop(java.awt.dnd.DnDConstants.ACTION_COPY)
                        val files = event.transferable.getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor) as? List<*>
                        val file = files?.singleOrNull() as? File
                        if (file != null && file.isFile && file.extension.lowercase() in setOf("pdf","jpg","jpeg","png")) {
                            desktopActions.drop?.invoke(file); event.dropComplete(true)
                        } else {
                            desktopActions.dropRejected?.invoke(); event.dropComplete(false)
                        }
                    } catch (_: Exception) { desktopActions.dropRejected?.invoke(); event.dropComplete(false) }
                }
            }, true)
            onDispose {target.component=null}
        }
        CompositionLocalProvider(LocalDesktopActions provides desktopActions) {
        DesktopTheme(desktopUi.theme) {
            Surface(Modifier.fillMaxSize()) {
                if (controller == null || startupError != null) Column(Modifier.padding(40.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text("PhoneForm AI PC", style = MaterialTheme.typography.headlineLarge)
                    Text(tr(language,"正在開啟加密儲存空間","正在打开加密存储","Opening encrypted storage"))
                    if (startupError != null) {
                        Text(tr(language,"儲存初始化失敗，原有資料已保留。","存储初始化失败，原有数据已保留。","Storage initialization failed. Existing data is preserved."))
                        Text(startupError!!)
                        Button(onClick = { retry++ }) { Text(tr(language,"重試","重试","Retry")) }
                    } else CircularProgressIndicator()
                } else CompositionLocalProvider(LocalEditorEnabled provides (!snapshot.busy && !exiting)) { DesktopApp(controller!!, snapshot, language) }
                if (exiting) Dialog(onCloseRequest={},title=tr(language,"保存並退出","保存并退出","Saving and exiting"),
                    state=rememberDialogState(width=400.dp,height=150.dp),resizable=false) {
                    Surface(Modifier.fillMaxSize()) { Row(Modifier.padding(24.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        CircularProgressIndicator(); Text(tr(language,"保存並退出…","保存并退出…","Saving and exiting…"))
                    } }
                }
                if (exitError != null) AlertDialog(onDismissRequest={exitError=null},
                    title={Text(tr(language,"尚未退出","尚未退出","App is still open"))},
                    text={Text(message(language,exitError!!))},
                    confirmButton={TextButton(onClick={exitError=null}){Text(tr(language,"返回工作區","返回工作区","Back to workspace"))}})
            }
        }
        }
    }
    }
}

@Composable fun Field(label: String, value: String, change: (String) -> Unit, single: Boolean = true, modifier: Modifier = Modifier.fillMaxWidth()) {
    OutlinedTextField(value, change, label = { Text(label) }, singleLine = single, modifier = modifier, enabled = LocalEditorEnabled.current)
}
@Composable fun <T : Enum<T>> EnumChoice(title: String, values: List<T>, selected: T, change: (T) -> Unit, language: UiLanguage) {
    var expanded by remember { mutableStateOf(false) }
    Column { Text(title, style = MaterialTheme.typography.labelLarge); Box {
        OutlinedButton(onClick = { expanded = true },enabled=LocalEditorEnabled.current) { Text(enumLabel(language, selected.name) + " ▾") }
        DropdownMenu(expanded, { expanded = false }) { values.forEach { value -> DropdownMenuItem(text = { Text(enumLabel(language, value.name)) }, onClick = { expanded = false; change(value) }) } }
    } }
}
fun enumLabel(language: UiLanguage, name: String): String = when(name) {
    "LIGHT" -> tr(language,"淺色","浅色","Light")
    "DARK" -> tr(language,"深色","深色","Dark")
    "SYSTEM" -> tr(language,"跟隨系統","跟随系统","System")
    "SIMPLIFIED", "SIMPLIFIED_CHINESE" -> "简体中文"; "TRADITIONAL", "TRADITIONAL_CHINESE" -> "繁體中文"; "ENGLISH" -> "English"
    "MIXED_CHINESE" -> tr(language,"混合中文","混合中文","Mixed Chinese")
    "GENERATED" -> tr(language,"程式產生編號","程序生成编号","App generated")
    "PAPER_ID" -> tr(language,"紙面編號必填","纸面编号必填","Paper ID required")
    "SAVE_AND_EXIT" -> tr(language,"保存並退出","保存并退出","Save and exit")
    "SYSTEM_TRAY" -> tr(language,"留在系統匣","留在系统托盘","Keep in system tray")
    "SINGLE_CHOICE" -> tr(language,"單選","单选","Single choice"); "MULTIPLE_CHOICE" -> tr(language,"多選","多选","Multiple choice")
    "NUMBER" -> tr(language,"數字","数字","Number"); "DATE" -> tr(language,"日期","日期","Date"); "TEXT" -> tr(language,"文字","文字","Text")
    "UNHANDLED" -> tr(language,"未處理","未处理","Unhandled"); "VALUE" -> tr(language,"有效答案","有效答案","Value")
    "BLANK" -> tr(language,"空白","空白","Blank"); "NOT_APPLICABLE" -> tr(language,"不適用","不适用","Not applicable")
    "UNREADABLE" -> tr(language,"無法辨認","无法辨认","Unreadable"); "NOT_CAPTURED" -> tr(language,"未拍到","未拍到","Not captured")
    "ALL" -> tr(language,"全部題目","全部题目","All questions"); "SELECTED" -> tr(language,"指定欄位","指定字段","Selected fields")
    "CHECKBOX_TICK_CROSS" -> tr(language,"方框勾選／交叉","方框勾选／交叉","Checkbox tick / cross")
    "CIRCLED_PRINTED_NUMBER" -> tr(language,"圈選印刷數字","圈选印刷数字","Circled printed number")
    "MANUAL_ONLY" -> tr(language,"只作人工輸入","仅人工输入","Manual only")
    "EMPTY" -> tr(language,"無標記","无标记","Unmarked")
    "AMBIGUOUS" -> tr(language,"不確定","不确定","Ambiguous")
    "UNSUPPORTED" -> tr(language,"未處理","未处理","Not processed")
    "BLOCKED" -> tr(language,"需要確認辨識設定","需要确认识别设置","Confirm recognition settings")
    "NOT_REQUIRED" -> tr(language,"無啟用辨識，毋須對齊","无启用识别，无需对齐","No enabled recognition; alignment not required")
    "RECOGNITION_SETTINGS" -> tr(language,"辨識設定未完成","识别设置未完成","Recognition settings incomplete")
    else -> name
}
fun message(language: UiLanguage, code: String): String = when(code.substringBefore(':')) {
    "EXPORT_SAVED" -> tr(language,"Excel 已保存。","Excel 已保存。","Excel saved.")
    "STALE_EDIT_RELOAD" -> tr(language,"資料已更新。請重新載入後再保存，原輸入仍保留。","数据已更新。请重新加载后再保存，原输入仍保留。","Data changed. Reload before saving; your input is retained.")
    "RECOGNITION_SETTINGS_REQUIRED","RECOGNITION_PROFILE_REQUIRED" -> tr(language,"請先開啟「辨識設定」，核對並確認後再重跑。","请先打开“识别设置”，核对并确认后再重跑。","Open Recognition settings, check and confirm them, then rerun.")
    "RECOGNITION_SETTINGS_INVALID","RECOGNITION_PROFILE_INVALID","TEMPLATE_INVALID" -> tr(language,"尚有題目或辨識設定未完成。請核對選項範圍、代碼及數字核心後再確認。","尚有题目或识别设置未完成。请检查选项区域、代码及数字核心后再确认。","Some questions or recognition settings are incomplete. Check option regions, codes and printed cores before confirming.")
    "RECOGNITION_PAGE_CHANGED","RECOGNITION_INPUT_CHANGED" -> tr(language,"頁面或提交版本已改變，原草稿保留。請重新載入設定或使用目前修訂重跑。","页面或提交版本已改变，原草稿保留。请重新加载设置或对当前修订重跑。","The page or submission changed. Your draft is retained. Reload settings or rerun the current revision.")
    "RECOGNITION_ENGINE_UNSUPPORTED","RECOGNITION_ENGINE_UNAVAILABLE" -> tr(language,"這份設定使用不同辨識引擎版本，請重新確認目前設定。","该设置使用不同识别引擎版本，请重新确认当前设置。","These settings use a different engine version. Confirm settings for the current engine.")
    "RECOGNITION_EVIDENCE_CHANGED","CANDIDATE_REFERENCE_CHANGED" -> tr(language,"候選證據已改變，未套用舊候選。請重新辨識或人工輸入。","候选证据已改变，未应用旧候选。请重新识别或人工输入。","Candidate evidence changed. The old candidate was not applied. Rerun or enter a human answer.")
    "REVIEW_ALREADY_CONFIRMED","QUESTIONNAIRE_CONFIRMED" -> tr(language,"此份已確認，未重新辨識。已保存答案保留。","该份已确认，未重新识别。已保存答案保留。","This questionnaire is confirmed and was not rerun. Saved answers are retained.")
    "REVIEW_INCOMPLETE" -> tr(language,"請先處理全部未完成或無效答案。","请先处理全部未完成或无效答案。","Resolve all unhandled or invalid answers first.")
    "EDITOR_RECOVERY_PENDING" -> tr(language,"仍有保留的題目或整組編輯，請先完成或明確放棄後再確認。","仍有保留的题目或整组编辑，请先完成或明确放弃后再确认。","Retained question or group edits need attention. Complete or explicitly discard them before confirming.")
    "ACTION_BUSY" -> tr(language,"操作仍在進行，完成後可再次退出。","操作仍在进行，完成后可再次退出。","An operation is still running. Exit again when it finishes.")
    "TEMPLATE_LOCKED","TEMPLATE_STRUCTURE_LOCKED" -> tr(language,"此範本已鎖定，請另建專案使用不同問卷結構。","此模板已锁定，请另建项目使用不同问卷结构。","This template is locked. Create a project for a different questionnaire design.")
    "PAGE_SLOTS_INVALID","NO_PAGES" -> tr(language,"請選擇頁面並核對頁槽。","请选择页面并核对页槽。","Select pages and check their slots.")
    "UNSUPPORTED_FORMAT" -> tr(language,"請選擇 PDF、JPG 或 PNG 檔案。","请选择 PDF、JPG 或 PNG 文件。","Choose a PDF, JPG or PNG file.")
    "PROJECT_NAME_REQUIRED","PROJECT_INVALID" -> tr(language,"請輸入專案名稱及有效份數。","请输入项目名称和有效份数。","Enter a project name and a valid expected count.")
    "MISSING_PAGES_NOT_ACCEPTED" -> tr(language,"請先核對並明確接受缺頁。","请先核对并明确接受缺页。","Review and explicitly accept missing pages first.")
    "PDF_PASSWORD_REQUIRED","PDF_ENCRYPTED" -> tr(language,"此 PDF 需要密碼，請使用可直接開啟的副本。","此 PDF 需要密码，请使用可直接打开的副本。","This PDF requires a password. Use an accessible copy.")
    "PDF_INVALID","PDF_CORRUPT" -> tr(language,"無法讀取此 PDF，請檢查檔案後重試。","无法读取此 PDF，请检查文件后重试。","This PDF could not be read. Check the file and try again.")
    else -> tr(language,"操作未完成，輸入已保留。請重試或查看詳情。","操作未完成，输入已保留。请重试或查看详情。","The operation could not finish. Your input is retained. Retry or view details.")
}
fun chooseFile(language: UiLanguage): File? {
    val dialog = FileDialog(null as Frame?, tr(language,"匯入 PDF／圖片","导入 PDF／图片","Import PDF / image"), FileDialog.LOAD)
    dialog.isVisible = true
    return dialog.file?.let { File(dialog.directory, it) }.also { dialog.dispose() }
}
fun chooseOutput(language: UiLanguage, filename: String): Path? {
    val dialog = FileDialog(null as Frame?, tr(language,"另存 Excel","另存 Excel","Save Excel"), FileDialog.SAVE)
    dialog.file = filename.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    dialog.isVisible = true
    return dialog.file?.let { Path.of(dialog.directory, if (it.endsWith(".xlsx", true)) it else "$it.xlsx") }.also { dialog.dispose() }
}
fun chooseDiagnosticOutput(language:UiLanguage):Path? {
    val dialog=FileDialog(null as Frame?,tr(language,"保存診斷","保存诊断","Save diagnostics"),FileDialog.SAVE)
    dialog.file="PhoneForm-PC-diagnostics.json";dialog.isVisible=true
    return dialog.file?.let {Path.of(dialog.directory,if(it.endsWith(".json",true))it else "$it.json")}.also {dialog.dispose()}
}
private fun trayIcon(): androidx.compose.ui.graphics.painter.Painter = object : androidx.compose.ui.graphics.painter.Painter() {
    override val intrinsicSize = Size(32f,32f)
    override fun androidx.compose.ui.graphics.drawscope.DrawScope.onDraw() { drawCircle(Color(0xff2563eb)); drawLine(Color.White, Offset(size.width*.25f,size.height*.5f), Offset(size.width*.44f,size.height*.7f), 3f); drawLine(Color.White, Offset(size.width*.44f,size.height*.7f), Offset(size.width*.78f,size.height*.27f),3f) }
}
