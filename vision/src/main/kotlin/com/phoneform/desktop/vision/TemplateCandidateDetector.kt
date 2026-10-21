package com.phoneform.desktop.vision

import com.phoneform.ai.core.model.*
import java.security.MessageDigest
import kotlin.math.*

data class VisionSuggestion(val fingerprint: String, val question: QuestionDefinition, val suggestedFieldIds: List<String>,
    val sourceAnchor: NormalizedRect = requireNotNull(question.region), val reason: String = "PRINT_LAYOUT_CANDIDATE")

/** Layout proposals, never answers. Geometry is always in the complete corrected page's unit square. */
object TemplateCandidateDetector {
    private val questionStart = Regex("^\\s*(?:[Qq]\\s*)?([0-9]{1,3})\\s*[.、．):：]\\s*(.+)$")
    private val optionStart = Regex("^\\s*(?:[□☐○◯●✓✔]\\s*|([A-Za-z])\\s*[.)、．:]\\s*)(.+)$")
    private val optionBoundary = Regex("(?:^|\\s)[A-Za-z][.)、．:]\\s*|[□☐○◯]")
    private val fullPage = NormalizedRect(0f, 0f, 1f, 1f)
    private data class Anchor(val line: OcrLine, val label: String, val title: String, val answer: NormalizedRect? = null)

