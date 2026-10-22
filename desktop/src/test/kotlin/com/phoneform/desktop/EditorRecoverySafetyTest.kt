package com.phoneform.desktop

import com.phoneform.ai.core.model.*
import com.phoneform.desktop.security.SecureWorkspace
import com.google.gson.JsonNull
import com.google.gson.JsonObject
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

class EditorRecoverySafetyTest {
    @get:Rule val folder=TemporaryFolder()

    @Test fun delayedRecoveryBlocksEditsAndCommitUntilTheSavedInputHasLoaded()=runBlocking {
        val read=CompletableDeferred<String?>();val writes=mutableListOf<String>()
        val holder=RecoverableEditorState("initial",{it},{it},{read.await()},{writes+=it})
        val loading=launch(start=CoroutineStart.UNDISPATCHED){holder.restore()}
        assertEquals(RecoveryLoadStatus.LOADING,holder.status)
        assertBlocked {holder.requireReady()}
        assertBlocked {holder.value="premature replacement"}
        assertTrue(writes.isEmpty())
        read.complete("exact retained input");loading.join()
        assertEquals(RecoveryLoadStatus.READY,holder.status)
        assertEquals("exact retained input",holder.value)
        holder.requireReady();holder.value="reviewed input"
        assertEquals(listOf("reviewed input"),writes)
    }

    @Test fun readFailureRetainsTheSourceAndRetryLoadsItWithoutWritingInitialDefaults()=runBlocking {
        var fail=true;val writes=mutableListOf<String>()
        val holder=RecoverableEditorState("initial",{it},{it},{if(fail)error("IO_FAILURE")else "retained"},{writes+=it})
        holder.restore();assertEquals(RecoveryLoadStatus.FAILED,holder.status)
        assertBlocked {holder.requireReady()};assertTrue(writes.isEmpty())
        fail=false;holder.retry();assertEquals(RecoveryLoadStatus.LOADING,holder.status)
        holder.restore();assertEquals("retained",holder.value);assertTrue(writes.isEmpty())
    }

    @Test fun corruptTypedRecoveryIsVisibleAndCannotBeSilentlyReplaced()=runBlocking {
        val original="{}";val writes=mutableListOf<String>()
        val q=QuestionDefinition("q","p",0,"1","Anonymous",QuestionType.TEXT)
        val holder=RecoverableEditorState(QuestionEditEnvelope("draft","fingerprint",q,q),::decodeQuestionRecovery,
            {editorJson.toJson(it)},{original},{writes+=it})
        holder.restore()
        assertEquals(RecoveryLoadStatus.FAILED,holder.status);assertEquals(original,holder.retainedRaw)
        assertBlocked {holder.value=QuestionEditEnvelope("draft","fingerprint",q,q.copy(title="replacement"))}
        assertTrue(writes.isEmpty())
        // Only an explicit successful discard is allowed to release the gate.
        holder.resetAfterDiscard(QuestionEditEnvelope("draft","fingerprint",q,q))
        holder.requireReady();assertNull(holder.retainedRaw)
    }

    @Test fun typedRecoveryCodecsRejectMissingAndNullNestedRequiredFieldsInsideOtherwiseValidBindings() {
        val region=NormalizedRect(.1f,.2f,.4f,.3f)
        val option=OptionDefinition("option","Anonymous option","0",region)
        val question=QuestionDefinition("q","page",0,"1","Anonymous question",QuestionType.SINGLE_CHOICE,region,region,listOf(option))
        val binding=CalibrationBinding("profile","template",mapOf("page" to "anonymous-fingerprint"))
        val questionEnvelope=QuestionEditEnvelope("draft","anonymous-fingerprint",question,question)
        val calibration=CalibrationEnvelope(binding,RecognitionProfileDraft("profile","project","template",
            pageFingerprints=binding.pageFingerprints,settings=listOf(QuestionRecognitionSettings("q",true,MarkStyle.CHECKBOX_TICK_CROSS,
                listOf(OptionMarkRegion("option",region))))))
        val matrix=MatrixEditEnvelope("draft","anonymous-fingerprint",region,rows="Anonymous row",columns="No\nYes")
        fun rejected(value:Any,decode:(String)->Any,owner:(JsonObject)->JsonObject,field:String) {
            listOf(false,true).forEach {explicitNull->
                val json=editorJson.toJsonTree(value).asJsonObject
                val target=owner(json)
                if(explicitNull)target.add(field,JsonNull.INSTANCE)else target.remove(field)
                try {decode(json.toString());fail("Required nested field $field accepted (${if(explicitNull)"null" else "missing"})")}
                catch(expected:IllegalArgumentException){/* The decoder must reject before a default form can be edited. */}
            }
        }
        // Establish that each complete envelope itself is accepted; only the required nested field changes.
        assertEquals(questionEnvelope,decodeQuestionRecovery(editorJson.toJson(questionEnvelope)))
        assertEquals(calibration,decodeCalibrationRecovery(editorJson.toJson(calibration)))
        assertEquals(matrix,decodeMatrixRecovery(editorJson.toJson(matrix)))
        rejected(questionEnvelope,::decodeQuestionRecovery,{it.getAsJsonObject("value")},"title")
        rejected(questionEnvelope,::decodeQuestionRecovery,{it.getAsJsonObject("original")},"type")
        rejected(questionEnvelope,::decodeQuestionRecovery,{it.getAsJsonObject("value").getAsJsonArray("options")[0].asJsonObject},"code")
        rejected(calibration,::decodeCalibrationRecovery,{it.getAsJsonObject("draft").getAsJsonArray("settings")[0].asJsonObject},"questionId")
        rejected(calibration,::decodeCalibrationRecovery,{it.getAsJsonObject("draft").getAsJsonArray("settings")[0].asJsonObject},"style")
        rejected(calibration,::decodeCalibrationRecovery,{it.getAsJsonObject("draft").getAsJsonArray("settings")[0].asJsonObject.getAsJsonArray("options")[0].asJsonObject},"region")
        rejected(matrix,::decodeMatrixRecovery,{it},"rows")
        rejected(matrix,::decodeMatrixRecovery,{it},"region")
    }

