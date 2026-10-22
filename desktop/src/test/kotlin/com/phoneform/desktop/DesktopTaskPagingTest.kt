package com.phoneform.desktop

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.phoneform.ai.core.model.*
import com.phoneform.desktop.data.DesktopRepositories
import com.phoneform.desktop.data.DesktopUiPreferencesStore
import com.phoneform.desktop.security.SecureWorkspace
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

class DesktopTaskPagingTest {
    @get:Rule val temporary=TemporaryFolder()
    private val projectId="anonymous-task-ui"
    private val projectName="Anonymous task UI / 匿名任務"
    private val gson=Gson()
    private fun open(root:Path)=Controller(SecureWorkspace.open(root),Path.of("../runtime/models"),startWorkers=false)
    private suspend fun await(condition:suspend()->Boolean)=withTimeout(20_000){while(!condition())delay(10)}
    private suspend fun ready(controller:Controller) {controller.select(projectId).join();await {controller.state.value.projects.any {it.project.id==projectId}}}
    private fun submissionId(number:Int)="task-ui-submission-$number"
    private fun recognitionId(number:Int)="task-ui-recognition-$number"
    private fun revisionId(number:Int)="task-ui-revision-$number"

    @Test fun taskPreferencesSupportOlderRecordsAndRemainEncryptedAcrossControllerRestart()=runBlocking {
        val old=DesktopUiCodec.decode("""{"theme":"DARK","projectSearch":"old search"}""")
        assertEquals(TaskPageQuery(),old.taskQuery());assertEquals(ThemeMode.DARK,old.theme)
        val invalid=DesktopUiCodec.decode("""{"taskFilter":"FUTURE","taskPageIndex":-4,"taskPageSize":1000}""")
        assertEquals(TaskPageQuery(),invalid.taskQuery())
        val root=temporary.newFolder("task-preferences").toPath()
        SecureWorkspace.open(root).use {DesktopUiPreferencesStore(it.database).save("""{"theme":"DARK"}""")}
        open(root).use {c->
            c.select(null).join();assertEquals(TaskPageQuery(),c.ui.value.taskQuery())
            c.updateUi {it.copy(taskSearch="private-task-search-Ångström",taskFilter="FAILED",taskPageSize=25)}
            c.updateUi {it.copy(taskPageIndex=7)}
        }
        open(root).use {c->
            c.select(null).join()
            assertEquals(ThemeMode.DARK,c.ui.value.theme)
            assertEquals(TaskPageQuery("private-task-search-Ångström",TaskListFilter.FAILED,7,25),c.ui.value.taskQuery())
        }
        val bytes=Files.readAllBytes(root.resolve("phoneform.db"))
        assertFalse(String(bytes,Charsets.ISO_8859_1).contains("private-task-search"))
        assertFalse(String(bytes,Charsets.ISO_8859_1).startsWith("SQLite format 3"))
    }

    @Test fun controllerRetriesALatePageTaskAndRejectsAnExpiredHead()=runBlocking {
        val root=temporary.newFolder("task-actions").toPath();seedFixture(root)
        open(root).use {c->
            ready(c);await {c.tasks.value.size==200}
            assertFalse(c.tasks.value.any {it.id==recognitionId(1)})
            assertEquals("FAILED",c.repositories.getTaskSummary(recognitionId(1),BackgroundTaskPhase.RECOGNITION)!!.status)
            c.retryTask(recognitionId(1),BackgroundTaskPhase.RECOGNITION)
            await {c.repositories.getTaskSummary(recognitionId(1),BackgroundTaskPhase.RECOGNITION)?.status=="QUEUED" || c.state.value.error!=null}
            assertNull(c.state.value.error)
            await {!c.state.value.busy}
            val expired=recognitionId(2)
            val before=recordPayload(c,"recognition",expired)
            val next=JsonParser.parseString(before).asJsonObject.apply {addProperty("id","replacement-recognition");addProperty("status","FAILED")}
            c.workspace.database.transaction {connection->
                connection.prepareStatement("INSERT INTO pc_records(kind,id,project_id,payload) VALUES('recognition',?,?,?)").use {it.setString(1,"replacement-recognition");it.setString(2,projectId);it.setString(3,next.toString());it.executeUpdate()}
                connection.prepareStatement("UPDATE pc_records SET payload=? WHERE kind='recognition_head' AND id=?").use {it.setString(1,"""{"id":"replacement-recognition"}""");it.setString(2,revisionId(2));it.executeUpdate()}
            }
            assertNull(c.repositories.getTaskSummary(expired,BackgroundTaskPhase.RECOGNITION))
            c.retryTask(expired,BackgroundTaskPhase.RECOGNITION)
            await {c.state.value.error!=null && !c.state.value.busy}
            assertEquals("TASK_RETRY_UNAVAILABLE",c.state.value.error)
            assertEquals(before,recordPayload(c,"recognition",expired))
            assertEquals("FAILED",c.repositories.getTaskSummary("replacement-recognition",BackgroundTaskPhase.RECOGNITION)!!.status)
        }
    }

