package com.phoneform.desktop

import org.junit.Assert.*
import org.junit.Test

class CanvasPreferenceTest {
    @Test fun viewportRoundTripRetainsNormalizedPositionAndImageSource() {
        val viewport = CanvasViewState(2.5f,.125f,-.2f,true,"ENHANCED")
        val state = DesktopUiState(projectId="project",projects=mapOf("project" to
            ProjectUiState(templatePageId="page",canvasViews=mapOf("page" to viewport))))
        val restored = DesktopUiCodec.decode(DesktopUiCodec.encode(state))
        assertEquals(viewport,restored.projects.getValue("project").canvasViews.getValue("page"))
        // The saved position is a viewport fraction, independent of pixels or Windows DPI.
        assertEquals(.125f,restored.projects.getValue("project").canvasViews.getValue("page").offsetX,0f)
    }

    @Test fun olderPreferencesAndUnknownViewportValuesRemainUsable() {
        val old = DesktopUiCodec.decode("""{"projects":{"project":{"templatePageId":"page"}}}""")
        assertTrue(old.projects.getValue("project").canvasViews.isEmpty())
        val future = DesktopUiCodec.decode("""{"projects":{"project":{"canvasViews":{"page":{"zoom":99,"offsetX":100,"offsetY":-100,"source":"FUTURE_SOURCE"}}}}}""")
        val viewport = future.projects.getValue("project").canvasViews.getValue("page")
        assertEquals(5f,viewport.zoom,0f)
        assertEquals(5f,viewport.offsetX,0f)
        assertEquals(-5f,viewport.offsetY,0f)
        assertEquals("BASE",viewport.source)
        assertFalse(viewport.pan)
    }
}
