package com.phoneform.desktop.vision

import com.phoneform.ai.core.model.*
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDate
import kotlin.math.*
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

data class RuntimeStatus(val ocrAvailable: Boolean, val openCvAvailable: Boolean,
    val missingModels: List<String>, val errorCodes: List<String>)
data class RecognitionResult(val alignment: PageAlignment, val observations: List<FieldObservation>)
data class ProcessedImage(val image: BufferedImage, val quality: PageQualityReport)

/** Local CPU processing. All returned values are candidates until a human confirms the revision. */
class DesktopVision(private val modelDir: Path, private val reader: OcrEngine = Tess4jOcrEngine(modelDir)) {
    fun initialize(): RuntimeStatus {
        val missing = Tess4jOcrEngine.requiredModels.filter { !Files.isRegularFile(modelDir.resolve("$it.traineddata")) }
        val cv = OpenCvRuntime.load()
        val ocr = Tess4jOcrEngine.nativeAvailable()
        return RuntimeStatus(ocr && missing.isEmpty(), cv, missing,
            buildList { if (!cv) add("OPENCV_UNAVAILABLE"); if (!ocr) add("OCR_NATIVE_UNAVAILABLE"); if (missing.isNotEmpty()) add("OCR_MODELS_MISSING") })
    }

    fun analyzeTemplate(page: SamplePage, image: BufferedImage, language: QuestionnaireLanguage,
        scope: NormalizedRect? = null): List<QuestionDefinition> {
        require(image.width == page.width && image.height == page.height) { "PAGE_DIMENSIONS_CHANGED" }
        val requested = scope ?: NormalizedRect(0f, 0f, 1f, 1f)
        require(requested.isValid()) { "INVALID_ANALYSIS_SCOPE" }
        checkCancelled()
        val crop = crop(image, requested)
        val lines = reader.read(crop.image, language).map { it.toPageCoordinates(crop.region) }
        checkCancelled()
        val layout = runCatching { OpenCvGeometry.layout(image, lines) }
            .getOrElse { PrintedLayoutGeometry(emptyList(), emptyList()) }
        return TemplateCandidateDetector.detect(page, lines, emptyList(), layout.marks, layout.answerAreas,
            crop.region, layout.independentAnswerBoxes).map { it.question }
    }

