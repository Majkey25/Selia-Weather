package cz.majkey.pocasicesko.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
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
        val sourceMark = File(
            System.getProperty("user.dir"),
            "src/main/res/drawable-nodpi/ic_launcher_foreground_mark.png",
        )
        val safeMark = File(
            System.getProperty("user.dir"),
            "src/main/res/drawable-nodpi/ic_launcher_foreground_mark_padded.png",
        )
        val adaptiveIcon = File(
            System.getProperty("user.dir"),
            "src/main/res/mipmap-anydpi/ic_launcher.xml",
        )
        assertTrue("Missing safe adaptive foreground", safeForeground.isFile)
        assertTrue("Missing source launcher mark", sourceMark.isFile)
        assertTrue("Missing padded adaptive mark", safeMark.isFile)
        assertEquals(1254 to 1254, pngSize(sourceMark))
        assertEquals(1920 to 1920, pngSize(safeMark))
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val androidNamespace = "http://schemas.android.com/apk/res/android"
        val safeDocument = factory.newDocumentBuilder().parse(safeForeground)
        val bitmap = safeDocument.getElementsByTagName("bitmap").item(0)
        assertEquals(
            "@drawable/ic_launcher_foreground_mark_padded",
            bitmap.attributes.getNamedItemNS(androidNamespace, "src").nodeValue,
        )
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
    fun launcherBackgroundAvoidsOemBlackFallback() {
        val colors = File(System.getProperty("user.dir"), "src/main/res/values/colors.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(colors)
        val entries = document.getElementsByTagName("color")
        val launcherBackground = (0 until entries.length)
            .map { entries.item(it) }
            .single { it.attributes.getNamedItem("name").nodeValue == "launcher_background" }

        assertEquals("#08072B", launcherBackground.textContent.uppercase())
    }

    private fun pngSize(file: File): Pair<Int, Int> = DataInputStream(file.inputStream().buffered()).use { input ->
        val signature = ByteArray(8)
        input.readFully(signature)
        assertArrayEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            signature,
        )
        assertEquals(13, input.readInt())
        assertEquals(0x49484452, input.readInt())
        input.readInt() to input.readInt()
    }
}