    @Test fun taskNavigationLocatesItsQuestionnaireAndPreservesInputWhenSavingFails()=runBlocking {
        val root=temporary.newFolder("task-navigation").toPath();seedFixture(root)
        open(root).use {c->
            ready(c)
            c.createProject("Navigation origin",null,QuestionnaireLanguage.ENGLISH,NumberingStrategy.GENERATED,OutputHeaderLanguage.ENGLISH)
            c.refresh();val origin=requireNotNull(c.ui.value.projectId)
            val before=c.ui.value
            c.workspace.database.transaction {connection->connection.createStatement().use {it.execute("CREATE TRIGGER reject_task_editor BEFORE INSERT ON pc_records WHEN NEW.kind='editor' BEGIN SELECT RAISE(ABORT,'TASK_EDITOR_SAVE_TEST'); END")}}
            try {
                c.queueEditorField("task-navigation-edit",origin,"answer","keep this exact anonymous input")
                try {c.openTaskQuestionnaire(recognitionId(260),BackgroundTaskPhase.RECOGNITION);fail("Navigation ignored the failed encrypted save")}
                catch(expected:Exception) {assertTrue(expected.message.orEmpty().contains("TASK_EDITOR_SAVE_TEST"))}
                assertEquals(before,c.ui.value);assertEquals(origin,c.state.value.project!!.project.id)
                assertEquals(EditorSaveState.FAILED,c.state.value.editorSaveState)
                assertEquals("keep this exact anonymous input",c.loadEditorField("task-navigation-edit","answer"))
            }finally {c.workspace.database.transaction {connection->connection.createStatement().use {it.execute("DROP TRIGGER reject_task_editor")}}}
            c.flushEdits();c.clearMessage()
            c.openTaskQuestionnaire(recognitionId(260),BackgroundTaskPhase.RECOGNITION);c.refresh()
            assertEquals(projectId,c.ui.value.projectId);assertEquals(WorkspaceSection.REVIEW,c.ui.value.section)
            assertEquals(submissionId(260),c.ui.value.projects.getValue(projectId).reviewSubmissionId)
            assertTrue(c.ui.value.projects.getValue(projectId).reviewWorkspace)
            val review=requireNotNull(c.repositories.intelligence.getReview(submissionId(260)))
            assertEquals("00260",review.submission.respondentId);assertNull(review.answers);assertEquals(3,review.targets.size)
            val answers=listOf(AnswerValue("question:number",AnswerState.VALUE,text="007"),
                AnswerValue("question:text",AnswerState.VALUE,text="anonymous"),
                AnswerValue("question:choice",AnswerState.VALUE,optionIds=listOf("a")))
            var version=0
            for((index,answer) in answers.withIndex())version=c.repositories.intelligence.saveAnswer(review.submission.id,review.revision.id,version,answer,"manual-task-answer-$index").version
            val saved=c.repositories.intelligence.getReview(review.submission.id)!!.answers!!.answers
            assertEquals(answers.map {it.copy(humanEdited=true)},saved)
            assertTrue(saved.all {it.source==AnswerSource.MANUAL && it.candidateRef==null})
        }
    }

