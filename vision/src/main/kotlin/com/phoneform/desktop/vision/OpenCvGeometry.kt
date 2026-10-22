package com.phoneform.desktop.vision

import java.awt.image.BufferedImage
import com.phoneform.ai.core.model.*


import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import kotlin.math.*

data class GeometryResult(val templateToImage: List<Double>, val warped: BufferedImage?, val reason: String) {
    val aligned get() = warped != null && templateToImage.size == 9
}
class LocalVisionUnavailableException : IllegalStateException("OPENCV_UNAVAILABLE")
data class PrintedLayoutGeometry(val marks: List<NormalizedRect>, val answerAreas: List<NormalizedRect>,
                                 val independentAnswerBoxes: List<NormalizedRect> = emptyList())

object OpenCvGeometry {
    private val loaded by lazy { OpenCvRuntime.load() }
    // Keep one bounded pair for the process. Their generated Java finalizers own deletion;
    // Algorithm.clear() is not a native destructor and ORB's Windows build rejects that call.
    private val orb by lazy { ORB.create(3000) }
    private val matcher by lazy { BFMatcher.create(Core.NORM_HAMMING, false) }
    fun requireAvailable() { if (!loaded) throw LocalVisionUnavailableException() }

    /** Rebuild aligned pixels from a persisted, evidence-matched stage checkpoint. */
    fun warpFromCheckpoint(template: BufferedImage, image: BufferedImage, templateToImage: List<Double>): GeometryResult {
        requireAvailable()
        require(templateToImage.size == 9 && templateToImage.all { it.isFinite() })
        val n = templateToImage
        val pixels = doubleArrayOf(n[0] * image.width / template.width, n[1] * image.width / template.height, n[2] * image.width,
            n[3] * image.height / template.width, n[4] * image.height / template.height, n[5] * image.height,
            n[6] / template.width, n[7] / template.height, n[8])
        val h = Mat(3, 3, CvType.CV_64F); val rgba = Mat(); val warped = Mat(); var inverse: Mat? = null
        try {
            h.put(0, 0, *pixels); OpenCvImages.toMat(image, rgba)
            inverse = h.inv()
            Imgproc.warpPerspective(rgba, warped, inverse, Size(template.width.toDouble(), template.height.toDouble()), Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar(255.0, 255.0, 255.0, 255.0))
            val result = BufferedImage(template.width, template.height, BufferedImage.TYPE_INT_RGB)
            OpenCvImages.toImage(warped, result)
            return GeometryResult(templateToImage, result, "")
        } finally { h.release(); rgba.release(); warped.release(); inverse?.release() }
    }

