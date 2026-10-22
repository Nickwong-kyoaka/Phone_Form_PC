package com.phoneform.desktop

import com.phoneform.ai.core.model.*
import org.junit.Assert.*
import org.junit.Test

class CalibrationGeometryTest {
    private val a=OptionMarkRegion("a",NormalizedRect(.1f,.1f,.3f,.3f),NormalizedRect(.18f,.18f,.22f,.22f),true)
    private val b=OptionMarkRegion("b",NormalizedRect(.3f,.1f,.5f,.3f),NormalizedRect(.38f,.18f,.42f,.22f),true)
    private val settings=QuestionRecognitionSettings("q",true,MarkStyle.CIRCLED_PRINTED_NUMBER,listOf(a,b))
    private val grid=CalibrationGrid(NormalizedRect(.1f,.1f,.5f,.3f),listOf(0f,1f),listOf(0f,.5f,1f),listOf(CalibrationCell("q","a",0,0),CalibrationCell("q","b",0,1)))
    private val question=QuestionDefinition("q","page",0,"1","Printed question",QuestionType.SINGLE_CHOICE,
        grid.region,grid.region,listOf(OptionDefinition("a","Never","2",a.region),OptionDefinition("b","Sometimes","1",b.region)),recognition=settings)

    @Test fun movingAGroupClampsAsAUnitAndRequiresCoreReconfirmation() {
        val moved=moveGrid(grid,1f,.2f)
        assertEquals(.6f,moved.region.left,.00001f);assertEquals(1f,moved.region.right,.00001f)
        val next=changeCalibrationGrid(listOf(settings),grid,moved,true).single()
        assertEquals(listOf("a","b"),next.options.map {it.optionId})
        assertEquals(.2f,next.options[0].region.right-next.options[0].region.left,.00001f)
        assertEquals(.68f,next.options[0].printedCore!!.left,.00001f)
        assertTrue(next.options.none {it.coreConfirmed})
        assertTrue(next.options.all {RecognitionBindings.contains(it.region,it.printedCore!!)})
    }

    @Test fun dividerDragCannotCrossItsNeighborsAndNeverMovesPrintedCore() {
        val changed=changeDivider(grid,false,1,.01f)
        assertTrue(changed.columns.zipWithNext().all {it.first<it.second})
        val next=changeCalibrationGrid(listOf(settings),grid,changed,false).single()
        assertEquals(a.printedCore,next.options.first().printedCore)
        assertFalse(next.options.first().coreConfirmed)
        assertFalse(RecognitionBindings.contains(next.options.first().region,next.options.first().printedCore!!))
        assertTrue(RecognitionBindings.validate(TemplateDraft("draft","project",questions=listOf(question)),listOf(next)).any {it.startsWith("CORE_CONFIRMATION_REQUIRED:")})
        assertEquals(grid,changeDivider(grid,true,0,.7f))
    }

    @Test fun undoRedoRestoresGeometryAndSettingsTogetherThroughEncryptedEnvelopePayload() {
        val binding=CalibrationBinding("profile-draft","template",mapOf("page" to "fingerprint"))
        val envelope=CalibrationEnvelope(binding,RecognitionProfileDraft("profile-draft","project","template",pageFingerprints=binding.pageFingerprints,settings=listOf(settings)),mapOf("group" to grid))
        val moved=moveGrid(grid,.1f,.2f)
        val edited=envelope.edit(changeCalibrationGrid(envelope.draft.settings,grid,moved,true),mapOf("group" to moved))
        val restored=editorJson.fromJson(editorJson.toJson(edited),CalibrationEnvelope::class.java)
        assertEquals(envelope.draft.settings,restored.undoEdit().draft.settings)
        assertEquals(envelope.grids,restored.undoEdit().grids)
        assertEquals(edited.draft.settings,restored.undoEdit().redoEdit().draft.settings)
        assertEquals(edited.grids,restored.undoEdit().redoEdit().grids)
        assertTrue(calibrationEnvelopeMatches(restored,binding))
        assertFalse(calibrationEnvelopeMatches(restored,binding.copy(pageFingerprints=mapOf("page" to "changed"))))
    }

