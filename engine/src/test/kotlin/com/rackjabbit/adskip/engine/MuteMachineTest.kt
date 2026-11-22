package com.rackjabbit.adskip.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MuteMachineTest {
    private val machine = MuteMachine(graceMs = 2_500, reapplyMs = 500)

    @Test
    fun backToBackAds_stayMutedUntilTheBlockEnds() {
        assertEquals(VolumeCommand.Mute(8), machine.onSample(true, true, 0, 8, false))
        assertEquals(VolumeCommand.None, machine.onSample(false, true, 400, 8, true))
        assertEquals(VolumeCommand.None, machine.onSample(true, true, 900, 8, true))
        assertEquals(VolumeCommand.None, machine.onSample(false, true, 1_200, 8, true))
        assertTrue(machine.holding)
        val restored = machine.onSample(false, true, 900 + 2_500, 8, true)
        assertEquals(VolumeCommand.Restore(level = 8, keepCurrentIndex = true, leaveMuted = false), restored)
        assertFalse(machine.holding)
    }

    @Test
    fun flappingDetection_doesNotUnmute() {
        assertTrue(machine.onSample(true, true, 0, 6, false) is VolumeCommand.Mute)
        assertEquals(VolumeCommand.None, machine.onSample(false, true, 80, 6, true))
        assertEquals(VolumeCommand.None, machine.onSample(true, true, 160, 6, true))
        assertEquals(VolumeCommand.None, machine.onSample(false, true, 240, 6, true))
        assertEquals(VolumeCommand.None, machine.onSample(true, true, 320, 6, true))
        assertTrue(machine.holding)
        assertTrue(machine.onSample(false, true, 320 + 2_499, 6, true) is VolumeCommand.None)
    }

    @Test
    fun raisedStream_isMutedAgain() {
        machine.onSample(adActive = true, wantMute = true, nowMs = 0, volumeIndex = 8, streamMuted = false)
        assertEquals(VolumeCommand.None, machine.onSample(true, true, 100, 8, false))
        assertEquals(
            VolumeCommand.Reapply(8),
            machine.onSample(true, true, 500, 8, false),
        )
    }

    @Test
    fun manualVolumeChange_isWhatWeRestore() {
        machine.onSample(true, true, 0, 8, false)
        machine.onSample(true, true, 100, 8, true)
        assertEquals(VolumeCommand.Reapply(11), machine.onSample(true, true, 700, 11, false))
        assertEquals(
            VolumeCommand.Restore(level = 11, keepCurrentIndex = true, leaveMuted = false),
            machine.forceRelease(),
        )
    }

    @Test
    fun zeroedIndex_isWrittenBackOnRestore() {
        machine.onSample(true, true, 0, 5, false)
        assertEquals(VolumeCommand.None, machine.onSample(true, true, 200, 0, false))
        assertEquals(
            VolumeCommand.Restore(level = 5, keepCurrentIndex = false, leaveMuted = false),
            machine.forceRelease(),
        )
    }

    @Test
    fun destroyDuringGrace_restoresImmediately() {
        machine.onSample(true, true, 0, 4, false)
        assertEquals(VolumeCommand.None, machine.onSample(false, true, 300, 4, true))
        assertEquals(
            VolumeCommand.Restore(level = 4, keepCurrentIndex = true, leaveMuted = false),
            machine.forceRelease(),
        )
        assertEquals(VolumeCommand.None, machine.forceRelease())
    }

    @Test
    fun alreadyMutedMedia_staysMuted() {
        assertEquals(VolumeCommand.Mute(0), machine.onSample(true, true, 0, 0, true))
        assertEquals(
            VolumeCommand.Restore(level = 0, keepCurrentIndex = true, leaveMuted = true),
            machine.forceRelease(),
        )
    }

    @Test
    fun turningMuteOff_releasesImmediately() {
        machine.onSample(true, true, 0, 9, false)
        assertEquals(
            VolumeCommand.Restore(level = 9, keepCurrentIndex = true, leaveMuted = false),
            machine.onSample(true, false, 50, 9, true),
        )
    }
}
