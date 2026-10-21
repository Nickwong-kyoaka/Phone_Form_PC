package com.phoneform.ai.core.model

import org.junit.Assert.*
import org.junit.Test

class ReviewValidationTest {
    private fun target(type: QuestionType) = AnswerTarget("question:q", "测试", QuestionDefinition("q", "page", 0, "1", "测试", type,
        options = listOf(OptionDefinition("a", "甲", "01"), OptionDefinition("b", "乙", "02"))))
    @Test fun emptyAnswersNeverBecomeZeroOrUnselectedOptions() {
        val target = target(QuestionType.MULTIPLE_CHOICE)
        assertFalse(ReviewTargets.valid(AnswerValue(target.key), target))
        assertFalse(ReviewTargets.valid(AnswerValue(target.key, AnswerState.VALUE), target))
        assertTrue(ReviewTargets.valid(AnswerValue(target.key, AnswerState.BLANK), target))
        assertFalse(ReviewTargets.valid(AnswerValue(target.key, AnswerState.BLANK, text = "0"), target))
        assertTrue(ReviewTargets.valid(AnswerValue(target.key, AnswerState.VALUE, optionIds = listOf("a")), target))
        assertFalse(ReviewTargets.valid(AnswerValue(target.key, AnswerState.VALUE, optionIds = listOf("a", "a")), target))
        assertFalse(ReviewTargets.valid(AnswerValue(target.key, AnswerState.VALUE, optionIds = listOf("unknown")), target))
    }
    @Test fun numericAndDateParsingNeverInventsMissingComponents() {
        val number = target(QuestionType.NUMBER); val date = target(QuestionType.DATE)
        assertTrue(ReviewTargets.valid(AnswerValue(number.key, AnswerState.VALUE, "-0.25"), number))
        assertFalse(ReviewTargets.valid(AnswerValue(number.key, AnswerState.VALUE, "1,234"), number))
        assertFalse(ReviewTargets.valid(AnswerValue(number.key, AnswerState.VALUE, "1e2"), number))
        assertFalse(ReviewTargets.valid(AnswerValue(date.key, AnswerState.VALUE, "2026-02-29"), date))
        assertFalse(ReviewTargets.valid(AnswerValue(date.key, AnswerState.VALUE, "10/18"), date))
        assertTrue(ReviewTargets.valid(AnswerValue(date.key, AnswerState.VALUE, "2024-02-29"), date))
    }
    @Test fun privacyPolicyExcludesAllColumnsOfOneMappedQuestionWithoutReintroducingAllMode() {
        val question = target(QuestionType.MULTIPLE_CHOICE).question!!
        val project = ProjectDetails(ProjectConfig("p", "p", null, QuestionnaireLanguage.ENGLISH, NumberingStrategy.GENERATED, 1, 1),
            OutputProfile("p", language = OutputHeaderLanguage.SIMPLIFIED), listOf(RequestedOutputField("f", "p", "private", 0,
                FieldSource.QUESTIONNAIRE, "q", FieldMappingState.BOUND)))
        val document = TemplateDraft("d", "p", questions = listOf(question))
        val columns = TemplateColumns.build(project, document, listOf(OutputFieldPolicy("p", "question:q", true)))
        assertTrue(columns.none { it.questionId == "q" })
        val included = TemplateColumns.build(project.copy(output = project.output.copy(includePersonalIdentifiers = true)), document,
            listOf(OutputFieldPolicy("p", "question:q", true)))
        assertEquals(2, included.count { it.questionId == "q" })
    }
    @Test fun mappedQuestionAnswersAreSharedAndDuplicateColumnTitlesAreDistinct() {
        val question = target(QuestionType.NUMBER).question!!
        val project = ProjectDetails(ProjectConfig("p", "p", null, QuestionnaireLanguage.ENGLISH, NumberingStrategy.GENERATED, 1, 1),
            OutputProfile("p", language = OutputHeaderLanguage.SIMPLIFIED), listOf("a", "b").mapIndexed { i, id ->
                RequestedOutputField(id, "p", "重复", i, FieldSource.QUESTIONNAIRE, "q", FieldMappingState.BOUND) })
        val document = TemplateDraft("d", "p", questions = listOf(question))
        val template = FormSchemaVersion("v", "p", 1, "d", 1, document)
        assertEquals(1, ReviewTargets.build(project, template).size)
        val columns = TemplateColumns.build(project, document)
        assertEquals(columns.size, columns.map { it.title }.distinct().size)
    }
}
