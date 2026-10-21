package com.phoneform.desktop.security

import com.sun.jna.Platform
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.SecureRandom
import java.sql.DriverManager
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SecureWorkspaceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val protector = TestKeyProtector()

    @Test fun encryptedDatabaseAndStreamingAssetsReopenWithoutPlaintext() {
        val root = temporary.newFolder().toPath()
        val sentinel = "private-questionnaire-001-繁體-SENTINEL"
        val payload = (sentinel.repeat(70000)).toByteArray()
        SecureWorkspace.open(root, protector).use { workspace ->
            workspace.database.transaction { db -> db.prepareStatement("INSERT INTO pc_records VALUES(?,?,?,?)").use {
                it.setString(1, "project"); it.setString(2, "001"); it.setString(3, "001"); it.setString(4, sentinel); it.executeUpdate()
            } }
            val info = workspace.assets.put("sample_assets/test.bin", ByteArrayInputStream(payload))
            assertEquals(payload.size.toLong(), info.byteCount)
            assertEquals(64, info.sha256.length)
            workspace.assets.openSeekable(info.relativePath).use { channel ->
                assertEquals(payload.size.toLong(), channel.size())
                val bytes = ByteArray(100)
                channel.position(1_048_570)
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) assertTrue(channel.read(buffer) > 0)
                assertArrayEquals(payload.copyOfRange(1_048_570, 1_048_670), bytes)
            }
        }
        assertFalse(String(Files.readAllBytes(root.resolve("phoneform.db")), Charsets.ISO_8859_1).startsWith("SQLite format 3"))
        Files.walk(root).use { files -> files.filter { Files.isRegularFile(it) }.forEach {
            assertFalse("Plaintext found in $it", String(Files.readAllBytes(it), Charsets.UTF_8).contains(sentinel))
        } }
        SecureWorkspace.open(root, protector).use { workspace ->
            assertArrayEquals(payload, workspace.assets.readBytes("sample_assets/test.bin"))
            assertEquals(sentinel, workspace.database.read { db -> db.createStatement().use { statement ->
                statement.executeQuery("SELECT payload FROM pc_records").use { it.next(); it.getString(1) }
            } })
        }
        expectFailure { DriverManager.getConnection("jdbc:sqlite:${root.resolve("phoneform.db")}").use {
            it.createStatement().use { query -> query.executeQuery("SELECT * FROM pc_records").close() }
        } }
        expectFailure { SecureDatabase.open(root.resolve("phoneform.db"), ByteArray(32) { 42 }).close() }
    }

    @Test fun tamperAndRenamedIdentityAreRejected() {
        val root = temporary.newFolder().toPath()
        SecureWorkspace.open(root, protector).use { workspace ->
            workspace.assets.put("a.bin", ByteArrayInputStream(ByteArray(1_100_000) { (it % 251).toByte() }))
            Files.copy(workspace.assets.root.resolve("a.bin"), workspace.assets.root.resolve("b.bin"))
            expectFailure { workspace.assets.readBytes("b.bin") }
            val path = workspace.assets.root.resolve("a.bin")
            val ciphertext = Files.readAllBytes(path)
            ciphertext[ciphertext.lastIndex - 4] = (ciphertext[ciphertext.lastIndex - 4].toInt() xor 1).toByte()
            Files.write(path, ciphertext)
            expectFailure { workspace.assets.readBytes("a.bin") }
            expectFailure { workspace.assets.readBytes("../outside") }
            expectFailure { workspace.assets.put("x:stream", ByteArrayInputStream(byteArrayOf(1))) }
        }
    }

    @Test fun interruptedWritePreservesPreviousObjectAndTransactionsRollBack() {
        SecureWorkspace.open(temporary.newFolder().toPath(), protector).use { workspace ->
            workspace.assets.put("one.bin", ByteArrayInputStream("original".toByteArray()))
            expectFailure { workspace.assets.put("one.bin", object : InputStream() {
                override fun read(): Int = throw IOException("Simulated input failure")
            }) }
            assertEquals("original", String(workspace.assets.readBytes("one.bin")))
            assertEquals(0L, Files.list(workspace.assets.root).use { it.filter { file -> file.fileName.toString().endsWith(".part") }.count() })
            val changes = workspace.database.changes.value
            expectFailure { workspace.database.transaction { db ->
                db.createStatement().use { it.executeUpdate("INSERT INTO pc_meta VALUES('bad','private')") }
                throw IOException("Simulated transaction failure")
            } }
            assertEquals(changes, workspace.database.changes.value)
            assertEquals(0, workspace.database.read { db -> db.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM pc_meta").use { it.next(); it.getInt(1) }
            } })
        }
    }

    @Test fun wrongProtectorOrMissingEnvelopeNeverResetsExistingData() {
        val root = temporary.newFolder().toPath()
        SecureWorkspace.open(root, protector).use { it.assets.put("protected.bin", ByteArrayInputStream("private".toByteArray())) }
        val database = Files.readAllBytes(root.resolve("phoneform.db"))
        expectFailure { SecureWorkspace.open(root, TestKeyProtector()).close() }
        assertArrayEquals(database, Files.readAllBytes(root.resolve("phoneform.db")))
        Files.delete(root.resolve("keys.v1.wrapped"))
        expectFailure { SecureWorkspace.open(root, protector).close() }
        assertArrayEquals(database, Files.readAllBytes(root.resolve("phoneform.db")))
    }

    @Test fun actualWindowsDpapiAuthenticatesPurposeAndReopens() {
        assumeTrue(Platform.isWindows())
        val plain = "test-storage-secret".toByteArray()
        val wrapped = DpapiKeyProtector.protect(plain, "test-purpose")
        assertArrayEquals(plain, DpapiKeyProtector.unprotect(wrapped, "test-purpose"))
        expectFailure { DpapiKeyProtector.unprotect(wrapped, "wrong-purpose") }
        val root = temporary.newFolder().toPath()
        SecureWorkspace.open(root).use { it.assets.put("dpapi.bin", ByteArrayInputStream(plain)) }
        SecureWorkspace.open(root).use { assertArrayEquals(plain, it.assets.readBytes("dpapi.bin")) }
    }
}

/** Authenticated fake exists only in tests; production always defaults to CurrentUser DPAPI. */
internal class TestKeyProtector : KeyProtector {
    private val key = SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "AES")
    override fun protect(plain: ByteArray, purpose: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(purpose.toByteArray())
        return cipher.iv + cipher.doFinal(plain)
    }
    override fun unprotect(wrapped: ByteArray, purpose: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrapped.copyOfRange(0, 12)))
        cipher.updateAAD(purpose.toByteArray())
        return cipher.doFinal(wrapped, 12, wrapped.size - 12)
    }
}

internal fun expectFailure(block: () -> Unit): Throwable {
    try { block() } catch (failure: Exception) { return failure }
    throw AssertionError("Expected an authenticated failure")
}
