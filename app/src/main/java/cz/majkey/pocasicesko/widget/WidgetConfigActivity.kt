package cz.majkey.pocasicesko.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.locale.AppLocale
import cz.majkey.pocasicesko.ui.WeatherTheme

class WidgetConfigActivity : ComponentActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var selectedImageUri by mutableStateOf<String?>(null)
    private var initialImageUri = ""
    private var applying = false

    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        selectedImageUri = uri?.toString()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        appWidgetId = savedInstanceState?.getInt(STATE_WIDGET_ID)
            ?: intent?.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        selectedImageUri = savedInstanceState?.getString(STATE_SELECTED_IMAGE_URI)
        initialImageUri = savedInstanceState?.getString(STATE_INITIAL_IMAGE_URI).orEmpty()
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        if (initialImageUri.isEmpty()) {
            initialImageUri = WeatherWidgetProvider.loadSettings(this, appWidgetId).imageUri
        }

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            WeatherTheme {
                WidgetEditorScreen(
                    initial = WeatherWidgetProvider.loadSettings(this, appWidgetId),
                    pickedImageUri = selectedImageUri,
                    onPickImage = { imagePicker.launch(arrayOf("image/*")) },
                    onRemoveImage = {
                        selectedImageUri = null
                    },
                    onApply = { settings ->
                        if (!applying) applySettings(settings)
                    },
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_WIDGET_ID, appWidgetId)
        outState.putString(STATE_SELECTED_IMAGE_URI, selectedImageUri)
        outState.putString(STATE_INITIAL_IMAGE_URI, initialImageUri)
        super.onSaveInstanceState(outState)
    }

    private fun applySettings(settings: WidgetSettings) {
        applying = true
        val normalized = settings.normalized()
        WeatherWidgetProvider.applySettings(
            applicationContext,
            appWidgetId,
            normalized,
            requiresWidgetImageGrant(initialImageUri, normalized.imageUri),
        ) { saved ->
            runOnUiThread {
                applying = false
                if (!saved) {
                    Toast.makeText(this, R.string.widget_image_access_failed, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                selectedImageUri = null
                setResult(
                    Activity.RESULT_OK,
                    Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
                )
                finish()
            }
        }
    }

    private companion object {
        const val STATE_WIDGET_ID = "widget_id"
        const val STATE_SELECTED_IMAGE_URI = "selected_image_uri"
        const val STATE_INITIAL_IMAGE_URI = "initial_image_uri"
    }
}
