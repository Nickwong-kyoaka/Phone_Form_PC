package com.phoneform.desktop.security

import com.phoneform.ai.core.model.PageProcessingStatus
import com.phoneform.ai.core.model.PreparationJobStatus
import com.phoneform.ai.core.model.RecognitionJobStatus
import org.sqlite.Function
import java.sql.Connection

/** Connection-local functions run inside encrypted SQLite; no questionnaire text leaves this process. */
internal object QueryFunctions {
    private val versionedKinds=setOf("recognition","answers","export")
    private val statuses=mapOf("recognition" to RecognitionJobStatus.entries.map { it.name }.toSet(),
        "preparation" to PreparationJobStatus.entries.map { it.name }.toSet(),
        "image_job" to PageProcessingStatus.entries.map { it.name }.toSet())
    fun register(connection: Connection) {
        Function.create(connection, "pc_contains", object : Function() {
            override fun xFunc() { result(if (value_text(0).orEmpty().contains(value_text(1).orEmpty(), ignoreCase = true)) 1 else 0) }
        }, 2, Function.FLAG_DETERMINISTIC)
        Function.create(connection, "pc_record_ok", object : Function() {
            override fun xFunc() {
                val kind = value_text(0) ?: return result(1)
                val rawVersion=value_text(1)
                val version = if(rawVersion==null) 1 else rawVersion.toIntOrNull()
                if (kind in versionedKinds && (version==null || version !in 1..2)) return error("UNSUPPORTED_RECORD_VERSION")
                val allowed = statuses[kind]
                if (allowed != null && value_text(2) !in allowed) return error("UNSUPPORTED_RECORD_ENUM")
                result(1)
            }
        }, 3, Function.FLAG_DETERMINISTIC)
        Function.create(connection,"pc_array_ok",object:Function() {
            override fun xFunc() { if(value_text(0)!="array") error("INVALID_RECORD_ARRAY") else result(1) }
        },1,Function.FLAG_DETERMINISTIC)
    }
}
