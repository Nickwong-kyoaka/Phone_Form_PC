package com.phoneform.desktop

import com.google.gson.JsonNull
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

class OrdinaryEditorRecoveryTest {
    @get:Rule val folder=TemporaryFolder()
    private fun controller(path:Path=folder.newFolder("workspace-${newId()}").toPath())=
        Controller(SecureWorkspace.open(path),Path.of("../runtime/models"),startWorkers=false)
    private fun text(c:Controller,session:String,projectId:String?,field:String,initial:String="",read:(suspend ()->String?)?=null)=
        RecoverableEditorState(initial,{it},{it},read ?: {c.loadEditorField(session,field)},{c.queueEditorField(session,projectId,field,it)})
    private suspend fun project(c:Controller,fields:List<Pair<String,FieldSource>> = emptyList()):ProjectDetails {
        c.createProject("Anonymous editor",10,QuestionnaireLanguage.ENGLISH,NumberingStrategy.PAPER_ID,OutputHeaderLanguage.ENGLISH,fields)
        return c.repositories.projects.observeProjects().first().single().let {requireNotNull(c.repositories.projects.getProject(it.id))}
    }
    private fun blocked(action:()->Unit) {
        try {action();fail("Unloaded recovery was committed")}
        catch(expected:IllegalStateException){assertEquals("EDITOR_RECOVERY_PENDING",expected.message)}
    }

    @Test fun partialWizardRestoreCannotCreateAProjectAndFinalKeystrokesAreValidatedBeforeRealSave()=runBlocking {
        val path=folder.newFolder("wizard").toPath();var savedId=""
        controller(path).use {c->
            c.queueEditorField("create-project",null,"name","Retained anonymous name")
            c.queueEditorField("create-project",null,"expected","18")
            c.flushEdits()
            val name=text(c,"create-project",null,"name")
            val finishRead=CompletableDeferred<Unit>()
            val expected=text(c,"create-project",null,"expected",read={finishRead.await();c.loadEditorField("create-project","expected")})
            name.restore()
            val reading=launch(start=CoroutineStart.UNDISPATCHED){expected.restore()}
            blocked {projectEditorValues(name,expected)}
            assertTrue(c.repositories.projects.observeProjects().first().isEmpty())
            assertEquals("18",c.loadEditorField("create-project","expected"))
            finishRead.complete(Unit);reading.join()
            val rendered=projectEditorValues(name,expected)
            name.value="Exact final name Z";expected.value="19"
            val current=projectEditorValues(name,expected)
            assertEquals("Retained anonymous name",rendered.name)
            c.createProject(current.name,current.expected,QuestionnaireLanguage.ENGLISH,NumberingStrategy.PAPER_ID,OutputHeaderLanguage.ENGLISH)
            val created=c.repositories.projects.observeProjects().first().single()
            savedId=created.id
            assertEquals("Exact final name Z",created.name);assertEquals(19,created.expectedCount)
            assertNull(c.loadEditorField("create-project","name"))
        }
        controller(path).use {c->
            val saved=requireNotNull(c.repositories.projects.getProject(savedId))
            assertEquals("Exact final name Z",saved.project.name);assertEquals(19,saved.project.expectedCount)
        }
    }

    @Test fun projectSettingsUseLatestValuesAndAnInvalidLastCountCannotTurnIntoNull()=runBlocking {
        controller().use {c->
            val project=project(c);val session="project:${project.project.id}"
            val name=text(c,session,project.project.id,"name",project.project.name)
            val expected=text(c,session,project.project.id,"expected","10")
            name.restore();expected.restore()
            val rendered=projectEditorValues(name,expected)
            name.value="Anonymous revised Z";expected.value="not an integer"
            try {projectEditorValues(name,expected);fail("An invalid final count was silently converted to null")}
            catch(error:IllegalArgumentException){assertEquals("EXPECTED_COUNT_INVALID",error.message)}
            assertEquals(rendered.name,c.repositories.projects.getProject(project.project.id)!!.project.name)
            assertEquals("not an integer",c.loadEditorField(session,"expected"))
            expected.value="21"
            val current=projectEditorValues(name,expected)
            c.repositories.projects.saveProjectSection(project.copy(project=project.project.copy(name=current.name,expectedCount=current.expected)),ProjectEditSection.PROJECT_INFO)
            c.clearEditor(session)
            val saved=c.repositories.projects.getProject(project.project.id)!!
            assertEquals("Anonymous revised Z",saved.project.name);assertEquals(21,saved.project.expectedCount)
        }
    }

