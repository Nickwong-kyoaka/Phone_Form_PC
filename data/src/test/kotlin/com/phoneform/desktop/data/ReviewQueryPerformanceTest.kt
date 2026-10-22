package com.phoneform.desktop.data

import com.phoneform.ai.core.model.*
import com.phoneform.desktop.security.SecureWorkspace
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Read-query workload only: generated encrypted records, no image bytes, OCR or workbook output. */
class ReviewQueryPerformanceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun boundedPagesMatchHeavyLegacyWorkspaceReadsAndSurviveReopening() = runBlocking {
        val count = System.getenv("PHONEFORM_QUERY_SCALE_COUNT")?.toIntOrNull() ?: 120
        require(count in 100..2000)
        val root = temporary.newFolder("anonymous-query-workload").toPath()
        var workspace = SecureWorkspace.open(root)
        try {
            var db = DesktopRepositories(workspace)
            val projectId = "query-project"
            val asset = SampleAsset("generated-metadata",SampleKind.IMAGE,"metadata-only.png","0".repeat(64),1)
            val pages = (0 until 10).map { p -> SamplePage("page-$p",asset,p,p,"metadata-only.png","metadata-only.png",1200,1600) }
            val questions = (0 until 100).map { n -> QuestionDefinition("q-$n",pages[n/10].id,n%10,"${n+1}",
                "Anonymous question ${n+1}",QuestionType.TEXT,NormalizedRect(.1f,.1f,.9f,.2f),NormalizedRect(.1f,.2f,.9f,.3f)) }
            val document = TemplateDraft("template-draft",projectId,pages=pages,questions=questions)
            val template = FormSchemaVersion("template",projectId,1,document.id,1,document)
            val details = ProjectDetails(ProjectConfig(projectId,"Anonymous paged query",count,QuestionnaireLanguage.ENGLISH,
                NumberingStrategy.PAPER_ID,1,1,PreparationStatus.TEMPLATE_CONFIRMED,template.id),
                OutputProfile(projectId,language=OutputHeaderLanguage.ENGLISH),emptyList())
            val seedStart = System.nanoTime()
            // Bulk seeding deliberately does not claim to benchmark collection writes or physical page availability.
            db.write {
                put("project",projectId,projectId,details);put("template",template.id,projectId,template)
                for (number in 1..count) {
                    val id="submission-$number";val revisionId="revision-$number";val confirmed=number%2==0
                    val missing=if(number%7==0)listOf(pages.last().id)else emptyList()
                    val paper=if(number==1)"Ångström_%_00001"else "%05d".format(number)
                    val submission=Submission(id,projectId,number,template.id,NumberingStrategy.PAPER_ID,number.toLong(),revisionId,paper)
                    val pageRevisions=pages.filterNot {it.id in missing}.map {PageRevision(it.id,it.copy(id="image-$number-${it.id}"))}
                    val revision=SubmissionRevision(revisionId,id,projectId,template.id,1,"draft-$number",1,paper,pageRevisions,missing,
                        SubmissionConfirmation(allowMissingPages=missing.isNotEmpty()),number.toLong())
                    val prepared=ProcessingJob("prep-$number",projectId,id,revisionId,PreparationJobStatus.READY,
                        PreparationStage.READY,pageRevisions,createdAtEpochMillis=number.toLong())
                    val observations=questions.map {q->FieldObservation("question:${q.id}",q.pageId,"image-$number-${q.pageId}",1,
                        AnswerValue("question:${q.id}",AnswerState.VALUE,"anonymous candidate"),rawText="anonymous printed evidence",requiresReview=true)}
                    val recognition=RecognitionJob("recognition-$number",projectId,id,revisionId,
                        if(number%11==0)RecognitionJobStatus.FAILED else RecognitionJobStatus.READY,
                        observations=observations,createdAtEpochMillis=number.toLong())
                    val edited=confirmed || number%3==0
                    val answers=AnswerSetRevision("answers-$number",projectId,id,revisionId,1,"answer-operation-$number",questions.map {q->
                        val state=if(q.pageId in missing)AnswerState.NOT_CAPTURED else if(edited)AnswerState.VALUE else AnswerState.UNHANDLED
                        AnswerValue("question:${q.id}",state,if(state==AnswerState.VALUE)"anonymous answer"else "",humanEdited=edited)
                    })
                    put("submission",id,projectId,submission);put("revision",revisionId,projectId,revision)
                    put("preparation",prepared.id,projectId,prepared);put("preparation_head",revisionId,projectId,RecordReference(prepared.id))
                    put("recognition",recognition.id,projectId,recognition);put("recognition_head",revisionId,projectId,RecordReference(recognition.id))
                    put("answers",answers.id,projectId,answers);put("answer_head",revisionId,projectId,RecordReference(answers.id))
                    if(confirmed){val confirmation=ReviewConfirmation("confirm-$number",revisionId,answers.id,"confirm-operation-$number",number.toLong())
                        put("confirmation",confirmation.id,projectId,confirmation);put("confirmation_answer",answers.id,projectId,RecordReference(confirmation.id))}
                }
                // A late historical failure must neither appear as a current task nor replace the current status.
                put("recognition","old-recognition",projectId,RecognitionJob("old-recognition",projectId,"submission-1","revision-1",RecognitionJobStatus.FAILED))
            }
            val seedMillis=(System.nanoTime()-seedStart)/1_000_000
            val legacyStart=System.nanoTime()
            val expected=db.read {list<Submission>("submission",projectId).sortedBy {it.number}.map {s->
                val w=requireNotNull(review(s.id))
                ReviewListSummary(s.id,s.number,w.revision.id,w.revision.missingTemplatePageIds.size,prep(w.revision.id)?.status,w.job?.status,w.confirmation!=null,
                    w.confirmation==null && (w.revision.missingTemplatePageIds.isNotEmpty() || prep(w.revision.id)?.status==PreparationJobStatus.FAILED || w.job?.status==RecognitionJobStatus.FAILED ||
                        w.job?.observations?.any {o->o.requiresReview && w.answers?.answers?.any {it.targetKey==o.targetKey && it.humanEdited}!=true}==true),s.respondentId)
            } }
            val legacyNanos=System.nanoTime()-legacyStart
            val query=ReviewPageQuery(filter=ReviewListFilter.ALL,pageSize=50)
            val firstStart=System.nanoTime();val first=db.intelligence.queryReviewPage(projectId,query);val firstNanos=System.nanoTime()-firstStart
            assertEquals(count,first.totalMatched);assertEquals(count,first.totalSubmitted);assertEquals(count/2,first.confirmedCount)
            val actual=mutableListOf<ReviewListSummary>()
            for(index in 0 until (count+49)/50){val page=db.intelligence.queryReviewPage(projectId,query.copy(pageIndex=index));assertTrue(page.items.size<=50);actual+=page.items}
            assertEquals(expected,actual);assertEquals(count,actual.map {it.submissionId}.distinct().size)
            val searches=listOf("ångström", "%", "_", "%05d".format(count), "absent anonymous ID")
            val searchTimes=searches.map {search->val start=System.nanoTime();val found=db.intelligence.queryReviewPage(projectId,query.copy(search=search));val elapsed=System.nanoTime()-start
                val truth=expected.filter {it.respondentId.contains(search,true)||it.number.toString().contains(search,true)}
                assertEquals(truth.size,found.totalMatched);assertEquals(truth.take(50),found.items);elapsed}
            val neighbors=db.intelligence.reviewNeighbors(projectId,"submission-50",query)
            assertEquals("submission-49",neighbors.previousSubmissionId);assertEquals("submission-51",neighbors.nextSubmissionId)
            val clamped=db.intelligence.queryReviewPage(projectId,query.copy(pageIndex=Int.MAX_VALUE))
            assertEquals((count-1)/50,clamped.query.pageIndex);assertEquals(count,clamped.items.last().number)
            val summaryStart=System.nanoTime();val summary=db.projects.observeProjectSummaries().first().single();val summaryNanos=System.nanoTime()-summaryStart
            assertEquals(count,summary.submittedCount);assertEquals(count/2,summary.confirmedCount)
            val tasksStart=System.nanoTime();val tasks=db.taskSummaries(20);val tasksNanos=System.nanoTime()-tasksStart
            assertEquals(count*2,tasks.total);assertEquals(20,tasks.items.size);assertEquals(tasks.total,tasks.statusCounts.values.sum())
            assertFalse(tasks.items.any {it.id=="old-recognition"})
            val rerun=db.intelligence.unconfirmedRerunTargets(projectId)
            assertEquals((1..count).count {it%2!=0},rerun.size)
            assertTrue(rerun.all {it.submissionRevisionId=="revision-${it.submissionId.removePrefix("submission-")}" && it.currentJobId=="recognition-${it.submissionId.removePrefix("submission-")}"})
            val databaseBytes=Files.size(root.resolve("phoneform.db"))
            workspace.close();val reopenStart=System.nanoTime();workspace=SecureWorkspace.open(root);db=DesktopRepositories(workspace)
            assertEquals(first,db.intelligence.queryReviewPage(projectId,query));val reopenNanos=System.nanoTime()-reopenStart
            val heap=ManagementFactory.getMemoryPoolMXBeans().filter {it.type==MemoryType.HEAP}.sumOf {it.peakUsage.used}
            val report=Path.of("build","reports","paging","query-$count.json")
            Files.createDirectories(report.parent)
            Files.writeString(report,"""{"version":"0.3.1-dev","records":$count,"pagesPerRecord":10,"targetsPerRecord":100,"fixtureScope":"Bulk-seeded encrypted record metadata; no image bytes, asset availability, collection throughput, OCR, GUI or workbook measurement","seedMillis":$seedMillis,"legacyFullWorkspaceSummaryNanos":$legacyNanos,"firstSqlPageNanos":$firstNanos,"searchNanos":[${searchTimes.joinToString()}],"projectSummaryNanos":$summaryNanos,"taskSummaryNanos":$tasksNanos,"taskTotal":${tasks.total},"taskRowsReturned":${tasks.items.size},"pageSize":50,"allRowsVerified":${actual.size},"reopenAndFirstPageNanos":$reopenNanos,"peakHeapPoolBytes":$heap,"encryptedDatabaseBytes":$databaseBytes,"passed":true,"artifactsRetained":0}""")
            Unit
        } finally {workspace.close()}
    }
}
