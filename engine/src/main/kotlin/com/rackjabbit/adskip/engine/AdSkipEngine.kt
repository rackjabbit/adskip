package com.rackjabbit.adskip.engine

enum class OverlayStyle {
    BANNER,
    COVER,
}

data class EngineSettings(
    val masterEnabled: Boolean,
    val packageEnabled: Boolean,
    val muteDuringAds: Boolean,
    val overlayStyle: OverlayStyle,
    val opacity: Float,
)

data class PlannedClick(
    val path: List<Int>,
    val bounds: Bounds,
    val signature: String,
    val label: String,
)

sealed class VolumeCommand {
    data object None : VolumeCommand()
    data class Mute(val restoreLevel: Int) : VolumeCommand()
    data class Reapply(val restoreLevel: Int) : VolumeCommand()

    /**
     * [keepCurrentIndex] is set when the live music index is already the level
     * to reveal (mute flag, or the user moved the volume during the ad).
     * [leaveMuted] is set when media was already muted before the ad.
     */
    data class Restore(
        val level: Int,
        val keepCurrentIndex: Boolean,
        val leaveMuted: Boolean,
    ) : VolumeCommand()
}

data class EngineOutput(
    val showOverlay: Boolean,
    val overlayStyle: OverlayStyle,
    val opacity: Float,
    val title: String,
    val detail: String?,
    val click: PlannedClick?,
    val volume: VolumeCommand,
    val muteHeld: Boolean,
    val adStarted: Boolean,
    val adEnded: Boolean,
    val detection: Detection?,
    val showNotAdButton: Boolean,
    val dismissedNotAd: Boolean,
)

class AdSession {
    var active: Boolean = false
        private set
    private var misses = 0
    private var clearMisses = 3

    fun updateThreshold(value: Int) {
        clearMisses = value.coerceIn(1, 10)
    }

    fun onDetection(adPlaying: Boolean): SessionTick {
        if (adPlaying) {
            val started = !active
            active = true
            misses = 0
            return SessionTick(active = true, started = started, ended = false)
        }
        if (!active) return SessionTick(active = false, started = false, ended = false)
        misses += 1
        if (misses >= clearMisses) {
            active = false
            misses = 0
            return SessionTick(active = false, started = false, ended = true)
        }
        return SessionTick(active = true, started = false, ended = false)
    }

    fun endNow(): SessionTick {
        val ended = active
        active = false
        misses = 0
        return SessionTick(active = false, started = false, ended = ended)
    }
}

data class SessionTick(
    val active: Boolean,
    val started: Boolean,
    val ended: Boolean,
)

