package cz.majkey.pocasicesko.ui

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.data.CzechLocation
import cz.majkey.pocasicesko.data.DeviceLocationRepository
import cz.majkey.pocasicesko.data.WeatherRepository
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class KeyboardVisibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun locationSearchStaysAtTopAndLastFavoriteIsReachableAboveRealKeyboard() {
        val base = compose.activity
        check(base.packageName.endsWith(".debug"))
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) =
                base.getSharedPreferences("keyboard_test_$name", mode)
        }
        val preferences = context.getSharedPreferences(WeatherRepository.PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        try {
            val repository = WeatherRepository(context)
            val favorites = (1..12).map { CzechLocation("Favorite $it", "Test", 50.0 + it * 0.01, 14.0) }
            favorites.forEach(repository::toggleFavorite)
            var selected: CzechLocation? = null
            compose.setContent {
                WeatherTheme {
                    LocationSearchSheet(repository, DeviceLocationRepository(context), favorites.first(), {}, { selected = it })
                }
            }
            val search = compose.onNodeWithContentDescription(base.getString(R.string.search_in_czechia))
            search.assertIsDisplayed().performClick().performTextInput("P")
            compose.waitUntil(5_000) {
                compose.runOnIdle {
                    WindowInspector.getGlobalWindowViews().any {
                        ViewCompat.getRootWindowInsets(it)?.isVisible(WindowInsetsCompat.Type.ime()) == true
                    }
                }
            }
            search.assertIsDisplayed()
            val last = compose.onNodeWithText("Favorite 12").performScrollTo().assertIsDisplayed()
            val screen = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            val visible = Rect()
            compose.runOnIdle {
                WindowInspector.getGlobalWindowViews().first {
                    ViewCompat.getRootWindowInsets(it)?.isVisible(WindowInsetsCompat.Type.ime()) == true
                }.getWindowVisibleDisplayFrame(visible)
            }
            assertTrue("The real keyboard must reduce usable height", visible.bottom < screen.height - 100)
            val row = last.fetchSemanticsNode().boundsInWindow
            assertTrue("The last row must stay above the keyboard", row.bottom <= visible.bottom)
            File(base.getExternalFilesDir(null), "location-keyboard.png").outputStream().use {
                screen.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            screen.recycle()
            last.performClick()
            compose.runOnIdle { assertEquals(favorites.last(), selected) }
        } finally {
            preferences.edit().clear().commit()
        }
    }
}