    private fun assertBlocked(block:()->Unit) {
        try {block();fail("Unloaded recovery was editable/committable")}
        catch(expected:IllegalStateException){assertEquals("EDITOR_RECOVERY_PENDING",expected.message)}
    }

    private suspend fun fixture(c:Controller):TemplateDraft {
        c.createProject("Anonymous recovery",null,QuestionnaireLanguage.ENGLISH,NumberingStrategy.GENERATED,OutputHeaderLanguage.ENGLISH)
        val file=folder.newFile("anonymous-${newId()}.png")
        val image=BufferedImage(400,600,BufferedImage.TYPE_INT_RGB)
        image.createGraphics().let {g->try {g.color=Color.WHITE;g.fillRect(0,0,400,600);g.color=Color.BLACK;g.drawRect(20,20,360,560)}finally{g.dispose()}}
        check(ImageIO.write(image,"png",file));image.flush()
        c.importTemplate(file,listOf(0))
        val draft=c.templateDraft();val page=draft.pages.single();val rect=NormalizedRect(.1f,.2f,.8f,.3f)
        val saved=c.saveTemplate(draft.copy(questions=listOf(QuestionDefinition("q",page.id,0,"1","Anonymous question",QuestionType.TEXT,rect,rect))))
        c.repositories.imageProcessing.keepAllWarnings(PageProcessingOwner(saved.projectId,PageProcessingOwnerKind.TEMPLATE_DRAFT,saved.id,saved.revision),
            mapOf(page.id to PageProcessingFingerprint.of(page)),"Anonymous fixture checked")
        return saved
    }
    private fun controller()=Controller(SecureWorkspace.open(folder.newFolder("workspace-${newId()}").toPath()),Path.of("../runtime/models"),startWorkers=false)

    @Test fun unmountedQuestionRecoveryBlocksConfirmationAndLocatesItsOwnerWithoutDeletingIt()=runBlocking {
        controller().use {c->
            val draft=fixture(c);val q=draft.questions.single();val page=draft.pages.single()
            val session="question:${draft.id}:${q.id}"
            val edited=q.copy(title="Retained anonymous edit")
            val raw=editorJson.toJson(QuestionEditEnvelope(draft.id,PageProcessingFingerprint.of(page),q,edited))
            c.queueEditorField(session,draft.projectId,"envelope",raw)
            // No editor hook exists, exactly as after the narrow Image tab unmounts it.
            try {c.confirmTemplate();fail("Unapplied editor input was bypassed")}
            catch(expected:IllegalStateException){assertEquals("EDITOR_RECOVERY_PENDING",expected.message)}
            assertNull(c.repositories.templates.observeConfirmed(draft.projectId).first())
            assertEquals(raw,c.loadEditorField(session,"envelope"))
            assertEquals(q.id,c.ui.value.projects.getValue(draft.projectId).templateQuestionId)
            assertEquals(page.id,c.ui.value.projects.getValue(draft.projectId).templatePageId)
            c.registerTemplateCommit(session) {
                c.saveTemplate(c.templateDraft().copy(questions=listOf(edited)));c.clearEditor(session)
            }
            c.confirmTemplate()
            assertEquals(edited,c.repositories.templates.observeConfirmed(draft.projectId).first()!!.document.questions.single())
            assertNull(c.loadEditorField(session,"envelope"))
        }
    }

