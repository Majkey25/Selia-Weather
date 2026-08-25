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
    private var pickedImageUri by mutableStateOf<String?>(null)

    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onSuccess {
            pickedImageUri = uri.toString()
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
        pickedImageUri = savedInstanceState?.getString(STATE_PENDING_IMAGE_URI)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            WeatherTheme {
                WidgetEditorScreen(
                    initial = WeatherWidgetProvider.loadSettings(this, appWidgetId),
                    pickedImageUri = pickedImageUri,
                    onPickImage = { imagePicker.launch(arrayOf("image/*")) },
                    onApply = { settings ->
                        WeatherWidgetProvider.saveSettings(this, appWidgetId, settings)
                        WeatherWidgetProvider.update(this, AppWidgetManager.getInstance(this), appWidgetId)
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
        outState.putString(STATE_PENDING_IMAGE_URI, pickedImageUri)
        super.onSaveInstanceState(outState)
    }

    private companion object {
        const val STATE_WIDGET_ID = "widget_id"
        const val STATE_PENDING_IMAGE_URI = "pending_image_uri"
    }
}
