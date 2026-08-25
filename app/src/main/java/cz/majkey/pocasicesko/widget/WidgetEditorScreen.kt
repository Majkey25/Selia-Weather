package cz.majkey.pocasicesko.widget

import android.content.Context
import android.graphics.Color as AndroidColor
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
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
    LaunchedEffect(pickedImageUri) {
        if (pickedImageUri != null && pickedImageUri != settings.imageUri) {
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
            item { WidgetPreview(settings) }
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
        onValueChange = onValueChange,
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
private fun WidgetPreview(settings: WidgetSettings) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val data = remember { loadPreview(context) }
    val normalized = settings.normalized()
    val image by produceState<android.graphics.Bitmap?>(
        initialValue = null,
        normalized.imageUri,
        normalized.backgroundStart,
    ) {
        value = if (normalized.backgroundMode == WidgetBackgroundMode.CUSTOM_IMAGE) {
            withContext(Dispatchers.IO) { WidgetBackground.previewImage(context, normalized) }
        } else {
            null
        }
    }
    val colors = WidgetBackground.previewColors(normalized, data.kind, data.isDay).map(::Color)
    val backgroundAlpha = widgetBackgroundAlpha(normalized, 255) / 255f
    val visibility = widgetContentVisibility(
        settings = normalized,
        size = WidgetSize.WIDE,
        hourlyAvailable = true,
        metricsAvailable = true,
        hasUpdatedAt = true,
    )
    val primary = Color(AndroidColor.parseColor(normalized.primaryColor))
    val secondary = Color(AndroidColor.parseColor(normalized.secondaryColor))
    val accent = Color(AndroidColor.parseColor(normalized.accentColor))
    val textAlign = when (normalized.alignment) {
        WidgetAlignment.LEFT -> TextAlign.Start
        WidgetAlignment.CENTER -> TextAlign.Center
        WidgetAlignment.RIGHT -> TextAlign.End
    }
    val horizontal = when (normalized.alignment) {
        WidgetAlignment.LEFT -> Alignment.Start
        WidgetAlignment.CENTER -> Alignment.CenterHorizontally
        WidgetAlignment.RIGHT -> Alignment.End
    }
    val contentAlignment = when (normalized.alignment) {
        WidgetAlignment.LEFT -> Alignment.CenterStart
        WidgetAlignment.CENTER -> Alignment.Center
        WidgetAlignment.RIGHT -> Alignment.CenterEnd
    }
    val scale = normalized.textScale / 100f
    Box(
        modifier = Modifier.fillMaxWidth().height(190.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(28.dp))
            .border(1.dp, accent.copy(alpha = 0.32f), androidx.compose.foundation.shape.RoundedCornerShape(28.dp))
            .padding(18.dp),
    ) {
        if (image == null || normalized.backgroundMode != WidgetBackgroundMode.CUSTOM_IMAGE) {
            Box(
                Modifier.matchParentSize().background(
                    Brush.horizontalGradient(colors.map { it.copy(alpha = it.alpha * backgroundAlpha) }),
                ),
            )
        }
        if (image != null) Image(
            bitmap = image!!.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alpha = backgroundAlpha,
            modifier = Modifier.matchParentSize(),
        )
        Column(modifier = Modifier.align(contentAlignment), horizontalAlignment = horizontal) {
            if (visibility.showLabel) Text(normalized.customLabel, color = secondary, fontSize = 11.sp * scale, textAlign = textAlign)
            if (visibility.showLocation) Text(data.city, color = secondary, fontSize = 13.sp * scale, fontWeight = FontWeight.SemiBold, textAlign = textAlign)
            if (visibility.showTemperature) Text(data.temperature, color = primary, fontSize = 43.sp * scale, lineHeight = 48.sp * scale, fontWeight = FontWeight.SemiBold, textAlign = textAlign)
            if (visibility.showCondition) Text(data.condition, color = primary, fontSize = 13.sp * scale, textAlign = textAlign)
            if (visibility.showRange) Text(stringResource(R.string.widget_preview_range), color = secondary, fontSize = 12.sp * scale, textAlign = textAlign)
            if (visibility.showHourly) Text(stringResource(R.string.widget_preview_hourly), color = accent, fontSize = 11.sp * scale, textAlign = textAlign)
            if (visibility.showUpdatedAt) Text(stringResource(R.string.widget_preview_updated), color = secondary, fontSize = 10.sp * scale, textAlign = textAlign)
        }
        Column(modifier = Modifier.align(Alignment.TopEnd), horizontalAlignment = Alignment.End) {
            if (visibility.showClock) Text(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")), color = primary, fontSize = 14.sp * scale)
            if (visibility.showDate) Text(LocalDate.now().format(DateTimeFormatter.ofPattern("d MMM")), color = secondary, fontSize = 10.sp * scale)
            if (visibility.showIcon) {
                Spacer(Modifier.height(8.dp))
                WeatherIcon(kind = data.kind, isDay = data.isDay, contentDescription = data.condition, modifier = Modifier.size(38.dp), tint = primary)
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
        )
    },
    restore = { values ->
        WidgetSettings(
            backgroundMode = WidgetBackgroundMode.valueOf(values[0] as String),
            backgroundStart = values[1] as String,
            backgroundEnd = values[2] as String,
            primaryColor = values[3] as String,
            secondaryColor = values[4] as String,
            accentColor = values[5] as String,
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
        ).normalized()
    },
)

private data class WidgetPreviewData(
    val city: String,
    val temperature: String,
    val condition: String,
    val kind: WeatherKind,
    val isDay: Boolean,
)

private fun loadPreview(context: Context): WidgetPreviewData {
    val localized = AppLocale.localized(context)
    val preferences = localized.getSharedPreferences(WeatherRepository.PREFERENCES_NAME, Context.MODE_PRIVATE)
    val temperature = preferences.getFloat(WeatherRepository.KEY_WIDGET_TEMPERATURE, Float.NaN)
    val kind = runCatching {
        WeatherKind.valueOf(preferences.getString(WeatherRepository.KEY_WIDGET_KIND, "UNKNOWN").orEmpty())
    }.getOrDefault(WeatherKind.UNKNOWN)
    return WidgetPreviewData(
        city = preferences.getString(WeatherRepository.KEY_WIDGET_CITY, null) ?: localized.getString(R.string.widget_placeholder_city),
        temperature = if (temperature.isNaN()) localized.getString(R.string.widget_placeholder_temperature) else "${temperature.roundToInt()}°",
        condition = localized.widgetConditionLabel(preferences.getString(WeatherRepository.KEY_WIDGET_CONDITION_KEY, null), kind),
        kind = kind,
        isDay = preferences.getBoolean(WeatherRepository.KEY_WIDGET_IS_DAY, true),
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
