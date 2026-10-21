package com.phoneform.desktop.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.sqlite.mc.SQLiteMCSqlCipherConfig
import java.io.Closeable
import java.io.IOException
import java.nio.file.Path
import java.sql.Connection

/** One serialized JDBC connection; every business mutation is committed before observers are notified. */
class SecureDatabase private constructor(private val connection: Connection) : Closeable {
    private val monitor = Any()
    private val mutableChanges = MutableStateFlow(0L)
    val changes: StateFlow<Long> = mutableChanges.asStateFlow()

    fun <T> read(block: (Connection) -> T): T = synchronized(monitor) { block(connection) }

    fun <T> transaction(block: (Connection) -> T): T = synchronized(monitor) {
        check(connection.autoCommit) { "Nested transactions are not supported" }
        connection.autoCommit = false
        try {
            val result = block(connection)
            connection.commit()
            mutableChanges.value += 1
            result
        } catch (failure: Throwable) {
            runCatching { connection.rollback() }.onFailure { failure.addSuppressed(it) }
            throw failure
        } finally { connection.autoCommit = true }
    }

    override fun close() = synchronized(monitor) { connection.close() }

    companion object {
        internal fun open(path: Path, key: ByteArray): SecureDatabase {
            Class.forName("org.sqlite.JDBC")
            // Explicit SQLCipher v4 settings: SHA512 KDF/HMAC, page authentication, fully encrypted header.
            val config = SQLiteMCSqlCipherConfig.getV4Defaults().setHmacUse(true).setPlaintextHeaderSize(0)
                .withKey(key.joinToString("") { "%02x".format(it) }).build()
            val properties = config.toProperties().apply { setProperty("hmac_check", "1"); setProperty("enable_load_extension", "false") }
            val connection = java.sql.DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}", properties)
            try {
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA cipher").use { result ->
                        if (!result.next() || !result.getString(1).equals("sqlcipher", true)) throw IOException("Encrypted SQLite cipher is unavailable")
                    }
                    // Force key authentication before schema migration; never retry as plaintext.
                    statement.executeQuery("SELECT count(*) FROM sqlite_master").use { it.next() }
                    statement.execute("PRAGMA temp_store=MEMORY")
                    statement.execute("PRAGMA secure_delete=ON")
                    statement.execute("PRAGMA foreign_keys=ON")
                    statement.execute("PRAGMA busy_timeout=10000")
                    statement.execute("PRAGMA journal_mode=DELETE")
                    statement.execute("PRAGMA synchronous=FULL")
                }
                return SecureDatabase(connection).apply { transaction { db ->
                    db.createStatement().use { statement ->
                        val version = statement.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) }
                        if (version > 1) throw IOException("Workspace was created by a newer version of PhoneForm")
                        statement.execute("CREATE TABLE IF NOT EXISTS pc_records(kind TEXT NOT NULL,id TEXT NOT NULL,project_id TEXT,payload TEXT NOT NULL,PRIMARY KEY(kind,id))")
                        statement.execute("CREATE INDEX IF NOT EXISTS pc_records_project ON pc_records(project_id,kind)")
                        statement.execute("CREATE TABLE IF NOT EXISTS pc_meta(key TEXT PRIMARY KEY NOT NULL,value TEXT NOT NULL)")
                        statement.execute("PRAGMA user_version=1")
                    }
                } }
            } catch (failure: Throwable) { connection.close(); throw failure }
        }
    }
}
