package com.phoneform.ai.core.model

import org.junit.Assert.*
import org.junit.Test

class TemplateValidationTest {
    @Test fun completeQuestionAndExplicitNonExtractedPageAreAccepted() {
        assertTrue(TemplateValidation.validate(document(), project()).isEmpty())
        val skipped = document().copy(pages = listOf(page().copy(noExtraction = true)), questions = emptyList(), mappings = emptyList())
        assertTrue(TemplateValidation.validate(skipped, project().copy(fields = emptyList())).isEmpty())
        assertTrue(TemplateValidation.validate(document().copy(mappings = listOf(FieldMapping("age", FieldMappingState.MANUAL_ENTRY))), project()).isEmpty())
    }

    @Test fun missingPagesQuestionsAndMappingCannotBeConfirmed() {
        val empty = TemplateDraft("draft", "project")
        assertTrue(codes(empty).containsAll(setOf(TemplateIssueCode.NO_PAGES, TemplateIssueCode.UNMAPPED_FIELD)))
        assertTrue(codes(document().copy(questions = emptyList())).contains(TemplateIssueCode.PAGE_WITHOUT_QUESTIONS))
        assertTrue(codes(document().copy(mappings = emptyList())).contains(TemplateIssueCode.UNMAPPED_FIELD))
        assertTrue(codes(document().copy(mappings = listOf(FieldMapping("age", FieldMappingState.BOUND, "missing")))).contains(TemplateIssueCode.INVALID_REFERENCE))
    }

    @Test fun invalidGeometryPathsAndDuplicatePageIdsAreRejected() {
        val page = page()
        listOf(
            page.copy(renderPath = "../outside.png"),
            page.copy(asset = page.asset.copy(relativePath = "C:/outside.pdf")),
            page.copy(width = 0),
            page.copy(transform = PageTransform(corners = listOf(NormalizedPoint(Float.NaN, 0f)))),
            page.copy(transform = PageTransform(sourceToPage = List(9) { 0f })),
        ).forEach { invalid -> assertTrue(codes(document().copy(pages = listOf(invalid))).contains(TemplateIssueCode.INVALID_PAGE)) }
        assertTrue(codes(document().copy(pages = listOf(page, page.copy(position = 1)))).contains(TemplateIssueCode.DUPLICATE_ID))
        assertTrue(codes(document().copy(questions = listOf(question().copy(region = null)))).contains(TemplateIssueCode.QUESTION_REGION))
        assertTrue(codes(document().copy(questions = listOf(question().copy(answerRegion = NormalizedRect(1f, 0f, 0f, 1f))))).contains(TemplateIssueCode.ANSWER_REGION))
    }

    @Test fun choiceRequiresDistinctCodesAndActualOptionRegions() {
        val choice = question().copy(type = QuestionType.SINGLE_CHOICE, answerRegion = null,
            options = listOf(OptionDefinition("one", "是", "1", region), OptionDefinition("two", "否", "0", region)))
        assertTrue(TemplateValidation.validate(document().copy(questions = listOf(choice)), project()).isEmpty())
        val invalid = choice.copy(options = listOf(choice.options.first(), choice.options.last().copy(code = " 1 ", title = "", region = null)))
        assertTrue(codes(document().copy(questions = listOf(invalid))).containsAll(setOf(
            TemplateIssueCode.DUPLICATE_OPTION_CODE, TemplateIssueCode.OPTION_TITLE, TemplateIssueCode.OPTION_REGION)))
        assertTrue(codes(document().copy(questions = listOf(choice.copy(options = choice.options.take(1))))).contains(TemplateIssueCode.OPTIONS_REQUIRED))
        assertTrue(codes(document().copy(questions = listOf(choice, choice.copy(id = "second", position = 1)))).contains(TemplateIssueCode.DUPLICATE_ID))
    }