    @Test fun movingThreeSharedEdgeOptionsRemainsConfirmableAfterFloatRemapping() {
        val bounds=NormalizedRect(.05f,.1f,.9f,.3f)
        val width=bounds.right-bounds.left
        val options=(0 until 3).map {index->OptionDefinition("option-$index","Option $index",index.toString(),
            NormalizedRect(bounds.left+width*(index/3f),bounds.top,bounds.left+width*((index+1)/3f),bounds.bottom))}
        val setting=QuestionRecognitionSettings("three",true,MarkStyle.CHECKBOX_TICK_CROSS,
            options.map {OptionMarkRegion(it.id,it.region!!)})
        val q=question.copy(id="three",region=bounds,answerRegion=bounds,options=options,recognition=setting)
        val original=calibrationGrid(listOf(q),listOf(setting))!!
        val moved=moveGrid(original,.03f,0f)
        val changed=changeCalibrationGrid(listOf(setting),original,moved,true).single()
        assertEquals(options.map {it.id},changed.options.map {it.optionId})
        assertTrue(RecognitionBindings.validate(TemplateDraft("draft","project",questions=listOf(q)),listOf(changed)).isEmpty())
        assertFalse(RecognitionBindings.optionRegionsOverlap(changed.options[0].region,changed.options[1].region))
        val realOverlap=changed.copy(options=changed.options.mapIndexed {index,option->
            if(index==1)option.copy(region=option.region.copy(left=changed.options[0].region.right-.001f))else option
        })
        assertTrue(RecognitionBindings.validate(TemplateDraft("draft","project",questions=listOf(q)),listOf(realOverlap))
            .any {it.startsWith("OVERLAPPING_OPTION_REGIONS:")})
    }

    @Test fun dividerDragSupportsVeryUnequalWeightsWithoutAnEmptyClampRange() {
        val narrow=grid.copy(columns=listOf(0f,1f/1002,2f/1002,1f),
            cells=(0 until 3).map {CalibrationCell("q","option-$it",0,it)})
        listOf(-1f,0f,.001f,.5f,1f,2f).forEach {position->
            val changed=changeDivider(narrow,false,1,position)
            assertEquals(narrow.columns[2],changed.columns[2],0f)
            assertTrue(changed.columns.zipWithNext().all {it.first<it.second})
            assertTrue(changed.columns.all {it.isFinite() && it in 0f..1f})
        }
        assertEquals(narrow,changeDivider(narrow,false,1,Float.NaN))
        val adjacent=grid.copy(columns=listOf(0f,Float.MIN_VALUE,Math.nextUp(Float.MIN_VALUE),1f))
        assertTrue(changeDivider(adjacent,false,1,.5f).columns.zipWithNext().all {it.first<it.second})
    }

    @Test fun recoveredQuestionCannotOverwriteNewQuestionPageOrDraft() {
        val envelope=QuestionEditEnvelope("draft","page-v1",question,question.copy(title="Unsaved input"))
        assertTrue(questionEnvelopeMatches(envelope,"draft","page-v1",question))
        assertFalse(questionEnvelopeMatches(envelope,"draft","page-v2",question))
        assertFalse(questionEnvelopeMatches(envelope,"another-draft","page-v1",question))
        assertFalse(questionEnvelopeMatches(envelope,"draft","page-v1",question.copy(title="Newer saved title")))
        assertEquals("Unsaved input",envelope.value.title)
    }

    @Test fun changingArrangementPreservesCodesByStableOptionIdentityAndClearsCoreApproval() {
        val vertical=arrangeCalibrationOptions(settings,grid.region,true)
        assertEquals(settings.options.map {it.optionId},vertical.options.map {it.optionId})
        assertTrue(vertical.options.all {it.printedCore==null && !it.coreConfirmed})
        assertEquals(vertical.options[0].region.bottom,vertical.options[1].region.top,0f)
        val rebuilt=calibrationGrid(listOf(question),listOf(vertical))!!
        assertEquals(3,rebuilt.rows.size);assertEquals(2,rebuilt.columns.size)
    }

    @Test fun evidenceCoreUsesBoundHomographyAndRejectsInvalidProjection() {
        val projected=projectEvidenceRect(a.printedCore!!,listOf(1.0,0.0,.1,0.0,1.0,.2,0.0,0.0,1.0))!!
        assertEquals(.28f,projected.left,.00001f);assertEquals(.38f,projected.top,.00001f)
        assertNull(projectEvidenceRect(a.printedCore!!,listOf(1.0)))
        assertNull(projectEvidenceRect(a.printedCore!!,listOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,0.0)))
    }

    @Test fun legacyOrSupersededEvidenceCannotBeAdoptedAsCurrentMachineAnswer() {
        val base=FieldObservation("question:q","page","image",1,AnswerValue("question:q",AnswerState.BLANK,source=AnswerSource.MACHINE_ACCEPTED))
        val bound=RecognitionBindings.bind(base,"job","profile")
        val job=RecognitionJob("job","project","submission","revision",RecognitionJobStatus.READY,profileId="profile",provenance=RecognitionProvenance.VERSIONED)
        assertTrue(usableCandidate(bound,job))
        assertFalse(usableCandidate(base,job))
        assertFalse(usableCandidate(bound,job.copy(id="new-job")))
        assertFalse(usableCandidate(bound,job.copy(profileId="new-profile")))
        assertFalse(usableCandidate(bound,job.copy(status=RecognitionJobStatus.RUNNING)))
        assertFalse(usableCandidate(bound.copy(candidate=bound.candidate!!.copy(candidateRef=null)),job))
    }
}
