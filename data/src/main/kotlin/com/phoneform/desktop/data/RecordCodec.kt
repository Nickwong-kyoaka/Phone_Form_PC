package com.phoneform.desktop.data

import com.google.gson.*
import com.phoneform.ai.core.model.*
import java.io.IOException

/** Explicit normalization: Gson's unsafe allocation does not apply Kotlin constructor defaults. */
internal object RecordCodec {
    val gson = Gson()
    fun encode(value: Any): String {
        val tree = gson.toJsonTree(value)
        if (value is RecognitionJob || value is TemplateRecognitionProfile || value is RecognitionProfileDraft || value is ExportSnapshot || value is AnswerSetRevision)
            tree.asJsonObject.addProperty("recordVersion", 2)
        return tree.toString()
    }
    fun <T> decode(payload: String, type: Class<T>): T {
        if (type !in setOf(RecognitionJob::class.java, TemplateRecognitionProfile::class.java, RecognitionProfileDraft::class.java, ExportSnapshot::class.java, AnswerSetRevision::class.java, TemplateDraft::class.java, FormSchemaVersion::class.java))
            return gson.fromJson(payload, type)
        val value = JsonParser.parseString(payload).asJsonObject
        val version = value.get("recordVersion")?.asInt ?: 1
        if (version !in 1..2) throw IOException("UNSUPPORTED_RECORD_VERSION")
        when (type) {
            RecognitionJob::class.java -> {
                value.string("engineVersion", ""); value.string("inputFingerprint", "")
                value.string("provenance", "LEGACY_UNBOUND"); value.array("completedPageIds"); value.array("alignments")
                value.requireEnum("provenance", RecognitionProvenance.entries.map { it.name })
                value.requireEnum("status", RecognitionJobStatus.entries.map { it.name })
                value.array("observations").forEach { normalizeObservation(it.asJsonObject) }
            }
            TemplateRecognitionProfile::class.java -> {
                value.string("id", ""); value.string("projectId", ""); value.number("version", 0)
                value.string("parametersVersion", "LEGACY_UNBOUND"); value.array("settings").forEach { normalizeSettings(it.asJsonObject) }
            }
            RecognitionProfileDraft::class.java -> {
                value.string("engineVersion", RecognitionContracts.ENGINE_VERSION)
                value.string("parametersVersion", RecognitionContracts.PARAMETERS_VERSION)
                value.array("settings").forEach { normalizeSettings(it.asJsonObject) }
            }
            ExportSnapshot::class.java -> value.array("records").forEach { it.asJsonObject.array("candidateRefs") }
            AnswerSetRevision::class.java -> value.array("answers")
            TemplateDraft::class.java -> normalizeDocument(value)
            FormSchemaVersion::class.java -> normalizeDocument(value.getAsJsonObject("document"))
        }
        return gson.fromJson(value, type)
    }
    private fun normalizeSettings(value: JsonObject) {
        value.bool("enabled", false); value.string("style", "MANUAL_ONLY")
        value.requireEnum("style", MarkStyle.entries.map { it.name })
        value.array("options").forEach { option -> option.asJsonObject.apply {
            bool("coreConfirmed", false)
            if (!has("region") || !get("region").isJsonObject) throw IOException("INVALID_RECORD_REGION")
        } }
    }
    private fun normalizeDocument(value: JsonObject) {
        value.array("questions").forEach { element -> element.asJsonObject.get("recognition")?.takeUnless { it.isJsonNull }?.let { normalizeSettings(it.asJsonObject) } }
    }
    private fun normalizeObservation(value: JsonObject) {
        value.string("provenance", "LEGACY_UNBOUND"); value.string("sourceSha256", ""); value.string("evidenceHash", "")
        value.requireEnum("provenance", RecognitionProvenance.entries.map { it.name })
        value.array("optionObservations").forEach { option ->
            option.asJsonObject.apply { requireEnum("decision", OptionDecision.entries.map { it.name }); string("reason", ""); string("cropSha256", ""); if (!has("metrics") || get("metrics").isJsonNull) add("metrics", JsonObject()) }
        }
    }
    private fun JsonObject.requireEnum(key: String, allowed: List<String>) {
        if (!has(key) || get(key).isJsonNull || get(key).asString !in allowed) throw IOException("UNSUPPORTED_RECORD_ENUM")
    }
    private fun JsonObject.string(key: String, default: String) { if (!has(key) || get(key).isJsonNull) addProperty(key, default) }
    private fun JsonObject.number(key: String, default: Int) { if (!has(key) || get(key).isJsonNull) addProperty(key, default) }
    private fun JsonObject.bool(key: String, default: Boolean) { if (!has(key) || get(key).isJsonNull) addProperty(key, default) }
    private fun JsonObject.array(key: String): JsonArray {
        if (!has(key) || get(key).isJsonNull) add(key, JsonArray())
        return getAsJsonArray(key)
    }
}