    @Test fun outputSettingsWaitForAllFieldsAndPersistLatestPrivacyPoliciesAndColumnTitlesTogether()=runBlocking {
        val path=folder.newFolder("output").toPath();var id="";var fieldKey=""
        controller(path).use {c->
            val base=project(c,listOf("Anonymous field" to FieldSource.MANUAL_METADATA));id=base.project.id
            fieldKey="field:${base.fields.single().id}"
            val session="output:$id:${base.output.version}"
            val language=RecoverableEditorState(base.output.language,{OutputHeaderLanguage.valueOf(it)},{it.name},
                {c.loadEditorField(session,"language")},{c.queueEditorField(session,id,"language",it)})
            val personal=RecoverableEditorState(false,{it.toBooleanStrict()},{it.toString()},
                {c.loadEditorField(session,"personal")},{c.queueEditorField(session,id,"personal",it)})
            val policies=RecoverableEditorState(emptyList<OutputFieldPolicy>(),{decodeOutputPolicies(it,id)},{editorJson.toJson(it)},
                {c.loadEditorField(session,"policies")},{c.queueEditorField(session,id,"policies",it)})
            val layout=RecoverableEditorState(emptyList<OutputColumnLayout>(),::decodeOutputLayout,{editorJson.toJson(it)},
                {c.loadEditorField(session,"layout")},{c.queueEditorField(session,id,"layout",it)})
            language.restore();personal.restore();policies.restore()
            blocked {outputEditorValues(language,personal,policies,layout)}
            assertEquals(base.output.version,c.repositories.projects.getProject(id)!!.output.version)
            layout.restore()
            val rendered=outputEditorValues(language,personal,policies,layout)
            language.value=OutputHeaderLanguage.TRADITIONAL;personal.value=true
            policies.value=listOf(OutputFieldPolicy(id,fieldKey,true,false))
            layout.value=listOf(OutputColumnLayout(fieldKey,"Exact final header Z",0))
            val current=outputEditorValues(language,personal,policies,layout)
            assertFalse(rendered.personal);assertTrue(rendered.layout.isEmpty())
            c.repositories.projects.saveOutputConfiguration(base.copy(output=base.output.copy(language=current.language,includePersonalIdentifiers=current.personal,columnLayout=current.layout)),current.policies,base.output.version)
            c.clearEditor(session)
            val saved=c.repositories.projects.getProject(id)!!
            assertEquals(OutputHeaderLanguage.TRADITIONAL,saved.output.language)
            assertTrue(saved.output.includePersonalIdentifiers)
            assertEquals("Exact final header Z",saved.output.columnLayout.single().titleOverride)
            assertEquals(current.policies,c.repositories.intelligence.observePolicies(id).first())
        }
        controller(path).use {c->
            val saved=c.repositories.projects.getProject(id)!!
            assertEquals("Exact final header Z",saved.output.columnLayout.single().titleOverride)
            assertEquals(listOf(OutputFieldPolicy(id,fieldKey,true,false)),c.repositories.intelligence.observePolicies(id).first())
        }
    }

    @Test fun invalidRecoveryDoesNotLoseTheEncryptedSourceAndEveryRequiredAnswerFieldIsChecked()=runBlocking {
        controller().use {c->
            val project=project(c);val session="answer:anonymous:question:q:0";val key="question:q"
            c.queueEditorField(session,project.project.id,"value","{\"targetKey\":\"question:q\"}")
            c.flushEdits()
            var readFails=true
            val holder=RecoverableEditorState(AnswerValue(key),{decodeAnswerRecovery(it,key)},{editorJson.toJson(it)},
                {if(readFails)error("READ_FAILURE") else c.loadEditorField(session,"value")},{c.queueEditorField(session,project.project.id,"value",it)})
            holder.restore();assertEquals(RecoveryLoadStatus.FAILED,holder.status)
            blocked {answerEditorValue(holder,key)}
            readFails=false;holder.retry();holder.restore()
            assertEquals(RecoveryLoadStatus.FAILED,holder.status)
            assertEquals("{\"targetKey\":\"question:q\"}",holder.retainedRaw)
            blocked {answerEditorValue(holder,key)}
            assertEquals(holder.retainedRaw,c.loadEditorField(session,"value"))
            val valid=AnswerValue(key,AnswerState.VALUE,"0088",humanEdited=true)
            listOf("targetKey","state","text","optionIds","source","humanEdited").forEach {field->
                listOf(false,true).forEach {explicitNull->
                    val json=editorJson.toJsonTree(valid).asJsonObject
                    if(explicitNull)json.add(field,JsonNull.INSTANCE)else json.remove(field)
                    try {decodeAnswerRecovery(json.toString(),key);fail("Missing/null $field accepted")}
                    catch(_:IllegalArgumentException){}
                }
            }
            // Discard is explicit and occurs only after the original encrypted field is removed.
            c.clearEditor(session);holder.resetToInitialAfterDiscard()
            assertTrue(holder.ready);assertNull(c.loadEditorField(session,"value"))
        }
    }

