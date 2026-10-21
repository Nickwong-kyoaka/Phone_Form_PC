package com.phoneform.ai.core.model

object ProjectValidation {
    fun isValidName(name: String): Boolean = name.isNotBlank()

    /** Blank means an unknown target; malformed, overflowing and nonpositive values are rejected. */
    fun parseExpectedCount(text: String): Int? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        require(trimmed.all { it in '0'..'9' }) { "Expected count must be a positive integer" }
        val count = trimmed.toIntOrNull()
        require(count != null && count > 0) { "Expected count must be a positive integer" }
        return count
    }

    fun validate(details: ProjectDetails) {
        val project = details.project
        require(project.id.isNotBlank()) { "Project ID is required" }
        require(isValidName(project.name)) { "Project name is required" }
        require(project.expectedCount == null || project.expectedCount > 0) {
            "Expected count must be positive when supplied"
        }
        require(project.templateVersionId == null || project.templateVersionId.isNotBlank()) { "Template ID is empty" }
        require(project.preparationStatus != PreparationStatus.AWAITING_SAMPLE || project.templateVersionId == null) {
            "An awaiting project cannot reference a confirmed template"
        }
        require(project.preparationStatus != PreparationStatus.TEMPLATE_CONFIRMED || project.templateVersionId != null) {
            "A confirmed project must reference a template"
        }
        require(details.output.projectId == project.id) { "Output profile belongs to another project" }
        require(details.output.version > 0) { "Output profile version must be positive" }
        require(details.output.columnLayout.map { it.key }.distinct().size == details.output.columnLayout.size &&
            details.output.columnLayout.all { it.key.isNotBlank() && it.position >= 0 && (it.titleOverride == null || it.titleOverride.isNotBlank()) } &&
            details.output.columnLayout.map { it.position }.distinct().size == details.output.columnLayout.size) { "Invalid output column layout" }
        require(details.fields.all { it.projectId == project.id }) { "Field belongs to another project" }
        require(details.fields.all { it.id.isNotBlank() && it.title.isNotBlank() }) {
            "Field ID and title are required"
        }
        require(details.fields.map { it.id }.distinct().size == details.fields.size) {
            "Field IDs must be unique"
        }
        require(details.fields.map { it.position }.sorted() == details.fields.indices.toList()) {
            "Field positions must start at zero and be contiguous"
        }
        require(details.fields.all { field ->
            when (field.mappingState) {
                FieldMappingState.BOUND -> field.source == FieldSource.QUESTIONNAIRE &&
                    !field.questionId.isNullOrBlank() && project.templateVersionId != null
                FieldMappingState.UNMAPPED, FieldMappingState.MANUAL_ENTRY -> field.questionId == null
            }
        }) {
            "Bound fields require a confirmed template and questionnaire question; other fields cannot bind a question"
        }
    }
}
