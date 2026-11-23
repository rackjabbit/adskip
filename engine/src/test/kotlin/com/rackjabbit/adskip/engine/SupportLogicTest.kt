package com.rackjabbit.adskip.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportLogicTest {
    @Test
    fun clockFormatting() {
        assertEquals("0:05", OverlayCopy.formatClock(5))
        assertEquals("1:05", OverlayCopy.formatClock(65))
        assertEquals("0:00", OverlayCopy.formatClock(0))
        val withClock = OverlayCopy.build(5, "Ad 1 of 2")
        assertEquals("Ad playing", withClock.title)
        assertEquals("Ad 1 of 2 · 0:05", withClock.detail)
        assertEquals("Ad · 0:05", OverlayCopy.build(5, "Ad · 0:05").detail)
    }

    @Test
    fun debouncerBlocksEverySignatureInsideTheWindow() {
        val debouncer = ClickDebouncer()
        assertTrue(debouncer.allow(1_000, "skip", 1200))
        assertFalse(debouncer.allow(1_100, "skip", 1200))
        assertFalse(debouncer.allow(2_000, "other", 1200))
        assertTrue(debouncer.allow(2_200, "other", 1200))
        assertEquals("other", debouncer.lastSignature)
    }

    @Test
    fun dumpThrottleHonorsForce() {
        val throttle = DumpThrottle(minIntervalMs = 2500)
        assertTrue(throttle.shouldDump(0, force = false))
        assertFalse(throttle.shouldDump(1000, force = false))
        assertTrue(throttle.shouldDump(1100, force = true))
        assertFalse(throttle.shouldDump(2000, force = false))
        assertTrue(throttle.shouldDump(1100 + 2500, force = false))
    }

    @Test
    fun formatterIncludesIdsAndTruncates() {
        val root = UiNode(
            viewId = "com.google.android.youtube:id/watch_player",
            className = "android.widget.FrameLayout",
            children = listOf(
                UiNode(
                    viewId = "com.google.android.youtube:id/ad_progress_text",
                    className = "android.widget.TextView",
                    text = "Ad · 0:05\nnext",
                    visible = true,
                    bounds = Bounds(40, 80, 360, 140),
                ),
                UiNode(
                    className = "android.widget.Button",
                    text = "Skip ad",
                    clickable = true,
                    visible = true,
                    bounds = Bounds(800, 1500, 1040, 1600),
                ),
            ),
        )
        val detection = AdDetector.detect(root, shippedConfig().apps.first())
        val text = NodeTreeFormatter.format(
            root = root,
            packageName = "com.google.android.youtube",
            capturedAt = "2026-11-21T00:00:00Z",
            detection = detection,
            maxNodes = 2,
        )
        assertTrue(text.contains("id=com.google.android.youtube:id/ad_progress_text"))
        assertTrue(text.contains("text=Ad · 0:05\\nnext"))
        assertTrue(text.contains("truncated: true"))
        assertTrue(text.contains("package: com.google.android.youtube"))
        assertTrue(text.contains("adPlaying: true"))
        assertTrue(text.contains("matches:"))
        assertTrue(text.contains("source=viewId"))
        assertTrue(text.contains("id=com.google.android.youtube:id/ad_progress_text"))
        assertTrue(text.contains("value=ad_progress_text"))
        val withNotes = NodeTreeFormatter.format(
            root = root,
            packageName = "com.google.android.youtube",
            capturedAt = "2026-11-21T00:00:00Z",
            detection = detection,
            notes = listOf("mute applied stream=MUSIC restore=8 method=ADJUST_MUTE"),
        )
        assertTrue(withNotes.contains("mute applied stream=MUSIC restore=8"))
    }

    @Test
    fun boundsOutsideTheScreenAreNotTappable() {
        assertFalse(Bounds(2000, 10, 2100, 40).isTappable(1080, 2400))
        assertTrue(Bounds(800, 1500, 1040, 1600).isTappable(1080, 2400))
        assertFalse(Bounds.EMPTY.isTappable(1080, 2400))
    }
}
