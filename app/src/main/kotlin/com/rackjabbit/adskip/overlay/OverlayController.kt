package com.rackjabbit.adskip.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.rackjabbit.adskip.engine.NotAdCopy
import com.rackjabbit.adskip.engine.OverlayLayer
import com.rackjabbit.adskip.engine.OverlayStyle
import com.rackjabbit.adskip.engine.OverlayWindowPolicy
import kotlin.math.max

class OverlayController(
    private val service: AccessibilityService,
    private val onNotAnAd: () -> Unit,
) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var scrimView: View? = null
    private var cardView: View? = null
    private var titleView: TextView? = null
    private var detailView: TextView? = null
    private var card: LinearLayout? = null
    private var root: FrameLayout? = null
    private var style: OverlayStyle? = null

    fun show(style: OverlayStyle, opacity: Float, title: String, detail: String?) {
        val layers = OverlayWindowPolicy.layers(style)
        if (cardView == null || this.style != style) {
            hide()
            val scrimLayer = layers.firstOrNull { !it.hostsNotAdButton }
            val cardLayer = layers.first { it.hostsNotAdButton }
            val created = buildCard()
            try {
                if (scrimLayer != null) {
                    val scrim = buildScrim()
                    windowManager.addView(scrim, layoutParams(scrimLayer, style))
                    scrimView = scrim
                }
                windowManager.addView(created, layoutParams(cardLayer, style))
            } catch (error: Exception) {
                Log.w(TAG, "could not add the ad overlay", error)
                hide()
                return
            }
            cardView = created
            this.style = style
        } else {
            layers.forEach { layer ->
                val target = if (layer.hostsNotAdButton) cardView else scrimView
                if (target == null) return@forEach
                try {
                    windowManager.updateViewLayout(target, layoutParams(layer, style))
                } catch (error: Exception) {
                    Log.w(TAG, "could not move the ad overlay", error)
                }
            }
        }
        bind(style, opacity, title, detail)
    }

    fun hide() {
        remove(cardView)
        remove(scrimView)
        cardView = null
        scrimView = null
        titleView = null
        detailView = null
        card = null
        root = null
        style = null
    }

    private fun remove(existing: View?) {
        if (existing == null) return
        try {
            windowManager.removeView(existing)
        } catch (error: Exception) {
            Log.w(TAG, "could not remove the ad overlay", error)
        }
    }

    private fun buildScrim(): View = FrameLayout(service).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        isClickable = false
    }

    private fun buildCard(): View {
        val root = FrameLayout(service).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            val side = dp(12)
            setPadding(side, dp(8), side, dp(8))
        }
        val card = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
            isClickable = false
        }
        val titleView = TextView(service).apply {
            setTextColor(Color.parseColor("#F4F1EA"))
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        }
        val detailView = TextView(service).apply {
            setTextColor(Color.parseColor("#E2B657"))
            textSize = 14f
            setPadding(0, dp(4), 0, 0)
        }
        val button = TextView(service).apply {
            text = NotAdCopy.BUTTON
            contentDescription = NotAdCopy.BUTTON
            setTextColor(Color.parseColor("#1A1408"))
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            minHeight = dp(48)
            val padH = dp(16)
            val padV = dp(12)
            setPadding(padH, padV, padH, padV)
            isClickable = true
            isFocusable = false
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#E2B657"))
            }
            setOnClickListener { onNotAnAd() }
        }
        card.addView(titleView)
        card.addView(detailView)
        card.addView(
            button,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )
        root.addView(
            card,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        this.root = root
        this.card = card
        this.titleView = titleView
        this.detailView = detailView
        return root
    }

    private fun bind(style: OverlayStyle, opacity: Float, title: String, detail: String?) {
        val cardBackground = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(
                if (style == OverlayStyle.COVER) {
                    Color.argb(235, 20, 24, 20)
                } else {
                    scrim(max(opacity, 0.45f))
                },
            )
        }
        card?.background = cardBackground
        scrimView?.setBackgroundColor(scrim(opacity))
        root?.setBackgroundColor(Color.TRANSPARENT)
        titleView?.text = title
        val detailText = detail?.takeIf { it.isNotBlank() }
        detailView?.text = detailText
        detailView?.visibility = if (detailText == null) View.GONE else View.VISIBLE
    }

    /**
     * [OverlayLayer.touchable] is the button's window. It must not set
     * [WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE], or the tap never arrives.
     * The cover scrim keeps that flag so the dim does not block Skip.
     */
    private fun layoutParams(layer: OverlayLayer, style: OverlayStyle): WindowManager.LayoutParams {
        val fullScreen = !layer.hostsNotAdButton
        val height = if (fullScreen) {
            WindowManager.LayoutParams.MATCH_PARENT
        } else {
            WindowManager.LayoutParams.WRAP_CONTENT
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            overlayLayoutFlags(layer.touchable),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = when {
                fullScreen -> Gravity.FILL
                style == OverlayStyle.COVER -> Gravity.CENTER
                else -> Gravity.TOP
            }
            if (!fullScreen && style == OverlayStyle.BANNER) {
                y = statusBarHeight() + dp(6)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun statusBarHeight(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val top = windowManager.currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.statusBars())
                .top
            if (top > 0) return top
        }
        return dp(24)
    }

    private fun scrim(opacity: Float): Int {
        val alpha = (opacity.coerceIn(0f, 1f) * 255).toInt()
        return Color.argb(alpha, 16, 20, 16)
    }

    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "AdSkip"
    }
}

internal fun overlayLayoutFlags(touchable: Boolean): Int {
    val base = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
    return if (!touchable) base or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else base
}
