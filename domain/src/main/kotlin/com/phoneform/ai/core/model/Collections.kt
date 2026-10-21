package com.phoneform.ai.core.model

import kotlinx.coroutines.flow.Flow

enum class CollectionMode { COLLECTING, BACKGROUND_ALLOWED }
enum class PreparationJobStatus { QUEUED, RUNNING, PAUSED, FAILED, READY, SUPERSEDED }
enum class PreparationStage { VERIFY_IMAGES, PREPARE_IMAGES, READY }

data class Submission(
    val id: String,
    val projectId: String,
    val number: Int,
    val templateVersionId: String,
    val numberingStrategy: NumberingStrategy,
    val createdAtEpochMillis: Long,
    val currentRevisionId: String? = null,
    val respondentId: String = "",
    val sourceSampleVersionId: String? = null,
    val discarded: Boolean = false,
)

/** A fixed template slot, never an implicit position in a compressed page list. */
data class PageRevision(val templatePageId: String, val image: SamplePage)

data class SubmissionDraft(
    val id: String,
    val submissionId: String,
    val projectId: String,
    val templateVersionId: String,
    val baseRevisionId: String? = null,
    val editRevision: Int = 0,
    val respondentId: String = "",
    val pages: List<PageRevision> = emptyList(),
    val updatedAtEpochMillis: Long = System.currentTimeMillis(),
)

data class SubmissionConfirmation(
    val allowMissingPages: Boolean = false,
    val duplicatePageSourcesConfirmed: Boolean = false,
    val repeatedRespondentReason: String = "",
)

data class SubmissionRevision(
    val id: String,
    val submissionId: String,
    val projectId: String,
    val templateVersionId: String,
    val number: Int,
    val sourceDraftId: String,
    val sourceEditRevision: Int,
    val respondentId: String,
    val pages: List<PageRevision>,
    val missingTemplatePageIds: List<String>,
    val confirmation: SubmissionConfirmation,
    val submittedAtEpochMillis: Long,
)

data class ProcessingJob(
    val id: String,
    val projectId: String,
    val submissionId: String,
    val submissionRevisionId: String,
    val status: PreparationJobStatus,
    val stage: PreparationStage = PreparationStage.VERIFY_IMAGES,
    val preparedPages: List<PageRevision> = emptyList(),
    val attempt: Int = 0,
    val error: String? = null,
    val processSessionId: String? = null,
    val runToken: String? = null,
    val createdAtEpochMillis: Long,
)

data class ProjectCollectionState(
    val projectId: String,
    val mode: CollectionMode = CollectionMode.COLLECTING,
    val policyGeneration: Long = 0,
    val wakeGeneration: Long = 0,
    val scheduledGeneration: Long = 0,
    val schedulingError: String? = null,
    val locked: Boolean = false,
)

enum class SubmissionIssueCode {
    NO_PAGES, UNKNOWN_SLOT, DUPLICATE_SLOT, INVALID_PAGE, PAPER_ID_REQUIRED,
    MISSING_PAGES, DUPLICATE_PAGE_SOURCE, REPEATED_RESPONDENT, FILE_UNAVAILABLE,
}
data class SubmissionIssue(val code: SubmissionIssueCode, val pageIds: List<String> = emptyList(), val relatedSubmissionIds: List<String> = emptyList())
class SubmissionValidationException(val issues: List<SubmissionIssue>) : IllegalArgumentException("Submission requires attention")
class StaleSubmissionException : IllegalStateException("Submission draft changed; reload before saving")
class CollectionLockedException : IllegalStateException("Collection locks questionnaire structure")
class PendingTemplateDraftException : IllegalStateException("Confirm or discard the template draft first")

