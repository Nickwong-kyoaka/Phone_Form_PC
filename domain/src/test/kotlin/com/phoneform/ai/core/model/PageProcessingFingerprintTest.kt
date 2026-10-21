package com.phoneform.ai.core.model

import org.junit.Assert.*
import org.junit.Test

class PageProcessingFingerprintTest {
    private fun page() = SamplePage("page", SampleAsset("asset", SampleKind.IMAGE, "original.png", "a".repeat(64), 120),
        0, 0, "base.png", "thumb.png", 500, 700)

    @Test fun processingChangesFingerprintWithoutChangingGeometryIdentity() {
        val old = page(); val enhanced = old.copy(processing = PageProcessingSpec(PageProcessingMode.AUTO_LIGHT))
        assertEquals(PageProcessingMode.OFF, old.processing.mode)
        assertEquals(old.revision, enhanced.revision)
        assertEquals(PageProcessingFingerprint.base(old), PageProcessingFingerprint.base(enhanced))
        assertNotEquals(PageProcessingFingerprint.of(old), PageProcessingFingerprint.of(enhanced))
        assertNotEquals(PageProcessingFingerprint.of(enhanced), PageProcessingFingerprint.of(enhanced.copy(processing = enhanced.processing.copy(parameterVersion = 2))))
    }

    @Test fun pageAndGeometryRevisionAndSourcePdfPageCannotReuseResults() {
        val old = page()
        listOf(old.copy(id = "other"), old.copy(revision = old.revision + 1), old.copy(sourcePageIndex = 1),
            old.copy(width = old.width + 1), old.copy(transform = old.transform.copy(rotationDegrees = 90))).forEach {
            assertNotEquals(PageProcessingFingerprint.base(old), PageProcessingFingerprint.base(it))
        }
    }

    @Test fun displayOrderAndRepairedPathDoNotPretendToChangeGeometry() {
        val old = page()
        assertEquals(PageProcessingFingerprint.of(old), PageProcessingFingerprint.of(old.copy(position = 4,
            renderPath = "repaired.png", thumbnailPath = "repaired-thumb.png", noExtraction = true)))
        // Actual corrected-input bytes are independently checked by the asset store and repository.
        assertEquals(PageProcessingFingerprint.of(old), PageProcessingFingerprint.of(old.copy(asset = old.asset.copy(relativePath = "immutable-copy.png"))))
    }
}
