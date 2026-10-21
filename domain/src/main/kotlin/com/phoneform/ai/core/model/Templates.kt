package com.phoneform.ai.core.model

import java.io.Serializable
import kotlinx.coroutines.flow.Flow

data class NormalizedPoint(val x: Float, val y: Float) : Serializable
data class NormalizedRect(val left: Float, val top: Float, val right: Float, val bottom: Float) : Serializable {
    fun isValid() = listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f } && right > left && bottom > top
}

enum class SampleKind { IMAGE, PDF }
data class SampleAsset(
    val id: String, val kind: SampleKind, val relativePath: String,
    val sha256: String, val byteCount: Long,
    val captureRotationDegrees: Int? = null,
) : Serializable

data class PageTransform(
    val rotationDegrees: Int = 0,
    val corners: List<NormalizedPoint> = listOf(NormalizedPoint(0f, 0f), NormalizedPoint(1f, 0f), NormalizedPoint(1f, 1f), NormalizedPoint(0f, 1f)),
    val sourceToPage: List<Float> = listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
) : Serializable

data class SamplePage(
    val id: String, val asset: SampleAsset, val sourcePageIndex: Int,
    val position: Int, val renderPath: String, val thumbnailPath: String,
    val width: Int, val height: Int, val revision: Int = 1,
    val transform: PageTransform = PageTransform(), val noExtraction: Boolean = false,
    val processing: PageProcessingConfig = PageProcessingConfig(),
) : Serializable

enum class QuestionType { SINGLE_CHOICE, MULTIPLE_CHOICE, NUMBER, DATE, TEXT }
data class OptionDefinition(
    val id: String, val title: String, val code: String,
    val region: NormalizedRect? = null,
) : Serializable
data class QuestionDefinition(
    val id: String, val pageId: String, val position: Int,
    val label: String, val title: String, val type: QuestionType,
    val region: NormalizedRect? = null, val answerRegion: NormalizedRect? = null,
    val options: List<OptionDefinition> = emptyList(), val matrixId: String? = null,
) : Serializable

/** Grid boundaries are fractions within region, including the 0 and 1 outer edges. */
data class MatrixDefinition(
    val id: String, val pageId: String, val region: NormalizedRect,
    val rowBoundaries: List<Float>, val columnBoundaries: List<Float>,
    val questionIds: List<String>,
) : Serializable
data class FieldMapping(
    val fieldId: String, val state: FieldMappingState, val questionId: String? = null,
) : Serializable

data class TemplateDraft(
    val id: String, val projectId: String, val revision: Int = 0,
    val baseVersionId: String? = null,
    val pages: List<SamplePage> = emptyList(),
    val questions: List<QuestionDefinition> = emptyList(),
    val matrices: List<MatrixDefinition> = emptyList(),
    val mappings: List<FieldMapping> = emptyList(),
    val updatedAtEpochMillis: Long = System.currentTimeMillis(),
) : Serializable
data class FormSchemaVersion(
    val id: String, val projectId: String, val version: Int,
    val sourceDraftId: String, val confirmedAtEpochMillis: Long,
    val document: TemplateDraft,
) : Serializable

enum class TemplateIssueCode {
    NO_PAGES, PAGE_FILE_MISSING, PAGE_WITHOUT_QUESTIONS, INVALID_PAGE,
    QUESTION_TITLE, QUESTION_REGION, ANSWER_REGION, OPTIONS_REQUIRED,
    OPTION_TITLE, OPTION_CODE, OPTION_REGION, DUPLICATE_OPTION_CODE,
    INVALID_REFERENCE, INVALID_MATRIX, UNMAPPED_FIELD, DUPLICATE_ID,
}
data class TemplateIssue(
    val code: TemplateIssueCode, val pageId: String? = null,
    val questionId: String? = null, val fieldId: String? = null,
)
class TemplateValidationException(val issues: List<TemplateIssue>) : IllegalArgumentException("Template is incomplete")
class StaleTemplateException : IllegalStateException("Template draft changed; reload before saving")

interface TemplateRepository {
    suspend fun getVersion(versionId: String): FormSchemaVersion? = null
    fun observeDraft(projectId: String): Flow<TemplateDraft?>
    fun observeConfirmed(projectId: String): Flow<FormSchemaVersion?>
    suspend fun beginDraft(projectId: String): TemplateDraft
    /** Optimistic revision check; returns the next saved revision. */
    suspend fun saveDraft(draft: TemplateDraft): TemplateDraft
    suspend fun confirmDraft(projectId: String, draftId: String, expectedRevision: Int): FormSchemaVersion
    suspend fun discardDraft(projectId: String, draftId: String)
}

data class OutputColumn(val key: String, val title: String, val questionId: String? = null, val optionId: String? = null)

