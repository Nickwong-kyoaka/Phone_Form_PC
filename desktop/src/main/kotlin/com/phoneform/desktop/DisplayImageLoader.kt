package com.phoneform.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import com.phoneform.ai.core.model.PageProcessingFingerprint
import com.phoneform.ai.core.model.SamplePage
import com.phoneform.ai.core.model.SampleAsset
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.awt.image.BufferedImage
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

enum class DisplayImageSource { BASE, ORIGINAL, ENHANCED, THUMBNAIL }

internal data class DisplayImageKey(val projectId: String, val pageId: String,
    val fingerprint: String, val renderPath: String, val thumbnailPath: String,
    val source: DisplayImageSource) {
    companion object {
        fun of(projectId: String, page: SamplePage, source: DisplayImageSource) = DisplayImageKey(
            projectId, page.id, PageProcessingFingerprint.of(page), page.renderPath, page.thumbnailPath, source)
    }
}

/** Owns no decoded bitmap cache: mounted Compose views own their display copies. */
internal class DisplayImageLoader(private val validate: (DisplayImageKey) -> Unit = {}) : AutoCloseable {
    private val permits = Semaphore(2)
    private val closed = AtomicBoolean(false)
    private val lifecycle=Any()
    private var activeIo=0
    private var idle=CompletableDeferred<Unit>().apply {complete(Unit)}
    private fun enterIo() = synchronized(lifecycle) {
        check(!closed.get()) { "IMAGE_LOADER_CLOSED" }
        if(activeIo++==0)idle=CompletableDeferred()
    }
    private fun leaveIo() = synchronized(lifecycle) {
        check(activeIo>0)
        if(--activeIo==0)idle.complete(Unit)
    }

    suspend fun <T> load(key: DisplayImageKey, decode: suspend () -> BufferedImage,
        convert: (BufferedImage) -> T): T = withContext(Dispatchers.IO) {
        enterIo()
        try {permits.withPermit {
            fun checkValid() { check(!closed.get()) { "IMAGE_LOADER_CLOSED" }; validate(key) }
            ensureActive(); checkValid()
            val image = decode()
            try {
                ensureActive(); checkValid()
                val result = convert(image)
                ensureActive(); checkValid()
                result
            } finally { image.flush() }
        }}finally{leaveIo()}
    }

    override fun close() { synchronized(lifecycle) {closed.set(true)} }
    /** Wait only for IO/permit finally blocks, never for a Compose caller to resume on Main. */
    suspend fun closeAndAwait() {
        val waiting=synchronized(lifecycle) {closed.set(true);idle}
        waiting.await()
    }
}

/** A canceled/non-cooperative reader cannot publish into a newer view's request slot. */
internal class DisplayImageRequestSlot {
    private var generation = 0L
    private var key: DisplayImageKey? = null
    @Synchronized fun begin(next: DisplayImageKey?): Long { key = next; return ++generation }
    @Synchronized fun accepts(token: Long, request: DisplayImageKey?): Boolean = token == generation && request == key
    @Synchronized fun invalidate(token: Long) { if (token == generation) { generation++; key = null } }
}

internal class DisplayImageState(val bitmap: ImageBitmap? = null, val loading: Boolean = false,
    val error: String? = null, val retry: (Boolean) -> Unit = {})

@Composable internal fun rememberDisplayImage(controller: Controller, projectId: String?, page: SamplePage?,
    source: DisplayImageSource = DisplayImageSource.BASE): DisplayImageState {
    val key = remember(projectId, page, source) { if (projectId != null && page != null) DisplayImageKey.of(projectId,page,source) else null }
    val slot = remember(controller) { DisplayImageRequestSlot() }
    var retry by remember(controller,key) { mutableIntStateOf(0) }
    var rebuild by remember(controller,key) { mutableStateOf(false) }
    var result by remember(controller,key) { mutableStateOf(DisplayImageState(loading=key != null)) }
    val token = remember(controller,key, retry) { slot.begin(key) }
    DisposableEffect(slot, token) { onDispose { slot.invalidate(token) } }
    LaunchedEffect(controller, key, token) {
        if (key == null || page == null) { result=DisplayImageState(); return@LaunchedEffect }
        result=DisplayImageState(loading=true)
        try {
            val bitmap=controller.loadDisplayImage(key.projectId,page,source,rebuild)
            ensureActive()
            if (slot.accepts(token,key)) {
                controller.validateDisplayImageRequest(key)
                result=DisplayImageState(bitmap=bitmap)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (slot.accepts(token,key)) result=DisplayImageState(error=Controller.errorCode(failure))
        }
    }
    return DisplayImageState(result.bitmap,result.loading,result.error) { repair -> rebuild=repair; retry++ }
}

internal object ThumbnailBounds {
    const val WIDTH = 160
    const val HEIGHT = 224
    const val PIXELS = WIDTH * HEIGHT
    // Previous versions generated a fixed-width 160px thumbnail (~226px high for A4).
    // Accept modest legacy sizes, then fit only the in-memory display copy.
    const val LEGACY_WIDTH = 320
    const val LEGACY_HEIGHT = 448
    const val MAX_ENCODED_BYTES = 2*1024*1024
    fun size(width: Int, height: Int): Pair<Int,Int> {
        require(width>0 && height>0) { "IMAGE_DECODE" }
        val scale=minOf(1.0,WIDTH.toDouble()/width,HEIGHT.toDouble()/height)
        return (width*scale).roundToInt().coerceIn(1,minOf(width,WIDTH)) to
            (height*scale).roundToInt().coerceIn(1,minOf(height,HEIGHT))
    }
}

internal fun readThumbnailBytes(input:InputStream):ByteArray {
    val output=ByteArrayOutputStream()
    val buffer=ByteArray(8192)
    while(true) {
        val read=input.read(buffer,0,minOf(buffer.size,ThumbnailBounds.MAX_ENCODED_BYTES-output.size()+1))
        if(read<0)return output.toByteArray()
        if(read==0)continue
        require(output.size()<=ThumbnailBounds.MAX_ENCODED_BYTES-read) { "THUMBNAIL_REBUILD_REQUIRED" }
        output.write(buffer,0,read)
    }
}

/** Verifies a PDF source with constant-size scratch space before the isolated worker opens it. */
internal fun verifyOriginalStream(input: InputStream, asset: SampleAsset) {
    require(asset.byteCount>=0) { "ASSET_INTEGRITY" }
    val digest=MessageDigest.getInstance("SHA-256")
    val buffer=ByteArray(64*1024)
    var count=0L
    try { while(true) {
        val read=input.read(buffer)
        if(read<0)break
        if(read==0)continue
        require(count<=asset.byteCount-read) { "ASSET_INTEGRITY" }
        count+=read;digest.update(buffer,0,read)
    } } finally { buffer.fill(0) }
    val sha=digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    require(count==asset.byteCount && sha.equals(asset.sha256,true)) { "ASSET_INTEGRITY" }
}
