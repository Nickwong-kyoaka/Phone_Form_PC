package com.phoneform.desktop

import com.phoneform.ai.core.model.*
import com.phoneform.desktop.data.DesktopUiPreferencesStore
import com.phoneform.desktop.security.SecureWorkspace
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopUiStateTest {
    @get:Rule val folder = TemporaryFolder()
    private fun open(root: Path) = Controller(SecureWorkspace.open(root), Path.of("../runtime/models"), startWorkers = false)
    private suspend fun project(c: Controller, name: String): String {
        c.createProject(name, null, QuestionnaireLanguage.ENGLISH, NumberingStrategy.GENERATED, OutputHeaderLanguage.ENGLISH)
        c.refresh()
        return requireNotNull(c.state.value.project).project.id
    }
    private suspend fun ready(c: Controller, id: String) = withTimeout(15_000) {
        while (c.state.value.projects.none { it.project.id == id }) delay(20)
    }

    @Test fun encryptedLayoutRestoresOnlyExistingProjectsAndPageReferences() = runBlocking {
        val root = folder.newFolder("preferences").toPath()
        val projectId = open(root).use { c ->
            val id = project(c, "Layout persistence")
            c.updateUi { it.copy(theme = ThemeMode.DARK, section = WorkspaceSection.REVIEW, sidebarExpanded = false) }
            c.updateProjectUi(id) { it.copy(reviewSearch = "private-layout-search", reviewFilter = "ALL", paneLeft = 265f, paneRight = 395f) }
            id
        }
        open(root).use { c ->
            ready(c, projectId)
            c.refresh()
            assertEquals(projectId, c.ui.value.projectId)
            assertEquals(ThemeMode.DARK, c.ui.value.theme)
            assertEquals(WorkspaceSection.REVIEW, c.ui.value.section)
            assertFalse(c.ui.value.sidebarExpanded)
            assertEquals("private-layout-search", c.ui.value.projects.getValue(projectId).reviewSearch)
            assertEquals(395f, c.ui.value.projects.getValue(projectId).paneRight, 0f)
        }
        assertFalse(String(Files.readAllBytes(root.resolve("phoneform.db")), Charsets.ISO_8859_1).contains("private-layout-search"))
        SecureWorkspace.open(root).use { workspace ->
            DesktopUiPreferencesStore(workspace.database).save(DesktopUiCodec.encode(DesktopUiState(
                projectId = "deleted-project", projects = mapOf("deleted-project" to ProjectUiState(),
                    projectId to ProjectUiState(templatePageId = "removed-page", templateQuestionId = "removed-question",
                        collectionDraftId = "removed-draft", collectionPageId = "removed-slot",
                        reviewSubmissionId = "removed-submission", reviewTargetKey = "question:removed")))))
        }
        open(root).use { c ->
            ready(c, projectId)
            assertNull(c.ui.value.projectId)
            assertFalse(c.ui.value.projects.containsKey("deleted-project"))
            c.select(projectId).join()
            assertEquals(ProjectUiState(), c.ui.value.projects.getValue(projectId))
            c.select("missing-project").join()
            assertEquals(projectId, c.ui.value.projectId)
            assertEquals(projectId, c.state.value.project?.project?.id)
        }
    }

    @Test fun failedEncryptedSavePreservesLocationAndInputUntilRetrySucceeds() = runBlocking {
        val root = folder.newFolder("failed-save").toPath()
        open(root).use { c ->
            val first = project(c, "First")
            val second = project(c, "Second")
            c.select(first).join()
            c.workspace.database.transaction { connection -> connection.createStatement().use {
                it.execute("CREATE TRIGGER reject_editor_save BEFORE INSERT ON pc_records WHEN NEW.kind='editor' BEGIN SELECT RAISE(ABORT,'EDITOR_SAVE_TEST'); END")
            } }
            try {
                c.queueEditorField("protected-edit", first, "answer", "keep this exact unsaved answer")
                c.select(second).join()
                assertEquals(first, c.ui.value.projectId)
                assertEquals(first, c.state.value.project?.project?.id)
                assertEquals(EditorSaveState.FAILED, c.state.value.editorSaveState)
                c.navigate(WorkspaceSection.EXPORT).join()
                assertEquals(WorkspaceSection.TEMPLATE, c.ui.value.section)
                assertEquals("keep this exact unsaved answer", c.loadEditorField("protected-edit", "answer"))
            } finally {
                c.workspace.database.transaction { connection -> connection.createStatement().use { it.execute("DROP TRIGGER reject_editor_save") } }
            }
            c.flushEdits()
            assertEquals(EditorSaveState.SAVED, c.state.value.editorSaveState)
            c.select(second).join(); c.navigate(WorkspaceSection.EXPORT).join()
            assertEquals(second, c.ui.value.projectId)
            assertEquals(WorkspaceSection.EXPORT, c.ui.value.section)
            assertTrue(c.repositories.loadEditorSession("protected-edit")!!.payload.contains("keep this exact unsaved answer"))
        }
    }
}