    @Test fun malformedOutputFieldsAndForeignCandidateBindingsAreRejectedWithoutDefaults() {
        val key="question:q"
        val candidate=AnswerValue(key,AnswerState.VALUE,"8",source=AnswerSource.MACHINE_ACCEPTED,humanEdited=true,
            candidateRef=CandidateRef("job","profile",key,"evidence"))
        assertEquals(candidate,decodeAnswerRecovery(editorJson.toJson(candidate),key))
        val invalid=editorJson.toJsonTree(candidate).asJsonObject
        invalid.getAsJsonObject("candidateRef").remove("evidenceHash")
        try {decodeAnswerRecovery(invalid.toString(),key);fail("Incomplete candidate binding accepted")}
        catch(_:IllegalArgumentException){}
        listOf("[{\"projectId\":\"project\",\"targetKey\":\"field:x\",\"personalIdentifier\":true}]",
            "[{\"projectId\":\"foreign\",\"targetKey\":\"field:x\",\"personalIdentifier\":true,\"exportIncluded\":false}]").forEach {raw->
            try {decodeOutputPolicies(raw,"project");fail("Incomplete/foreign output policy accepted")}
            catch(_:IllegalArgumentException){}
        }
        listOf("[{\"key\":\"field:x\"}]","[{\"key\":\"field:x\",\"position\":1.5}]","[{\"key\":\"field:x\",\"position\":null}]").forEach {raw->
            try {decodeOutputLayout(raw);fail("Incomplete output layout accepted")}
            catch(_:IllegalArgumentException){}
        }
    }

    @Test fun discardInvalidatesAnInFlightMultiFieldReadAndItsLateFailure()=runBlocking {
        controller().use {c->
            listOf(false,true).forEach {lateFailure->
                val session="anonymous-multiple-$lateFailure"
                c.queueEditorField(session,null,"bad","not-an-integer")
                c.queueEditorField(session,null,"slow","retained earlier input")
                c.flushEdits()
                val bad=RecoverableEditorState(0,{it.toInt()},{it.toString()},{c.loadEditorField(session,"bad")},{c.queueEditorField(session,null,"bad",it)})
                val finish=CompletableDeferred<String?>()
                val slow=text(c,session,null,"slow","initial",read={finish.await()})
                bad.restore();assertEquals(RecoveryLoadStatus.FAILED,bad.status)
                val reading=launch(start=CoroutineStart.UNDISPATCHED){slow.restore()}
                val fields=EditorRecoveryFields(bad,slow)
                assertFalse(fields.ready)
                c.clearEditor(session);fields.resetAfterDiscard()
                if(lateFailure)finish.completeExceptionally(IllegalStateException("late read failure"))else finish.complete("retained earlier input")
                reading.join()
                fields.requireReady();assertEquals("initial",slow.value);assertEquals(0,bad.value)
                assertNull(c.loadEditorField(session,"bad"));assertNull(c.loadEditorField(session,"slow"))
            }
        }
    }

    @Test fun editorCallbacksUseImmediateBusyStateAndResumeOnlyAfterTheActionFinishes()=runBlocking {
        controller().use {c->
            val project=project(c);val session="project:${project.project.id}"
            val name=text(c,session,project.project.id,"name",project.project.name)
            name.restore()
            val started=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>()
            c.perform {started.complete(Unit);finish.await()}
            withTimeout(10_000){started.await()}
            assertTrue(c.state.value.busy)
            c.editIfAvailable(name){"must not replace the saving form"}
            assertEquals(project.project.name,name.value)
            assertNull(c.loadEditorField(session,"name"))
            finish.complete(Unit)
            withTimeout(10_000){while(c.state.value.busy)delay(10)}
            c.editIfAvailable(name){"accepted after save Z"}
            assertEquals("accepted after save Z",name.value)
            assertEquals("accepted after save Z",c.loadEditorField(session,"name"))
        }
    }

