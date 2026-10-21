package com.phoneform.ai.core.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

enum class SuggestionDisposition { PENDING, ACCEPTED, DISMISSED }
data class TemplateSuggestion(val id: String, val pageId: String, val imageRevision: Int,
    val question: QuestionDefinition, val disposition: SuggestionDisposition = SuggestionDisposition.PENDING,
    val suggestedFieldId: String? = null, val reason: String = "",
    val scope: TemplateAnalysisScope? = null, val processingFingerprint: String = "",
    val sourceAnchor: NormalizedRect? = null)
data class TemplateAnalysisRun(val id: String, val projectId: String, val draftId: String,
    val draftRevision: Int, val engineVersion: String, val suggestions: List<TemplateSuggestion>,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val scope: TemplateAnalysisScope? = null, val processingFingerprints: Map<String, String> = emptyMap())
data class TemplateRecognitionProfile(val templateVersionId: String, val engineVersion: String,
    val pageFingerprints: Map<String, String>, val createdAtEpochMillis: Long = System.currentTimeMillis())

enum class AlignmentStatus { ALIGNED, FAILED, MANUAL }
data class PageAlignment(val templatePageId: String, val imageId: String, val imageRevision: Int,
    val status: AlignmentStatus, val templateToImage: List<Double> = emptyList(),
    val reason: String = "", val engineVersion: String = "")
enum class AnswerState { UNHANDLED, VALUE, BLANK, NOT_APPLICABLE, UNREADABLE, NOT_CAPTURED }
enum class AnswerSource { MANUAL, MACHINE_ACCEPTED, INHERITED }
/** Keys are question:<stable question id> or field:<stable manual field id>. */
data class AnswerValue(val targetKey: String, val state: AnswerState = AnswerState.UNHANDLED,
    val text: String = "", val optionIds: List<String> = emptyList(), val source: AnswerSource = AnswerSource.MANUAL,
    val humanEdited: Boolean = false)
data class FieldObservation(val targetKey: String, val templatePageId: String, val imageId: String,
    val imageRevision: Int, val candidate: AnswerValue? = null, val rawText: String = "",
    val requiresReview: Boolean = true, val reason: String = "", val evidenceRegion: NormalizedRect? = null,
    val engineVersion: String = "", val processingFingerprint: String = "", val enhancedRawText: String = "")
enum class RecognitionJobStatus { QUEUED, RUNNING, PAUSED, FAILED, READY, SUPERSEDED }
data class RecognitionJob(val id: String, val projectId: String, val submissionId: String,
    val submissionRevisionId: String, val status: RecognitionJobStatus = RecognitionJobStatus.QUEUED,
    val completedPageIds: List<String> = emptyList(), val observations: List<FieldObservation> = emptyList(),
    val alignments: List<PageAlignment> = emptyList(), val attempt: Int = 0,
    val processSessionId: String? = null, val runToken: String? = null, val error: String? = null,
    val createdAtEpochMillis: Long = System.currentTimeMillis())
data class AnswerSetRevision(val id: String, val projectId: String, val submissionId: String,
    val submissionRevisionId: String, val version: Int, val operationId: String,
    val answers: List<AnswerValue>, val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val operatorId: String = "local-operator")
data class ReviewConfirmation(val id: String, val submissionRevisionId: String, val answerRevisionId: String,
    val operationId: String, val confirmedAtEpochMillis: Long = System.currentTimeMillis(), val operatorId: String = "local-operator")
data class AnswerTarget(val key: String, val title: String, val question: QuestionDefinition? = null)
data class ReviewWorkspace(val project: ProjectDetails, val submission: Submission, val revision: SubmissionRevision,
    val template: FormSchemaVersion, val job: RecognitionJob?, val answers: AnswerSetRevision?,
    val confirmation: ReviewConfirmation?, val targets: List<AnswerTarget>)
data class OutputFieldPolicy(val projectId: String, val targetKey: String, val personalIdentifier: Boolean, val exportIncluded: Boolean = true)
data class ReviewListSummary(val submissionId: String, val number: Int, val submissionRevisionId: String,
    val missingPageCount: Int, val preparationStatus: PreparationJobStatus?, val recognitionStatus: RecognitionJobStatus?,
    val confirmed: Boolean, val hasExceptions: Boolean)
class StaleAnswerException : IllegalStateException("Answers or submission changed; reload before saving")
class ReviewValidationException(val targetKeys: List<String>) : IllegalArgumentException("Resolve all unhandled or invalid answers before confirmation")

enum class ExportCellType { TEXT, NUMBER }
data class ExportCell(val value: String = "", val type: ExportCellType = ExportCellType.TEXT)
data class ExportSheet(val name: String, val headers: List<String>, val rows: List<List<ExportCell>>)
data class ExportRecordReference(val submissionId: String, val submissionRevisionId: String,
    val answerRevisionId: String?, val confirmationId: String?)
data class ExportSnapshot(val id: String, val projectId: String, val projectName: String,
    val outputVersion: Int, val includeUnresolved: Boolean, val createdAtEpochMillis: Long,
    val records: List<ExportRecordReference>, val excludedReasons: Map<String, String>, val sheets: List<ExportSheet>)
