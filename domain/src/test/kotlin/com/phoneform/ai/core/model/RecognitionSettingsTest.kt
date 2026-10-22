package com.phoneform.ai.core.model

import org.junit.Assert.*
import org.junit.Test

class RecognitionSettingsTest {
    private val box = NormalizedRect(.1f,.2f,.3f,.4f)
    private val core = NormalizedRect(.16f,.26f,.24f,.34f)
    private val choice = QuestionDefinition("q", "p", 0, "1", "Pick one", QuestionType.SINGLE_CHOICE,
        box, options = listOf(OptionDefinition("a","A","0",box), OptionDefinition("b","B","01",NormalizedRect(.4f,.2f,.6f,.4f))))
    private val document = TemplateDraft("draft","project", questions = listOf(choice))
    private fun configured() = QuestionRecognitionSettings("q",true,MarkStyle.CIRCLED_PRINTED_NUMBER,
        listOf(OptionMarkRegion("a",box,core,true),OptionMarkRegion("b",NormalizedRect(.4f,.2f,.6f,.4f),NormalizedRect(.46f,.26f,.54f,.34f),true)))

    @Test fun missingSettingsRemainManualWithoutInferringFromNumericCodes() {
        val settings = RecognitionBindings.settings(document).single()
        assertFalse(settings.enabled); assertEquals(MarkStyle.MANUAL_ONLY, settings.style)
        assertTrue(RecognitionBindings.validate(document,listOf(settings)).isEmpty())
    }
    @Test fun circledDigitsRequireConfirmedContainedCoresAndExactOptionIdentities() {
        val config = configured()
        assertTrue(RecognitionBindings.validate(document,listOf(config)).isEmpty())
        assertTrue(RecognitionBindings.validate(document,listOf(config.copy(options=config.options.map { it.copy(coreConfirmed=false) }))).isNotEmpty())
        assertTrue(RecognitionBindings.validate(document,listOf(config.copy(options=config.options.take(1)))).isNotEmpty())
        assertTrue(RecognitionBindings.validate(document,listOf(config.copy(options=config.options.map { it.copy(printedCore=NormalizedRect(0f,0f,1f,1f)) }))).isNotEmpty())
        assertTrue(RecognitionBindings.validate(document,listOf(config.copy(options=config.options.map { it.copy(region=box,printedCore=core) }))).any { it.startsWith("OVERLAPPING_OPTION_REGIONS") })
    }
    @Test fun bindingRejectsConcatenationCollisionsAndTracksGeometryOrEngineChanges() {
        assertNotEquals(RecognitionBindings.hash("ab","c"),RecognitionBindings.hash("a","bc"))
        val profile=TemplateRecognitionProfile("template",RecognitionContracts.ENGINE_VERSION,emptyMap(),settings=listOf(configured()))
        assertNotEquals(RecognitionBindings.profileHash(profile),RecognitionBindings.profileHash(profile.copy(parametersVersion="other")))
        assertNotEquals(RecognitionBindings.profileHash(profile),RecognitionBindings.profileHash(profile.copy(settings=listOf(configured().copy(enabled=false)))))
    }
    @Test fun sharedOptionEdgesTolerateOnlyFloatRoundingAndStillRejectRealOverlap() {
        val first=NormalizedRect(.1f,.2f,.4f,.4f)
        val touching=NormalizedRect(.4f,.2f,.6f,.4f)
        assertFalse(RecognitionBindings.optionRegionsOverlap(first,touching))
        assertFalse(RecognitionBindings.optionRegionsOverlap(first,touching.copy(left=Math.nextDown(.4f))))
        assertTrue(RecognitionBindings.optionRegionsOverlap(first,touching.copy(left=.399f)))
        val valid=configured().copy(style=MarkStyle.CHECKBOX_TICK_CROSS,options=listOf(
            OptionMarkRegion("a",first),OptionMarkRegion("b",touching.copy(left=Math.nextDown(.4f)))))
        assertTrue(RecognitionBindings.validate(document,listOf(valid)).isEmpty())
        assertTrue(RecognitionBindings.validate(document,listOf(valid.copy(options=valid.options.map {
            if(it.optionId=="b")it.copy(region=it.region.copy(left=.399f))else it
        }))).any {it.startsWith("OVERLAPPING_OPTION_REGIONS:")})
    }
    @Test fun candidateReferenceBindsActualJobProfileAndEvidenceWithoutHashRecursion() {
        val observation=FieldObservation("question:q","p","image",2,
            candidate=AnswerValue("question:q",AnswerState.VALUE,optionIds=listOf("a")),
            optionObservations=listOf(OptionObservation("a",OptionDecision.SELECTED,box,core,metrics=mapOf("coverage" to .9))),
            sourceSha256="source",processingFingerprint="processing",engineVersion=RecognitionContracts.ENGINE_VERSION)
        val bound=RecognitionBindings.bind(observation,"job","profile")
        assertEquals(CandidateRef("job","profile","question:q",bound.evidenceHash),bound.candidate!!.candidateRef)
        assertEquals(bound.evidenceHash,RecognitionBindings.evidenceHash(bound))
        assertNotEquals(bound.evidenceHash,RecognitionBindings.evidenceHash(bound.copy(sourceSha256="another")))
        assertNotEquals(bound.evidenceHash,RecognitionBindings.evidenceHash(bound.copy(requiresReview=false)))
        assertEquals(RecognitionProvenance.VERSIONED,bound.provenance)
    }
}
