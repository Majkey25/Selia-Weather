package cz.majkey.pocasicesko.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class LauncherIconTest {
    @Test
    fun launcherLabelStaysShortInEveryAppLocale() {
        val root = File(System.getProperty("user.dir"), "src/main/res")
        val factory = DocumentBuilderFactory.newInstance()
        for (directory in listOf("values", "values-cs", "values-de", "values-es", "values-fr")) {
            val document = factory.newDocumentBuilder().parse(File(root, "$directory/strings.xml"))
            val strings = document.getElementsByTagName("string")
            val label = (0 until strings.length)
                .map { strings.item(it) }
                .single { it.attributes.getNamedItem("name").nodeValue == "app_name" }
                .textContent

            assertEquals("Vetra", label)
            assertTrue(label.length <= 10)
        }
    }

    @Test
    fun adaptiveForegroundStaysInsideOemSafeZone() {
        val safeForeground = File(
            System.getProperty("user.dir"),
            "src/main/res/drawable/ic_launcher_foreground_safe.xml",
        )
        val safeMark = File(
            System.getProperty("user.dir"),
            "src/main/res/drawable-nodpi/ic_launcher_foreground_mark.png",
        )
        val adaptiveIcon = File(
            System.getProperty("user.dir"),
            "src/main/res/mipmap-anydpi/ic_launcher.xml",
        )
        assertTrue("Missing safe adaptive foreground", safeForeground.isFile)
        assertTrue("Missing padded adaptive mark", safeMark.isFile)
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val safeDocument = factory.newDocumentBuilder().parse(safeForeground)
        val item = safeDocument.getElementsByTagName("item").item(0)
        val androidNamespace = "http://schemas.android.com/apk/res/android"
        for (side in listOf("left", "top", "right", "bottom")) {
            assertEquals("24dp", item.attributes.getNamedItemNS(androidNamespace, side).nodeValue)
        }
        val iconDocument = factory.newDocumentBuilder().parse(adaptiveIcon)
        val foreground = iconDocument.getElementsByTagName("foreground").item(0)
        assertEquals(
            "@drawable/ic_launcher_foreground_safe",
            foreground.attributes.getNamedItemNS(androidNamespace, "drawable").nodeValue,
        )
    }

    @Test
    fun androidThirteenIconSupportsSystemTheming() {
        val themedIcon = File(
            System.getProperty("user.dir"),
            "src/main/res/mipmap-anydpi-v33/ic_launcher.xml",
        )
        assertTrue("Missing Android 13 themed icon", themedIcon.isFile)
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(themedIcon)
        val androidNamespace = "http://schemas.android.com/apk/res/android"
        val monochrome = document.getElementsByTagName("monochrome").item(0)

        assertEquals(
            "@drawable/ic_launcher_foreground_safe",
            monochrome.attributes.getNamedItemNS(androidNamespace, "drawable").nodeValue,
        )
    }

    @Test
    fun launcherBackgroundIsTransparent() {
        val colors = File(System.getProperty("user.dir"), "src/main/res/values/colors.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(colors)
        val entries = document.getElementsByTagName("color")
        val launcherBackground = (0 until entries.length)
            .map { entries.item(it) }
            .single { it.attributes.getNamedItem("name").nodeValue == "launcher_background" }

        assertEquals("#00000000", launcherBackground.textContent.uppercase())
    }
}