    @Test fun draftImageTasksOpenTheExactNewOrPageRevisionCollectionInsteadOfReview()=runBlocking {
        val root=temporary.newFolder("draft-task-navigation").toPath();seedFixture(root)
        open(root).use {c->
            ready(c)
            val template=requireNotNull(c.state.value.template)
            val templatePage=template.document.pages.single()
            val blank=c.repositories.submissions.beginSubmission(projectId)
            val fresh=c.repositories.submissions.saveDraft(blank.copy(pages=listOf(PageRevision(templatePage.id,templatePage.copy(id="new-collection-image")))))
            assertNull(c.repositories.intelligence.getReviewSummary(fresh.submissionId))
            suspend fun imageJob(draft:SubmissionDraft)=c.repositories.imageProcessing.observeJobs().first().single {
                it.owner.kind==PageProcessingOwnerKind.SUBMISSION_DRAFT && it.owner.id==draft.id && it.owner.revision==draft.editRevision
            }
            val freshJob=imageJob(fresh)
            val freshSummary=requireNotNull(c.repositories.getTaskSummary(freshJob.id,BackgroundTaskPhase.IMAGE))
            assertEquals(fresh.id,freshSummary.collectionDraftId);assertEquals("new-collection-image",freshSummary.pageId)
            c.openTaskQuestionnaire(freshJob.id,BackgroundTaskPhase.IMAGE);c.refresh()
            assertEquals(WorkspaceSection.COLLECTION,c.ui.value.section)
            assertEquals(fresh.id,c.ui.value.projects.getValue(projectId).collectionDraftId)
            assertEquals(templatePage.id,c.ui.value.projects.getValue(projectId).collectionPageId)

            val patch=c.repositories.submissions.beginRevision(submissionId(260))
            val patchJob=imageJob(patch)
            assertNotNull(patch.baseRevisionId)
            c.openTaskQuestionnaire(patchJob.id,BackgroundTaskPhase.IMAGE);c.refresh()
            assertEquals(WorkspaceSection.COLLECTION,c.ui.value.section)
            assertEquals(patch.id,c.ui.value.projects.getValue(projectId).collectionDraftId)
            assertEquals(templatePage.id,c.ui.value.projects.getValue(projectId).collectionPageId)
            val before=c.ui.value
            c.repositories.submissions.saveDraft(patch.copy(respondentId="anonymous-revision-change"))
            assertNull(c.repositories.getTaskSummary(patchJob.id,BackgroundTaskPhase.IMAGE))
            try {c.openTaskQuestionnaire(patchJob.id,BackgroundTaskPhase.IMAGE);fail("An old draft task was allowed to navigate")}
            catch(expected:IllegalStateException) {assertEquals("TASK_RETRY_UNAVAILABLE",expected.message)}
            assertEquals(before,c.ui.value)
        }
    }

    /** Opt in only for native UI inspection; ordinary test runs retain no questionnaire artifacts. */
    @Test fun anonymousTaskSmokeWorkspaceIsOptInAndStartsWithUnhandledAnswers()=runBlocking {
        val requested=System.getenv("PHONEFORM_PC_UI_SMOKE_DIR")?.takeIf {it.isNotBlank()}
        val root=if(requested==null)temporary.newFolder("anonymous-task-smoke").toPath() else Path.of(requested).toAbsolutePath().normalize().also {
            require(!Files.exists(it)) {"UI smoke destination must be a new, non-existing directory"}
            Files.createDirectories(it)
        }
        seedFixture(root)
        SecureWorkspace.open(root).use {workspace->
            val db=DesktopRepositories(workspace)
            val page=db.queryTaskPage(TaskPageQuery(filter=TaskListFilter.ALL))
            assertEquals(520,page.total);assertEquals(230,page.statusCounts["FAILED"]);assertEquals(289,page.statusCounts["READY"]);assertEquals(1,page.statusCounts["BLOCKED"])
            val review=db.intelligence.getReview(submissionId(260))!!
            assertEquals("00260",review.submission.respondentId);assertEquals(3,review.targets.size);assertNull(review.answers);assertNull(review.confirmation)
            assertNotNull(db.intelligence.getRecognitionProfile(review.template.id))
            assertTrue(workspace.assets.exists(review.revision.pages.single().image.renderPath))
        }
        if(requested!=null)println("ANONYMOUS_TASK_UI_SMOKE_READY:$root")
    }

    private fun recordPayload(c:Controller,kind:String,id:String):String=c.workspace.database.read {connection->
        connection.prepareStatement("SELECT payload FROM pc_records WHERE kind=? AND id=?").use {it.setString(1,kind);it.setString(2,id);it.executeQuery().use {rows->check(rows.next());rows.getString(1)}}
    }

