package com.rackjabbit.adskip.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdDetectorTest {
    private val config = shippedConfig()
    private val youtube = config.apps.first { it.packageName == "com.google.android.youtube" }
    private val prime = config.apps.first { it.packageName == "com.amazon.avod.thirdpartyclient" }
    private val livingRoom = config.apps.first { it.packageName == "com.amazon.amazonvideo.livingroom" }

    @Test
    fun adPresent_withoutSkipButton() {
        val detection = AdDetector.detect(fixture("ad_present"), youtube)
        assertTrue(detection.adPlaying)
        assertEquals(5, detection.countdownSeconds)
        assertNull(detection.skip)
        assertTrue(detection.label.orEmpty().contains("0:05"))
    }

    @Test
    fun skipVisibleButNotClickable_isNotPressed() {
        val detection = AdDetector.detect(fixture("skip_visible_not_clickable"), youtube)
        assertTrue(detection.adPlaying)
        assertEquals(12, detection.countdownSeconds)
        val skip = requireNotNull(detection.skip)
        assertEquals("Skip", skip.label)
        assertFalse(skip.ready)
        assertNull(skip.clickablePath)
        assertEquals(listOf(1), skip.nodePath)
    }

    @Test
    fun skipClickable_targetsTheButton() {
        val detection = AdDetector.detect(fixture("skip_clickable"), youtube)
        assertTrue(detection.adPlaying)
        assertNull(detection.countdownSeconds)
        val skip = requireNotNull(detection.skip)
        assertTrue(skip.ready)
        assertEquals(listOf(1), skip.clickablePath)
        assertEquals(Bounds(800, 1500, 1040, 1600), skip.bounds)
        assertEquals(920, skip.bounds.centerX)
        assertEquals(1550, skip.bounds.centerY)
    }

    @Test
    fun noAd_ignoresSkipIntroAndPlayerClock() {
        val detection = AdDetector.detect(fixture("no_ad"), youtube)
        assertFalse(detection.adPlaying)
        assertNull(detection.skip)
        assertNull(detection.countdownSeconds)
    }

    @Test
    fun skipTextOnChild_clicksClickableAncestor() {
        val detection = AdDetector.detect(fixture("skip_via_ancestor"), youtube)
        assertTrue(detection.adPlaying)
        val skip = requireNotNull(detection.skip)
        assertTrue(skip.ready)
        assertEquals(listOf(1, 0), skip.nodePath)
        assertEquals(listOf(1), skip.clickablePath)
        assertEquals(Bounds(700, 1400, 1040, 1520), skip.bounds)
    }

    @Test
    fun skipInFive_isVisibleButNotReady() {
        val detection = AdDetector.detect(fixture("skip_in_5"), youtube)
        assertTrue(detection.adPlaying)
        assertEquals(5, detection.countdownSeconds)
        val skip = requireNotNull(detection.skip)
        assertFalse(skip.ready)
    }

    @Test
    fun sponsoredHomeTile_isNotAVideoAd() {
        val detection = AdDetector.detect(fixture("sponsored_home"), youtube)
        assertFalse(detection.adPlaying)
        assertNull(detection.skip)
    }

    @Test
    fun adWordFarFromTimestamp_isNotAVideoAd() {
        val time = listOf(0, 0, 0)
        val badge = listOf(1, 0, 0, 0)
        assertTrue(pathDistance(time, badge) > AdDetector.PROXIMITY)
        val detection = AdDetector.detect(fixture("far_ad_badge"), youtube)
        assertFalse(detection.adPlaying)
    }

    @Test
    fun playerClockNextToTheWordAd_isNotAVideoAd() {
        val detection = AdDetector.detect(fixture("player_clock_beside_ad_word"), youtube)
        assertFalse(detection.adPlaying)
        assertNull(detection.countdownSeconds)
    }

    @Test
    fun primeCountdown_besideAdBadge() {
        val detection = AdDetector.detect(fixture("prime_ad_countdown"), prime)
        assertTrue(detection.adPlaying)
        assertEquals(20, detection.countdownSeconds)
        assertNull(detection.skip)
        assertEquals("Ad", detection.label)
    }

    @Test
    fun primeSkip_isClickable() {
        val detection = AdDetector.detect(fixture("prime_skip_clickable"), prime)
        assertTrue(detection.adPlaying)
        assertEquals(5, detection.countdownSeconds)
        val skip = requireNotNull(detection.skip)
        assertTrue(skip.ready)
        assertEquals("Skip", skip.label)
        assertEquals(listOf(1), skip.clickablePath)
    }

    @Test
    fun livingRoomProfile_usesTheSamePrimeCues() {
        val detection = AdDetector.detect(fixture("prime_skip_clickable"), livingRoom)
        assertTrue(detection.adPlaying)
        assertTrue(detection.skip?.ready == true)
    }

    @Test
    fun caseInsensitiveSkipAd_andContentDescription() {
        val root = UiNode(
            children = listOf(
                UiNode(contentDescription = "SKIP ADS", clickable = true, bounds = Bounds(10, 10, 90, 50)),
            ),
        )
        val detection = AdDetector.detect(root, youtube)
        assertTrue(detection.adPlaying)
        assertTrue(requireNotNull(detection.skip).ready)
    }

    @Test
    fun disabledSkipButton_isNotReady() {
        val root = UiNode(
            children = listOf(
                UiNode(
                    text = "Skip ad",
                    clickable = true,
                    enabled = false,
                    visible = true,
                    bounds = Bounds(40, 80, 240, 140),
                ),
            ),
        )
        val detection = AdDetector.detect(root, youtube)
        assertTrue(detection.adPlaying)
        assertFalse(requireNotNull(detection.skip).ready)
    }

    @Test
    fun invisibleSkipTemplate_doesNotCountAsAnAd() {
        val root = UiNode(
            children = listOf(
                UiNode(text = "Skip", clickable = true, visible = false),
            ),
        )
        val detection = AdDetector.detect(root, youtube)
        assertFalse(detection.adPlaying)
        assertNull(detection.skip)
    }

    @Test
    fun viewIdSuffix_matchesFullResourceName() {
        val root = UiNode(
            children = listOf(
                UiNode(
                    viewId = "com.google.android.youtube:id/ad_progress_text",
                    text = "Ad · 0:08",
                    visible = true,
                    bounds = Bounds(40, 80, 360, 140),
                ),
            ),
        )
        val detection = AdDetector.detect(root, youtube)
        assertTrue(detection.adPlaying)
        assertNull(detection.skip)
        assertTrue(detection.hits.any { it.viewId?.endsWith("ad_progress_text") == true })
    }

    @Test
    fun invisibleAdNode_doesNotMatch() {
        val hidden = UiNode(
            children = listOf(
                UiNode(
                    viewId = "com.google.android.youtube:id/ad_progress_text",
                    text = "Ad · 0:05",
                    visible = false,
                    bounds = Bounds(40, 80, 360, 140),
                ),
                UiNode(
                    viewId = "com.google.android.youtube:id/ad_overlay",
                    visible = true,
                    bounds = Bounds.EMPTY,
                ),
            ),
        )
        val detection = AdDetector.detect(hidden, youtube)
        assertFalse(detection.adPlaying)
        assertTrue(detection.hits.isEmpty())
    }

    @Test
    fun visibleEmptyAdOverlay_doesNotMatch() {
        val root = UiNode(
            viewId = "com.google.android.youtube:id/ad_overlay",
            visible = true,
            bounds = Bounds(0, 0, 1080, 600),
            children = listOf(
                UiNode(
                    viewId = "com.google.android.youtube:id/title",
                    text = "How to make a shelf",
                    visible = true,
                    bounds = Bounds(40, 500, 900, 580),
                ),
            ),
        )
        assertFalse(AdDetector.detect(root, youtube).adPlaying)
    }

    @Test
    fun feedAdBadgeBesideDuration_isNotAVideoAd() {
        val root = UiNode(
            children = listOf(
                UiNode(
                    viewId = "com.google.android.youtube:id/ad_badge",
                    text = "Ad",
                    visible = true,
                    bounds = Bounds(20, 40, 90, 72),
                ),
                UiNode(
                    text = "0:30",
                    visible = true,
                    bounds = Bounds(20, 200, 110, 240),
                ),
            ),
        )
        assertFalse(AdDetector.detect(root, youtube).adPlaying)
    }

    @Test
    fun sponsoredBesideDuration_isNotAVideoAd() {
        val root = UiNode(
            children = listOf(
                UiNode(text = "Sponsored", visible = true, bounds = Bounds(20, 40, 220, 80)),
                UiNode(text = "0:12", visible = true, bounds = Bounds(20, 90, 120, 130)),
            ),
        )
        assertFalse(AdDetector.detect(root, youtube).adPlaying)
    }

    @Test
    fun oldStrongBadgeRule_doesNotTreatAThumbnailAsAnAd() {
        val withBadge = youtube.copy(
            adSignals = youtube.adSignals + SignalRule(
                source = SignalSource.VIEW_ID,
                match = MatchMode.SUFFIX,
                values = listOf("ad_badge"),
                strength = SignalStrength.STRONG,
            ),
        )
        val root = UiNode(
            children = listOf(
                UiNode(
                    viewId = "com.google.android.youtube:id/ad_badge",
                    text = "Ad",
                    visible = true,
                    bounds = Bounds(20, 40, 90, 72),
                ),
                UiNode(text = "0:30", visible = true, bounds = Bounds(20, 200, 110, 240)),
            ),
        )
        assertFalse(AdDetector.detect(root, withBadge).adPlaying)
    }

    @Test
    fun genericSkipButtonId_withoutSkipText_isNotAnAd() {
        val withSkipId = youtube.copy(
            skipSignals = youtube.skipSignals + SignalRule(
                source = SignalSource.VIEW_ID,
                match = MatchMode.SUFFIX,
                values = listOf("skip_button"),
                strength = SignalStrength.STRONG,
            ),
        )
        val root = UiNode(
            children = listOf(
                UiNode(
                    viewId = "com.google.android.youtube:id/skip_button",
                    visible = true,
                    bounds = Bounds(800, 1400, 980, 1500),
                ),
            ),
        )
        assertFalse(AdDetector.detect(root, withSkipId).adPlaying)
    }

    @Test
    fun titleThatSaysAd_besideAClock_isNotAVideoAd() {
        val root = UiNode(
            children = listOf(
                UiNode(
                    viewId = "com.google.android.youtube:id/title",
                    text = "Ad",
                    visible = true,
                    bounds = Bounds(40, 400, 800, 470),
                ),
                UiNode(text = "0:20", visible = true, bounds = Bounds(40, 480, 160, 520)),
            ),
        )
        assertFalse(AdDetector.detect(root, youtube).adPlaying)
    }
}
