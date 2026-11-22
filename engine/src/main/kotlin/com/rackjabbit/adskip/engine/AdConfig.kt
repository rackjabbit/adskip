package com.rackjabbit.adskip.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AdConfig(
    val version: Int = 1,
    val notes: String = "",
    val debounceMs: Long = 1200,
    val clearMisses: Int = 3,
    val hideTimeoutMs: Long = 800,
    val apps: List<AppProfile> = emptyList(),
)

@Serializable
data class AppProfile(
    val packageName: String,
    val label: String,
    val adSignals: List<SignalRule> = emptyList(),
    val skipSignals: List<SignalRule> = emptyList(),
    val skipNotReady: List<String> = emptyList(),
    val countdownViewIds: List<String> = emptyList(),
    val countdownPatterns: List<String> = emptyList(),
    val standaloneCountdown: String? = null,
    val ignoreStandaloneViewIdParts: List<String> = emptyList(),
)

@Serializable
data class SignalRule(
    val source: SignalSource,
    val match: MatchMode,
    val values: List<String> = emptyList(),
    val strength: SignalStrength = SignalStrength.STRONG,
)

@Serializable
enum class SignalSource {
    @SerialName("viewId")
    VIEW_ID,

    @SerialName("text")
    TEXT,

    @SerialName("contentDescription")
    CONTENT_DESCRIPTION,
}

@Serializable
enum class MatchMode {
    @SerialName("equals")
    EQUALS,

    @SerialName("prefix")
    PREFIX,

    @SerialName("contains")
    CONTAINS,

    @SerialName("suffix")
    SUFFIX,

    @SerialName("regex")
    REGEX,
}

@Serializable
enum class SignalStrength {
    @SerialName("strong")
    STRONG,

    @SerialName("weak")
    WEAK,
}
