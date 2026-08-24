package cz.majkey.pocasicesko.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.majkey.pocasicesko.ui.WeatherTheme

class WidgetConfigActivity : ComponentActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
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
                WidgetConfigScreen(
                    initial = WeatherWidgetProvider.loadSettings(applicationContext, appWidgetId),
                    onSave = { settings ->
                        WeatherWidgetProvider.saveSettings(applicationContext, appWidgetId, settings)
                        WeatherWidgetProvider.update(
                            applicationContext,
                            AppWidgetManager.getInstance(applicationContext),
                            appWidgetId,
                        )
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
}

@Composable
private fun WidgetConfigScreen(initial: WidgetSettings, onSave: (WidgetSettings) -> Unit) {
    var settings by remember { mutableStateOf(initial) }
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text("Nastavit widget", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Widget se přizpůsobí šířce. Úzký ukáže základ, široký přidá město a denní rozsah.",
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                "Vzhled",
                modifier = Modifier.padding(top = 30.dp, bottom = 10.dp),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(end = 12.dp),
            ) {
                items(WidgetTheme.entries) { theme ->
                    FilterChip(
                        selected = settings.theme == theme,
                        onClick = { settings = settings.copy(theme = theme) },
                        label = { Text(theme.label()) },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            SettingSwitch(
                title = "Čas",
                description = "Průběžně aktualizované systémové hodiny",
                checked = settings.showClock,
                onCheckedChange = { settings = settings.copy(showClock = it) },
            )
            SettingSwitch(
                title = "Ikona počasí",
                description = "Jednoduchý symbol aktuálního stavu",
                checked = settings.showIcon,
                onCheckedChange = { settings = settings.copy(showIcon = it) },
            )
            SettingSwitch(
                title = "Podrobnosti",
                description = "Stav a denní minimum/maximum v širokém widgetu",
                checked = settings.showDetails,
                onCheckedChange = { settings = settings.copy(showDetails = it) },
            )

            Spacer(Modifier.weight(1f))
            Button(
                onClick = { onSave(settings) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Přidat widget")
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun WidgetTheme.label(): String = when (this) {
    WidgetTheme.AUTOMATIC -> "Automatický"
    WidgetTheme.LIGHT -> "Světlý"
    WidgetTheme.DARK -> "Tmavý"
    WidgetTheme.TRANSPARENT -> "Průhledný"
}