interface SubmissionRepository {
    fun observeSubmissions(projectId: String): Flow<List<Submission>>
    fun observeDrafts(projectId: String): Flow<List<SubmissionDraft>>
    fun observeDraft(draftId: String): Flow<SubmissionDraft?>
    fun observeRevisions(submissionId: String): Flow<List<SubmissionRevision>>
    fun observeJobs(projectId: String): Flow<List<ProcessingJob>>
    fun observeCollectionState(projectId: String): Flow<ProjectCollectionState>
    suspend fun getSubmission(id: String): Submission?
    suspend fun getDraft(id: String): SubmissionDraft?
    suspend fun getDraftForSubmission(submissionId: String): SubmissionDraft?
    suspend fun getRevision(id: String): SubmissionRevision?
    suspend fun beginSubmission(projectId: String): SubmissionDraft
    /** Repeated conversion returns the same formal record, including after it was submitted. */
    suspend fun convertSampleToFirst(projectId: String): Submission
    suspend fun beginRevision(submissionId: String): SubmissionDraft
    suspend fun saveDraft(draft: SubmissionDraft): SubmissionDraft
    suspend fun discardDraft(draftId: String, expectedEditRevision: Int)
    suspend fun submitSubmission(draftId: String, expectedEditRevision: Int, confirmation: SubmissionConfirmation): SubmissionRevision
    suspend fun setCollectionMode(projectId: String, mode: CollectionMode)
    suspend fun retryJob(jobId: String)
}

/** Room is authoritative; Android scheduling only wakes the shared preparation engine. */
interface ProcessingJobRepository {
    suspend fun initializeProcess(processSessionId: String)
    suspend fun claimNext(processSessionId: String, appInForeground: Boolean): ProcessingJob?
    suspend fun isClaimValid(job: ProcessingJob, appInForeground: Boolean): Boolean
    suspend fun checkpoint(job: ProcessingJob, preparedPages: List<PageRevision>): Boolean
    suspend fun complete(job: ProcessingJob): Boolean
    suspend fun pause(job: ProcessingJob, reason: String)
    suspend fun fail(job: ProcessingJob, reason: String)
    suspend fun pendingSchedules(): List<ProjectCollectionState>
    suspend fun markScheduled(projectId: String, generation: Long)
    suspend fun schedulingFailed(projectId: String, generation: Long, reason: String)
    suspend fun requestBackgroundContinuation()
}

object SubmissionValidation {
    fun issues(draft: SubmissionDraft, submission: Submission, template: FormSchemaVersion,
               confirmation: SubmissionConfirmation, sameRespondent: List<String> = emptyList()): List<SubmissionIssue> = buildList {
        val slots = template.document.pages.map { it.id }
        if (draft.pages.isEmpty()) add(SubmissionIssue(SubmissionIssueCode.NO_PAGES))
        if (draft.pages.any { it.templatePageId !in slots }) add(SubmissionIssue(SubmissionIssueCode.UNKNOWN_SLOT))
        if (draft.pages.map { it.templatePageId }.distinct().size != draft.pages.size) add(SubmissionIssue(SubmissionIssueCode.DUPLICATE_SLOT))
        if (draft.pages.map { it.image.id }.distinct().size != draft.pages.size || draft.pages.any {
            it.image.width <= 0 || it.image.height <= 0 || it.image.revision < 1 || it.image.sourcePageIndex < 0 ||
                it.image.transform.sourceToPage.size != 9 || it.image.transform.sourceToPage.any { number -> !number.isFinite() }
        }) add(SubmissionIssue(SubmissionIssueCode.INVALID_PAGE))
        if (submission.numberingStrategy == NumberingStrategy.PAPER_ID && draft.respondentId.isBlank()) add(SubmissionIssue(SubmissionIssueCode.PAPER_ID_REQUIRED))
        val missing = slots.filterNot { slot -> draft.pages.any { it.templatePageId == slot } }
        if (missing.isNotEmpty() && !confirmation.allowMissingPages) add(SubmissionIssue(SubmissionIssueCode.MISSING_PAGES, missing))
        val duplicated = draft.pages.groupBy { it.image.asset.sha256 to it.image.sourcePageIndex }.values.filter { it.size > 1 }.flatten()
        if (duplicated.isNotEmpty() && !confirmation.duplicatePageSourcesConfirmed) add(SubmissionIssue(SubmissionIssueCode.DUPLICATE_PAGE_SOURCE, duplicated.map { it.templatePageId }))
        if (sameRespondent.isNotEmpty() && confirmation.repeatedRespondentReason.isBlank()) add(SubmissionIssue(SubmissionIssueCode.REPEATED_RESPONDENT, relatedSubmissionIds = sameRespondent))
    }
}
