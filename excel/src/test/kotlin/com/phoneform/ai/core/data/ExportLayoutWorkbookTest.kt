package com.phoneform.ai.core.data

import com.phoneform.ai.core.model.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Reproducible synthetic workbook for an independent openpyxl/Excel check. */
class ExportLayoutWorkbookTest {
    @Test fun selectedColumnsRenamedOrderMissingStatesAndPrivacyShareOneFrozenPlan() {
        val region = NormalizedRect(.1f, .1f, .9f, .9f)
        val choice = QuestionDefinition("choice", "choices-page", 0, "1", "多选", QuestionType.MULTIPLE_CHOICE, region, region,
            listOf(OptionDefinition("a", "甲", "01", region), OptionDefinition("b", "乙", "02", region)))
        val number = QuestionDefinition("number", "number-page", 0, "2", "精确数字", QuestionType.NUMBER, region, region)
        val secret = QuestionDefinition("secret", "number-page", 1, "3", "PRIVATE_TITLE_V08", QuestionType.TEXT, region, region)
        val excluded = QuestionDefinition("excluded", "number-page", 2, "4", "EXCLUDED_TITLE_V08", QuestionType.TEXT, region, region)
        val document = TemplateDraft("draft", "p", questions = listOf(choice, number, secret, excluded), updatedAtEpochMillis = 1)
        val template = FormSchemaVersion("template", "p", 1, "draft", 1, document)
        val project = ProjectDetails(ProjectConfig("p", "合成导出布局", null, QuestionnaireLanguage.SIMPLIFIED_CHINESE,
            NumberingStrategy.PAPER_ID, 1, 1, PreparationStatus.TEMPLATE_CONFIRMED, template.id),
            OutputProfile("p", 8, language = OutputHeaderLanguage.SIMPLIFIED, columnLayout = listOf(
                OutputColumnLayout("question_number", "重复标题", 0), OutputColumnLayout("field_note", "人工编号", 1),
                OutputColumnLayout("field_alias_a", "重复标题", 2))), listOf(
                RequestedOutputField("note", "p", "人工列", 0, FieldSource.MANUAL_METADATA, mappingState = FieldMappingState.MANUAL_ENTRY),
                RequestedOutputField("alias", "p", "多选别名", 1, FieldSource.QUESTIONNAIRE, choice.id, FieldMappingState.BOUND)))
        fun workspace(id: String, missing: Boolean): ReviewWorkspace {
            val submission = Submission(id, "p", if (missing) 2 else 1, template.id, NumberingStrategy.PAPER_ID, 1,
                currentRevisionId = "rev-$id", respondentId = "PRIVATE_RESPONDENT_V08")
            val revision = SubmissionRevision("rev-$id", id, "p", template.id, 1, "draft-$id", 1,
                "PRIVATE_RESPONDENT_V08", emptyList(), if (missing) listOf("choices-page") else emptyList(), SubmissionConfirmation(allowMissingPages = missing), 1)
            val answers = AnswerSetRevision("answers-$id", "p", id, revision.id, 1, "op-$id", listOf(
                AnswerValue("field:note", AnswerState.VALUE, if (missing) "=SUM(1,2)" else "0007", humanEdited = true),
                AnswerValue("question:choice", if (missing) AnswerState.NOT_CAPTURED else AnswerState.VALUE, optionIds = if (missing) emptyList() else listOf("a")),
                AnswerValue("question:number", if (missing) AnswerState.NOT_APPLICABLE else AnswerState.VALUE, if (missing) "" else "12345678901234567"),
                AnswerValue("question:secret", AnswerState.VALUE, "PRIVATE_ANSWER_V08"),
                AnswerValue("question:excluded", AnswerState.VALUE, "EXCLUDED_ANSWER_V08")))
            val confirmation = if (missing) null else ReviewConfirmation("confirmation-$id", revision.id, answers.id, "confirm-$id", 1)
            return ReviewWorkspace(project, submission, revision, template, null, answers, confirmation, ReviewTargets.build(project, template))
        }
        val snapshot = ExportSheetBuilder.build("v08-layout", project, template,
            listOf(OutputFieldPolicy("p", "question:secret", true), OutputFieldPolicy("p", "question:excluded", false, false)),
            listOf(workspace("complete", false), workspace("missing", true)), emptyMap(), true)
        assertEquals(listOf("Responses", "Codebook", "Review_Flags", "Summary"), snapshot.sheets.map { it.name })
        val responses = snapshot.sheets.first()
        assertEquals(10, responses.headers.size)
        assertEquals(10, responses.headers.distinct().size)
        assertEquals(listOf("12345678901234567", "0007", "1", "0"), responses.rows.first().drop(6).map { it.value })
        assertEquals(ExportCellType.TEXT, responses.rows.first()[6].type)
        assertEquals(listOf("", "=SUM(1,2)", "", ""), responses.rows.last().drop(6).map { it.value })
        val text = snapshot.sheets.flatMap { it.headers + it.rows.flatten().map { cell -> cell.value } }.joinToString("|")
        assertFalse(text.contains("PRIVATE_"))
        assertFalse(text.contains("EXCLUDED_"))
        assertTrue(snapshot.sheets.single { it.name == "Review_Flags" }.rows.any { it[2].value == "NOT_CAPTURED" })
        val file = File("build/validation/xlsx-v08-layout.xlsx").apply { parentFile?.mkdirs() }
        val counts = file.outputStream().use { output -> XlsxWriter.write(output, snapshot.sheets.map { sheet -> XlsxSheet(sheet.name, sheet.headers) {
            sheet.rows.asSequence().map { row -> row.map { cell -> when {
                cell.value.isEmpty() -> XlsxCell.Empty
                cell.type == ExportCellType.NUMBER -> XlsxCell.Number(cell.value)
                else -> XlsxCell.Text(cell.value)
            } } }
        } }) }
        XlsxWriter.validate(file, counts)
    }
}
