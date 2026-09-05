package cz.majkey.pocasicesko.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class WidgetLayoutTest {
    @Test
    fun contentFillsTheResizedLauncherHost() {
        val layout = File(
            System.getProperty("user.dir"),
            "src/main/res/layout/widget_adaptive.xml",
        )
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(layout)
        val androidNamespace = "http://schemas.android.com/apk/res/android"
        val elements = document.getElementsByTagName("LinearLayout")
        val content = (0 until elements.length)
            .map { elements.item(it) }
            .single {
                it.attributes.getNamedItemNS(androidNamespace, "id")?.nodeValue ==
                    "@+id/widget_content"
            }

        assertEquals("0dp", content.attributes.getNamedItemNS(androidNamespace, "layout_width").nodeValue)
        assertEquals("1", content.attributes.getNamedItemNS(androidNamespace, "layout_weight").nodeValue)
    }
    @Test
    fun fontsAreAppliedDuringInflationIncludingTheTickingClock() {
        val root = File(System.getProperty("user.dir"), "src/main")
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val parser = factory.newDocumentBuilder()
        val namespace = "http://schemas.android.com/apk/res/android"
        val document = parser.parse(File(root, "res/layout/widget_adaptive.xml"))
        for (tag in listOf("TextView", "TextClock")) {
            val elements = document.getElementsByTagName(tag)
            for (index in 0 until elements.length) {
                assertEquals(
                    "?android:attr/fontFamily",
                    elements.item(index).attributes.getNamedItemNS(namespace, "fontFamily")?.nodeValue,
                )
            }
        }
        for (font in listOf("System", "Material", "Rounded", "Light")) {
            for (alignment in listOf("", "Center", "Right")) {
                val suffix = if (alignment.isEmpty()) "" else "_${alignment.lowercase()}"
                val themeSuffix = if (alignment.isEmpty()) "" else ".$alignment"
                val wrapper = parser.parse(File(root, "res/layout/widget_font_${font.lowercase()}$suffix.xml"))
                assertEquals("@style/WidgetText.$font$themeSuffix", wrapper.documentElement.getAttributeNS(namespace, "theme"))
                assertEquals(
                    "@layout/widget_adaptive",
                    wrapper.getElementsByTagName("include").item(0).attributes.getNamedItem("layout").nodeValue,
                )
            }
        }
        val provider = File(root, "java/cz/majkey/pocasicesko/widget/WeatherWidgetProvider.kt").readText()
        assertFalse(provider.contains("\"setTextAppearance\""))
        assertFalse(provider.contains("\"setTypeface\""))
        assertFalse(provider.contains("\"setGravity\""))
    }
}
