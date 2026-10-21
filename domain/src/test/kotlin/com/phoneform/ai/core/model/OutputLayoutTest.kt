package com.phoneform.ai.core.model

import org.junit.Assert.*
import org.junit.Test

class OutputLayoutTest {
    private val region = NormalizedRect(.1f, .1f, .9f, .9f)
    private val question = QuestionDefinition("q", "page", 0, "1", "选项", QuestionType.MULTIPLE_CHOICE, region, region,
        listOf(OptionDefinition("a", "甲", "01", region), OptionDefinition("b", "乙", "02", region)))
    private val document = TemplateDraft("draft", "p", questions = listOf(question), updatedAtEpochMillis = 0)
    private val project = ProjectDetails(ProjectConfig("p", "合成", null, QuestionnaireLanguage.SIMPLIFIED_CHINESE,
        NumberingStrategy.GENERATED, 0, 0, PreparationStatus.TEMPLATE_CONFIRMED, "v"),
        OutputProfile("p", language = OutputHeaderLanguage.SIMPLIFIED), listOf(
            RequestedOutputField("one", "p", "同名", 0, FieldSource.QUESTIONNAIRE, "q", FieldMappingState.BOUND),
            RequestedOutputField("two", "p", "同名", 1, FieldSource.QUESTIONNAIRE, "q", FieldMappingState.BOUND),
            RequestedOutputField("site", "p", "站点", 2, FieldSource.MANUAL_METADATA, mappingState = FieldMappingState.MANUAL_ENTRY)))

    @Test fun targetExclusionHidesEveryAliasAndMultiColumnWithoutChangingReviewTargets() {
        val columns = TemplateColumns.build(project, document, listOf(OutputFieldPolicy("p", "question:q", false, false)))
        assertTrue(columns.none { it.questionId == "q" })
        assertTrue(columns.any { it.key == "field_site" })
        val version = FormSchemaVersion("v", "p", 1, "draft", 0, document)
        assertTrue(ReviewTargets.build(project, version).any { it.key == "question:q" })
    }
    @Test fun layoutChangesOrderAndTitlesWhileFixedColumnsStayFirst() {
        val layout = listOf(OutputColumnLayout("field_site", "先列", 0), OutputColumnLayout("field_one_b", "乙列", 1))
        val columns = TemplateColumns.build(project.copy(output = project.output.copy(columnLayout = layout)), document)
        assertEquals("submission_id", columns.first().key)
        assertEquals(listOf("field_site", "field_one_b"), columns.drop(6).take(2).map { it.key })
        assertEquals(listOf("先列", "乙列"), columns.drop(6).take(2).map { it.title })
        assertEquals(columns.size, columns.map { it.title }.distinct().size)
    }
    @Test fun movingDuplicateTitlesPreservesStableSuffixAssignment() {
        val first = TemplateColumns.build(project, document).associate { it.key to it.title }
        val layout = first.keys.reversed().mapIndexed { index, key -> OutputColumnLayout(key, position = index) }
        val moved = TemplateColumns.build(project.copy(output = project.output.copy(columnLayout = layout)), document).associate { it.key to it.title }
        assertEquals(first, moved)
    }
    @Test fun staleLayoutForMissingColumnsDoesNotInventOrRestoreAColumn() {
        val altered = project.copy(output = project.output.copy(columnLayout = listOf(OutputColumnLayout("missing", "不存在", 0))))
        assertEquals(TemplateColumns.build(project, document), TemplateColumns.build(altered, document))
    }
    @Test fun selectedLegacyProjectOnlyAddsNewQuestionWhenExplicitlySelectedForExport() {
        val legacy = project.copy(output = project.output.copy(extractionScope = ExtractionScope.SELECTED), fields = emptyList())
        assertTrue(TemplateColumns.build(legacy, document).none { it.questionId == "q" })
        assertEquals(2, TemplateColumns.build(legacy, document, listOf(OutputFieldPolicy("p", "question:q", false, true))).count { it.questionId == "q" })
    }
}
