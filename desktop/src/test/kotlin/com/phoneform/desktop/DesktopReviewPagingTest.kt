package com.phoneform.desktop

import com.phoneform.ai.core.model.*
import com.phoneform.desktop.security.SecureWorkspace
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopReviewPagingTest {
    @get:Rule val folder = TemporaryFolder()
    private suspend fun page(c: Controller, index: Int, count: Int): ReviewPage = withTimeout(15_000) {
        while (true) {
            val value = c.reviewPage.value
            val loaded = value.page
            if (!value.loading && !value.stale && value.error == null && loaded != null && loaded.query.pageIndex == index && loaded.totalSubmitted == count)
                return@withTimeout loaded
            delay(15)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    @Test fun refreshKeepsAnOpenOffPageQuestionnaireAndListPageChangesClearOnlyListSelection() = runBlocking {
        val source = folder.newFile("anonymous.png")
        val image = BufferedImage(300,400,BufferedImage.TYPE_INT_RGB)
        image.createGraphics().let { g -> try { g.color=Color.WHITE;g.fillRect(0,0,300,400);g.color=Color.BLACK;g.drawRect(20,20,260,360) } finally {g.dispose()} }
        check(ImageIO.write(image,"png",source)); image.flush()
        Controller(SecureWorkspace.open(folder.newFolder("workspace").toPath()),Path.of("../runtime/models"),startWorkers=false).use { c ->
            c.createProject("Anonymous paging",null,QuestionnaireLanguage.ENGLISH,NumberingStrategy.GENERATED,OutputHeaderLanguage.ENGLISH)
            c.importTemplate(source,listOf(0))
            val draft=c.templateDraft()
            val saved=c.saveTemplate(draft.copy(pages=draft.pages.map {it.copy(noExtraction=true)}))
            c.repositories.imageProcessing.keepAllWarnings(PageProcessingOwner(saved.projectId,PageProcessingOwnerKind.TEMPLATE_DRAFT,saved.id,saved.revision),
                saved.pages.associate {it.id to PageProcessingFingerprint.of(it)},"Anonymous paging fixture")
            c.confirmTemplate()
            val template=c.repositories.templates.observeConfirmed(saved.projectId).first()!!
            val ids=buildList {
                repeat(3) {
                    c.importSubmission(source,listOf(0),listOf(template.document.pages.single().id))
                    val collection=c.repositories.submissions.observeDrafts(saved.projectId).first().single()
                    c.repositories.imageProcessing.keepAllWarnings(PageProcessingOwner(saved.projectId,PageProcessingOwnerKind.SUBMISSION_DRAFT,collection.id,collection.editRevision),
                        collection.pages.associate {it.image.id to PageProcessingFingerprint.of(it.image)},"Anonymous paging fixture")
                    add(c.repositories.submissions.submitSubmission(collection.id,collection.editRevision,SubmissionConfirmation(duplicatePageSourcesConfirmed=true)).submissionId)
                }
            }
            c.refresh()
            assertTrue(c.state.value.collectionLocked)
            assertTrue(c.state.value.submissions.isEmpty())
            c.navigate(WorkspaceSection.REVIEW).join()
            c.updateProjectUi(saved.projectId) {it.copy(reviewFilter="ALL",reviewPageSize=1)}
            assertEquals(ids.first(),page(c,0,3).items.single().submissionId)
            c.updateProjectUi(saved.projectId) {it.copy(reviewSubmissionId=ids.first(),reviewWorkspace=true)}
            c.changeReviewPage(2)
            assertEquals(ids.last(),page(c,2,3).items.single().submissionId)
            c.refresh()
            assertEquals(ids.first(),c.ui.value.projects.getValue(saved.projectId).reviewSubmissionId)
            c.updateProjectUi(saved.projectId) {it.copy(reviewWorkspace=false)}
            c.changeReviewPage(1)
            page(c,1,3)
            assertNull(c.ui.value.projects.getValue(saved.projectId).reviewSubmissionId)
            c.updateProjectUi(saved.projectId) {it.copy(reviewSearch="3")}
            val searched=page(c,0,3)
            assertEquals(1,searched.totalMatched)
            assertEquals(ids.last(),searched.items.single().submissionId)
        }
    }
}
