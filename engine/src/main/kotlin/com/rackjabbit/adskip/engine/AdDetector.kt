package com.rackjabbit.adskip.engine

data class Detection(
    val adPlaying: Boolean,
    val label: String?,
    val countdownSeconds: Int?,
    val skip: SkipTarget?,
)

data class SkipTarget(
    val label: String,
    val nodePath: List<Int>,
    val clickablePath: List<Int>?,
    val bounds: Bounds,
    val ready: Boolean,
    val signature: String,
)

object AdDetector {
    const val PROXIMITY = 4
    const val MAX_COUNTDOWN_SECONDS = 240
    const val MAX_STANDALONE_SECONDS = 120
    const val MAX_TEXT_CHARS = 48

    private val regexCache = HashMap<String, Regex?>()

    fun detect(root: UiNode, app: AppProfile): Detection {
        val tree = root.withPaths()
        val nodes = tree.flatten()
        val index = nodes.associateBy { it.path }
        val notReady = app.skipNotReady.mapNotNull { compile(it) }
        val strong = nodes.filter { node ->
            app.adSignals.any { it.strength == SignalStrength.STRONG && it.matches(node) }
        }
        val weak = nodes.filter { node ->
            app.adSignals.any { it.strength == SignalStrength.WEAK && it.matches(node) }
        }
        val countdown = parseCountdown(nodes, app, strong, weak)
        val skips = nodes.mapNotNull { node ->
            if (app.skipSignals.none { it.matches(node) }) return@mapNotNull null
            toSkip(node, index, notReady)
        }
        val adPlaying = strong.isNotEmpty() ||
            countdown.labeled != null ||
            countdown.standaloneNearWeak ||
            skips.any { it.matchedVisible }
        val chosen = skips.firstOrNull { it.target.ready }?.target ?: skips.firstOrNull()?.target
        val label = bestLabel(strong) ?: countdown.labeled?.label ?: bestLabel(weak)
        val seconds = countdown.labeled?.seconds ?: countdown.standalone?.seconds
        return Detection(
            adPlaying = adPlaying,
            label = label,
            countdownSeconds = if (adPlaying) seconds else null,
            skip = if (adPlaying) chosen else null,
        )
    }

    private data class Hit(val seconds: Int, val label: String)

    private data class CountdownParse(
        val labeled: Hit?,
        val standalone: Hit?,
        val standaloneNearWeak: Boolean,
    )

    private fun parseCountdown(
        nodes: List<UiNode>,
        app: AppProfile,
        strong: List<UiNode>,
        weak: List<UiNode>,
    ): CountdownParse {
        val labeledPatterns = app.countdownPatterns.mapNotNull { compile(it) }
        val standalonePattern = app.standaloneCountdown?.let { compile(it) }
        val countdownIds = app.countdownViewIds.map { it.lowercase() }
        val labeled = nodes
            .sortedByDescending { countdownPriority(it, countdownIds) }
            .firstNotNullOfOrNull { matchLabeled(it, labeledPatterns) }
        var standalone: Hit? = null
        var nearWeak = false
        if (standalonePattern != null) {
            val signals = strong + weak
            for (node in nodes) {
                if (ignoredClock(node, app)) continue
                val hit = matchStandalone(node, standalonePattern) ?: continue
                val nearSignal = signals.any { other ->
                    pathDistance(other.path, node.path) in 1..PROXIMITY
                }
                val besideWeak = weak.any { other ->
                    pathDistance(other.path, node.path) in 1..PROXIMITY
                }
                if (besideWeak) nearWeak = true
                if (standalone == null && (nearSignal || besideWeak)) standalone = hit
            }
        }
        return CountdownParse(labeled, standalone, nearWeak)
    }

    private fun countdownPriority(node: UiNode, countdownIds: List<String>): Int {
        val entry = node.viewId?.let(::viewIdEntry)?.lowercase() ?: return 0
        return if (countdownIds.any { entry == it }) 1 else 0
    }

    private fun ignoredClock(node: UiNode, app: AppProfile): Boolean {
        val id = node.viewId?.lowercase() ?: return false
        return app.ignoreStandaloneViewIdParts.any { part ->
            part.isNotBlank() && id.contains(part.lowercase())
        }
    }

