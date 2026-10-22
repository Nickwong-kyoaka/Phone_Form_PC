package com.phoneform.desktop.vision

import com.phoneform.ai.core.model.QuestionnaireLanguage
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Path
import org.junit.Assert.*
import org.junit.Test

/** Real local OCR from synthetic pixels with the pinned models; no recognition result is stubbed. */
class OfflineOcrTest {
    private val models: Path = Path.of(System.getProperty("phoneform.model.dir", "../runtime/models"))
    private fun image(text: String): BufferedImage = BufferedImage(1100, 180, BufferedImage.TYPE_INT_RGB).apply {
        createGraphics().let { g ->
            try {
                g.color = Color.WHITE; g.fillRect(0, 0, width, height)
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g.color = Color.BLACK; g.font = Font("Microsoft YaHei", Font.PLAIN, 64); g.drawString(text, 40, 115)
            } finally { g.dispose() }
        }
    }

    @Test fun everyBundledLanguageModelInitializesAndReadsSyntheticPixelsOffline() {
        val runtime = DesktopVision(models).initialize()
        assertTrue("Native/model runtime unavailable: ${runtime.errorCodes} ${runtime.missingModels}", runtime.ocrAvailable)
        val reader = Tess4jOcrEngine(models)
        val english = reader.read(image("12345 2026-10-21"), QuestionnaireLanguage.ENGLISH).joinToString(" ") { it.text }
        assertTrue(english, english.contains("12345"))
        assertTrue(english, english.contains("2026-10-21"))
        val simplified = reader.read(image("问卷编号 12345"), QuestionnaireLanguage.SIMPLIFIED_CHINESE).joinToString(" ") { it.text }
        val traditional = reader.read(image("問卷編號 12345"), QuestionnaireLanguage.TRADITIONAL_CHINESE).joinToString(" ") { it.text }
        assertTrue(simplified, simplified.contains("12345"))
        assertTrue(traditional, traditional.contains("12345"))
        assertTrue("Simplified OCR must retain real Chinese glyphs: $simplified", simplified.any { it in '\u4e00'..'\u9fff' })
        assertTrue("Traditional OCR must retain real Chinese glyphs: $traditional", traditional.any { it in '\u4e00'..'\u9fff' })
    }

    @Test fun localPurposesReadOnlyNumbersAndFullDatesWithoutCoercingRanges() {
        val reader=Tess4jOcrEngine(models)
        val number=reader.read(image("00001"),QuestionnaireLanguage.ENGLISH,OcrPurpose.NUMBER).joinToString(" "){it.text}.trim()
        assertEquals("00001",DesktopVision.parseNumber(number))
        val date=reader.read(image("2024/02/29"),QuestionnaireLanguage.ENGLISH,OcrPurpose.DATE).joinToString(" "){it.text}.trim()
        assertEquals("2024-02-29",DesktopVision.parseDate(date))
        val range=reader.read(image("6-7"),QuestionnaireLanguage.ENGLISH,OcrPurpose.NUMBER).joinToString(" "){it.text}.trim()
        assertTrue(range,range.contains("-"));assertNull(DesktopVision.parseNumber(range))
    }
}
