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
internal class RecoverableEditorState<T : Any>(private val initial:T, private val decode:(String)->T,
    private val encode:(T)->String, private val read:suspend ()->String?, private val write:(String)->Unit) : MutableState<T> {
    private val backing=mutableStateOf(initial)
    var status by mutableStateOf(RecoveryLoadStatus.LOADING); private set
    var retainedRaw by mutableStateOf<String?>(null); private set
    var attempt by mutableIntStateOf(0); private set
    private val loadGeneration=java.util.concurrent.atomic.AtomicLong()
    val ready get()=status==RecoveryLoadStatus.READY
    fun requireReady() { check(ready){"EDITOR_RECOVERY_PENDING"} }
    fun retry() { if(status==RecoveryLoadStatus.FAILED){loadGeneration.incrementAndGet();status=RecoveryLoadStatus.LOADING;attempt++} }
    suspend fun restore() {
        val generation=loadGeneration.incrementAndGet()
        status=RecoveryLoadStatus.LOADING
        try {
            val raw=read()
            if(loadGeneration.get()!=generation)return
            retainedRaw=raw
            val restored=raw?.let {requireNotNull(decode(it)){"EDITOR_RECOVERY_CORRUPT"}}
            if(loadGeneration.get()!=generation)return
            if(restored!=null)backing.value=restored
            status=RecoveryLoadStatus.READY
        } catch(failure:CancellationException){throw failure}
        catch(_:Exception){if(loadGeneration.get()==generation)status=RecoveryLoadStatus.FAILED}
    }
    /** Called only after an explicit discard successfully removes the encrypted source. */
    fun resetAfterDiscard(initial:T) { loadGeneration.incrementAndGet();backing.value=initial;retainedRaw=null;status=RecoveryLoadStatus.READY }
    fun resetToInitialAfterDiscard() = resetAfterDiscard(initial)
    override var value:T
        get()=backing.value
        set(value){requireReady();backing.value=value;write(encode(value))}
    override fun component1()=value
    override fun component2():(T)->Unit={value=it}
}

/** A multi-field form is committable only after every legacy field has loaded successfully. */
internal class EditorRecoveryFields(vararg val fields:RecoverableEditorState<*>) {
    val ready get()=fields.all {it.ready}
    fun requireReady() = fields.forEach {it.requireReady()}
    fun retry() = fields.forEach {it.retry()}
    fun resetAfterDiscard() = fields.forEach {it.resetToInitialAfterDiscard()}
}

@Composable internal fun recoverableEncryptedText(controller:Controller,sessionId:String,projectId:String?,fieldName:String,initial:String="") =
    recoverableEncryptedState(controller,sessionId,projectId,fieldName,initial,{it},{it})

@Composable internal fun RecoveryFieldsProblem(fields:EditorRecoveryFields,controller:Controller,sessionId:String,language:UiLanguage,onDiscarded:()->Unit={},beforeDiscard:()->Unit={}) {
    val loading=fields.fields.firstOrNull {it.status==RecoveryLoadStatus.LOADING}
    val failed=fields.fields.firstOrNull {it.status==RecoveryLoadStatus.FAILED}
    val shown=loading ?: failed ?: return
    if(loading==null && fields.fields.size>1)
        Text(tr(language,"放棄恢復會重設這份表單的全部恢復欄位。已正式保存的資料仍保留。","放弃恢复会重置这份表单的全部恢复字段。已正式保存的数据仍保留。","Discarding recovery resets every recovered field in this form. Saved records remain available."),Modifier.padding(horizontal=12.dp),style=MaterialTheme.typography.bodySmall)
    RecoveryLoadProblem(shown,language) {
        controller.perform {
            beforeDiscard()
            clearEditor(sessionId)
            fields.resetAfterDiscard()
            onDiscarded()
        }
    }
    if(loading==null && failed!=null && fields.fields.count {it.status==RecoveryLoadStatus.FAILED}>1)
        TextButton(onClick={fields.retry()}) {Text(tr(language,"重試全部欄位","重试全部字段","Retry all fields"))}
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
