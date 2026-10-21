package com.phoneform.desktop

import com.phoneform.ai.core.model.*
import org.junit.Assert.*
import org.junit.Test

class DataPresentationTest {
    @Test fun columnMoveRetainsHiddenAliasesAndNeverMovesFixedColumns() {
        val original = listOf(OutputColumnLayout("hidden", "Private alias", 8), OutputColumnLayout("first", "Exact alias", 0))
        val preview = listOf(OutputColumn("submission_id", "Number"), OutputColumn("respondent_id", "Paper ID"), OutputColumn("first", "Exact alias"), OutputColumn("second", "Second"))
        val moved = moveOutputColumn(original, preview, "second", -1)
        assertEquals(OutputColumnLayout("hidden", "Private alias", 8), moved.first { it.key == "hidden" })
        assertEquals("Exact alias", moved.first { it.key == "first" }.titleOverride)
        assertEquals(0, moved.first { it.key == "second" }.position)
        assertEquals(1, moved.first { it.key == "first" }.position)
        assertEquals(original, moveOutputColumn(original, preview, "respondent_id", 1))
    }

    @Test fun confirmationPreflightAllowsOnlyReadySafeCandidates() {
        val workspace = review()
        val job = requireNotNull(workspace.job)
        assertTrue(unresolvedReviewTargets(workspace).isEmpty())
        assertEquals(listOf("question:number"), unresolvedReviewTargets(workspace.copy(job = job.copy(status = RecognitionJobStatus.RUNNING))))
        assertEquals(listOf("question:number"), unresolvedReviewTargets(workspace.copy(job = job.copy(observations = job.observations.map { it.copy(requiresReview = true) }))))
    }

    @Test fun invalidHumanInputCannotBeReplacedBySafeMachineCandidate() {
        val workspace = review()
        val edited = AnswerSetRevision("answers", "project", "submission", "revision", 1, "operation",
            listOf(AnswerValue("question:number", AnswerState.VALUE, "not a number", humanEdited = true)))
        assertEquals(listOf("question:number"), unresolvedReviewTargets(workspace.copy(answers = edited)))
    }

    @Test fun missingPagesRemainNotCapturedDuringConfirmationPreflight() {
        val workspace = review()
        assertTrue(unresolvedReviewTargets(workspace.copy(job = null, revision = workspace.revision.copy(missingTemplatePageIds = listOf("page")))).isEmpty())
    }

    @Test fun recoveredEditsBlockConfirmationEvenWhenSavedAnswerIsValid() {
        val base = review()
        val saved = AnswerValue("question:number", AnswerState.VALUE, "12", humanEdited = true)
        val workspace = base.copy(answers = AnswerSetRevision("saved", "project", "submission", "revision", 2, "save", listOf(saved)))
        assertTrue(unresolvedReviewTargets(workspace).isEmpty())
        assertEquals(listOf(saved.targetKey), dirtyReviewRecoveryTargets(workspace, mapOf(saved.targetKey to editorJson.toJson(saved.copy(text = "99")))))
        assertTrue(dirtyReviewRecoveryTargets(workspace, mapOf(saved.targetKey to editorJson.toJson(saved))).isEmpty())
        assertEquals("answer:revision:question:number:2", answerEditorSession(workspace, saved.targetKey))
    }

    @Test fun carryForwardOnlyCopiesUnchangedTargetsAfterExpectedVersionSave() {
        val base = review()
        val other = AnswerTarget("field:other", "Other")
        val before = base.copy(targets = base.targets + other)
        val pending = mapOf("question:number" to editorJson.toJson(AnswerValue("question:number", AnswerState.VALUE, "88", humanEdited = true)))
        val after = AnswerSetRevision("next", "project", "submission", "revision", 1, "save-other",
            listOf(initialReviewAnswer(before, base.targets.single()), AnswerValue(other.key, AnswerState.VALUE, "new value", humanEdited = true)))
        val plan = planAnswerRecoveryCarryForward(before, after, pending)
        assertEquals(pending, plan.copies)
        assertTrue(plan.blockedKeys.isEmpty())
        assertEquals("answer:revision:question:number:1", answerEditorSession(before, "question:number", after.version))
        assertEquals(listOf("question:number"), planAnswerRecoveryCarryForward(before, after.copy(version = 3), pending).blockedKeys)
        assertEquals(listOf("question:number"), planAnswerRecoveryCarryForward(before, after.copy(submissionRevisionId = "newer-revision"), pending).blockedKeys)
    }

    @Test fun changedSavedTargetKeepsItsOriginalRecoveryInsteadOfCarryingItForward() {
        val before = review()
        val pending = mapOf("question:number" to editorJson.toJson(AnswerValue("question:number", AnswerState.VALUE, "88")))
        val after = AnswerSetRevision("next", "project", "submission", "revision", 1, "changed-number", listOf(AnswerValue("question:number", AnswerState.VALUE, "77", humanEdited = true)))
        val plan = planAnswerRecoveryCarryForward(before, after, pending)
        assertTrue(plan.copies.isEmpty())
        assertEquals(listOf("question:number"), plan.blockedKeys)
    }

    @Test(expected = IllegalArgumentException::class) fun recoveryCannotBeAppliedToADifferentTarget() {
        dirtyReviewRecoveryTargets(review(), mapOf("question:number" to editorJson.toJson(AnswerValue("field:other", AnswerState.VALUE, "wrong target"))))
    }

    private fun review(): ReviewWorkspace {
        val project = ProjectDetails(ProjectConfig("project", "Test", null, QuestionnaireLanguage.ENGLISH,
            NumberingStrategy.GENERATED, 0, 0), OutputProfile("project", language = OutputHeaderLanguage.ENGLISH), emptyList())
        val question = QuestionDefinition("number", "page", 0, "1", "Number", QuestionType.NUMBER)
        val target = AnswerTarget("question:number", "Number", question)
        val template = FormSchemaVersion("template", "project", 1, "draft", 0, TemplateDraft("draft", "project", questions = listOf(question)))
        val submission = Submission("submission", "project", 1, "template", NumberingStrategy.GENERATED, 0, "revision")
        val revision = SubmissionRevision("revision", "submission", "project", "template", 1, "collection-draft", 1,
            "", emptyList(), emptyList(), SubmissionConfirmation(), 0)
        val job = RecognitionJob("job", "project", "submission", "revision", RecognitionJobStatus.READY,
            observations = listOf(FieldObservation(target.key, "page", "image", 1,
                candidate = AnswerValue(target.key, AnswerState.VALUE, "12"), requiresReview = false)))
        return ReviewWorkspace(project, submission, revision, template, job, null, null, listOf(target))
    }
}