    fun detect(page: SamplePage, lines: List<OcrLine>, fields: List<RequestedOutputField>,
               marks: List<NormalizedRect> = emptyList(), answerAreas: List<NormalizedRect> = emptyList(),
               scope: NormalizedRect = fullPage, independentAnswerBoxes: List<NormalizedRect> = emptyList()): List<VisionSuggestion> {
        require(scope.isValid())
        fun contained(rect: NormalizedRect) = rect.isValid() && rect.left >= scope.left && rect.top >= scope.top && rect.right <= scope.right && rect.bottom <= scope.bottom
        val availableAreas = answerAreas.filter(::contained)
        val availableMarks = marks.filter(::contained)
        val ordered = lines.flatMap(::splitMergedQuestionLines).filter { it.region.isValid() && insideCenter(it.region, scope) }
            .sortedWith(compareBy({ it.region.top }, { it.region.left }))
        val anchors = ordered.mapNotNull { line ->
            questionStart.matchEntire(line.text)?.let { Anchor(line, it.groupValues[1], it.groupValues[2].trim()) }
        }
        val unnumbered = ordered.filter { questionStart.matchEntire(it.text) == null }
            .mapNotNull { unnumberedAnchor(it, availableAreas, independentAnswerBoxes.filter(::contained), availableMarks) }
        if (anchors.isEmpty() && unnumbered.isEmpty()) return emptyList()
        // Group by left indentation, not global reading height. Two columns may reuse paper numbers.
        val lefts = mutableListOf<Float>()
        anchors.sortedBy { it.line.region.left }.forEach { anchor ->
            if (lefts.none { abs(it - anchor.line.region.left) < .18f }) lefts += anchor.line.region.left
        }
        fun column(anchor: Anchor) = lefts.indices.minBy { abs(lefts[it] - anchor.line.region.left) }
        val result = mutableListOf<VisionSuggestion>()
        for (columnIndex in lefts.indices) {
            val columnLeft = max(scope.left, lefts[columnIndex] - .025f)
            val columnRight = min(scope.right, lefts.getOrNull(columnIndex + 1)?.minus(.015f) ?: scope.right)
            val columnAnchors = anchors.filter { column(it) == columnIndex }.sortedBy { it.line.region.top }
            columnAnchors.forEachIndexed { index, anchor ->
                val line = anchor.line
                val nextY = columnAnchors.getOrNull(index + 1)?.line?.region?.top ?: scope.bottom
                val following = ordered.filter { candidate ->
                    candidate !== line && candidate.region.top >= line.region.bottom - .002f && candidate.region.top < nextY &&
                        candidate.region.left >= columnLeft - .015f && candidate.region.left < columnRight &&
                        candidate.region.right <= columnRight + .035f
                }
                val optionLines = following.flatMap(::splitOptions).mapNotNull { item -> optionStart.matchEntire(item.text)?.let { item to it } }
                val title = anchor.title
                val type = when {
                    optionLines.size >= 2 -> if (Regex("多选|多選|multiple|select all", RegexOption.IGNORE_CASE).containsMatchIn(title)) QuestionType.MULTIPLE_CHOICE else QuestionType.SINGLE_CHOICE
                    else -> scalarType(title)
                }
                val bottom = min(nextY - .005f, max(line.region.bottom + .04f,
                    following.maxOfOrNull { it.region.bottom } ?: anchor.answer?.bottom ?: line.region.bottom + .08f))
                    .coerceIn(line.region.bottom, scope.bottom)
                val region = NormalizedRect(max(columnLeft, line.region.left - .01f), max(scope.top, line.region.top - .008f),
                    min(columnRight, max(line.region.right, following.maxOfOrNull { it.region.right } ?: line.region.right) + .02f), bottom)
                if (!region.isValid()) return@forEachIndexed
                // Anchor identity excludes title, crop scope, body height, and enhancement settings.
                val fingerprint = hash("${page.id}:${page.revision}:${anchor.label.ifBlank { "unnumbered" }}:${(line.region.left * 100).roundToInt()}:${(line.region.top * 100).roundToInt()}")
                val options = optionLines.mapIndexed { n, (item, optionMatch) ->
                    val mark = availableMarks.filter { candidate ->
                        insideCenter(candidate, NormalizedRect(columnLeft, scope.top, columnRight, scope.bottom)) &&
                            abs(centerY(candidate) - centerY(item.region)) < max(.012f, item.region.bottom - item.region.top) &&
                            candidate.right <= item.region.left + .025f && item.region.left - candidate.right < .08f
                    }.minByOrNull { abs(it.right - item.region.left) }
                    val optionRegion = mark ?: NormalizedRect(max(columnLeft, item.region.left - .035f), item.region.top,
                        min(columnRight, item.region.left + .008f), item.region.bottom)
                    OptionDefinition("$fingerprint-option-$n", optionMatch.groupValues[2].trim(),
                        optionMatch.groupValues[1].ifBlank { (n + 1).toString() }, optionRegion.takeIf { it.isValid() })
                }
                val detectedAnswer = anchor.answer ?: availableAreas.filter { nearAnswer(line.region, it) && it.right <= columnRight }.minByOrNull { abs(it.top - line.region.bottom) }
                val answer = if (options.isEmpty()) detectedAnswer ?: NormalizedRect(region.left,
                    min(bottom - .005f, line.region.bottom + .005f), columnRight, bottom) else null
                val question = QuestionDefinition("ai-$fingerprint", page.id, result.size, anchor.label, title, type, region,
                    answer?.takeIf { it.isValid() }, options)
                val fieldIds = fields.filter { it.source == FieldSource.QUESTIONNAIRE && normalize(it.title).isNotEmpty() && normalize(it.title) == normalize(title) }.map { it.id }
                val duplicateLabel = anchor.label.isNotBlank() && anchors.count { it.label == anchor.label } > 1
                val reason = if (duplicateLabel) "POSSIBLE_DUPLICATE_OR_BILINGUAL_LABEL"
                    else if (anchor.label.isBlank()) "UNNUMBERED_LABEL_CANDIDATE" else "PRINT_LAYOUT_CANDIDATE"
                result += VisionSuggestion(fingerprint, question, fieldIds, line.region, reason)
            }
        }
        // Unnumbered fields cannot create false columns or truncate numbered question bodies.
        for (anchor in unnumbered) {
            val answer = requireNotNull(anchor.answer)
            val identity = hash("${page.id}:${page.revision}:unnumbered:${(anchor.line.region.left * 100).roundToInt()}:${(anchor.line.region.top * 100).roundToInt()}")
            val combined = union(listOf(anchor.line.region, answer))
            val region = NormalizedRect(max(scope.left, combined.left - .008f), max(scope.top, combined.top - .005f),
                min(scope.right, combined.right + .008f), min(scope.bottom, combined.bottom + .005f))
            if (!region.isValid()) continue
            val question = QuestionDefinition("ai-$identity", page.id, result.size, "", anchor.title, scalarType(anchor.title), region, answer)
            val fieldIds = fields.filter { it.source == FieldSource.QUESTIONNAIRE && normalize(it.title).isNotEmpty() && normalize(it.title) == normalize(anchor.title) }.map { it.id }
            result += VisionSuggestion(identity, question, fieldIds, anchor.line.region, "UNNUMBERED_LABEL_CANDIDATE")
        }
        return result.sortedWith(compareBy({ it.sourceAnchor.top }, { it.sourceAnchor.left }))
            .mapIndexed { index, suggestion -> suggestion.copy(question = suggestion.question.copy(position = index)) }
    }

