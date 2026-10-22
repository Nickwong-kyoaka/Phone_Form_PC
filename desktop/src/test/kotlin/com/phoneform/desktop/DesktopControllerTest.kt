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

    @Test fun scopedPreviewIgnoresOtherIncompleteQuestionsWithoutPublishingOrWritingAnswers() = runBlocking {
        controller().use { c ->
            val id=project(c)
            c.importTemplate(source(),listOf(0))
            val draft=c.templateDraft()
            val page=draft.pages.single()
            val boxRegions=listOf(NormalizedRect(.15f,.20f,.25f,.30f),NormalizedRect(.40f,.20f,.50f,.30f))
            val circleRegions=listOf(NormalizedRect(.15f,.55f,.30f,.70f),NormalizedRect(.45f,.55f,.60f,.70f))
            val questions=listOf(
                QuestionDefinition("checkbox",page.id,0,"1","Anonymous checkbox",QuestionType.SINGLE_CHOICE,
                    NormalizedRect(.10f,.15f,.60f,.35f),options=listOf(
                        OptionDefinition("box-a","A","A",boxRegions[0]),OptionDefinition("box-b","B","B",boxRegions[1]))),
                QuestionDefinition("circle",page.id,1,"2","Anonymous printed number",QuestionType.SINGLE_CHOICE,
                    NormalizedRect(.10f,.50f,.70f,.75f),options=listOf(
                        OptionDefinition("circle-zero","0","0",circleRegions[0]),OptionDefinition("circle-one","1","1",circleRegions[1]))))
            // Confirm the structure with manual settings, then save an intentionally incomplete calibration draft.
            val saved=c.saveTemplate(draft.copy(questions=questions))
            c.repositories.imageProcessing.keepAllWarnings(PageProcessingOwner(id,PageProcessingOwnerKind.TEMPLATE_DRAFT,saved.id,saved.revision),
                mapOf(page.id to PageProcessingFingerprint.of(page)),"Anonymous source reviewed")
            c.confirmTemplate();c.refresh()
            val template=c.repositories.templates.observeConfirmed(id).first()!!
            val published=c.repositories.intelligence.getRecognitionProfile(template.id)!!
            assertTrue(published.settings.none { it.enabled })
            val calibration=c.loadRecognitionDraft()
            val configured=c.saveRecognitionDraft(calibration.copy(settings=listOf(
                QuestionRecognitionSettings("checkbox",true,MarkStyle.CHECKBOX_TICK_CROSS,listOf(
                    OptionMarkRegion("box-a",boxRegions[0]),OptionMarkRegion("box-b",boxRegions[1]))),
                QuestionRecognitionSettings("circle",true,MarkStyle.CIRCLED_PRINTED_NUMBER,listOf(
                    OptionMarkRegion("circle-zero",circleRegions[0],NormalizedRect(.20f,.60f,.25f,.65f),false),
                    OptionMarkRegion("circle-one",circleRegions[1],NormalizedRect(.50f,.60f,.55f,.65f),false))))))
            assertTrue(RecognitionBindings.validate(template.document,configured.settings).any { it.startsWith("CORE_CONFIRMATION_REQUIRED:") })
            fun recognitionRecordCounts():Map<String,Int> = c.workspace.database.read { connection ->
                connection.prepareStatement("SELECT kind,count(*) FROM pc_records WHERE project_id=? AND (kind LIKE 'recognition%' OR kind IN ('answers','confirmation','export')) GROUP BY kind").use { query ->
                    query.setString(1,id)
                    query.executeQuery().use { rows -> buildMap { while(rows.next())put(rows.getString(1),rows.getInt(2)) } }
                }
            }
            val before=recognitionRecordCounts()
            val preview=c.previewRecognition(configured,page.id,setOf("checkbox"))
            assertEquals(page.id,preview.alignment.templatePageId)
            assertEquals(listOf("question:checkbox"),preview.observations.map { it.targetKey })
            val observation=preview.observations.single()
            assertEquals("preview:${configured.id}",observation.profileId)
            assertEquals(page.id,observation.imageId)
            assertEquals(Controller.hash(c.workspace.assets.readBytes(page.renderPath)),observation.sourceSha256)
            assertEquals(PageProcessingFingerprint.of(page),observation.processingFingerprint)
            assertEquals(RecognitionProvenance.VERSIONED,observation.provenance)
            assertEquals(RecognitionBindings.evidenceHash(observation),observation.evidenceHash)
            assertNull(observation.candidate?.candidateRef)
            try { c.previewRecognition(configured,page.id);fail("Whole-page preview accepted an unconfirmed printed core") }
            catch(expected:IllegalArgumentException) { assertEquals("RECOGNITION_SETTINGS_INVALID",expected.message) }
            assertEquals(before,recognitionRecordCounts())
            assertEquals(published,c.repositories.intelligence.getRecognitionProfile(template.id))
            assertEquals(configured,c.repositories.intelligence.beginRecognitionProfileDraft(template.id))
            assertTrue(c.repositories.intelligence.observeRecognitionJobs(id).first().isEmpty())
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
