package com.rackjabbit.adskip.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdSkipEngineTest {
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
    fun waitsForClickableSkip_thenDebouncesRetries() {
        val idle = engine.onTree(fixture("no_ad"), youtube, settings, nowMs = 0, currentVolume = 8)
        assertFalse(idle.showOverlay)
        assertNull(idle.click)
        assertTrue(idle.volume is VolumeCommand.None)

        val waiting = engine.onTree(
            fixture("skip_visible_not_clickable"),
            youtube,
            settings,
            nowMs = 1_000,
            currentVolume = 8,
        )
        assertTrue(waiting.showOverlay)
        assertTrue(waiting.adStarted)
        assertNull(waiting.click)
        assertEquals(VolumeCommand.Mute(8), waiting.volume)
        assertEquals("Ad playing", waiting.title)

        val holding = engine.onTree(
            fixture("skip_visible_not_clickable"),
            youtube,
            settings,
            nowMs = 1_400,
            currentVolume = 0,
        )
        assertTrue(holding.volume is VolumeCommand.None)
        assertFalse(holding.adStarted)

        val firstClick = engine.onTree(fixture("skip_clickable"), youtube, settings, nowMs = 2_000, currentVolume = 0)
        assertEquals(listOf(1), firstClick.click?.path)
        val tooSoon = engine.onTree(fixture("skip_clickable"), youtube, settings, nowMs = 2_500, currentVolume = 0)
        assertNull(tooSoon.click)
        val retry = engine.onTree(fixture("skip_clickable"), youtube, settings, nowMs = 3_200, currentVolume = 0)
        assertEquals(listOf(1), retry.click?.path)
    }

    @Test
    fun threeCleanReadings_endTheAdAndRestoreVolume() {
        engine.onTree(fixture("ad_present"), youtube, settings, nowMs = 0, currentVolume = 6)
        val firstMiss = engine.onTree(fixture("no_ad"), youtube, settings, nowMs = 1_000, currentVolume = 0)
        val secondMiss = engine.onTree(fixture("no_ad"), youtube, settings, nowMs = 1_200, currentVolume = 0)
        assertTrue(firstMiss.showOverlay)
        assertTrue(secondMiss.showOverlay)
        val ended = engine.onTree(fixture("no_ad"), youtube, settings, nowMs = 1_400, currentVolume = 0)
        assertFalse(ended.showOverlay)
        assertTrue(ended.adEnded)
        assertTrue(ended.muteHeld)
        assertEquals(VolumeCommand.None, ended.volume)
        val restored = engine.onTree(fixture("no_ad"), youtube, settings, nowMs = 2_500, currentVolume = 0)
        assertEquals(
            VolumeCommand.Restore(level = 6, keepCurrentIndex = false, leaveMuted = false),
            restored.volume,
        )
        assertFalse(restored.muteHeld)
    }

    @Test
    fun nullRootWhileAnAdIsUp_holdsThenStops() {
        engine.onTree(fixture("ad_present"), youtube, settings, nowMs = 0, currentVolume = 4)
        val held = engine.onTree(null, youtube, settings, nowMs = 500, currentVolume = 0)
        assertTrue(held.showOverlay)
        assertFalse(held.adEnded)
        assertNull(held.click)
        val gone = engine.onTree(null, youtube, settings, nowMs = 900, currentVolume = 0)
        assertFalse(gone.showOverlay)
        assertTrue(gone.muteHeld)
        assertEquals(VolumeCommand.None, gone.volume)
        val left = engine.onTargetHidden(settings, currentVolume = 0)
        assertEquals(
            VolumeCommand.Restore(level = 4, keepCurrentIndex = false, leaveMuted = false),
            left.volume,
        )
    }

    @Test
    fun masterOff_stopsImmediatelyWithoutClicking() {
        engine.onTree(fixture("skip_clickable"), youtube, settings, nowMs = 0, currentVolume = 9)
        val off = engine.onTree(
            fixture("skip_clickable"),
            youtube,
            settings.copy(masterEnabled = false),
            nowMs = 2_000,
            currentVolume = 9,
            streamMuted = true,
        )
        assertFalse(off.showOverlay)
        assertNull(off.click)
        assertEquals(
            VolumeCommand.Restore(level = 9, keepCurrentIndex = true, leaveMuted = false),
            off.volume,
        )
    }

    @Test
    fun muteOff_stillShowsTheOverlay() {
        val quiet = settings.copy(muteDuringAds = false)
        val output = engine.onTree(fixture("ad_present"), youtube, quiet, nowMs = 0, currentVolume = 11)
        assertTrue(output.showOverlay)
        assertTrue(output.volume is VolumeCommand.None)
        assertEquals("Ad · 0:05", output.detail)
    }

    @Test
    fun ancestorSkip_isThePlannedClick() {
        val output = engine.onTree(fixture("skip_via_ancestor"), youtube, settings, nowMs = 0, currentVolume = 3)
        assertEquals(listOf(1), output.click?.path)
        assertEquals(Bounds(700, 1400, 1040, 1520), output.click?.bounds)
    }

    @Test
    fun leavingTheTarget_restoresVolume() {
        engine.onTree(fixture("prime_ad_countdown"), "com.amazon.avod.thirdpartyclient", settings, 0, 2)
        val hidden = engine.onTargetHidden(settings, currentVolume = 2, streamMuted = true)
        assertFalse(hidden.showOverlay)
        assertEquals(
            VolumeCommand.Restore(level = 2, keepCurrentIndex = true, leaveMuted = false),
            hidden.volume,
        )
    }

    @Test
    fun backToBackAds_doNotUnmuteBetweenThem() {
        val first = engine.onTree(fixture("ad_present"), youtube, settings, nowMs = 0, currentVolume = 5)
        assertEquals(VolumeCommand.Mute(5), first.volume)
        val gap = engine.onTree(
            fixture("no_ad"),
            youtube,
            settings,
            nowMs = 600,
            currentVolume = 5,
            streamMuted = true,
        )
        assertEquals(VolumeCommand.None, gap.volume)
        assertTrue(gap.muteHeld)
        val second = engine.onTree(
            fixture("ad_present"),
            youtube,
            settings,
            nowMs = 1_100,
            currentVolume = 5,
            streamMuted = true,
        )
        assertEquals(VolumeCommand.None, second.volume)
        assertTrue(second.showOverlay)
        val after = engine.onTree(
            fixture("no_ad"),
            youtube,
            settings,
            nowMs = 1_100 + MuteMachine.GRACE_MS,
            currentVolume = 5,
            streamMuted = true,
        )
        assertEquals(
            VolumeCommand.Restore(level = 5, keepCurrentIndex = true, leaveMuted = false),
            after.volume,
        )
    }
}