    private suspend fun submitted(c:Controller,targetCount:Int=1):ReviewWorkspace {
        val project=project(c)
        val image=BufferedImage(400,600,BufferedImage.TYPE_INT_RGB)
        image.createGraphics().let {g->try {g.color=Color.WHITE;g.fillRect(0,0,400,600);g.color=Color.BLACK;g.drawRect(20,20,360,560)}finally{g.dispose()}}
        val source=folder.newFile("anonymous-${newId()}.png");check(ImageIO.write(image,"png",source));image.flush()
        c.importTemplate(source,listOf(0));val initial=c.templateDraft();val page=initial.pages.single()
        val region=NormalizedRect(.1f,.2f,.8f,.4f)
        val draft=c.saveTemplate(initial.copy(questions=List(targetCount){index->QuestionDefinition(if(index==0)"q" else "q${index+1}",page.id,index,"${index+1}","Anonymous answer ${index+1}",QuestionType.TEXT,region,region)}))
        c.repositories.imageProcessing.keepAllWarnings(PageProcessingOwner(project.project.id,PageProcessingOwnerKind.TEMPLATE_DRAFT,draft.id,draft.revision),
            mapOf(page.id to PageProcessingFingerprint.of(page)),"Anonymous source checked")
        c.confirmTemplate();c.importSubmission(source,listOf(0),listOf(page.id))
        val submission=c.repositories.submissions.observeDrafts(project.project.id).first().single()
        val id=text(c,"respondent:${submission.id}",project.project.id,"value")
        val reason=text(c,"collection-confirm:${submission.id}:${submission.editRevision}",project.project.id,"repeatReason")
        id.restore();reason.restore();id.value="00001";reason.value="Anonymous reviewed source"
        val rendered=collectionEditorValues(id,reason,true)
        id.value="00009";reason.value="Exact final reason Z"
        val current=collectionEditorValues(id,reason,true)
        assertEquals("00001",rendered.paperId)
        val saved=c.repositories.submissions.saveDraft(submission.copy(respondentId=current.paperId))
        c.repositories.imageProcessing.keepAllWarnings(PageProcessingOwner(project.project.id,PageProcessingOwnerKind.SUBMISSION_DRAFT,saved.id,saved.editRevision),
            saved.pages.associate {it.image.id to PageProcessingFingerprint.of(it.image)},"Anonymous source checked")
        val revision=c.repositories.submissions.submitSubmission(saved.id,saved.editRevision,SubmissionConfirmation(true,true,current.repeatReason))
        assertEquals("00009",revision.respondentId);assertEquals("Exact final reason Z",revision.confirmation.repeatedRespondentReason)
        val preparation=requireNotNull(c.repositories.jobs.claimNext("ordinary-editor-test",true))
        assertTrue(c.repositories.jobs.checkpoint(preparation,revision.pages));assertTrue(c.repositories.jobs.complete(preparation))
        return requireNotNull(c.repositories.intelligence.getReview(saved.submissionId))
    }

