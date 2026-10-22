package com.phoneform.desktop

import com.phoneform.ai.core.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

class DisplayImageLoaderTest {
    private val key=DisplayImageKey("project","page","fingerprint","render","thumb",DisplayImageSource.BASE)
    private class TrackedImage:BufferedImage(10,20,BufferedImage.TYPE_INT_RGB) {
        var flushed=false
        override fun flush() { flushed=true; super.flush() }
    }

    @Test fun decoderBudgetBoundsConcurrentFullPagesAndThumbnailsTogether()=runBlocking {
        val loader=DisplayImageLoader()
        val entered=AtomicInteger();val active=AtomicInteger();val peak=AtomicInteger()
        val firstTwo=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val jobs=(0 until 8).map { index->async {
            loader.load(key.copy(pageId=index.toString(),source=if(index%2==0)DisplayImageSource.BASE else DisplayImageSource.THUMBNAIL),{
                val running=active.incrementAndGet();peak.updateAndGet {maxOf(it,running)}
                if(entered.incrementAndGet()==2)firstTwo.complete(Unit)
                try {release.await();TrackedImage()}finally{active.decrementAndGet()}
            }) {it.width}
        } }
        withTimeout(5000){firstTwo.await()}
        assertEquals(2,entered.get());assertEquals(2,active.get())
        release.complete(Unit)
        assertEquals(List(8){10},jobs.awaitAll());assertEquals(2,peak.get())
    }

    @Test fun conversionSuccessAndFailureAlwaysFlushTheBufferedImage()=runBlocking {
        val loader=DisplayImageLoader();val success=TrackedImage();val failure=TrackedImage()
        assertEquals(20,loader.load(key,{success}) {it.height});assertTrue(success.flushed)
        try {loader.load(key,{failure}) {error("CONVERSION_FAILURE")};fail("Expected conversion error")}
        catch(expected:IllegalStateException){assertEquals("CONVERSION_FAILURE",expected.message)}
        assertTrue(failure.flushed)
    }

    @Test fun cancellationDuringConversionFlushesAndDoesNotReturnTheOldImage()=runBlocking {
        val loader=DisplayImageLoader();val image=TrackedImage();var published=false
        val done=launch {
            val ownJob=currentCoroutineContext()[Job]!!
            loader.load(key,{image}) {ownJob.cancel();it.width}
            published=true
        }
        done.join();assertTrue(done.isCancelled);assertFalse(published);assertTrue(image.flushed)
        assertEquals(20,loader.load(key,{TrackedImage()}) {it.height})
    }

    @Test fun aCanceledQueuedReaderNeverStartsDecoding()=runBlocking {
        val loader=DisplayImageLoader();val release=CompletableDeferred<Unit>();val both=CompletableDeferred<Unit>()
        val entered=AtomicInteger()
        val holding=List(2){async {loader.load(key,{if(entered.incrementAndGet()==2)both.complete(Unit);release.await();TrackedImage()}){it.width}}}
        withTimeout(5000){both.await()}
        var decoded=false
        val canceled=launch {loader.load(key,{decoded=true;TrackedImage()}){it.width}}
        yield();canceled.cancelAndJoin();release.complete(Unit);holding.awaitAll()
        assertFalse(decoded)
    }

    @Test fun deletionAndClosePreventLatePublicationWithoutKeepingACache()=runBlocking {
        var deleted=false
        val loader=DisplayImageLoader {if(deleted)throw ProjectDeletedException()}
        val late=TrackedImage()
        try {loader.load(key,{late}){deleted=true;it.width};fail("Deleted image was returned")}
        catch(_:ProjectDeletedException){}
        assertTrue(late.flushed)
        deleted=false;loader.close();var decoded=false
        try {loader.load(key,{decoded=true;TrackedImage()}){it.width};fail("Closed loader decoded an image")}
        catch(expected:IllegalStateException){assertEquals("IMAGE_LOADER_CLOSED",expected.message)}
        assertFalse(decoded)
    }

    @Test fun closeAndAwaitWaitsForActiveIoAndRejectsQueuedAndNewDecoders()=runBlocking {
        val loader=DisplayImageLoader()
        val entered=AtomicInteger();val both=CompletableDeferred<Unit>()
        val release=CountDownLatch(1);val images=List(2){TrackedImage()}
        val reads=List(2){index->async {
            runCatching {loader.load(key,{
                if(entered.incrementAndGet()==2)both.complete(Unit)
                check(release.await(5,TimeUnit.SECONDS));images[index]
            }) {it.width}}
        }}
        withTimeout(5000){both.await()}
        var queuedDecoded=false
        val queued=async {runCatching {loader.load(key,{queuedDecoded=true;TrackedImage()}){it.width}}}
        yield()
        val closingStarted=CompletableDeferred<Unit>()
        val close=async {loader.close();closingStarted.complete(Unit);loader.closeAndAwait()}
        try {
            closingStarted.await();assertFalse(close.isCompleted)
            var newDecoded=false
            try {loader.load(key,{newDecoded=true;TrackedImage()}){it.width};fail("Closed loader admitted a new reader")}
            catch(expected:IllegalStateException){assertEquals("IMAGE_LOADER_CLOSED",expected.message)}
            assertFalse(newDecoded)
            release.countDown();withTimeout(5000){close.await()}
            assertTrue(images.all {it.flushed});assertFalse(queuedDecoded)
            assertTrue(reads.awaitAll().all {it.isFailure});assertTrue(queued.await().isFailure)
        }finally{release.countDown()}
    }

