package com.phoneform.desktop.security

import com.google.crypto.tink.StreamingAead
import com.phoneform.ai.core.model.ProjectOperationGate
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

data class AssetInfo(val relativePath: String, val sha256: String, val byteCount: Long)

/** Every persisted byte is ciphertext; AAD binds each file to its stable relative identity. */
class EncryptedAssetStore internal constructor(root: Path, private val streaming: StreamingAead) {
    val root: Path = Files.createDirectories(root).toRealPath()
    private val ownershipMonitor = Any()

    private fun target(relativePath: String): Path {
        require(relativePath.isNotBlank() && !relativePath.contains('\\') && !relativePath.contains(':')) { "Invalid asset identity" }
        require(relativePath.none { it.code < 32 }) { "Invalid asset identity" }
        require(relativePath.split('/').all { it.isNotBlank() && it != "." && it != ".." }) { "Invalid asset identity" }
        val result = root.resolve(relativePath).normalize()
        require(result.startsWith(root) && result != root) { "Invalid asset identity" }
        var ancestor: Path? = result
        while (ancestor != null && ancestor != root) {
            if (Files.exists(ancestor)) require(ancestor.toRealPath().startsWith(root)) { "Asset identity escapes workspace" }
            ancestor = ancestor.parent
        }
        return result
    }

    private fun aad(path: String) = "PhoneForm/PC/file/v1/$path".toByteArray(Charsets.UTF_8)

    fun put(relativePath: String, input: InputStream): AssetInfo = ProjectOperationGate.shared.openFileLease(relativePath).use {
        val target = target(relativePath)
        // A durable encrypted ownership entry precedes publication, including a failed DB save or
        // interrupted import. The ledger is kept until deletion has cleaned every owned object.
        if (!relativePath.startsWith(".owners/")) ProjectOperationGate.shared.currentProjectId()?.let {
            recordOwnership(it, relativePath)
        }
        Files.createDirectories(target.parent)
        target(relativePath) // Recheck existing ancestors after directory creation.
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        atomicCiphertextWrite(target, validate = { candidate ->
            Files.newInputStream(candidate).use { raw -> streaming.newDecryptingStream(raw, aad(relativePath)).use { decoded ->
                val checkDigest = MessageDigest.getInstance("SHA-256")
                var checked = 0L
                val buffer = ByteArray(65536)
                while (true) {
                    val size = decoded.read(buffer)
                    if (size == -1) break
                    checkDigest.update(buffer, 0, size); checked += size
                }
                check(checked == count && MessageDigest.isEqual(checkDigest.digest(), digest.cloneDigest())) { "Encrypted asset verification failed" }
            } }
        }) { raw ->
            streaming.newEncryptingStream(nonClosing(raw), aad(relativePath)).use { encrypted ->
                val buffer = ByteArray(65536)
                while (true) {
                    val size = input.read(buffer)
                    if (size == -1) break
                    if (size == 0) continue
                    digest.update(buffer, 0, size); count += size; encrypted.write(buffer, 0, size)
                }
            }
        }
        AssetInfo(relativePath, digest.digest().joinToString("") { "%02x".format(it) }, count)
    }

    fun open(relativePath: String): InputStream {
        val lease = ProjectOperationGate.shared.openFileLease(relativePath)
        try {
            val raw = Files.newInputStream(target(relativePath))
            val decoded = try { streaming.newDecryptingStream(raw, aad(relativePath)) }
                catch (failure: Throwable) { raw.close(); throw failure }
            return object : FilterInputStream(decoded) {
                override fun close() { try { super.close() } finally { lease.close() } }
            }
        } catch (failure: Throwable) { lease.close(); throw failure }
    }

    fun openSeekable(relativePath: String): SeekableByteChannel {
        val lease = ProjectOperationGate.shared.openFileLease(relativePath)
        try {
            val raw = FileChannel.open(target(relativePath), StandardOpenOption.READ)
            val decoded = try { streaming.newSeekableDecryptingChannel(raw, aad(relativePath)) }
                catch (failure: Throwable) { raw.close(); throw failure }
            // Tink's keyset wrapper cannot expose logical size until a read selects and authenticates
            // the matching key. Prime one byte, then restore offset zero (also works for empty files).
            try { decoded.read(ByteBuffer.allocate(1)); decoded.position(0) }
            catch (failure: Throwable) { decoded.close(); throw failure }
            return object : SeekableByteChannel by decoded {
                override fun close() { try { decoded.close() } finally { lease.close() } }
            }
        } catch (failure: Throwable) { lease.close(); throw failure }
    }