    @Test fun identicalQuestionAndGroupPayloadsDoNotBlockEvenWithOldBindingMetadata()=runBlocking {
        controller().use {c->
            val draft=fixture(c);val q=draft.questions.single()
            c.queueEditorField("question:${draft.id}:${q.id}",draft.projectId,"envelope",
                editorJson.toJson(QuestionEditEnvelope("old-draft","old-page",q.copy(title="Earlier"),q)))
            val group=CalibrationEnvelope(CalibrationBinding("old","old",emptyMap()),
                RecognitionProfileDraft("old",draft.projectId,"old",settings=listOf(calibrationSettings(q))))
            c.queueEditorField("initial-calibration:${draft.id}:${q.id}",draft.projectId,"envelope",editorJson.toJson(group))
            c.requireTemplateRecoveryClean(draft)
            assertNotNull(c.loadEditorField("question:${draft.id}:${q.id}","envelope"))
            assertNotNull(c.loadEditorField("initial-calibration:${draft.id}:${q.id}","envelope"))
        }
    }

    @Test fun sameTickEditAndCommitSaveTheLatestHolderValueBeforeClearingItsEncryptedRecovery()=runBlocking {
        controller().use {c->
            val draft=fixture(c);val q=draft.questions.single();val page=draft.pages.single()
            val fingerprint=PageProcessingFingerprint.of(page);val session="question:${draft.id}:${q.id}"
            val initial=QuestionEditEnvelope(draft.id,fingerprint,q,q)
            val holder=RecoverableEditorState(initial,::decodeQuestionRecovery,{editorJson.toJson(it)},
                {c.loadEditorField(session,"envelope")},{c.queueEditorField(session,draft.projectId,"envelope",it)})
            holder.restore()
            // The rendered snapshot remains old; no recomposition occurs between this edit and hook.
            val rendered=holder.value.value
            c.registerTemplateCommit(session) {
                val committed=holder.questionForCommit(draft.id,fingerprint,q)
                c.saveTemplate(c.templateDraft().copy(questions=listOf(committed)))
                c.clearEditor(session)
            }
            holder.value=holder.value.copy(value=q.copy(title="The exact final keystroke Z"))
            assertEquals(q,rendered)
            assertTrue(c.loadEditorField(session,"envelope")!!.contains("keystroke Z"))
            c.confirmTemplate()
            assertEquals("The exact final keystroke Z",c.repositories.templates.observeConfirmed(draft.projectId).first()!!.document.questions.single().title)
            assertNull(c.loadEditorField(session,"envelope"))
        }
    }

    @Test fun commitChecksTheLatestEnvelopeBindingAndRetainsANewerStaleEdit()=runBlocking {
        val q=QuestionDefinition("q","page",0,"1","Anonymous",QuestionType.TEXT)
        val initial=QuestionEditEnvelope("draft","fingerprint",q,q)
        val written=mutableListOf<String>()
        val holder=RecoverableEditorState(initial,::decodeQuestionRecovery,{editorJson.toJson(it)},{null},{written+=it})
        holder.restore()
        holder.value=initial.copy(pageFingerprint="different-page",value=q.copy(title="Retain this edit"))
        try {holder.questionForCommit("draft","fingerprint",q);fail("An earlier rendered binding bypassed the latest envelope")}
        catch(expected:IllegalArgumentException){assertEquals("STALE_EDIT_RELOAD",expected.message)}
        assertEquals("Retain this edit",holder.value.value.title)
        assertEquals("different-page",decodeQuestionRecovery(written.single()).pageFingerprint)
    }

    @Test fun unappliedGroupAndMatrixRecoveryAreFoundWhenTheirDialogsAreClosed()=runBlocking {
        controller().use {c->
            val draft=fixture(c);val q=draft.questions.single();val page=draft.pages.single()
            val groupSession="initial-calibration:${draft.id}:${q.id}";val matrixSession="matrix:${draft.id}:${page.id}"
            val group=CalibrationEnvelope(CalibrationBinding("owner",draft.id,mapOf(page.id to PageProcessingFingerprint.of(page))),
                RecognitionProfileDraft("owner",draft.projectId,draft.id,settings=listOf(calibrationSettings(q).copy(answerRegion=NormalizedRect(.2f,.4f,.6f,.5f)))))
            c.queueEditorField(groupSession,draft.projectId,"envelope",editorJson.toJson(group))
            c.queueEditorField(matrixSession,draft.projectId,"envelope",editorJson.toJson(MatrixEditEnvelope(draft.id,PageProcessingFingerprint.of(page),
                NormalizedRect(.1f,.5f,.9f,.9f),rows="Anonymous row",columns="No\nYes",codes="0\n1")))
            val issues=c.templateRecoveryIssues(draft)
            assertEquals(setOf(groupSession,matrixSession),issues.map {it.sessionId}.toSet())
            assertEquals(q.id,issues.first {it.sessionId==groupSession}.questionId)
            assertTrue(issues.first {it.sessionId==matrixSession}.matrix)
            c.clearEditor(groupSession);c.clearEditor(matrixSession)
            c.queueEditorField(matrixSession,draft.projectId,"envelope",editorJson.toJson(MatrixEditEnvelope(draft.id,"old-page",NormalizedRect(.1f,.1f,.9f,.9f))))
            c.requireTemplateRecoveryClean(draft)
        }
    }

