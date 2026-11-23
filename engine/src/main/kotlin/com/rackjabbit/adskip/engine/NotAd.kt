package com.rackjabbit.adskip.engine

object NotAdCopy {
    const val BUTTON = "This isn't an ad"
    const val TOAST = "Got it, saved a report"
    const val STAT = "Marked not an ad"
    const val REPORT_NOTE = "report: This isn't an ad"
}

/**
 * Layers of the ad overlay. The button lives on a touchable layer so the tap
 * reaches the app. A full-screen dim, when there is one, does not take touches,
 * so Skip underneath still works.
 */
data class OverlayLayer(
    val name: String,
    val touchable: Boolean,
    val hostsNotAdButton: Boolean,
)

object OverlayWindowPolicy {
    fun layers(style: OverlayStyle): List<OverlayLayer> = when (style) {
        OverlayStyle.BANNER -> listOf(
            OverlayLayer(name = "card", touchable = true, hostsNotAdButton = true),
        )
        OverlayStyle.COVER -> listOf(
            OverlayLayer(name = "scrim", touchable = false, hostsNotAdButton = false),
            OverlayLayer(name = "card", touchable = true, hostsNotAdButton = true),
        )
    }
}

object ScreenLogPolicy {
    fun shouldWrite(debugEnabled: Boolean, falsePositiveReport: Boolean): Boolean =
        falsePositiveReport || debugEnabled
}

/**
 * After "This isn't an ad", ignore the same match until the video changes or the
 * match goes away and later comes back. A one-frame flicker does not re-arm.
 */
class NotAdSuppression(
    private var gapSamples: Int = GAP,
) {
    var active: Boolean = false
        private set

    private var signature: String = ""
    private var screenKey: String = ""
    private var packageName: String = ""
    private var absent: Int = 0
    private var gapOpen: Boolean = false

    fun updateGap(value: Int) {
        gapSamples = value.coerceIn(1, 10)
    }

    fun suppress(signature: String, screenKey: String, packageName: String) {
        active = true
        this.signature = signature
        this.screenKey = screenKey
        this.packageName = packageName
        absent = 0
        gapOpen = false
    }

    fun clear() {
        active = false
        signature = ""
        screenKey = ""
        packageName = ""
        absent = 0
        gapOpen = false
    }

    fun allow(adPlaying: Boolean, matchSignature: String, screenKey: String, packageName: String): Boolean {
        if (!active) return true
        if (packageName.isNotEmpty() && this.packageName.isNotEmpty() && packageName != this.packageName) {
            clear()
            return true
        }
        if (isNewVideo(screenKey)) {
            clear()
            return true
        }
        val same = adPlaying && (signature.isEmpty() || matchSignature == signature)
        if (same && !gapOpen) {
            absent = 0
            return false
        }
        if (adPlaying && gapOpen) {
            clear()
            return true
        }
        absent += 1
        if (absent >= gapSamples) gapOpen = true
        return false
    }

    private fun isNewVideo(screenKey: String): Boolean =
        screenKey.isNotEmpty() && this.screenKey.isNotEmpty() && screenKey != this.screenKey

    companion object {
        const val GAP = 3
    }
}

/**
 * Stable identity of the video on screen. Ad copy is left out so an ad starting
 * or ending does not look like a new video. An empty string means the title is
 * not in the tree yet.
 */
fun videoScreenKey(root: UiNode): String {
    return root.flatten()
        .asSequence()
        .filter { it.shownToUser() }
        .mapNotNull { node ->
            val id = node.viewId?.lowercase() ?: return@mapNotNull null
            if (!id.contains("title")) return@mapNotNull null
            if (id.contains("ad_") || id.contains("subtitle")) return@mapNotNull null
            node.text?.trim()?.takeIf { it.length in 2..180 }
        }
        .distinct()
        .take(2)
        .joinToString("\n")
}
