package com.phoneform.desktop.data

import com.phoneform.ai.core.model.*
import com.phoneform.desktop.security.KeyProtector
import com.phoneform.desktop.security.SecureWorkspace
import java.io.ByteArrayInputStream
import java.nio.file.Files
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopRepositoriesTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var workspace: SecureWorkspace
    private lateinit var db: DesktopRepositories
    // Test-only authenticated protector; production uses CurrentUser DPAPI without fallback.
    private val protector = object : KeyProtector {
        private val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
        override fun protect(plain: ByteArray, purpose: String): ByteArray {
            val iv = ByteArray(12).also(java.security.SecureRandom()::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)); cipher.updateAAD(purpose.toByteArray()); return iv + cipher.doFinal(plain)
        }
        override fun unprotect(wrapped: ByteArray, purpose: String): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrapped.copyOfRange(0, 12))); cipher.updateAAD(purpose.toByteArray()); return cipher.doFinal(wrapped.copyOfRange(12, wrapped.size))
        }
    }
    @Before fun open() { workspace = SecureWorkspace.open(folder.newFolder("workspace").toPath(), protector); db = DesktopRepositories(workspace) }
    @After fun close() { workspace.close() }

    private suspend fun project(id: String = newId()): ProjectDetails {
        val now = System.currentTimeMillis()
        val project = ProjectDetails(ProjectConfig(id, "問卷 English", 1000, QuestionnaireLanguage.ENGLISH, NumberingStrategy.GENERATED, now, now), OutputProfile(id, language = OutputHeaderLanguage.ENGLISH), emptyList())
        db.projects.saveProject(project); return db.projects.getProject(id)!!
    }
    private fun page(position: Int, prefix: String = newId()): SamplePage {
        val data = "synthetic original $prefix".toByteArray()
        val original = workspace.assets.put("originals/$prefix.bin", ByteArrayInputStream(data))
        workspace.assets.put("renders/$prefix.png", ByteArrayInputStream("render $prefix".toByteArray()))
        workspace.assets.put("thumbs/$prefix.png", ByteArrayInputStream("thumb $prefix".toByteArray()))
        return SamplePage(newId(), SampleAsset(newId(), SampleKind.IMAGE, original.relativePath, original.sha256, original.byteCount), 0, position, "renders/$prefix.png", "thumbs/$prefix.png", 1200, 1600)
    }
    private suspend fun template(projectId: String, count: Int): FormSchemaVersion {
        val draft = db.templates.beginDraft(projectId)
        val pages = (0 until count).map { page(it) }
        val questions = pages.mapIndexed { index, page -> QuestionDefinition("q${index + 1}", page.id, 0, "Q${index + 1}", if (index == 0) "Private name" else "Answer ${index + 1}", QuestionType.TEXT, NormalizedRect(.1f, .1f, .9f, .5f), NormalizedRect(.1f, .3f, .9f, .5f)) }
        val saved = db.templates.saveDraft(draft.copy(pages = pages, questions = questions))
        db.imageProcessing.keepAllWarnings(saved.owner(), pages.associate { it.id to PageProcessingFingerprint.of(it) }, "Fixture source reviewed")
        return db.templates.confirmDraft(projectId, saved.id, saved.revision)
    }
    private suspend fun draft(projectId: String, template: FormSchemaVersion, slots: List<Int> = template.document.pages.indices.toList()): SubmissionDraft {
        val opened = db.submissions.beginSubmission(projectId)
        val saved = db.submissions.saveDraft(opened.copy(respondentId = "00123", pages = slots.map { index -> PageRevision(template.document.pages[index].id, page(index)) }))
        db.imageProcessing.keepAllWarnings(saved.owner(), saved.pages.associate { it.image.id to PageProcessingFingerprint.of(it.image) }, "Fixture source reviewed")
        return saved
    }
    private suspend fun prepare(revision: SubmissionRevision): ProcessingJob {
        // Template and draft quality jobs are now obsolete; process current revision jobs first.
        while (true) {
            val quality = db.imageProcessing.claim("test", true) ?: break
            val report = PageQualityReport(quality.inputFingerprint, "fixture", assessment = PageQualityAssessment.ASSESSED, inputSha256 = quality.inputSha256)
            assertTrue(db.imageProcessing.complete(quality, PageProcessingResult(quality.inputFingerprint, report)))
        }
        val job = db.jobs.claimNext("test", true)!!
        assertEquals(revision.id, job.submissionRevisionId)
        assertTrue(db.jobs.checkpoint(job, revision.pages)); assertTrue(db.jobs.complete(job)); return job
    }
    private inline fun <reified T : Throwable> fails(block: () -> Unit): T {
        try { block() } catch (failure: Throwable) { if (failure is T) return failure; throw failure }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }

    @Test fun fixedSlotsAndRetryAreAtomicAndStructureLocks() = runBlocking {
        val project = project(); val template = template(project.project.id, 3)
        val original = draft(project.project.id, template, listOf(0, 2))
        val confirmation = SubmissionConfirmation(allowMissingPages = true)
        val revision = db.submissions.submitSubmission(original.id, original.editRevision, confirmation)
        assertEquals(listOf(template.document.pages[0].id, template.document.pages[2].id), revision.pages.map { it.templatePageId })
        assertEquals(listOf(template.document.pages[1].id), revision.missingTemplatePageIds)
        assertEquals(revision, db.submissions.submitSubmission(original.id, original.editRevision, confirmation))
        assertEquals(1, db.submissions.observeJobs(project.project.id).first().size)
        assertEquals(1, db.projects.observeProjectSummaries().first().single().submittedCount)
        fails<CollectionLockedException> { runBlocking { db.templates.beginDraft(project.project.id) } }
        fails<StaleSubmissionException> { runBlocking { db.submissions.saveDraft(original) } }
        val next = db.submissions.beginSubmission(project.project.id)
        assertEquals(2, db.submissions.getSubmission(next.submissionId)!!.number)
        assertEquals(next, db.submissions.beginSubmission(project.project.id))
    }

    @Test fun staleClaimsCannotWriteAfterRevisionAndManualAnswersSurviveCandidates() = runBlocking {
        val project = project(); val template = template(project.project.id, 1)
        val draft = draft(project.project.id, template); val revision = db.submissions.submitSubmission(draft.id, draft.editRevision, SubmissionConfirmation())
        prepare(revision)
        val recognition = db.intelligence.claimRecognition("test", true)!!
        val first = db.intelligence.saveAnswer(draft.submissionId, revision.id, 0, AnswerValue("question:q1", AnswerState.VALUE, "00123"), "answer-operation")
        val page = revision.pages.single()
        val alignment = PageAlignment(page.templatePageId, page.image.id, page.image.revision, AlignmentStatus.ALIGNED, listOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0))
        val observation = FieldObservation("question:q1", page.templatePageId, page.image.id, page.image.revision, AnswerValue("question:q1", AnswerState.VALUE, "999"), requiresReview = false)
        assertTrue(db.intelligence.checkpointRecognition(recognition, alignment, listOf(observation))); assertTrue(db.intelligence.completeRecognition(recognition))
        assertEquals("00123", db.intelligence.getReview(draft.submissionId)!!.answers!!.answers.single().text)
        assertEquals(first, db.intelligence.saveAnswer(draft.submissionId, revision.id, 0, AnswerValue("question:q1", AnswerState.VALUE, "00123"), "answer-operation"))
        val confirmed = db.intelligence.confirmReview(draft.submissionId, revision.id, first.version, "confirm-operation")
        assertEquals(first.id, confirmed.answerRevisionId)
        val replacement = db.submissions.beginRevision(draft.submissionId)
        val changed = db.submissions.saveDraft(replacement.copy(pages = listOf(PageRevision(page.templatePageId, page(0)))))
        db.imageProcessing.keepAllWarnings(changed.owner(), changed.pages.associate { it.image.id to PageProcessingFingerprint.of(it.image) }, "New source reviewed")
        val revised = db.submissions.submitSubmission(changed.id, changed.editRevision, SubmissionConfirmation())
        assertFalse(db.intelligence.checkpointRecognition(recognition, alignment, listOf(observation)))
        assertFalse(db.jobs.checkpoint(prepareInvalidJob(revision), revision.pages))
        assertNull(db.intelligence.getReview(draft.submissionId)!!.confirmation)
        assertEquals(revised.id, db.intelligence.getReview(draft.submissionId)!!.revision.id)
        assertNull(db.intelligence.getReview(draft.submissionId)!!.answers)
    }
    private fun prepareInvalidJob(revision: SubmissionRevision) = ProcessingJob("missing", revision.projectId, revision.submissionId, revision.id, PreparationJobStatus.RUNNING, runToken = "old", processSessionId = "old", createdAtEpochMillis = 0)

    @Test fun immutableFourSheetSnapshotsApplyPrivacyAndPreserveExactText() = runBlocking {
        val project = project(); val template = template(project.project.id, 2)
        val draft = draft(project.project.id, template); val revision = db.submissions.submitSubmission(draft.id, draft.editRevision, SubmissionConfirmation()); prepare(revision)
        var answer = db.intelligence.saveAnswer(draft.submissionId, revision.id, 0, AnswerValue("question:q1", AnswerState.VALUE, "00123"), "save1")
        answer = db.intelligence.saveAnswer(draft.submissionId, revision.id, answer.version, AnswerValue("question:q2", AnswerState.VALUE, "=HYPERLINK(\"test\")"), "save2")
        db.intelligence.confirmReview(draft.submissionId, revision.id, answer.version, "confirm")
        db.intelligence.savePolicy(OutputFieldPolicy(project.project.id, "question:q1", true))
        val frozen = db.intelligence.createExportSnapshot(project.project.id, false, "snapshot1")
        assertEquals(4, frozen.sheets.size)
        assertFalse(frozen.sheets.any { it.headers.any { title -> title == "Private name" } })
        assertFalse(frozen.sheets.flatMap { it.rows }.flatten().any { it.value == "00123" })
        assertTrue(frozen.sheets.flatMap { it.rows }.flatten().any { it.value == "=HYPERLINK(\"test\")" && it.type == ExportCellType.TEXT })
        val changed = db.intelligence.saveAnswer(draft.submissionId, revision.id, answer.version, AnswerValue("question:q2", AnswerState.VALUE, "new"), "save3")
        assertNotNull(changed)
        assertEquals(frozen, db.intelligence.getExportSnapshot("snapshot1"))
        assertEquals(frozen, db.intelligence.createExportSnapshot(project.project.id, false, "snapshot1"))
        assertNull(db.intelligence.getReview(draft.submissionId)!!.confirmation)
    }

    @Test fun outputSectionCasAndEncryptedEditorRecoverySurviveReopen() = runBlocking {
        val project = project()
        val first = db.projects.saveProjectSection(project.copy(output = project.output.copy(language = OutputHeaderLanguage.TRADITIONAL)), ProjectEditSection.OUTPUT, project.output.version)
        fails<StaleOutputConfigurationException> { runBlocking { db.projects.saveProjectSection(project.copy(output = project.output.copy(language = OutputHeaderLanguage.SIMPLIFIED)), ProjectEditSection.OUTPUT, project.output.version) } }
        val renamed = db.projects.saveProjectSection(project.copy(project = project.project.copy(name = "renamed")), ProjectEditSection.PROJECT_INFO)
        assertEquals(first.output.language, renamed.output.language)
        val editor = db.editors.save("template-editor", project.project.id, "private unsaved text", 0)
        fails<IllegalStateException> { runBlocking { db.editors.save("template-editor", project.project.id, "stale", 0) } }
        val root = workspace.root; workspace.close(); workspace = SecureWorkspace.open(root, protector); db = DesktopRepositories(workspace)
        assertEquals(editor, db.editors.read("template-editor")); assertEquals(renamed, db.projects.getProject(project.project.id))
        val raw = Files.readAllBytes(root.resolve("phoneform.db")).toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains("private unsaved text")); assertFalse(raw.contains("renamed"))
    }

    @Test fun deletionWaitsForLeaseRejectsLateWriterAndProtectsSharedEvidence() = runBlocking {
        val first = project(); val second = project()
        val template = template(first.project.id, 1)
        // A second project's draft intentionally shares the original and derived assets.
        val secondDraft = db.templates.beginDraft(second.project.id)
        db.templates.saveDraft(secondDraft.copy(pages = template.document.pages.map { it.copy(id = "second-page", noExtraction = true) }))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val lease = async(Dispatchers.Default) { ProjectOperationGate.shared.withLease(first.project.id) { entered.complete(Unit); release.await() } }
        entered.await(); db.maintenance.requestDeletion(first.project.id)
        val deletion = async(Dispatchers.Default) { db.maintenance.resumePendingDeletions() }
        fails<ProjectDeletedException> { runBlocking { db.projects.saveProject(first) } }
        assertNotNull(db.projects.getProject(first.project.id)); assertFalse(deletion.isCompleted)
        release.complete(Unit); lease.await(); deletion.await()
        assertNull(db.projects.getProject(first.project.id))
        assertNotNull(db.projects.getProject(second.project.id))
        assertTrue(workspace.assets.exists(template.document.pages.single().asset.relativePath))
        val status = db.maintenance.observeDeletions().first().single()
        assertEquals(ProjectDeletionPhase.COMPLETE, status.phase)
        assertEquals(status, db.maintenance.retryDeletion(first.project.id))
        fails<ProjectDeletedException> { runBlocking { db.templates.confirmDraft(first.project.id, template.sourceDraftId, template.document.revision) } }
        Unit
    }

    @Test fun transactionRollsBackInvalidDraftAndDuplicateSourceRequiresChoice() = runBlocking {
        val project = project(); val template = template(project.project.id, 1)
        val first = draft(project.project.id, template)
        val revision = db.submissions.submitSubmission(first.id, first.editRevision, SubmissionConfirmation())
        val opened = db.submissions.beginSubmission(project.project.id)
        val wrong = opened.copy(pages = listOf(PageRevision("unknown-slot", page(0))))
        fails<SubmissionValidationException> { runBlocking { db.submissions.saveDraft(wrong) } }
        assertEquals(opened, db.submissions.getDraft(opened.id))
        val source = revision.pages.single()
        val duplicate = db.submissions.saveDraft(opened.copy(pages = listOf(source.copy(image = source.image.copy(id = newId())))))
        db.imageProcessing.keepAllWarnings(duplicate.owner(), duplicate.pages.associate { it.image.id to PageProcessingFingerprint.of(it.image) }, "Duplicate reviewed")
        val failure = fails<SubmissionValidationException> { runBlocking { db.submissions.submitSubmission(duplicate.id, duplicate.editRevision, SubmissionConfirmation()) } }
        assertTrue(failure.issues.any { it.code == SubmissionIssueCode.DUPLICATE_PAGE_SOURCE })
        assertNotNull(db.submissions.getDraft(duplicate.id))
        val committed = db.submissions.submitSubmission(duplicate.id, duplicate.editRevision, SubmissionConfirmation(duplicatePageSourcesConfirmed = true))
        assertNotEquals(revision.id, committed.id)
    }

    @Test fun ownershipJournalCollectsUnsavedImportsAndLeavesExternalFilesUntouched() = runBlocking {
        val project = project()
        val orphan = ProjectOperationGate.shared.withLease(project.project.id) {
            workspace.assets.put("imports/unsaved-source.png", ByteArrayInputStream("unsaved private source".toByteArray()))
        }
        val external = folder.root.toPath().resolve("user-original.png")
        Files.writeString(external, "external original")
        assertTrue(workspace.assets.ownershipPaths(project.project.id).contains(orphan.relativePath))
        val preview = db.maintenance.previewDeletion(project.project.id)
        assertTrue(preview.exclusiveBytes > 0)
        db.maintenance.requestDeletion(project.project.id); db.maintenance.resumePendingDeletions()
        assertFalse(workspace.assets.exists(orphan.relativePath))
        assertTrue(Files.exists(external))
        assertTrue(workspace.assets.ownershipPaths(project.project.id).isEmpty())
        assertEquals(ProjectDeletionPhase.COMPLETE, db.maintenance.observeDeletions().first().single().phase)
    }

    @Test fun processRecoveryReissuesClaimAndRejectsPreviousRunToken() = runBlocking {
        val project = project(); val template = template(project.project.id, 1)
        val draft = draft(project.project.id, template)
        val revision = db.submissions.submitSubmission(draft.id, draft.editRevision, SubmissionConfirmation())
        val old = db.jobs.claimNext("old-process", true)!!
        assertTrue(db.jobs.checkpoint(old, revision.pages))
        db.jobs.initializeProcess("new-process")
        val recovered = db.jobs.claimNext("new-process", true)!!
        assertNotEquals(old.runToken, recovered.runToken)
        assertEquals(revision.pages, recovered.preparedPages)
        assertFalse(db.jobs.checkpoint(old, revision.pages)); assertFalse(db.jobs.complete(old))
        assertTrue(db.jobs.complete(recovered))
        assertEquals(1, db.intelligence.observeRecognitionJobs(project.project.id).first().size)
    }

    @Test fun unsavedOtherProjectEditorAloneProtectsSharedPageAndLaterDeletionCollectsIt() = runBlocking {
        val first = project(); val second = project()
        val template = template(first.project.id, 1)
        val page = template.document.pages.single()
        db.editors.save("unsaved-shared-page", second.project.id, Records.gson.toJson(page), 0)
        db.maintenance.requestDeletion(first.project.id); db.maintenance.resumePendingDeletions()
        assertNull(db.projects.getProject(first.project.id))
        assertTrue(workspace.assets.exists(page.asset.relativePath))
        assertTrue(workspace.assets.exists(page.renderPath))
        assertTrue(workspace.assets.exists(page.thumbnailPath))
        assertTrue(db.maintenance.previewDeletion(second.project.id).exclusiveBytes > 0)
        db.maintenance.requestDeletion(second.project.id); db.maintenance.resumePendingDeletions()
        assertFalse(workspace.assets.exists(page.asset.relativePath))
        assertFalse(workspace.assets.exists(page.renderPath))
        assertFalse(workspace.assets.exists(page.thumbnailPath))
    }
}
