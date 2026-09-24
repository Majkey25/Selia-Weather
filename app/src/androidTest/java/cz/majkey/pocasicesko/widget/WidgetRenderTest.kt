package cz.majkey.pocasicesko.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextClock
import android.widget.TextView
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.data.WeatherRepository
import cz.majkey.pocasicesko.ui.WeatherTheme
import java.io.File
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

class WidgetRenderTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context by lazy {
        FixtureContext(instrumentation.targetContext.createConfigurationContext(
            Configuration(instrumentation.targetContext.resources.configuration).apply { setLocale(Locale.ENGLISH) },
        ))
    }

    @Before
    fun seedIsolatedWeather() {
        assertTrue(context.getSharedPreferences(WeatherRepository.PREFERENCES_NAME, Context.MODE_PRIVATE).edit()
            .clear()
            .putString(WeatherRepository.KEY_WIDGET_CITY, "Prague")
            .putString(WeatherRepository.KEY_WIDGET_KIND, "PARTLY_CLOUDY")
            .putString(WeatherRepository.KEY_WIDGET_CONDITION_KEY, "PARTLY_CLOUDY")
            .putBoolean(WeatherRepository.KEY_WIDGET_IS_DAY, true)
            .putFloat(WeatherRepository.KEY_WIDGET_TEMPERATURE, 22f)
            .putFloat(WeatherRepository.KEY_WIDGET_HIGH, 24f)
            .putFloat(WeatherRepository.KEY_WIDGET_LOW, 14f)
            .putString(WeatherRepository.KEY_WIDGET_HOURLY_TIMES, "11:00|12:00|13:00")
            .putString(WeatherRepository.KEY_WIDGET_HOURLY_TEMPERATURES, "22|23|24")
            .putInt(WeatherRepository.KEY_WIDGET_PRECIPITATION_PROBABILITY, 10)
            .putFloat(WeatherRepository.KEY_WIDGET_WIND_SPEED, 12f)
            .putInt(WeatherRepository.KEY_WIDGET_HUMIDITY, 65)
            .putLong(WeatherRepository.KEY_WIDGET_UPDATED_AT, 1790236800000L)
            .commit())
    }

    @After
    fun removeFixturePreferences() {
        context.getSharedPreferences(WeatherRepository.PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun nativeWidgetsFitAfterResizeAndKeepIndependentStyles() {
        instrumentation.runOnMainSync {
            val defaults = WidgetSettings()
            val custom = defaults.copy(backgroundMode = WidgetBackgroundMode.LIGHT, fontStyle = WidgetFontStyle.ROUNDED,
                alignment = WidgetAlignment.RIGHT, corners = WidgetCorners.SOFT, showClock = false, showDate = false)
            assertTrue(WeatherWidgetProvider.saveSettings(context, 41, defaults))
            assertTrue(WeatherWidgetProvider.saveSettings(context, 42, custom))
            for ((width, height) in listOf(110 to 40, 152 to 64, 250 to 112, 168 to 184, 352 to 174)) {
                val widget = render(41, width, height)
                assertTemperatureFits(widget)
                save(widget, "widget-${width}x$height.png")
            }
            val tall = render(41, 168, 184)
            val temperature = tall.findViewById<TextView>(R.id.widget_temperature)
            assertTrue("Narrow widget temperature must stay prominent", temperature.textSize >=
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 24f, context.resources.displayMetrics))
            val condition = tall.findViewById<TextView>(R.id.widget_condition)
            assertTrue("Weather details must sit below the temperature", bounds(tall, condition).top >= bounds(tall, temperature).bottom)
            val light = render(42, 168, 184)
            assertEquals(Color.BLACK, light.findViewById<TextView>(R.id.widget_temperature).currentTextColor)
            assertEquals(View.GONE, light.findViewById<View>(R.id.widget_clock).visibility)
            assertEquals(defaults, WeatherWidgetProvider.loadSettings(context, 41))
            assertEquals(custom, WeatherWidgetProvider.loadSettings(context, 42))
            save(light, "widget-light-168x184.png")
        }
    }

    @Test
    fun largeTextAndLongTemperatureRemainInsideNativeWidget() {
        val largeContext = FixtureContext(context.baseContext.createConfigurationContext(
            Configuration(context.resources.configuration).apply { fontScale = 1.6f },
        ))
        assertTrue(WeatherWidgetProvider.saveSettings(context, 41, WidgetSettings(textScale = 140,
            customLabel = "Weather at home", showPrecipitation = true, showWind = true, showHumidity = true)))
        context.getSharedPreferences(WeatherRepository.PREFERENCES_NAME, Context.MODE_PRIVATE).edit()
            .putFloat(WeatherRepository.KEY_WIDGET_TEMPERATURE, -123f).commit()
        instrumentation.runOnMainSync {
            for ((width, height) in listOf(110 to 40, 168 to 184, 352 to 174, 352 to 300)) {
                assertTemperatureFits(render(41, width, height, largeContext))
            }
        }
    }

    @Test
    fun captureEditorUsingDemoWeatherOnly() {
        var applied: WidgetSettings? = null
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                WeatherTheme { WidgetEditorScreen(WidgetSettings(), null, {}, {}, { applied = it }) }
            }
        }
        compose.waitForIdle()
        saveBitmap(compose.onRoot().captureToImage().asAndroidBitmap(), "widget-editor.png")
        compose.onNodeWithText(context.getString(R.string.widget_preset_minimal)).performTouchInput { click() }
        compose.onNodeWithText(context.getString(R.string.widget_apply)).performTouchInput { click() }
        assertEquals(WidgetBackgroundMode.TRANSPARENT, applied?.backgroundMode)
        assertEquals(false, applied?.showIcon)
    }

    @Test
    fun captureCzechEditorUsingDemoWeatherOnly() {
        val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("cs"))
        })
        context.getSharedPreferences(WeatherRepository.PREFERENCES_NAME, Context.MODE_PRIVATE).edit()
            .putString(WeatherRepository.KEY_WIDGET_CITY, "Praha").commit()
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration) {
                WeatherTheme { WidgetEditorScreen(WidgetSettings(), null, {}, {}, {}) }
            }
        }
        compose.waitForIdle()
        saveBitmap(compose.onRoot().captureToImage().asAndroidBitmap(), "widget-editor-cs.png")
    }

    private fun render(id: Int, widthDp: Int, heightDp: Int, renderContext: Context = context): FrameLayout {
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp)
        }
        val frame = FrameLayout(renderContext).apply { setBackgroundColor(Color.rgb(23, 32, 43)) }
        val views = WeatherWidgetProvider.createViews(renderContext, id, options)
        frame.addView(views.apply(renderContext, frame), FrameLayout.LayoutParams(-1, -1))
        frame.findViewById<TextClock>(R.id.widget_clock).text = "10:08"
        frame.findViewById<TextView>(R.id.widget_date).text = "9/24/26"
        val density = renderContext.resources.displayMetrics.density
        val width = (widthDp * density).roundToInt()
        val height = (heightDp * density).roundToInt()
        frame.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        frame.layout(0, 0, width, height)
        return frame
    }

    private fun assertTemperatureFits(widget: FrameLayout) {
        val temperature = widget.findViewById<TextView>(R.id.widget_temperature)
        assertEquals(View.VISIBLE, temperature.visibility)
        assertEquals(1, temperature.layout.lineCount)
        assertTrue("Temperature clipped horizontally", temperature.layout.getLineWidth(0) <= temperature.width)
        assertTrue("Temperature clipped vertically", temperature.layout.getLineBottom(0) <= temperature.height)
        val rect = bounds(widget, temperature)
        assertTrue("Temperature leaves widget bounds: $rect in ${widget.width}x${widget.height}",
            rect.left >= 0 && rect.top >= 0 && rect.right <= widget.width && rect.bottom <= widget.height)
    }

    private fun bounds(parent: ViewGroup, child: View): Rect = Rect(0, 0, child.width, child.height).apply {
        parent.offsetDescendantRectToMyCoords(child, this)
    }

    private fun save(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        saveBitmap(bitmap, name)
        bitmap.recycle()
    }

    private fun saveBitmap(bitmap: Bitmap, name: String) {
        val directory = requireNotNull(context.getExternalFilesDir("widget-captures"))
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private class FixtureContext(base: Context) : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int) =
            super.getSharedPreferences("widget_render_test_$name", mode)

        override fun createConfigurationContext(overrideConfiguration: Configuration): Context =
            FixtureContext(super.createConfigurationContext(overrideConfiguration))
    }
}
