package com.phoneform.desktop.security

import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.BinaryKeysetWriter
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.google.crypto.tink.streamingaead.StreamingAeadKeyTemplates
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.SecureRandom

class SecureWorkspace private constructor(
    val root: Path,
    val database: SecureDatabase,
    val assets: EncryptedAssetStore,
    private val lockChannel: FileChannel,
    private val fileLock: FileLock,
) : Closeable {
    override fun close() {
        try { database.close() } finally {
            try { fileLock.release() } finally { lockChannel.close() }
        }
    }

    companion object {
        private const val MAGIC = 0x50464b31
        private const val DB_PURPOSE = "PhoneForm/PC/database-key/v1"
        private const val ASSET_PURPOSE = "PhoneForm/PC/asset-key/v1"

        @Synchronized
        fun open(root: Path, protector: KeyProtector = DpapiKeyProtector): SecureWorkspace {
            Files.createDirectories(root)
            val canonical = root.toRealPath()
            val channel = FileChannel.open(canonical.resolve("workspace.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock = try { channel.tryLock() ?: throw IOException("Workspace is already open") }
                catch (failure: Exception) { channel.close(); throw IOException("Workspace is already open", failure) }
            try {
                StreamingAeadConfig.register()
                val envelope = canonical.resolve("keys.v1.wrapped")
                val databasePath = canonical.resolve("phoneform.db")
                val assetDirectory = canonical.resolve("assets")
                if (!Files.exists(envelope)) {
                    val existingAssets = Files.exists(assetDirectory) && Files.walk(assetDirectory).use { files -> files.anyMatch { Files.isRegularFile(it) } }
                    if (Files.exists(databasePath) || existingAssets) throw IOException("Storage key envelope is missing; existing data was preserved")
                    val databaseKey = ByteArray(32).also(SecureRandom()::nextBytes)
                    val handle = KeysetHandle.generateNew(StreamingAeadKeyTemplates.AES256_GCM_HKDF_1MB)
                    val keyset = ByteArrayOutputStream().also { CleartextKeysetHandle.write(handle, BinaryKeysetWriter.withOutputStream(it)) }.toByteArray()
                    try {
                        val wrappedDatabase = protector.protect(databaseKey, DB_PURPOSE)
                        val wrappedAssets = protector.protect(keyset, ASSET_PURPOSE)
                        atomicCiphertextWrite(envelope) { raw -> DataOutputStream(raw).apply {
                            writeInt(MAGIC); writeInt(wrappedDatabase.size); write(wrappedDatabase)
                            writeInt(wrappedAssets.size); write(wrappedAssets); flush()
                        } }
                    } finally { databaseKey.fill(0); keyset.fill(0) }
                }
                if (Files.size(envelope) !in 16L..131072L) throw IOException("Invalid storage key envelope")
                val wrapped = DataInputStream(Files.newInputStream(envelope)).use { input ->
                    if (input.readInt() != MAGIC) throw IOException("Unsupported storage key envelope")
                    fun part(): ByteArray {
                        val length = input.readInt()
                        if (length !in 1..65536) throw IOException("Invalid storage key envelope")
                        return ByteArray(length).also(input::readFully)
                    }
                    val pair = part() to part()
                    if (input.read() != -1) throw IOException("Invalid storage key envelope")
                    pair
                }
                val databaseKey = protector.unprotect(wrapped.first, DB_PURPOSE)
                val keyset = try { protector.unprotect(wrapped.second, ASSET_PURPOSE) }
                    catch (failure: Exception) { databaseKey.fill(0); throw failure }
                try {
                    if (databaseKey.size != 32 || keyset.size !in 1..32768) throw IOException("Invalid unwrapped storage keys")
                    val handle = CleartextKeysetHandle.read(BinaryKeysetReader.withBytes(keyset))
                    val assets = EncryptedAssetStore(assetDirectory, handle.getPrimitive(StreamingAead::class.java))
                    val database = SecureDatabase.open(databasePath, databaseKey)
                    return SecureWorkspace(canonical, database, assets, channel, lock)
                } finally { databaseKey.fill(0); keyset.fill(0) }
            } catch (failure: Throwable) {
                try { lock.release() } finally { channel.close() }
                throw failure
            }
        }
    }
}
