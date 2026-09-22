package cz.majkey.pocasicesko.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.locale.AppLocale
import cz.majkey.pocasicesko.notification.WeatherAlertCategory
import cz.majkey.pocasicesko.notification.WeatherAlertSettings
import cz.majkey.pocasicesko.notification.temperatureDropText
import cz.majkey.pocasicesko.units.MeasurementSystem
import cz.majkey.pocasicesko.units.WeatherUnitFormatter
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsSheet(
    settings: WeatherAlertSettings,
    measurementSystem: MeasurementSystem,
    dailyBriefingEnabled: Boolean,
    notificationsAllowed: Boolean,
    onSettingsChange: (WeatherAlertSettings) -> Unit,
    onDailyBriefingChange: (Boolean) -> Unit,
    onRequestPermission: () -> Unit,
    onChannelSettings: (String) -> Unit,
    onDismiss: () -> Unit,
    blockedChannels: Set<String> = emptySet(),
) {
    val units = WeatherUnitFormatter(measurementSystem, AppLocale.locale(LocalContext.current))
    val current = settings.normalized()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF101820),
        contentColor = Color.White,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        SheetHeader(stringResource(R.string.notifications), onBack = onDismiss)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
            .navigationBarsPadding().padding(bottom = 24.dp)) {
            Text(stringResource(R.string.notification_settings_summary),
                color = Color.White.copy(alpha = 0.68f), modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            if (!notificationsAllowed) {
                Text(stringResource(R.string.notification_permission_summary), modifier = Modifier.padding(horizontal = 20.dp))
                Button(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(stringResource(R.string.notification_enable))
                }
            }
            NotificationToggle(
                title = stringResource(R.string.daily_briefing),
                summary = stringResource(R.string.daily_briefing_summary),
                enabled = dailyBriefingEnabled,
                onChange = onDailyBriefingChange,
                onChannelSettings = { onChannelSettings("daily_weather_briefing") },
                systemBlocked = "daily_weather_briefing" in blockedChannels,
            )
            WeatherAlertCategory.entries.forEach { category ->
                NotificationToggle(
                    title = stringResource(category.labelResource),
                    summary = stringResource(category.summaryResource()),
                    enabled = current.isEnabled(category),
                    onChange = { onSettingsChange(current.withEnabled(category, it)) },
                    onChannelSettings = { onChannelSettings(category.channelId) },
                    systemBlocked = category.channelId in blockedChannels,
                )
                if (current.isEnabled(category)) {
                    when (category) {
                        WeatherAlertCategory.COLD -> NotificationThreshold(
                            stringResource(R.string.notification_at_or_below, units.temperature(current.coldCelsius)),
                            current.coldCelsius, -30f..15f,
                        ) { onSettingsChange(current.copy(coldCelsius = it)) }
                        WeatherAlertCategory.DROP -> NotificationThreshold(
                            stringResource(R.string.notification_drop_threshold, temperatureDropText(current.dropCelsius, measurementSystem)),
                            current.dropCelsius, 3f..20f,
                        ) { onSettingsChange(current.copy(dropCelsius = it)) }
                        WeatherAlertCategory.HEAT -> NotificationThreshold(
                            stringResource(R.string.notification_at_or_above, units.temperature(current.heatCelsius)),
                            current.heatCelsius, 20f..45f,
                        ) { onSettingsChange(current.copy(heatCelsius = it)) }
                        WeatherAlertCategory.WIND -> NotificationThreshold(
                            stringResource(R.string.notification_at_or_above, units.windSpeed(current.windKmh)),
                            current.windKmh, 20f..120f,
                        ) { onSettingsChange(current.copy(windKmh = it)) }
                        WeatherAlertCategory.UV -> NotificationThreshold(
                            stringResource(R.string.notification_uv_threshold, current.uvIndex.roundToInt()),
                            current.uvIndex, 3f..11f,
                        ) { onSettingsChange(current.copy(uvIndex = it)) }
                        WeatherAlertCategory.RAIN, WeatherAlertCategory.OFFICIAL -> Unit
                    }
                }
            }
            NotificationThreshold(
                stringResource(R.string.notification_look_ahead, current.lookAheadHours),
                current.lookAheadHours.toDouble(), 1f..12f,
            ) { onSettingsChange(current.copy(lookAheadHours = it.roundToInt())) }
        }
    }
}

@Composable
private fun NotificationToggle(
    title: String,
    summary: String,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
    onChannelSettings: () -> Unit,
    systemBlocked: Boolean,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(if (systemBlocked) "$summary\n${stringResource(R.string.notification_channel_blocked)}" else summary)
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = enabled, onCheckedChange = null)
                IconButton(onClick = onChannelSettings) {
                    Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.notification_channel_settings, title))
                }
            }
        },
        modifier = Modifier.fillMaxWidth().toggleable(value = enabled, role = Role.Switch, onValueChange = onChange),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun NotificationThreshold(label: String, value: Double, range: ClosedFloatingPointRange<Float>, onChange: (Double) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
        Text(label)
        Slider(value = value.toFloat(), onValueChange = { onChange(it.roundToInt().toDouble()) },
            valueRange = range, steps = (range.endInclusive - range.start).roundToInt() - 1,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label })
    }
}

private fun WeatherAlertCategory.summaryResource(): Int = when (this) {
    WeatherAlertCategory.RAIN -> R.string.notification_rain_summary
    WeatherAlertCategory.COLD -> R.string.notification_cold_summary
    WeatherAlertCategory.DROP -> R.string.notification_drop_summary
    WeatherAlertCategory.HEAT -> R.string.notification_heat_summary
    WeatherAlertCategory.WIND -> R.string.notification_wind_summary
    WeatherAlertCategory.UV -> R.string.notification_uv_summary
    WeatherAlertCategory.OFFICIAL -> R.string.notification_official_summary
}
