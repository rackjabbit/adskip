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
import com.rackjabbit.adskip.engine.OverlayStyle
import kotlin.math.max

class OverlayController(private val service: AccessibilityService) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var titleView: TextView? = null
    private var detailView: TextView? = null
    private var card: LinearLayout? = null
    private var root: FrameLayout? = null
    private var style: OverlayStyle? = null

    fun show(style: OverlayStyle, opacity: Float, title: String, detail: String?) {
        if (view == null || this.style != style) {
            hide()
            val created = build(style)
            try {
                windowManager.addView(created, layoutParams(style))
            } catch (error: Exception) {
                Log.w(TAG, "could not add the ad overlay", error)
                return
            }
            view = created
            this.style = style
        } else {
            try {
                windowManager.updateViewLayout(view, layoutParams(style))
            } catch (error: Exception) {
                Log.w(TAG, "could not move the ad overlay", error)
            }
        }
        bind(style, opacity, title, detail)
    }

    fun hide() {
        val existing = view ?: return
        try {
            windowManager.removeView(existing)
        } catch (error: Exception) {
            Log.w(TAG, "could not remove the ad overlay", error)
        }
        view = null
        titleView = null
        detailView = null
        card = null
        root = null
        style = null
    }

    private fun build(style: OverlayStyle): View {
        val root = FrameLayout(service).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            val side = dp(12)
            val top = if (style == OverlayStyle.BANNER) dp(8) else 0
            setPadding(side, top, side, if (style == OverlayStyle.BANNER) dp(8) else 0)
        }
        val card = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
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
        card.addView(titleView)
        card.addView(detailView)
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        )
        if (style == OverlayStyle.COVER) {
            params.gravity = Gravity.CENTER
            val margin = dp(28)
            params.setMargins(margin, margin, margin, margin)
        }
        root.addView(card, params)
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
        if (style == OverlayStyle.COVER) {
            root?.setBackgroundColor(scrim(opacity))
        } else {
            root?.setBackgroundColor(Color.TRANSPARENT)
        }
        titleView?.text = title
        val detailText = detail?.takeIf { it.isNotBlank() }
        detailView?.text = detailText
        detailView?.visibility = if (detailText == null) View.GONE else View.VISIBLE
    }

    private fun layoutParams(style: OverlayStyle): WindowManager.LayoutParams {
        val height = if (style == OverlayStyle.COVER) {
            WindowManager.LayoutParams.MATCH_PARENT
        } else {
            WindowManager.LayoutParams.WRAP_CONTENT
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = if (style == OverlayStyle.COVER) Gravity.FILL else Gravity.TOP
            if (style == OverlayStyle.BANNER) {
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