object TemplateColumns {
    fun build(project: ProjectDetails, document: TemplateDraft?, policies: List<OutputFieldPolicy> = emptyList()): List<OutputColumn> {
        val language = project.output.language
        val columns = mutableListOf(
            OutputColumn("submission_id", language.label("问卷编号", "問卷編號", "Questionnaire number")),
            OutputColumn("record_id", language.label("内部记录 ID", "內部記錄 ID", "Record ID")),
            OutputColumn("review_status", language.label("覆核状态", "覆核狀態", "Review status")),
            OutputColumn("submission_revision", language.label("提交修订", "提交修訂", "Submission revision")),
            OutputColumn("template_version", language.label("模板版本", "範本版本", "Template version")),
            OutputColumn("completeness", language.label("页面完整性", "頁面完整性", "Page completeness")),
        )
        if (project.output.includePersonalIdentifiers) columns += OutputColumn("respondent_id", language.label("受访者编号", "受訪者編號", "Respondent ID"))
        val included = mutableSetOf<String>()
        val privateTargets = policies.filter { it.personalIdentifier }.map { it.targetKey }.toMutableSet()
        val excludedTargets = policies.filterNot { it.exportIncluded }.map { it.targetKey }.toMutableSet()
        // During draft preview a newly selected binding has not yet transferred its policy in Room.
        project.fields.filter { "field:${it.id}" in privateTargets && it.source == FieldSource.QUESTIONNAIRE }.forEach { field ->
            val mapping = document?.mappings?.find { it.fieldId == field.id }
            val questionId = if (mapping != null) mapping.questionId.takeIf { mapping.state == FieldMappingState.BOUND }
                else field.questionId.takeIf { field.mappingState == FieldMappingState.BOUND }
            if (questionId != null) privateTargets += "question:$questionId"
        }
        project.fields.filter { "field:${it.id}" in excludedTargets && it.source == FieldSource.QUESTIONNAIRE }.forEach { field ->
            val mapping = document?.mappings?.find { it.fieldId == field.id }
            val questionId = if (mapping != null) mapping.questionId.takeIf { mapping.state == FieldMappingState.BOUND }
                else field.questionId.takeIf { field.mappingState == FieldMappingState.BOUND }
            if (questionId != null) excludedTargets += "question:$questionId"
        }
        fun append(key: String, title: String, question: QuestionDefinition?) {
            question?.let { included += it.id }
            val target = question?.let { "question:${it.id}" } ?: "field:${key.removePrefix("field_")}"
            if (target in excludedTargets) return
            if (!project.output.includePersonalIdentifiers && target in privateTargets) return
            if (question?.type == QuestionType.MULTIPLE_CHOICE) question.options.forEach { option ->
                columns += OutputColumn("${key}_${option.id}", "$title · ${option.title}", question.id, option.id)
            } else columns += OutputColumn(key, title, question?.id)
            question?.let { included += it.id }
        }
        project.fields.sortedBy { it.position }.forEach { field ->
            val mapping = document?.mappings?.find { it.fieldId == field.id }
            val id = if (field.source == FieldSource.MANUAL_METADATA) null
                else if (mapping != null) mapping.questionId.takeIf { mapping.state == FieldMappingState.BOUND }
                else field.questionId.takeIf { field.mappingState == FieldMappingState.BOUND }
            append("field_${field.id}", field.title, document?.questions?.find { it.id == id })
        }
        if (document != null) {
            val pages = document?.pages?.associate { it.id to it.position }.orEmpty()
            document?.questions?.sortedWith(compareBy({ pages[it.pageId] ?: 0 }, { it.position }))
                ?.filterNot { it.id in included }
                ?.filter { project.output.extractionScope == ExtractionScope.ALL || policies.any { p -> p.targetKey == "question:${it.id}" && p.exportIncluded } }
                ?.forEach { append("question_${it.id}", it.title, it) }
        }
        val layout = project.output.columnLayout.associateBy { it.key }
        val systemKeys = setOf("submission_id", "record_id", "review_status", "submission_revision", "template_version", "completeness", "respondent_id")
        val fixed = columns.filter { it.key in systemKeys }
        val data = columns.filterNot { it.key in systemKeys }.withIndex().sortedWith(
            compareBy<IndexedValue<OutputColumn>>({ layout[it.value.key]?.position ?: Int.MAX_VALUE }, { it.index })).map { it.value }
        val ordered = fixed + data.map { column -> column.copy(title = layout[column.key]?.titleOverride?.takeIf { it.isNotBlank() } ?: column.title) }
        val used = mutableSetOf<String>()
        val reservedTitles = ordered.map { it.title }.toSet()
        // Resolve collisions by the key order so moving columns does not rename their suffixes.
        val titles = mutableMapOf<String, String>()
        ordered.groupBy { it.title }.toSortedMap().forEach { (base, group) ->
            group.sortedWith(compareBy({ if (it.key in systemKeys) 0 else 1 }, { it.key })).forEachIndexed { index, column ->
                var title = if (index == 0) base else "$base (${index + 1})"
                var suffix = index + 2
                while (title in used || index != 0 && title in reservedTitles) { title = "$base ($suffix)"; suffix++ }
                used += title
                titles[column.key] = title
            }
        }
        return ordered.map { column ->
            column.copy(title = titles.getValue(column.key))
        }
    }
}