    @Test fun closeWaitsOnlyForIoEvenWhenTheCallerDispatcherCannotResume()=runBlocking {
        val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val callers=CoroutineScope(SupervisorJob()+dispatcher)
        val decodeEntered=CompletableDeferred<Unit>();val dispatcherBlocked=CompletableDeferred<Unit>()
        val releaseIo=CountDownLatch(1);val releaseCaller=CountDownLatch(1)
        val image=TrackedImage();val loader=DisplayImageLoader()
        try {
            val read=callers.async {runCatching {loader.load(key,{
                decodeEntered.complete(Unit);check(releaseIo.await(5,TimeUnit.SECONDS));image
            }){it.width}}}
            withTimeout(5000){decodeEntered.await()}
            callers.launch {dispatcherBlocked.complete(Unit);check(releaseCaller.await(5,TimeUnit.SECONDS))}
            withTimeout(5000){dispatcherBlocked.await()}
            loader.close();releaseIo.countDown()
            withTimeout(5000){loader.closeAndAwait()}
            assertTrue(image.flushed)
            assertFalse("Caller is deliberately blocked; shutdown must not join its Job",read.isCompleted)
            releaseCaller.countDown();assertTrue(withTimeout(5000){read.await()}.isFailure)
        }finally{releaseIo.countDown();releaseCaller.countDown();callers.cancel();dispatcher.close()}
    }

    @Test fun requestKeysSeparateProjectsGeometryEnhancementAndSources() {
        val page=SamplePage("page",SampleAsset("asset",SampleKind.IMAGE,"source","abc",1),0,0,"render","thumb",300,500)
        val original=DisplayImageKey.of("project",page,DisplayImageSource.BASE)
        assertNotEquals(original,DisplayImageKey.of("another",page,DisplayImageSource.BASE))
        assertNotEquals(original,DisplayImageKey.of("project",page.copy(revision=2),DisplayImageSource.BASE))
        assertNotEquals(original,DisplayImageKey.of("project",page.copy(processing=PageProcessingSpec(PageProcessingMode.AUTO_LIGHT)),DisplayImageSource.BASE))
        assertNotEquals(original,DisplayImageKey.of("project",page,DisplayImageSource.ORIGINAL))
        val slot=DisplayImageRequestSlot();val first=slot.begin(original)
        assertTrue(slot.accepts(first,original))
        val next=original.copy(source=DisplayImageSource.ENHANCED);val second=slot.begin(next)
        assertFalse(slot.accepts(first,original));assertTrue(slot.accepts(second,next))
        slot.invalidate(first);assertTrue(slot.accepts(second,next))
        slot.invalidate(second);assertFalse(slot.accepts(second,next))
    }

    @Test fun thumbnailBoundsHandleTallNarrowAndWideImagesWithoutUpscaling() {
        listOf(1 to 100_000,100_000 to 1,2560 to 1700,160 to 224,1 to 1).forEach { (width,height)->
            val (w,h)=ThumbnailBounds.size(width,height)
            assertTrue(w in 1..160 && h in 1..224);assertTrue(w.toLong()*h<=ThumbnailBounds.PIXELS)
            assertTrue(w<=width && h<=height)
        }
        assertEquals(1 to 224,ThumbnailBounds.size(1,100_000))
        assertEquals(160 to 1,ThumbnailBounds.size(100_000,1))
    }

    @Test fun encodedThumbnailLimitStopsBeforeAllocatingAnArbitrarySource() {
        var count=0
        val input=object:InputStream(){override fun read():Int {count++;return 0}}
        try {readThumbnailBytes(input);fail("Expected thumbnail bound")}
        catch(expected:IllegalArgumentException){assertEquals("THUMBNAIL_REBUILD_REQUIRED",expected.message)}
        assertEquals(ThumbnailBounds.MAX_ENCODED_BYTES+1,count)
        assertArrayEquals(byteArrayOf(1,2,3),readThumbnailBytes(ByteArrayInputStream(byteArrayOf(1,2,3))))
    }

    @Test fun originalIntegrityUsesBoundedReadsAndChecksBothLengthAndSha() {
        val size=5*1024*1024+17
        val expected=MessageDigest.getInstance("SHA-256")
        val chunk=ByteArray(1024){it.toByte()}
        var remaining=size
        while(remaining>0){val count=minOf(remaining,chunk.size);expected.update(chunk,0,count);remaining-=count}
        val sha=expected.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
        var largestRead=0;var position=0
        val input=object:InputStream(){
            override fun read():Int=if(position>=size)-1 else (position++%1024).toByte().toInt() and 255
            override fun read(bytes:ByteArray,offset:Int,length:Int):Int {
                largestRead=maxOf(largestRead,length)
                if(position>=size)return -1
                val count=minOf(length,size-position)
                repeat(count){bytes[offset+it]=(position++%1024).toByte()};return count
            }
        }
        val asset=SampleAsset("source",SampleKind.PDF,"source.pdf",sha,size.toLong())
        verifyOriginalStream(input,asset);assertEquals(size,position);assertTrue(largestRead<=65536)
        listOf(asset.copy(byteCount=2),asset.copy(sha256="0".repeat(64))).forEach { invalid->
            try {verifyOriginalStream(ByteArrayInputStream(byteArrayOf(1,2,3)),invalid);fail("Invalid source passed")}
            catch(expected:IllegalArgumentException){assertEquals("ASSET_INTEGRITY",expected.message)}
        }
    }
}
