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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.majkey.pocasicesko.data.WeatherKind
import cz.majkey.pocasicesko.data.WeatherRepository
import cz.majkey.pocasicesko.ui.WeatherIcon
import cz.majkey.pocasicesko.ui.WeatherTheme
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private data class WidgetPreviewData(
    val city: String,
    val temperature: String,
    val condition: String,
    val kind: WeatherKind,
    val isDay: Boolean,
)

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

        val preview = loadPreview(applicationContext)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            WeatherTheme {
                WidgetConfigScreen(
                    initial = WeatherWidgetProvider.loadSettings(applicationContext, appWidgetId),
                    preview = preview,
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
private fun WidgetConfigScreen(
    initial: WidgetSettings,
    preview: WidgetPreviewData,
    onSave: (WidgetSettings) -> Unit,
) {
    var settings by remember { mutableStateOf(initial) }
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Text("Widget", fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Náhled se mění rovnou s nastavením.",
                modifier = Modifier.padding(top = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
            WidgetPreview(
                data = preview,
                settings = settings,
                modifier = Modifier.padding(top = 18.dp),
            )

            Text(
                "Vzhled",
                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
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

            Spacer(Modifier.height(14.dp))
            SettingSwitch(
                title = "Čas",
                description = "Systémové hodiny v pravém rohu",
                checked = settings.showClock,
                onCheckedChange = { settings = settings.copy(showClock = it) },
            )
            SettingSwitch(
                title = "Ikona počasí",
                description = "Aktuální stav bez další grafiky",
                checked = settings.showIcon,
                onCheckedChange = { settings = settings.copy(showIcon = it) },
            )
            SettingSwitch(
                title = "Podrobnosti",
                description = "Stav, denní rozsah a hodinový mini-výhled",
                checked = settings.showDetails,
                onCheckedChange = { settings = settings.copy(showDetails = it) },
            )

            Spacer(Modifier.weight(1f))
            Button(
                onClick = { onSave(settings) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Použít")
            }
        }
    }
}

@Composable
private fun WidgetPreview(data: WidgetPreviewData, settings: WidgetSettings, modifier: Modifier = Modifier) {
    val colors = previewColors(settings.theme, data.kind, data.isDay)
    val textColor = if (settings.theme == WidgetTheme.LIGHT) Color(0xFF173042) else Color.White
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(138.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.horizontalGradient(colors))
            .border(BorderStroke(1.dp, textColor.copy(alpha = 0.12f)), RoundedCornerShape(28.dp))
            .padding(18.dp),
    ) {
        Column(Modifier.align(Alignment.CenterStart)) {
            Text(
                data.city,
                color = textColor.copy(alpha = 0.72f),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                data.temperature,
                color = textColor,
                fontSize = 46.sp,
                lineHeight = 51.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (settings.showDetails) {
                Text(data.condition, color = textColor.copy(alpha = 0.76f), fontSize = 13.sp)
            }
        }
        Column(
            modifier = Modifier.align(Alignment.CenterEnd),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (settings.showClock) {
                Text(
                    LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")),
                    color = textColor.copy(alpha = 0.84f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
            }
            if (settings.showIcon) {
                WeatherIcon(
                    kind = data.kind,
                    isDay = data.isDay,
                    contentDescription = data.condition,
                    modifier = Modifier.size(42.dp),
                    tint = textColor,
                )
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
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun loadPreview(context: Context): WidgetPreviewData {
    val preferences = context.getSharedPreferences(WeatherRepository.PREFERENCES_NAME, Context.MODE_PRIVATE)
    val temperature = preferences.getFloat(WeatherRepository.KEY_WIDGET_TEMPERATURE, Float.NaN)
    return WidgetPreviewData(
        city = preferences.getString(WeatherRepository.KEY_WIDGET_CITY, null) ?: "Praha",
        temperature = if (temperature.isNaN()) "--°" else "${temperature.roundToInt()}°",
        condition = preferences.getString(WeatherRepository.KEY_WIDGET_CONDITION, null) ?: "Načíst počasí",
        kind = runCatching {
            WeatherKind.valueOf(preferences.getString(WeatherRepository.KEY_WIDGET_KIND, "UNKNOWN").orEmpty())
        }.getOrDefault(WeatherKind.UNKNOWN),
        isDay = preferences.getBoolean(WeatherRepository.KEY_WIDGET_IS_DAY, true),
    )
}

private fun previewColors(theme: WidgetTheme, kind: WeatherKind, isDay: Boolean): List<Color> = when (theme) {
    WidgetTheme.LIGHT -> listOf(Color(0xFFF4F1EA), Color(0xFFE2EAF0))
    WidgetTheme.DARK -> listOf(Color(0xFF080C11), Color(0xFF17242E))
    WidgetTheme.TRANSPARENT -> listOf(Color(0x66313B43), Color(0x66202A31))
    WidgetTheme.AUTOMATIC -> when {
        !isDay -> listOf(Color(0xFF090D1A), Color(0xFF213460))
        kind == WeatherKind.RAIN || kind == WeatherKind.STORM || kind == WeatherKind.SNOW ->
            listOf(Color(0xFF0B151B), Color(0xFF415D69))
        else -> listOf(Color(0xFF0C1922), Color(0xFF28758D))
    }
}

private fun WidgetTheme.label(): String = when (this) {
    WidgetTheme.AUTOMATIC -> "Automatický"
    WidgetTheme.LIGHT -> "Světlý"
    WidgetTheme.DARK -> "Tmavý"
    WidgetTheme.TRANSPARENT -> "Průhledný"
}
