package com.phoneform.ai.core.model

import java.io.OutputStream

/** Only these codes may leave the process in a diagnostic report. Never retain Throwable.message. */
enum class DiagnosticsErrorCode {
    DEVICE_LOCKED, KEY_UNAVAILABLE, FOREGROUND_MIGRATION_REQUIRED, INSUFFICIENT_SPACE,
    CORRUPT_STORAGE, MIGRATION_FAILED, DATABASE_UNREADABLE, DEVICE_INFO_UNAVAILABLE, UNKNOWN_ERROR,
}

enum class DiagnosticsStorageState {
    CHECKING, COPYING_DATABASE, COPYING_FILES, VERIFYING, ACTIVATING, CLEANING_LEGACY, READY, UNAVAILABLE,
}

data class DiagnosticsStorageStatus(
    val state: DiagnosticsStorageState,
    val errorCode: DiagnosticsErrorCode? = null,
    /** null means unknown, not zero; there is at most one active generation cleanup. */
    val pendingMigrationCleanupCount: Int? = null,
)

/** Counts of persisted tasks, including their history; never a questionnaire total. */
data class DiagnosticsTaskCounts(
    val queued: Long = 0, val running: Long = 0, val paused: Long = 0,
    val failed: Long = 0, val ready: Long = 0, val superseded: Long = 0, val unknown: Long = 0,
)

/** A fixed, in-memory snapshot. No business identifiers, paths, arbitrary errors or logs belong here. */
data class DeviceDiagnosticsSnapshot(
    val formatVersion: Int = 1,
    val capturedAtEpochMillis: Long,
    val appVersionName: String?,
    val appVersionCode: Long?,
    val androidApiLevel: Int,
    val androidRelease: String?,
    val manufacturer: String?,
    val model: String?,
    val supportedAbis: List<String>,
    val totalMemoryBytes: Long?,
    val availableMemoryBytes: Long?,
    val availableStorageBytes: Long?,
    val storage: DiagnosticsStorageStatus,
    /** All database-dependent fields are null together if the database cannot be read. */
    val databaseVersion: Int?,
    val preparation: DiagnosticsTaskCounts?,
    val imageProcessing: DiagnosticsTaskCounts?,
    val recognition: DiagnosticsTaskCounts?,
    val pendingProjectDeletions: Long?,
    val errorCodes: Set<DiagnosticsErrorCode> = emptySet(),
)

interface DiagnosticsRepository {
    /** Reads existing state only. Does not initialize storage, schedule jobs or persist the snapshot. */
    suspend fun capture(): DeviceDiagnosticsSnapshot
}

interface DiagnosticsReportWriter {
    /** Validates the entire DTO before writing whitelist-only JSON. Caller owns and closes output. */
    fun write(snapshot: DeviceDiagnosticsSnapshot, output: OutputStream)
}
