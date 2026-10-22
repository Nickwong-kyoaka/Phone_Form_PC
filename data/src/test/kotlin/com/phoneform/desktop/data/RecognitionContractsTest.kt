package com.phoneform.desktop.data

import com.phoneform.ai.core.model.*
import com.phoneform.desktop.security.SecureWorkspace
import com.phoneform.desktop.security.TestKeyProtector
import java.io.ByteArrayInputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecognitionContractsTest {
    @get:Rule val folder=TemporaryFolder()
    private val protector=TestKeyProtector()
    private lateinit var workspace: SecureWorkspace
    private lateinit var db: DesktopRepositories
    @Before fun open(){ workspace=SecureWorkspace.open(folder.newFolder().toPath(),protector); db=DesktopRepositories(workspace) }
    @After fun close(){workspace.close()}
    private inline fun <reified T:Throwable> fails(block:()->Unit){try{block();fail("Expected ${T::class.java.simpleName}")}catch(e:Throwable){if(e !is T)throw e}}
    private suspend fun template(enabled:Boolean=true): FormSchemaVersion {
        val id=newId();val now=System.currentTimeMillis()
        db.projects.saveProject(ProjectDetails(ProjectConfig(id,"Synthetic contract fixture",1,QuestionnaireLanguage.ENGLISH,NumberingStrategy.GENERATED,now,now),OutputProfile(id,language=OutputHeaderLanguage.ENGLISH),emptyList()))
        val asset=workspace.assets.put("fixtures/$id.bin",ByteArrayInputStream("synthetic encrypted evidence $id".toByteArray()))
        val page=SamplePage(newId(),SampleAsset(newId(),SampleKind.IMAGE,asset.relativePath,asset.sha256,asset.byteCount),0,0,asset.relativePath,asset.relativePath,1000,1400)
        val options=listOf(OptionDefinition("a","First","01",NormalizedRect(.1f,.3f,.18f,.36f)),OptionDefinition("b","Second","02",NormalizedRect(.3f,.3f,.38f,.36f)))
        val settings=QuestionRecognitionSettings("q",enabled,if(enabled)MarkStyle.CHECKBOX_TICK_CROSS else MarkStyle.MANUAL_ONLY,options.map{OptionMarkRegion(it.id,it.region!!)})
        val question=QuestionDefinition("q",page.id,0,"1","Synthetic choice",QuestionType.SINGLE_CHOICE,NormalizedRect(.05f,.2f,.6f,.5f),options=options,recognition=settings)
        val initial=db.templates.beginDraft(id);val draft=db.templates.saveDraft(initial.copy(pages=listOf(page),questions=listOf(question)))
        db.imageProcessing.keepAllWarnings(draft.owner(),mapOf(page.id to PageProcessingFingerprint.of(page)),"Synthetic fixture inspection")
        return db.templates.confirmDraft(id,draft.id,draft.revision)
    }
    private suspend fun submit(template:FormSchemaVersion):SubmissionRevision {
        val initial=db.submissions.beginSubmission(template.projectId)
        val draft=db.submissions.saveDraft(initial.copy(pages=template.document.pages.map{PageRevision(it.id,it.copy(id=newId()))}))
        db.imageProcessing.keepAllWarnings(draft.owner(),draft.pages.associate{it.image.id to PageProcessingFingerprint.of(it.image)},"Synthetic fixture inspection")
        val revision=db.submissions.submitSubmission(draft.id,draft.editRevision,SubmissionConfirmation())
        val prep=db.jobs.claimNext("test",true)!!;assertTrue(db.jobs.checkpoint(prep,revision.pages));assertTrue(db.jobs.complete(prep))
        return revision
    }
    private suspend fun nextProfile(template:FormSchemaVersion):TemplateRecognitionProfile {
        val draft=db.intelligence.beginRecognitionProfileDraft(template.id)
        val saved=db.intelligence.saveRecognitionProfileDraft(draft)
        return db.intelligence.confirmRecognitionProfileDraft(saved.id,saved.revision,saved.baseProfileId,newId())
    }
    private fun observation(job:RecognitionJob, revision:SubmissionRevision, selected:String="a"):Pair<PageAlignment,FieldObservation>{
        val page=revision.pages.single();val options=listOf(OptionObservation("a",if(selected=="a")OptionDecision.SELECTED else OptionDecision.EMPTY,NormalizedRect(.1f,.3f,.18f,.36f)),OptionObservation("b",if(selected=="b")OptionDecision.SELECTED else OptionDecision.EMPTY,NormalizedRect(.3f,.3f,.38f,.36f)))
        val alignment=PageAlignment(page.templatePageId,page.image.id,page.image.revision,AlignmentStatus.ALIGNED,listOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0),engineVersion=job.engineVersion)
        val raw=FieldObservation("question:q",page.templatePageId,page.image.id,page.image.revision,
            AnswerValue("question:q",AnswerState.VALUE,optionIds=listOf(selected),source=AnswerSource.MACHINE_ACCEPTED),reason="MARKS_REQUIRE_CONFIRMATION",evidenceRegion=NormalizedRect(.05f,.2f,.6f,.5f),engineVersion=job.engineVersion,processingFingerprint=PageProcessingFingerprint.of(page.image),optionObservations=options,sourceSha256=pageHash(db,page.image))
        return alignment to RecognitionBindings.bind(raw,job.id,job.profileId!!)
    }
    private suspend fun finish(job:RecognitionJob,revision:SubmissionRevision,selected:String="a"):FieldObservation {
        val (alignment,value)=observation(job,revision,selected)
        assertTrue(db.intelligence.checkpointRecognition(job,alignment,listOf(value)));assertTrue(db.intelligence.completeRecognition(job));return value
    }

    @Test fun initialProfileAndDraftPublishingAreAtomicImmutableAndIdempotent()=runBlocking {
        val template=template();val first=db.intelligence.getRecognitionProfile(template.id)!!
        assertEquals(template.sourceDraftId,first.sourceDraftId);assertEquals(1,first.version);assertEquals(template.projectId,first.projectId)
        val draft=db.intelligence.beginRecognitionProfileDraft(template.id)
        val invalid=db.intelligence.saveRecognitionProfileDraft(draft.copy(settings=draft.settings.map{it.copy(style=MarkStyle.CIRCLED_PRINTED_NUMBER)}))
        fails<IllegalArgumentException>{runBlocking{db.intelligence.confirmRecognitionProfileDraft(invalid.id,invalid.revision,first.id,"invalid-publish")}}
        assertEquals(first,db.intelligence.getRecognitionProfile(template.id))
        val corrected=db.intelligence.saveRecognitionProfileDraft(invalid.copy(settings=draft.settings))
        val second=db.intelligence.confirmRecognitionProfileDraft(corrected.id,corrected.revision,first.id,"publish-2")
        val third=nextProfile(template);assertEquals(3,third.version)
        assertEquals(second,db.intelligence.confirmRecognitionProfileDraft(corrected.id,corrected.revision,first.id,"publish-2"))
        assertEquals(third,db.intelligence.getRecognitionProfile(template.id))
        fails<IllegalArgumentException>{runBlocking{db.intelligence.saveRecognitionProfile(second.copy(settings=emptyList()))}}
        fails<StaleRecognitionException>{runBlocking{db.intelligence.saveRecognitionProfileDraft(draft)}}
        assertEquals(first,db.intelligence.getRecognitionProfileById(first.id))
    }

    @Test fun invalidInitialCalibrationRollsBackTemplateAndProfileTogether()=runBlocking {
        val reference=template();val id=newId();val now=System.currentTimeMillis()
        db.projects.saveProject(ProjectDetails(ProjectConfig(id,"Synthetic invalid calibration",1,QuestionnaireLanguage.ENGLISH,NumberingStrategy.GENERATED,now,now),OutputProfile(id,language=OutputHeaderLanguage.ENGLISH),emptyList()))
        val begin=db.templates.beginDraft(id)
        val bad=db.templates.saveDraft(begin.copy(pages=reference.document.pages,questions=reference.document.questions.map{q->q.copy(recognition=q.recognition!!.copy(style=MarkStyle.CIRCLED_PRINTED_NUMBER))}))
        db.imageProcessing.keepAllWarnings(bad.owner(),bad.pages.associate{it.id to PageProcessingFingerprint.of(it)},"Synthetic fixture inspection")
        fails<IllegalArgumentException>{runBlocking{db.templates.confirmDraft(id,bad.id,bad.revision)}}
        assertNull(db.projects.getProject(id)!!.project.templateVersionId)
        assertNotNull(db.templates.observeDraft(id).first());assertNull(db.templates.observeConfirmed(id).first())
        val profiles=workspace.database.read{c->c.prepareStatement("SELECT count(*) FROM pc_records WHERE project_id=? AND kind LIKE 'recognition_profile%'").use{it.setString(1,id);it.executeQuery().use{r->r.next();r.getInt(1)}}}
        assertEquals(0,profiles)
    }

    @Test fun rerunPreservesManualAnswersAndOldStatusWritesCannotRestoreHead()=runBlocking {
        val template=template();val revision=submit(template);val first=db.intelligence.claimRecognition("one",true)!!
        db.intelligence.saveAnswer(revision.submissionId,revision.id,0,AnswerValue("question:q",AnswerState.VALUE,optionIds=listOf("b")),"manual")
        val profile=nextProfile(template)
        // Publishing is not an implicit rerun and does not invalidate the pinned running version.
        assertTrue(db.intelligence.isRecognitionClaimValid(first,true))
        val second=db.intelligence.rerunRecognition(revision.submissionId,revision.id,profile.id,first.id,"rerun-2")
        val (alignment,stale)=observation(first,revision)
        assertFalse(db.intelligence.checkpointRecognition(first,alignment,listOf(stale)))
        db.intelligence.pauseRecognition(first,"late");db.intelligence.failRecognition(first,"late")
        fails<StaleRecognitionException>{runBlocking{db.intelligence.retryRecognition(first.id)}}
        db.intelligence.initializeRecognition("two")
        assertEquals(second.id,db.intelligence.getReview(revision.submissionId)!!.job!!.id)
        val claimed=db.intelligence.claimRecognition("two",true)!!;assertEquals(second.id,claimed.id)
        finish(claimed,revision)
        assertEquals(listOf("b"),db.intelligence.getReview(revision.submissionId)!!.answers!!.answers.single().optionIds)
        val third=db.intelligence.rerunRecognition(revision.submissionId,revision.id,profile.id,second.id,"rerun-3")
        assertEquals(second.id,db.intelligence.rerunRecognition(revision.submissionId,revision.id,profile.id,first.id,"rerun-2").id)
        assertEquals(third.id,db.intelligence.getReview(revision.submissionId)!!.job!!.id)
        val active=db.intelligence.claimRecognition("three",true)!!
        db.intelligence.initializeRecognition("four")
        val recovered=db.intelligence.claimRecognition("four",true)!!
        assertNotEquals(active.runToken,recovered.runToken)
        assertFalse(db.intelligence.completeRecognition(active))
    }

    @Test fun candidateAcceptanceAndFrozenExportUseActualHistoricalReference()=runBlocking {
        val template=template();val revision=submit(template);val first=db.intelligence.claimRecognition("one",true)!!
        val old=finish(first,revision)
        val unresolved=db.intelligence.createExportSnapshot(template.projectId,true,"unresolved-snapshot")
        assertEquals(listOf(old.candidate!!.candidateRef!!),unresolved.records.single().candidateRefs)
        val saved=db.intelligence.saveAnswer(revision.submissionId,revision.id,0,old.candidate!!,"accept-old")
        assertEquals(old.candidate!!.candidateRef,saved.answers.single().candidateRef)
        val profile=nextProfile(template)
        val next=db.intelligence.rerunRecognition(revision.submissionId,revision.id,profile.id,first.id,"next")
        fails<StaleRecognitionException>{runBlocking{db.intelligence.saveAnswer(revision.submissionId,revision.id,saved.version,old.candidate!!,"stale-accept")}}
        val claimed=db.intelligence.claimRecognition("two",true)!!;assertEquals(next.id,claimed.id);finish(claimed,revision,"b")
        db.intelligence.confirmReview(revision.submissionId,revision.id,saved.version,"confirm")
        val snapshot=db.intelligence.createExportSnapshot(template.projectId,false,"snapshot")
        assertEquals(listOf(old.candidate!!.candidateRef!!),snapshot.records.single().candidateRefs)
        assertEquals("01",snapshot.sheets.first().rows.single().last().value)
        assertEquals(unresolved,db.intelligence.getExportSnapshot(unresolved.id))
        db.intelligence.savePolicy(OutputFieldPolicy(template.projectId,"question:q",true))
        val privateSnapshot=db.intelligence.createExportSnapshot(template.projectId,false,"private-snapshot")
        assertTrue(privateSnapshot.records.single().candidateRefs.isEmpty());assertEquals(6,privateSnapshot.sheets.first().headers.size)
        fails<StaleRecognitionException>{runBlocking{db.intelligence.rerunRecognition(revision.submissionId,revision.id,profile.id,next.id,"confirmed-rerun")}}
        val manual=db.intelligence.saveAnswer(revision.submissionId,revision.id,saved.version,AnswerValue("question:q",AnswerState.VALUE,optionIds=listOf("b"),candidateRef=old.candidate!!.candidateRef),"manual-new")
        assertNull(manual.answers.single().candidateRef);assertEquals(AnswerSource.MANUAL,manual.answers.single().source)
        assertEquals(snapshot,db.intelligence.getExportSnapshot(snapshot.id))
        db.intelligence.confirmReview(revision.submissionId,revision.id,manual.version,"confirm-new")
        val newSnapshot=db.intelligence.createExportSnapshot(template.projectId,false,"new-snapshot")
        assertTrue(newSnapshot.records.single().candidateRefs.isEmpty())
        val root=workspace.root;workspace.close();workspace=SecureWorkspace.open(root,protector);db=DesktopRepositories(workspace)
        assertEquals(snapshot,db.intelligence.getExportSnapshot(snapshot.id));assertEquals(profile,db.intelligence.getRecognitionProfileById(profile.id))
    }

    @Test fun evidenceHashesAndSelectionRulesAreEnforcedBeforeCheckpoint()=runBlocking {
        val template=template();val revision=submit(template);val job=db.intelligence.claimRecognition("one",true)!!
        val (alignment,value)=observation(job,revision)
        val wrongSha=RecognitionBindings.bind(value.copy(sourceSha256="0".repeat(64)),job.id,job.profileId!!)
        fails<IllegalArgumentException>{runBlocking{db.intelligence.checkpointRecognition(job,alignment,listOf(wrongSha))}}
        val ambiguous=RecognitionBindings.bind(value.copy(optionObservations=value.optionObservations.map{it.copy(decision=OptionDecision.AMBIGUOUS)}),job.id,job.profileId!!)
        fails<IllegalArgumentException>{runBlocking{db.intelligence.checkpointRecognition(job,alignment,listOf(ambiguous))}}
        fails<IllegalArgumentException>{runBlocking{db.intelligence.checkpointRecognition(job,alignment.copy(status=AlignmentStatus.NOT_REQUIRED),listOf(value))}}
        assertTrue(db.intelligence.checkpointRecognition(job,alignment,listOf(value)))
        db.intelligence.saveAnswer(revision.submissionId,revision.id,0,AnswerValue("question:q",AnswerState.BLANK),"blank")
        db.intelligence.confirmReview(revision.submissionId,revision.id,1,"confirm-during-run")
        assertFalse(db.intelligence.completeRecognition(job));assertFalse(db.intelligence.isRecognitionClaimValid(job,true))
        assertEquals(RecognitionJobStatus.SUPERSEDED,db.intelligence.getReview(revision.submissionId)!!.job!!.status)
    }

    @Test fun unsupportedQueuedEngineIsBlockedWithoutPreventingHumanConfirmation()=runBlocking {
        val template=template();val revision=submit(template)
        val original=db.intelligence.getReview(revision.submissionId)!!.job!!
        db.write{put("recognition",original.id,original.projectId,original.copy(engineVersion="unsupported-engine"))}
        db.intelligence.initializeRecognition("new-process")
        assertNull(db.intelligence.claimRecognition("new-process",true))
        val blocked=db.intelligence.getReview(revision.submissionId)!!.job!!
        assertEquals(original.id,blocked.id);assertEquals(RecognitionJobStatus.BLOCKED,blocked.status)
        assertEquals("RECOGNITION_ENGINE_UNAVAILABLE",blocked.error)
        db.intelligence.saveAnswer(revision.submissionId,revision.id,0,AnswerValue("question:q",AnswerState.BLANK),"human")
        db.intelligence.confirmReview(revision.submissionId,revision.id,1,"human-confirm")
        assertEquals(1,db.intelligence.createExportSnapshot(template.projectId,false,"manual-export").records.size)
    }

    @Test fun manualOnlyProfileDoesNotBlockHumanWorkflowAndAllProfileRowsAreProjectOwned()=runBlocking {
        val template=template(false);val revision=submit(template);val job=db.intelligence.claimRecognition("one",true)!!
        val (alignment,rogue)=observation(job,revision)
        fails<IllegalArgumentException>{runBlocking{db.intelligence.checkpointRecognition(job,alignment,listOf(rogue))}}
        val page=revision.pages.single()
        val manual=RecognitionBindings.bind(rogue.copy(candidate=null,optionObservations=emptyList(),reason="MANUAL_MARK_REQUIRED"),job.id,job.profileId!!)
        assertTrue(db.intelligence.checkpointRecognition(job,alignment.copy(status=AlignmentStatus.NOT_REQUIRED,templateToImage=emptyList()),listOf(manual)))
        assertTrue(db.intelligence.completeRecognition(job))
        val answer=db.intelligence.saveAnswer(revision.submissionId,revision.id,0,AnswerValue("question:q",AnswerState.VALUE,optionIds=listOf("a")),"human")
        db.intelligence.confirmReview(revision.submissionId,revision.id,answer.version,"confirm")
        assertEquals(1,db.intelligence.createExportSnapshot(template.projectId,false,"export").records.size)
        nextProfile(template);val pendingDraft=db.intelligence.beginRecognitionProfileDraft(template.id)
        val rowsBefore=workspace.database.read{c->c.prepareStatement("SELECT count(*) FROM pc_records WHERE project_id=? AND kind LIKE 'recognition_profile%'").use{it.setString(1,template.projectId);it.executeQuery().use{r->r.next();r.getInt(1)}}}
        assertTrue(rowsBefore>=5)
        db.maintenance.requestDeletion(template.projectId)
        fails<ProjectDeletedException>{runBlocking{db.intelligence.saveRecognitionProfileDraft(pendingDraft)}}
        fails<ProjectDeletedException>{runBlocking{db.intelligence.confirmRecognitionProfileDraft(pendingDraft.id,pendingDraft.revision,pendingDraft.baseProfileId,"late-publish")}}
        fails<ProjectDeletedException>{runBlocking{db.intelligence.rerunRecognition(revision.submissionId,revision.id,job.profileId!!,job.id,"late-rerun")}}
        db.maintenance.resumePendingDeletions()
        val left=workspace.database.read{c->c.prepareStatement("SELECT count(*) FROM pc_records WHERE project_id=?").use{it.setString(1,template.projectId);it.executeQuery().use{r->r.next();r.getInt(1)}}}
        assertEquals(0,left);assertTrue(db.intelligence.observeRecognitionJobs(template.projectId).first().isEmpty())
    }
}
