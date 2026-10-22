package com.phoneform.desktop.data

import com.phoneform.ai.core.model.*
import com.phoneform.desktop.security.KeyProtector
import com.phoneform.desktop.security.SecureWorkspace
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ListQueriesTest {
    @get:Rule val folder=TemporaryFolder()
    // Reversible test protector only. Production still uses CurrentUser DPAPI.
    private val protector=object:KeyProtector {
        override fun protect(plain:ByteArray,purpose:String)=plain.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        override fun unprotect(wrapped:ByteArray,purpose:String)=protect(wrapped,purpose)
    }
    private lateinit var workspace:SecureWorkspace
    private lateinit var db:DesktopRepositories
    private val projectId="anonymous-query-project"
    private lateinit var template:FormSchemaVersion
    @Before fun open() {
        workspace=SecureWorkspace.open(folder.newFolder("workspace").toPath(),protector);db=DesktopRepositories(workspace)
        val config=ProjectConfig(projectId,"Anonymous query",1000,QuestionnaireLanguage.ENGLISH,NumberingStrategy.PAPER_ID,1,2,templateVersionId="template")
        template=FormSchemaVersion("template",projectId,1,"draft",1,TemplateDraft("draft",projectId))
        db.write { put("project",projectId,projectId,ProjectDetails(config,OutputProfile(projectId,language=OutputHeaderLanguage.ENGLISH),emptyList()));put("template",template.id,projectId,template);installInitialProfile(template) }
    }
    @After fun close() { workspace.close() }
    private fun seed(number:Int,paperId:String="%04d".format(number),missing:Boolean=false,recognition:RecognitionJobStatus=RecognitionJobStatus.READY):Submission {
        val submission=Submission("s$number",projectId,number,template.id,NumberingStrategy.PAPER_ID,number.toLong(),"r$number",paperId)
        val revision=SubmissionRevision("r$number",submission.id,projectId,template.id,1,"source$number",1,paperId,emptyList(),if(missing) listOf("page-missing") else emptyList(),SubmissionConfirmation(),number.toLong())
        val preparation=ProcessingJob("p$number",projectId,submission.id,revision.id,PreparationJobStatus.READY,createdAtEpochMillis=number.toLong())
        val job=RecognitionJob("n$number",projectId,submission.id,revision.id,recognition,observations=listOf(FieldObservation("question:q","page","image",1,requiresReview=true)),createdAtEpochMillis=number.toLong())
        db.write {put("submission",submission.id,projectId,submission);put("revision",revision.id,projectId,revision);put("preparation",preparation.id,projectId,preparation);put("recognition",job.id,projectId,job);put("recognition_head",revision.id,projectId,RecordReference(job.id))}
        return submission
    }
    private fun answer(number:Int,human:Boolean=true):AnswerSetRevision=AnswerSetRevision("a$number",projectId,"s$number","r$number",1,"answer$number",listOf(AnswerValue("question:q",AnswerState.VALUE,"001",humanEdited=human))).also { db.write {put("answers",it.id,projectId,it)} }
    private fun confirmation(number:Int,answer:AnswerSetRevision)=ReviewConfirmation("c$number","r$number",answer.id,"confirm$number").also {db.write{put("confirmation",it.id,projectId,it)}}
    private inline fun <reified T:Throwable> fails(block:()->Unit) {try {block();fail("Expected ${T::class.java.simpleName}")}catch(failure:Throwable){if(failure !is T)throw failure}}

    @Test fun pagesUseStableSqlOrderLiteralUnicodeSearchAndSameSnapshotCounts()=runBlocking {
        (23 downTo 1).forEach {seed(it,when(it){1->"00001";2->"Älpha Ελληνικά";3->"A%_B";else->"paper-$it"})}
        val first=db.intelligence.queryReviewPage(projectId,ReviewPageQuery(filter=ReviewListFilter.ALL,pageSize=10))
        assertEquals((1..10).toList(),first.items.map{it.number});assertEquals(23,first.totalSubmitted);assertEquals(23,first.totalMatched);assertEquals(23,first.pendingCount)
        val last=db.intelligence.queryReviewPage(projectId,first.query.copy(pageIndex=99))
        assertEquals(2,last.query.pageIndex);assertEquals(listOf(21,22,23),last.items.map{it.number})
        assertEquals("00001",db.intelligence.getReviewSummary("s1")!!.respondentId)
        for(search in listOf("älPHA","εΛΛΗΝΙΚΆ"))assertEquals(listOf(2),db.intelligence.queryReviewPage(projectId,ReviewPageQuery(search,ReviewListFilter.ALL)).items.map{it.number})
        assertEquals(listOf(3),db.intelligence.queryReviewPage(projectId,ReviewPageQuery("%_",ReviewListFilter.ALL)).items.map{it.number})
        assertEquals(listOf(1),db.intelligence.queryReviewPage(projectId,ReviewPageQuery("00001",ReviewListFilter.ALL)).items.map{it.number})
        assertEquals(23,db.intelligence.observeReviewSummaries(projectId).first().size)
        db.write {(21..23).forEach {number->val s=requireNotNull(get<Submission>("submission","s$number"));put("submission",s.id,projectId,s.copy(discarded=true))}}
        val afterDeletion=db.intelligence.queryReviewPage(projectId,last.query)
        assertEquals(1,afterDeletion.query.pageIndex);assertEquals((11..20).toList(),afterDeletion.items.map{it.number})
        fails<IllegalArgumentException>{runBlocking{db.intelligence.queryReviewPage(projectId,ReviewPageQuery(pageSize=101))}}
    }

    @Test fun confirmationNeedsRealCurrentRecordAndExceptionsRespectHumanEdits()=runBlocking {
        seed(1);seed(2,missing=true);seed(3);seed(4,recognition=RecognitionJobStatus.FAILED)
        answer(1);val a2=answer(2);confirmation(2,a2)
        val a3=answer(3,human=false)
        db.write {put("confirmation_answer",a3.id,projectId,RecordReference("absent-confirmation"))}
        assertFalse(db.intelligence.getReviewSummary("s1")!!.hasExceptions)
        assertTrue(db.intelligence.getReviewSummary("s2")!!.confirmed)
        assertFalse(db.intelligence.getReviewSummary("s2")!!.hasExceptions)
        assertFalse(db.intelligence.getReviewSummary("s3")!!.confirmed)
        assertTrue(db.intelligence.getReviewSummary("s3")!!.hasExceptions)
        val exceptions=db.intelligence.queryReviewPage(projectId,ReviewPageQuery(filter=ReviewListFilter.EXCEPTIONS))
        assertEquals(listOf(2,3,4),exceptions.items.map{it.number});assertEquals(3,exceptions.exceptionCount);assertEquals(1,exceptions.confirmedCount)
        val summary=db.projects.observeProjectSummaries().first().single()
        assertEquals(4,summary.submittedCount);assertEquals(1,summary.confirmedCount);assertEquals(1,summary.missingCount);assertEquals(2,summary.readyForReviewCount);assertEquals(1,summary.failedCount)
        // A confirmation for historical answers cannot confirm the new answer head.
        val newer=a2.copy(id="a2-new",version=2,operationId="answer2-new")
        db.write{put("answers",newer.id,projectId,newer)}
        assertFalse(db.intelligence.getReviewSummary("s2")!!.confirmed)
    }

    @Test fun neighborsCrossPageAndRerunTargetsKeepTheirRevisionAndHead()=runBlocking {
        (1..5).forEach{seed(it)};confirmation(3,answer(3))
        val query=ReviewPageQuery(filter=ReviewListFilter.PENDING,pageSize=2)
        val adjacent=db.intelligence.reviewNeighbors(projectId,"s4",query)
        assertEquals("s2",adjacent.previousSubmissionId);assertEquals("s5",adjacent.nextSubmissionId);assertEquals(3,adjacent.position);assertEquals(4,adjacent.totalMatched)
        assertNull(db.intelligence.reviewNeighbors(projectId,"s3",query).position)
        assertEquals("s2",db.intelligence.reviewNeighbors(projectId,"s3",query).previousSubmissionId)
        assertEquals("s4",db.intelligence.reviewNeighbors(projectId,"s3",query).nextSubmissionId)
        val target=db.intelligence.unconfirmedRerunTargets(projectId).first{it.submissionId=="s1"}
        assertEquals("r1",target.submissionRevisionId);assertEquals("n1",target.currentJobId)
        val profile=db.intelligence.getRecognitionProfile(template.id)!!
        val rerun=db.intelligence.rerunRecognition(target.submissionId,target.submissionRevisionId,profile.id,target.currentJobId,"first-rerun")
        assertNotEquals(target.currentJobId,rerun.id)
        fails<StaleRecognitionException>{runBlocking{db.intelligence.rerunRecognition(target.submissionId,target.submissionRevisionId,profile.id,target.currentJobId,"second-rerun")}}
        val fresh=db.intelligence.unconfirmedRerunTargets(projectId).first{it.submissionId=="s1"}
        confirmation(1,answer(1))
        fails<StaleRecognitionException>{runBlocking{db.intelligence.rerunRecognition(fresh.submissionId,fresh.submissionRevisionId,profile.id,fresh.currentJobId,"after-confirm")}}
        assertEquals(3,db.intelligence.reviewNeighbors(projectId,"absent",query).totalMatched)
        val justConfirmed=db.intelligence.reviewNeighbors(projectId,"s1",query)
        assertNull(justConfirmed.previousSubmissionId);assertEquals("s2",justConfirmed.nextSubmissionId);assertNull(justConfirmed.position)
    }

    @Test fun taskLimitKeepsFullCountsAndHidesStaleHeadsAndOwners()=runBlocking {
        (1..112).forEach{seed(it,recognition=if(it%2==0)RecognitionJobStatus.FAILED else RecognitionJobStatus.QUEUED)}
        db.write {put("recognition","old",projectId,RecognitionJob("old",projectId,"s1","r1",RecognitionJobStatus.RUNNING));put("preparation","old-prep",projectId,ProcessingJob("old-prep",projectId,"s1","old-revision",PreparationJobStatus.FAILED,createdAtEpochMillis=1))}
        val tasks=db.taskSummaries()
        assertEquals(200,tasks.items.size);assertEquals(224,tasks.total);assertEquals(mapOf("FAILED" to 56,"QUEUED" to 56,"READY" to 112),tasks.statusCounts)
        assertFalse(tasks.items.any{it.id=="old" || it.id=="old-prep"})
        assertEquals(7,db.taskSummaries(7).items.size);assertEquals(224,db.taskSummaries(7).total)
        db.write {remove("revision","r112")}
        assertEquals(222,db.taskSummaries().total)
        val page=SamplePage("page",SampleAsset("asset",SampleKind.IMAGE,"unused","0".repeat(64),1),0,0,"unused","unused",10,10)
        val draft=TemplateDraft("image-owner",projectId,revision=2,pages=listOf(page))
        val image=PageProcessingJob("image-job",PageProcessingOwner(projectId,PageProcessingOwnerKind.TEMPLATE_DRAFT,draft.id,2),page,"fingerprint",PageProcessingStatus.PAUSED,error="INTERRUPTED")
        db.write{put("template_draft",draft.id,projectId,draft);put("image_job",image.id,projectId,image)}
        assertEquals(223,db.taskSummaries().total)
        assertEquals(1,db.taskSummaries().items.single{it.id==image.id}.pageNumber)
        db.write{put("template_draft",draft.id,projectId,draft.copy(revision=3))}
        assertEquals(222,db.taskSummaries().total)
        db.write{put("deletion",projectId,null,ProjectDeletionStatus(projectId,"delete",ProjectDeletionPhase.WAITING,1,null,0,0,null))}
        assertEquals(0,db.taskSummaries().total);assertTrue(db.projects.observeProjectSummaries().first().isEmpty());assertNull(db.intelligence.getReviewSummary("s1"));assertEquals(0,db.intelligence.queryReviewPage(projectId,ReviewPageQuery()).totalSubmitted)
    }

    @Test fun additiveIndexesSurviveV1MigrationAndReopenWithSchemaTwo()=runBlocking {
        (1..3).forEach{seed(it)}
        val root=workspace.root
        db.write {connection.createStatement().use{it.execute("DROP INDEX pc_submission_review_order");it.execute("DROP INDEX pc_project_updated");it.execute("PRAGMA user_version=1")}}
        workspace.close();workspace=SecureWorkspace.open(root,protector);db=DesktopRepositories(workspace)
        assertEquals(3,db.intelligence.queryReviewPage(projectId,ReviewPageQuery(filter=ReviewListFilter.ALL)).totalMatched)
        db.read {connection.createStatement().use {statement->
            statement.executeQuery("PRAGMA user_version").use{it.next();assertEquals(2,it.getInt(1))}
            val indexes=statement.executeQuery("PRAGMA index_list(pc_records)").use{rows->buildSet{while(rows.next())add(rows.getString("name"))}}
            assertTrue(indexes.containsAll(listOf("pc_submission_review_order","pc_project_updated")))
            statement.executeQuery("EXPLAIN QUERY PLAN SELECT id FROM pc_records WHERE kind='submission' AND project_id='anonymous-query-project' AND coalesce(json_extract(payload,'$.discarded'),0)=0 AND json_extract(payload,'$.currentRevisionId') IS NOT NULL ORDER BY CAST(json_extract(payload,'$.number') AS INTEGER),id LIMIT 2").use{rows->val plans=buildList{while(rows.next())add(rows.getString("detail"))};assertTrue(plans.joinToString().contains("pc_submission_review_order"))}
        }}
        workspace.close();workspace=SecureWorkspace.open(root,protector);db=DesktopRepositories(workspace)
        assertEquals(3,db.projects.observeProjectSummaries().first().single().submittedCount)
    }

    @Test fun unsupportedCurrentRecordVersionsAndStatusesFailOutsideTheFirstPage()=runBlocking {
        (1..3).forEach{seed(it)}
        db.write {connection.createStatement().use{it.execute("UPDATE pc_records SET payload=json_set(payload,'$.recordVersion',3) WHERE kind='recognition' AND id='n3'")}}
        fails<Exception>{runBlocking{db.intelligence.queryReviewPage(projectId,ReviewPageQuery(pageSize=1))}}
        db.write {connection.createStatement().use{it.execute("UPDATE pc_records SET payload=json_set(payload,'$.recordVersion',2,'$.status','FUTURE') WHERE kind='recognition' AND id='n3'")}}
        fails<Exception>{runBlocking{db.intelligence.queryReviewPage(projectId,ReviewPageQuery(pageSize=1))}}
        fails<Exception>{runBlocking{db.taskSummaries(1)}}
    }

    @Test fun exportHistoryReadsOnlyFrozenScalarCountsAndNeverRewritesSnapshots()=runBlocking {
        assertTrue(db.intelligence.observeExportSummaries(projectId).first().isEmpty())
        val sheets=listOf("Answers","Review","Summary","Fields").map {ExportSheet(it,listOf("anonymous"),List(30){listOf(ExportCell("anonymous exact text"))})}
        val snapshot=ExportSnapshot("snapshot-a",projectId,"Frozen project name",4,true,10,
            listOf(ExportRecordReference("s1","r1","a1",null),ExportRecordReference("s2","r2","a2","c2")),mapOf("s3" to "UNCONFIRMED","s4" to "MISSING_PAGES"),sheets)
        val empty=snapshot.copy(id="snapshot-b",records=emptyList(),excludedReasons=emptyMap(),includeUnresolved=false)
        db.write{put("export",snapshot.id,projectId,snapshot);put("export",empty.id,projectId,empty)}
        fun stored()=db.read {connection.prepareStatement("SELECT payload FROM pc_records WHERE kind='export' ORDER BY id").use {it.executeQuery().use {rows->buildList{while(rows.next())add(rows.getString(1))}}}}
        val before=stored()
        val summaries=db.intelligence.observeExportSummaries(projectId).first()
        assertEquals(listOf("snapshot-a","snapshot-b"),summaries.map{it.id})
        assertEquals(ExportListSummary(snapshot.id,projectId,snapshot.projectName,4,true,10,2,2),summaries.first())
        assertEquals(0,summaries.last().recordCount);assertEquals(0,summaries.last().excludedCount);assertFalse(summaries.last().includeUnresolved)
        assertEquals(before,stored());assertEquals(snapshot,db.intelligence.getExportSnapshot(snapshot.id))
        assertTrue(db.intelligence.observeExportSummaries("other-project").first().isEmpty())
        db.write{put("deletion",projectId,null,ProjectDeletionStatus(projectId,"delete",ProjectDeletionPhase.WAITING,1,null,0,0,null))}
        assertTrue(db.intelligence.observeExportSummaries(projectId).first().isEmpty());assertEquals(before,stored())
    }

    @Test fun exportSummaryRejectsFutureRecordVersion()=runBlocking {
        val snapshot=ExportSnapshot("snapshot",projectId,"Anonymous",1,false,1,emptyList(),emptyMap(),emptyList())
        db.write {put("export",snapshot.id,projectId,snapshot);connection.createStatement().use{it.execute("UPDATE pc_records SET payload=json_set(payload,'$.recordVersion',3) WHERE kind='export'")}}
        fails<Exception>{runBlocking{db.intelligence.observeExportSummaries(projectId).first()}}
    }

    @Test fun structureLocksWithAnUndiscardedDraftBeforeItsFirstSubmission() {
        assertFalse(db.read{locked(projectId)})
        val draft=Submission("unsubmitted",projectId,1,template.id,NumberingStrategy.PAPER_ID,1)
        db.write{put("submission",draft.id,projectId,draft)}
        assertTrue(db.read{locked(projectId)})
        db.write{put("submission",draft.id,projectId,draft.copy(discarded=true))}
        assertFalse(db.read{locked(projectId)})
    }
}
