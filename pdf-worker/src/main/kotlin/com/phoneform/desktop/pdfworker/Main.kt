package com.phoneform.desktop.pdfworker

import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.RandomAccessRead
import org.apache.pdfbox.io.RandomAccessReadView
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import javax.imageio.ImageIO

private const val MAGIC = 0x50465031
private const val RANGE = 10
private const val READY = 11
private const val PNG = 12
private const val FAILURE = 13
private const val RENDER = 20

/** Binary stdout is reserved for protocol frames. No user input or document bytes are logged. */
fun main() {
    val input = DataInputStream(System.`in`.buffered())
    val output = DataOutputStream(System.out.buffered())
    // ImageIO's default disk cache must never receive questionnaire pixels.
    ImageIO.setUseCache(false)
    try {
        if (input.readInt() != MAGIC) throw IOException("PROTOCOL")
        val length = input.readLong()
        if (length !in 1L..1024L * 1024 * 1024) throw IOException("DOCUMENT_SIZE")
        RangeReader(input, output, length).use { source ->
            Loader.loadPDF(source).use { document ->
                if (document.isEncrypted) throw IOException("PASSWORD_PROTECTED")
                if (document.numberOfPages !in 1..10000) throw IOException("PAGE_COUNT")
                output.writeInt(READY); output.writeInt(document.numberOfPages); output.flush()
                val renderer = PDFRenderer(document).apply { isSubsamplingAllowed = true }
                while (true) {
                    if (input.readInt() != RENDER) throw IOException("PROTOCOL")
                    val index = input.readInt()
                    val dpi = input.readInt()
                    if (index !in 0 until document.numberOfPages || dpi !in 36..300) throw IOException("PAGE_RANGE")
                    val bounds = document.getPage(index).cropBox
                    val scale = dpi / 72.0
                    val width = bounds.width * scale
                    val height = bounds.height * scale
                    if (!width.isFinite() || !height.isFinite() || width <= 0 || height <= 0 || width * height > 32_000_000) throw IOException("PAGE_SIZE")
                    val image = renderer.renderImageWithDPI(index, dpi.toFloat(), ImageType.RGB)
                    try {
                        val bytes = ByteArrayOutputStream()
                        if (!ImageIO.write(image, "png", bytes) || bytes.size() > 64 * 1024 * 1024) throw IOException("IMAGE_SIZE")
                        output.writeInt(PNG); output.writeInt(bytes.size()); bytes.writeTo(output); output.flush()
                    } finally { image.flush() }
                }
            }
        }
    } catch (_: EOFException) {
        // Closing the private pipe ends the document's lifetime.
    } catch (failure: Throwable) {
        val reason = when (failure) {
            is InvalidPasswordException -> "PASSWORD_PROTECTED"
            is OutOfMemoryError -> "RESOURCE_LIMIT"
            else -> if (failure.message in setOf("PASSWORD_PROTECTED", "PROTOCOL", "DOCUMENT_SIZE", "PAGE_COUNT", "PAGE_RANGE", "PAGE_SIZE", "IMAGE_SIZE")) failure.message!! else "INVALID_DOCUMENT"
        }
        runCatching { output.writeInt(FAILURE); output.writeUTF(reason); output.flush() }
    }
}

/** Only a 64 KiB range cache is retained. Decryption and ciphertext authentication stay in the parent. */
private class RangeReader(
    private val input: DataInputStream,
    private val output: DataOutputStream,
    private val size: Long,
) : RandomAccessRead {
    private var offset = 0L
    private var closed = false
    private var cacheStart = -1L
    private var cache = ByteArray(0)

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 255
    }

    override fun read(bytes: ByteArray, start: Int, length: Int): Int {
        ensureOpen()
        if (start < 0 || length < 0 || start > bytes.size - length) throw IndexOutOfBoundsException()
        if (length == 0) return 0
        if (offset >= size) return -1
        if (offset < cacheStart || offset >= cacheStart + cache.size) fillCache()
        val from = (offset - cacheStart).toInt()
        val count = minOf(length, cache.size - from)
        cache.copyInto(bytes, start, from, from + count)
        offset += count
        return count
    }

    private fun fillCache() {
        val count = minOf(65536L, size - offset).toInt()
        output.writeInt(RANGE); output.writeLong(offset); output.writeInt(count); output.flush()
        if (input.readInt() != count) throw IOException("Invalid range response")
        cache = ByteArray(count).also(input::readFully)
        cacheStart = offset
    }

    override fun getPosition(): Long { ensureOpen(); return offset }
    override fun seek(position: Long) { ensureOpen(); if (position < 0) throw IOException("Invalid seek"); offset = position }
    override fun length(): Long { ensureOpen(); return size }
    override fun isClosed(): Boolean = closed
    override fun isEOF(): Boolean { ensureOpen(); return offset >= size }
    override fun close() { closed = true; cache.fill(0); cache = ByteArray(0) }
    override fun createView(startPosition: Long, streamLength: Long): RandomAccessReadView {
        ensureOpen()
        if (startPosition < 0 || streamLength < 0 || startPosition > size || streamLength > size - startPosition) throw IOException("Invalid view")
        return RandomAccessReadView(this, startPosition, streamLength)
    }
    private fun ensureOpen() { if (closed) throw IOException("Reader is closed") }
}
