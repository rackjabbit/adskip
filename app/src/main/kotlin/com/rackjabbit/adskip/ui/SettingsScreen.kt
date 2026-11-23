package com.rackjabbit.adskip.ui

import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.rememberSliderState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rackjabbit.adskip.BuildConfig
import com.rackjabbit.adskip.DeviceActions
import com.rackjabbit.adskip.data.ConfigFiles
import com.rackjabbit.adskip.data.SettingsRepository
import com.rackjabbit.adskip.debug.DebugFiles
import com.rackjabbit.adskip.engine.ConfigParseResult
import com.rackjabbit.adskip.engine.NotAdCopy
import com.rackjabbit.adskip.engine.OverlayStyle
import com.rackjabbit.adskip.service.AdSkipAccessibilityService
import java.io.File
import kotlin.math.roundToInt

private enum class Screen { Home, Editor }

@Composable
fun SettingsApp(repository: SettingsRepository) {
    var screen by remember { mutableStateOf(Screen.Home) }
    when (screen) {
        Screen.Home -> HomeScreen(repository, onEdit = { screen = Screen.Editor })
        Screen.Editor -> EditorScreen(onBack = { screen = Screen.Home })
    }
}

@Composable
private fun HomeScreen(repository: SettingsRepository, onEdit: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ConfigFiles.store(context) }
    var prefsTick by remember { mutableIntStateOf(0) }
    var revision by remember { mutableIntStateOf(0) }
    var serviceOn by remember { mutableStateOf(DeviceActions.isServiceEnabled(context)) }
    var notice by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var confirmRestore by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(repository) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> prefsTick++ }
        repository.register(listener)
        onDispose { repository.unregister(listener) }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) serviceOn = DeviceActions.isServiceEnabled(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val config = remember(revision) { store.current() }
    val configError = remember(revision) { store.lastError }
    val master = remember(prefsTick) { repository.masterEnabled }
    val mute = remember(prefsTick) { repository.muteDuringAds }
    val debug = remember(prefsTick) { repository.debugDump }
    val style = remember(prefsTick) { repository.overlayStyle }
    val opacity = remember(prefsTick) { repository.opacity }
    val adsSeen = remember(prefsTick) { repository.adsSeen }
    val skips = remember(prefsTick) { repository.skips }
    val markedNotAd = remember(prefsTick) { repository.markedNotAd }
    val latestLog = remember(prefsTick) { DebugFiles.latest(context) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = try {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (_: Exception) {
            null
        }
        if (text == null) {
            notice = null
            problem = "Couldn't read that file."
            return@rememberLauncherForActivityResult
        }
        when (val result = store.replace(text)) {
            is ConfigParseResult.Ok -> {
                problem = null
                notice = "Detection rules updated."
                revision++
                AdSkipAccessibilityService.refresh()
            }
            is ConfigParseResult.Error -> {
                notice = null
                problem = result.message
            }
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text("Ad Skip", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Covers a video ad, and presses Skip once that button can be pressed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
                )
                if (!serviceOn) {
                    OnboardingCard()
                } else {
                    StatusLine("The accessibility service is on.")
                }

                Section(title = "Playback") {
                    ToggleRow(
                        title = "Run while videos play",
                        subtitle = "Turns detection, the overlay, and Skip presses on or off. The accessibility service stays enabled.",
                        checked = master,
                        onChecked = { repository.masterEnabled = it },
                    )
                    config.apps.forEach { app ->
                        val installed = DeviceActions.isInstalled(context, app.packageName)
                        ToggleRow(
                            title = app.label,
                            subtitle = buildString {
                                append(app.packageName)
                                append(if (installed) " · installed" else " · not on this phone")
                            },
                            checked = repository.isAppEnabled(app.packageName),
                            onChecked = { repository.setAppEnabled(app.packageName, it) },
                        )
                    }
                }

                Section(title = "While an ad is on screen") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        StyleButton("Banner", style == OverlayStyle.BANNER, Modifier.weight(1f)) {
                            repository.overlayStyle = OverlayStyle.BANNER
                        }
                        StyleButton("Full cover", style == OverlayStyle.COVER, Modifier.weight(1f)) {
                            repository.overlayStyle = OverlayStyle.COVER
                        }
                    }
                    val sliderState = rememberSliderState(
                        value = opacity,
                        trackRange = SettingsRepository.MIN_OPACITY..SettingsRepository.MAX_OPACITY,
                    )
                    Text(
                        "Opacity ${(sliderState.value * 100).roundToInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                    Slider(
                        state = sliderState,
                        onValueChange = { repository.opacity = it },
                    )
                    OverlayPreview(style, opacity)
                    Text(
                        "${NotAdCopy.BUTTON} hides the cover, puts the volume back, and saves a screen log you can share.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                    )
                    ToggleRow(
                        title = "Mute media volume",
                        subtitle = "Mutes media audio for the ad, including back-to-back ads, then puts it back. Alarms and calls stay as they are.",
                        checked = mute,
                        onChecked = { repository.muteDuringAds = it },
                    )
                }

                Section(title = "What it has done") {
                    StatRow("Ads noticed", adsSeen)
                    StatRow("Skips pressed", skips)
                    StatRow(NotAdCopy.STAT, markedNotAd)
                    TextButton(onClick = { repository.resetStats() }) { Text("Reset counts") }
                }

                Section(title = "Detection rules") {
                    Text(
                        "Packages, view ids, and the English phrases for Skip and ad badges live in a JSON file on this phone. When an app moves a button, edit that file. Nothing is uploaded.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (configError != null) {
                        Text(
                            configError,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    notice?.let {
                        Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                    }
                    problem?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { Text("Edit JSON") }
                    OutlinedButton(
                        onClick = { importer.launch(arrayOf("application/json", "text/plain", "*/*")) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) { Text("Import JSON") }
                    OutlinedButton(
                        onClick = {
                            val file = File(context.filesDir, "ad_config.json")
                            if (file.exists()) DeviceActions.share(context, file, "Ad Skip detection rules")
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) { Text("Share JSON") }
                    TextButton(
                        onClick = { confirmRestore = true },
                        modifier = Modifier.padding(top = 4.dp),
                    ) { Text("Restore built-in rules") }
                }

                Section(title = "Screen logs") {
                    Text(
                        "While this is on, Ad Skip writes the accessibility tree of YouTube or Prime Video to a text file every few seconds. Open the app whose layout changed, let an ad appear, then come back and share the log.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    ToggleRow(
                        title = "Write screen logs",
                        subtitle = "Logs stay on this phone until you share or delete them.",
                        checked = debug,
                        onChecked = { repository.debugDump = it },
                    )
                    Button(
                        onClick = { latestLog?.let { DeviceActions.share(context, it, "Ad Skip screen log") } },
                        enabled = latestLog != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (latestLog == null) "No log yet" else "Share latest log") }
                }

                Text(
                    "Version ${BuildConfig.VERSION_NAME}. This build has no internet permission and does not collect data.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 22.dp, bottom = 28.dp),
                )
            }
        }
    }

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("Restore built-in rules?") },
            text = { Text("This replaces the JSON on this phone. Edits you have saved will be discarded.") },
            confirmButton = {
                TextButton(onClick = {
                    store.reset()
                    confirmRestore = false
                    problem = null
                    notice = "Built-in rules restored."
                    revision++
                    AdSkipAccessibilityService.refresh()
                }) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestore = false }) { Text("Keep mine") }
            },
        )
    }
}

