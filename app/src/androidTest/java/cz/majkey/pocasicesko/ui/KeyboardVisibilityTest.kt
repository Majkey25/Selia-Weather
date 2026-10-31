package cz.majkey.pocasicesko.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.inspector.WindowInspector
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.data.CzechLocation
import cz.majkey.pocasicesko.data.DeviceLocationRepository
import cz.majkey.pocasicesko.data.WeatherRepository
import cz.majkey.pocasicesko.widget.WidgetConfigActivity
import cz.majkey.pocasicesko.widget.WidgetDateFormat
import cz.majkey.pocasicesko.widget.WidgetEditorScreen
import cz.majkey.pocasicesko.widget.WidgetSettings
import cz.majkey.pocasicesko.widget.ColorInput
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class KeyboardVisibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun colorPickerCanConfirmHexWithRealKeyboardOpen() {
        val base = compose.activity
        var applied: String? = null
        compose.setContent { WeatherTheme { ColorInput("#112233", "Color") { applied = it } } }
        compose.onNodeWithContentDescription(base.getString(R.string.widget_choose_color, "Color")).performClick()
        compose.onNodeWithText("HEX").performClick().performTextReplacement("#ABCDEF")
        val visible = Rect()
        compose.waitUntil(5_000) { compose.runOnIdle {
            WindowInspector.getGlobalWindowViews().firstOrNull {
                ViewCompat.getRootWindowInsets(it)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }?.let { it.getWindowVisibleDisplayFrame(visible); true } == true
        } }
        val confirm = compose.onNodeWithText(base.getString(android.R.string.ok)).assertIsDisplayed()
        assertTrue("Color confirmation must stay above the keyboard: ${confirm.fetchSemanticsNode().boundsInWindow}, visible=$visible",
            confirm.fetchSemanticsNode().boundsInWindow.bottom <= visible.bottom)
        confirm.performClick()
        compose.runOnIdle { assertEquals("#ABCDEF", applied) }
    }

    @Test
    fun widgetApplyRemainsReachableWhileEditingDateWithRealKeyboard() {
        val base = compose.activity
        val activity = base.packageManager.getActivityInfo(ComponentName(base, WidgetConfigActivity::class.java), 0)
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            activity.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            WindowCompat.enableEdgeToEdge(base.window)
            base.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        var applied: WidgetSettings? = null
        compose.setContent { WeatherTheme {
            WidgetEditorScreen(WidgetSettings(dateFormat = WidgetDateFormat.CUSTOM), null, {}, {}, { applied = it })
        } }
        val label = base.getString(R.string.widget_date_pattern)
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(label))
        compose.onNodeWithText(label).performClick().performTextReplacement("dd.MM.yyyy")
        compose.waitUntil(5_000) { compose.runOnIdle {
            WindowInspector.getGlobalWindowViews().any {
                ViewCompat.getRootWindowInsets(it)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
        } }
        val apply = compose.onNodeWithText(base.getString(R.string.widget_apply)).assertIsDisplayed()
        val visible = Rect()
        compose.runOnIdle { base.window.decorView.getWindowVisibleDisplayFrame(visible) }
        assertTrue("Apply must stay above the keyboard", apply.fetchSemanticsNode().boundsInWindow.bottom <= visible.bottom)
        apply.performClick()
        compose.runOnIdle { assertEquals("dd.MM.yyyy", applied?.customDatePattern) }
    }

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
            (1..12).forEach { repository.toggleFavorite(CzechLocation("Favorite $it", "Test", 50.0 + it * 0.01, 14.0)) }
            val favorites = repository.favoriteLocations()
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
