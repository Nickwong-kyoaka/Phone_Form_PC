package com.phoneform.desktop.vision

import com.phoneform.ai.core.model.*
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.*
import org.junit.Test
import org.opencv.core.Core

/** Synthetic algorithm checks are evidence of behavior, not recognition-accuracy measurements. */
class DesktopVisionTest {
    private fun page(id: String) = SamplePage(id, SampleAsset("asset-$id", SampleKind.IMAGE, "$id.png", "synthetic-$id", 1),
        0, 0, "$id.png", "$id.png", 1000, 1400)
    private fun printed(): BufferedImage = BufferedImage(1000, 1400, BufferedImage.TYPE_INT_RGB).apply {
        val g = createGraphics()
        try {
            g.color = Color.WHITE; g.fillRect(0, 0, width, height); g.color = Color.BLACK
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.font = Font("SansSerif", Font.PLAIN, 25)
            repeat(18) { row ->
                g.drawString("${row + 1}. Printed question ${row * 137 + 37} / Group ${row * 31 + 11}", 60 + row % 3 * 17, 70 + row * 60)
                g.drawString("Alpha ${row * 19}   Beta ${row + 127}  Record ${row * 47 + 5}", 350, 95 + row * 60)
            }
            g.stroke = BasicStroke(3f); g.drawRect(60, 30, 880, 1120)
        } finally { g.dispose() }
    }
    private fun choice(): QuestionDefinition = QuestionDefinition("q", "template", 0, "1", "Select", QuestionType.MULTIPLE_CHOICE,
        NormalizedRect(.05f, .84f, .7f, .97f), options = listOf(
            OptionDefinition("a", "A", "01", NormalizedRect(.1f, .88f, .16f, .923f)),
            OptionDefinition("b", "B", "02", NormalizedRect(.3f, .88f, .36f, .923f))))
    private fun addChoices(image: BufferedImage, selected: Boolean) {
        val g = image.createGraphics()
        try {
            g.color = Color.BLACK; g.stroke = BasicStroke(3f)
            g.drawRect(100, 1232, 60, 60); g.drawRect(300, 1232, 60, 60)
            if (selected) { g.stroke = BasicStroke(5f); g.drawLine(112, 1244, 148, 1280); g.drawLine(148, 1244, 112, 1280) }
        } finally { g.dispose() }
    }

    @Test fun exactPinnedNativeRuntimeLoadsAndModelAbsenceIsExplicit() {
        assertTrue("Official OpenCV 4.12.0 runtime must be prepared before tests", OpenCvRuntime.load())
        assertEquals("4.12.0", Core.getVersionString())
        val missing = Files.createTempDirectory("empty-tessdata-")
        try {
            val status = DesktopVision(missing).initialize()
            assertTrue(status.openCvAvailable); assertFalse(status.ocrAvailable)
            assertEquals(listOf("eng", "chi_sim", "chi_tra"), status.missingModels)
            assertTrue(status.errorCodes.contains("OCR_MODELS_MISSING"))
        } finally { Files.delete(missing) }
    }

    @Test fun homographyUsesDistributedPrintedEvidenceAndOmrReturnsOnlyReviewableCandidates() {
        val template = printed().also { addChoices(it, false) }
        val formal = printed().also { addChoices(it, true) }
        val transformed = BufferedImage(1000, 1400, BufferedImage.TYPE_INT_RGB)
        transformed.createGraphics().let { g ->
            try { g.color = Color.WHITE; g.fillRect(0, 0, 1000, 1400); g.rotate(Math.toRadians(1.5), 500.0, 700.0); g.drawImage(formal, 3, -4, null) }
            finally { g.dispose() }
        }
        val result = DesktopVision(Path.of("missing-models")).recognize(page("template"), template, page("formal"), transformed,
            listOf(choice()), QuestionnaireLanguage.ENGLISH)
        assertEquals(result.alignment.reason, AlignmentStatus.ALIGNED, result.alignment.status)
        assertEquals(9, result.alignment.templateToImage.size)
        val observation = result.observations.single()
        assertEquals(listOf("a"), observation.candidate?.optionIds)
        assertTrue(observation.requiresReview)
        assertEquals("MARKS_REQUIRE_CONFIRMATION", observation.reason)
    }

