package com.phoneform.ai.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectValidationTest {
    @Test
    fun countCanBeUnknownButCannotBeMalformedOrNonpositive() {
        assertNull(ProjectValidation.parseExpectedCount("  "))
        assertEquals(12, ProjectValidation.parseExpectedCount(" 12 "))
        assertEquals(Int.MAX_VALUE, ProjectValidation.parseExpectedCount(Int.MAX_VALUE.toString()))
        listOf("0", "-1", "+3", "1.5", "12份", "2147483648", "一百").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                ProjectValidation.parseExpectedCount(value)
            }
        }
    }

    @Test
    fun chineseAndEnglishNamesAreAcceptedWithoutRewritingTheirContents() {
        assertTrue(ProjectValidation.isValidName("家庭問卷"))
        assertTrue(ProjectValidation.isValidName("Survey A"))
        assertFalse(ProjectValidation.isValidName(" \n\t"))
        val details = details(name = " 原始問卷 ")
        ProjectValidation.validate(details)
        assertEquals(" 原始問卷 ", details.project.name)
    }

    @Test
    fun validUnboundProjectAllowsMetadataAndQuestionnaireFields() {
        val details = details().copy(fields = listOf(
            field("age", 0, FieldSource.QUESTIONNAIRE),
            field("site", 1, FieldSource.MANUAL_METADATA),
        ))
        ProjectValidation.validate(details)
        assertNull(details.project.templateVersionId)
        assertEquals(PreparationStatus.AWAITING_SAMPLE, details.project.preparationStatus)
        assertEquals(ExportFormat.XLSX, details.output.format)
        assertFalse(details.output.includePersonalIdentifiers)
    }

    @Test
    fun incompleteOrCrossProjectConfigurationsAreRejected() {
        val valid = details()
        val invalid = listOf(
            valid.copy(project = valid.project.copy(name = " ")),
            valid.copy(project = valid.project.copy(expectedCount = 0)),
            valid.copy(output = valid.output.copy(projectId = "another")),
            valid.copy(fields = listOf(field("age", 0).copy(projectId = "another"))),
            valid.copy(fields = listOf(field("age", 0).copy(title = " "))),
            valid.copy(fields = listOf(field("", 0))),
            valid.copy(fields = listOf(field("age", 0), field("age", 1))),
            valid.copy(fields = listOf(field("age", 0), field("site", 0))),
            valid.copy(fields = listOf(field("age", 1))),
        )
        invalid.forEach { configuration ->
            assertThrows(IllegalArgumentException::class.java) {
                ProjectValidation.validate(configuration)
            }
        }
    }

    @Test
    fun bindingRequiresConsistentTemplateStatusAndQuestionMapping() {
        val valid = details()
        assertThrows(IllegalArgumentException::class.java) {
            ProjectValidation.validate(valid.copy(project = valid.project.copy(templateVersionId = "template")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProjectValidation.validate(valid.copy(fields = listOf(field("age", 0).copy(questionId = "q1"))))
        }
        val confirmed = valid.copy(
            project = valid.project.copy(preparationStatus = PreparationStatus.TEMPLATE_CONFIRMED, templateVersionId = "template"),
            fields = listOf(field("age", 0).copy(questionId = "q1", mappingState = FieldMappingState.BOUND)),
        )
        ProjectValidation.validate(confirmed)
        ProjectValidation.validate(confirmed.copy(project = confirmed.project.copy(preparationStatus = PreparationStatus.TEMPLATE_DRAFT)))
        assertThrows(IllegalArgumentException::class.java) {
            ProjectValidation.validate(confirmed.copy(fields = listOf(confirmed.fields.single().copy(source = FieldSource.MANUAL_METADATA))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProjectValidation.validate(confirmed.copy(project = confirmed.project.copy(templateVersionId = null)))
        }
    }

    private fun details(name: String = "测试问卷") = ProjectDetails(
        project = ProjectConfig(
            id = "project", name = name, expectedCount = null,
            questionnaireLanguage = QuestionnaireLanguage.MIXED_CHINESE,
            numberingStrategy = NumberingStrategy.GENERATED,
            createdAtEpochMillis = 100L, updatedAtEpochMillis = 100L,
        ),
        output = OutputProfile(projectId = "project", language = OutputHeaderLanguage.SIMPLIFIED),
        fields = emptyList(),
    )

    private fun field(id: String, position: Int, source: FieldSource = FieldSource.QUESTIONNAIRE) =
        RequestedOutputField(id, "project", id, position, source)
}
