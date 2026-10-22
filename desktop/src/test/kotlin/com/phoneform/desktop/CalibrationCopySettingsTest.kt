package com.phoneform.desktop

import com.phoneform.ai.core.model.*
import org.junit.Assert.*
import org.junit.Test

class CalibrationCopySettingsTest {
    private fun question(id:String,page:String="page",y:Float)=QuestionDefinition(id,page,0,id,"Question $id",QuestionType.SINGLE_CHOICE,
        NormalizedRect(.1f,y,.5f,y+.2f),NormalizedRect(.1f,y,.5f,y+.2f),listOf(
            OptionDefinition("$id-a","First","0",NormalizedRect(.1f,y,.3f,y+.2f)),
            OptionDefinition("$id-b","Second","2",NormalizedRect(.3f,y,.5f,y+.2f))))

    @Test fun samePageCopyMapsStableTargetOptionsWithoutCopyingCoreApproval() {
        val source=question("source",y=.1f);val target=question("target",y=.6f)
        val from=calibrationSettings(source).copy(enabled=true,style=MarkStyle.CIRCLED_PRINTED_NUMBER,
            options=source.options.map {o->OptionMarkRegion(o.id,o.region!!,o.region!!.let {NormalizedRect(it.left+.04f,it.top+.04f,it.right-.04f,it.bottom-.04f)},true)})
        val copied=copySamePageRecognitionSettings(source,target,from,calibrationSettings(target),"page-fp","page-fp")
        assertEquals(target.id,copied.questionId);assertEquals(target.options.map {it.id},copied.options.map {it.optionId})
        assertTrue(copied.options.all {!it.coreConfirmed && it.printedCore!=null && RecognitionBindings.contains(it.region,it.printedCore!!)})
        assertEquals(.6f,copied.options.first().region.top,.00001f)
        assertEquals(listOf("0","2"),target.options.map {it.code})
    }

    @Test fun copyRejectsAnotherPageChangedFingerprintOrDifferentStructure() {
        val source=question("source",y=.1f);val target=question("target",y=.6f)
        fun rejected(t:QuestionDefinition,expected:String?,actual:String):Boolean=runCatching {
            copySamePageRecognitionSettings(source,t,calibrationSettings(source),calibrationSettings(t),expected,actual)
        }.isFailure
        assertTrue(rejected(target.copy(pageId="other"),"same","same"))
        assertTrue(rejected(target,"old","new"))
        assertTrue(rejected(target.copy(options=target.options.take(1)),"same","same"))
        assertTrue(rejected(target.copy(type=QuestionType.MULTIPLE_CHOICE),"same","same"))
    }

    @Test fun toolPreferenceRoundTripsWithoutGeometryAndUnknownFutureToolFallsBack() {
        val ui=DesktopUiState(projects=mapOf("project" to ProjectUiState(recognitionTool="CORE")))
        val encoded=DesktopUiCodec.encode(ui)
        assertEquals("CORE",DesktopUiCodec.decode(encoded).projects.getValue("project").recognitionTool)
        assertFalse(encoded.contains("printedCore"));assertFalse(encoded.contains("optionRegions"))
        assertEquals("GROUP",DesktopUiCodec.decode("""{"projects":{"project":{"recognitionTool":"FUTURE"}}}""").projects.getValue("project").recognitionTool)
    }
}
