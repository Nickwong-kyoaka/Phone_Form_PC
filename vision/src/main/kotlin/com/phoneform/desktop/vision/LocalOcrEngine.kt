package com.phoneform.desktop.vision

import com.phoneform.ai.core.model.NormalizedRect
import com.phoneform.ai.core.model.QuestionnaireLanguage
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import net.sourceforge.tess4j.ITessAPI
import net.sourceforge.tess4j.TessAPI
import net.sourceforge.tess4j.Tesseract

data class OcrElement(val text: String, val region: NormalizedRect)
data class OcrLine(val text: String, val region: NormalizedRect, val elements: List<OcrElement> = emptyList(),
    val blockId: String? = null, val blockRegion: NormalizedRect? = null)

fun OcrLine.toPageCoordinates(crop: NormalizedRect): OcrLine {
    require(crop.isValid())
    fun project(rect: NormalizedRect) = NormalizedRect(
        crop.left + rect.left * (crop.right - crop.left), crop.top + rect.top * (crop.bottom - crop.top),
        crop.left + rect.right * (crop.right - crop.left), crop.top + rect.bottom * (crop.bottom - crop.top))
    return copy(region = project(region), elements = elements.map { it.copy(region = project(it.region)) },
        blockRegion = blockRegion?.let(::project))
}

/** Image-only contract: PDF source paths never reach Tesseract. */
enum class OcrPurpose { PAGE_LAYOUT, QUESTION_TEXT, NUMBER, DATE, PRINTED_DIGIT }
fun interface OcrEngine {
    fun read(image: BufferedImage, language: QuestionnaireLanguage): List<OcrLine>
    fun read(image: BufferedImage, language: QuestionnaireLanguage, purpose: OcrPurpose): List<OcrLine> = read(image, language)
}

class Tess4jOcrEngine(private val modelDir: Path) : OcrEngine {
    override fun read(image: BufferedImage, language: QuestionnaireLanguage): List<OcrLine> = read(image,language,OcrPurpose.PAGE_LAYOUT)
    override fun read(image: BufferedImage, language: QuestionnaireLanguage, purpose: OcrPurpose): List<OcrLine> {
        val models = modelNames(language)
        require(models.all { Files.isRegularFile(modelDir.resolve("$it.traineddata")) }) { "OCR_MODELS_MISSING" }
        val engine = Tesseract().apply {
            setDatapath(modelDir.toAbsolutePath().toString())
            setLanguage(models.joinToString("+"))
            setPageSegMode(when(purpose) {
                OcrPurpose.PAGE_LAYOUT -> ITessAPI.TessPageSegMode.PSM_AUTO
                OcrPurpose.QUESTION_TEXT -> ITessAPI.TessPageSegMode.PSM_SINGLE_BLOCK
                OcrPurpose.NUMBER, OcrPurpose.DATE -> ITessAPI.TessPageSegMode.PSM_SINGLE_LINE
                OcrPurpose.PRINTED_DIGIT -> ITessAPI.TessPageSegMode.PSM_SPARSE_TEXT
            })
            setOcrEngineMode(ITessAPI.TessOcrEngineMode.OEM_LSTM_ONLY)
            when(purpose) {
                OcrPurpose.NUMBER -> setVariable("tessedit_char_whitelist","0123456789+-./–—− ")
                OcrPurpose.DATE -> setVariable("tessedit_char_whitelist","0123456789-/年月日 ")
                OcrPurpose.PRINTED_DIGIT -> setVariable("tessedit_char_whitelist","0123456789")
                else -> Unit
            }
            setVariable("debug_file", if (System.getProperty("os.name").startsWith("Windows")) "NUL" else "/dev/null")
        }
        fun rect(box: java.awt.Rectangle) = NormalizedRect(
            (box.x.toFloat() / image.width).coerceIn(0f, 1f), (box.y.toFloat() / image.height).coerceIn(0f, 1f),
            ((box.x + box.width).toFloat() / image.width).coerceIn(0f, 1f),
            ((box.y + box.height).toFloat() / image.height).coerceIn(0f, 1f))
        val words = engine.getWords(listOf(image), ITessAPI.TessPageIteratorLevel.RIL_WORD).mapNotNull { word ->
            rect(word.boundingBox).takeIf { it.isValid() }?.let { OcrElement(word.text.trim(), it) }
        }
        return engine.getWords(listOf(image), ITessAPI.TessPageIteratorLevel.RIL_TEXTLINE).mapNotNull { line ->
            val area = rect(line.boundingBox)
            area.takeIf { it.isValid() && line.text.isNotBlank() }?.let {
                OcrLine(line.text.trim(), area, words.filter { word ->
                    val x = (word.region.left + word.region.right) / 2
                    val y = (word.region.top + word.region.bottom) / 2
                    x in area.left..area.right && y in area.top..area.bottom
                })
            }
        }.sortedWith(compareBy({ it.region.top }, { it.region.left }))
    }

    companion object {
        val requiredModels = listOf("eng", "chi_sim", "chi_tra")
        fun nativeAvailable(): Boolean = runCatching { TessAPI.INSTANCE.TessVersion().isNotBlank() }.getOrDefault(false)
        fun modelNames(language: QuestionnaireLanguage): List<String> = when (language) {
            QuestionnaireLanguage.ENGLISH -> listOf("eng")
            QuestionnaireLanguage.SIMPLIFIED_CHINESE -> listOf("chi_sim", "eng")
            QuestionnaireLanguage.TRADITIONAL_CHINESE -> listOf("chi_tra", "eng")
            QuestionnaireLanguage.MIXED_CHINESE -> listOf("chi_sim", "chi_tra", "eng")
        }
    }
}
