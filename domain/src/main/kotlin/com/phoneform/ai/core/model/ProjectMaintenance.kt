package com.phoneform.ai.core.model

import java.io.Closeable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ProjectDeletedException : IllegalStateException("PROJECT_DELETED")
class ProjectStorageBusyException : IllegalStateException("STORAGE_MAINTENANCE_IN_PROGRESS")

/** Coroutine-safe leases; a deletion never waits while holding a database transaction. */
class ProjectOperationGate {
    private val monitor = Any()
    private val blocked = mutableSetOf<String>()
    private val projectLeases = mutableMapOf<String, Int>()
    private var fileLeases = 0
    private var exclusiveFiles = false
    private var changed = CompletableDeferred<Unit>()
    private val fileMaintenance = Mutex()
    private val projectContext = ThreadLocal<String?>()
    private val exclusiveContext = ThreadLocal<Boolean>()
    fun currentProjectId(): String? = projectContext.get()
    private fun signal() { changed.complete(Unit); changed = CompletableDeferred() }
    fun blockProject(projectId: String) = synchronized(monitor) { blocked.add(projectId); signal() }
    fun checkProject(projectId: String) = synchronized(monitor) {
        if (projectId in blocked) throw ProjectDeletedException()
    }
    suspend fun <T> withLease(projectId: String, block: suspend () -> T): T {
        synchronized(monitor) {
            if (projectId in blocked) throw ProjectDeletedException()
            projectLeases[projectId] = (projectLeases[projectId] ?: 0) + 1
        }
        try { return withContext(projectContext.asContextElement(projectId)) { block() } } finally {
            synchronized(monitor) {
                val remaining = (projectLeases[projectId] ?: 1) - 1
                if (remaining == 0) projectLeases.remove(projectId) else projectLeases[projectId] = remaining
                signal()
            }
        }
    }
    suspend fun awaitProjectIdle(projectId: String) {
        while (true) {
            val wait = synchronized(monitor) { if ((projectLeases[projectId] ?: 0) == 0) null else changed }
            (wait ?: return).await()
        }
    }
    fun openFileLease(@Suppress("UNUSED_PARAMETER") path: String): Closeable {
        synchronized(monitor) { if (exclusiveFiles && exclusiveContext.get() != true) throw ProjectStorageBusyException(); fileLeases++ }
        var closed = false
        return Closeable { synchronized(monitor) {
            if (!closed) { closed = true; fileLeases--; signal() }
        } }
    }
    suspend fun <T> withExclusiveFiles(block: suspend () -> T): T = fileMaintenance.withLock {
        synchronized(monitor) { exclusiveFiles = true; signal() }
        try {
            while (true) {
                val wait = synchronized(monitor) { if (fileLeases == 0) null else changed }
                if (wait == null) break
                wait.await()
            }
            withContext(exclusiveContext.asContextElement(true)) { block() }
        } finally { synchronized(monitor) { exclusiveFiles = false; signal() } }
    }
    companion object { val shared = ProjectOperationGate() }
}

data class EditorSession(
    val id: String, val projectId: String?, val payload: String, val revision: Int,
    val updatedAtEpochMillis: Long,
)
interface EditorSessionRepository {
    suspend fun read(sessionId: String): EditorSession?
    /** expectedRevision=null means create or replace the caller's opaque session; otherwise compare-and-save. */
    suspend fun save(sessionId: String, projectId: String?, payload: String, expectedRevision: Int? = null): EditorSession
    suspend fun delete(sessionId: String)
}

enum class ProjectDeletionPhase { WAITING, CLEANING, FAILED, COMPLETE }
data class ProjectDeletionStatus(
    val projectId: String, val operationId: String, val phase: ProjectDeletionPhase,
    val startedAtEpochMillis: Long, val completedAtEpochMillis: Long?, val pendingFiles: Int,
    val releasedBytes: Long, val errorCode: String?,
)
data class ProjectDeletionPreview(
    val projectId: String, val projectName: String, val submissions: Int, val historicalRevisions: Int,
    val templateVersions: Int, val internalExports: Int, val exclusiveBytes: Long, val sharedBytes: Long,
)
/** Exclusive bytes are a union across the whole batch, never a sum of per-project estimates. */
data class ProjectBatchDeletionPreview(val projects: List<ProjectDeletionPreview>, val exclusiveBytes: Long, val sharedBytes: Long)
data class ProjectStorageUsage(val projectId: String, val projectName: String, val exclusiveBytes: Long, val sharedBytes: Long)
data class StorageUsage(
    val originalBytes: Long, val derivedBytes: Long, val exportBytes: Long, val temporaryBytes: Long,
    val databaseBytes: Long, val otherBytes: Long, val projects: List<ProjectStorageUsage>,
    val pendingDeletions: List<ProjectDeletionStatus>,
) { val totalBytes: Long get() = originalBytes + derivedBytes + exportBytes + temporaryBytes + databaseBytes + otherBytes }
interface ProjectMaintenanceRepository {
    fun observeDeletions(): Flow<List<ProjectDeletionStatus>>
    suspend fun previewDeletion(projectId: String): ProjectDeletionPreview
    suspend fun requestDeletion(projectId: String): ProjectDeletionStatus
    suspend fun previewDeletions(projectIds: Set<String>): ProjectBatchDeletionPreview {
        val previews = projectIds.sorted().map { previewDeletion(it) }
        return ProjectBatchDeletionPreview(previews, previews.sumOf { it.exclusiveBytes }, previews.sumOf { it.sharedBytes })
    }
    /** Registers every intent atomically and returns before leases/file cleanup. Resume in application scope. */
    suspend fun requestDeletions(projectIds: Set<String>): List<ProjectDeletionStatus> = projectIds.sorted().map { requestDeletion(it) }
    suspend fun retryDeletion(projectId: String): ProjectDeletionStatus
    suspend fun resumePendingDeletions()
    suspend fun storageUsage(): StorageUsage
    suspend fun cleanupTemporaryFiles(): Long
}
