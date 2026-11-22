package com.rackjabbit.adskip.engine

import java.io.File

class ConfigStore(
    private val file: File,
    private val defaultText: String,
) {
    private var cache: AdConfig? = null
    private var cachedStamp: Long = Long.MIN_VALUE
    var lastError: String? = null
        private set

    @Synchronized
    fun ensure() {
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText(defaultText)
            cachedStamp = Long.MIN_VALUE
        }
    }

    @Synchronized
    fun rawText(): String {
        ensure()
        return file.readText()
    }

    @Synchronized
    fun current(): AdConfig {
        ensure()
        val stamp = file.lastModified()
        val cached = cache
        if (cached != null && stamp == cachedStamp && lastError == null) return cached
        return when (val parsed = AdConfigLoader.parse(file.readText())) {
            is ConfigParseResult.Ok -> {
                cache = parsed.config
                cachedStamp = file.lastModified()
                lastError = null
                parsed.config
            }
            is ConfigParseResult.Error -> {
                lastError = parsed.message
                cached ?: fallback()
            }
        }
    }

    @Synchronized
    fun replace(text: String): ConfigParseResult {
        return when (val parsed = AdConfigLoader.parse(text)) {
            is ConfigParseResult.Ok -> {
                file.parentFile?.mkdirs()
                file.writeText(AdConfigLoader.pretty(parsed.config))
                cache = parsed.config
                cachedStamp = file.lastModified()
                lastError = null
                parsed
            }
            is ConfigParseResult.Error -> parsed
        }
    }

    @Synchronized
    fun reset(): AdConfig {
        file.parentFile?.mkdirs()
        file.writeText(defaultText)
        cache = null
        cachedStamp = Long.MIN_VALUE
        lastError = null
        return current()
    }

    private fun fallback(): AdConfig {
        return when (val parsed = AdConfigLoader.parse(defaultText)) {
            is ConfigParseResult.Ok -> parsed.config.also { cache = it }
            is ConfigParseResult.Error -> error("Built-in detection config is invalid: ${parsed.message}")
        }
    }
}
