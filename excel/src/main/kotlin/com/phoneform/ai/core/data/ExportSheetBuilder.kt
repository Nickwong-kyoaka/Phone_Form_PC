package com.phoneform.ai.core.data

import com.phoneform.ai.core.model.*

/** Only generic quality codes and ordinal page numbers are exported, never OCR text or a free-form keep reason. */
data class ExportQualityFlag(val submissionId: String, val pageNumber: Int, val codes: List<String>, val kept: Boolean)

/** One frozen column plan drives preview, values and codebook; no live database reads while writing. */
object ExportSheetBuilder {
    fun build(id: String, project: ProjectDetails, template: FormSchemaVersion, policies: List<OutputFieldPolicy>,
              workspaces: List<ReviewWorkspace>, excluded: Map<String, String>, includeUnresolved: Boolean,
              qualityFlags: List<ExportQualityFlag> = emptyList()): ExportSnapshot {
        val columns = TemplateColumns.build(project, template.document.copy(mappings = emptyList()), policies)
        val language = project.output.language
        val flags = mutableListOf<List<ExportCell>>()
        fun flag(submissionId: String, columnKey: String, state: String, source: String = "", reason: String = "") {
            flags += listOf(submissionId, columnKey, state, source, reason).map { ExportCell(it) }
        }
        fun key(column: OutputColumn) = column.questionId?.let { "question:$it" } ?: column.key.takeIf { it.startsWith("field_") }?.let { "field:${it.removePrefix("field_")}" }
        qualityFlags.forEach { quality -> flag(quality.submissionId, "completeness", if (quality.kept) "PAGE_QUALITY_KEPT" else "PAGE_QUALITY_UNRESOLVED",
            "IMAGE_PROCESSING", "PAGE_${quality.pageNumber}:${quality.codes.joinToString(";")}") }
        val questions = template.document.questions.associateBy { it.id }
        val rows = workspaces.map { workspace ->
            val saved = workspace.answers?.answers.orEmpty().associateBy { it.targetKey }
            val candidates = workspace.job?.observations.orEmpty().associateBy { it.targetKey }
            if (workspace.confirmation == null) flag(workspace.submission.id, "review_status", "UNCONFIRMED")
            if (workspace.revision.missingTemplatePageIds.isNotEmpty()) flag(workspace.submission.id, "completeness", "MISSING_PAGES",
                reason = workspace.revision.missingTemplatePageIds.joinToString(","))
            columns.map { column ->
                val targetKey = key(column)
                if (targetKey == null) {
                    ExportCell(when (column.key) {
                        "submission_id" -> workspace.submission.number.toString()
                        "record_id" -> workspace.submission.id
                        "review_status" -> if (workspace.confirmation != null) "CONFIRMED" else "UNCONFIRMED"
                        "submission_revision" -> workspace.revision.id
                        "template_version" -> workspace.template.id
                        "completeness" -> if (workspace.revision.missingTemplatePageIds.isEmpty()) "COMPLETE" else "MISSING_PAGES"
                        "respondent_id" -> workspace.revision.respondentId
                        else -> ""
                    })
                } else {
                    val question = column.questionId?.let { questions[it] }
                    val missing = question?.pageId in workspace.revision.missingTemplatePageIds
                    val observation = candidates[targetKey]
                    val savedAnswer = saved[targetKey]
                    val candidate = if (savedAnswer == null || savedAnswer.state == AnswerState.UNHANDLED && !savedAnswer.humanEdited) observation?.candidate else null
                    val answer = when {
                        missing -> AnswerValue(targetKey, AnswerState.NOT_CAPTURED)
                        savedAnswer != null && (savedAnswer.state != AnswerState.UNHANDLED || savedAnswer.humanEdited) -> savedAnswer
                        includeUnresolved && candidate != null -> candidate
                        else -> AnswerValue(targetKey)
                    }
                    val target = workspace.targets.find { it.key == targetKey }
                    val valid = target != null && ReviewTargets.valid(answer, target)
                    if (candidate != null && includeUnresolved && !missing) flag(workspace.submission.id, column.key, "UNCONFIRMED_CANDIDATE", "MACHINE", observation?.reason.orEmpty())
                    if (candidate == null && workspace.confirmation == null && answer.state == AnswerState.VALUE && valid) {
                        flag(workspace.submission.id, column.key, "UNCONFIRMED_VALUE", answer.source.name)
                    }
                    if (answer.state != AnswerState.VALUE || !valid) {
                        flag(workspace.submission.id, column.key, if (valid || answer.state != AnswerState.VALUE) answer.state.name else "INVALID_VALUE", answer.source.name)
                        ExportCell()
                    } else {
                        when (question?.type) {
                            QuestionType.MULTIPLE_CHOICE -> ExportCell(if (column.optionId in answer.optionIds) "1" else "0", ExportCellType.NUMBER)
                            QuestionType.SINGLE_CHOICE -> ExportCell(question.options.single { it.id == answer.optionIds.single() }.code)
                            QuestionType.NUMBER -> {
                                val number = requireNotNull(answer.text.toBigDecimalOrNull())
                                if (number.stripTrailingZeros().precision() > 15 || number.abs() > java.math.BigDecimal("1E308") || (number.signum() != 0 && number.abs() < java.math.BigDecimal("1E-307"))) {
                                    flag(workspace.submission.id, column.key, "NUMBER_PRESERVED_AS_TEXT", answer.source.name)
                                    ExportCell(answer.text)
                                } else ExportCell(number.toPlainString(), ExportCellType.NUMBER)
                            }
                            else -> ExportCell(answer.text)
                        }
                    }
                }
            }
        }
        val codebook = columns.map { column ->
            val question = column.questionId?.let { questions[it] }
            val option = column.optionId?.let { optionId -> question?.options?.find { it.id == optionId } }
            listOf(column.key, column.title, key(column).orEmpty(), question?.type?.name ?: if (column.key.startsWith("field_")) "TEXT" else "SYSTEM",
                option?.code.orEmpty(), option?.title.orEmpty(),
                if (question?.type == QuestionType.SINGLE_CHOICE) question.options.joinToString(" | ") { "${it.code}=${it.title}" } else "",
                if (question?.type == QuestionType.MULTIPLE_CHOICE) "1=SELECTED;0=NOT_SELECTED;empty=MISSING" else "",
                "BLANK;NOT_APPLICABLE;UNREADABLE;NOT_CAPTURED;UNHANDLED").map { ExportCell(it) }
        }
        val summary = listOf(
            listOf("snapshot_id", id), listOf("project", project.project.name), listOf("output_version", project.output.version.toString()),
            listOf("included", workspaces.size.toString()), listOf("excluded", excluded.size.toString()),
            listOf("include_unresolved", includeUnresolved.toString()), listOf("include_personal_identifiers", project.output.includePersonalIdentifiers.toString()),
            listOf("note", language.label("个人标识按人工标记排除，未自动检查自由文字", "個人標識按人工標記排除，未自動檢查自由文字", "Personal identifiers are excluded using manual markings; free text is not automatically checked.")),
        ).map { row -> row.map { ExportCell(it) } } + excluded.map { (submission, reason) -> listOf(ExportCell("excluded:$submission"), ExportCell(reason)) }
        val sheets = listOf(
            ExportSheet("Responses", columns.map { it.title }, rows),
            ExportSheet("Codebook", listOf(
                language.label("列键", "欄鍵", "Column key"), language.label("标题", "標題", "Title"),
                language.label("目标键", "目標鍵", "Target key"), language.label("类型", "類型", "Type"),
                language.label("选项代码", "選項代碼", "Option code"), language.label("选项标题", "選項標題", "Option title"),
                language.label("代码", "代碼", "Codes"), language.label("多选规则", "多選規則", "Multiple choice rules"),
                language.label("缺失状态", "缺失狀態", "Missing states")), codebook),
            ExportSheet("Review_Flags", listOf(
                language.label("内部记录 ID", "內部記錄 ID", "Record ID"), language.label("列键", "欄鍵", "Column key"),
                language.label("状态", "狀態", "State"), language.label("来源", "來源", "Source"),
                language.label("原因", "原因", "Reason")), flags),
            ExportSheet("Summary", listOf(language.label("键", "鍵", "Key"), language.label("值", "值", "Value")), summary),
        )
        sheets.forEach { sheet ->
            require(sheet.headers.size <= 16384 && sheet.rows.size < 1048576) { "EXCEL_LIMIT_EXCEEDED" }
            require(sheet.headers.all { it.length <= 32767 } && sheet.rows.all { row -> row.all { it.value.length <= 32767 } }) { "EXCEL_CELL_LIMIT_EXCEEDED" }
        }
        return ExportSnapshot(id, project.project.id, project.project.name, project.output.version, includeUnresolved, System.currentTimeMillis(),
            workspaces.map { ExportRecordReference(it.submission.id, it.revision.id, it.answers?.id, it.confirmation?.id) }, excluded, sheets)
    }
}
