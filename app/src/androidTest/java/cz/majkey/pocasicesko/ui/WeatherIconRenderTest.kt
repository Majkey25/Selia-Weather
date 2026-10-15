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
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class WeatherIconRenderTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun precipitationShapesStayDistinctAtHourlySizeAndCloudHidesTheSun() {
        val dark = mutableStateOf(true)
        val codes = listOf(51, 61, 63, 65, 68, 71, 73, 75)
        compose.setContent {
            WeatherTheme(config = AppearanceConfig(mode = if (dark.value) AppearanceMode.DARK else AppearanceMode.LIGHT)) {
                Column(Modifier.background(if (dark.value) Color(0xFF102332) else Color.White)) {
                    Row {
                        listOf(1, 2).forEach { code ->
                            val condition = conditionFor(code)
                            WeatherIcon(condition.kind, true, "sky-$code", Modifier.size(96.dp),
                                tint = Color(0xFF9ED9EA), conditionKey = condition.key, weatherCode = code)
                        }
                    }
                    Row {
                        codes.forEach { code ->
                            val condition = conditionFor(code)
                            WeatherIcon(condition.kind, true, "wet-$code", Modifier.size(26.dp),
                                tint = Color(0xFF65B8DF), conditionKey = condition.key, weatherCode = code)
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
                val expected = if (!night) Color(0xFF477B9C) else if (code == 1) Color(0xFF9FB7FF) else Color(0xFF9ED9EA)
                assertEquals("Sun shows through cloud interior", expected.toArgb(),
                    image.getPixel((image.width * 0.75f).toInt(), (image.height * 0.75f).toInt()))
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "weather-icons.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