    private fun matchLabeled(node: UiNode, patterns: List<Regex>): Hit? {
        for (text in shortTexts(node)) {
            for (pattern in patterns) {
                val seconds = secondsFrom(pattern, text) ?: continue
                if (seconds !in 0..MAX_COUNTDOWN_SECONDS) continue
                return Hit(seconds, text)
            }
        }
        return null
    }

    private fun matchStandalone(node: UiNode, pattern: Regex): Hit? {
        for (text in shortTexts(node)) {
            val seconds = secondsFrom(pattern, text) ?: continue
            if (seconds !in 0..MAX_STANDALONE_SECONDS) continue
            return Hit(seconds, text)
        }
        return null
    }

    private fun secondsFrom(pattern: Regex, text: String): Int? {
        val match = pattern.find(text) ?: return null
        val groups = match.groupValues.drop(1).filter { it.isNotEmpty() }
        return when {
            groups.isEmpty() -> null
            groups.size == 1 -> groups[0].toIntOrNull()
            else -> {
                val minutes = groups[0].toIntOrNull() ?: return null
                val seconds = groups[1].toIntOrNull() ?: return null
                if (seconds > 59) null else minutes * 60 + seconds
            }
        }
    }

    private data class SkipCandidate(val target: SkipTarget, val matchedVisible: Boolean)

    private fun toSkip(node: UiNode, index: Map<List<Int>, UiNode>, notReady: List<Regex>): SkipCandidate {
        val clickable = findClickable(node, index)
        val blocked = matchesAny(node, notReady) || (clickable != null && matchesAny(clickable, notReady))
        val ready = clickable != null && !blocked
        val bounds = when {
            clickable != null && !clickable.bounds.isEmpty -> clickable.bounds
            else -> node.bounds
        }
        val label = node.text?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.takeIf { it.isNotBlank() }
            ?: "Skip"
        val target = SkipTarget(
            label = label,
            nodePath = node.path,
            clickablePath = clickable?.path,
            bounds = bounds,
            ready = ready,
            signature = listOf(node.viewId.orEmpty(), node.text.orEmpty(), node.contentDescription.orEmpty())
                .joinToString("|"),
        )
        return SkipCandidate(target, node.visible || clickable?.visible == true)
    }

    private fun findClickable(node: UiNode, index: Map<List<Int>, UiNode>): UiNode? {
        var path: List<Int>? = node.path
        while (path != null) {
            val current = index[path]
            if (current != null && current.clickable && current.enabled && current.visible) return current
            path = if (path.isEmpty()) null else path.dropLast(1)
        }
        return null
    }

    private fun matchesAny(node: UiNode, patterns: List<Regex>): Boolean {
        val fields = listOfNotNull(node.text, node.contentDescription).map { it.trim() }
        return fields.any { text -> patterns.any { it.containsMatchIn(text) } }
    }

    private fun bestLabel(nodes: List<UiNode>): String? =
        nodes.firstNotNullOfOrNull { shortTexts(it).firstOrNull() }

    private fun shortTexts(node: UiNode): List<String> =
        listOfNotNull(node.text, node.contentDescription)
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= MAX_TEXT_CHARS }

    private fun compile(pattern: String): Regex? = regexCache.getOrPut(pattern) {
        try {
            Regex(pattern, setOf(RegexOption.IGNORE_CASE))
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

fun SignalRule.matches(node: UiNode): Boolean {
    val raw = when (source) {
        SignalSource.VIEW_ID -> node.viewId
        SignalSource.TEXT -> node.text
        SignalSource.CONTENT_DESCRIPTION -> node.contentDescription
    }?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    return values.any { candidate ->
        val value = candidate.trim()
        if (value.isEmpty()) return@any false
        when (match) {
            MatchMode.EQUALS -> raw.equals(value, ignoreCase = true)
            MatchMode.PREFIX -> raw.startsWith(value, ignoreCase = true)
            MatchMode.CONTAINS -> raw.contains(value, ignoreCase = true)
            MatchMode.SUFFIX -> raw.equals(value, ignoreCase = true) ||
                viewIdEntry(raw).equals(value, ignoreCase = true) ||
                raw.endsWith(value, ignoreCase = true)
            MatchMode.REGEX -> regexMatches(value, raw)
        }
    }
}

private fun regexMatches(pattern: String, raw: String): Boolean = try {
    Regex(pattern, setOf(RegexOption.IGNORE_CASE)).containsMatchIn(raw)
} catch (_: IllegalArgumentException) {
    false
}
