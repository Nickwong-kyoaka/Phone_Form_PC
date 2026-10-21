package com.phoneform.desktop.vision

import java.awt.image.BufferedImage
import java.io.File
import java.security.MessageDigest
import com.phoneform.ai.core.model.*
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

object DesktopImageProcessor {
        const val VERSION = "opencv-4.12.0_quality-light-1"
        const val PARAMETER_VERSION = 1

        internal fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }

        private fun unavailableIssue() = PageQualityIssue(PageQualityIssueCode.ENHANCEMENT_UNAVAILABLE,
            PageQualitySeverity.WARNING, detail = "BASE_IMAGE_RETAINED")
        private fun unavailable(fingerprint: String) = PageQualityReport(fingerprint, VERSION,
            issues = listOf(unavailableIssue()), assessment = PageQualityAssessment.UNKNOWN)

        /** Measurements are recorded; thresholds are warnings, never claims of recovered evidence. */
        fun assess(bitmap: BufferedImage, fingerprint: String): PageQualityReport {
            OpenCvGeometry.requireAvailable()
            val gray = gray(bitmap)
            val sample = Mat(); val foreground = Mat(); val edges = Mat(); val laplacian = Mat()
            val mean = MatOfDouble(); val deviation = MatOfDouble()
            try {
                val scale = min(1.0, 1024.0 / max(bitmap.width, bitmap.height))
                Imgproc.resize(gray, sample, Size(max(1.0, (bitmap.width * scale).roundToInt().toDouble()),
                    max(1.0, (bitmap.height * scale).roundToInt().toDouble())), 0.0, 0.0, Imgproc.INTER_AREA)
                Core.meanStdDev(sample, mean, deviation)
                val brightness = mean.toArray().single()
                val contrast = deviation.toArray().single()
                Imgproc.adaptiveThreshold(sample, foreground, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                    Imgproc.THRESH_BINARY_INV, 51, 10.0)
                Imgproc.Canny(sample, edges, 60.0, 140.0)
                Imgproc.Laplacian(sample, laplacian, CvType.CV_64F)
                Core.meanStdDev(laplacian, mean, deviation)
                val focus = deviation.toArray().single().pow(2)
                val ink = Core.countNonZero(foreground).toDouble() / foreground.total()
                val edgeRatio = Core.countNonZero(edges).toDouble() / edges.total()
                val tiles = mutableListOf<Tile>()
                for (row in 0..3) for (column in 0..3) {
                    val x = sample.cols() * column / 4; val y = sample.rows() * row / 4
                    val width = sample.cols() * (column + 1) / 4 - x
                    val height = sample.rows() * (row + 1) / 4 - y
                    if (width == 0 || height == 0) continue
                    val patch = sample.submat(Rect(x, y, width, height))
                    val inkPatch = foreground.submat(Rect(x, y, width, height))
                    try {
                        val bytes = ByteArray(width * height); patch.get(0, 0, bytes)
                        val histogram = IntArray(256); bytes.forEach { histogram[it.toInt() and 255]++ }
                        var count = 0; var background = 255
                        for (value in histogram.indices) { count += histogram[value]; if (count >= bytes.size * .9) { background = value; break } }
                        tiles += Tile(column, row, Core.mean(patch).`val`[0], background.toDouble(),
                            Core.countNonZero(inkPatch).toDouble() / inkPatch.total())
                    } finally { patch.release(); inkPatch.release() }
                }
                val active = tiles.count { it.ink > .003 }
                // A blank or sparse page does not provide enough printed evidence to assess focus.
                val assessed = active >= 4 && ink >= .004 && contrast >= 5.0
                val issues = mutableListOf<PageQualityIssue>()
                if (min(bitmap.width, bitmap.height) < 600) issues += PageQualityIssue(PageQualityIssueCode.LOW_RESOLUTION,
                    PageQualitySeverity.WARNING, detail = "SMALL_SOURCE_PAGE")
                if (brightness < 110) issues += PageQualityIssue(PageQualityIssueCode.LOW_LIGHT,
                    if (brightness < 65) PageQualitySeverity.SEVERE else PageQualitySeverity.WARNING)
                if (assessed) {
                    if (focus < 45) issues += PageQualityIssue(PageQualityIssueCode.BLUR,
                        if (focus < 12) PageQualitySeverity.SEVERE else PageQualitySeverity.WARNING)
                    if (contrast < 18) issues += PageQualityIssue(PageQualityIssueCode.LOW_CONTRAST, PageQualitySeverity.WARNING)
                    val spread = tiles.maxOf { it.background } - tiles.minOf { it.background }
                    if (spread > 60 && tiles.minOf { it.background } < 185) issues += PageQualityIssue(PageQualityIssueCode.SHADOW,
                        if (spread > 115 && tiles.minOf { it.background } < 110) PageQualitySeverity.SEVERE else PageQualitySeverity.WARNING,
                        detail = "UNEVEN_BACKGROUND")
                    if (brightness < 225) tiles.firstOrNull { tile ->
                        tile.mean > 245 && tile.background >= 253 && tiles.any { neighbor ->
                            abs(tile.x - neighbor.x) + abs(tile.y - neighbor.y) == 1 && neighbor.mean < 210
                        }
                    }?.let { tile -> issues += PageQualityIssue(PageQualityIssueCode.GLARE, PageQualitySeverity.WARNING,
                        NormalizedRect(tile.x / 4f, tile.y / 4f, (tile.x + 1) / 4f, (tile.y + 1) / 4f), "SUSPECTED_BRIGHT_PATCH") }
                }
                return PageQualityReport(fingerprint, VERSION, issues,
                    mapOf("brightness" to brightness, "contrastStdDev" to contrast, "laplacianVariance" to focus,
                        "foregroundFraction" to ink, "edgeFraction" to edgeRatio, "activeTiles" to active.toDouble()),
                    assessment = if (assessed) PageQualityAssessment.ASSESSED else PageQualityAssessment.UNKNOWN)
            } finally {
                gray.release(); sample.release(); foreground.release(); edges.release(); laplacian.release(); mean.release(); deviation.release()
            }
        }

        /** Fixed v1 parameters. Every operation preserves dimensions and the base page unit square. */
        fun enhance(bitmap: BufferedImage): BufferedImage {
            OpenCvGeometry.requireAvailable()
            val original = gray(bitmap); val bright = Mat(); val smooth = Mat(); val local = Mat()
            val blended = Mat(); val blurred = Mat(); val rgba = Mat()
            val clahe = Imgproc.createCLAHE(1.3, Size(8.0, 8.0))
            try {
                val offset = ((190.0 - Core.mean(original).`val`[0]) * .08).coerceIn(0.0, 12.0)
                original.convertTo(bright, CvType.CV_8U, 1.0, offset)
                Imgproc.bilateralFilter(bright, smooth, 3, 12.0, 2.0)
                clahe.apply(smooth, local)
                Core.addWeighted(bright, .7, local, .3, 0.0, blended)
                Imgproc.GaussianBlur(blended, blurred, Size(3.0, 3.0), .8)
                val size = bitmap.width * bitmap.height
                val sourceBytes = ByteArray(size); val outputBytes = ByteArray(size); val blurredBytes = ByteArray(size)
                original.get(0, 0, sourceBytes); blended.get(0, 0, outputBytes); blurred.get(0, 0, blurredBytes)
                for (index in outputBytes.indices) {
                    val source = sourceBytes[index].toInt() and 255
                    val value = outputBytes[index].toInt() and 255
                    val unsharp = ((value - (blurredBytes[index].toInt() and 255)) * .18).coerceIn(-6.0, 6.0)
                    // Cap both the halo and illumination change; protect existing thin, dark strokes.
                    val upper = min(255, source + if (source < 96) 10 else 28)
                    val lower = max(0, source - 20)
                    outputBytes[index] = (value + unsharp).roundToInt().coerceIn(lower, upper).toByte()
                }
                blended.put(0, 0, outputBytes)
                Imgproc.cvtColor(blended, rgba, Imgproc.COLOR_GRAY2RGBA)
                return BufferedImage(bitmap.width, bitmap.height, BufferedImage.TYPE_INT_RGB).also { OpenCvImages.toImage(rgba, it) }
            } finally {
                original.release(); bright.release(); smooth.release(); local.release(); blended.release(); blurred.release(); rgba.release()
                clahe.collectGarbage(); clahe.clear()
            }
        }

        private data class Tile(val x: Int, val y: Int, val mean: Double, val background: Double, val ink: Double)
        private fun gray(bitmap: BufferedImage): Mat {
            val rgba = Mat(); val result = Mat()
            try { OpenCvImages.toMat(bitmap, rgba); Imgproc.cvtColor(rgba, result, Imgproc.COLOR_RGBA2GRAY) }
            catch (failure: Throwable) { result.release(); throw failure }
            finally { rgba.release() }
            return result
        }
}