    fun recognize(templatePage: SamplePage, templateImage: BufferedImage, submittedPage: SamplePage,
        image: BufferedImage, questions: List<QuestionDefinition>, language: QuestionnaireLanguage): RecognitionResult {
        require(questions.all { it.pageId == templatePage.id }) { "QUESTION_PAGE_MISMATCH" }
        require(templateImage.width == templatePage.width && templateImage.height == templatePage.height &&
            image.width == submittedPage.width && image.height == submittedPage.height) { "PAGE_DIMENSIONS_CHANGED" }
        checkCancelled()
        val geometry = if (templatePage.noExtraction || questions.isEmpty()) GeometryResult(emptyList(), null, "NO_REQUESTED_REGIONS")
            else try { OpenCvGeometry.align(templateImage, image, questions) }
                catch (_: LocalVisionUnavailableException) { GeometryResult(emptyList(), null, "LOCAL_CV_UNAVAILABLE") }
                catch (_: LinkageError) { GeometryResult(emptyList(), null, "LOCAL_CV_UNAVAILABLE") }
        val alignment = PageAlignment(templatePage.id, submittedPage.id, submittedPage.revision,
            if (geometry.aligned) AlignmentStatus.ALIGNED else AlignmentStatus.FAILED,
            geometry.templateToImage, geometry.reason, VERSION)
        val processing = if (geometry.aligned) process(submittedPage, image) else null
        val enhancedAligned = if (processing != null && processing.image !== image && geometry.aligned) {
            try { OpenCvGeometry.warpFromCheckpoint(templateImage, processing.image, geometry.templateToImage).warped }
            finally { processing.image.flush() }
        } else null
        val observations = try { questions.map { question ->
            checkCancelled()
            val base = FieldObservation("question:${question.id}", templatePage.id, submittedPage.id, submittedPage.revision,
                evidenceRegion = question.answerRegion ?: question.region, engineVersion = VERSION,
                processingFingerprint = PageProcessingFingerprint.of(submittedPage))
            if (!geometry.aligned) base.copy(reason = "ALIGNMENT_FAILED:${geometry.reason}", evidenceRegion = null)
            else when (question.type) {
                QuestionType.SINGLE_CHOICE, QuestionType.MULTIPLE_CHOICE -> choice(geometry.warped!!, question, base)
                QuestionType.NUMBER, QuestionType.DATE -> try {
                    val original = scalar(geometry.warped!!, question, base, language)
                    val alternative = enhancedAligned?.let { scalar(it, question, base, language) }
                    reconcileScalar(original, alternative,
                        processing?.quality?.issues?.any { it.severity != PageQualitySeverity.INFO } == true,
                        submittedPage.processing.mode == PageProcessingMode.AUTO_LIGHT && alternative == null,
                        processing?.quality?.assessment != PageQualityAssessment.ASSESSED)
                }
                    catch (_: LinkageError) { base.copy(reason = "LOCAL_OCR_UNAVAILABLE") }
                    catch (interrupted: java.util.concurrent.CancellationException) { throw interrupted }
                    catch (_: Exception) { base.copy(reason = "LOCAL_OCR_UNAVAILABLE") }
                QuestionType.TEXT -> base.copy(reason = "MANUAL_TEXT_REQUIRED")
            }
        } } finally { enhancedAligned?.flush(); geometry.warped?.flush() }
        return RecognitionResult(alignment, observations.map { observation -> observation.copy(evidenceRegion =
            observation.evidenceRegion?.let { evidenceInSubmittedImage(it, geometry.templateToImage) }) })
    }

    private fun choice(image: BufferedImage, question: QuestionDefinition, base: FieldObservation): FieldObservation {
        val marks = question.options.map { option -> option.region?.let { OpenCvGeometry.marked(image, it) } }
        if (marks.isEmpty() || marks.any { it == null }) return base.copy(reason = "AMBIGUOUS_MARKS")
        val selected = question.options.filterIndexed { index, _ -> marks[index] == true }.map { it.id }
        if (selected.isEmpty()) return base.copy(candidate = AnswerValue(base.targetKey, AnswerState.BLANK,
            source = AnswerSource.MACHINE_ACCEPTED), reason = "BLANK_REQUIRES_CONFIRMATION")
        if (question.type == QuestionType.SINGLE_CHOICE && selected.size != 1) return base.copy(reason = "MULTIPLE_SINGLE_CHOICE_MARKS")
        return base.copy(candidate = AnswerValue(base.targetKey, AnswerState.VALUE, optionIds = selected,
            source = AnswerSource.MACHINE_ACCEPTED), requiresReview = true,
            reason = if (question.matrixId == null) "MARKS_REQUIRE_CONFIRMATION" else "MATRIX_MARKS_REQUIRE_CONFIRMATION")
    }

    private fun scalar(image: BufferedImage, question: QuestionDefinition, base: FieldObservation,
        language: QuestionnaireLanguage): FieldObservation {
        val region = question.answerRegion?.takeIf { it.isValid() } ?: return base.copy(reason = "ANSWER_REGION_MISSING")
        val raw = reader.read(crop(image, region).image, language).joinToString("\n") { it.text }.trim()
        val value = when (question.type) {
            QuestionType.NUMBER -> raw.takeIf { it.matches(Regex("[+-]?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)")) && it.toBigDecimalOrNull() != null }
            QuestionType.DATE -> parseDate(raw)
            else -> null
        }
        return if (value == null) base.copy(rawText = raw, reason = if (raw.isBlank()) "NO_READABLE_TEXT" else "INVALID_${question.type}")
            else base.copy(rawText = raw, candidate = AnswerValue(base.targetKey, AnswerState.VALUE, text = value,
                source = AnswerSource.MACHINE_ACCEPTED), requiresReview = true, reason = "OCR_REQUIRES_CONFIRMATION")
    }

