package cz.majkey.pocasicesko.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import cz.majkey.pocasicesko.data.conditionFor
import cz.majkey.pocasicesko.data.WeatherKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WeatherIconRenderTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun lightRainHasTwoStreaksRatherThanOneDropAtSmallSizesInBothThemes() {
        val dark = mutableStateOf(true)
        compose.setContent {
            WeatherTheme(config = AppearanceConfig(mode = if (dark.value) AppearanceMode.DARK else AppearanceMode.LIGHT)) {
                Column(Modifier.background(if (dark.value) Color.Black else Color.White)) {
                    for (size in listOf(18, 24)) {
                        Row {
                            for (code in listOf(51, 61)) {
                                val condition = conditionFor(code)
                                WeatherIcon(condition.kind, true, "small-$size-$code", Modifier.size(size.dp),
                                    conditionKey = condition.key, weatherCode = code)
                            }
                        }
                    }
                }
            }
        }
        for (night in listOf(true, false)) {
            compose.runOnIdle { dark.value = night }
            val background = (if (night) Color.Black else Color.White).toArgb()
            for (size in listOf(18, 24)) {
                for ((code, expected) in listOf(51 to 3, 61 to 2)) {
                    val image = compose.onNodeWithContentDescription("small-$size-$code").captureToImage().asAndroidBitmap()
                    val columns = (0 until image.width).map { x ->
                        ((image.height * 0.74).toInt() until image.height).any { y -> image.getPixel(x, y) != background }
                    }
                    val marks = columns.indices.count { columns[it] && (it == 0 || !columns[it - 1]) }
                    assertEquals("code=$code size=$size dark=$night", expected, marks)
                }
            }
        }
    }

    @Test
    fun precipitationShapesStayDistinctAtHourlySizeAndCloudHidesTheSun() {
        val dark = mutableStateOf(true)
        val codes = listOf(51, 61, 63, 65, 68, 71, 73, 75)
        lateinit var appearance: RenderedAppearance
        compose.setContent {
            WeatherTheme(config = AppearanceConfig(mode = if (dark.value) AppearanceMode.DARK else AppearanceMode.LIGHT)) {
                appearance = LocalWeatherAppearance.current
                Column(Modifier.background(if (dark.value) Color(0xFF102332) else Color.White)) {
                    Row {
                        listOf(1, 2).forEach { code ->
                            val condition = conditionFor(code)
                            WeatherIcon(condition.kind, true, "sky-$code", Modifier.size(96.dp),
                                conditionKey = condition.key, weatherCode = code)
                        }
                    }
                    Row {
                        codes.forEach { code ->
                            val condition = conditionFor(code)
                            WeatherIcon(condition.kind, true, "wet-$code", Modifier.size(26.dp),
                                conditionKey = condition.key, weatherCode = code)
                        }
                    }
                    for (isDay in listOf(true, false)) {
                        Row {
                            WeatherKind.entries.forEach { kind ->
                                WeatherIcon(kind, isDay, "symbol-$kind-$isDay", Modifier.size(24.dp))
                            }
                        }
                    }
                }
            }
        }
        val images = codes.map { compose.onNodeWithContentDescription("wet-$it").captureToImage().asAndroidBitmap() }
        images.forEachIndexed { index, image ->
            images.drop(index + 1).forEach { other -> assertFalse("Precipitation icons render identically", image.sameAs(other)) }
        }
        for (night in listOf(true, false)) {
            compose.runOnIdle { dark.value = night }
            for (code in listOf(1, 2)) {
                val image = compose.onNodeWithContentDescription("sky-$code").captureToImage().asAndroidBitmap()
                val expected = weatherIconTint(WeatherKind.CLOUDY, true, appearance)
                assertEquals("Sun shows through cloud interior", expected.toArgb(),
                    image.getPixel((image.width * 0.75f).toInt(), (image.height * 0.75f).toInt()))
            }
            for (isDay in listOf(true, false)) for (kind in WeatherKind.entries) {
                val image = compose.onNodeWithContentDescription("symbol-$kind-$isDay").captureToImage().asAndroidBitmap()
                val expected = weatherIconTint(kind, isDay, appearance).toArgb()
                assertTrue("$kind day=$isDay dark=$night uses its semantic color",
                    (0 until image.width).any { x -> (0 until image.height).any { y -> image.getPixel(x, y) == expected } })
            }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.getExternalFilesDir(null), "weather-icons-${if (night) "dark" else "light"}.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "weather-icons.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
