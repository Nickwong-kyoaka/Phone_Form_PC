package com.phoneform.ai.core.model

import kotlin.math.abs

/** Validates a complete candidate. Incomplete drafts may be saved; only confirmation uses this gate. */
object TemplateValidation {
    fun validate(draft: TemplateDraft, project: ProjectDetails): List<TemplateIssue> {
        val issues = mutableListOf<TemplateIssue>()
        fun issue(code: TemplateIssueCode, page: String? = null, question: String? = null, field: String? = null) {
            issues += TemplateIssue(code, page, question, field)
        }
        fun duplicateIds(ids: List<String>) = ids.any { it.isBlank() } || ids.distinct().size != ids.size
        if (draft.id.isBlank() || draft.projectId != project.project.id || draft.revision < 0) issue(TemplateIssueCode.INVALID_REFERENCE)
        if (draft.pages.isEmpty()) issue(TemplateIssueCode.NO_PAGES)
        if (duplicateIds(draft.pages.map { it.id }) || duplicateIds(draft.questions.map { it.id }) ||
            duplicateIds(draft.matrices.map { it.id }) || duplicateIds(draft.mappings.map { it.fieldId }) ||
            duplicateIds(draft.questions.flatMap { it.options }.map { it.id })) {
            issue(TemplateIssueCode.DUPLICATE_ID)
        }
        val pages = draft.pages.associateBy { it.id }
        val questions = draft.questions.associateBy { it.id }
        val matrices = draft.matrices.associateBy { it.id }
        if (draft.pages.map { it.position }.sorted() != draft.pages.indices.toList()) issue(TemplateIssueCode.INVALID_PAGE)
        val assets = draft.pages.groupBy { it.asset.id }
        draft.pages.forEach { page ->
            if (page.asset.id.isBlank() || page.asset.byteCount <= 0 ||
                !page.asset.sha256.matches(Regex("[a-fA-F0-9]{64}")) ||
                !relativePath(page.asset.relativePath) || !relativePath(page.renderPath) || !relativePath(page.thumbnailPath) ||
                page.sourcePageIndex < 0 || (page.asset.kind == SampleKind.IMAGE && page.sourcePageIndex != 0) ||
                page.width <= 0 || page.height <= 0 || page.revision < 1 || !validTransform(page.transform) ||
                assets.getValue(page.asset.id).any { it.asset != page.asset }) {
                issue(TemplateIssueCode.INVALID_PAGE, page.id)
            }
            val pageQuestions = draft.questions.filter { it.pageId == page.id }
            if (pageQuestions.isEmpty() && !page.noExtraction) issue(TemplateIssueCode.PAGE_WITHOUT_QUESTIONS, page.id)
            if (page.noExtraction && pageQuestions.isNotEmpty()) issue(TemplateIssueCode.INVALID_REFERENCE, page.id)
            if (pageQuestions.any { it.position < 0 } || pageQuestions.map { it.position }.distinct().size != pageQuestions.size) {
                issue(TemplateIssueCode.INVALID_REFERENCE, page.id)
            }
        }
        draft.questions.forEach { question ->
            fun questionIssue(code: TemplateIssueCode) = issue(code, question.pageId, question.id)
            if (question.pageId !in pages || (question.matrixId != null && question.matrixId !in matrices)) questionIssue(TemplateIssueCode.INVALID_REFERENCE)
            if (question.title.isBlank() || question.label.isBlank()) questionIssue(TemplateIssueCode.QUESTION_TITLE)
            if (question.region?.isValid() != true) questionIssue(TemplateIssueCode.QUESTION_REGION)
            if (question.type == QuestionType.SINGLE_CHOICE || question.type == QuestionType.MULTIPLE_CHOICE) {
                if (question.options.size < 2) questionIssue(TemplateIssueCode.OPTIONS_REQUIRED)
                if (duplicateIds(question.options.map { it.id })) questionIssue(TemplateIssueCode.DUPLICATE_ID)
                if (question.options.any { it.title.isBlank() }) questionIssue(TemplateIssueCode.OPTION_TITLE)
                if (question.options.any { it.code.isBlank() }) questionIssue(TemplateIssueCode.OPTION_CODE)
                if (question.options.map { it.code.trim() }.distinct().size != question.options.size) questionIssue(TemplateIssueCode.DUPLICATE_OPTION_CODE)
                if (question.options.any { it.region?.isValid() != true }) questionIssue(TemplateIssueCode.OPTION_REGION)
            } else {
                if (question.answerRegion?.isValid() != true) questionIssue(TemplateIssueCode.ANSWER_REGION)
                if (question.options.isNotEmpty()) questionIssue(TemplateIssueCode.INVALID_REFERENCE)
            }
        }
        draft.matrices.forEach { matrix ->
            val rows = matrix.questionIds.mapNotNull { questions[it] }
            val valid = matrix.pageId in pages && matrix.region.isValid() && matrix.questionIds.isNotEmpty() &&
                !duplicateIds(matrix.questionIds) && rows.size == matrix.questionIds.size &&
                boundaries(matrix.rowBoundaries) && boundaries(matrix.columnBoundaries) &&
                matrix.rowBoundaries.size == rows.size + 1 && matrix.columnBoundaries.size >= 3 &&
                rows.all { it.pageId == matrix.pageId && it.matrixId == matrix.id &&
                    (it.type == QuestionType.SINGLE_CHOICE || it.type == QuestionType.MULTIPLE_CHOICE) &&
                    it.options.size + 1 == matrix.columnBoundaries.size } &&
                draft.questions.filter { it.matrixId == matrix.id }.map { it.id }.toSet() == matrix.questionIds.toSet()
            if (!valid) issue(TemplateIssueCode.INVALID_MATRIX, matrix.pageId)
        }
        val fields = project.fields.associateBy { it.id }
        draft.mappings.forEach { mapping ->
            val field = fields[mapping.fieldId]
            if (field == null || (mapping.state == FieldMappingState.BOUND &&
                    (field.source != FieldSource.QUESTIONNAIRE || mapping.questionId !in questions)) ||
                (mapping.state != FieldMappingState.BOUND && mapping.questionId != null)) {
                issue(TemplateIssueCode.INVALID_REFERENCE, field = mapping.fieldId)
            }
        }
        project.fields.filter { it.source == FieldSource.QUESTIONNAIRE }.forEach { field ->
            val mapping = draft.mappings.find { it.fieldId == field.id }
            if (mapping == null || mapping.state == FieldMappingState.UNMAPPED) issue(TemplateIssueCode.UNMAPPED_FIELD, field = field.id)
        }
        return issues.distinct()
    }

