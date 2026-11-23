package com.rackjabbit.adskip.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotAdTest {
    private val youtube = "com.google.android.youtube"
    private val config = shippedConfig()
    private val engine = AdSkipEngine { config }
    private val settings = EngineSettings(
        masterEnabled = true,
        packageEnabled = true,
        muteDuringAds = true,
        overlayStyle = OverlayStyle.BANNER,
        opacity = 0.8f,
    )

    @Test
    fun button_hidesOverlay_restoresVolume_andStaysSuppressed() {
        val started = engine.onTree(video("Shelf build", ad = true), youtube, settings, nowMs = 0, currentVolume = 8)
        assertTrue(started.showOverlay)
        assertTrue(started.showNotAdButton)
        assertEquals(NotAdCopy.BUTTON, NotAdCopy.BUTTON)
        assertTrue(started.volume is VolumeCommand.Mute)

        val dismissed = engine.markNotAnAd(settings, currentVolume = 8, streamMuted = true)
        assertFalse(dismissed.showOverlay)
        assertFalse(dismissed.showNotAdButton)
        assertTrue(dismissed.dismissedNotAd)
        assertNull(dismissed.click)
        assertEquals(
            VolumeCommand.Restore(level = 8, keepCurrentIndex = true, leaveMuted = false),
            dismissed.volume,
        )
        assertFalse(dismissed.muteHeld)

        val still = engine.onTree(video("Shelf build", ad = true), youtube, settings, nowMs = 1_000, currentVolume = 8)
        assertFalse(still.showOverlay)
        assertFalse(still.showNotAdButton)
        assertNull(still.click)
        assertTrue(still.volume is VolumeCommand.None)

        val flicker = engine.onTree(video("Shelf build", ad = false), youtube, settings, nowMs = 1_200, currentVolume = 8)
        assertFalse(flicker.showOverlay)
        val returned = engine.onTree(video("Shelf build", ad = true), youtube, settings, nowMs = 1_400, currentVolume = 8)
        assertFalse(returned.showOverlay)
        assertNull(returned.click)
    }

    @Test
    fun newVideo_rearmsDetection() {
        engine.onTree(video("Shelf build", ad = true), youtube, settings, nowMs = 0, currentVolume = 4)
        engine.markNotAnAd(settings, currentVolume = 4, streamMuted = true)
        engine.onTargetHidden(settings, currentVolume = 4, streamMuted = false)
        val same = engine.onTree(video("Shelf build", ad = true), youtube, settings, nowMs = 2_000, currentVolume = 4)
        assertFalse(same.showOverlay)

        val next = engine.onTree(video("Rain on a tin roof", ad = true), youtube, settings, nowMs = 3_000, currentVolume = 4)
        assertTrue(next.showOverlay)
        assertTrue(next.showNotAdButton)
        assertTrue(next.adStarted)
        assertTrue(next.volume is VolumeCommand.Mute)
    }

    @Test
    fun signalGap_thenTheSameMatch_rearms() {
        engine.onTree(video("Shelf build", ad = true), youtube, settings, nowMs = 0, currentVolume = 6)
        engine.markNotAnAd(settings, currentVolume = 6, streamMuted = true)
        repeat(3) { index ->
            val quiet = engine.onTree(
                video("Shelf build", ad = false),
                youtube,
                settings,
                nowMs = 1_000L + index * 100,
                currentVolume = 6,
            )
            assertFalse(quiet.showOverlay)
        }
        val again = engine.onTree(video("Shelf build", ad = true), youtube, settings, nowMs = 2_000, currentVolume = 6)
        assertTrue(again.showOverlay)
        assertTrue(again.showNotAdButton)
    }

    @Test
    fun dismiss_stopsSkipPresses() {
        val playing = engine.onTree(fixture("skip_clickable"), youtube, settings, nowMs = 0, currentVolume = 5)
        assertEquals(listOf(1), playing.click?.path)
        val dismissed = engine.markNotAnAd(settings, currentVolume = 5, streamMuted = true)
        assertNull(dismissed.click)
        val again = engine.onTree(fixture("skip_clickable"), youtube, settings, nowMs = 5_000, currentVolume = 5)
        assertFalse(again.showOverlay)
        assertNull(again.click)
    }

    @Test
    fun overlayButton_isOnATouchableLayer_inBothModes() {
        OverlayStyle.entries.forEach { style ->
            val button = OverlayWindowPolicy.layers(style).single { it.hostsNotAdButton }
            assertTrue(button.touchable)
            assertEquals("card", button.name)
        }
        val scrim = OverlayWindowPolicy.layers(OverlayStyle.COVER).single { !it.hostsNotAdButton }
        assertFalse(scrim.touchable)
        assertEquals(NotAdCopy.BUTTON, "This isn't an ad")
        val cover = settings.copy(overlayStyle = OverlayStyle.COVER)
        val output = AdSkipEngine { config }.onTree(video("Shelf build", ad = true), youtube, cover, 0, 3)
        assertEquals(OverlayStyle.COVER, output.overlayStyle)
        assertTrue(output.showNotAdButton)
    }

    @Test
    fun falsePositiveReport_isWrittenEvenWhenLoggingIsOff() {
        assertTrue(ScreenLogPolicy.shouldWrite(debugEnabled = false, falsePositiveReport = true))
        assertFalse(ScreenLogPolicy.shouldWrite(debugEnabled = false, falsePositiveReport = false))
        assertTrue(ScreenLogPolicy.shouldWrite(debugEnabled = true, falsePositiveReport = false))
        val detection = AdDetector.detect(video("Shelf build", ad = true), config.apps.first())
        val text = NodeTreeFormatter.format(
            root = video("Shelf build", ad = true),
            packageName = youtube,
            capturedAt = "2026-11-23T00:00:00Z",
            detection = detection,
            notes = listOf(NotAdCopy.REPORT_NOTE),
        )
        assertTrue(text.contains(NotAdCopy.REPORT_NOTE))
        assertTrue(text.contains("matches:"))
        assertTrue(text.contains("ad_progress_text"))
        assertTrue(text.contains("visible=true"))
    }

    private fun video(title: String, ad: Boolean): UiNode {
        val children = mutableListOf(
            UiNode(
                viewId = "com.google.android.youtube:id/title",
                text = title,
                visible = true,
                bounds = Bounds(40, 1500, 900, 1580),
            ),
        )
        if (ad) {
            children += UiNode(
                viewId = "com.google.android.youtube:id/ad_progress_text",
                text = "Ad · 0:05",
                visible = true,
                bounds = Bounds(48, 96, 420, 160),
            )
        }
        return UiNode(children = children)
    }
}
