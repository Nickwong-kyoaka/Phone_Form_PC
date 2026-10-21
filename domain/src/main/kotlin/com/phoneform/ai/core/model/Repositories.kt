package com.phoneform.ai.core.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface ProjectRepository {
    fun observeProjects(): Flow<List<ProjectConfig>>
    fun observeProject(id: String): Flow<ProjectDetails?>
    suspend fun getProject(id: String): ProjectDetails?

    /** Saves the full configuration atomically; a retry with identical content is a no-op. */
    suspend fun saveProject(details: ProjectDetails)
    fun observeProjectSummaries(): Flow<List<ProjectListSummary>> = observeProjects().map { rows -> rows.map { ProjectListSummary(it) } }
    suspend fun saveProjectSection(details: ProjectDetails, section: ProjectEditSection, expectedOutputVersion: Int? = null): ProjectDetails {
        val existing = getProject(details.project.id)
        val merged = when (section) {
            ProjectEditSection.PROJECT_INFO -> existing?.copy(project = existing.project.copy(name = details.project.name, expectedCount = details.project.expectedCount)) ?: details
            ProjectEditSection.INPUT_RULES -> existing?.copy(project = existing.project.copy(questionnaireLanguage = details.project.questionnaireLanguage, numberingStrategy = details.project.numberingStrategy)) ?: details
            ProjectEditSection.PROJECT_SETTINGS -> existing?.copy(project = existing.project.copy(name = details.project.name, expectedCount = details.project.expectedCount,
                questionnaireLanguage = details.project.questionnaireLanguage, numberingStrategy = details.project.numberingStrategy)) ?: details
            ProjectEditSection.OUTPUT -> {
                if (existing != null && existing.output.version != expectedOutputVersion) throw StaleOutputConfigurationException()
                existing?.copy(output = details.output, fields = details.fields) ?: details
            }
        }
        saveProject(merged)
        return getProject(details.project.id) ?: merged
    }
    /** Atomic output/profile/policy save; production implementations must compare the version in their transaction. */
    suspend fun saveOutputConfiguration(details: ProjectDetails, policies: List<OutputFieldPolicy>, expectedVersion: Int): ProjectDetails =
        saveProjectSection(details, ProjectEditSection.OUTPUT, expectedVersion)
}

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun setLanguage(language: UiLanguage)
    suspend fun setCloseBehavior(behavior: CloseBehavior)
    suspend fun completeOnboarding()
}