    @Test fun matrixRowsMustReferenceActualQuestionsAndIncreasingGridBoundaries() {
        val choice = question().copy(type = QuestionType.SINGLE_CHOICE, matrixId = "matrix", answerRegion = null,
            options = listOf(OptionDefinition("one", "是", "1", region), OptionDefinition("two", "否", "0", region)))
        val matrix = MatrixDefinition("matrix", "page", region, listOf(0f, 1f), listOf(0f, .5f, 1f), listOf("question"))
        val valid = document().copy(questions = listOf(choice), matrices = listOf(matrix))
        assertTrue(TemplateValidation.validate(valid, project()).isEmpty())
        assertTrue(codes(valid.copy(matrices = listOf(matrix.copy(columnBoundaries = listOf(0f, .5f, .4f, 1f))))).contains(TemplateIssueCode.INVALID_MATRIX))
        assertTrue(codes(valid.copy(matrices = listOf(matrix.copy(questionIds = listOf("missing"))))).contains(TemplateIssueCode.INVALID_MATRIX))
    }

    @Test fun columnsExpandMultiSelectAndDoNotDuplicateAlreadyMappedQuestion() {
        val multi = question().copy(type = QuestionType.MULTIPLE_CHOICE, options = listOf(
            OptionDefinition("a", "甲", "1", region), OptionDefinition("b", "乙", "2", region)))
        val second = question().copy(id = "second", position = 1, title = "调查日期")
        val document = document().copy(questions = listOf(multi, second))
        val config = project().copy(output = project().output.copy(language = OutputHeaderLanguage.TRADITIONAL, includePersonalIdentifiers = true))
        val columns = TemplateColumns.build(config, document)
        assertEquals(listOf("submission_id", "record_id", "review_status", "submission_revision", "template_version", "completeness", "respondent_id", "field_age_a", "field_age_b", "question_second"), columns.map { it.key })
        assertEquals("問卷編號", columns.first().title)
        assertEquals("原始年齡 · 甲", columns.single { it.key == "field_age_a" }.title)
        assertEquals(2, columns.count { it.questionId == "question" })
        assertEquals(9, TemplateColumns.build(config.copy(output = config.output.copy(extractionScope = ExtractionScope.SELECTED)), document).size)
        assertEquals(listOf("submission_id", "record_id", "review_status", "submission_revision", "template_version", "completeness", "field_age"), TemplateColumns.build(project(), null).map { it.key })
    }

    @Test fun changingFieldToMetadataIgnoresOldConfirmedQuestionMapping() {
        val multi = question().copy(type = QuestionType.MULTIPLE_CHOICE, options = listOf(
            OptionDefinition("a", "甲", "1", region), OptionDefinition("b", "乙", "2", region)))
        val current = project().copy(output = project().output.copy(extractionScope = ExtractionScope.SELECTED),
            fields = project().fields.map { it.copy(source = FieldSource.MANUAL_METADATA, mappingState = FieldMappingState.MANUAL_ENTRY) })
        val columns = TemplateColumns.build(current, document().copy(questions = listOf(multi)))
        assertEquals(listOf("submission_id", "record_id", "review_status", "submission_revision", "template_version", "completeness", "field_age"), columns.map { it.key })
        assertNull(columns.last().questionId)
        assertNull(columns.last().optionId)
    }

    private fun codes(draft: TemplateDraft) = TemplateValidation.validate(draft, project()).map { it.code }.toSet()
    private val region = NormalizedRect(.1f, .1f, .9f, .4f)
    private fun question() = QuestionDefinition("question", "page", 0, "Q1", "年龄", QuestionType.NUMBER, region, region)
    private fun page() = SamplePage("page", SampleAsset("asset", SampleKind.IMAGE, "samples/source.jpg", "a".repeat(64), 100),
        0, 0, "samples/render.png", "samples/thumbnail.png", 1000, 1400)
    private fun document() = TemplateDraft("draft", "project", pages = listOf(page()), questions = listOf(question()),
        mappings = listOf(FieldMapping("age", FieldMappingState.BOUND, "question")))
    private fun project() = ProjectDetails(ProjectConfig("project", "测试", null, QuestionnaireLanguage.MIXED_CHINESE,
        NumberingStrategy.GENERATED, 100, 100), OutputProfile("project", language = OutputHeaderLanguage.SIMPLIFIED),
        listOf(RequestedOutputField("age", "project", "原始年齡", 0, FieldSource.QUESTIONNAIRE)))
}
