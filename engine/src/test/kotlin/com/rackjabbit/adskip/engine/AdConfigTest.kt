package com.rackjabbit.adskip.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AdConfigTest {
    @Test
    fun shippedConfig_listsOnlyTheTargetApps() {
        val config = shippedConfig()
        assertEquals(
            listOf(
                "com.google.android.youtube",
                "com.amazon.avod.thirdpartyclient",
                "com.amazon.amazonvideo.livingroom",
            ),
            config.apps.map { it.packageName },
        )
        assertEquals(1200, config.debounceMs)
    }

    @Test
    fun rejectsBadPackage_badRegex_andEmptyApps() {
        val badPackage = """{"version":1,"apps":[{"packageName":"youtube","label":"YouTube","skipSignals":[{"source":"text","match":"equals","values":["Skip"]}]}]}"""
        val packageResult = AdConfigLoader.parse(badPackage)
        assertTrue(packageResult is ConfigParseResult.Error)

        val badRegex = """{"version":1,"apps":[{"packageName":"com.google.android.youtube","label":"YouTube","skipSignals":[{"source":"text","match":"regex","values":["["]}]}]}"""
        val regexResult = AdConfigLoader.parse(badRegex)
        assertTrue((regexResult as ConfigParseResult.Error).message.contains("not a valid pattern"))

        val empty = """{"version":1,"apps":[]}"""
        assertTrue(AdConfigLoader.parse(empty) is ConfigParseResult.Error)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val text = """
            {
              "version": 1,
              "future": true,
              "apps": [{
                "packageName": "com.example.player",
                "label": "Example",
                "note": "ignored",
                "adSignals": [{"source":"text","match":"equals","values":["Ad"],"strength":"strong"}]
              }]
            }
        """.trimIndent()
        val parsed = AdConfigLoader.parse(text) as ConfigParseResult.Ok
        assertEquals("com.example.player", parsed.config.apps.single().packageName)
    }

    @Test
    fun countdownPatternMustCaptureAGroup() {
        val text = """{"version":1,"apps":[{"packageName":"com.google.android.youtube","label":"YouTube","adSignals":[{"source":"text","match":"equals","values":["Ad"]}],"countdownPatterns":["Ad"]}]}"""
        val result = AdConfigLoader.parse(text)
        assertTrue((result as ConfigParseResult.Error).message.contains("parentheses"))
    }

    @Test
    fun storeKeepsThePreviousRulesWhenImportIsInvalid() {
        val dir = File(System.getProperty("java.io.tmpdir"), "adskip-config-${System.nanoTime()}")
        dir.mkdirs()
        val store = ConfigStore(File(dir, "ad_config.json"), shippedConfigText())
        val saved = store.replace(shippedConfigText())
        assertTrue(saved is ConfigParseResult.Ok)
        val rejected = store.replace("{")
        assertTrue(rejected is ConfigParseResult.Error)
        assertEquals(3, store.current().apps.size)
        assertNull(store.lastError)
        val reset = store.reset()
        assertEquals(3, reset.apps.size)
        assertNull(store.lastError)
    }

    @Test
    fun corruptFileFallsBackToTheBuiltInRules() {
        val dir = File(System.getProperty("java.io.tmpdir"), "adskip-config-${System.nanoTime()}")
        dir.mkdirs()
        val file = File(dir, "ad_config.json")
        file.writeText("{\"version\":9}")
        val store = ConfigStore(file, shippedConfigText())
        val config = store.current()
        assertEquals(1, config.version)
        assertEquals(3, config.apps.size)
        assertTrue(store.lastError?.contains("version") == true)
    }
}