    /** Image evidence only; no subtraction from a potentially filled-in sample. */
    @Synchronized fun align(template: BufferedImage, image: BufferedImage, questions: List<QuestionDefinition>): GeometryResult {
        requireAvailable()
        val mats = mutableListOf<Mat>()
        fun <T : Mat> keep(value: T): T = value.also { mats += it }
        val matches = mutableListOf<MatOfDMatch>()
        try {
            val reference = keep(gray(template)); val input = keep(gray(image))
            val mask = keep(Mat(reference.size(), CvType.CV_8UC1, Scalar(255.0)))
            questions.flatMap { question -> question.options.mapNotNull { it.region } + listOfNotNull(question.answerRegion) }.forEach { region ->
                val inset = .012
                Imgproc.rectangle(mask, Point(max(0.0, region.left - inset) * reference.cols(), max(0.0, region.top - inset) * reference.rows()),
                    Point(min(1.0, region.right + inset) * reference.cols(), min(1.0, region.bottom + inset) * reference.rows()), Scalar(0.0), -1)
            }
            val referencePoints = keep(MatOfKeyPoint()); val imagePoints = keep(MatOfKeyPoint())
            val referenceDescriptors = keep(Mat()); val imageDescriptors = keep(Mat()); val emptyMask = keep(Mat())
            orb.detectAndCompute(reference, mask, referencePoints, referenceDescriptors)
            orb.detectAndCompute(input, emptyMask, imagePoints, imageDescriptors)
            if (referenceDescriptors.empty() || imageDescriptors.empty()) return GeometryResult(emptyList(), null, "INSUFFICIENT_PRINT_FEATURES")
            matcher.knnMatch(referenceDescriptors, imageDescriptors, matches, 2)
            val good = matches.mapNotNull { group -> group.toArray().takeIf { it.size >= 2 }?.let { if (it[0].distance < .7f * it[1].distance) it[0] else null } }
                .sortedBy { it.distance }.distinctBy { it.trainIdx }
            if (good.size < 16) return GeometryResult(emptyList(), null, "INSUFFICIENT_MATCHES")
            val r = referencePoints.toArray(); val s = imagePoints.toArray()
            val from = keep(MatOfPoint2f(*good.map { r[it.queryIdx].pt }.toTypedArray()))
            val to = keep(MatOfPoint2f(*good.map { s[it.trainIdx].pt }.toTypedArray()))
            val inliers = keep(Mat())
            val h = keep(Calib3d.findHomography(from, to, Calib3d.RANSAC, 4.0, inliers))
            if (h.empty()) return GeometryResult(emptyList(), null, "HOMOGRAPHY_FAILED")
            val accepted = good.indices.filter { inliers.get(it, 0)?.firstOrNull() == 1.0 }
            if (accepted.size < 12 || accepted.size.toDouble() / good.size < .55) return GeometryResult(emptyList(), null, "INCONSISTENT_MATCHES")
            val points = accepted.map { r[good[it].queryIdx].pt }
            val coverageX = (points.maxOf { it.x } - points.minOf { it.x }) / template.width
            val coverageY = (points.maxOf { it.y } - points.minOf { it.y }) / template.height
            if (coverageX < .35 || coverageY < .35) return GeometryResult(emptyList(), null, "MATCHES_TOO_LOCAL")
            val projected = keep(MatOfPoint2f()); Core.perspectiveTransform(from, projected, h)
            val projectedPoints = projected.toArray()
            val residual = sqrt(accepted.sumOf { index ->
                val a = projectedPoints[index]; val b = s[good[index].trainIdx].pt
                (a.x - b.x).pow(2) + (a.y - b.y).pow(2)
            } / accepted.size)
            if (residual > 3.5) return GeometryResult(emptyList(), null, "ALIGNMENT_RESIDUAL")
            val corners = keep(MatOfPoint2f(Point(0.0, 0.0), Point(template.width.toDouble(), 0.0), Point(template.width.toDouble(), template.height.toDouble()), Point(0.0, template.height.toDouble())))
            val projectedCorners = keep(MatOfPoint2f()); Core.perspectiveTransform(corners, projectedCorners, h)
            val polygon = projectedCorners.toArray()
            val contour = keep(MatOfPoint(*polygon))
            val area = abs(Imgproc.contourArea(contour)) / (image.width.toDouble() * image.height)
            if (!Imgproc.isContourConvex(contour) || Imgproc.contourArea(contour, true) <= 0 || area !in .3..1.3 || polygon.any { it.x / image.width !in -.1..1.1 || it.y / image.height !in -.1..1.1 })
                return GeometryResult(emptyList(), null, "IMPLAUSIBLE_PAGE_GEOMETRY")
            val data = DoubleArray(9); h.get(0, 0, data)
            // Pixel transform -> normalized template coordinates to normalized formal image coordinates.
            val matrix = listOf(data[0] * template.width / image.width, data[1] * template.height / image.width, data[2] / image.width,
                data[3] * template.width / image.height, data[4] * template.height / image.height, data[5] / image.height,
                data[6] * template.width, data[7] * template.height, data[8]).map { it / data[8] }
            if (matrix.any { !it.isFinite() }) return GeometryResult(emptyList(), null, "INVALID_TRANSFORM")
            val rgba = keep(Mat()); OpenCvImages.toMat(image, rgba)
            val inverse = keep(h.inv()); val warped = keep(Mat())
            Imgproc.warpPerspective(rgba, warped, inverse, Size(template.width.toDouble(), template.height.toDouble()), Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar(255.0, 255.0, 255.0, 255.0))
            val result = BufferedImage(template.width, template.height, BufferedImage.TYPE_INT_RGB)
            OpenCvImages.toImage(warped, result)
            return GeometryResult(matrix, result, "")
        } finally {
            matches.forEach { it.release() }; mats.forEach { it.release() }
        }
    }

