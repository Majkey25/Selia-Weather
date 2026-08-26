package cz.majkey.pocasicesko.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class LauncherIconTest {
    @Test
    fun adaptiveForegroundStaysInsideOemSafeZone() {
        val safeForeground = File(
            System.getProperty("user.dir"),
            "src/main/res/drawable/ic_launcher_foreground_safe.xml",
        )
        val adaptiveIcon = File(
            System.getProperty("user.dir"),
            "src/main/res/mipmap-anydpi/ic_launcher.xml",
        )
        assertTrue("Missing safe adaptive foreground", safeForeground.isFile)
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val safeDocument = factory.newDocumentBuilder().parse(safeForeground)
        val item = safeDocument.getElementsByTagName("item").item(0)
        val androidNamespace = "http://schemas.android.com/apk/res/android"
        for (side in listOf("left", "top", "right", "bottom")) {
            assertEquals("18dp", item.attributes.getNamedItemNS(androidNamespace, side).nodeValue)
        }
        val iconDocument = factory.newDocumentBuilder().parse(adaptiveIcon)
        val foreground = iconDocument.getElementsByTagName("foreground").item(0)
        assertEquals(
            "@drawable/ic_launcher_foreground_safe",
            foreground.attributes.getNamedItemNS(androidNamespace, "drawable").nodeValue,
        )
    }
}