enum class ExportArtifactStatus { GENERATING, READY, FAILED }
data class ExportArtifact(val snapshotId: String, val status: ExportArtifactStatus,
    val relativePath: String? = null, val sha256: String? = null, val byteCount: Long = 0, val error: String? = null)

interface IntelligenceRepository {
    fun observeReviewSummaries(projectId: String): Flow<List<ReviewListSummary>> = flowOf(emptyList())
    fun observeAnalysis(draftId: String): Flow<TemplateAnalysisRun?>
    suspend fun saveAnalysis(run: TemplateAnalysisRun)
    suspend fun decideSuggestion(draftId: String, suggestionId: String, accept: Boolean): TemplateDraft
    suspend fun saveRecognitionProfile(profile: TemplateRecognitionProfile)
    suspend fun getRecognitionProfile(templateVersionId: String): TemplateRecognitionProfile?
    fun observeRecognitionJobs(projectId: String): Flow<List<RecognitionJob>>
    suspend fun initializeRecognition(processSessionId: String)
    suspend fun claimRecognition(processSessionId: String, appInForeground: Boolean): RecognitionJob?
    suspend fun isRecognitionClaimValid(job: RecognitionJob, appInForeground: Boolean): Boolean
    suspend fun checkpointAlignment(job: RecognitionJob, alignment: PageAlignment): Boolean
    suspend fun checkpointRecognition(job: RecognitionJob, alignment: PageAlignment, observations: List<FieldObservation>): Boolean
    suspend fun completeRecognition(job: RecognitionJob): Boolean
    suspend fun pauseRecognition(job: RecognitionJob, reason: String)
    suspend fun failRecognition(job: RecognitionJob, reason: String)
    suspend fun retryRecognition(jobId: String)
    fun observeReview(submissionId: String): Flow<ReviewWorkspace?>
    suspend fun getReview(submissionId: String): ReviewWorkspace?
    suspend fun saveAnswer(submissionId: String, submissionRevisionId: String, expectedVersion: Int,
        answer: AnswerValue, operationId: String): AnswerSetRevision
    suspend fun confirmReview(submissionId: String, submissionRevisionId: String, expectedVersion: Int,
        operationId: String): ReviewConfirmation
    fun observeAnswerHistory(submissionId: String): Flow<List<AnswerSetRevision>>
    fun observePolicies(projectId: String): Flow<List<OutputFieldPolicy>>
    suspend fun savePolicy(policy: OutputFieldPolicy)
    suspend fun createExportSnapshot(projectId: String, includeUnresolved: Boolean, operationId: String): ExportSnapshot
    fun observeExports(projectId: String): Flow<List<ExportSnapshot>>
    suspend fun getExportSnapshot(snapshotId: String): ExportSnapshot?
    suspend fun saveExportArtifact(artifact: ExportArtifact)
    suspend fun getExportArtifact(snapshotId: String): ExportArtifact?
}

object ReviewTargets {
    fun build(project: ProjectDetails, template: FormSchemaVersion): List<AnswerTarget> {
        val questions = template.document.questions.associateBy { it.id }
        val targets = linkedMapOf<String, AnswerTarget>()
        project.fields.sortedBy { it.position }.forEach { field ->
            val question = field.questionId?.takeIf { field.source == FieldSource.QUESTIONNAIRE && field.mappingState == FieldMappingState.BOUND }?.let { questions[it] }
            val key = if (question != null) "question:${question.id}" else "field:${field.id}"
            targets.putIfAbsent(key, AnswerTarget(key, question?.title ?: field.title, question))
        }
        if (project.output.extractionScope == ExtractionScope.ALL) template.document.questions.sortedWith(compareBy({ q -> template.document.pages.find { it.id == q.pageId }?.position ?: 0 }, { it.position })).forEach {
            val key = "question:${it.id}"; targets.putIfAbsent(key, AnswerTarget(key, it.title, it))
        }
        return targets.values.toList()
    }
    fun valid(answer: AnswerValue, target: AnswerTarget): Boolean {
        if (answer.state == AnswerState.UNHANDLED) return false
        if (answer.state != AnswerState.VALUE) return answer.text.isEmpty() && answer.optionIds.isEmpty()
        val question = target.question ?: return answer.text.isNotBlank() && answer.optionIds.isEmpty()
        return when (question.type) {
            QuestionType.SINGLE_CHOICE -> answer.text.isEmpty() && answer.optionIds.size == 1 && answer.optionIds.all { id -> question.options.any { it.id == id } }
            QuestionType.MULTIPLE_CHOICE -> answer.text.isEmpty() && answer.optionIds.isNotEmpty() && answer.optionIds.distinct().size == answer.optionIds.size && answer.optionIds.all { id -> question.options.any { it.id == id } }
            QuestionType.NUMBER -> answer.optionIds.isEmpty() && answer.text.matches(Regex("[+-]?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)")) && answer.text.toBigDecimalOrNull() != null
            QuestionType.DATE -> answer.optionIds.isEmpty() && answer.text.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) && runCatching { java.time.LocalDate.parse(answer.text) }.isSuccess
            QuestionType.TEXT -> answer.optionIds.isEmpty() && answer.text.isNotBlank()
        }
    }
}