    @Test fun answerAndCollectionCommitLatestValuesAndFailedAnswerSaveRetainsExactRecoveryAfterReopening()=runBlocking {
        val path=folder.newFolder("answer").toPath();var submissionId="";var failedSession=""
        controller(path).use {c->
            val review=submitted(c);submissionId=review.submission.id;val target=review.targets.single()
            val session=answerEditorSession(review,target.key)
            val holder=RecoverableEditorState(initialReviewAnswer(review,target),{decodeAnswerRecovery(it,target.key)},{editorJson.toJson(it)},
                {c.loadEditorField(session,"value")},{c.queueEditorField(session,review.project.project.id,"value",it)})
            blocked {answerEditorValue(holder,target.key)}
            holder.restore();val rendered=holder.value
            holder.value=holder.value.copy(state=AnswerState.VALUE,text="0088Z",humanEdited=true)
            val saved=c.saveAnswer(review,answerEditorValue(holder,target.key));c.clearEditor(session)
            assertEquals("",rendered.text);assertEquals("0088Z",saved.answers.single().text)
            val current=requireNotNull(c.repositories.intelligence.getReview(review.submission.id))
            failedSession=answerEditorSession(current,target.key)
            val failed=RecoverableEditorState(initialReviewAnswer(current,target),{decodeAnswerRecovery(it,target.key)},{editorJson.toJson(it)},
                {c.loadEditorField(failedSession,"value")},{c.queueEditorField(failedSession,review.project.project.id,"value",it)})
            failed.restore();failed.value=failed.value.copy(text="exact retained edit Z")
            c.flushEdits()
            c.workspace.database.transaction {connection->connection.createStatement().use {
                it.execute("CREATE TRIGGER reject_answer_save BEFORE INSERT ON pc_records WHEN NEW.kind='answers' BEGIN SELECT RAISE(ABORT,'ANSWER_SAVE_TEST'); END")
            }}
            try {c.saveAnswer(current,answerEditorValue(failed,target.key));fail("Injected save failure was ignored")}
            catch(_:java.sql.SQLException){}
            finally {c.workspace.database.transaction {connection->connection.createStatement().use {it.execute("DROP TRIGGER reject_answer_save")}}}
            assertEquals("0088Z",c.repositories.intelligence.getReview(submissionId)!!.answers!!.answers.single().text)
            assertEquals("exact retained edit Z",decodeAnswerRecovery(c.loadEditorField(failedSession,"value")!!,target.key).text)
        }
        controller(path).use {c->
            val review=c.repositories.intelligence.getReview(submissionId)!!
            assertEquals("00009",review.revision.respondentId)
            assertEquals("0088Z",review.answers!!.answers.single().text)
            assertEquals("exact retained edit Z",decodeAnswerRecovery(c.loadEditorField(failedSession,"value")!!,review.targets.single().key).text)
        }
    }

    @Test fun forcedReloadBlocksOldAnswerAndNextCallbacksUntilTheNewAnswerVersionHasLoaded()=runBlocking {
        controller().use {c->
            val before=submitted(c,2);val q1=before.targets[0];val q2=before.targets[1]
            c.refresh();c.navigate(WorkspaceSection.REVIEW).join()
            c.updateProjectUi(before.project.project.id){it.copy(reviewWorkspace=true,reviewSubmissionId=before.submission.id,reviewTargetKey=q2.key)}
            var loaded:ReviewWorkspace?=before;var reload=0;var loadedReload=0;var loading=false
            fun gate(expected:ReviewWorkspace)=ReviewEditGate {
                val ui=c.ui.value;val projectUi=ui.projects[before.project.project.id]
                currentReviewWorkspace(expected,loaded,reload,loadedReload,loading && loadedReload!=reload,ui.projectId,projectUi?.reviewSubmissionId,projectUi?.reviewWorkspace==true)
            }
            val oldGate=gate(before)
            val oldSession=answerEditorSession(before,q2.key)
            val oldHolder=RecoverableEditorState(initialReviewAnswer(before,q2),{decodeAnswerRecovery(it,q2.key)},{editorJson.toJson(it)},
                {c.loadEditorField(oldSession,"value")},{c.queueEditorField(oldSession,before.project.project.id,"value",it)})
            oldHolder.restore();assertTrue(oldGate.ready)
            // Same-version background refreshes caused by recovery-buffer saves must not drop input.
            loading=true;assertTrue(oldGate.ready);loading=false
            c.saveAnswer(before,AnswerValue(q1.key,AnswerState.VALUE,"Q1 final Z",humanEdited=true))
            reload++ // The real afterSave invalidation occurs before Compose runs a new effect.
            assertFalse(c.state.value.busy)
            var nextCalls=0
            val staleNext={oldGate.run{nextCalls++}}
            oldGate.edit(c,oldHolder){it.copy(state=AnswerState.VALUE,text="must not enter version zero")}
            staleNext();assertEquals(0,nextCalls);assertNull(c.loadEditorField(oldSession,"value"))
            try {oldGate.requireReady();fail("An old save callback bypassed explicit reload")}
            catch(expected:IllegalStateException){assertEquals("REVIEW_RELOADING",expected.message)}
            val release=CompletableDeferred<Unit>();val requested=reload
            loading=true
            val reader=launch(start=CoroutineStart.UNDISPATCHED){release.await();loaded=c.repositories.intelligence.getReview(before.submission.id);loadedReload=requested;loading=false}
            assertFalse(oldGate.ready)
            release.complete(Unit);reader.join()
            val current=requireNotNull(loaded)
            assertEquals(1,current.answers!!.version);assertFalse(oldGate.ready)
            val freshGate=gate(current);assertTrue(freshGate.ready)
            val session=answerEditorSession(current,q2.key)
            val holder=RecoverableEditorState(initialReviewAnswer(current,q2),{decodeAnswerRecovery(it,q2.key)},{editorJson.toJson(it)},
                {c.loadEditorField(session,"value")},{c.queueEditorField(session,current.project.project.id,"value",it)})
            holder.restore();freshGate.edit(c,holder){it.copy(state=AnswerState.VALUE,text="Q2 latest Z",humanEdited=true)}
            freshGate.requireReady();val saved=c.saveAnswer(current,answerEditorValue(holder,q2.key));c.clearEditor(session)
            assertEquals("Q1 final Z",saved.answers.single {it.targetKey==q1.key}.text)
            assertEquals("Q2 latest Z",saved.answers.single {it.targetKey==q2.key}.text)
            assertNull(c.loadEditorField(oldSession,"value"))
        }
    }

