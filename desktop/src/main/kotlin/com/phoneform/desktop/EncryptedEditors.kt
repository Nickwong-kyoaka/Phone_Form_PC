package com.phoneform.desktop

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import com.phoneform.ai.core.model.UiLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val editorJson = Gson()

internal enum class RecoveryLoadStatus { LOADING, READY, FAILED }

/** No edit/commit can race the encrypted recovery read. Failed decoding retains its source. */
internal class RecoverableEditorState<T : Any>(initial:T, private val decode:(String)->T,
    private val encode:(T)->String, private val read:suspend ()->String?, private val write:(String)->Unit) : MutableState<T> {
    private val backing=mutableStateOf(initial)
    var status by mutableStateOf(RecoveryLoadStatus.LOADING); private set
    var retainedRaw by mutableStateOf<String?>(null); private set
    var attempt by mutableIntStateOf(0); private set
    val ready get()=status==RecoveryLoadStatus.READY
    fun requireReady() { check(ready){"EDITOR_RECOVERY_PENDING"} }
    fun retry() { if(status==RecoveryLoadStatus.FAILED){status=RecoveryLoadStatus.LOADING;attempt++} }
    suspend fun restore() {
        status=RecoveryLoadStatus.LOADING
        try {
            val raw=read();retainedRaw=raw
            if(raw!=null)backing.value=requireNotNull(decode(raw)){"EDITOR_RECOVERY_CORRUPT"}
            status=RecoveryLoadStatus.READY
        } catch(failure:CancellationException){throw failure}
        catch(_:Exception){status=RecoveryLoadStatus.FAILED}
    }
    /** Called only after an explicit discard successfully removes the encrypted source. */
    fun resetAfterDiscard(initial:T) { backing.value=initial;retainedRaw=null;status=RecoveryLoadStatus.READY }
    override var value:T
        get()=backing.value
        set(value){requireReady();backing.value=value;write(encode(value))}
    override fun component1()=value
    override fun component2():(T)->Unit={value=it}
}

@Composable internal fun <T:Any> recoverableEncryptedState(controller:Controller,sessionId:String,projectId:String?,fieldName:String,
    initial:T,decode:(String)->T,encode:(T)->String):RecoverableEditorState<T> {
    val holder=remember(sessionId,fieldName){RecoverableEditorState(initial,decode,encode,
        {withContext(Dispatchers.IO){controller.loadEditorField(sessionId,fieldName)}},
        {controller.queueEditorField(sessionId,projectId,fieldName,it)})}
    LaunchedEffect(holder,holder.attempt){holder.restore()}
    return holder
}

@Composable internal fun RecoveryLoadProblem(holder:RecoverableEditorState<*>,language:UiLanguage,onDiscard:()->Unit) {
    Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if(holder.status==RecoveryLoadStatus.LOADING) {
            Text(tr(language,"正在讀取加密編輯恢復資料，完成後才可編輯或保存。","正在读取加密编辑恢复数据，完成后才可编辑或保存。","Loading encrypted editor recovery. Editing and saving will be available when it completes."))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            Text(tr(language,"恢復資料無法讀取或格式不完整，原資料仍保留。請重試，或明確放棄後重新開始。","恢复数据无法读取或格式不完整，原数据仍保留。请重试，或明确放弃后重新开始。","Recovery could not be read or decoded. Its source is retained. Retry, or explicitly discard it to start again."),color=MaterialTheme.colorScheme.error)
            holder.retainedRaw?.let {raw->SelectionContainer {Text(raw,Modifier.heightIn(max=180.dp).verticalScroll(rememberScrollState()),style=MaterialTheme.typography.bodySmall)}}
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick={holder.retry()}){Text(tr(language,"重試讀取","重试读取","Retry loading"))}
                TextButton(onClick=onDiscard){Text(tr(language,"放棄這份恢復資料","放弃这份恢复数据","Discard this recovery"))}
            }
        }
    }
}

/** Only the opaque session ID is navigation state; every typed value is stored in encrypted SQLite. */
@Composable fun <T> encryptedState(controller: Controller, sessionId: String, projectId: String?, fieldName: String,
    initial: T, decode: (String) -> T, encode: (T) -> String): MutableState<T> {
    val backing = remember(sessionId, fieldName) { mutableStateOf(initial) }
    val edits = remember(sessionId, fieldName) { java.util.concurrent.atomic.AtomicLong() }
    LaunchedEffect(sessionId, fieldName) {
        val version = edits.get()
        val restored = withContext(Dispatchers.IO) { controller.loadEditorField(sessionId, fieldName) }
        if (restored != null && edits.get() == version) runCatching { decode(restored) }.onSuccess { backing.value = it }
    }
    return remember(sessionId, fieldName) {
        object : MutableState<T> {
            override var value: T
                get() = backing.value
                set(value) { edits.incrementAndGet(); backing.value = value; controller.queueEditorField(sessionId, projectId, fieldName, encode(value)) }
            override fun component1() = value
            override fun component2(): (T) -> Unit = { value = it }
        }
    }
}
@Composable fun encryptedText(controller: Controller, sessionId: String, projectId: String?, field: String, initial: String = "") =
    encryptedState(controller, sessionId, projectId, field, initial, { it }, { it })
