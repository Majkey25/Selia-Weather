package cz.majkey.pocasicesko.widget

import org.junit.Assert.assertEquals
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
}
