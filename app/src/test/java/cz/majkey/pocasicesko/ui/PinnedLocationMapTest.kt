package cz.majkey.pocasicesko.ui

import java.io.File
import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertTrue
import org.junit.Test

class PinnedLocationMapTest {
    @Test
    fun usesPinnedLeafletAndRestrictedWorldwideTiles() {
        val root = File(requireNotNull(System.getProperty("user.dir")))
        val html = File(root, "src/main/assets/location_picker.html").readText()
        val kotlin = File(
            root,
            "src/main/java/cz/majkey/pocasicesko/ui/PinnedLocationMap.kt",
        ).readText()

        assertTrue(html.contains("script-src 'unsafe-inline' file:"))
        assertTrue(html.contains("leaflet-1.9.4.js"))
        assertTrue(html.contains("leaflet-1.9.4.css"))
        assertTrue(
            sri(File(root, "src/main/assets/leaflet-1.9.4.js")) ==
                "sha256-20nQCchB9co0qIjJZRGuk2/Z9VM+kNiyxNV1lvTlZBo=",
        )
        assertTrue(
            sri(File(root, "src/main/assets/leaflet-1.9.4.css")) ==
                "sha256-M3v8pcq9A7OYFbJwD+vis7ft9VkhxZzUn4jssyghIwM=",
        )
        assertTrue(html.contains("https://tile.openstreetmap.org/{z}/{x}/{y}.png"))
        assertTrue(html.contains("LocationBridge.onLocationSelected"))
        assertTrue(html.contains("ResizeObserver"))
        assertTrue(html.contains("map.invalidateSize()"))
        assertTrue(html.contains("height: 240px"))
        assertTrue(kotlin.contains("addJavascriptInterface"))
        assertTrue(kotlin.contains("file:///android_asset/location_picker.html"))
    }

    private fun sri(file: File): String = "sha256-" + Base64.getEncoder().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(
            file.readText().replace("\r\n", "\n").toByteArray(),
        ),
    )
}