    /** Builds metadata in one transaction. This is neither a collection nor an OCR benchmark. */
    private suspend fun seedFixture(root:Path) {
        SecureWorkspace.open(root).use {workspace->
            val db=DesktopRepositories(workspace)
            db.projects.saveProject(ProjectDetails(ProjectConfig(projectId,projectName,260,QuestionnaireLanguage.ENGLISH,NumberingStrategy.PAPER_ID,1,1),OutputProfile(projectId,language=OutputHeaderLanguage.ENGLISH),emptyList()))
            ImageIO.setUseCache(false)
            val image=BufferedImage(1000,1400,BufferedImage.TYPE_INT_RGB)
            val encoded=ByteArrayOutputStream()
            image.createGraphics().let {g->try {
                g.color=Color.WHITE;g.fillRect(0,0,image.width,image.height);g.color=Color.BLACK;g.font=Font("SansSerif",Font.PLAIN,30)
                g.drawString("Anonymous UI inspection / no personal data",70,100)
                g.drawString("1. Number: 007",80,320);g.drawString("2. Exact text: anonymous",80,650)
                g.drawString("3. Select A",80,980);g.stroke=BasicStroke(3f);g.drawRect(100,1030,55,55);g.drawString("A",180,1072);g.drawRect(390,1030,55,55);g.drawString("B",470,1072)
                g.drawLine(110,1050,125,1072);g.drawLine(125,1072,147,1040)
            }finally {g.dispose()}}
            check(ImageIO.write(image,"png",encoded));image.flush()
            val asset=workspace.assets.put("anonymous/task-ui-page.png",ByteArrayInputStream(encoded.toByteArray()))
            val page=SamplePage("task-ui-page",SampleAsset("task-ui-asset",SampleKind.IMAGE,asset.relativePath,asset.sha256,asset.byteCount),0,0,asset.relativePath,asset.relativePath,1000,1400)
            val questions=listOf(
                QuestionDefinition("number",page.id,0,"1","Number",QuestionType.NUMBER,NormalizedRect(.06f,.15f,.9f,.30f),NormalizedRect(.25f,.19f,.65f,.25f)),
                QuestionDefinition("text",page.id,1,"2","Exact text",QuestionType.TEXT,NormalizedRect(.06f,.38f,.9f,.53f),NormalizedRect(.28f,.42f,.8f,.48f)),
                QuestionDefinition("choice",page.id,2,"3","Select A",QuestionType.SINGLE_CHOICE,NormalizedRect(.06f,.64f,.9f,.80f),options=listOf(
                    OptionDefinition("a","A","A",NormalizedRect(.09f,.73f,.17f,.79f)),OptionDefinition("b","B","B",NormalizedRect(.38f,.73f,.46f,.79f)))))
            val initial=db.templates.beginDraft(projectId)
            val draft=db.templates.saveDraft(initial.copy(pages=listOf(page),questions=questions))
            db.imageProcessing.keepAllWarnings(PageProcessingOwner(projectId,PageProcessingOwnerKind.TEMPLATE_DRAFT,draft.id,draft.revision),mapOf(page.id to PageProcessingFingerprint.of(page)),"Anonymous UI inspection fixture")
            val template=db.templates.confirmDraft(projectId,draft.id,draft.revision)
            val profile=requireNotNull(db.intelligence.getRecognitionProfile(template.id))
            assertTrue(profile.settings.none {it.enabled})
            workspace.database.transaction {connection->
                connection.prepareStatement("INSERT INTO pc_records(kind,id,project_id,payload) VALUES(?,?,?,?)").use {statement->
                    fun put(kind:String,id:String,value:Any) {
                        val tree=gson.toJsonTree(value)
                        if(value is RecognitionJob)tree.asJsonObject.addProperty("recordVersion",2)
                        statement.setString(1,kind);statement.setString(2,id);statement.setString(3,projectId);statement.setString(4,tree.toString());statement.addBatch()
                    }
                    for(number in 1..260) {
                        val paper="%05d".format(java.util.Locale.ROOT,number)
                        val submission=Submission(submissionId(number),projectId,number,template.id,NumberingStrategy.PAPER_ID,number.toLong(),revisionId(number),paper)
                        val revision=SubmissionRevision(revisionId(number),submission.id,projectId,template.id,1,"anonymous-source-$number",1,paper,listOf(PageRevision(page.id,page)),emptyList(),SubmissionConfirmation(),number.toLong())
                        val preparation=ProcessingJob("task-ui-preparation-$number",projectId,submission.id,revision.id,PreparationJobStatus.READY,stage=PreparationStage.READY,preparedPages=revision.pages,createdAtEpochMillis=number.toLong())
                        val status=when {number<=230->RecognitionJobStatus.FAILED;number==260->RecognitionJobStatus.BLOCKED;else->RecognitionJobStatus.READY}
                        val job=RecognitionJob(recognitionId(number),projectId,submission.id,revision.id,status,
                            completedPageIds=if(status==RecognitionJobStatus.READY)listOf(page.id)else emptyList(),
                            error=when(status){RecognitionJobStatus.FAILED->"ANONYMOUS_UI_FAILURE";RecognitionJobStatus.BLOCKED->"RECOGNITION_PROFILE_REQUIRED";else->null},
                            createdAtEpochMillis=number.toLong(),profileId=profile.id,engineVersion=profile.engineVersion,inputFingerprint=RecognitionBindings.jobFingerprint(revision,profile),provenance=RecognitionProvenance.VERSIONED)
                        put("submission",submission.id,submission);put("revision",revision.id,revision)
                        put("preparation",preparation.id,preparation);put("preparation_head",revision.id,mapOf("id" to preparation.id))
                        put("recognition",job.id,job);put("recognition_head",revision.id,mapOf("id" to job.id))
                    }
                    statement.executeBatch()
                }
            }
            DesktopUiPreferencesStore(workspace.database).save(DesktopUiCodec.encode(DesktopUiState(projectId=projectId,section=WorkspaceSection.REVIEW,projects=mapOf(projectId to ProjectUiState(reviewFilter="ALL")),taskFilter="ALL")))
        }
    }
}
