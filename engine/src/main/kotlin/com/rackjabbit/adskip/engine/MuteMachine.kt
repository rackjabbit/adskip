package com.rackjabbit.adskip.engine

/**
 * Holds a media mute across brief detection gaps (Ad 1 of 2, then Ad 2 of 2)
 * and asks the platform layer to mute again if the stream comes back while the
 * ad block is still on.
 */
class MuteMachine(
    private val graceMs: Long = GRACE_MS,
    private val reapplyMs: Long = REAPPLY_MS,
) {
    var holding: Boolean = false
        private set

    private var restoreLevel: Int? = null
    private var lastAdAtMs: Long = Long.MIN_VALUE
    private var lastApplyAtMs: Long = Long.MIN_VALUE
    private var userAdjusted: Boolean = false
    private var indexZeroed: Boolean = false
    private var alreadyMuted: Boolean = false

    fun onSample(
        adActive: Boolean,
        wantMute: Boolean,
        nowMs: Long,
        volumeIndex: Int,
        streamMuted: Boolean,
    ): VolumeCommand {
        if (!wantMute) return forceRelease()
        if (adActive) lastAdAtMs = nowMs
        val inGrace = holding && !adActive && nowMs - lastAdAtMs < graceMs
        if (!adActive && !inGrace) return forceRelease()
        if (!holding) {
            holding = true
            restoreLevel = volumeIndex
            alreadyMuted = streamMuted
            userAdjusted = false
            indexZeroed = false
            lastApplyAtMs = nowMs
            return VolumeCommand.Mute(volumeIndex)
        }
        val saved = restoreLevel ?: volumeIndex
        if (volumeIndex > 0 && volumeIndex != saved) {
            userAdjusted = true
            restoreLevel = volumeIndex
        }
        if (volumeIndex <= 0 && !streamMuted) indexZeroed = true
        val silent = streamMuted || volumeIndex <= 0
        if (!silent && nowMs - lastApplyAtMs >= reapplyMs) {
            lastApplyAtMs = nowMs
            return VolumeCommand.Reapply(restoreLevel ?: volumeIndex)
        }
        return VolumeCommand.None
    }

    fun observe(volumeIndex: Int, streamMuted: Boolean) {
        if (!holding) return
        val saved = restoreLevel ?: return
        if (volumeIndex > 0 && volumeIndex != saved) {
            userAdjusted = true
            restoreLevel = volumeIndex
        }
        if (volumeIndex <= 0 && !streamMuted) indexZeroed = true
    }

    fun forceRelease(): VolumeCommand {
        if (!holding) return VolumeCommand.None
        val level = restoreLevel ?: 0
        val keepCurrentIndex = alreadyMuted || userAdjusted || !indexZeroed
        val leaveMuted = alreadyMuted
        clear()
        return VolumeCommand.Restore(
            level = level,
            keepCurrentIndex = keepCurrentIndex,
            leaveMuted = leaveMuted,
        )
    }

    private fun clear() {
        holding = false
        restoreLevel = null
        lastAdAtMs = Long.MIN_VALUE
        lastApplyAtMs = Long.MIN_VALUE
        userAdjusted = false
        indexZeroed = false
        alreadyMuted = false
    }

    companion object {
        const val GRACE_MS = 2_500L
        const val REAPPLY_MS = 500L
    }
}