    private fun relativePath(path: String): Boolean = path.isNotBlank() && !path.startsWith('/') &&
        !path.startsWith('\\') && ':' !in path && path.replace('\\', '/').split('/').none { it == ".." || it.isEmpty() }

    private fun boundaries(values: List<Float>): Boolean = values.size >= 2 && values.first() == 0f &&
        values.last() == 1f && values.all { it.isFinite() && it in 0f..1f } && values.zipWithNext().all { it.first < it.second }

    private fun validTransform(transform: PageTransform): Boolean {
        if (transform.rotationDegrees !in listOf(0, 90, 180, 270) || transform.corners.size != 4 ||
            transform.corners.any { !it.x.isFinite() || !it.y.isFinite() || it.x !in 0f..1f || it.y !in 0f..1f } ||
            transform.sourceToPage.size != 9 || transform.sourceToPage.any { !it.isFinite() }) return false
        val signs = transform.corners.indices.map { index ->
            val a = transform.corners[index]; val b = transform.corners[(index + 1) % 4]; val c = transform.corners[(index + 2) % 4]
            (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
        }
        if (!(signs.all { it > 0f } || signs.all { it < 0f })) return false
        val m = transform.sourceToPage
        val determinant = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])
        return abs(determinant) > 0.0000001f
    }
}
