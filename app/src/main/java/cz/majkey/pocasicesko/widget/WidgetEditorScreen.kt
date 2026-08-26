package cz.majkey.pocasicesko.widget

import android.content.Context
import android.graphics.Color as AndroidColor
import android.text.format.DateFormat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.data.WeatherKind
import cz.majkey.pocasicesko.data.WeatherRepository
import cz.majkey.pocasicesko.locale.AppLocale
import cz.majkey.pocasicesko.ui.WeatherIcon
import cz.majkey.pocasicesko.units.MeasurementUnits
import cz.majkey.pocasicesko.units.WeatherUnitFormatter
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun WidgetEditorScreen(
    initial: WidgetSettings,
    pickedImageUri: String?,
    onPickImage: () -> Unit,
    onRemoveImage: (String) -> Unit,
    onApply: (WidgetSettings) -> Unit,
) {
    var settings by rememberSaveable(stateSaver = WidgetSettingsSaver) { mutableStateOf(initial) }
    var previewSize by rememberSaveable { mutableStateOf(WidgetSize.WIDE) }
    LaunchedEffect(pickedImageUri) {
        if (pickedImageUri != null) {
            settings = settings.copy(imageUri = pickedImageUri)
        }
    }
    val invalidColors = listOf(
        settings.backgroundStart,
        settings.backgroundEnd,
        settings.primaryColor,
        settings.secondaryColor,
        settings.accentColor,
    ).any { !isWidgetColor(it) }

    Scaffold(
        bottomBar = {
            Button(
                enabled = !invalidColors,
                onClick = { onApply(settings.normalized()) },
                modifier = Modifier.fillMaxWidth().padding(20.dp).height(52.dp),
            ) { Text(stringResource(R.string.widget_apply)) }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                top = padding.calculateTopPadding() + 18.dp,
                end = 20.dp,
                bottom = padding.calculateBottomPadding() + 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text(stringResource(R.string.widget_title), fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.widget_preview_description), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            item {
                PreviewSizeSelector(previewSize) { previewSize = it }
                WidgetPreview(settings, previewSize)
            }
            item {
                EditorSection(stringResource(R.string.widget_editor_presets)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        WidgetPreset.entries.forEach { preset ->
                            AssistChip(
                                onClick = { settings = widgetPresetSettings(preset, settings) },
                                label = { Text(stringResource(preset.labelResource())) },
                            )
                        }
                    }
                }
            }
            item {
                EditorSection(stringResource(R.string.widget_editor_background)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        WidgetBackgroundMode.entries.forEach { mode ->
                            FilterChip(
                                selected = settings.backgroundMode == mode,
                                onClick = { settings = settings.copy(backgroundMode = mode) },
                                label = { Text(stringResource(mode.labelResource())) },
                            )
                        }
                    }
                    ColorInput(settings.backgroundStart, stringResource(R.string.widget_background_start)) {
                        settings = settings.copy(backgroundStart = it)
                    }
                    ColorInput(settings.backgroundEnd, stringResource(R.string.widget_background_end)) {
                        settings = settings.copy(backgroundEnd = it)
                    }
                    if (settings.backgroundMode == WidgetBackgroundMode.CUSTOM_IMAGE) {
                        ImageControl(
                            hasImage = settings.imageUri.isNotBlank(),
                            onPick = onPickImage,
                            onRemove = {
                                onRemoveImage(settings.imageUri)
                                settings = settings.copy(imageUri = "")
                            },
                        )
                    }
                }
            }
            item {
                EditorSection(stringResource(R.string.widget_editor_colors)) {
                    ColorInput(settings.primaryColor, stringResource(R.string.widget_color_primary)) {
                        settings = settings.copy(primaryColor = it)
                    }
                    ColorInput(settings.secondaryColor, stringResource(R.string.widget_color_secondary)) {
                        settings = settings.copy(secondaryColor = it)
                    }
                    ColorInput(settings.accentColor, stringResource(R.string.widget_color_accent)) {
                        settings = settings.copy(accentColor = it)
                    }
                    SliderControl(
                        title = stringResource(R.string.widget_opacity),
                        value = settings.opacity,
                        range = 0..100,
                    ) { settings = settings.copy(opacity = it) }
                }
            }
            item {
                EditorSection(stringResource(R.string.widget_editor_layout)) {
                    SliderControl(
                        title = stringResource(R.string.widget_text_scale),
                        value = settings.textScale,
                        range = 80..140,
                    ) { settings = settings.copy(textScale = it) }
                    Text(
                        stringResource(R.string.widget_font),
                        fontWeight = FontWeight.Medium,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        WidgetFontStyle.entries.forEach { fontStyle ->
                            FilterChip(
                                selected = settings.fontStyle == fontStyle,
                                onClick = { settings = settings.copy(fontStyle = fontStyle) },
                                label = { Text(stringResource(fontStyle.labelResource())) },
                            )
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        WidgetAlignment.entries.forEach { alignment ->
                            FilterChip(
                                selected = settings.alignment == alignment,
                                onClick = { settings = settings.copy(alignment = alignment) },
                                label = { Text(stringResource(alignment.labelResource())) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = settings.customLabel,
                        onValueChange = { settings = settings.copy(customLabel = it.take(40)) },
                        label = { Text(stringResource(R.string.widget_custom_label)) },
                        supportingText = { Text(stringResource(R.string.widget_label_limit, settings.customLabel.length, 40)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                EditorSection(stringResource(R.string.widget_editor_content)) {
                    FieldToggle(R.string.widget_field_time, settings.showClock) { settings = settings.copy(showClock = it) }
                    FieldToggle(R.string.widget_field_date, settings.showDate) { settings = settings.copy(showDate = it) }
                    FieldToggle(R.string.widget_field_location, settings.showLocation) { settings = settings.copy(showLocation = it) }
                    FieldToggle(R.string.widget_field_temperature, settings.showTemperature) { settings = settings.copy(showTemperature = it) }
                    FieldToggle(R.string.widget_field_icon, settings.showIcon) { settings = settings.copy(showIcon = it) }
                    FieldToggle(R.string.widget_field_condition, settings.showCondition) { settings = settings.copy(showCondition = it) }
                    FieldToggle(R.string.widget_field_range, settings.showRange) { settings = settings.copy(showRange = it) }
                    FieldToggle(R.string.widget_field_hourly, settings.showHourly) { settings = settings.copy(showHourly = it) }
                    FieldToggle(R.string.widget_field_precipitation, settings.showPrecipitation) { settings = settings.copy(showPrecipitation = it) }
                    FieldToggle(R.string.widget_field_wind, settings.showWind) { settings = settings.copy(showWind = it) }
                    FieldToggle(R.string.widget_field_humidity, settings.showHumidity) { settings = settings.copy(showHumidity = it) }
                    FieldToggle(R.string.dew_point, settings.showDewPoint) { settings = settings.copy(showDewPoint = it) }
                    FieldToggle(R.string.pressure, settings.showPressure) { settings = settings.copy(showPressure = it) }
                    FieldToggle(R.string.visibility, settings.showVisibility) { settings = settings.copy(showVisibility = it) }
                    FieldToggle(R.string.wind_gusts, settings.showWindGusts) { settings = settings.copy(showWindGusts = it) }
                    FieldToggle(R.string.moon, settings.showMoon) { settings = settings.copy(showMoon = it) }
                    FieldToggle(R.string.widget_field_updated, settings.showUpdatedAt) { settings = settings.copy(showUpdatedAt = it) }
                }
            }
        }
    }
}

@Composable
private fun EditorSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title.uppercase(), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun ColorInput(value: String, label: String, onValueChange: (String) -> Unit) {
    val invalid = !isWidgetColor(value)
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(widgetColorInput(it)) },
        label = { Text(label) },
        isError = invalid,
        supportingText = { if (invalid) Text(stringResource(R.string.widget_invalid_hex)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ImageControl(hasImage: Boolean, onPick: () -> Unit, onRemove: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = onPick, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.widget_image_select)) }
        if (hasImage) Button(onClick = onRemove, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.widget_image_remove)) }
        Text(
            stringResource(if (hasImage) R.string.widget_image_selected else R.string.widget_image_not_selected),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun SliderControl(title: String, value: Int, range: IntRange, onValueChange: (Int) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(stringResource(R.string.widget_percent, value), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt().coerceIn(range)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        )
    }
}

@Composable
private fun FieldToggle(label: Int, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(stringResource(label))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PreviewSizeSelector(selected: WidgetSize, onSelect: (WidgetSize) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WidgetSize.entries.forEach { size ->
            FilterChip(
                selected = size == selected,
                onClick = { onSelect(size) },
                label = { Text(stringResource(size.labelResource())) },
            )
        }
    }
}

@Composable
private fun WidgetPreview(settings: WidgetSettings, size: WidgetSize) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val configuration = LocalConfiguration.current
    val localized = remember(context, configuration.locales[0]) { AppLocale.localized(context) }
    val locale = localized.resources.configuration.locales[0]
    val data = remember(localized, locale) { loadPreview(localized) }
    val normalized = settings.normalized()
    val backgroundKey = widgetPreviewBackgroundKey(normalized, data.kind, data.isDay)
    val image by produceState<android.graphics.Bitmap?>(
        initialValue = null,
        backgroundKey,
    ) {
        value = withContext(Dispatchers.IO) {
            WidgetBackground.previewBitmap(context, normalized, data.kind, data.isDay)
        }
    }
    val backgroundAlpha = widgetBackgroundAlpha(normalized, 255) / 255f
    val availability = widgetDataAvailability(
        data.hourlyTimes,
        data.hourlyTemperatures,
        data.precipitationProbability,
        data.windSpeed,
        data.humidityPercent,
        data.updatedAt,
    )
    val visibility = widgetContentVisibility(
        settings = normalized,
        size = size,
        availability = availability,
    )
    val advancedText = widgetAdvancedText(normalized, data.advanced)
    val primary = androidx.compose.ui.graphics.Color(AndroidColor.parseColor(normalized.primaryColor))
    val secondary = androidx.compose.ui.graphics.Color(AndroidColor.parseColor(normalized.secondaryColor))
    val accent = androidx.compose.ui.graphics.Color(AndroidColor.parseColor(normalized.accentColor))
    val scale = normalized.textScale / 100f * when (size) {
        WidgetSize.COMPACT -> 0.78f
        WidgetSize.STANDARD -> 0.9f
        WidgetSize.TALL, WidgetSize.WIDE -> 1f
    }
    val previewHeight = when (size) {
        WidgetSize.COMPACT -> 96.dp
        WidgetSize.STANDARD -> 132.dp
        WidgetSize.TALL -> 160.dp
        WidgetSize.WIDE -> 174.dp
    }
    Box(
        modifier = Modifier.fillMaxWidth().height(previewHeight)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(28.dp))
            .border(1.dp, accent.copy(alpha = 0.32f), androidx.compose.foundation.shape.RoundedCornerShape(28.dp))
    ) {
        if (image != null) Image(
            bitmap = image!!.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alpha = backgroundAlpha,
            modifier = Modifier.matchParentSize(),
        )
        Row(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxHeight().fillMaxWidth().padding(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        if (visibility.showLabel) Text(normalized.customLabel, color = secondary, fontSize = 11.sp * scale)
                        if (visibility.showLocation) Text(data.city, color = secondary, fontSize = 12.sp * scale, fontWeight = FontWeight.SemiBold)
                        if (visibility.showTemperature) Text(data.temperature, color = primary, fontSize = 34.sp * scale, lineHeight = 40.sp * scale, fontWeight = FontWeight.SemiBold)
                    }
                    if (visibility.showIcon) {
                        WeatherIcon(
                            kind = data.kind,
                            isDay = data.isDay,
                            contentDescription = data.condition,
                            modifier = Modifier.padding(horizontal = 8.dp).size(30.dp),
                            tint = primary,
                        )
                    }
                    if (visibility.showCondition || visibility.showRange) {
                        Column(Modifier.weight(1f)) {
                            if (visibility.showCondition) Text(data.condition, color = primary, fontSize = 12.sp * scale, fontWeight = FontWeight.SemiBold)
                            if (visibility.showRange) Text(data.range, color = secondary, fontSize = 11.sp * scale)
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        if (visibility.showClock) Text(widgetClock(LocalTime.now(), DateFormat.is24HourFormat(context)), color = primary, fontSize = 14.sp * scale, fontWeight = FontWeight.SemiBold)
                        if (visibility.showDate) Text(widgetDate(LocalDate.now(), locale), color = secondary, fontSize = 10.sp * scale)
                    }
                }
                if (visibility.showMetrics) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (visibility.showPrecipitation) Text(data.precipitation, Modifier.weight(1f), color = secondary, fontSize = 10.sp * scale)
                        if (visibility.showWind) Text(data.wind, Modifier.weight(1f), color = secondary, fontSize = 10.sp * scale, textAlign = TextAlign.Center)
                        if (visibility.showHumidity) Text(data.humidity, Modifier.weight(1f), color = secondary, fontSize = 10.sp * scale, textAlign = TextAlign.End)
                    }
                }
                if (widgetAdvancedVisible(size, advancedText)) {
                    Text(advancedText, color = secondary, fontSize = 10.sp * scale, maxLines = 2)
                }
                if (visibility.showHourly) {
                    Spacer(Modifier.height(5.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(accent))
                    Spacer(Modifier.height(5.dp))
                    Row(Modifier.fillMaxWidth()) {
                        repeat(3) { index ->
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(data.hourlyTimes[index], color = secondary, fontSize = 11.sp * scale)
                                Text(data.hourlyTemperatures[index], color = primary, fontSize = 14.sp * scale, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                if (visibility.showUpdatedAt) Text(
                    widgetUpdatedAt(data.updatedAt, ZoneId.systemDefault(), locale),
                    modifier = Modifier.fillMaxWidth(),
                    color = secondary,
                    fontSize = 9.sp * scale,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

private val WidgetSettingsSaver = listSaver<WidgetSettings, Any>(
    save = { settings ->
        listOf(
            settings.backgroundMode.name,
            settings.backgroundStart,
            settings.backgroundEnd,
            settings.primaryColor,
            settings.secondaryColor,
            settings.accentColor,
            settings.opacity,
            settings.textScale,
            settings.alignment.name,
            settings.customLabel,
            settings.imageUri,
            settings.showClock,
            settings.showDate,
            settings.showLocation,
            settings.showTemperature,
            settings.showIcon,
            settings.showCondition,
            settings.showRange,
            settings.showHourly,
            settings.showPrecipitation,
            settings.showWind,
            settings.showHumidity,
            settings.showUpdatedAt,
            settings.showDewPoint,
            settings.showPressure,
            settings.showVisibility,
            settings.showWindGusts,
            settings.showMoon,
            settings.fontStyle.name,
        )
    },
    restore = { values ->
        WidgetSettings(
            backgroundMode = WidgetBackgroundMode.valueOf(values[0] as String),
            backgroundStart = widgetColorInput(values[1] as String),
            backgroundEnd = widgetColorInput(values[2] as String),
            primaryColor = widgetColorInput(values[3] as String),
            secondaryColor = widgetColorInput(values[4] as String),
            accentColor = widgetColorInput(values[5] as String),
            opacity = values[6] as Int,
            textScale = values[7] as Int,
            alignment = WidgetAlignment.valueOf(values[8] as String),
            customLabel = values[9] as String,
            imageUri = values[10] as String,
            showClock = values[11] as Boolean,
            showDate = values[12] as Boolean,
            showLocation = values[13] as Boolean,
            showTemperature = values[14] as Boolean,
            showIcon = values[15] as Boolean,
            showCondition = values[16] as Boolean,
            showRange = values[17] as Boolean,
            showHourly = values[18] as Boolean,
            showPrecipitation = values[19] as Boolean,
            showWind = values[20] as Boolean,
            showHumidity = values[21] as Boolean,
            showUpdatedAt = values[22] as Boolean,
            showDewPoint = values.getOrNull(23) as? Boolean ?: false,
            showPressure = values.getOrNull(24) as? Boolean ?: false,
            showVisibility = values.getOrNull(25) as? Boolean ?: false,
            showWindGusts = values.getOrNull(26) as? Boolean ?: false,
            showMoon = values.getOrNull(27) as? Boolean ?: false,
            fontStyle = widgetFontStyle(values.getOrNull(28) as? String),
        ).normalized()
    },
)

private data class WidgetPreviewData(
    val city: String,
    val temperature: String,
    val condition: String,
    val kind: WeatherKind,
    val isDay: Boolean,
    val range: String,
    val hourlyTimes: List<String>,
    val hourlyTemperatures: List<String>,
    val precipitation: String,
    val precipitationProbability: Int,
    val wind: String,
    val windSpeed: Float,
    val humidity: String,
    val humidityPercent: Int,
    val advanced: WidgetAdvancedData,
    val updatedAt: Long,
)

private fun loadPreview(context: Context): WidgetPreviewData {
    val preferences = context.getSharedPreferences(WeatherRepository.PREFERENCES_NAME, Context.MODE_PRIVATE)
    val unitFormatter = WeatherUnitFormatter(MeasurementUnits.current(context), context.resources.configuration.locales[0])
    val temperature = preferences.getFloat(WeatherRepository.KEY_WIDGET_TEMPERATURE, Float.NaN)
    val kind = runCatching {
        WeatherKind.valueOf(preferences.getString(WeatherRepository.KEY_WIDGET_KIND, "UNKNOWN").orEmpty())
    }.getOrDefault(WeatherKind.UNKNOWN)
    val high = preferences.getFloat(WeatherRepository.KEY_WIDGET_HIGH, Float.NaN)
    val low = preferences.getFloat(WeatherRepository.KEY_WIDGET_LOW, Float.NaN)
    val hourlyTimes = preferences.getString(WeatherRepository.KEY_WIDGET_HOURLY_TIMES, null)?.split('|').orEmpty()
    val hourlyTemperatures = preferences.getString(WeatherRepository.KEY_WIDGET_HOURLY_TEMPERATURES, null)?.split('|').orEmpty()
    val precipitation = preferences.getInt(WeatherRepository.KEY_WIDGET_PRECIPITATION_PROBABILITY, -1)
    val wind = preferences.getFloat(WeatherRepository.KEY_WIDGET_WIND_SPEED, Float.NaN)
    val humidity = preferences.getInt(WeatherRepository.KEY_WIDGET_HUMIDITY, -1)
    return WidgetPreviewData(
        city = preferences.getString(WeatherRepository.KEY_WIDGET_CITY, null) ?: context.getString(R.string.widget_placeholder_city),
        temperature = if (temperature.isNaN()) context.getString(R.string.widget_placeholder_temperature)
        else unitFormatter.temperature(temperature.toDouble()),
        condition = context.widgetConditionLabel(preferences.getString(WeatherRepository.KEY_WIDGET_CONDITION_KEY, null), kind),
        kind = kind,
        isDay = preferences.getBoolean(WeatherRepository.KEY_WIDGET_IS_DAY, true),
        range = if (high.isNaN() || low.isNaN()) context.getString(R.string.widget_placeholder_range)
        else "${unitFormatter.temperature(high.toDouble())} / ${unitFormatter.temperature(low.toDouble())}",
        hourlyTimes = hourlyTimes,
        hourlyTemperatures = hourlyTemperatures.map { value ->
            value.toDoubleOrNull()?.let(unitFormatter::temperature).orEmpty()
        },
        precipitation = if (precipitation < 0) "--" else "${context.getString(R.string.precipitation)} $precipitation%",
        precipitationProbability = precipitation,
        wind = if (wind.isNaN()) "--" else "${context.getString(R.string.wind)} ${unitFormatter.windSpeed(wind.toDouble())}",
        windSpeed = wind,
        humidity = if (humidity < 0) "--" else "${context.getString(R.string.humidity)} $humidity%",
        humidityPercent = humidity,
        advanced = preferences.widgetAdvancedData(context, unitFormatter),
        updatedAt = preferences.getLong(WeatherRepository.KEY_WIDGET_UPDATED_AT, 0L),
    )
}

private fun WidgetBackgroundMode.labelResource(): Int = when (this) {
    WidgetBackgroundMode.AUTOMATIC -> R.string.widget_background_automatic
    WidgetBackgroundMode.LIGHT -> R.string.widget_background_light
    WidgetBackgroundMode.DARK -> R.string.widget_background_dark
    WidgetBackgroundMode.TRANSPARENT -> R.string.widget_background_transparent
    WidgetBackgroundMode.SOLID -> R.string.widget_background_solid
    WidgetBackgroundMode.GRADIENT -> R.string.widget_background_gradient
    WidgetBackgroundMode.CUSTOM_IMAGE -> R.string.widget_background_custom_image
}

private fun WidgetAlignment.labelResource(): Int = when (this) {
    WidgetAlignment.LEFT -> R.string.widget_alignment_left
    WidgetAlignment.CENTER -> R.string.widget_alignment_center
    WidgetAlignment.RIGHT -> R.string.widget_alignment_right
}

private fun WidgetPreset.labelResource(): Int = when (this) {
    WidgetPreset.MINIMAL -> R.string.widget_preset_minimal
    WidgetPreset.MATERIAL -> R.string.widget_preset_material
    WidgetPreset.PIXEL -> R.string.widget_preset_pixel
    WidgetPreset.CUPERTINO -> R.string.widget_preset_cupertino
}

private fun WidgetFontStyle.labelResource(): Int = when (this) {
    WidgetFontStyle.SYSTEM -> R.string.widget_font_system
    WidgetFontStyle.MATERIAL -> R.string.widget_font_material
    WidgetFontStyle.ROUNDED -> R.string.widget_font_rounded
    WidgetFontStyle.LIGHT -> R.string.widget_font_light
}

private fun WidgetSize.labelResource(): Int = when (this) {
    WidgetSize.COMPACT -> R.string.widget_size_compact
    WidgetSize.STANDARD -> R.string.widget_size_standard
    WidgetSize.TALL -> R.string.widget_size_tall
    WidgetSize.WIDE -> R.string.widget_size_wide
}