class AdSkipEngine(
    private val configProvider: () -> AdConfig,
) {
    private val session = AdSession()
    private val debouncer = ClickDebouncer()
    private val mute = MuteMachine()
    private val suppression = NotAdSuppression()
    private var displayed = OverlayCopy.idle()
    private var nullStreak = 0
    private var latchedSignature = ""
    private var latchedScreenKey = ""
    private var latchedPackage = ""

    val isOverlayShowing: Boolean get() = session.active

    fun onTree(
        root: UiNode?,
        packageName: String?,
        settings: EngineSettings,
        nowMs: Long,
        currentVolume: Int,
        streamMuted: Boolean = false,
    ): EngineOutput {
        val config = configProvider()
        session.updateThreshold(config.clearMisses)
        suppression.updateGap(config.clearMisses)
        val app = packageName?.let { pkg -> config.apps.find { it.packageName == pkg } }
        if (!settings.masterEnabled || !settings.packageEnabled || app == null) {
            mute.observe(currentVolume, streamMuted)
            return stop(settings)
        }
        if (root == null) {
            nullStreak += 1
            val ended = if (nullStreak >= 2) session.endNow().ended else false
            if (!session.active) displayed = OverlayCopy.idle()
            return finish(
                settings = settings,
                showOverlay = session.active,
                detail = if (session.active) displayed.detail else null,
                click = null,
                adStarted = false,
                adEnded = ended,
                detection = null,
                adActive = false,
                nowMs = nowMs,
                currentVolume = currentVolume,
                streamMuted = streamMuted,
            )
        }
        nullStreak = 0
        val raw = AdDetector.detect(root, app)
        val screenKey = videoScreenKey(root)
        latchedPackage = packageName
        if (screenKey.isNotEmpty()) latchedScreenKey = screenKey
        if (raw.adPlaying) latchedSignature = raw.matchSignature()
        val allowed = suppression.allow(raw.adPlaying, raw.matchSignature(), screenKey, packageName.orEmpty())
        val detection = if (allowed) raw else raw.copy(adPlaying = false, countdownSeconds = null, skip = null)
        if (detection.adPlaying) {
            displayed = OverlayCopy.build(detection.countdownSeconds, detection.label)
        }
        val tick = session.onDetection(detection.adPlaying)
        if (!tick.active) displayed = OverlayCopy.idle()
        val skip = detection.skip
        val click = if (
            tick.active &&
            skip != null &&
            skip.ready &&
            skip.clickablePath != null &&
            debouncer.allow(nowMs, skip.signature, config.debounceMs)
        ) {
            PlannedClick(
                path = skip.clickablePath,
                bounds = skip.bounds,
                signature = skip.signature,
                label = skip.label,
            )
        } else {
            null
        }
        return finish(
            settings = settings,
            showOverlay = tick.active,
            detail = if (tick.active) displayed.detail else null,
            click = click,
            adStarted = tick.started,
            adEnded = tick.ended,
            detection = detection,
            adActive = detection.adPlaying,
            nowMs = nowMs,
            currentVolume = currentVolume,
            streamMuted = streamMuted,
        )
    }

    /**
     * Hans tapped "This isn't an ad". Hide immediately, give the volume back,
     * and don't plan another skip until this video or this match actually changes.
     */
    fun markNotAnAd(
        settings: EngineSettings,
        currentVolume: Int,
        streamMuted: Boolean = false,
    ): EngineOutput {
        mute.observe(currentVolume, streamMuted)
        suppression.suppress(latchedSignature, latchedScreenKey, latchedPackage)
        nullStreak = 0
        debouncer.reset()
        val tick = session.endNow()
        displayed = OverlayCopy.idle()
        return output(
            showOverlay = false,
            settings = settings,
            detail = null,
            click = null,
            volume = mute.forceRelease(),
            adStarted = false,
            adEnded = tick.ended,
            detection = null,
            dismissedNotAd = true,
        )
    }

    fun onTargetHidden(
        settings: EngineSettings,
        currentVolume: Int,
        streamMuted: Boolean = false,
    ): EngineOutput {
        mute.observe(currentVolume, streamMuted)
        return stop(settings)
    }

    private fun stop(settings: EngineSettings): EngineOutput {
        nullStreak = 0
        val tick = session.endNow()
        displayed = OverlayCopy.idle()
        return output(
            showOverlay = false,
            settings = settings,
            detail = null,
            click = null,
            volume = mute.forceRelease(),
            adStarted = false,
            adEnded = tick.ended,
            detection = null,
        )
    }

    private fun finish(
        settings: EngineSettings,
        showOverlay: Boolean,
        detail: String?,
        click: PlannedClick?,
        adStarted: Boolean,
        adEnded: Boolean,
        detection: Detection?,
        adActive: Boolean,
        nowMs: Long,
        currentVolume: Int,
        streamMuted: Boolean,
    ): EngineOutput = output(
        showOverlay = showOverlay,
        settings = settings,
        detail = detail,
        click = click,
        volume = mute.onSample(
            adActive = adActive,
            wantMute = settings.muteDuringAds,
            nowMs = nowMs,
            volumeIndex = currentVolume,
            streamMuted = streamMuted,
        ),
        adStarted = adStarted,
        adEnded = adEnded,
        detection = detection,
    )

    private fun output(
        showOverlay: Boolean,
        settings: EngineSettings,
        detail: String?,
        click: PlannedClick?,
        volume: VolumeCommand,
        adStarted: Boolean,
        adEnded: Boolean,
        detection: Detection?,
        dismissedNotAd: Boolean = false,
    ): EngineOutput = EngineOutput(
        showOverlay = showOverlay,
        overlayStyle = settings.overlayStyle,
        opacity = settings.opacity,
        title = displayed.title,
        detail = detail,
        click = click,
        volume = volume,
        muteHeld = mute.holding,
        adStarted = adStarted,
        adEnded = adEnded,
        detection = detection,
        showNotAdButton = showOverlay,
        dismissedNotAd = dismissedNotAd,
    )
}
