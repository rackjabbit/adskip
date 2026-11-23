package com.rackjabbit.adskip.engine

data class Detection(
    val adPlaying: Boolean,
    val label: String?,
    val countdownSeconds: Int?,
    val skip: SkipTarget?,
    val hits: List<SignalHit> = emptyList(),
)

data class SignalHit(
    val role: String,
    val source: String,
    val match: String,
    val value: String,
    val strength: String,
    val viewId: String?,
    val text: String?,
    val contentDescription: String?,
    val path: List<Int>,
    val visible: Boolean,
    val bounds: Bounds,
) {
    /** Stable across small tree shifts. Path is recorded on the hit, not in this key. */
    fun signature(): String = listOf(
        role,
        source,
        match,
        value,
        viewId?.let(::viewIdEntry).orEmpty(),
        text?.trim().orEmpty(),
    ).joinToString("|")
}

fun Detection.matchSignature(): String = hits.map { it.signature() }.toSet().sorted().joinToString("\n")

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
    private val thumbnailBadgeIds = setOf("ad_badge", "ad_badge_icon")
    private val contentContextParts = listOf(
        "title", "description", "metadata", "caption", "channel",
        "comment", "owner", "headline", "author",
    )
    private val builtinClockParts = listOf(
        "time_bar", "duration", "seekbar", "seek_bar", "player_time", "current_time",
        "watch_time", "thumbnail", "metadata", "timestamp", "length", "chapter",
    )

    fun detect(root: UiNode, app: AppProfile): Detection {
        val tree = root.withPaths()
        val all = tree.flatten()
        val shown = all.filter { it.shownToUser() }
        val index = all.associateBy { it.path }
        val notReady = app.skipNotReady.mapNotNull { compile(it) }

        val strong = mutableListOf<SignalHit>()
        val weak = mutableListOf<SignalHit>()
        for (node in shown) {
            for (rule in app.adSignals) {
                val value = rule.matchedValue(node) ?: continue
                val strength = effectiveStrength(rule, node)
                if (strength == SignalStrength.STRONG && !overlayHasAdText(rule, node, app)) continue
                val hit = signalHit("ad", rule, value, node, strength)
                if (strength == SignalStrength.STRONG) strong += hit else weak += hit
            }
        }

        val countdown = parseCountdown(shown, app, strong, weak)
        val skips = shown.mapNotNull { node ->
            val rule = app.skipSignals.firstOrNull { skipRuleCounts(it, node) } ?: return@mapNotNull null
            val value = rule.matchedValue(node) ?: return@mapNotNull null
            toSkip(node, index, notReady, signalHit("skip", rule, value, node, SignalStrength.STRONG))
        }

        val labeled = countdown.labeled
        val standaloneTriggered = countdown.standaloneNearWeak
        val adPlaying = strong.isNotEmpty() ||
            labeled != null ||
            standaloneTriggered ||
            skips.isNotEmpty()
        val hits = buildList {
            if (strong.isNotEmpty()) addAll(strong)
            if (labeled != null) add(labeled.hit)
            if (standaloneTriggered) {
                addAll(countdown.weakBeside)
                countdown.standalone?.hit?.let { add(it) }
            }
            if (adPlaying) addAll(skips.map { it.hit })
        }.distinctBy { it.signature() + "|" + it.path.joinToString("/") }
        val chosen = skips.firstOrNull { it.target.ready }?.target ?: skips.firstOrNull()?.target
        val label = textOf(strong) ?: labeled?.label ?: textOf(if (standaloneTriggered) countdown.weakBeside else weak)
        val seconds = labeled?.seconds ?: countdown.standalone?.seconds
        return Detection(
            adPlaying = adPlaying,
            label = label,
            countdownSeconds = if (adPlaying) seconds else null,
            skip = if (adPlaying) chosen else null,
            hits = if (adPlaying) hits else emptyList(),
        )
    }

    private data class Timed(val seconds: Int, val label: String, val hit: SignalHit)

    private data class CountdownParse(
        val labeled: Timed?,
        val standalone: Timed?,
        val standaloneNearWeak: Boolean,
        val weakBeside: List<SignalHit>,
    )

    private fun parseCountdown(
        nodes: List<UiNode>,
        app: AppProfile,
        strong: List<SignalHit>,
        weak: List<SignalHit>,
    ): CountdownParse {
        val labeledPatterns = app.countdownPatterns.mapNotNull { compile(it) }
        val standalonePattern = app.standaloneCountdown?.let { compile(it) }
        val countdownIds = app.countdownViewIds.map { it.lowercase() }
        val labeled = nodes
            .sortedByDescending { countdownPriority(it, countdownIds) }
            .firstNotNullOfOrNull { matchLabeled(it, labeledPatterns, countdownIds) }
        var standalone: Timed? = null
        var nearWeak = false
        val weakBeside = mutableListOf<SignalHit>()
        if (standalonePattern != null) {
            val strongNodes = strong.map { it.path }
            for (node in nodes) {
                if (ignoredClock(node, app)) continue
                val hit = matchStandalone(node, standalonePattern) ?: continue
                val nearSignal = strongNodes.any { other -> pathDistance(other, node.path) in 1..PROXIMITY }
                val beside = weak.filter { other ->
                    weakCanAnchorCountdown(other) && pathDistance(other.path, node.path) in 1..PROXIMITY
                }
                if (beside.isNotEmpty()) {
                    nearWeak = true
                    if (weakBeside.isEmpty()) weakBeside += beside
                }
                if (standalone == null && (nearSignal || beside.isNotEmpty())) standalone = hit
            }
        }
        return CountdownParse(labeled, standalone, nearWeak, weakBeside)
    }

    /**
     * Feed badges sit beside a video's duration. That pair is not a player ad.
     * Prime's player badge is the word Ad with no thumbnail view id.
     */
    private fun weakCanAnchorCountdown(hit: SignalHit): Boolean {
        val entry = hit.viewId?.let(::viewIdEntry)?.lowercase().orEmpty()
        if (entry in thumbnailBadgeIds) return false
        val text = hit.text?.trim().orEmpty()
        val desc = hit.contentDescription?.trim().orEmpty()
        return text.equals("Ad", ignoreCase = true) || desc.equals("Ad", ignoreCase = true)
    }

    private fun countdownPriority(node: UiNode, countdownIds: List<String>): Int {
        val entry = node.viewId?.let(::viewIdEntry)?.lowercase() ?: return 0
        return if (countdownIds.any { entry == it }) 1 else 0
    }

    private fun ignoredClock(node: UiNode, app: AppProfile): Boolean {
        val id = node.viewId?.lowercase() ?: return false
        return (app.ignoreStandaloneViewIdParts + builtinClockParts).any { part ->
            part.isNotBlank() && id.contains(part.lowercase())
        }
    }

    private fun matchLabeled(node: UiNode, patterns: List<Regex>, countdownIds: List<String>): Timed? {
        if (contentContext(node) && countdownPriority(node, countdownIds) == 0) return null
        val idBacked = countdownPriority(node, countdownIds) > 0
        for (text in shortTexts(node)) {
            for (pattern in patterns) {
                val match = pattern.find(text) ?: continue
                val covers = match.range.first == 0 && match.range.last == text.lastIndex
                if (!idBacked && !covers) continue
                val seconds = secondsOf(match) ?: continue
                if (seconds !in 0..MAX_COUNTDOWN_SECONDS) continue
                return Timed(seconds, text, countdownHit(node, pattern.pattern, text))
            }
        }
        return null
    }

    private fun matchStandalone(node: UiNode, pattern: Regex): Timed? {
        for (text in shortTexts(node)) {
            val match = pattern.find(text) ?: continue
            val covers = match.range.first == 0 && match.range.last == text.lastIndex
            if (!covers) continue
            val seconds = secondsOf(match) ?: continue
            if (seconds !in 0..MAX_STANDALONE_SECONDS) continue
            return Timed(seconds, text, countdownHit(node, pattern.pattern, text))
        }
        return null
    }

    private fun secondsOf(match: MatchResult): Int? {
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

    private data class SkipCandidate(val target: SkipTarget, val hit: SignalHit)

    private fun toSkip(
        node: UiNode,
        index: Map<List<Int>, UiNode>,
        notReady: List<Regex>,
        hit: SignalHit,
    ): SkipCandidate {
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
        return SkipCandidate(target, hit)
    }

    private fun findClickable(node: UiNode, index: Map<List<Int>, UiNode>): UiNode? {
        var path: List<Int>? = node.path
        while (path != null) {
            val current = index[path]
            if (current != null && current.clickable && current.enabled && current.shownToUser()) return current
            path = if (path.isEmpty()) null else path.dropLast(1)
        }
        return null
    }

    private fun matchesAny(node: UiNode, patterns: List<Regex>): Boolean {
        val fields = listOfNotNull(node.text, node.contentDescription).map { it.trim() }
        return fields.any { text -> patterns.any { it.containsMatchIn(text) } }
    }

    private fun textOf(hits: List<SignalHit>): String? =
        hits.firstNotNullOfOrNull { hit ->
            listOfNotNull(hit.text, hit.contentDescription)
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && it.length <= MAX_TEXT_CHARS }
        }

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

    private fun effectiveStrength(rule: SignalRule, node: UiNode): SignalStrength {
        if (rule.source == SignalSource.VIEW_ID && thumbnailBadge(node)) return SignalStrength.WEAK
        return rule.strength
    }

    private fun thumbnailBadge(node: UiNode): Boolean =
        viewIdEntry(node.viewId.orEmpty()).lowercase() in thumbnailBadgeIds

    /**
     * `ad_overlay` stays in the player tree during a normal video. The id alone
     * is not an ad unless a real ad phrase is on screen inside it.
     */
    private fun overlayHasAdText(rule: SignalRule, node: UiNode, app: AppProfile): Boolean {
        if (rule.source != SignalSource.VIEW_ID) return true
        if (viewIdEntry(node.viewId.orEmpty()).lowercase() != "ad_overlay") return true
        return node.flatten().any { child ->
            child.shownToUser() && app.adSignals.any { other ->
                other.source != SignalSource.VIEW_ID &&
                    other.strength == SignalStrength.STRONG &&
                    other.matchedValue(child) != null
            }
        }
    }

    private fun skipRuleCounts(rule: SignalRule, node: UiNode): Boolean {
        if (rule.matchedValue(node) == null) return false
        if (rule.source != SignalSource.VIEW_ID) return true
        if (viewIdEntry(node.viewId.orEmpty()).lowercase() != "skip_button") return true
        return looksLikeSkip(node.text) || looksLikeSkip(node.contentDescription)
    }

    private fun looksLikeSkip(value: String?): Boolean {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return false
        return Regex("^Skip(?:\\s+ads?)?(?:\\s+button)?\\s*[>›»]?$", RegexOption.IGNORE_CASE).matches(text)
    }

    private fun contentContext(node: UiNode): Boolean {
        val id = node.viewId?.lowercase() ?: return false
        return contentContextParts.any { part -> id.contains(part) }
    }

    private fun signalHit(
        role: String,
        rule: SignalRule,
        value: String,
        node: UiNode,
        strength: SignalStrength,
    ): SignalHit = SignalHit(
        role = role,
        source = sourceName(rule.source),
        match = matchName(rule.match),
        value = value,
        strength = strengthName(strength),
        viewId = node.viewId,
        text = node.text,
        contentDescription = node.contentDescription,
        path = node.path,
        visible = node.visible,
        bounds = node.bounds,
    )

    private fun countdownHit(node: UiNode, pattern: String, label: String): SignalHit = SignalHit(
        role = "countdown",
        source = "text",
        match = "regex",
        value = pattern,
        strength = "strong",
        viewId = node.viewId,
        text = label,
        contentDescription = node.contentDescription,
        path = node.path,
        visible = node.visible,
        bounds = node.bounds,
    )

    private fun sourceName(source: SignalSource): String = when (source) {
        SignalSource.VIEW_ID -> "viewId"
        SignalSource.TEXT -> "text"
        SignalSource.CONTENT_DESCRIPTION -> "contentDescription"
    }

    private fun matchName(mode: MatchMode): String = when (mode) {
        MatchMode.EQUALS -> "equals"
        MatchMode.PREFIX -> "prefix"
        MatchMode.CONTAINS -> "contains"
        MatchMode.SUFFIX -> "suffix"
        MatchMode.REGEX -> "regex"
    }

    private fun strengthName(strength: SignalStrength): String = when (strength) {
        SignalStrength.STRONG -> "strong"
        SignalStrength.WEAK -> "weak"
    }
}

