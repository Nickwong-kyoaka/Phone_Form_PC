package com.phoneform.ai.core.model

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

enum class MarkStyle { CHECKBOX_TICK_CROSS, CIRCLED_PRINTED_NUMBER, MANUAL_ONLY }
enum class OptionDecision { SELECTED, EMPTY, AMBIGUOUS, UNSUPPORTED }
enum class RecognitionProvenance { VERSIONED, LEGACY_UNBOUND }

object RecognitionContracts {
    const val ENGINE_VERSION = "tesseract-5.5_opencv-4.12.0_marks-1"
    const val PARAMETERS_VERSION = "marks-1"
}

data class OptionMarkRegion(val optionId: String, val region: NormalizedRect,
    val printedCore: NormalizedRect? = null, val coreConfirmed: Boolean = false)
data class QuestionRecognitionSettings(val questionId: String,
    val enabled: Boolean = false, val style: MarkStyle = MarkStyle.MANUAL_ONLY,
    val options: List<OptionMarkRegion> = emptyList(), val answerRegion: NormalizedRect? = null)
data class RecognitionProfileDraft(val id: String, val projectId: String, val templateVersionId: String,
    val revision: Int = 0, val baseProfileId: String? = null,
    val pageFingerprints: Map<String, String> = emptyMap(),
    val settings: List<QuestionRecognitionSettings> = emptyList(),
    val engineVersion: String = RecognitionContracts.ENGINE_VERSION,
    val parametersVersion: String = RecognitionContracts.PARAMETERS_VERSION)
data class CandidateRef(val jobId: String, val profileId: String, val targetKey: String, val evidenceHash: String)
data class OptionObservation(val optionId: String, val decision: OptionDecision,
    val region: NormalizedRect, val printedCore: NormalizedRect? = null,
    val reason: String = "", val metrics: Map<String, Double> = emptyMap(),
    val submittedRegion: NormalizedRect? = null, val cropSha256: String = "")
class StaleRecognitionException : IllegalStateException("Recognition settings or task changed; reload before saving")

/** Stable hashes are length-prefixed and contain no platform objects or serialized secrets. */
object RecognitionBindings {
    fun hash(vararg values: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        values.forEach { value -> val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun settings(document: TemplateDraft) = document.questions.map { q ->
        q.recognition ?: QuestionRecognitionSettings(q.id)
    }
    fun pageFingerprints(document: TemplateDraft) = document.pages.associate { it.id to PageProcessingFingerprint.of(it) }
    fun profileHash(profile: TemplateRecognitionProfile): String = hash(profile.templateVersionId, profile.engineVersion,
        profile.parametersVersion, profile.pageFingerprints.toSortedMap().entries.joinToString { "${it.key}:${it.value}" },
        *profile.settings.sortedBy { it.questionId }.map { s -> hash(s.questionId, s.enabled.toString(), s.style.name,
            s.answerRegion.toString(), *s.options.sortedBy { it.optionId }.map {
                hash(it.optionId, it.region.toString(), it.printedCore.toString(), it.coreConfirmed.toString()) }.toTypedArray()) }.toTypedArray())
    fun jobFingerprint(revision: SubmissionRevision, profile: TemplateRecognitionProfile) = hash(revision.id,
        profile.id, profileHash(profile), *revision.pages.sortedBy { it.templatePageId }.map {
            hash(it.templatePageId, it.image.id, it.image.asset.sha256, PageProcessingFingerprint.of(it.image)) }.toTypedArray())
    fun evidenceHash(value: FieldObservation) = hash(value.targetKey, value.imageId, value.imageRevision.toString(),
        value.profileId.orEmpty(), value.processingFingerprint, value.sourceSha256, value.engineVersion,
        value.rawText, value.enhancedRawText, value.reason, value.requiresReview.toString(), value.evidenceRegion.toString(),
        value.candidate?.let { hash(it.state.name, it.text, *it.optionIds.toTypedArray()) }.orEmpty(),
        *value.optionObservations.map { hash(it.optionId, it.decision.name, it.region.toString(),
            it.printedCore.toString(), it.submittedRegion.toString(), it.reason, it.cropSha256,
            it.metrics.toSortedMap().entries.joinToString { e -> "${e.key}:${e.value}" }) }.toTypedArray())
    fun bind(value: FieldObservation, jobId: String, profileId: String): FieldObservation {
        val bound = value.copy(profileId = profileId, provenance = RecognitionProvenance.VERSIONED)
        val digest = evidenceHash(bound)
        return bound.copy(evidenceHash = digest, candidate = bound.candidate?.copy(
            candidateRef = CandidateRef(jobId, profileId, bound.targetKey, digest)))
    }
    fun initial(template: FormSchemaVersion) = TemplateRecognitionProfile(template.id,
        RecognitionContracts.ENGINE_VERSION, pageFingerprints(template.document), id = UUID.randomUUID().toString(),
        projectId = template.projectId, settings = settings(template.document), sourceDraftId = template.sourceDraftId)

    fun validate(document: TemplateDraft, settings: List<QuestionRecognitionSettings>): List<String> {
        val errors = mutableListOf<String>()
        if (settings.map { it.questionId }.distinct().size != settings.size) errors += "DUPLICATE_QUESTION_SETTINGS"
        val questions = document.questions.associateBy { it.id }
        settings.forEach { s ->
            val q = questions[s.questionId]
            if (q == null) { errors += "UNKNOWN_QUESTION:${s.questionId}"; return@forEach }
            if (!s.enabled) return@forEach
            when (q.type) {
                QuestionType.SINGLE_CHOICE, QuestionType.MULTIPLE_CHOICE -> {
                    if (s.style == MarkStyle.MANUAL_ONLY) errors += "MARK_STYLE_REQUIRED:${q.id}"
                    if (s.options.map { it.optionId }.toSet() != q.options.map { it.id }.toSet() ||
                        s.options.map { it.optionId }.distinct().size != s.options.size) errors += "OPTION_SETTINGS_REQUIRED:${q.id}"
                    s.options.forEach { option ->
                        if (!option.region.isValid()) errors += "INVALID_OPTION_REGION:${option.optionId}"
                        if (s.style == MarkStyle.CIRCLED_PRINTED_NUMBER && (!option.coreConfirmed ||
                            option.printedCore?.let { it.isValid() && contains(option.region, it) } != true))
                            errors += "CORE_CONFIRMATION_REQUIRED:${option.optionId}"
                    }
                    s.options.forEachIndexed { index, first -> s.options.drop(index + 1).forEach { second ->
                        if (optionRegionsOverlap(first.region, second.region))
                            errors += "OVERLAPPING_OPTION_REGIONS:${q.id}"
                    } }
                }
                QuestionType.NUMBER, QuestionType.DATE -> if ((s.answerRegion ?: q.answerRegion)?.isValid() != true)
                    errors += "ANSWER_REGION_REQUIRED:${q.id}"
                QuestionType.TEXT -> errors += "TEXT_REQUIRES_MANUAL:${q.id}"
            }
        }
        return errors
    }
    /** Adjacent Float-based grid transforms may differ by a few ulps at a shared edge. */
    fun optionRegionsOverlap(first: NormalizedRect, second: NormalizedRect): Boolean =
        minOf(first.right, second.right) - maxOf(first.left, second.left) > 0.000001f &&
            minOf(first.bottom, second.bottom) - maxOf(first.top, second.top) > 0.000001f

    fun contains(outer: NormalizedRect, inner: NormalizedRect) = inner.left >= outer.left && inner.top >= outer.top &&
        inner.right <= outer.right && inner.bottom <= outer.bottom
}
