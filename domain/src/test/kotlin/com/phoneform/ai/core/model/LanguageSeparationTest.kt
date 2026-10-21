package com.phoneform.ai.core.model

import org.junit.Assert.*
import org.junit.Test

class LanguageSeparationTest {
    private fun project(language: OutputHeaderLanguage) = ProjectDetails(
        ProjectConfig("p", "原始專案名稱", null, QuestionnaireLanguage.TRADITIONAL_CHINESE,
            NumberingStrategy.PAPER_ID, 0, 0),
        OutputProfile("p", language = language, includePersonalIdentifiers = true),
        listOf(RequestedOutputField("manual", "p", "原始自訂標題", 0,
            FieldSource.MANUAL_METADATA, mappingState = FieldMappingState.MANUAL_ENTRY)),
    )

    @Test fun interfaceLanguageAndExportLanguageHaveIndependentTypesAndStableTags() {
        assertEquals(listOf("zh-Hans", "zh-Hant", "en"), UiLanguage.entries.map { it.languageTag })
        assertEquals(listOf("zh-Hans", "zh-Hant", "en"), OutputHeaderLanguage.entries.map { it.languageTag })
        val settings = AppSettings(UiLanguage.ENGLISH, true)
        val details = project(OutputHeaderLanguage.TRADITIONAL)
        assertEquals(UiLanguage.ENGLISH, settings.language)
        assertEquals(OutputHeaderLanguage.TRADITIONAL, details.output.language)
        assertEquals(QuestionnaireLanguage.TRADITIONAL_CHINESE, details.project.questionnaireLanguage)
    }

    @Test fun everyGeneratedResponseHeaderHasAnExplicitTranslationWithoutTranslatingUserContent() {
        val expected = mapOf(
            OutputHeaderLanguage.SIMPLIFIED to listOf("问卷编号", "内部记录 ID", "覆核状态", "提交修订", "模板版本", "页面完整性", "受访者编号"),
            OutputHeaderLanguage.TRADITIONAL to listOf("問卷編號", "內部記錄 ID", "覆核狀態", "提交修訂", "範本版本", "頁面完整性", "受訪者編號"),
            OutputHeaderLanguage.ENGLISH to listOf("Questionnaire number", "Record ID", "Review status", "Submission revision", "Template version", "Page completeness", "Respondent ID"),
        )
        OutputHeaderLanguage.entries.forEach { language ->
            val details = project(language)
            val columns = TemplateColumns.build(details, null)
            assertEquals(expected.getValue(language), columns.take(7).map { it.title })
            assertEquals("原始自訂標題", columns.last().title)
            assertEquals("原始專案名稱", details.project.name)
        }
    }

    @Test fun languageChangesOnlyGeneratedTitlesAndPreservesStableKeysAndColumnOverrides() {
        val simplified = project(OutputHeaderLanguage.SIMPLIFIED).copy(output = project(OutputHeaderLanguage.SIMPLIFIED).output.copy(
            columnLayout = listOf(OutputColumnLayout("field_manual", "Custom English override", 0))))
        val english = simplified.copy(output = simplified.output.copy(language = OutputHeaderLanguage.ENGLISH))
        val before = TemplateColumns.build(simplified, null)
        val after = TemplateColumns.build(english, null)
        assertEquals(before.map { it.key }, after.map { it.key })
        assertEquals("Custom English override", after.last().title)
        assertNotEquals(before.first().title, after.first().title)
    }
}
