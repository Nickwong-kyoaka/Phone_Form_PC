package com.phoneform.desktop.security

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonParser
import com.google.gson.JsonObject
import java.io.IOException
import java.sql.Connection

/** Metadata-only migration inside SecureDatabase's authenticated transaction. Never uses Records.put. */
internal object SchemaV2 {
    fun migrate(db: Connection) {
        val jobs = db.prepareStatement("SELECT id,payload FROM pc_records WHERE kind='recognition' ORDER BY id").use { query ->
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1) to rows.getString(2)) } }
        }
        val values = mutableListOf<JsonObject>()
        jobs.forEach { (id, payload) ->
            val value = JsonParser.parseString(payload).asJsonObject
            if ((value.get("recordVersion")?.asInt ?: 1) !in 1..2) throw IOException("UNSUPPORTED_RECORD_VERSION")
            if (value.get("status")?.asString !in setOf("QUEUED", "RUNNING", "PAUSED", "FAILED", "READY", "SUPERSEDED", "BLOCKED")) throw IOException("UNSUPPORTED_RECORD_ENUM")
            values += value
            value.addProperty("recordVersion", 2)
            value.addProperty("provenance", "LEGACY_UNBOUND")
            value.add("profileId", JsonNull.INSTANCE)
            value.addProperty("engineVersion", value.get("engineVersion")?.takeUnless { it.isJsonNull }?.asString ?: "")
            value.addProperty("inputFingerprint", "")
            value.add("runToken", JsonNull.INSTANCE); value.add("processSessionId", JsonNull.INSTANCE)
            if (value.get("status")?.asString in setOf("QUEUED", "RUNNING", "PAUSED", "FAILED", "BLOCKED")) {
                value.addProperty("status", "BLOCKED"); value.addProperty("error", "RECOGNITION_PROFILE_REQUIRED")
            }
            (value.getAsJsonArray("observations") ?: JsonArray()).forEach { element ->
                val observation = element.asJsonObject
                observation.addProperty("provenance", "LEGACY_UNBOUND")
                observation.add("profileId", JsonNull.INSTANCE)
            }
            db.prepareStatement("UPDATE pc_records SET payload=? WHERE kind='recognition' AND id=?").use { update ->
                update.setString(1, value.toString()); update.setString(2, id); update.executeUpdate()
            }
        }
        values.groupBy { it.get("submissionRevisionId").asString }.forEach { (revisionId, group) ->
            val head = db.prepareStatement("SELECT payload FROM pc_records WHERE kind='recognition_head' AND id=?").use { query ->
                query.setString(1, revisionId); query.executeQuery().use { rows -> if (rows.next()) JsonParser.parseString(rows.getString(1)).asJsonObject.get("id").asString else null }
            }
            if (head != null && group.none { it.get("id").asString == head } || head == null && group.size != 1) throw IOException("RECOGNITION_HEAD_INVALID")
            if (head == null) {
                val only = group.single(); val pointer = JsonObject().apply { addProperty("id", only.get("id").asString) }
                db.prepareStatement("INSERT INTO pc_records(kind,id,project_id,payload) VALUES('recognition_head',?,?,?)").use { update ->
                    update.setString(1, revisionId); update.setString(2, only.get("projectId").asString); update.setString(3, pointer.toString()); update.executeUpdate()
                }
            }
        }
        // Answers, confirmations, exports, old profiles and existing valid heads are untouched.
        // A missing head is recovered only when exactly one legacy job proves the identity.
    }
}
