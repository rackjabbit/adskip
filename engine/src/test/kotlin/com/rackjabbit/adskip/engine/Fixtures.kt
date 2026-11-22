package com.rackjabbit.adskip.engine

import kotlinx.serialization.json.Json
import java.io.File

private val fixtureJson = Json { ignoreUnknownKeys = true }

fun shippedConfigText(): String {
    var dir = File(System.getProperty("user.dir")).absoluteFile
    repeat(6) {
        val candidate = File(dir, "app/src/main/assets/default_ad_config.json")
        if (candidate.exists()) return candidate.readText()
        dir = dir.parentFile ?: return@repeat
    }
    error("default_ad_config.json not found from ${System.getProperty("user.dir")}")
}

fun shippedConfig(): AdConfig {
    return when (val parsed = AdConfigLoader.parse(shippedConfigText())) {
        is ConfigParseResult.Ok -> parsed.config
        is ConfigParseResult.Error -> error(parsed.message)
    }
}

fun fixture(name: String): UiNode {
    val stream = checkNotNull(Fixtures::class.java.getResourceAsStream("/fixtures/$name.json")) {
        "Missing fixture $name"
    }
    return fixtureJson.decodeFromString(UiNode.serializer(), stream.bufferedReader().readText())
}

private object Fixtures
