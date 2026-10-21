package com.phoneform.desktop.vision

import com.phoneform.ai.core.model.*
import org.junit.Assert.*
import org.junit.Test

class TemplateCandidateDetectorTest {
    private val page = SamplePage("page", SampleAsset("asset", SampleKind.IMAGE, "image", "sha", 1), 0, 0, "render", "thumb", 1000, 1400)
    private fun line(text: String, top: Float) = OcrLine(text, NormalizedRect(.1f, top, .8f, top + .025f))

    @Test fun proposalsKeepStableTombstoneIdentityAndNeverParseSampleAnswers() {
        val input = listOf(line("1. Select one", .1f), line("A. Yes", .15f), line("B. No", .2f), line("2. Age", .3f), line("42", .35f))
        val first = TemplateCandidateDetector.detect(page, input, emptyList())
        val rerun = TemplateCandidateDetector.detect(page, input, emptyList())
        assertEquals(2, first.size)
        assertEquals(first.map { it.fingerprint }, rerun.map { it.fingerprint })
        assertEquals(QuestionType.SINGLE_CHOICE, first[0].question.type)
        assertEquals(listOf("A", "B"), first[0].question.options.map { it.code })
        assertEquals(QuestionType.NUMBER, first[1].question.type)
        assertFalse(first[1].question.title.contains("42"))
        assertNotEquals(first[0].fingerprint, TemplateCandidateDetector.detect(page.copy(revision = 2), input, emptyList())[0].fingerprint)
    }

    @Test fun onlyFullValidCalendarDateBecomesCandidate() {
        assertEquals("2026-01-09", DesktopVision.parseDate("2026年1月9日"))
        assertEquals("2026-10-18", DesktopVision.parseDate("2026/10/18"))
        assertNull(DesktopVision.parseDate("2026-02-30"))
        assertNull(DesktopVision.parseDate("18/10/26"))
        assertNull(DesktopVision.parseDate("2026-10"))
    }

    @Test fun horizontalChoicesProduceSeparateEditableRegions() {
        val suggestions = TemplateCandidateDetector.detect(page, listOf(line("1. Select all", .1f), line("A. First  B. Second", .16f)), emptyList())
        val options = suggestions.single().question.options
        assertEquals(listOf("First", "Second"), options.map { it.title })
        assertTrue(options[0].region!!.right < options[1].region!!.left)
    }

    @Test fun sameNumberAtSameHeightInTwoColumnsHasSeparateIdentityAndChoices() {
        fun item(text: String, x: Float, y: Float) = OcrLine(text, NormalizedRect(x, y, x + .3f, y + .025f))
        val detected = TemplateCandidateDetector.detect(page, listOf(
            item("1. Left question", .08f, .1f), item("1. 右栏题目", .58f, .1f),
            item("A. Left yes", .1f, .16f), item("B. Left no", .1f, .22f),
            item("A. 右边甲", .6f, .16f), item("B. 右边乙", .6f, .22f)), emptyList())
        assertEquals(2, detected.size)
        assertNotEquals(detected[0].fingerprint, detected[1].fingerprint)
        assertEquals(listOf("Left yes", "Left no"), detected[0].question.options.map { it.title })
        assertEquals(listOf("右边甲", "右边乙"), detected[1].question.options.map { it.title })
        assertTrue(detected[0].question.region!!.right < detected[1].question.region!!.left)
        assertTrue(detected.all { it.reason == "POSSIBLE_DUPLICATE_OR_BILINGUAL_LABEL" })
    }

    @Test fun horizontalOptionsUseActualUnevenElementBoxes() {
        val elements = listOf(
            OcrElement("A.", NormalizedRect(.1f, .2f, .13f, .23f)),
            OcrElement("Long phrase", NormalizedRect(.15f, .2f, .35f, .23f)),
            OcrElement("B.", NormalizedRect(.72f, .2f, .75f, .23f)),
            OcrElement("No", NormalizedRect(.77f, .2f, .81f, .23f)))
        val groups = TemplateCandidateDetector.splitOptions(OcrLine("A. Long phrase B. No", NormalizedRect(.1f, .2f, .81f, .23f), elements))
        assertEquals(2, groups.size)
        assertEquals(.72f, groups[1].region.left, .0001f)
        assertEquals(.35f, groups[0].region.right, .0001f)
    }

    @Test fun mergedOcrLineWithTwoPrintedNumberAnchorsSplitsAtRealColumnGap() {
        val elements = listOf(OcrElement("1.", NormalizedRect(.08f, .1f, .11f, .13f)),
            OcrElement("Age", NormalizedRect(.13f, .1f, .25f, .13f)),
            OcrElement("1.", NormalizedRect(.58f, .1f, .61f, .13f)),
            OcrElement("Date", NormalizedRect(.63f, .1f, .8f, .13f)))
        val result = TemplateCandidateDetector.detect(page,
            listOf(OcrLine("1. Age 1. Date", NormalizedRect(.08f, .1f, .8f, .13f), elements)), emptyList())
        assertEquals(listOf(QuestionType.NUMBER, QuestionType.DATE), result.map { it.question.type })
        assertEquals(2, result.map { it.fingerprint }.distinct().size)
    }

    @Test fun unnumberedLabelNeedsVisibleWritingEvidenceAndDoesNotTurnProseIntoQuestion() {
        val name = OcrLine("Name:", NormalizedRect(.1f, .2f, .25f, .23f))
        val area = NormalizedRect(.28f, .205f, .7f, .245f)
        val detected = TemplateCandidateDetector.detect(page,
            listOf(name, line("Please read all instructions carefully", .6f)), emptyList(), answerAreas = listOf(area))
        assertEquals(1, detected.size)
        assertEquals("Name", detected.single().question.title)
        assertEquals(area, detected.single().question.answerRegion)
        assertEquals("UNNUMBERED_LABEL_CANDIDATE", detected.single().reason)
    }