    private fun unnumberedAnchor(line: OcrLine, areas: List<NormalizedRect>, boxes: List<NormalizedRect>, marks: List<NormalizedRect>): Anchor? {
        if (optionStart.matches(line.text) || line.text.length > 100 || line.text.none { it.isLetter() } ||
            line.text.any { it in "□☐○◯✓✔" }) return null
        // A nearby table edge is not evidence that every wrapped sentence is a new field.
        val underline = Regex("[_＿]{2,}|[.．]{5,}").find(line.text)
        val colon = line.text.indexOfFirst { it == ':' || it == '：' }.takeIf { it >= 0 }
        val delimiter = listOfNotNull(colon, underline?.range?.first).minOrNull()
        val title = (delimiter?.let { line.text.substring(0, it) } ?: line.text).trim().trimEnd(':', '：')
        if (title.length !in 2..48 || title.any { it == '?' || it == '？' }) return null
        val delimiterElement = line.elements.indexOfFirst { item -> item.text.any { it == ':' || it == '：' } || Regex("[_＿]{2,}|[.．]{5,}").containsMatchIn(item.text) }
        val label = if (delimiterElement >= 0) line.copy(region = union(line.elements.take(delimiterElement + 1).map { it.region })) else line
        if (marks.any { it.right <= label.region.left + .015f && label.region.left - it.right < .04f && abs(centerY(it) - centerY(label.region)) < .025f }) return null
        val elementArea = line.elements.filter { Regex("[_＿]{2,}|[.．]{5,}").containsMatchIn(it.text) }.map { it.region }.takeIf { it.isNotEmpty() }?.let(::union)
        val independentBox = boxes.filter { area ->
            title.length <= 30 && label.region.right - label.region.left <= .3f &&
                area.left >= label.region.right - .005f && area.left - label.region.right <= .05f &&
                abs(centerY(area) - centerY(label.region)) <= max(.015f, (label.region.bottom - label.region.top) / 2)
        }.minByOrNull { it.left - label.region.right }
        if (delimiter == null && independentBox == null) return null
        val area = if (delimiter != null) areas.filter { nearAnswer(label.region, it) }.minByOrNull { abs(centerY(it) - centerY(label.region)) } else null
        val placeholder = underline?.let { match ->
            elementArea ?: line.region.copy(left = line.region.left + (line.region.right - line.region.left) * match.range.first / line.text.length)
        }
        val answer = placeholder ?: independentBox ?: area ?: return null
        return Anchor(label, "", title, answer)
    }

    private fun scalarType(title: String) = when {
        Regex("日期|年月日|\\bdate\\b|\\bbirthdate\\b", RegexOption.IGNORE_CASE).containsMatchIn(title) -> QuestionType.DATE
        Regex("年龄|年齡|岁|歲|数量|數量|分数|分數|\\bage\\b|\\bnumber\\b|\\bamount\\b", RegexOption.IGNORE_CASE).containsMatchIn(title) -> QuestionType.NUMBER
        else -> QuestionType.TEXT
    }

    private fun nearAnswer(label: NormalizedRect, area: NormalizedRect): Boolean =
        (area.left >= label.right - .02f && area.left - label.right < .12f && abs(centerY(area) - centerY(label)) < .035f) ||
            (area.top >= label.bottom - .008f && area.top - label.bottom < .055f && area.right > label.left && area.left < label.right)

    /** Real OCR elements delimit horizontal options. Approximation remains only for legacy readers without elements. */
    internal fun splitOptions(line: OcrLine): List<OcrLine> {
        if (line.elements.isNotEmpty()) {
            val elements = line.elements.sortedBy { it.region.left }
            val starts = elements.indices.filter { optionBoundary.find(elements[it].text)?.range?.first == 0 }
            if (starts.size < 2) return listOf(line)
            return starts.mapIndexed { index, start ->
                val group = elements.subList(start, starts.getOrNull(index + 1) ?: elements.size)
                OcrLine(group.joinToString(" ") { it.text }, union(group.map { it.region }), group, line.blockId, line.blockRegion)
            }
        }
        val boundaries = optionBoundary.findAll(line.text).toList()
        if (boundaries.size < 2) return listOf(line)
        return boundaries.mapIndexed { index, boundary ->
            val start = boundary.range.first
            val end = boundaries.getOrNull(index + 1)?.range?.first ?: line.text.length
            val width = line.region.right - line.region.left
            OcrLine(line.text.substring(start, end).trim(), line.region.copy(
                left = line.region.left + width * start / line.text.length,
                right = line.region.left + width * end / line.text.length), blockId = line.blockId, blockRegion = line.blockRegion)
        }
    }

    private fun splitMergedQuestionLines(line: OcrLine): List<OcrLine> {
        if (line.elements.size < 2) return listOf(line)
        val elements = line.elements.sortedBy { it.region.left }
        val prefix = Regex("^\\s*(?:[Qq]\\s*)?[0-9]{1,3}[.、．):：]")
        val starts = elements.indices.filter { index -> prefix.containsMatchIn(elements[index].text) &&
            (index == 0 || elements[index].region.left - elements[index - 1].region.right > .06f) }
        if (starts.size < 2 || starts.first() != 0) return listOf(line)
        return starts.mapIndexed { index, start ->
            val group = elements.subList(start, starts.getOrNull(index + 1) ?: elements.size)
            OcrLine(group.joinToString(" ") { it.text }, union(group.map { it.region }), group, line.blockId, line.blockRegion)
        }
    }

    private fun normalize(value: String) = value.lowercase().filter { it.isLetterOrDigit() }
    private fun centerY(rect: NormalizedRect) = (rect.top + rect.bottom) / 2
    private fun insideCenter(rect: NormalizedRect, scope: NormalizedRect) = (rect.left + rect.right) / 2 in scope.left..scope.right && centerY(rect) in scope.top..scope.bottom
    private fun union(rects: List<NormalizedRect>) = NormalizedRect(rects.minOf { it.left }, rects.minOf { it.top }, rects.maxOf { it.right }, rects.maxOf { it.bottom })
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
}
