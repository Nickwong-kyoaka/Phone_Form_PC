package com.phoneform.ai.core.model

enum class UiLanguage(val languageTag: String) {
    SIMPLIFIED("zh-Hans"),
    TRADITIONAL("zh-Hant"),
    ENGLISH("en"),
}

/** Export headings are independent of the interface and questionnaire languages. */
enum class OutputHeaderLanguage(val languageTag: String) {
    SIMPLIFIED("zh-Hans"),
    TRADITIONAL("zh-Hant"),
    ENGLISH("en"),
    ;

    /** Deliberately exhaustive: adding a language must supply its generated labels. */
    fun label(simplified: String, traditional: String, english: String): String = when (this) {
        SIMPLIFIED -> simplified
        TRADITIONAL -> traditional
        ENGLISH -> english
    }
}

enum class QuestionnaireLanguage {
    SIMPLIFIED_CHINESE, TRADITIONAL_CHINESE, MIXED_CHINESE, ENGLISH,
}

enum class NumberingStrategy { GENERATED, PAPER_ID }
enum class ExtractionScope { ALL, SELECTED }
enum class FieldSource { QUESTIONNAIRE, MANUAL_METADATA }
enum class PreparationStatus { AWAITING_SAMPLE, TEMPLATE_DRAFT, TEMPLATE_CONFIRMED }
enum class FieldMappingState { UNMAPPED, BOUND, MANUAL_ENTRY }
enum class ExportFormat { XLSX }
enum class ProjectEditSection { PROJECT_INFO, INPUT_RULES, PROJECT_SETTINGS, OUTPUT }
class StaleOutputConfigurationException : IllegalStateException("OUTPUT_CONFIGURATION_CHANGED")

/** Overrides follow stable column keys, not the currently translated title. */
data class OutputColumnLayout(val key: String, val titleOverride: String? = null, val position: Int)

data class ProjectConfig(
    val id: String,
    val name: String,
    val expectedCount: Int?,
    val questionnaireLanguage: QuestionnaireLanguage,
    val numberingStrategy: NumberingStrategy,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val preparationStatus: PreparationStatus = PreparationStatus.AWAITING_SAMPLE,
    val templateVersionId: String? = null,
)

data class OutputProfile(
    val projectId: String,
    val version: Int = 1,
    val format: ExportFormat = ExportFormat.XLSX,
    val language: OutputHeaderLanguage,
    val includePersonalIdentifiers: Boolean = false,
    val extractionScope: ExtractionScope = ExtractionScope.ALL,
    val columnLayout: List<OutputColumnLayout> = emptyList(),
)

data class ProjectListSummary(
    val project: ProjectConfig,
    val submittedCount: Int = 0, val draftCount: Int = 0, val missingCount: Int = 0,
    val preparingCount: Int = 0, val readyForReviewCount: Int = 0,
    val confirmedCount: Int = 0, val failedCount: Int = 0,
)

data class RequestedOutputField(
    val id: String,
    val projectId: String,
    val title: String,
    val position: Int,
    val source: FieldSource,
    val questionId: String? = null,
    val mappingState: FieldMappingState = FieldMappingState.UNMAPPED,
)

data class ProjectDetails(
    val project: ProjectConfig,
    val output: OutputProfile,
    val fields: List<RequestedOutputField>,
)

enum class CloseBehavior { SAVE_AND_EXIT, SYSTEM_TRAY }

data class AppSettings(
    val language: UiLanguage? = null,
    val onboardingCompleted: Boolean = false,
    val closeBehavior: CloseBehavior = CloseBehavior.SAVE_AND_EXIT,
)
