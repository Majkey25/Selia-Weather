package cz.majkey.pocasicesko.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    @Test
    fun previewFontFamiliesMatchTheLauncherXmlThemes() {
        val root = File(System.getProperty("user.dir"), "src/main")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(root, "res/values/themes.xml"))
        val styles = document.getElementsByTagName("style")
        WidgetFontStyle.entries.forEach { font ->
            val name = "WidgetText." + font.name.lowercase().replaceFirstChar(Char::uppercase)
            val style = (0 until styles.length).map(styles::item)
                .single { it.attributes.getNamedItem("name").nodeValue == name }
            val family = (0 until style.childNodes.length).map(style.childNodes::item)
                .single { it.attributes?.getNamedItem("name")?.nodeValue == "android:fontFamily" }
            assertEquals(family.textContent, widgetPreviewFontName(font))
        }
        val preview = File(root, "java/cz/majkey/pocasicesko/widget/WidgetEditorScreen.kt").readText()
        assertTrue(preview.contains("heightDp = previewHeight.value.roundToInt()"))
        assertTrue(preview.contains("textAlign = textAlignment"))
        assertTrue(preview.contains("fontFamily = fontFamily"))
    }

    @Test
    fun nativeBackgroundDisplaysMaskedPixelsWithoutCroppingCorners() {
        val root = File(System.getProperty("user.dir"), "src/main")
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val parser = factory.newDocumentBuilder()
        val namespace = "http://schemas.android.com/apk/res/android"
        val layout = parser.parse(File(root, "res/layout/widget_adaptive.xml"))
        val images = layout.getElementsByTagName("ImageView")
        val background = (0 until images.length).map(images::item).single {
            it.attributes.getNamedItemNS(namespace, "id").nodeValue == "@+id/widget_background_image"
        }
        assertEquals("fitXY", background.attributes.getNamedItemNS(namespace, "scaleType").nodeValue)
        assertEquals("@android:color/transparent", background.attributes.getNamedItemNS(namespace, "background").nodeValue)
        val provider = File(root, "java/cz/majkey/pocasicesko/widget/WeatherWidgetProvider.kt").readText()
        val backgroundCode = File(root, "java/cz/majkey/pocasicesko/widget/WidgetBackground.kt").readText()
        assertTrue(provider.contains("views.setViewPadding(R.id.widget_content"))
        assertTrue(provider.contains("normalized.contentPaddingDp"))
        assertTrue(provider.contains("widgetCorners(preferences.getString"))
        assertTrue(backgroundCode.contains("Canvas(this).drawRoundRect("))
        assertTrue(backgroundCode.contains("widgetCornerRadiusPixels(normalized.corners, hostSize, size)"))
        assertTrue(backgroundCode.contains("source.recycle()"))
        assertFalse(backgroundCode.contains("\"setClipToOutline\""))
        assertFalse(backgroundCode.contains("\"setCornerRadius\""))
    }

    @Test
    fun bitmapCornerRadiusTracksHostDensityAndBounds() {
        val wide = WidgetHostSize(320, 160)
        assertEquals(28f, widgetCornerRadiusPixels(WidgetCorners.ROUND, wide, WidgetBitmapSize(320, 160)), 0.001f)
        assertEquals(44.8f, widgetCornerRadiusPixels(WidgetCorners.ROUND, wide, WidgetBitmapSize(512, 256)), 0.001f)
        assertEquals(16f, widgetCornerRadiusPixels(WidgetCorners.SOFT, wide, WidgetBitmapSize(320, 160)), 0.001f)
        assertEquals(0f, widgetCornerRadiusPixels(WidgetCorners.SQUARE, wide, WidgetBitmapSize(512, 256)), 0.001f)
        assertEquals(40f, widgetCornerRadiusPixels(WidgetCorners.ROUND, WidgetHostSize(110, 40), WidgetBitmapSize(220, 80)), 0.001f)
        assertEquals(1f, widgetCornerRadiusPixels(WidgetCorners.ROUND, WidgetHostSize(1, 1), WidgetBitmapSize(2, 2)), 0.001f)
    }

    @Test
    fun previewAndLauncherUseTheSameTemperatureWidthFit() {
        val root = File(System.getProperty("user.dir"), "src/main/java/cz/majkey/pocasicesko/widget")
        val provider = File(root, "WeatherWidgetProvider.kt").readText()
        val preview = File(root, "WidgetEditorScreen.kt").readText()
        assertTrue(provider.contains("val temperatureFit = widgetTemperatureFit("))
        assertTrue(preview.contains("val temperatureFit = widgetTemperatureFit("))
        assertTrue(provider.contains("temperatureFit.textSizeSp"))
        assertTrue(preview.contains("temperatureFit.textSizeSp.sp"))
        assertTrue(provider.contains("('0'..'9').maxOf"))
        assertTrue(provider.contains("TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP"))
        assertTrue(provider.contains("paint.fontMetrics.descent - paint.fontMetrics.ascent"))
        assertTrue(provider.contains("availableHeight ="))
        assertTrue(provider.contains("StaticLayout.Builder.obtain"))
        assertFalse(provider.contains("getDefaultPaddingForWidget"))
        assertFalse(provider.contains("hostPaddingPx"))
    }
}
