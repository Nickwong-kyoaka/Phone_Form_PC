package com.phoneform.desktop.security

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Path
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

interface PdfSession : Closeable {
    val pageCount: Int
    val processId: Long
    val isAlive: Boolean
    /** Zero-based PDF page index. The returned PNG exists only in memory. */
    fun render(pageIndex: Int, dpi: Int = 150): ByteArray
    fun cancel()
}

class PdfFailure(val reason: String, cause: Throwable? = null) : IOException("PDF operation failed: $reason", cause)

/** A fresh bounded helper JVM parses each document. It receives ranges, never keys or source paths. */
class PdfClient(private val assets: EncryptedAssetStore, private val timeoutSeconds: Long = 45) {
    fun open(relativePath: String): PdfSession {
        val channel = assets.openSeekable(relativePath)
        val size = try { channel.size() } catch (failure: Throwable) { channel.close(); throw failure }
        if (size !in 1L..MAX_DOCUMENT_BYTES) { channel.close(); throw PdfFailure("DOCUMENT_SIZE") }
        val classpath = System.getProperty("phoneform.pdf.classpath") ?: helperClasspath()
        val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java").toString()
        val process = try {
            ProcessBuilder(java, "-Xmx512m", "-Djava.awt.headless=true", "-cp", classpath,
                "com.phoneform.desktop.pdfworker.MainKt")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        } catch (failure: Throwable) { channel.close(); throw PdfFailure("HELPER_START", failure) }
        val session = RemotePdfSession(process, channel, timeoutSeconds)
        try { session.start(size); return session }
        catch (failure: Throwable) { session.cancel(); throw failure }
    }

    companion object { const val MAX_DOCUMENT_BYTES = 1024L * 1024 * 1024 }

    private fun helperClasspath(): String {
        // jpackage launchers may use an application class loader whose dependencies are absent from
        // java.class.path. Resolve actual packaged JAR locations without passing data paths or keys.
        val anchors = listOf("com.phoneform.desktop.pdfworker.MainKt", "kotlin.jvm.internal.Intrinsics",
            "org.apache.pdfbox.Loader", "org.apache.pdfbox.io.RandomAccessRead",
            "org.apache.fontbox.FontBoxFont", "org.apache.commons.logging.LogFactory")
        val jars = anchors.map { name ->
            try { Path.of(Class.forName(name, false, PdfClient::class.java.classLoader).protectionDomain.codeSource.location.toURI()).toString() }
            catch (failure: Exception) { throw PdfFailure("HELPER_CLASSPATH", failure) }
        }
        return (jars + System.getProperty("java.class.path", "").split(File.pathSeparator).filter { it.isNotBlank() })
            .distinct().joinToString(File.pathSeparator)
    }
}

private class RemotePdfSession(
    private val process: Process,
    private val source: SeekableByteChannel,
    private val timeoutSeconds: Long,
) : PdfSession {
    private val input = DataInputStream(process.inputStream.buffered())
    private val output = DataOutputStream(process.outputStream.buffered())
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "pdf-helper-${process.pid()}").apply { isDaemon = true } }
    private val closed = AtomicBoolean(false)
    private val operation = Any()
    private var pages = 0
    override val pageCount: Int get() = pages
    override val processId: Long get() = process.pid()
    override val isAlive: Boolean get() = process.isAlive

    fun start(size: Long) = request {
        output.writeInt(MAGIC); output.writeLong(size); output.flush()
        pump(READY).also { pages = ByteBuffer.wrap(it).int }
        if (pages !in 1..10000) throw PdfFailure("PAGE_COUNT")
    }

    override fun render(pageIndex: Int, dpi: Int): ByteArray {
        require(pageIndex in 0 until pageCount) { "PDF page index is out of range" }
        require(dpi in 36..300) { "PDF DPI must be between 36 and 300" }
        return request {
            output.writeInt(RENDER); output.writeInt(pageIndex); output.writeInt(dpi); output.flush()
            pump(PNG)
        }
    }

    private fun <T> request(block: () -> T): T = synchronized(operation) {
        if (closed.get()) throw PdfFailure("CLOSED")
        val future = executor.submit<T> { block() }
        try { future.get(timeoutSeconds, TimeUnit.SECONDS) }
        catch (failure: Exception) {
            cancel()
            val cause = failure.cause ?: failure
            if (cause is PdfFailure) throw cause
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            throw PdfFailure(if (failure is TimeoutException) "TIMEOUT" else "HELPER_STOPPED", cause)
        }
    }

    private fun pump(expected: Int): ByteArray {
        var totalRead = 0L
        // A bounded budget also prevents a broken child from requesting the same source forever.
        val budget = maxOf(source.size() * 64, 64L * 1024 * 1024)
        while (!closed.get()) {
            when (val event = input.readInt()) {
                RANGE -> {
                    val offset = input.readLong()
                    val length = input.readInt()
                    val size = source.size()
                    if (offset < 0 || length !in 1..65536 || offset > size || length.toLong() > size - offset) throw PdfFailure("INVALID_RANGE")
                    totalRead += length
                    if (totalRead > budget) throw PdfFailure("READ_BUDGET")
                    val bytes = ByteArray(length)
                    source.position(offset)
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) if (source.read(buffer) < 0) throw PdfFailure("SOURCE_EOF")
                    output.writeInt(length); output.write(bytes); output.flush()
                }
                READY -> {
                    if (expected != READY) throw PdfFailure("PROTOCOL")
                    return ByteBuffer.allocate(4).putInt(input.readInt()).array()
                }
                PNG -> {
                    if (expected != PNG) throw PdfFailure("PROTOCOL")
                    val length = input.readInt()
                    if (length !in 8..64 * 1024 * 1024) throw PdfFailure("IMAGE_SIZE")
                    return ByteArray(length).also(input::readFully)
                }
                FAILURE -> throw PdfFailure(input.readUTF().take(64))
                else -> throw PdfFailure("PROTOCOL_$event")
            }
        }
        throw PdfFailure("CANCELLED")
    }

    /** May be called from another thread while open/render is blocked. */
    override fun cancel() {
        if (!closed.compareAndSet(false, true)) return
        process.destroyForcibly()
        runCatching { source.close() }
        executor.shutdownNow()
        runCatching { input.close() }
        runCatching { output.close() }
        if (!process.waitFor(5, TimeUnit.SECONDS)) throw PdfFailure("HELPER_EXIT_TIMEOUT")
    }

    override fun close() = cancel()

    companion object {
        const val MAGIC = 0x50465031
        const val RANGE = 10
        const val READY = 11
        const val PNG = 12
        const val FAILURE = 13
        const val RENDER = 20
    }
}