    fun process(image: BufferedImage, config: PageProcessingConfig): ProcessedImage = processImage(image, config, imageHash(image))
    fun process(page: SamplePage, image: BufferedImage): ProcessedImage = processImage(image, page.processing, PageProcessingFingerprint.of(page))

    private fun processImage(image: BufferedImage, config: PageProcessingConfig, fingerprint: String): ProcessedImage {
        checkCancelled()
        var report = try { DesktopImageProcessor.assess(image, fingerprint) }
            catch (_: LinkageError) { unavailableQuality(fingerprint) }
            catch (_: LocalVisionUnavailableException) { unavailableQuality(fingerprint) }
            catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
            catch (_: Exception) { unavailableQuality(fingerprint) }
        checkCancelled()
        var result = image
        if (config.mode == PageProcessingMode.AUTO_LIGHT) {
            require(config.parameterVersion == DesktopImageProcessor.PARAMETER_VERSION) { "UNSUPPORTED_PROCESSING_PARAMETERS" }
            try { result = DesktopImageProcessor.enhance(image) }
            catch (_: LinkageError) { report = unavailableQuality(fingerprint) }
            catch (_: LocalVisionUnavailableException) { report = unavailableQuality(fingerprint) }
            catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
            catch (_: Exception) { report = unavailableQuality(fingerprint) }
        }
        checkCancelled()
        return ProcessedImage(result, report.copy(inputSha256 = imageHash(image)))
    }

    fun rotate(image: BufferedImage, degrees: Int): BufferedImage {
        require(degrees % 90 == 0) { "INVALID_ROTATION" }
        val angle = ((degrees % 360) + 360) % 360
        val result = BufferedImage(if (angle % 180 == 0) image.width else image.height,
            if (angle % 180 == 0) image.height else image.width, BufferedImage.TYPE_INT_RGB)
        result.createGraphics().useGraphics { g ->
            g.color = Color.WHITE; g.fillRect(0, 0, result.width, result.height)
            when (angle) { 90 -> g.translate(result.width, 0); 180 -> g.translate(result.width, result.height); 270 -> g.translate(0, result.height) }
            g.rotate(Math.toRadians(angle.toDouble())); g.drawImage(image, 0, 0, null)
        }
        return result
    }