@Composable
private fun EditorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ConfigFiles.store(context) }
    var text by remember { mutableStateOf(store.rawText()) }
    var dirty by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }

    fun close() {
        if (dirty) confirmDiscard = true else onBack()
    }

    BackHandler { close() }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { close() }) { Text("Back") }
                Text("Detection JSON", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    dirty = true
                    error = null
                },
                modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                isError = error != null,
            )
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        when (val result = com.rackjabbit.adskip.engine.AdConfigLoader.parse(text)) {
                            is ConfigParseResult.Ok -> error = "Looks valid. ${result.config.apps.size} apps."
                            is ConfigParseResult.Error -> error = result.message
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Check") }
                Button(
                    onClick = {
                        when (val result = store.replace(text)) {
                            is ConfigParseResult.Ok -> {
                                dirty = false
                                AdSkipAccessibilityService.refresh()
                                onBack()
                            }
                            is ConfigParseResult.Error -> error = result.message
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Save") }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Leave without saving?") },
            text = { Text("The JSON on this phone stays as it was.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onBack()
                }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") }
            },
        )
    }
}

@Composable
private fun OnboardingCard() {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
    ) {
        Text("Turn on Ad Skip", style = MaterialTheme.typography.titleLarge)
        Text(
            "Ad Skip reads the screen inside YouTube and Prime Video only. It can cover the ad, mute media volume, and press Skip when that control is clickable.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            "On Android 13 and newer, a sideloaded app cannot turn on an accessibility service until you allow restricted settings.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Step(1, "Open App info. Tap the three-dot menu and choose Allow restricted settings. Confirm with your PIN, pattern, or fingerprint.")
        Step(2, "Open Accessibility settings, find Ad Skip, and turn it on. On some phones it is listed under Installed apps.")
        Step(3, "Come back here and leave Run while videos play switched on.")
        Button(
            onClick = { DeviceActions.openAppDetails(context) },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) { Text("Open app info") }
        OutlinedButton(
            onClick = { DeviceActions.openAccessibilitySettings(context) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) { Text("Open accessibility settings") }
    }
}

@Composable
private fun Step(number: Int, text: String) {
    Text(
        "$number. $text",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun StatusLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp),
        ) { content() }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChecked)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun StyleButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
    }
}

@Composable
private fun OverlayPreview(style: OverlayStyle, opacity: Float) {
    val cover = style == OverlayStyle.COVER
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 8.dp)
            .height(if (cover) 160.dp else 84.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF101410).copy(alpha = if (cover) opacity else 1f)),
    ) {
        Column(
            Modifier
                .align(if (cover) Alignment.Center else Alignment.CenterStart)
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF141814).copy(alpha = if (cover) 0.92f else opacity))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text("Ad playing", color = Color(0xFFF4F1EA), fontWeight = FontWeight.Bold)
            Text("Ad 1 of 2 · 0:05", color = Color(0xFFE2B657), fontSize = 14.sp)
            Text(
                NotAdCopy.BUTTON,
                color = Color(0xFF1A1408),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFE2B657))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun StatRow(label: String, value: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value.toString(), style = MaterialTheme.typography.titleMedium)
    }
}