    fun readBytes(relativePath: String): ByteArray = open(relativePath).use(InputStream::readBytes)
    fun delete(relativePath: String): Boolean = Files.deleteIfExists(target(relativePath))
    fun exists(relativePath: String): Boolean = Files.isRegularFile(target(relativePath))

    fun ownershipPaths(projectId: String): Set<String> = synchronized(ownershipMonitor) {
        val path = ownerPath(projectId)
        if (!exists(path)) emptySet() else readOwnership(path, projectId)
    }

    fun allOwnershipPaths(): Map<String, Set<String>> = synchronized(ownershipMonitor) {
        val owners = target(".owners")
        if (!Files.exists(owners)) return@synchronized emptyMap()
        Files.list(owners).use { directories -> directories.toList().mapNotNull { directory ->
            require(Files.isDirectory(directory) && directory.toRealPath().startsWith(root)) { "Invalid ownership directory" }
            val manifest = ".owners/${directory.fileName}/manifest.json"
            if (!exists(manifest)) {
                require(Files.list(directory).use { files -> files.allMatch { it.fileName.toString().endsWith(".part") } }) { "Invalid ownership directory" }
                null
            } else {
                val document = JsonParser.parseString(String(readBytes(manifest), Charsets.UTF_8)).asJsonObject
                val projectId = document.get("projectId").asString
                require(ownerPath(projectId) == manifest) { "Invalid ownership identity" }
                projectId to readOwnership(manifest, projectId)
            }
        }.toMap() }
    }

    /** Call after the deletion journal has no remaining files; do not erase recovery evidence early. */
    fun clearOwnership(projectId: String) = synchronized(ownershipMonitor) {
        delete(ownerPath(projectId))
        Unit
    }

    private fun recordOwnership(projectId: String, relativePath: String) = synchronized(ownershipMonitor) {
        val paths = ownershipPaths(projectId) + relativePath
        val document = JsonObject().apply {
            addProperty("format", 1); addProperty("projectId", projectId)
            add("paths", JsonArray().apply { paths.sorted().forEach(::add) })
        }
        put(ownerPath(projectId), ByteArrayInputStream(document.toString().toByteArray(Charsets.UTF_8)))
        Unit
    }

    private fun ownerPath(projectId: String): String {
        require(projectId.isNotBlank())
        val id = MessageDigest.getInstance("SHA-256").digest(projectId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return ".owners/$id/manifest.json"
    }

    private fun readOwnership(path: String, projectId: String): Set<String> {
        val document = JsonParser.parseString(String(readBytes(path), Charsets.UTF_8)).asJsonObject
        require(document.get("format").asInt == 1 && document.get("projectId").asString == projectId) { "Invalid ownership ledger" }
        return document.getAsJsonArray("paths").map { item ->
            item.asString.also { relative ->
                require(!relative.startsWith(".owners/")) { "Invalid owned file" }
                target(relative)
            }
        }.toSet()
    }
}

private fun MessageDigest.cloneDigest(): ByteArray = (clone() as MessageDigest).digest()

internal fun nonClosing(raw: OutputStream): OutputStream = object : FilterOutputStream(raw) {
    override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
    override fun close() = flush()
}

/** An atomic rename is required. If unsupported, preserve the previous object and report failure. */
internal fun atomicCiphertextWrite(target: Path, validate: (Path) -> Unit = {}, writer: (OutputStream) -> Unit) {
    Files.createDirectories(target.parent)
    val temporary = target.resolveSibling(".${target.fileName}.${UUID.randomUUID()}.part")
    try {
        FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            writer(nonClosing(java.nio.channels.Channels.newOutputStream(channel)))
            channel.force(true)
        }
        validate(temporary)
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally { Files.deleteIfExists(temporary) }
}
