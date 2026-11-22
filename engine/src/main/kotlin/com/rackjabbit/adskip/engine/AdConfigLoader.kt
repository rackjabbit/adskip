package com.rackjabbit.adskip.engine

import kotlinx.serialization.json.Json
import java.util.regex.Pattern

sealed class ConfigParseResult {
    data class Ok(val config: AdConfig) : ConfigParseResult()
    data class Error(val message: String) : ConfigParseResult()
}

object AdConfigLoader {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val packageName = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    fun parse(text: String): ConfigParseResult {
        if (text.length > 1_000_000) {
            return ConfigParseResult.Error("Config is larger than 1 MB.")
        }
        val decoded = try {
            json.decodeFromString(AdConfig.serializer(), text)
        } catch (error: Exception) {
            return ConfigParseResult.Error(error.message ?: "Could not read this JSON.")
        }
        return validate(decoded)
    }

    fun pretty(config: AdConfig): String = json.encodeToString(AdConfig.serializer(), config)

    private fun validate(config: AdConfig): ConfigParseResult {
        if (config.version != 1) {
            return ConfigParseResult.Error("Unsupported config version ${config.version}. This app reads version 1.")
        }
        if (config.debounceMs !in 300L..10_000L) {
            return ConfigParseResult.Error("debounceMs must be between 300 and 10000.")
        }
        if (config.clearMisses !in 1..8) {
            return ConfigParseResult.Error("clearMisses must be between 1 and 8.")
        }
        if (config.hideTimeoutMs !in 400L..10_000L) {
            return ConfigParseResult.Error("hideTimeoutMs must be between 400 and 10000.")
        }
        if (config.apps.isEmpty()) {
            return ConfigParseResult.Error("Add at least one app.")
        }
        val seen = HashSet<String>()
        config.apps.forEachIndexed { index, app ->
            validateApp(index, app)?.let { return it }
            if (!seen.add(app.packageName)) {
                return ConfigParseResult.Error("Package ${app.packageName} is listed more than once.")
            }
        }
        return ConfigParseResult.Ok(config)
    }

    private fun validateApp(index: Int, app: AppProfile): ConfigParseResult.Error? {
        val where = "apps[$index]"
        if (!packageName.matches(app.packageName)) {
            return ConfigParseResult.Error("$where packageName '${app.packageName}' is not a package name.")
        }
        if (app.label.isBlank()) {
            return ConfigParseResult.Error("$where needs a label.")
        }
        if (app.adSignals.isEmpty() && app.skipSignals.isEmpty()) {
            return ConfigParseResult.Error("$where needs adSignals or skipSignals.")
        }
        app.adSignals.forEachIndexed { signalIndex, rule ->
            validateRule("$where.adSignals[$signalIndex]", rule)?.let { return it }
        }
        app.skipSignals.forEachIndexed { signalIndex, rule ->
            validateRule("$where.skipSignals[$signalIndex]", rule)?.let { return it }
        }
        app.skipNotReady.forEachIndexed { patternIndex, pattern ->
            compile(pattern)?.let { return ConfigParseResult.Error("$where.skipNotReady[$patternIndex] is not a valid pattern: $it") }
        }
        app.countdownPatterns.forEachIndexed { patternIndex, pattern ->
            validateGrouped("$where.countdownPatterns[$patternIndex]", pattern)?.let { return it }
        }
        app.standaloneCountdown?.let { pattern ->
            validateGrouped("$where.standaloneCountdown", pattern)?.let { return it }
        }
        return null
    }

    private fun validateRule(where: String, rule: SignalRule): ConfigParseResult.Error? {
        if (rule.values.isEmpty() || rule.values.all { it.isBlank() }) {
            return ConfigParseResult.Error("$where needs at least one value.")
        }
        if (rule.match == MatchMode.REGEX) {
            rule.values.forEachIndexed { valueIndex, pattern ->
                compile(pattern)?.let {
                    return ConfigParseResult.Error("$where.values[$valueIndex] is not a valid pattern: $it")
                }
            }
        }
        return null
    }

    private fun validateGrouped(where: String, pattern: String): ConfigParseResult.Error? {
        val compiled = try {
            Pattern.compile(pattern, Pattern.CASE_INSENSITIVE)
        } catch (error: Exception) {
            return ConfigParseResult.Error("$where is not a valid pattern: ${error.message}")
        }
        if (compiled.matcher("").groupCount() < 1) {
            return ConfigParseResult.Error("$where must capture the countdown with parentheses.")
        }
        return null
    }

    private fun compile(pattern: String): String? = try {
        Pattern.compile(pattern, Pattern.CASE_INSENSITIVE)
        null
    } catch (error: Exception) {
        error.message ?: "invalid pattern"
    }
}
