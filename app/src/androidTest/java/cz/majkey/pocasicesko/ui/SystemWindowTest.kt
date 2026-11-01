package cz.majkey.pocasicesko.ui

import android.content.ComponentName
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import cz.majkey.pocasicesko.MainActivity
import cz.majkey.pocasicesko.widget.WidgetConfigActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class SystemWindowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    @Suppress("DEPRECATION")
    fun activityThemePreservesTransparentBarsAndCutoutLayout() {
        compose.waitForIdle()
        compose.runOnIdle {
            val activity = compose.activity
            val window = activity.window
            assertEquals(Color.TRANSPARENT, window.statusBarColor)
            assertEquals(Color.TRANSPARENT, window.navigationBarColor)
            assertFalse(window.isStatusBarContrastEnforced)
            assertFalse(window.isNavigationBarContrastEnforced)
            val contentPosition = IntArray(2)
            window.decorView.findViewById<View>(android.R.id.content).getLocationOnScreen(contentPosition)
            assertEquals("Content must extend behind the status bar", 0, contentPosition[1])
            assertEquals(
                if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES,
                window.attributes.layoutInDisplayCutoutMode,
            )
            val widget = activity.packageManager.getActivityInfo(ComponentName(activity, WidgetConfigActivity::class.java), 0)
            assertEquals(activity.applicationInfo.theme, widget.themeResource)
        }
    }
}
