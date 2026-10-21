package com.phoneform.desktop.data

import com.phoneform.desktop.security.SecureDatabase

/** Presentation preferences share the authenticated, encrypted database; no plaintext settings file. */
class DesktopUiPreferencesStore(private val database: SecureDatabase) {
    fun load(): String? = database.read { connection ->
        connection.prepareStatement("SELECT value FROM pc_meta WHERE key=?").use { statement ->
            statement.setString(1, KEY)
            statement.executeQuery().use { result -> if (result.next()) result.getString(1) else null }
        }
    }

    fun save(value: String) {
        if (load() == value) return
        database.transaction { connection ->
            connection.prepareStatement("INSERT INTO pc_meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value").use { statement ->
                statement.setString(1, KEY); statement.setString(2, value); statement.executeUpdate()
            }
        }
    }

    private companion object { const val KEY = "desktop_ui_v1" }
}
