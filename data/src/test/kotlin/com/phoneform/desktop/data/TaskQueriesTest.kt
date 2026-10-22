package com.phoneform.desktop.data

import com.phoneform.ai.core.model.*
import com.phoneform.desktop.security.KeyProtector
import com.phoneform.desktop.security.SecureWorkspace
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TaskQueriesTest {
    @get:Rule val folder=TemporaryFolder()
    private val protector=object:KeyProtector {
        override fun protect(plain:ByteArray,purpose:String)=plain.map { (it.toInt() xor 0x63).toByte() }.toByteArray()
        override fun unprotect(wrapped:ByteArray,purpose:String)=protect(wrapped,purpose)
    }
    private lateinit var workspace:SecureWorkspace
    private lateinit var db:DesktopRepositories
    private val project="anonymous-task-project"
    private val name="Ångström_%_資料"
    @Before fun open() {
        workspace=SecureWorkspace.open(folder.newFolder("workspace").toPath(),protector);db=DesktopRepositories(workspace)
        addProject(project,name)
    }
    @After fun close() { workspace.close() }
    private fun addProject(id:String,label:String) {
        val config=ProjectConfig(id,label,1000,QuestionnaireLanguage.ENGLISH,NumberingStrategy.PAPER_ID,1,2)
        db.write {put("project",id,id,ProjectDetails(config,OutputProfile(id,language=OutputHeaderLanguage.ENGLISH),emptyList()))}
    }
    private fun Records.seed(number:Int,recognition:RecognitionJobStatus=RecognitionJobStatus.READY,projectId:String=project) {
        val prefix="$projectId-$number"
        val submission=Submission("s-$prefix",projectId,number,"template",NumberingStrategy.PAPER_ID,number.toLong(),"r-$prefix","paper-$number")
        val revision=SubmissionRevision("r-$prefix",submission.id,projectId,"template",1,"source-$prefix",1,submission.respondentId,emptyList(),emptyList(),SubmissionConfirmation(),number.toLong())
        val preparation=ProcessingJob("p-$prefix",projectId,submission.id,revision.id,PreparationJobStatus.READY,createdAtEpochMillis=number.toLong())
        val job=RecognitionJob("n-$prefix",projectId,submission.id,revision.id,recognition,createdAtEpochMillis=number.toLong())
        put("submission",submission.id,projectId,submission);put("revision",revision.id,projectId,revision)
        put("preparation",preparation.id,projectId,preparation);put("recognition",job.id,projectId,job)
        put("recognition_head",revision.id,projectId,RecordReference(job.id))
    }
    private inline fun <reified T:Throwable> fails(block:()->Unit) {
        try {block();fail("Expected ${T::class.java.simpleName}")}catch(failure:Throwable){if(failure !is T)throw failure}
    }
    private fun pages(query:TaskPageQuery):List<BackgroundTaskSummary> = runBlocking {
        val first=db.queryTaskPage(query)
        buildList { addAll(first.items);for(page in 1..((first.totalMatched-1).coerceAtLeast(0)/first.query.pageSize)) addAll(db.queryTaskPage(first.query.copy(pageIndex=page)).items) }
    }

    @Test fun filtersAndPagesReachAllCurrentTasksBeyondTheOldTwoHundredLimit()=runBlocking {
        val statuses=listOf(RecognitionJobStatus.RUNNING,RecognitionJobStatus.FAILED,RecognitionJobStatus.BLOCKED,RecognitionJobStatus.PAUSED,RecognitionJobStatus.QUEUED,RecognitionJobStatus.READY)
        db.write {(260 downTo 1).forEach {seed(it,statuses[(it-1)%statuses.size])}}
        val all=pages(TaskPageQuery(filter=TaskListFilter.ALL,pageSize=37))
        assertEquals(520,all.size);assertEquals(520,all.map {it.phase to it.id}.toSet().size)
        assertTrue(all.all {it.projectName==name})
        assertTrue(all.all {it.submissionId=="s-$project-${it.submissionNumber}"})
        val ranks=mapOf("RUNNING" to 0,"FAILED" to 1,"BLOCKED" to 2,"PAUSED" to 3,"QUEUED" to 4,"READY" to 5)
        assertEquals(all.sortedWith(compareBy<BackgroundTaskSummary> {ranks.getValue(it.status)}.thenByDescending {it.createdAtEpochMillis}.thenBy {it.phase.name}.thenBy {it.id}),all)
        val legacy=db.taskSummaries()
        assertEquals(all.take(200),legacy.items);assertEquals(520,legacy.total)
        for(filter in TaskListFilter.entries) {
            val query=TaskPageQuery(filter=filter,pageSize=29)
            val first=db.queryTaskPage(query)
            val expected=all.filter {when(filter) {
                TaskListFilter.PENDING -> it.status in setOf("QUEUED","RUNNING","PAUSED","BLOCKED")
                TaskListFilter.FAILED -> it.status=="FAILED"
                TaskListFilter.COMPLETED -> it.status=="READY"
                TaskListFilter.ALL -> true
            }}
            assertEquals(expected,pages(query));assertEquals(expected.size,first.totalMatched)
            assertEquals(legacy.statusCounts,first.statusCounts);assertEquals(520,first.total)
        }
        val completed=db.queryTaskPage(TaskPageQuery(filter=TaskListFilter.COMPLETED,pageIndex=3))
        assertTrue(completed.items.isNotEmpty());assertTrue(completed.items.none {item->legacy.items.any {it.id==item.id && it.phase==item.phase}})
        val later=all.last()
        assertEquals(later,db.getTaskSummary(later.id,later.phase));assertNull(db.getTaskSummary(later.id,BackgroundTaskPhase.IMAGE))
    }

    @Test fun searchesAreLiteralUnicodeGlobalAndKeepCountsWhilePagesClampAfterDeletion()=runBlocking {
        addProject("other","Other anonymous")
        db.write {(1..126).forEach {seed(it,RecognitionJobStatus.FAILED)};seed(8765,RecognitionJobStatus.BLOCKED,"other")}
        for(search in listOf("ångSTRÖM","資料","%_")) {
            val page=db.queryTaskPage(TaskPageQuery(" $search ",TaskListFilter.ALL,pageIndex=Int.MAX_VALUE,pageSize=100))
            assertEquals(search,page.query.search);assertEquals(252,page.totalMatched);assertEquals(254,page.total)
            assertEquals(2,page.query.pageIndex);assertEquals(52,page.items.size);assertTrue(page.items.all {it.projectId==project})
        }
        assertEquals(2,db.queryTaskPage(TaskPageQuery("8765",TaskListFilter.ALL)).totalMatched)
        assertEquals("other",db.queryTaskPage(TaskPageQuery("8765",TaskListFilter.ALL)).items.first().projectId)
        assertEquals(0,db.queryTaskPage(TaskPageQuery("%wildcard",TaskListFilter.ALL)).totalMatched)
        val last=db.queryTaskPage(TaskPageQuery(name,TaskListFilter.COMPLETED,pageIndex=2,pageSize=50))
        assertEquals(2,last.query.pageIndex);assertEquals(26,last.items.size)
        db.write {(101..126).forEach {number->
            val submission=requireNotNull(get<Submission>("submission","s-$project-$number"))
            put("submission",submission.id,project,submission.copy(discarded=true))
        }}
        val after=db.queryTaskPage(last.query)
        assertEquals(1,after.query.pageIndex);assertEquals(50,after.items.size);assertEquals(100,after.totalMatched);assertEquals(202,after.total)
    }

    @Test fun pointReadsRejectStaleHeadsAndAllowRetryOfRowsOutsideTheLegacyBatch()=runBlocking {
        db.write {(1..130).forEach {seed(it,RecognitionJobStatus.RUNNING)}}
        val id="p-$project-1"
        db.write {val job=requireNotNull(get<ProcessingJob>("preparation",id));put("preparation",id,project,job.copy(status=PreparationJobStatus.FAILED))}
        val task=requireNotNull(db.getTaskSummary(id,BackgroundTaskPhase.PREPARATION))
        assertTrue(task.retrySupported)
        db.submissions.retryJob(id)
        assertEquals("QUEUED",db.getTaskSummary(id,BackgroundTaskPhase.PREPARATION)!!.status)
        val later=db.queryTaskPage(TaskPageQuery(filter=TaskListFilter.COMPLETED,pageIndex=2)).items.last()
        assertTrue(db.taskSummaries().items.none {it.id==later.id})
        assertEquals(later,db.getTaskSummary(later.id,later.phase))
        db.write {
            val old=requireNotNull(get<ProcessingJob>("preparation",later.id))
            put("preparation","newer-preparation",project,old.copy(id="newer-preparation",status=PreparationJobStatus.READY))
        }
        assertNull(db.getTaskSummary(later.id,later.phase));assertNotNull(db.getTaskSummary("newer-preparation",later.phase))
        db.write {val submission=requireNotNull(get<Submission>("submission","s-$project-2"));put("submission",submission.id,project,submission.copy(discarded=true))}
        assertNull(db.getTaskSummary("n-$project-2",BackgroundTaskPhase.RECOGNITION))
        db.write {put("deletion",project,null,ProjectDeletionStatus(project,"delete",ProjectDeletionPhase.WAITING,1,null,0,0,null))}
        assertNull(db.getTaskSummary(id,BackgroundTaskPhase.PREPARATION))
        val empty=db.queryTaskPage(TaskPageQuery(filter=TaskListFilter.ALL,pageIndex=Int.MAX_VALUE))
        assertEquals(0,empty.total);assertEquals(0,empty.totalMatched);assertEquals(0,empty.query.pageIndex);assertTrue(empty.items.isEmpty());assertTrue(empty.statusCounts.isEmpty())
    }

    @Test fun imageTaskPointLookupChecksCurrentOwnerAndHead()=runBlocking {
        val page=SamplePage("page",SampleAsset("asset",SampleKind.IMAGE,"metadata-only","0".repeat(64),1),0,0,"metadata-only","metadata-only",10,10)
        val draft=TemplateDraft("draft",project,revision=2,pages=listOf(page))
        val job=PageProcessingJob("image",PageProcessingOwner(project,PageProcessingOwnerKind.TEMPLATE_DRAFT,draft.id,2),page,PageProcessingFingerprint.of(page),PageProcessingStatus.PAUSED)
        db.write {put("template_draft",draft.id,project,draft);put("image_job",job.id,project,job)}
        val summary=requireNotNull(db.getTaskSummary(job.id,BackgroundTaskPhase.IMAGE))
        assertEquals(name,summary.projectName);assertEquals(1,summary.pageNumber);assertTrue(summary.retrySupported);assertNull(summary.submissionId)
        assertEquals(listOf(summary),db.queryTaskPage(TaskPageQuery(name)).items)
        db.write {put("image_job","new-image",project,job.copy(id="new-image"))}
        assertNull(db.getTaskSummary(job.id,BackgroundTaskPhase.IMAGE));assertNotNull(db.getTaskSummary("new-image",BackgroundTaskPhase.IMAGE))
        db.write {put("template_draft",draft.id,project,draft.copy(revision=3))}
        assertNull(db.getTaskSummary("new-image",BackgroundTaskPhase.IMAGE));assertEquals(0,db.queryTaskPage(TaskPageQuery()).total)
    }

    @Test fun unsupportedCurrentRecordsCannotHideBehindSearchFiltersOrSmallPages()=runBlocking {
        db.write {(1..126).forEach {seed(it,RecognitionJobStatus.READY)}}
        val badId="n-$project-1"
        db.write {connection.prepareStatement("UPDATE pc_records SET payload=json_set(payload,'$.recordVersion',3) WHERE kind='recognition' AND id=?").use {it.setString(1,badId);it.executeUpdate()}}
        fails<Exception> {runBlocking {db.queryTaskPage(TaskPageQuery("no-match",TaskListFilter.FAILED,pageSize=1))}}
        fails<Exception> {runBlocking {db.getTaskSummary(badId,BackgroundTaskPhase.RECOGNITION)}}
        db.write {connection.prepareStatement("UPDATE pc_records SET payload=json_set(payload,'$.recordVersion',2,'$.status','FUTURE') WHERE kind='recognition' AND id=?").use {it.setString(1,badId);it.executeUpdate()}}
        fails<Exception> {runBlocking {db.queryTaskPage(TaskPageQuery("no-match",TaskListFilter.PENDING,pageSize=1))}}
        fails<Exception> {runBlocking {db.getTaskSummary(badId,BackgroundTaskPhase.RECOGNITION)}}
    }

    @Test fun draftImageTasksKeepUnsubmittedAndPageRevisionOwnersSeparateFromReview()=runBlocking {
        db.write {seed(2)}
        val page=SamplePage("draft-image",SampleAsset("draft-asset",SampleKind.IMAGE,"metadata-only","0".repeat(64),1),0,0,"metadata-only","metadata-only",10,10)
        val unsubmitted=Submission("unsubmitted",project,3,"template",NumberingStrategy.PAPER_ID,1)
        val fresh=SubmissionDraft("new-draft",unsubmitted.id,project,"template",editRevision=2,pages=listOf(PageRevision("slot",page)))
        val patch=fresh.copy(id="revision-draft",submissionId="s-$project-2",baseRevisionId="r-$project-2",editRevision=3)
        fun job(draft:SubmissionDraft)=PageProcessingJob("image-${draft.id}",PageProcessingOwner(project,PageProcessingOwnerKind.SUBMISSION_DRAFT,draft.id,draft.editRevision),page,PageProcessingFingerprint.of(page),PageProcessingStatus.FAILED)
        db.write {put("submission",unsubmitted.id,project,unsubmitted);listOf(fresh,patch).forEach {put("submission_draft",it.id,project,it);val queued=job(it);put("image_job",queued.id,project,queued)}}
        for(draft in listOf(fresh,patch)) {
            val summary=requireNotNull(db.getTaskSummary(job(draft).id,BackgroundTaskPhase.IMAGE))
            assertEquals(draft.id,summary.collectionDraftId);assertEquals(page.id,summary.pageId);assertEquals(draft.submissionId,summary.submissionId)
            val paged=db.queryTaskPage(TaskPageQuery(filter=TaskListFilter.FAILED)).items.single {it.id==summary.id}
            assertEquals(summary,paged)
        }
        val prep=requireNotNull(db.getTaskSummary("p-$project-2",BackgroundTaskPhase.PREPARATION))
        assertNull(prep.collectionDraftId);assertNull(prep.pageId)
        db.write {val submission=requireNotNull(get<Submission>("submission",patch.submissionId));put("submission",submission.id,project,submission.copy(currentRevisionId="newer-revision"))}
        assertNull(db.getTaskSummary(job(patch).id,BackgroundTaskPhase.IMAGE))
        db.write {remove("submission_draft",fresh.id)}
        assertNull(db.getTaskSummary(job(fresh).id,BackgroundTaskPhase.IMAGE))
    }

    @Test fun taskQueryRejectsInvalidBoundsAndNormalizesEmptyResults()=runBlocking {
        for(query in listOf(TaskPageQuery(pageIndex=-1),TaskPageQuery(pageSize=0),TaskPageQuery(pageSize=101)))
            fails<IllegalArgumentException> {runBlocking {db.queryTaskPage(query)}}
        val empty=db.queryTaskPage(TaskPageQuery("  ",TaskListFilter.ALL,pageIndex=Int.MAX_VALUE))
        assertEquals("",empty.query.search);assertEquals(0,empty.query.pageIndex);assertEquals(0,empty.total);assertTrue(empty.items.isEmpty())
        assertNull(db.getTaskSummary("missing",BackgroundTaskPhase.RECOGNITION))
    }

    @Test fun preparationRetryCannotReplaceANewerCurrentHeadAndKeepsSameJobCheckpoint()=runBlocking {
        db.write {seed(1)}
        val old=db.read {requireNotNull(get<ProcessingJob>("preparation","p-$project-1"))}.copy(status=PreparationJobStatus.FAILED,attempt=3,error="ANONYMOUS_FAILURE",runToken="old-token",processSessionId="old-session")
        val current=old.copy(id="current-preparation",status=PreparationJobStatus.PAUSED,stage=PreparationStage.PREPARE_IMAGES)
        db.write {put("preparation",old.id,project,old);put("preparation",current.id,project,current)}
        val payloadsBefore=db.read {list<ProcessingJob>("preparation").associateBy {it.id}}
        fails<StaleSubmissionException> {runBlocking {db.submissions.retryJob(old.id)}}
        assertEquals(payloadsBefore,db.read {list<ProcessingJob>("preparation").associateBy {it.id}})
        assertNull(db.getTaskSummary(old.id,BackgroundTaskPhase.PREPARATION))
        db.submissions.retryJob(current.id)
        val retried=db.read {requireNotNull(prep(current.submissionRevisionId))}
        assertEquals(current.copy(status=PreparationJobStatus.QUEUED,runToken=null,processSessionId=null,error=null),retried)
        assertEquals(old,db.read {get<ProcessingJob>("preparation",old.id)})
        db.write {val submission=requireNotNull(get<Submission>("submission",old.submissionId));put("submission",submission.id,project,submission.copy(discarded=true))}
        fails<StaleSubmissionException> {runBlocking {db.submissions.retryJob(current.id)}}
        assertEquals(retried,db.read {get<ProcessingJob>("preparation",current.id)})
    }

    @Test fun imageRetryCannotReplaceANewerCurrentHeadAndKeepsSameJobInput()=runBlocking {
        val page=SamplePage("retry-page",SampleAsset("asset",SampleKind.IMAGE,"metadata-only","0".repeat(64),1),0,0,"metadata-only","metadata-only",10,10)
        val draft=TemplateDraft("retry-draft",project,revision=2,pages=listOf(page))
        val owner=PageProcessingOwner(project,PageProcessingOwnerKind.TEMPLATE_DRAFT,draft.id,draft.revision)
        val old=PageProcessingJob("old-image",owner,page,PageProcessingFingerprint.of(page),PageProcessingStatus.FAILED,"old-session","old-token",3,"ANONYMOUS_FAILURE",1,"1".repeat(64))
        val current=old.copy(id="current-image",status=PageProcessingStatus.PAUSED)
        db.write {put("template_draft",draft.id,project,draft);put("image_job",old.id,project,old);put("image_job",current.id,project,current)}
        val before=db.read {list<PageProcessingJob>("image_job").associateBy {it.id}}
        fails<StalePageProcessingException> {runBlocking {db.imageProcessing.retry(old.id)}}
        assertEquals(before,db.read {list<PageProcessingJob>("image_job").associateBy {it.id}})
        assertNull(db.getTaskSummary(old.id,BackgroundTaskPhase.IMAGE))
        db.imageProcessing.retry(current.id)
        val retried=db.read {requireNotNull(imageJob(owner,page.id,current.inputFingerprint))}
        assertEquals(current.copy(status=PageProcessingStatus.QUEUED,runToken=null,processSessionId=null,error=null),retried)
        assertEquals(old,db.read {get<PageProcessingJob>("image_job",old.id)})
        db.write {put("template_draft",draft.id,project,draft.copy(revision=3))}
        fails<StalePageProcessingException> {runBlocking {db.imageProcessing.retry(current.id)}}
        assertEquals(retried,db.read {get<PageProcessingJob>("image_job",current.id)})
    }

    @Test fun staleSubmissionImageOwnersCannotBeRetriedClaimedOrAcceptLateResults()=runBlocking {
        var number=500
        for(kind in listOf(PageProcessingOwnerKind.SUBMISSION_DRAFT,PageProcessingOwnerKind.SUBMISSION_REVISION))
            for(invalidation in listOf("BASE_CHANGED","DISCARDED","PROJECT_CHANGED")) {
                number++
                val currentNumber=number
                db.write {seed(currentNumber)}
                val submission=db.read {requireNotNull(get<Submission>("submission","s-$project-$currentNumber"))}
                val page=SamplePage("owner-page-$currentNumber",SampleAsset("asset-$currentNumber",SampleKind.IMAGE,"metadata-only","0".repeat(64),1),0,0,"metadata-only","metadata-only",10,10)
                val revision=db.read {requireNotNull(get<SubmissionRevision>("revision",submission.currentRevisionId!!))}.copy(pages=listOf(PageRevision("slot",page)))
                val draft=SubmissionDraft("owner-draft-$currentNumber",submission.id,project,"template",revision.id,2,pages=revision.pages)
                val owner=if(kind==PageProcessingOwnerKind.SUBMISSION_DRAFT)PageProcessingOwner(project,kind,draft.id,draft.editRevision) else PageProcessingOwner(project,kind,revision.id,revision.number)
                val job=PageProcessingJob("owner-job-$currentNumber",owner,page,PageProcessingFingerprint.of(page),PageProcessingStatus.PAUSED)
                db.write {put("revision",revision.id,project,revision);put("submission_draft",draft.id,project,draft);put("image_job",job.id,project,job)}
                assertEquals(listOf(page),db.read {ownerPages(owner)})
                val claimed=requireNotNull(db.imageProcessing.claim("owner-test",true,owner,page.id))
                assertTrue(db.imageProcessing.isClaimValid(claimed,true))
                db.write {when(invalidation) {
                    "BASE_CHANGED" -> put("submission",submission.id,project,submission.copy(currentRevisionId="replacement-$currentNumber"))
                    "DISCARDED" -> put("submission",submission.id,project,submission.copy(discarded=true))
                    else -> put("submission",submission.id,"another-project",submission.copy(projectId="another-project"))
                }}
                assertNull(db.read {ownerPages(owner)})
                assertNull(db.getTaskSummary(job.id,BackgroundTaskPhase.IMAGE))
                assertFalse(db.imageProcessing.isClaimValid(claimed,true))
                val lateResult=PageProcessingResult(job.inputFingerprint,PageQualityReport(job.inputFingerprint,"anonymous-owner-check"))
                assertFalse(db.imageProcessing.complete(claimed,lateResult))
                assertEquals(claimed,db.read {get<PageProcessingJob>("image_job",job.id)})
                // Model a durable paused checkpoint after a process restart, retaining the same owner.
                val paused=claimed.copy(status=PageProcessingStatus.PAUSED,runToken=null,processSessionId=null,error="ANONYMOUS_PAUSE")
                db.write {put("image_job",paused.id,project,paused)}
                fails<StalePageProcessingException> {runBlocking {db.imageProcessing.retry(job.id)}}
                assertEquals(paused,db.read {get<PageProcessingJob>("image_job",job.id)})
                assertNull(db.imageProcessing.claim("owner-test-late",true,owner,page.id))
                assertEquals(PageProcessingStatus.SUPERSEDED,db.read {get<PageProcessingJob>("image_job",job.id)!!.status})
                assertNull(db.imageProcessing.getResult(page))
            }
    }
}