    @Test fun blankImageAlignmentFailsWithoutCroppingOrCreatingAnswers() {
        val blank = BufferedImage(1000, 1400, BufferedImage.TYPE_INT_RGB)
        blank.createGraphics().let { g -> try { g.color = Color.WHITE; g.fillRect(0, 0, 1000, 1400) } finally { g.dispose() } }
        val result = DesktopVision(Path.of("missing-models")).recognize(page("template"), printed(), page("formal"), blank,
            listOf(choice()), QuestionnaireLanguage.ENGLISH)
        assertEquals(AlignmentStatus.FAILED, result.alignment.status)
        assertNull(result.observations.single().candidate)
        assertNull(result.observations.single().evidenceRegion)
        assertTrue(result.observations.single().reason.startsWith("ALIGNMENT_FAILED:"))
    }

    @Test fun omrDistinguishesBlankCrossAndOverfilledAmbiguityWithoutAssumingFilledSampleIsBlank() {
        val image = printed().also { addChoices(it, true) }
        assertEquals(true, OpenCvGeometry.marked(image, choice().options[0].region!!))
        assertEquals(false, OpenCvGeometry.marked(image, choice().options[1].region!!))
        image.createGraphics().let { g -> try { g.color = Color.BLACK; g.fillRect(300, 1232, 60, 60) } finally { g.dispose() } }
        assertNull(OpenCvGeometry.marked(image, choice().options[1].region!!))
    }

    @Test fun scalarParsingRetainsRawTextAndRejectsImpossibleDatesAndFormulaText() {
        assertEquals("2026-10-21", DesktopVision.parseDate("2026年10月21日"))
        assertEquals("2024-02-29", DesktopVision.parseDate("2024/2/29"))
        assertNull(DesktopVision.parseDate("2026-02-29"))
        assertNull(DesktopVision.parseDate("=2026-10-21"))
    }

    @Test fun qualityAndLightEnhancementPreserveGeometryAndExistingThinStrokes() {
        val image = BufferedImage(1000, 1400, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().let { g ->
            try { g.color = Color(230, 230, 230); g.fillRect(0, 0, 1000, 1400); g.color = Color(50, 50, 50); g.stroke = BasicStroke(2f); g.drawLine(250, 200, 250, 1200) }
            finally { g.dispose() }
        }
        val before = image.getRGB(250, 700)
        val result = DesktopVision(Path.of("missing-models")).process(image, PageProcessingConfig(PageProcessingMode.AUTO_LIGHT))
        assertEquals(1000, result.image.width); assertEquals(1400, result.image.height)
        assertEquals(before, image.getRGB(250, 700))
        assertTrue(Color(result.image.getRGB(250, 700)).red <= 60)
        assertTrue(Color(result.image.getRGB(255, 700)).red >= 210)
        assertTrue(result.quality.inputSha256.matches(Regex("[a-f0-9]{64}")))
    }

    @Test fun cropCoordinatesAndClockwiseRotationPreserveEvidencePositions() {
        val crop = NormalizedRect(.2f, .3f, .8f, .9f)
        val line = OcrLine("text", NormalizedRect(.1f, .1f, .5f, .5f)).toPageCoordinates(crop)
        assertEquals(.26f, line.region.left, .0001f); assertEquals(.36f, line.region.top, .0001f)
        val image = BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, Color.RED.rgb) }
        val rotated = DesktopVision(Path.of("missing-models")).rotate(image, 90)
        assertEquals(10, rotated.width); assertEquals(20, rotated.height)
        assertEquals(Color.RED.rgb, rotated.getRGB(9, 0))
    }

    @Test fun crossedPerspectiveCornersAreRejectedBeforeNativeAllocation() {
        assertThrows(IllegalArgumentException::class.java) {
            DesktopVision(Path.of("missing-models")).rectify(printed(), listOf(
                NormalizedPoint(0f, 0f), NormalizedPoint(1f, 1f), NormalizedPoint(1f, 0f), NormalizedPoint(0f, 1f)))
        }
    }

    @Test fun evidenceBoundsFollowSubmittedImageHomographyAndRejectInvalidProjection() {
        val region = NormalizedRect(.1f,.2f,.3f,.4f)
        val projected = DesktopVision.evidenceInSubmittedImage(region, listOf(0.0,-1.0,1.0, 1.0,0.0,0.0, 0.0,0.0,1.0))!!
        assertEquals(.6f,projected.left,.0001f); assertEquals(.1f,projected.top,.0001f)
        assertEquals(.8f,projected.right,.0001f); assertEquals(.3f,projected.bottom,.0001f)
        assertNull(DesktopVision.evidenceInSubmittedImage(region, List(9){0.0}))
    }
}