    @Test fun malformedRecoveryBlocksConfirmationWithoutClearingItsExactPayload()=runBlocking {
        controller().use {c->
            val draft=fixture(c);val q=draft.questions.single();val session="question:${draft.id}:${q.id}"
            c.queueEditorField(session,draft.projectId,"envelope","{invalid-json")
            try {c.requireTemplateRecoveryClean(draft);fail("Corrupt input was silently ignored")}
            catch(expected:IllegalStateException){assertEquals("EDITOR_RECOVERY_PENDING",expected.message)}
            assertEquals("{invalid-json",c.loadEditorField(session,"envelope"))
        }
    }

    @Test fun generatedMatrixRetainedAfterACrashDoesNotBlockButChangedCodesStillDo()=runBlocking {
        controller().use {c->
            val draft=fixture(c);val page=draft.pages.single();val region=NormalizedRect(.1f,.5f,.9f,.8f)
            val options=listOf(OptionDefinition("no","No","0",NormalizedRect(.1f,.5f,.5f,.8f)),
                OptionDefinition("yes","Yes","1",NormalizedRect(.5f,.5f,.9f,.8f)))
            val q=QuestionDefinition("matrix-q",page.id,1,"1","Anonymous row",QuestionType.SINGLE_CHOICE,region,region,options,"matrix",
                QuestionRecognitionSettings("matrix-q",false,MarkStyle.MANUAL_ONLY,options.map {OptionMarkRegion(it.id,it.region!!)}))
            val matrix=MatrixDefinition("matrix",page.id,region,listOf(0f,1f),listOf(0f,.5f,1f),listOf(q.id))
            val current=c.saveTemplate(draft.copy(questions=draft.questions+q,matrices=listOf(matrix)))
            val raw=editorJson.toJson(MatrixEditEnvelope(draft.id,PageProcessingFingerprint.of(page),region,
                rows="Anonymous row",columns="No\nYes",codes="0\n1"))
            val session="matrix:${draft.id}:${page.id}"
            c.queueEditorField(session,draft.projectId,"envelope",raw)
            c.requireTemplateRecoveryClean(current)
            assertEquals(raw,c.loadEditorField(session,"envelope"))
            val changed=current.copy(questions=current.questions.map {if(it.id==q.id)it.copy(options=it.options.map {o->if(o.id=="no")o.copy(code="2")else o})else it})
            assertEquals(listOf(session),c.templateRecoveryIssues(changed).map {it.sessionId})
            val changedGeometry=current.copy(questions=current.questions.map {if(it.id==q.id)it.copy(options=it.options.map {o->
                if(o.id=="no")o.copy(region=o.region!!.copy(right=o.region!!.right+.001f))else o})else it})
            assertEquals(listOf(session),c.templateRecoveryIssues(changedGeometry).map {it.sessionId})
            val changedGrid=current.copy(matrices=listOf(matrix.copy(columnBoundaries=listOf(0f,.501f,1f))))
            assertEquals(listOf(session),c.templateRecoveryIssues(changedGrid).map {it.sessionId})
        }
    }

    @Test fun calibrationZoomActionsRoundTripThroughThePersistedViewportWithoutJumping() {
        var zoom=1f;repeat(12){zoom=CanvasLimits.zoom(zoom*1.25f)}
        assertEquals(5f,zoom,0f)
        val view=CanvasViewState(zoom,.2f,-.3f,true,"BASE")
        val encoded=DesktopUiCodec.encode(DesktopUiState(projects=mapOf("anonymous" to ProjectUiState(canvasViews=mapOf("page" to view)))))
        assertEquals(view,DesktopUiCodec.decode(encoded).projects.getValue("anonymous").canvasViews.getValue("page"))
        repeat(24){zoom=CanvasLimits.zoom(zoom/1.25f)}
        assertEquals(.5f,zoom,0f);assertEquals(zoom,CanvasViewState(zoom).normalized().zoom,0f)
    }
}