    /** Corners are clockwise top-left, top-right, bottom-right, bottom-left in source coordinates. */
    fun rectify(image: BufferedImage, corners: List<NormalizedPoint>): BufferedImage {
        require(corners.size == 4 && corners.all { it.x.isFinite() && it.y.isFinite() && it.x in 0f..1f && it.y in 0f..1f }) { "INVALID_CORNERS" }
        val points = corners.map { Point(it.x * (image.width - 1.0), it.y * (image.height - 1.0)) }
        val cross = points.indices.map { i ->
            val a = points[i]; val b = points[(i + 1) % 4]; val c = points[(i + 2) % 4]
            (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
        }
        require(cross.all { it > 1.0 }) { "INVALID_CORNERS" }
        OpenCvGeometry.requireAvailable()
        fun distance(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)
        val width = max(distance(points[0], points[1]), distance(points[2], points[3])).roundToInt().coerceAtLeast(1)
        val height = max(distance(points[0], points[3]), distance(points[1], points[2])).roundToInt().coerceAtLeast(1)
        val source = Mat(); val destination = Mat()
        val from = MatOfPoint2f(*points.toTypedArray())
        val to = MatOfPoint2f(Point(0.0, 0.0), Point(width - 1.0, 0.0), Point(width - 1.0, height - 1.0), Point(0.0, height - 1.0))
        val transform = Imgproc.getPerspectiveTransform(from, to)
        return try {
            OpenCvImages.toMat(image, source)
            Imgproc.warpPerspective(source, destination, transform, Size(width.toDouble(), height.toDouble()), Imgproc.INTER_LINEAR,
                Core.BORDER_CONSTANT, Scalar(255.0, 255.0, 255.0, 255.0))
            BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { OpenCvImages.toImage(destination, it) }
        } finally { source.release(); destination.release(); from.release(); to.release(); transform.release() }
    }

    /** Normalized current-page to corrected-page transform, suitable for composition and recovery. */
    fun rectificationMatrix(corners: List<NormalizedPoint>): List<Float> {
        require(corners.size == 4 && corners.all { it.x.isFinite() && it.y.isFinite() && it.x in 0f..1f && it.y in 0f..1f }) { "INVALID_CORNERS" }
        OpenCvGeometry.requireAvailable()
        val from = MatOfPoint2f(*corners.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())
        val to = MatOfPoint2f(Point(0.0, 0.0), Point(1.0, 0.0), Point(1.0, 1.0), Point(0.0, 1.0))
        val transform = Imgproc.getPerspectiveTransform(from, to)
        return try { DoubleArray(9).also { transform.get(0, 0, it) }.map { it.toFloat() }.also { require(it.all(Float::isFinite)) } }
            finally { from.release(); to.release(); transform.release() }
    }

    /** Renders from the immutable original, avoiding cumulative interpolation after repeated edits. */
    fun warpGeometry(image: BufferedImage, sourceToPage: List<Float>, width: Int, height: Int): BufferedImage {
        require(sourceToPage.size == 9 && sourceToPage.all(Float::isFinite) && width > 1 && height > 1) { "INVALID_TRANSFORM" }
        OpenCvGeometry.requireAvailable()
        val x = (image.width - 1.0).coerceAtLeast(1.0); val y = (image.height - 1.0).coerceAtLeast(1.0)
        val w = width - 1.0; val h = height - 1.0; val n = sourceToPage
        val values = doubleArrayOf(n[0] * w / x, n[1] * w / y, n[2] * w,
            n[3] * h / x, n[4] * h / y, n[5] * h, n[6] / x, n[7] / y, n[8].toDouble())
        val matrix = Mat(3, 3, CvType.CV_64F); val source = Mat(); val target = Mat()
        return try {
            matrix.put(0, 0, *values); OpenCvImages.toMat(image, source)
            Imgproc.warpPerspective(source, target, matrix, Size(width.toDouble(), height.toDouble()), Imgproc.INTER_LINEAR,
                Core.BORDER_CONSTANT, Scalar(255.0, 255.0, 255.0, 255.0))
            BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { OpenCvImages.toImage(target, it) }
        } finally { matrix.release(); source.release(); target.release() }
    }

    private data class Crop(val image: BufferedImage, val region: NormalizedRect)
    private fun crop(image: BufferedImage, region: NormalizedRect): Crop {
        require(region.isValid())
        val left = floor(region.left * image.width).toInt().coerceIn(0, image.width - 1)
        val top = floor(region.top * image.height).toInt().coerceIn(0, image.height - 1)
        val right = ceil(region.right * image.width).toInt().coerceIn(left + 1, image.width)
        val bottom = ceil(region.bottom * image.height).toInt().coerceIn(top + 1, image.height)
        return Crop(image.getSubimage(left, top, right - left, bottom - top),
            NormalizedRect(left.toFloat() / image.width, top.toFloat() / image.height, right.toFloat() / image.width, bottom.toFloat() / image.height))
    }

    private fun unavailableQuality(fingerprint: String) = PageQualityReport(fingerprint, VERSION,
        listOf(PageQualityIssue(PageQualityIssueCode.ENHANCEMENT_UNAVAILABLE, PageQualitySeverity.WARNING, detail = "BASE_IMAGE_RETAINED")),
        assessment = PageQualityAssessment.UNKNOWN)

    companion object {
        const val VERSION = "tesseract-5.5_opencv-4.12.0_layout-4"
        /** Review displays the submitted page, so evidence must use its normalized coordinates. */
        internal fun evidenceInSubmittedImage(region: NormalizedRect, matrix: List<Double>): NormalizedRect? {
            if (!region.isValid() || matrix.size != 9 || matrix.any { !it.isFinite() }) return null
            val points = listOf(region.left to region.top, region.right to region.top, region.right to region.bottom, region.left to region.bottom).map { (x,y) ->
                val divisor = matrix[6]*x + matrix[7]*y + matrix[8]
                if (!divisor.isFinite() || abs(divisor) < 1E-12) return null
                ((matrix[0]*x + matrix[1]*y + matrix[2])/divisor) to ((matrix[3]*x + matrix[4]*y + matrix[5])/divisor)
            }
            if (points.any { !it.first.isFinite() || !it.second.isFinite() }) return null
            return NormalizedRect(points.minOf { it.first }.toFloat().coerceIn(0f,1f), points.minOf { it.second }.toFloat().coerceIn(0f,1f),
                points.maxOf { it.first }.toFloat().coerceIn(0f,1f), points.maxOf { it.second }.toFloat().coerceIn(0f,1f)).takeIf { it.isValid() }
        }
        internal fun reconcileScalar(base: FieldObservation, enhanced: FieldObservation?, qualityWarning: Boolean,
            enhancementUnavailable: Boolean, qualityUnknown: Boolean = false): FieldObservation {
            val compared = if (enhanced == null) base else {
                val disagreement = base.rawText != enhanced.rawText || base.candidate?.text != enhanced.candidate?.text
                base.copy(candidate = base.candidate ?: enhanced.candidate, enhancedRawText = enhanced.rawText,
                    requiresReview = base.requiresReview || enhanced.requiresReview || disagreement,
                    reason = if (disagreement) "BASE_ENHANCED_OCR_CONFLICT" else base.reason)
            }
            return when {
                compared.reason == "BASE_ENHANCED_OCR_CONFLICT" -> compared
                compared.candidate == null && compared.reason.isNotBlank() -> compared.copy(requiresReview = true)
                qualityWarning -> compared.copy(requiresReview = true, reason = "PAGE_QUALITY_REQUIRES_CONFIRMATION")
                enhancementUnavailable -> compared.copy(requiresReview = true, reason = "ENHANCEMENT_UNAVAILABLE")
                qualityUnknown -> compared.copy(requiresReview = true, reason = "PAGE_QUALITY_UNKNOWN")
                else -> compared
            }
        }
        fun parseDate(raw: String): String? {
            val groups = Regex("^([0-9]{4})(?:-|/|年)([0-9]{1,2})(?:-|/|月)([0-9]{1,2})日?$").matchEntire(raw)?.groupValues ?: return null
            return runCatching { LocalDate.of(groups[1].toInt(), groups[2].toInt(), groups[3].toInt()).toString() }.getOrNull()
        }
        private fun checkCancelled() { if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("VISION_CANCELLED") }
        private fun imageHash(image: BufferedImage): String {
            val hash = MessageDigest.getInstance("SHA-256")
            val row = IntArray(image.width); val bytes = ByteBuffer.allocate(image.width * 4)
            for (y in 0 until image.height) {
                image.getRGB(0, y, image.width, 1, row, 0, image.width)
                bytes.clear(); row.forEach { bytes.putInt(it) }; hash.update(bytes.array())
            }
            return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}

private inline fun <T> java.awt.Graphics2D.useGraphics(block: (java.awt.Graphics2D) -> T): T = try { block(this) } finally { dispose() }
