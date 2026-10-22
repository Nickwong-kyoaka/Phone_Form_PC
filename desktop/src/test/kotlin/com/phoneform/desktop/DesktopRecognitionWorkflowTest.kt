package com.phoneform.desktop

import com.phoneform.ai.core.data.*
import com.phoneform.ai.core.model.*
import com.phoneform.desktop.data.DesktopRepositories
import com.phoneform.desktop.security.SecureWorkspace
import com.phoneform.desktop.vision.DesktopVision
import com.phoneform.desktop.vision.MarkDetectors
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Anonymous end-to-end source binding, calibration replacement, review and actual workbook bytes. */
class DesktopRecognitionWorkflowTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun versionedImageRecognitionRerunPreservesAcceptedSourceAndExportsThreeLanguages() = runBlocking {
        val retained = System.getenv("PHONEFORM_PC_R4_QA_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it).toAbsolutePath().normalize() }
        val root = retained ?: temporary.newFolder("recognition-workspace").toPath()
        if (retained != null) { require(!Files.exists(root)); Files.createDirectories(root) }
        var workspace = SecureWorkspace.open(root)
        try {
            var db = DesktopRepositories(workspace)
            val projectId = newId(); val now = System.currentTimeMillis()
            db.projects.saveProject(ProjectDetails(ProjectConfig(projectId,"Anonymous recognition / 匿名辨識",2,
                QuestionnaireLanguage.ENGLISH,NumberingStrategy.PAPER_ID,now,now),OutputProfile(projectId,language=OutputHeaderLanguage.ENGLISH),emptyList()))
            val templateImage = fixture(false); val filledImage = fixture(true)
            val templatePage = ProjectOperationGate.shared.withLease(projectId) { store(workspace,templateImage,"template") }
            val filledPage = ProjectOperationGate.shared.withLease(projectId) { store(workspace,filledImage,"filled") }
            // Read the complete printed border and surrounding ink, not a crop through its stroke.
            val tightRegions = listOf(NormalizedRect(.10f,.70f,.17f,.75f),NormalizedRect(.30f,.70f,.37f,.75f))
            val regions = listOf(NormalizedRect(.09f,.69f,.18f,.76f),NormalizedRect(.29f,.69f,.38f,.76f))
            val settings = QuestionRecognitionSettings("choice",true,MarkStyle.CHECKBOX_TICK_CROSS,
                listOf(OptionMarkRegion("a",regions[0]),OptionMarkRegion("b",regions[1])))
            val circleRegions=listOf(NormalizedRect(.52f,.59f,.60f,.65f),NormalizedRect(.62f,.59f,.70f,.65f))
            val circleCores=listOf(NormalizedRect(.548f,.606f,.572f,.634f),NormalizedRect(.648f,.606f,.672f,.634f))
            val questions = listOf(
                QuestionDefinition("choice",templatePage.id,0,"1","Select a box",QuestionType.SINGLE_CHOICE,
                    NormalizedRect(.05f,.64f,.8f,.78f),options=listOf(OptionDefinition("a","A","0",regions[0]),OptionDefinition("b","B","01",regions[1])),recognition=settings),
                QuestionDefinition("text",templatePage.id,1,"2","Exact text",QuestionType.TEXT,
                    NormalizedRect(.05f,.80f,.8f,.9f),NormalizedRect(.1f,.84f,.7f,.89f)),
                QuestionDefinition("circle",templatePage.id,2,"3","Circled number",QuestionType.SINGLE_CHOICE,
                    NormalizedRect(.48f,.57f,.76f,.67f),options=listOf(OptionDefinition("zero","0","0",circleRegions[0]),OptionDefinition("one","1","1",circleRegions[1])),
                    recognition=QuestionRecognitionSettings("circle",true,MarkStyle.CIRCLED_PRINTED_NUMBER,
                        listOf(OptionMarkRegion("zero",circleRegions[0],circleCores[0],true),OptionMarkRegion("one",circleRegions[1],circleCores[1],true)))))
            val initial = db.templates.beginDraft(projectId)
            val draft = db.templates.saveDraft(initial.copy(pages=listOf(templatePage),questions=questions))
            db.imageProcessing.keepAllWarnings(PageProcessingOwner(projectId,PageProcessingOwnerKind.TEMPLATE_DRAFT,draft.id,draft.revision),
                mapOf(templatePage.id to PageProcessingFingerprint.of(templatePage)),"Anonymous generated fixture")
            val template = db.templates.confirmDraft(projectId,draft.id,draft.revision)
            val profile = requireNotNull(db.intelligence.getRecognitionProfile(template.id))
            assertEquals(settings,profile.settings.first { it.questionId=="choice" })
            val opened = db.submissions.beginSubmission(projectId)
            val submission = db.submissions.saveDraft(opened.copy(respondentId="00001",pages=listOf(PageRevision(templatePage.id,filledPage))))
            db.imageProcessing.keepAllWarnings(PageProcessingOwner(projectId,PageProcessingOwnerKind.SUBMISSION_DRAFT,submission.id,submission.editRevision),
                mapOf(filledPage.id to PageProcessingFingerprint.of(filledPage)),"Anonymous generated fixture")
            val revision = db.submissions.submitSubmission(submission.id,submission.editRevision,SubmissionConfirmation(true,true))
            val preparation = requireNotNull(db.jobs.claimNext("recognition-e2e",true))
            assertTrue(db.jobs.checkpoint(preparation,revision.pages)); assertTrue(db.jobs.complete(preparation))
            val engine = DesktopVision(Path.of(System.getProperty("phoneform.model.dir","../runtime/models")))
            suspend fun runCurrent(): RecognitionJob {
                val job = requireNotNull(db.intelligence.claimRecognition("recognition-e2e",true))
                val pinned = requireNotNull(job.profileId?.let { db.intelligence.getRecognitionProfileById(it) })
                val result = engine.recognize(templatePage,templateImage,filledPage,filledImage,questions,QuestionnaireLanguage.ENGLISH,
                    pinned,job.id,Controller.hash(workspace.assets.readBytes(filledPage.renderPath)))
                assertTrue(result.observations.all { it.requiresReview })
                assertTrue(db.intelligence.checkpointAlignment(job,result.alignment))
                assertTrue(db.intelligence.checkpointRecognition(job,result.alignment,result.observations))
                assertTrue(db.intelligence.completeRecognition(job))
                return requireNotNull(db.intelligence.getReview(submission.submissionId)?.job)
            }
            val firstJob = runCurrent()
            tightRegions.forEachIndexed { index, region ->
                val cropped = MarkDetectors.inspect(filledImage,MarkStyle.CHECKBOX_TICK_CROSS,OptionMarkRegion("cropped-$index",region))
                assertEquals(OptionDecision.AMBIGUOUS,cropped.decision)
                assertEquals("CHECKBOX_CROP_TOUCHES_BORDER",cropped.reason)
            }
            val observed = firstJob.observations.single { it.targetKey=="question:choice" }
            val candidateDiagnostics = "alignments=" + firstJob.alignments.joinToString { "${it.status}:${it.reason}" } +
                "; observationReason=${observed.reason}; options=" + observed.optionObservations.joinToString { option ->
                    "${option.optionId}:${option.decision}:${option.reason}:${option.metrics.toSortedMap()}"
                }
            assertNotNull("The clear checkbox fixture must produce a real machine candidate; $candidateDiagnostics",observed.candidate)
            val candidate = requireNotNull(observed.candidate)
            assertEquals(AnswerState.VALUE,candidate.state)
            assertEquals(listOf("a"),candidate.optionIds)
            assertNotNull("Machine candidates must identify their actual job/profile evidence",candidate.candidateRef)
            val acceptedRef = requireNotNull(candidate.candidateRef)
            assertEquals(CandidateRef(firstJob.id,profile.id,observed.targetKey,observed.evidenceHash),acceptedRef)
            assertEquals(RecognitionBindings.evidenceHash(observed),acceptedRef.evidenceHash)
            assertEquals(Controller.hash(workspace.assets.readBytes(filledPage.renderPath)),observed.sourceSha256)
            val answer = candidate.copy(source=AnswerSource.MACHINE_ACCEPTED)
            val firstAnswers = db.intelligence.saveAnswer(submission.submissionId,revision.id,0,answer,"accept-first")
            assertTrue(firstAnswers.answers.single { it.targetKey==answer.targetKey }.humanEdited)
            assertEquals(AnswerSource.MACHINE_ACCEPTED,firstAnswers.answers.single { it.targetKey==answer.targetKey }.source)
            assertEquals(acceptedRef,firstAnswers.answers.single { it.targetKey==answer.targetKey }.candidateRef)
            val circleObservation = firstJob.observations.single { it.targetKey=="question:circle" }
            assertNotNull("The confirmed printed core must produce a circle candidate: ${circleObservation.reason}; ${circleObservation.optionObservations}",circleObservation.candidate)
            val circleCandidate = requireNotNull(circleObservation.candidate)
            assertEquals(AnswerState.VALUE,circleCandidate.state)
            assertEquals(listOf("zero"),circleCandidate.optionIds)
            val circleRef = requireNotNull(circleCandidate.candidateRef)
            assertEquals(CandidateRef(firstJob.id,profile.id,circleObservation.targetKey,circleObservation.evidenceHash),circleRef)
            val acceptedAnswers = db.intelligence.saveAnswer(submission.submissionId,revision.id,firstAnswers.version,
                circleCandidate.copy(source=AnswerSource.MACHINE_ACCEPTED),"accept-circle")
            val acceptedRefs = listOf(acceptedRef,circleRef).toSet()
            val calibration = db.intelligence.beginRecognitionProfileDraft(template.id)
            val edited = db.intelligence.saveRecognitionProfileDraft(calibration.copy(settings=calibration.settings.map { it.copy(enabled=false) }))
            val secondProfile = db.intelligence.confirmRecognitionProfileDraft(edited.id,edited.revision,edited.baseProfileId,"publish-second")
            assertNotEquals(profile.id,secondProfile.id)
            val rerun = db.intelligence.rerunRecognition(submission.submissionId,revision.id,secondProfile.id,firstJob.id,"rerun-second")
            assertEquals(rerun.id,db.intelligence.rerunRecognition(submission.submissionId,revision.id,secondProfile.id,firstJob.id,"rerun-second").id)
            db.intelligence.pauseRecognition(firstJob,"LATE_OLD_TASK")
            assertEquals(rerun.id,db.intelligence.getReview(submission.submissionId)!!.job!!.id)
            val secondJob = runCurrent()
            assertEquals(AlignmentStatus.NOT_REQUIRED,secondJob.alignments.single().status)
            assertEquals(acceptedAnswers.answers,db.intelligence.getReview(submission.submissionId)!!.answers!!.answers)
            val withText = db.intelligence.saveAnswer(submission.submissionId,revision.id,acceptedAnswers.version,
                AnswerValue("question:text",AnswerState.VALUE,text="=1+1"),"save-text")
            db.intelligence.confirmReview(submission.submissionId,revision.id,withText.version,"confirm")
            val snapshotIds = mutableListOf<String>()
            for (language in OutputHeaderLanguage.entries) {
                val project = requireNotNull(db.projects.getProject(projectId))
                db.projects.saveOutputConfiguration(project.copy(output=project.output.copy(language=language)),emptyList(),project.output.version)
                val snapshot = db.intelligence.createExportSnapshot(projectId,false,"snapshot:${language.name}")
                assertEquals(1,snapshot.records.size); assertEquals(4,snapshot.sheets.size)
                assertEquals(acceptedRefs,snapshot.records.single().candidateRefs.toSet())
                snapshotIds += snapshot.id
                val bytes = ByteArrayOutputStream(); val counts = XlsxWriter.write(bytes,snapshot.toXlsxSheets())
                XlsxWriter.validate(ByteArrayInputStream(bytes.toByteArray()),counts)
                if (retained != null) Files.write(root.resolve("review-${language.name}.xlsx"),bytes.toByteArray())
            }
            if (retained != null) {
                check(ImageIO.write(templateImage,"png",root.resolve("template-fixture.png").toFile()))
                check(ImageIO.write(filledImage,"png",root.resolve("filled-fixture.png").toFile()))
            }
            templateImage.flush(); filledImage.flush()
            workspace.close(); workspace=SecureWorkspace.open(root); db=DesktopRepositories(workspace)
            assertNotNull(db.intelligence.getReview(submission.submissionId)!!.confirmation)
            assertEquals(2,db.intelligence.getRecognitionProfile(template.id)!!.version)
            val reopenedChoice = db.intelligence.getReview(submission.submissionId)!!.answers!!.answers.single { it.targetKey==answer.targetKey }
            assertEquals(AnswerSource.MACHINE_ACCEPTED,reopenedChoice.source)
            assertEquals(acceptedRef,reopenedChoice.candidateRef)
            val reopenedCircle = db.intelligence.getReview(submission.submissionId)!!.answers!!.answers.single { it.targetKey=="question:circle" }
            assertEquals(AnswerSource.MACHINE_ACCEPTED,reopenedCircle.source)
            assertEquals(circleRef,reopenedCircle.candidateRef)
            snapshotIds.forEach { id -> assertEquals(acceptedRefs,db.intelligence.getExportSnapshot(id)!!.records.single().candidateRefs.toSet()) }
            assertEquals(1,db.projects.observeProjectSummaries().first().single().confirmedCount)
            if (retained != null) println("ANONYMOUS_QA_WORKSPACE_READY")
        } finally { workspace.close() }
    }
    private fun store(workspace: SecureWorkspace,image:BufferedImage,name:String):SamplePage {
        val bytes=ByteArrayOutputStream();check(ImageIO.write(image,"png",bytes))
        val info=workspace.assets.put("anonymous/$name.png",ByteArrayInputStream(bytes.toByteArray()))
        return SamplePage(newId(),SampleAsset(newId(),SampleKind.IMAGE,info.relativePath,info.sha256,info.byteCount),0,0,
            info.relativePath,info.relativePath,image.width,image.height)
    }
    private fun fixture(selected:Boolean)=BufferedImage(1000,1400,BufferedImage.TYPE_INT_RGB).apply {
        val g=createGraphics()
        try {
            g.color=Color.WHITE;g.fillRect(0,0,width,height);g.color=Color.BLACK;g.font=Font("SansSerif",Font.PLAIN,22)
            repeat(12){i->g.drawString("${i+1}. Anonymous printed anchor ${i*139+31}",50+i%3*19,70+i*64)
                g.drawString("Alpha ${i*17}  Beta ${i*23}",520,100+i*64)}
            g.stroke=BasicStroke(2f);g.drawRect(30,25,930,1310);g.drawRect(100,980,70,70);g.drawRect(300,980,70,70)
            if(selected){g.stroke=BasicStroke(4f);g.drawLine(112,1010,132,1035);g.drawLine(132,1035,160,993)}
            g.font=Font("SansSerif",Font.PLAIN,28);g.drawString("0",550,880);g.drawString("1",650,880)
            if(selected){g.stroke=BasicStroke(3f);g.drawOval(535,840,50,60)}
            g.drawString("2. Exact text",50,1160);g.drawRect(100,1176,600,70)
        }finally{g.dispose()}
    }
}
