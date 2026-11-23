package com.rackjabbit.adskip.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.SharedPreferences
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.rackjabbit.adskip.audio.VolumeApplier
import com.rackjabbit.adskip.data.ConfigFiles
import com.rackjabbit.adskip.data.SettingsRepository
import com.rackjabbit.adskip.debug.DebugFiles
import com.rackjabbit.adskip.debug.DumpWriter
import com.rackjabbit.adskip.engine.AdSkipEngine
import com.rackjabbit.adskip.engine.Bounds
import com.rackjabbit.adskip.engine.Detection
import com.rackjabbit.adskip.engine.DumpThrottle
import com.rackjabbit.adskip.engine.EngineOutput
import com.rackjabbit.adskip.engine.EngineSettings
import com.rackjabbit.adskip.engine.NodeTreeFormatter
import com.rackjabbit.adskip.engine.NotAdCopy
import com.rackjabbit.adskip.engine.PlannedClick
import com.rackjabbit.adskip.engine.ScreenLogPolicy
import com.rackjabbit.adskip.engine.UiNode
import com.rackjabbit.adskip.engine.VolumeCommand
import com.rackjabbit.adskip.overlay.OverlayController
import java.time.Instant

class AdSkipAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val repository by lazy { SettingsRepository(this) }
    private val volumes by lazy { VolumeApplier(this, repository) }
    private val overlay by lazy { OverlayController(this, ::onNotAnAd) }
    private val windowManager by lazy { getSystemService(WindowManager::class.java) }
    private val engine = AdSkipEngine { ConfigFiles.store(this).current() }
    private val dumpThrottle = DumpThrottle()
    private var lastPackage: String? = null
    private var evalQueued = false
    private var muteHeld = false
    private var reportTree: UiNode? = null
    private var reportDetection: Detection? = null
    private var reportPackage: String? = null
    private var reportingNotAd = false

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        refreshSoon()
    }

    private val evalRunnable = Runnable {
        evalQueued = false
        evaluateActiveWindow()
    }

    private val pollRunnable = Runnable { evaluateActiveWindow() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        volumes.recoverStuck()
        updatePackageFilter()
        repository.register(preferenceListener)
        Log.i(TAG, "service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        scheduleEval()
    }

    override fun onInterrupt() {
        val settings = settingsFor(lastPackage)
        apply(engine.onTargetHidden(settings, volumes.current(), volumes.isMusicMuted()), null, null, null)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        val settings = settingsFor(lastPackage)
        apply(engine.onTargetHidden(settings, volumes.current(), volumes.isMusicMuted()), null, null, null)
        overlay.hide()
        repository.unregister(preferenceListener)
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun refreshSoon() {
        handler.removeCallbacks(evalRunnable)
        evalQueued = false
        handler.post(evalRunnable)
    }

    private fun scheduleEval() {
        if (evalQueued) return
        evalQueued = true
        val delay = if (engine.isOverlayShowing) 80L else 350L
        handler.postDelayed(evalRunnable, delay)
    }

    private fun evaluateActiveWindow() {
        updatePackageFilter()
        val currentVolume = volumes.current()
        val streamMuted = volumes.isMusicMuted()
        if (!repository.masterEnabled) {
            lastPackage = null
            apply(engine.onTargetHidden(settingsFor(null), currentVolume, streamMuted), null, null, null)
            return
        }
        var root: AccessibilityNodeInfo? = null
        try {
            root = try {
                rootInActiveWindow
            } catch (error: Exception) {
                Log.w(TAG, "active window unavailable", error)
                null
            }
            if (root == null) {
                val pkg = lastPackage
                apply(
                    engine.onTree(
                        null,
                        pkg,
                        settingsFor(pkg),
                        System.currentTimeMillis(),
                        currentVolume,
                        streamMuted,
                    ),
                    null,
                    null,
                    pkg,
                )
                return
            }
            val pkg = root.packageName?.toString()
            if (pkg == null || pkg == packageName || pkg !in targetPackages()) {
                lastPackage = null
                apply(engine.onTargetHidden(settingsFor(pkg), currentVolume, streamMuted), null, null, pkg)
                return
            }
            lastPackage = pkg
            val tree = try {
                UiNodeMapper.map(root)
            } catch (error: Exception) {
                Log.w(TAG, "could not read the screen", error)
                return
            }
            val output = engine.onTree(
                tree,
                pkg,
                settingsFor(pkg),
                System.currentTimeMillis(),
                currentVolume,
                streamMuted,
            )
            apply(output, root, tree, pkg)
        } finally {
            @Suppress("DEPRECATION")
            try {
                root?.recycle()
            } catch (_: Exception) {
            }
        }
    }

    private fun apply(
        output: EngineOutput,
        clickRoot: AccessibilityNodeInfo?,
        tree: UiNode?,
        packageName: String?,
    ) {
        when (val command = output.volume) {
            is VolumeCommand.Mute -> volumes.applyMute(command.restoreLevel)
            is VolumeCommand.Reapply -> volumes.reapply(command.restoreLevel)
            is VolumeCommand.Restore -> volumes.restore(
                command.level,
                command.keepCurrentIndex,
                command.leaveMuted,
            )
            VolumeCommand.None -> Unit
        }
        if (tree != null && packageName != null && output.showOverlay) {
            reportTree = tree
            reportPackage = packageName
            if (output.detection?.hits?.isNotEmpty() == true) reportDetection = output.detection
        }
        if (output.showOverlay) {
            overlay.show(output.overlayStyle, output.opacity, output.title, output.detail)
        } else {
            overlay.hide()
        }
        if (output.adStarted) {
            repository.recordAd()
            Log.i(TAG, "ad started")
        }
        if (output.adEnded) Log.i(TAG, "ad ended")
        val click = output.click
        if (click != null && clickRoot != null && performClick(clickRoot, click)) {
            repository.recordSkip()
            Log.i(TAG, "skip pressed")
        }
        muteHeld = output.muteHeld
        if (tree != null && packageName != null) maybeDump(tree, packageName, output.detection, force = false)
        schedulePoll(output.showOverlay || output.muteHeld)
    }

    private fun onNotAnAd() {
        if (reportingNotAd) return
        reportingNotAd = true
        try {
            val pkg = reportPackage ?: lastPackage
            val tree = reportTree
            val detection = reportDetection
            val output = engine.markNotAnAd(
                settingsFor(pkg),
                volumes.current(),
                volumes.isMusicMuted(),
            )
            if (tree != null && pkg != null) maybeDump(tree, pkg, detection, force = true)
            repository.recordNotAd()
            apply(output, null, null, pkg)
            Toast.makeText(this, NotAdCopy.TOAST, Toast.LENGTH_SHORT).show()
        } finally {
            reportingNotAd = false
        }
    }

    private fun performClick(root: AccessibilityNodeInfo, click: PlannedClick): Boolean {
        val node = nodeAt(root, click.path)
        val clicked = if (node != null) {
            try {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } catch (error: Exception) {
                Log.w(TAG, "accessibility click failed", error)
                false
            } finally {
                if (node !== root) {
                    @Suppress("DEPRECATION")
                    try {
                        node.recycle()
                    } catch (_: Exception) {
                    }
                }
            }
        } else {
            false
        }
        if (clicked) return true
        val (width, height) = screenSize()
        if (!click.bounds.isTappable(width, height)) return false
        return dispatchTap(click.bounds)
    }

    private fun nodeAt(root: AccessibilityNodeInfo, path: List<Int>): AccessibilityNodeInfo? {
        var current = root
        for (index in path) {
            val child = try {
                current.getChild(index)
            } catch (_: Exception) {
                null
            }
            if (child == null) {
                if (current !== root) recycle(current)
                return null
            }
            if (current !== root) recycle(current)
            current = child
        }
        return current
    }

    private fun dispatchTap(bounds: Bounds): Boolean {
        val path = Path().apply {
            val x = bounds.centerX.toFloat()
            val y = bounds.centerY.toFloat()
            moveTo(x, y)
            lineTo(x + 1f, y + 1f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        return try {
            dispatchGesture(gesture, null, null)
        } catch (error: Exception) {
            Log.w(TAG, "tap failed", error)
            false
        }
    }

    private fun maybeDump(tree: UiNode, packageName: String, detection: Detection?, force: Boolean) {
        if (!ScreenLogPolicy.shouldWrite(repository.debugDump, force)) return
        if (!dumpThrottle.shouldDump(System.currentTimeMillis(), force = force)) return
        try {
            val notes = buildList {
                addAll(volumes.notes())
                if (force) add(NotAdCopy.REPORT_NOTE)
            }
            val text = NodeTreeFormatter.format(
                root = tree,
                packageName = packageName,
                capturedAt = Instant.now().toString(),
                detection = detection,
                notes = notes,
            )
            val file = DumpWriter(DebugFiles.dir(this)).write(text)
            if (repository.lastDumpPath != file.absolutePath) repository.lastDumpPath = file.absolutePath
        } catch (error: Exception) {
            Log.w(TAG, "could not write the screen log", error)
        }
    }

    private fun schedulePoll(keepWatching: Boolean) {
        handler.removeCallbacks(pollRunnable)
        if (!keepWatching) return
        val delay = if (muteHeld) {
            400L
        } else {
            val configured = try {
                ConfigFiles.store(this).current().hideTimeoutMs
            } catch (_: Exception) {
                800L
            }
            configured.coerceIn(400L, 5_000L)
        }
        handler.postDelayed(pollRunnable, delay)
    }

    private fun updatePackageFilter() {
        val packages = targetPackages().toTypedArray()
        if (packages.isEmpty()) return
        val info = serviceInfo ?: return
        if (info.packageNames?.toSet() == packages.toSet()) return
        info.packageNames = packages
        serviceInfo = info
    }

    private fun targetPackages(): Set<String> = try {
        ConfigFiles.store(this).current().apps.map { it.packageName }.toSet()
    } catch (error: Exception) {
        Log.w(TAG, "using the built-in package list", error)
        DEFAULT_PACKAGES
    }

    private fun settingsFor(packageName: String?): EngineSettings = EngineSettings(
        masterEnabled = repository.masterEnabled,
        packageEnabled = packageName != null && repository.isAppEnabled(packageName),
        muteDuringAds = repository.muteDuringAds,
        overlayStyle = repository.overlayStyle,
        opacity = repository.opacity,
    )

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = resources.displayMetrics
            metrics.widthPixels to metrics.heightPixels
        }
    }

    @Suppress("DEPRECATION")
    private fun recycle(node: AccessibilityNodeInfo) {
        try {
            node.recycle()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "AdSkip"
        private val DEFAULT_PACKAGES = setOf(
            "com.google.android.youtube",
            "com.amazon.avod.thirdpartyclient",
            "com.amazon.amazonvideo.livingroom",
        )

        @Volatile private var instance: AdSkipAccessibilityService? = null

        fun refresh() {
            instance?.refreshSoon()
        }
    }
}
