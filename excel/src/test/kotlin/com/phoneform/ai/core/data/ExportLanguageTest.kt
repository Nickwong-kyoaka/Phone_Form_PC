package com.phoneform.ai.core.data

import com.phoneform.ai.core.model.*
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class ExportLanguageTest {
    @Test fun fourSheetsHaveTranslatedGeneratedHeadersAndStableMachineValuesInEveryLanguage() {
        val document = TemplateDraft("draft", "p", updatedAtEpochMillis = 0)
        val template = FormSchemaVersion("v", "p", 1, "draft", 0, document)
        val expected = mapOf(
            OutputHeaderLanguage.SIMPLIFIED to listOf("问卷编号", "列键", "内部记录 ID", "键"),
            OutputHeaderLanguage.TRADITIONAL to listOf("問卷編號", "欄鍵", "內部記錄 ID", "鍵"),
            OutputHeaderLanguage.ENGLISH to listOf("Questionnaire number", "Column key", "Record ID", "Key"),
        )
        OutputHeaderLanguage.entries.forEach { language ->
            val project = ProjectDetails(ProjectConfig("p", "原始名稱", null, QuestionnaireLanguage.TRADITIONAL_CHINESE,
                NumberingStrategy.GENERATED, 0, 0), OutputProfile("p", language = language), emptyList())
            val snapshot = ExportSheetBuilder.build("snapshot", project, template, emptyList(), emptyList(), emptyMap(), false)
            assertEquals(listOf("Responses", "Codebook", "Review_Flags", "Summary"), snapshot.sheets.map { it.name })
            assertEquals(expected.getValue(language), snapshot.sheets.map { it.headers.first() })
            assertEquals("submission_id", snapshot.sheets[1].rows.first().first().value)
            assertEquals("原始名稱", snapshot.sheets.last().rows.single { it.first().value == "project" }[1].value)
            val note = snapshot.sheets.last().rows.single { it.first().value == "note" }[1].value
            if (language == OutputHeaderLanguage.ENGLISH) assertTrue(note.startsWith("Personal identifiers"))
            val output = ByteArrayOutputStream()
            val counts = XlsxWriter.write(output, snapshot.toXlsxSheets())
            XlsxWriter.validate(output.toByteArray().inputStream(), counts)
        }
    }
}
