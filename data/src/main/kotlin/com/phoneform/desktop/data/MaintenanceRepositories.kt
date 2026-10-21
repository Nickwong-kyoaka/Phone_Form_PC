package com.phoneform.desktop.data

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.phoneform.ai.core.model.*
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal class DesktopSettingsRepository(private val db: DesktopRepositories) : SettingsRepository {
    override val settings = db.observe { get<AppSettings>("settings", "local") ?: AppSettings() }
    override suspend fun setLanguage(language: UiLanguage) { db.write { val old = get<AppSettings>("settings", "local") ?: AppSettings(); put("settings", "local", null, old.copy(language = language)) } }
    override suspend fun setCloseBehavior(behavior: CloseBehavior) { db.write { val old = get<AppSettings>("settings", "local") ?: AppSettings(); put("settings", "local", null, old.copy(closeBehavior = behavior)) } }
    override suspend fun completeOnboarding() { db.write { val old = get<AppSettings>("settings", "local") ?: AppSettings(); put("settings", "local", null, old.copy(onboardingCompleted = true)) } }
}

internal class DesktopEditorRepository(private val db: DesktopRepositories) : EditorSessionRepository {
    override suspend fun read(sessionId: String) = db.read { get<EditorSession>("editor", sessionId) }
    override suspend fun save(sessionId: String, projectId: String?, payload: String, expectedRevision: Int?) = db.write {
        require(sessionId.isNotBlank()); projectId?.let { active(it) }
        val old = get<EditorSession>("editor", sessionId)
        if (old != null && old.projectId != projectId) throw IllegalStateException("EDITOR_OWNER_CHANGED")
        if (expectedRevision != null && (old?.revision ?: 0) != expectedRevision) throw IllegalStateException("EDITOR_CHANGED")
        if (old?.payload == payload) return@write old
        val next = EditorSession(sessionId, projectId, payload, (old?.revision ?: 0) + 1, nextTime(old?.updatedAtEpochMillis ?: 0))
        put("editor", sessionId, projectId, next); next
    }
    override suspend fun delete(sessionId: String) { db.write { get<EditorSession>("editor", sessionId)?.projectId?.let { active(it) }; remove("editor", sessionId) } }
}

internal data class DeletionManifest(val projectId: String, val paths: List<String>)

