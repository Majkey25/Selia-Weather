package cz.majkey.pocasicesko.ui

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.data.DeviceLocationFix
import cz.majkey.pocasicesko.data.DeviceLocationRepository
import cz.majkey.pocasicesko.data.LocationMode
import cz.majkey.pocasicesko.data.WeatherRepository
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FollowLocationUiTest {
    @get:Rule val compose = createComposeRule()

    private fun isolatedContext(): Context = object : ContextWrapper(
        InstrumentationRegistry.getInstrumentation().targetContext,
    ) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) =
            super.getSharedPreferences("follow_ui_$name", mode)
    }

    @Test
    fun cancelingDisclosureLeavesThePlaceFixed() {
        val context = isolatedContext()
        val repository = WeatherRepository(context)
        val location = repository.lastLocation()
        repository.selectLocation(location)
        compose.setContent { WeatherTheme {
            LocationSearchSheet(repository, DeviceLocationRepository(context), location, {}, {})
        } }
        compose.onNodeWithText(context.getString(R.string.location_follow)).performClick()
        compose.onNodeWithText(context.getString(R.string.location_follow_disclosure)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
        compose.runOnIdle { assertEquals(LocationMode.FIXED, repository.locationMode()) }
    }

    @Test
    fun fixedChoiceStopsFollowingTheSamePlace() {
        val context = isolatedContext()
        val repository = WeatherRepository(context)
        val location = repository.lastLocation()
        repository.selectCurrentFix(DeviceLocationFix(location, System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos()), true)
        compose.setContent { WeatherTheme {
            LocationSearchSheet(repository, DeviceLocationRepository(context), location, {}, repository::selectLocation)
        } }
        compose.onNodeWithText(context.getString(R.string.location_fixed)).performClick()
        compose.runOnIdle {
            assertEquals(LocationMode.FIXED, repository.locationMode())
            assertEquals(location, repository.lastLocation())
        }
    }
}