    @Test fun localCropMapsEveryBoxBackAndScopeDoesNotChangeAnchorIdentity() {
        val input = OcrLine("1. Age", NormalizedRect(.1f, .2f, .7f, .3f),
            listOf(OcrElement("Age", NormalizedRect(.3f, .2f, .7f, .3f))), "block", NormalizedRect(.05f, .1f, .8f, .4f))
        val scope = NormalizedRect(.5f, .4f, 1f, .9f)
        val mapped = input.toPageCoordinates(scope)
        assertEquals(.55f, mapped.region.left, .0001f)
        assertEquals(.5f, mapped.region.top, .0001f)
        assertEquals(.65f, mapped.elements.single().region.left, .0001f)
        assertEquals(.45f, mapped.blockRegion!!.top, .0001f)
        val whole = TemplateCandidateDetector.detect(page, listOf(mapped), emptyList()).single()
        val local = TemplateCandidateDetector.detect(page, listOf(mapped), emptyList(), scope = scope).single()
        assertEquals(whole.fingerprint, local.fingerprint)
        assertEquals(mapped.region, local.sourceAnchor)
    }

    @Test fun matrixWrappedSentencesAndBilingualHeadersNeverBecomeUnnumberedFieldsFromNearbyGridLines() {
        fun item(text: String, x: Float, y: Float) = OcrLine(text, NormalizedRect(x, y, x + .3f, y + .025f))
        val lines = listOf(item("English instructions", .08f, .1f), item("Other language header", .55f, .1f),
            item("1. A question wraps", .08f, .2f), item("onto another line", .08f, .24f),
            item("1. Isang tanong", .55f, .2f), item("na may pagpapatuloy", .55f, .24f),
            item("Sometimes", .55f, .3f), item("Another instruction", .08f, .35f))
        val gridAreas = lines.map { it.region.copy(top = it.region.bottom, bottom = it.region.bottom + .035f) }
        val result = TemplateCandidateDetector.detect(page, lines, emptyList(), answerAreas = gridAreas)
        assertEquals(2, result.size)
        assertTrue(result.all { it.reason == "POSSIBLE_DUPLICATE_OR_BILINGUAL_LABEL" })
        assertTrue(result[0].question.region!!.right < result[1].question.region!!.left)
    }

    @Test fun explicitChinesePlaceholderAndIndependentRightBoxRemainCandidatesWithoutCopyingFilledValue() {
        val name = OcrLine("姓名：____", NormalizedRect(.1f, .1f, .5f, .13f), listOf(
            OcrElement("姓名：", NormalizedRect(.1f, .1f, .22f, .13f)),
            OcrElement("____", NormalizedRect(.24f, .1f, .5f, .13f))))
        val label = OcrLine("Code", NormalizedRect(.1f, .3f, .23f, .33f))
        val box = NormalizedRect(.25f, .29f, .6f, .34f)
        val filled = OcrLine("Name: synthetic filled value", NormalizedRect(.1f, .5f, .7f, .53f), listOf(
            OcrElement("Name:", NormalizedRect(.1f, .5f, .24f, .53f)), OcrElement("synthetic", NormalizedRect(.27f, .5f, .4f, .53f))))
        val result = TemplateCandidateDetector.detect(page, listOf(name, label, filled), emptyList(),
            answerAreas = listOf(NormalizedRect(.26f, .5f, .7f, .54f)), independentAnswerBoxes = listOf(box))
        assertEquals(listOf("姓名", "Code", "Name"), result.map { it.question.title })
        assertEquals(box, result[1].question.answerRegion)
        assertTrue(result.none { it.question.title.contains("synthetic") })
        assertTrue(result.all { it.question.region!!.left <= it.sourceAnchor.left && it.question.region!!.right >= it.sourceAnchor.right })
    }

    @Test fun englishScalarKeywordsMustBeWholeWordsRatherThanPartsOfOrdinarySentences() {
        val proposals = TemplateCandidateDetector.detect(page, listOf(
            line("1. Manage daily life in your village", .1f),
            line("2. Candidate update", .3f), line("3. Age", .5f), line("4. Birthdate", .7f)), emptyList())
        assertEquals(listOf(QuestionType.TEXT, QuestionType.TEXT, QuestionType.NUMBER, QuestionType.DATE), proposals.map { it.question.type })
    }

    @Test fun scalarConflictQualityWarningsAndUnavailableEnhancementCannotAutoConfirm() {
        val base = FieldObservation("question:age", "p", "image", 1,
            AnswerValue("question:age", AnswerState.VALUE, "3"), "3", false)
        val changed = base.copy(candidate = base.candidate!!.copy(text = "8"), rawText = "8")
        val conflict = DesktopVision.reconcileScalar(base, changed, false, false)
        assertEquals("3", conflict.candidate?.text)
        assertEquals("8", conflict.enhancedRawText)
        assertEquals("BASE_ENHANCED_OCR_CONFLICT", conflict.reason)
        assertTrue(conflict.requiresReview)
        assertTrue(DesktopVision.reconcileScalar(base, base, true, false).requiresReview)
        assertTrue(DesktopVision.reconcileScalar(base, null, false, true).requiresReview)
        assertEquals("PAGE_QUALITY_UNKNOWN", DesktopVision.reconcileScalar(base, null, false, false, true).reason)
        assertFalse(DesktopVision.reconcileScalar(base, null, false, false).requiresReview)
    }
}