internal class DesktopMaintenanceRepository(private val db: DesktopRepositories) : ProjectMaintenanceRepository {
    private val gate = ProjectOperationGate.shared
    override fun observeDeletions() = db.observe { list<ProjectDeletionStatus>("deletion").sortedBy { it.startedAtEpochMillis } }
    override suspend fun previewDeletion(projectId: String) = db.read { preview(projectId, references()) }
    override suspend fun previewDeletions(projectIds: Set<String>) = db.read {
        require(projectIds.isNotEmpty()); val references = references()
        val previews = projectIds.sorted().map { preview(it, references) }
        val paths = projectIds.flatMap { references[it].orEmpty() }.toSet()
        val shared = references.filterKeys { it !in projectIds }.values.flatten().toSet()
        ProjectBatchDeletionPreview(previews, bytes(paths - shared), bytes(paths.intersect(shared)))
    }
    private fun Records.preview(projectId: String, references: Map<String, Set<String>>): ProjectDeletionPreview {
        val project = requireProject(projectId)
        val mine = references[projectId].orEmpty(); val other = references.filterKeys { it != projectId }.values.flatten().toSet()
        return ProjectDeletionPreview(projectId, project.project.name, list<Submission>("submission", projectId).count { it.currentRevisionId != null }, list<SubmissionRevision>("revision", projectId).size,
            list<FormSchemaVersion>("template", projectId).size, list<ExportArtifact>("export_artifact", projectId).count { it.relativePath != null }, bytes(mine - other), bytes(mine.intersect(other)))
    }
    override suspend fun requestDeletion(projectId: String) = requestDeletions(setOf(projectId)).single()
    override suspend fun requestDeletions(projectIds: Set<String>): List<ProjectDeletionStatus> {
        require(projectIds.isNotEmpty())
        val result = db.write {
            // Validate every target before registering any intent.
            projectIds.forEach { if (get<ProjectDeletionStatus>("deletion", it) == null) requireProject(it) }
            val references = references()
            projectIds.sorted().map { projectId ->
                get<ProjectDeletionStatus>("deletion", projectId) ?: ProjectDeletionStatus(projectId, newId(), ProjectDeletionPhase.WAITING, System.currentTimeMillis(), null, references[projectId].orEmpty().size, 0, null).also {
                    put("deletion_manifest", projectId, null, DeletionManifest(projectId, references[projectId].orEmpty().toList()))
                    put("deletion", projectId, null, it)
                }
            }
        }
        projectIds.forEach(gate::blockProject); return result
    }
    override suspend fun retryDeletion(projectId: String): ProjectDeletionStatus {
        val previous = db.read { get<ProjectDeletionStatus>("deletion", projectId) } ?: return requestDeletion(projectId)
        if (previous.phase == ProjectDeletionPhase.COMPLETE) return previous
        clean(projectId); return db.read { requireNotNull(get<ProjectDeletionStatus>("deletion", projectId)) }
    }
    override suspend fun resumePendingDeletions() {
        val ids = db.read { list<ProjectDeletionStatus>("deletion").filter { it.phase != ProjectDeletionPhase.COMPLETE }.map { it.projectId } }
        ids.forEach(gate::blockProject); ids.forEach { clean(it) }
    }
    private suspend fun clean(projectId: String) {
        gate.blockProject(projectId); gate.awaitProjectIdle(projectId)
        try { gate.withExclusiveFiles {
            val manifest = db.write {
                val status = requireNotNull(get<ProjectDeletionStatus>("deletion", projectId))
                if (status.phase == ProjectDeletionPhase.COMPLETE) return@write null
                val stored = requireNotNull(get<DeletionManifest>("deletion_manifest", projectId)) { "DELETION_MANIFEST_MISSING" }
                // A lease admitted before the tombstone may publish another encrypted file while
                // deletion waits. Refresh ownership only after those operations have drained.
                val manifest = stored.copy(paths = (stored.paths + db.workspace.assets.ownershipPaths(projectId)).distinct())
                // Validate all identities before the first business row or file is removed.
                manifest.paths.forEach { checkedPath(it) }
                put("deletion_manifest", projectId, null, manifest)
                put("deletion", projectId, null, status.copy(phase = ProjectDeletionPhase.CLEANING, pendingFiles = manifest.paths.size, errorCode = null))
                connection.prepareStatement("DELETE FROM pc_records WHERE project_id=?").use { it.setString(1, projectId); it.executeUpdate() }
                manifest
            } ?: return@withExclusiveFiles
            var released = db.read { get<ProjectDeletionStatus>("deletion", projectId)!!.releasedBytes }
            for (path in manifest.paths) {
                db.write {
                    // Keep the final reference check and unlink under the same database lock.
                    // Another project's save cannot add a reference between these operations.
                    val shared = references(excludeOwnership = projectId).values.any { path in it }
                    if (!shared) { val size = bytes(setOf(path)); if (db.workspace.assets.delete(path)) released += size }
                    val current = requireNotNull(get<DeletionManifest>("deletion_manifest", projectId))
                    val remaining = current.paths.filterNot { it == path }
                    put("deletion_manifest", projectId, null, current.copy(paths = remaining))
                    val status = get<ProjectDeletionStatus>("deletion", projectId)!!
                    put("deletion", projectId, null, status.copy(pendingFiles = remaining.size, releasedBytes = released))
                }
            }
            db.workspace.assets.clearOwnership(projectId)
            db.write {
                val status = get<ProjectDeletionStatus>("deletion", projectId)!!
                put("deletion", projectId, null, status.copy(phase = ProjectDeletionPhase.COMPLETE, completedAtEpochMillis = System.currentTimeMillis(), pendingFiles = 0, releasedBytes = released, errorCode = null))
                remove("deletion_manifest", projectId)
                // Results are shared by fingerprint; remove only results without any surviving page owner.
                val liveFingerprints = livePages().values.flatten().map { PageProcessingFingerprint.of(it) }.toSet()
                list<PageProcessingResult>("image_result").filter { it.inputFingerprint !in liveFingerprints }.forEach { remove("image_result", it.inputFingerprint) }
            }
        } } catch (failure: Exception) {
            db.write { get<ProjectDeletionStatus>("deletion", projectId)?.let { put("deletion", projectId, null, it.copy(phase = ProjectDeletionPhase.FAILED, errorCode = "CLEANUP_FAILED")) } }
        }
    }
    private fun Records.livePages(): Map<String, List<SamplePage>> {
        val result = mutableMapOf<String, MutableList<SamplePage>>()
        fun add(projectId: String, pages: List<SamplePage>) { result.getOrPut(projectId) { mutableListOf() }.addAll(pages) }
        list<TemplateDraft>("template_draft").forEach { add(it.projectId, it.pages) }
        list<FormSchemaVersion>("template").forEach { add(it.projectId, it.document.pages) }
        list<SubmissionDraft>("submission_draft").forEach { add(it.projectId, it.pages.map { p -> p.image }) }
        list<SubmissionRevision>("revision").forEach { add(it.projectId, it.pages.map { p -> p.image }) }
        list<ProcessingJob>("preparation").forEach { add(it.projectId, it.preparedPages.map { p -> p.image }) }
        list<PageProcessingJob>("image_job").forEach { add(it.owner.projectId, listOf(it.page)) }
        return result
    }
    private fun Records.references(excludeOwnership: String? = null): Map<String, Set<String>> {
        val result = mutableMapOf<String, MutableSet<String>>()
        livePages().forEach { (projectId, pages) ->
            val paths = result.getOrPut(projectId) { mutableSetOf() }
            pages.forEach { page ->
                paths += listOf(page.asset.relativePath, page.renderPath, page.thumbnailPath)
                get<PageProcessingResult>("image_result", PageProcessingFingerprint.of(page))?.enhanced?.let { paths += listOf(it.relativePath, it.thumbnailPath) }
            }
        }
        list<ExportArtifact>("export_artifact").forEach { artifact -> get<ExportSnapshot>("export", artifact.snapshotId)?.let { snapshot -> artifact.relativePath?.let { result.getOrPut(snapshot.projectId) { mutableSetOf() } += it } } }
        db.workspace.assets.allOwnershipPaths().filterKeys { it != excludeOwnership }.forEach { (projectId, paths) -> result.getOrPut(projectId) { mutableSetOf() }.addAll(paths) }
        // Only explicit asset fields in editor documents are references. Check their managed
        // identities independently of business rows, which may already have been deleted.
        list<EditorSession>("editor").forEach { editor ->
            val candidates = runCatching { assetStrings(JsonParser.parseString(editor.payload)) }.getOrDefault(emptySet())
            val paths = candidates.filter { runCatching { db.workspace.assets.exists(it) }.getOrDefault(false) }
            result.getOrPut(editor.projectId ?: "__unowned_editor__") { mutableSetOf() }.addAll(paths)
        }
        return result
    }
    private fun assetStrings(value: JsonElement): Set<String> = when {
        value.isJsonArray -> value.asJsonArray.flatMap { assetStrings(it) }.toSet()
        value.isJsonObject -> value.asJsonObject.entrySet().flatMap { (key, child) ->
            if (key in setOf("relativePath", "renderPath", "thumbnailPath") && child.isJsonPrimitive && child.asJsonPrimitive.isString) setOf(child.asString)
            else assetStrings(child)
        }.toSet()
        else -> emptySet()
    }
    private fun checkedPath(relative: String): Path {
        require(relative.isNotBlank() && !relative.contains('\\') && !relative.contains(':') && relative.none { it.code < 32 })
        require(relative.split('/').all { it.isNotBlank() && it != "." && it != ".." })
        val root = db.workspace.assets.root; val path = root.resolve(relative).normalize()
        require(path.startsWith(root) && path != root)
        var ancestor: Path? = path
        while (ancestor != null && ancestor != root) { if (Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) require(ancestor.toRealPath().startsWith(root)); ancestor = ancestor.parent }
        return path
    }
    private fun bytes(paths: Set<String>) = paths.sumOf { val path = checkedPath(it); if (Files.isRegularFile(path)) Files.size(path) else 0 }
    override suspend fun storageUsage(): StorageUsage = db.read {
        val refs = references(); val paths = refs.values.flatten().toSet()
        val originals = livePages().values.flatten().map { it.asset.relativePath }.toSet()
        val exports = list<ExportArtifact>("export_artifact").mapNotNull { it.relativePath }.toSet()
        val derived = paths - originals - exports
        val assetFiles = Files.walk(db.workspace.assets.root).use { stream -> stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.toList() }
        val temporary = assetFiles.filter { it.fileName.toString().endsWith(".part") }.sumOf { Files.size(it) }
        val managedBytes = bytes(paths)
        val diskAssets = assetFiles.sumOf { Files.size(it) }
        val databaseBytes = Files.list(db.workspace.root).use { stream -> stream.filter { it.fileName.toString().startsWith("phoneform.db") }.mapToLong { Files.size(it) }.sum() }
        val projects = list<ProjectDetails>("project").map { project -> val id = project.project.id; val mine = refs[id].orEmpty(); val other = refs.filterKeys { it != id }.values.flatten().toSet(); ProjectStorageUsage(id, project.project.name, bytes(mine - other), bytes(mine.intersect(other))) }
        StorageUsage(bytes(originals), bytes(derived), bytes(exports), temporary, databaseBytes, maxOf(0, diskAssets - managedBytes - temporary), projects, list<ProjectDeletionStatus>("deletion").filter { it.phase != ProjectDeletionPhase.COMPLETE })
    }
    override suspend fun cleanupTemporaryFiles(): Long = gate.withExclusiveFiles {
        val root = db.workspace.assets.root
        val paths = Files.walk(root).use { stream -> stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && it.fileName.toString().startsWith(".") && it.fileName.toString().endsWith(".part") }.toList() }
        var released = 0L
        paths.forEach { path -> require(path.toRealPath().startsWith(root)); val size = Files.size(path); if (Files.deleteIfExists(path)) released += size }
        released
    }
}