fun SignalRule.matches(node: UiNode): Boolean = matchedValue(node) != null

internal fun SignalRule.matchedValue(node: UiNode): String? {
    if (!node.shownToUser()) return null
    if (strength == SignalStrength.WEAK && contentContextNode(node)) return null
    val raw = when (source) {
        SignalSource.VIEW_ID -> node.viewId
        SignalSource.TEXT -> node.text
        SignalSource.CONTENT_DESCRIPTION -> node.contentDescription
    }?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return values.firstOrNull { candidate ->
        val value = candidate.trim()
        value.isNotEmpty() && valueMatches(match, value, raw)
    }
}

private fun contentContextNode(node: UiNode): Boolean {
    val id = node.viewId?.lowercase() ?: return false
    return listOf(
        "title", "description", "metadata", "caption", "channel",
        "comment", "owner", "headline", "author",
    ).any { id.contains(it) }
}

private fun valueMatches(mode: MatchMode, value: String, raw: String): Boolean = when (mode) {
    MatchMode.EQUALS -> raw.equals(value, ignoreCase = true)
    MatchMode.PREFIX -> raw.startsWith(value, ignoreCase = true)
    MatchMode.CONTAINS -> raw.contains(value, ignoreCase = true)
    MatchMode.SUFFIX -> raw.equals(value, ignoreCase = true) ||
        viewIdEntry(raw).equals(value, ignoreCase = true) ||
        raw.endsWith(value, ignoreCase = true)
    MatchMode.REGEX -> regexMatches(value, raw)
}

private fun regexMatches(pattern: String, raw: String): Boolean = try {
    Regex(pattern, setOf(RegexOption.IGNORE_CASE)).containsMatchIn(raw)
} catch (_: IllegalArgumentException) {
    false
}