    fun marks(bitmap: BufferedImage, lines: List<OcrLine> = emptyList()): List<NormalizedRect> = layout(bitmap, lines).marks

    /** Printed outlines only: closed geometry plus OCR glyph exclusion reduces letter/digit loops. */
    fun layout(bitmap: BufferedImage, lines: List<OcrLine> = emptyList()): PrintedLayoutGeometry {
        requireAvailable()
        val gray = gray(bitmap); val binary = Mat(); val hierarchy = Mat(); val horizontal = Mat(); val vertical = Mat()
        val contours = mutableListOf<MatOfPoint>(); val lineContours = mutableListOf<MatOfPoint>(); val verticalContours = mutableListOf<MatOfPoint>()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(max(24.0, bitmap.width * .06), 1.0))
        val verticalKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(1.0, max(20.0, bitmap.height * .025)))
        try {
            Imgproc.threshold(gray, binary, 0.0, 255.0, Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)
            Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE)
            fun normalized(rect: Rect) = NormalizedRect(rect.x.toFloat() / bitmap.width, rect.y.toFloat() / bitmap.height,
                    (rect.x + rect.width).toFloat() / bitmap.width, (rect.y + rect.height).toFloat() / bitmap.height)
            val glyphs = lines.flatMap { it.elements.ifEmpty { listOf(OcrElement(it.text, it.region)) } }
                .filterNot { it.text.trim().matches(Regex("[□☐○◯●✓✔_＿.．]+")) }
            val marks = mutableListOf<NormalizedRect>(); val areas = mutableListOf<NormalizedRect>(); val boxes = mutableListOf<NormalizedRect>()
            for ((contourIndex, contour) in contours.withIndex()) {
                val rect = Imgproc.boundingRect(contour)
                if (rect.width < 8 || rect.height < 5) continue
                val points = MatOfPoint2f(*contour.toArray()); val polygon = MatOfPoint2f()
                try {
                    val perimeter = Imgproc.arcLength(points, true)
                    if (perimeter <= 0) continue
                    Imgproc.approxPolyDP(points, polygon, perimeter * .025, true)
                    val area = abs(Imgproc.contourArea(contour))
                    val extent = area / rect.area()
                    val vertices = polygon.total()
                    val isRectangle = vertices == 4L && extent > .75
                    val isCircle = vertices >= 7 && 4 * PI * area / (perimeter * perimeter) > .83 && extent in .6.. .9
                    val childIndex = hierarchy.get(0, contourIndex)?.getOrNull(2)?.toInt() ?: -1
                    val childArea = contours.getOrNull(childIndex)?.let { abs(Imgproc.contourArea(it)) } ?: 0.0
                    // A closed outline needs a substantial enclosed interior, not only a glyph/solid blob.
                    // Parent page/table membership does not reject a real nested checkbox.
                    val hasInterior = area > 0 && childArea / area in .35.. .98
                    val region = normalized(rect)
                    val cx = (region.left + region.right) / 2; val cy = (region.top + region.bottom) / 2
                    val isGlyph = glyphs.any { cx in it.region.left..it.region.right && cy in it.region.top..it.region.bottom }
                    if (hasInterior && !isGlyph && (isRectangle || isCircle) && rect.width <= bitmap.width * .05 && rect.height <= bitmap.height * .04 && rect.width.toDouble() / rect.height in .7..1.4) marks += region
                    if (hasInterior && isRectangle && rect.width >= bitmap.width * .08 && rect.width.toDouble() / rect.height >= 2.5 && rect.height <= bitmap.height * .12) boxes += region
                } finally { points.release(); polygon.release() }
            }
            Imgproc.morphologyEx(binary, horizontal, Imgproc.MORPH_OPEN, kernel)
            Imgproc.morphologyEx(binary, vertical, Imgproc.MORPH_OPEN, verticalKernel)
            Imgproc.findContours(vertical, verticalContours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            val verticalLines = verticalContours.map { Imgproc.boundingRect(it) }
            Imgproc.findContours(horizontal, lineContours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            lineContours.map { Imgproc.boundingRect(it) }.filter { it.width >= bitmap.width * .08 && it.height <= max(8.0, bitmap.height * .01) }
                .forEach { rect ->
                    val lineY = (rect.y + rect.height / 2f) / bitmap.height
                    val left = rect.x.toFloat() / bitmap.width; val right = (rect.x + rect.width).toFloat() / bitmap.width
                    val crossesText = glyphs.any { glyph -> lineY in glyph.region.top..glyph.region.bottom &&
                        min(right, glyph.region.right) > max(left, glyph.region.left) }
                    val verticalCrossings = verticalLines.count { upright ->
                        upright.x + upright.width >= rect.x - 4 && upright.x <= rect.x + rect.width + 4 &&
                            upright.y <= rect.y + rect.height + 4 && upright.y + upright.height >= rect.y - 4
                    }
                    if (crossesText || verticalCrossings >= 2) return@forEach
                    // A line suggests the writing area immediately above it, never fabricated ink.
                    areas += NormalizedRect(rect.x.toFloat() / bitmap.width, max(0f, rect.y.toFloat() / bitmap.height - .025f),
                        (rect.x + rect.width).toFloat() / bitmap.width, min(1f, (rect.y + rect.height).toFloat() / bitmap.height + .003f))
                }
            fun distinct(regions: List<NormalizedRect>): List<NormalizedRect> = regions.sortedByDescending { (it.right - it.left) * (it.bottom - it.top) }
                .fold(mutableListOf<NormalizedRect>()) { found, region ->
                    if (found.none { abs((it.left + it.right) - (region.left + region.right)) < .01f && abs((it.top + it.bottom) - (region.top + region.bottom)) < .01f }) found += region
                    found
                }
            val distinctBoxes = distinct(boxes)
            val independentBoxes = distinctBoxes.filter { box ->
                val containsText = glyphs.any { glyph ->
                    val cx = (glyph.region.left + glyph.region.right) / 2; val cy = (glyph.region.top + glyph.region.bottom) / 2
                    cx in box.left..box.right && cy in box.top..box.bottom
                }
                val sharesGridEdge = distinctBoxes.any { other -> other !== box && (
                    ((abs(box.bottom - other.top) < .008f || abs(box.top - other.bottom) < .008f) &&
                        min(box.right, other.right) - max(box.left, other.left) > min(box.right - box.left, other.right - other.left) * .6f) ||
                    ((abs(box.right - other.left) < .008f || abs(box.left - other.right) < .008f) &&
                        min(box.bottom, other.bottom) - max(box.top, other.top) > min(box.bottom - box.top, other.bottom - other.top) * .6f)) }
                !containsText && !sharesGridEdge
            }
            return PrintedLayoutGeometry(distinct(marks), distinct(areas + independentBoxes), independentBoxes)
        } finally {
            gray.release(); binary.release(); hierarchy.release(); horizontal.release(); vertical.release(); kernel.release(); verticalKernel.release()
            contours.forEach { it.release() }; lineContours.forEach { it.release() }; verticalContours.forEach { it.release() }
        }
    }

    /** Compatibility diagnostic for checkbox callers only. Production requires an explicit profile/style. */
    fun marked(bitmap: BufferedImage, region: NormalizedRect): Boolean? {
        if (!region.isValid()) return null
        return when(MarkDetectors.inspect(bitmap,MarkStyle.CHECKBOX_TICK_CROSS,OptionMarkRegion("diagnostic",region)).decision) {
            OptionDecision.SELECTED -> true
            OptionDecision.EMPTY -> false
            OptionDecision.AMBIGUOUS,OptionDecision.UNSUPPORTED -> null
        }
    }

    private fun gray(bitmap: BufferedImage): Mat {
        val rgba = Mat(); val result = Mat()
        try { OpenCvImages.toMat(bitmap, rgba); Imgproc.cvtColor(rgba, result, Imgproc.COLOR_RGBA2GRAY) }
        finally { rgba.release() }
        return result
    }
}
