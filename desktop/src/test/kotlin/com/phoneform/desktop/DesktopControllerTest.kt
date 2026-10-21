package com.phoneform.desktop

import com.phoneform.ai.core.model.*
import com.phoneform.desktop.security.SecureWorkspace
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopControllerTest {
    @get:Rule val folder = TemporaryFolder()
    private fun source(): java.io.File = folder.newFile("source-${newId()}.png").also { file ->
        val image = BufferedImage(400,600,BufferedImage.TYPE_INT_RGB)
        image.createGraphics().let { g -> try {
            g.color=Color.WHITE;g.fillRect(0,0,400,600);g.color=Color.BLACK;g.drawRect(20,20,360,560)
            g.color=Color.RED;g.fillRect(10,10,30,30)
        } finally {g.dispose()} }
        check(ImageIO.write(image,"png",file))
    }
    private suspend fun project(controller: Controller): String {
        controller.createProject("Synthetic controller",null,QuestionnaireLanguage.ENGLISH,NumberingStrategy.GENERATED,OutputHeaderLanguage.ENGLISH)
        return controller.repositories.projects.observeProjects().first().single().id
    }
    private fun controller(): Controller = Controller(SecureWorkspace.open(folder.newFolder("workspace-${newId()}").toPath()),Path.of("../runtime/models"),startWorkers=false)

    @Test fun geometryEditsComposeOriginalTransformAndRecoverMissingEncryptedDerivatives() = runBlocking {
        controller().use { c ->
            project(c);c.importTemplate(source(),listOf(0))
            val initial=c.templateDraft().pages.single()
            val original=c.workspace.assets.readBytes(initial.asset.relativePath)
            val region=NormalizedRect(.1f,.1f,.9f,.4f)
            val draft=c.templateDraft()
            c.saveTemplate(draft.copy(questions=listOf(QuestionDefinition("q",initial.id,0,"1","Question",QuestionType.TEXT,region,region))))
            c.rotateTemplatePage(initial.id)
            val rotated=c.templateDraft()
            assertTrue(rotated.questions.isEmpty());assertTrue(rotated.matrices.isEmpty())
            assertEquals(600,rotated.pages.single().width);assertEquals(400,rotated.pages.single().height)
            assertEquals(listOf(0f,-1f,1f,1f,0f,0f,0f,0f,1f),rotated.pages.single().transform.sourceToPage)
            c.rectifyTemplatePage(initial.id,listOf(NormalizedPoint(.05f,.05f),NormalizedPoint(.95f,.05f),NormalizedPoint(.95f,.95f),NormalizedPoint(.05f,.95f)))
            val corrected=c.templateDraft().pages.single()
            assertTrue(corrected.revision>rotated.pages.single().revision)
            val rendered=c.workspace.assets.readBytes(corrected.renderPath)
            c.workspace.assets.delete(corrected.renderPath);c.workspace.assets.delete(corrected.thumbnailPath)
            val recovered=c.loadImage(corrected)
            assertEquals(corrected.width,recovered.width);assertEquals(corrected.height,recovered.height)
            assertArrayEquals(rendered,c.workspace.assets.readBytes(corrected.renderPath))
            assertTrue(c.workspace.assets.exists(corrected.thumbnailPath))
            assertArrayEquals(original,c.workspace.assets.readBytes(initial.asset.relativePath))
            assertFalse(ImageIO.getUseCache())
            try {c.loadImage(corrected,true);fail("Missing enhancement was silently substituted")}
            catch(expected:IllegalStateException){assertEquals("ENHANCEMENT_UNAVAILABLE",expected.message)}
        }
    }

    @Test fun workerProcessesCurrentEvidenceWithFixedMissingMiddleSlotAndHonorsPause() = runBlocking {
        controller().use { c ->
            val id=project(c);val file=source()
            repeat(3){c.importTemplate(file,listOf(0))}
            val draft=c.templateDraft()
            val saved=c.saveTemplate(draft.copy(pages=draft.pages.map{it.copy(noExtraction=true)}))
            c.repositories.imageProcessing.keepAllWarnings(PageProcessingOwner(id,PageProcessingOwnerKind.TEMPLATE_DRAFT,saved.id,saved.revision),
                saved.pages.associate{it.id to PageProcessingFingerprint.of(it)},"Synthetic pages reviewed")
            c.confirmTemplate()
            val template=c.repositories.templates.observeConfirmed(id).first()!!
            c.importSubmission(file,listOf(0),listOf(template.document.pages[0].id))
            c.importSubmission(file,listOf(0),listOf(template.document.pages[2].id))
            val submissionDraft=c.repositories.submissions.observeDrafts(id).first().single()
            c.repositories.imageProcessing.keepAllWarnings(PageProcessingOwner(id,PageProcessingOwnerKind.SUBMISSION_DRAFT,submissionDraft.id,submissionDraft.editRevision),
                submissionDraft.pages.associate{it.image.id to PageProcessingFingerprint.of(it.image)},"Synthetic source reviewed")
            val submitted=c.repositories.submissions.submitSubmission(submissionDraft.id,submissionDraft.editRevision,
                SubmissionConfirmation(allowMissingPages=true,duplicatePageSourcesConfirmed=true))
            assertEquals(listOf(template.document.pages[1].id),submitted.missingTemplatePageIds)
            c.togglePaused();assertFalse(c.runWorkerPass());c.togglePaused()
            var passes=0;while(c.runWorkerPass()){check(++passes<30){"Worker did not drain durable jobs"}}
            assertEquals(PreparationJobStatus.READY,c.repositories.submissions.observeJobs(id).first().single().status)
            val recognized=c.repositories.intelligence.observeRecognitionJobs(id).first().single()
            assertEquals(RecognitionJobStatus.READY,recognized.status)
            assertEquals(setOf(template.document.pages[0].id,template.document.pages[2].id),recognized.completedPageIds.toSet())
            assertTrue(recognized.observations.isEmpty())
        }
    }
}
