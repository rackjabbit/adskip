package com.rackjabbit.adskip.data

import android.content.Context
import android.content.SharedPreferences
import com.rackjabbit.adskip.engine.OverlayStyle

class SettingsRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var masterEnabled: Boolean
        get() = prefs.getBoolean(KEY_MASTER, true)
        set(value) = prefs.edit().putBoolean(KEY_MASTER, value).apply()

    var muteDuringAds: Boolean
        get() = prefs.getBoolean(KEY_MUTE, false)
        set(value) = prefs.edit().putBoolean(KEY_MUTE, value).apply()

    var debugDump: Boolean
        get() = prefs.getBoolean(KEY_DEBUG, false)
        set(value) = prefs.edit().putBoolean(KEY_DEBUG, value).apply()

    var overlayStyle: OverlayStyle
        get() = if (prefs.getString(KEY_STYLE, STYLE_BANNER) == STYLE_COVER) OverlayStyle.COVER else OverlayStyle.BANNER
        set(value) {
            val stored = if (value == OverlayStyle.COVER) STYLE_COVER else STYLE_BANNER
            prefs.edit().putString(KEY_STYLE, stored).apply()
        }

    var opacity: Float
        get() = prefs.getFloat(KEY_OPACITY, 0.72f).coerceIn(MIN_OPACITY, MAX_OPACITY)
        set(value) = prefs.edit().putFloat(KEY_OPACITY, value.coerceIn(MIN_OPACITY, MAX_OPACITY)).apply()

    var savedVolume: Int?
        get() = if (prefs.contains(KEY_SAVED_VOLUME)) prefs.getInt(KEY_SAVED_VOLUME, 0) else null
        set(value) {
            if (value == null) prefs.edit().remove(KEY_SAVED_VOLUME).apply()
            else prefs.edit().putInt(KEY_SAVED_VOLUME, value).apply()
        }

    var muteIndexZeroed: Boolean
        get() = prefs.getBoolean(KEY_MUTE_ZEROED, false)
        set(value) = prefs.edit().putBoolean(KEY_MUTE_ZEROED, value).apply()

    val adsSeen: Int get() = prefs.getInt(KEY_ADS, 0)
    val skips: Int get() = prefs.getInt(KEY_SKIPS, 0)

    var lastDumpPath: String?
        get() = prefs.getString(KEY_DUMP_PATH, null)
        set(value) = prefs.edit().putString(KEY_DUMP_PATH, value).apply()

    fun isAppEnabled(packageName: String): Boolean = prefs.getBoolean(appKey(packageName), true)

    fun setAppEnabled(packageName: String, enabled: Boolean) {
        prefs.edit().putBoolean(appKey(packageName), enabled).apply()
    }

    fun recordAd() {
        prefs.edit().putInt(KEY_ADS, adsSeen + 1).apply()
    }

    fun recordSkip() {
        prefs.edit().putInt(KEY_SKIPS, skips + 1).apply()
    }

    fun resetStats() {
        prefs.edit().putInt(KEY_ADS, 0).putInt(KEY_SKIPS, 0).apply()
    }

    fun register(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregister(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun appKey(packageName: String) = "app:$packageName"

    companion object {
        const val MIN_OPACITY = 0.2f
        const val MAX_OPACITY = 0.92f
        private const val FILE = "adskip"
        private const val KEY_MASTER = "master"
        private const val KEY_MUTE = "mute"
        private const val KEY_DEBUG = "debug"
        private const val KEY_STYLE = "overlay_style"
        private const val KEY_OPACITY = "opacity"
        private const val KEY_SAVED_VOLUME = "saved_volume"
        private const val KEY_MUTE_ZEROED = "mute_index_zeroed"
        private const val KEY_ADS = "ads_seen"
        private const val KEY_SKIPS = "skips"
        private const val KEY_DUMP_PATH = "last_dump"
        private const val STYLE_BANNER = "banner"
        private const val STYLE_COVER = "cover"
    }
}