    @Test fun recoveryMarkerRejectsMissingNullAndInvalidVersionsBeforeVersionZeroFiltering() {
        val bad=listOf("[{}]","[{\"targetKey\":\"question:q\"}]","[{\"targetKey\":null,\"sourceVersion\":0}]",
            """[{"targetKey":" ","sourceVersion":0}]""",
            "[{\"targetKey\":\"question:q\",\"sourceVersion\":null}]","[{\"targetKey\":\"question:q\",\"sourceVersion\":-1}]",
            "[{\"targetKey\":\"question:q\",\"sourceVersion\":0.5}]","[{\"targetKey\":\"question:q\",\"sourceVersion\":\"0\"}]")
        bad.forEach {raw->
            try {decodeAnswerRecoveryMarker(raw).filter {it.sourceVersion!=0};fail("A corrupt index disappeared during version-zero filtering")}
            catch(expected:CorruptAnswerRecoveryMarker){assertEquals(raw,expected.retainedRaw)}
        }
        assertEquals(listOf(AnswerRecoveryBlock("question:q",0)),decodeAnswerRecoveryMarker("[{\"targetKey\":\"question:q\",\"sourceVersion\":0}]"))
    }

    @Test fun explicitCorruptMarkerDiscardRetainsOtherDraftsAndRescanningStillBlocksKnownDirtyInput()=runBlocking {
        controller().use {c->
            val review=submitted(c);val target=review.targets.single();val marker=answerRecoveryMarker(review.revision.id)
            val raw="[{\"targetKey\":\"${target.key}\",\"sourceVersion\":null}]"
            val currentSession=answerEditorSession(review,target.key)
            val currentRaw=editorJson.toJson(AnswerValue(target.key,AnswerState.VALUE,"Retain current draft Z",humanEdited=true))
            val orphanSession=answerEditorSession(review,target.key,9)
            c.queueEditorField(marker,review.project.project.id,"blocked",raw)
            c.queueEditorField(currentSession,review.project.project.id,"value",currentRaw)
            c.queueEditorField(orphanSession,review.project.project.id,"value","retained unlocated source")
            c.flushEdits()
            try {c.loadAnswerRecoveryBlocks(review.revision.id).filter {it.sourceVersion!=0};fail("Corrupt index was treated as empty")}
            catch(expected:CorruptAnswerRecoveryMarker){assertEquals(raw,expected.retainedRaw)}
            assertEquals(raw,c.loadEditorField(marker,"blocked"))
            c.discardCorruptAnswerRecoveryMarker(review.revision.id,raw)
            assertTrue(c.loadAnswerRecoveryBlocks(review.revision.id).isEmpty())
            assertEquals(currentRaw,c.loadEditorField(currentSession,"value"))
            assertEquals("retained unlocated source",c.loadEditorField(orphanSession,"value"))
            assertEquals(listOf(target.key),dirtyReviewRecoveryTargets(review,mapOf(target.key to currentRaw)))
            val newer="[{\"targetKey\":\"${target.key}\",\"sourceVersion\":-1}]"
            c.queueEditorField(marker,review.project.project.id,"blocked",newer)
            try {c.discardCorruptAnswerRecoveryMarker(review.revision.id,raw);fail("A stale discard cleared newer recovery")}
            catch(expected:IllegalArgumentException){assertEquals("STALE_EDIT_RELOAD",expected.message)}
            assertEquals(newer,c.loadEditorField(marker,"blocked"))
        }
    }
}
