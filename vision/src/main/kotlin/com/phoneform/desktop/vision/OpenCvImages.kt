package com.phoneform.desktop.vision

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat

/** Java and native bindings must come from the same pinned OpenCV release. */
object OpenCvRuntime {
    const val EXPECTED_VERSION = "4.12.0"
    @Volatile private var attempted = false
    @Volatile private var available = false

    @Synchronized fun load(): Boolean {
        if (attempted) return available
        attempted = true
        available = runCatching {
            val configured = System.getProperty("phoneform.opencv.dll")?.let(Path::of)
            val resources = System.getProperty("compose.application.resources.dir")?.let(Path::of)
            val candidates = listOfNotNull(configured,
                resources?.resolve("opencv/opencv_java4120.dll"),
                Path.of("runtime/opencv/opencv_java4120.dll"),
                Path.of("../runtime/opencv/opencv_java4120.dll"))
            val native = candidates.firstOrNull { Files.isRegularFile(it) }
                ?: return@runCatching false
            System.load(native.toAbsolutePath().normalize().toString())
            Core.getVersionString() == EXPECTED_VERSION
        }.getOrDefault(false)
        return available
    }
}

/** Always transfers RGBA, preserving the Android algorithm's channel contract. */
internal object OpenCvImages {
    fun toMat(image: BufferedImage, destination: Mat) {
        val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        val bytes = ByteArray(pixels.size * 4)
        pixels.forEachIndexed { index, pixel ->
            bytes[index * 4] = (pixel ushr 16).toByte()
            bytes[index * 4 + 1] = (pixel ushr 8).toByte()
            bytes[index * 4 + 2] = pixel.toByte()
            bytes[index * 4 + 3] = (pixel ushr 24).toByte()
        }
        destination.create(image.height, image.width, CvType.CV_8UC4)
        destination.put(0, 0, bytes)
    }

    fun toImage(source: Mat, destination: BufferedImage) {
        require(source.cols() == destination.width && source.rows() == destination.height)
        require(source.channels() == 4)
        val bytes = ByteArray(destination.width * destination.height * 4)
        source.get(0, 0, bytes)
        val pixels = IntArray(destination.width * destination.height) { index ->
            ((bytes[index * 4 + 3].toInt() and 255) shl 24) or
                ((bytes[index * 4].toInt() and 255) shl 16) or
                ((bytes[index * 4 + 1].toInt() and 255) shl 8) or
                (bytes[index * 4 + 2].toInt() and 255)
        }
        destination.setRGB(0, 0, destination.width, destination.height, pixels, 0, destination.width)
    }
}
