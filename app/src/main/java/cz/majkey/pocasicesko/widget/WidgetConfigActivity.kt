package cz.majkey.pocasicesko.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cz.majkey.pocasicesko.locale.AppLocale
import cz.majkey.pocasicesko.ui.WeatherTheme

class WidgetConfigActivity : ComponentActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var pendingImageUri by mutableStateOf<String?>(null)
    private var initialImageUri = ""
    private var applied = false

    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onSuccess {
            val value = uri.toString()
            if (value == pendingImageUri) return@onSuccess
            discardPendingImage()
            pendingImageUri = value.takeUnless { it == initialImageUri }
        }
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
        pendingImageUri = savedInstanceState?.getString(STATE_PENDING_IMAGE_URI)
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
                    pickedImageUri = pendingImageUri,
                    onPickImage = { imagePicker.launch(arrayOf("image/*")) },
                    onRemoveImage = { uri ->
                        if (uri == pendingImageUri) discardPendingImage()
                    },
                    onApply = { settings ->
                        WeatherWidgetProvider.saveSettings(this, appWidgetId, settings)
                        WeatherWidgetProvider.update(this, AppWidgetManager.getInstance(this), appWidgetId)
                        pendingImageUri = null
                        applied = true
                        setResult(
                            Activity.RESULT_OK,
                            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
                        )
                        finish()
                    },
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_WIDGET_ID, appWidgetId)
        outState.putString(STATE_PENDING_IMAGE_URI, pendingImageUri)
        outState.putString(STATE_INITIAL_IMAGE_URI, initialImageUri)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (isFinishing && !applied) discardPendingImage()
        super.onDestroy()
    }

    private fun discardPendingImage() {
        pendingImageUri?.let { WeatherWidgetProvider.releaseImageIfUnused(this, it) }
        pendingImageUri = null
    }

    private companion object {
        const val STATE_WIDGET_ID = "widget_id"
        const val STATE_PENDING_IMAGE_URI = "pending_image_uri"
        const val STATE_INITIAL_IMAGE_URI = "initial_image_uri"
    }
}
