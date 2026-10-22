package com.phoneform.desktop.security

import java.sql.Connection

/** Additive, reconstructible query indexes; schema 2 business records and their history stay unchanged. */
internal object QueryIndexes {
    fun ensure(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("""CREATE INDEX IF NOT EXISTS pc_submission_review_order ON pc_records(
                project_id, CAST(json_extract(payload,'$.number') AS INTEGER), id)
                WHERE kind='submission' AND coalesce(json_extract(payload,'$.discarded'),0)=0
                AND json_extract(payload,'$.currentRevisionId') IS NOT NULL""")
            statement.execute("""CREATE INDEX IF NOT EXISTS pc_project_updated ON pc_records(
                CAST(json_extract(payload,'$.project.updatedAtEpochMillis') AS INTEGER) DESC, id)
                WHERE kind='project'""")
        }
    }
}
