package com.rackjabbit.adskip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ManifestContractTest {
    @Test
    fun manifestDoesNotRequestInternet() {
        val manifest = readProjectFile("app/src/main/AndroidManifest.xml")
        val elements = Regex("<uses-permission\\b[^>]*>").findAll(manifest).map { it.value }.toList()
        val internet = elements.filter { it.contains("android.permission.INTERNET") }
        assertEquals(1, internet.size)
        assertTrue(internet.single().contains("tools:node=\"remove\""))
    }

    @Test
    fun accessibilityServiceIsLimitedToTheTargetApps() {
        val config = readProjectFile("app/src/main/res/xml/accessibility_service_config.xml")
        assertTrue(config.contains("com.google.android.youtube"))
        assertTrue(config.contains("com.amazon.avod.thirdpartyclient"))
        assertTrue(config.contains("com.amazon.amazonvideo.livingroom"))
        assertTrue(config.contains("android:canRetrieveWindowContent=\"true\""))
        assertTrue(config.contains("android:canPerformGestures=\"true\""))
        assertFalse(config.contains("android:packageNames=\"\""))
    }

    @Test
    fun overlayUsesTheAccessibilityWindowType() {
        val source = readProjectFile("app/src/main/kotlin/com/rackjabbit/adskip/overlay/OverlayController.kt")
        assertTrue(source.contains("TYPE_ACCESSIBILITY_OVERLAY"))
        assertFalse(source.contains("TYPE_APPLICATION_OVERLAY"))
    }

    private fun readProjectFile(relative: String): String {
        var dir = File(System.getProperty("user.dir") ?: error("user.dir is not set")).absoluteFile
        repeat(6) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate.readText()
            dir = dir.parentFile ?: return@repeat
        }
        error("Could not find $relative from ${System.getProperty("user.dir")}")
    }
}
