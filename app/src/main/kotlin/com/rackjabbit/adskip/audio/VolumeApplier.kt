package com.rackjabbit.adskip.audio

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.rackjabbit.adskip.data.SettingsRepository

/**
 * Mutes only [AudioManager.STREAM_MUSIC]. Alarms, calls, and the ringer are left alone.
 *
 * [AudioManager.setStreamVolume] to 0 often does not stick: YouTube writes the previous
 * media volume back, and some phones keep playing because the stream mute flag is still
 * clear. [AudioManager.ADJUST_MUTE] sets that flag. The volume index is only forced to 0
 * when the flag did not take.
 */
class VolumeApplier(
    context: Context,
    private val repository: SettingsRepository,
) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val notes = ArrayDeque<String>()

    fun current(): Int = try {
        audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    } catch (error: Exception) {
        Log.w(TAG, "could not read media volume", error)
        0
    }

    fun isMusicMuted(): Boolean = try {
        audio.isStreamMute(AudioManager.STREAM_MUSIC)
    } catch (error: Exception) {
        Log.w(TAG, "could not read media mute flag", error)
        false
    }

    fun notes(): List<String> = notes.toList()

    fun recoverStuck() {
        val saved = repository.savedVolume ?: return
        val zeroed = repository.muteIndexZeroed
        if (isMusicMuted()) adjust(AudioManager.ADJUST_UNMUTE)
        if (zeroed) set(saved)
        note(
            "volume restored on service start level=$saved keepIndex=${!zeroed} " +
                "volumeNow=${current()} mutedNow=${isMusicMuted()}",
        )
        clearSaved()
    }

    fun applyMute(restoreLevel: Int) {
        repository.savedVolume = restoreLevel
        note("mute applied stream=MUSIC restore=$restoreLevel method=ADJUST_MUTE")
        silence(restoreLevel, reapply = false)
    }

    fun reapply(restoreLevel: Int) {
        repository.savedVolume = restoreLevel
        note("mute re-applied stream=MUSIC restore=$restoreLevel method=ADJUST_MUTE")
        silence(restoreLevel, reapply = true)
    }

    fun restore(level: Int, keepCurrentIndex: Boolean, leaveMuted: Boolean) {
        if (leaveMuted) {
            note("volume restore skipped; media was already muted before the ad")
            clearSaved()
            return
        }
        if (isMusicMuted()) adjust(AudioManager.ADJUST_UNMUTE)
        if (!keepCurrentIndex && current() != level) set(level)
        note(
            "volume restored level=$level keepIndex=$keepCurrentIndex " +
                "volumeNow=${current()} mutedNow=${isMusicMuted()}",
        )
        clearSaved()
    }

    private fun silence(restoreLevel: Int, reapply: Boolean) {
        try {
            if (audio.isVolumeFixed) {
                note("mute warning: this output reports fixed volume; media mute may not stick")
            }
        } catch (error: Exception) {
            Log.w(TAG, "could not read fixed-volume state", error)
        }
        adjust(AudioManager.ADJUST_MUTE)
        var muted = isMusicMuted()
        var volume = current()
        note("mute verified muted=$muted volume=$volume")
        var zeroed = false
        if (!muted && volume > 0) {
            note("mute fallback setStreamVolume 0 after ADJUST_MUTE left volume=$volume")
            set(0)
            zeroed = true
            muted = isMusicMuted()
            volume = current()
            note("mute verified after fallback muted=$muted volume=$volume reapply=$reapply restore=$restoreLevel")
        }
        repository.muteIndexZeroed = zeroed
        if (!muted && volume > 0) {
            note("mute failed: music stream still audible volume=$volume")
        }
    }

    private fun adjust(direction: Int) {
        try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        } catch (error: Exception) {
            Log.w(TAG, "adjustStreamVolume $direction failed", error)
            note("mute adjust failed direction=$direction ${error.javaClass.simpleName}")
        }
    }

    private fun set(level: Int) {
        try {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, level.coerceIn(0, max), 0)
        } catch (error: Exception) {
            Log.w(TAG, "could not change media volume", error)
            note("mute setStreamVolume failed ${error.javaClass.simpleName}")
        }
    }

    private fun clearSaved() {
        repository.savedVolume = null
        repository.muteIndexZeroed = false
    }

    private fun note(line: String) {
        Log.i(TAG, line)
        notes.addLast(line)
        while (notes.size > NOTE_LIMIT) notes.removeFirst()
    }

    companion object {
        private const val TAG = "AdSkip"
        private const val NOTE_LIMIT = 12
    }
}
