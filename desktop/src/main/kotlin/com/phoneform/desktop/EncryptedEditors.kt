package com.phoneform.desktop

import androidx.compose.runtime.*
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val editorJson = Gson()

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
