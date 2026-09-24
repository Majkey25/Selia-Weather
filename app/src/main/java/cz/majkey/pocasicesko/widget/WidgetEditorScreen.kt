package cz.majkey.pocasicesko.widget

import android.appwidget.AppWidgetManager
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import cz.majkey.pocasicesko.R
import java.time.LocalDate
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
    applying: Boolean = false,
) {
    var settings by rememberSaveable(stateSaver = WidgetSettingsSaver) { mutableStateOf(initial) }
    var previewSize by rememberSaveable { mutableStateOf(WidgetSize.WIDE) }
    val locale = LocalConfiguration.current.locales[0]
    val displayedColors = settings.renderedTextColors()
    LaunchedEffect(pickedImageUri) {
        if (pickedImageUri != null) {
            settings = settings.copy(imageUri = pickedImageUri)
        }
    }
    val invalidColors = listOf(
        settings.primaryColor,
        settings.secondaryColor,
        settings.accentColor,
    ).plus(settings.editableBackgroundColors()).any { !isWidgetColor(it) }
    val invalidDatePattern = remember(settings.dateFormat, settings.customDatePattern, locale) {
        settings.dateFormat == WidgetDateFormat.CUSTOM && !isWidgetDatePattern(settings.customDatePattern, locale)
    }

    Scaffold(
        bottomBar = {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (applying) Text(
                    stringResource(R.string.widget_saving_wait),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                Button(
                    enabled = !invalidColors && !invalidDatePattern && !applying,
                    onClick = { onApply(settings.normalized()) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) { Text(stringResource(if (applying) R.string.widget_saving else R.string.widget_apply)) }
            }
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
                    if (settings.editableBackgroundColors().isNotEmpty()) {
                        ColorInput(settings.backgroundStart, stringResource(R.string.widget_background_start)) {
                            settings = settings.copy(backgroundStart = it)
                        }
                    }
                    if (settings.editableBackgroundColors().size > 1) {
                        ColorInput(settings.backgroundEnd, stringResource(R.string.widget_background_end)) {
                            settings = settings.copy(backgroundEnd = it)
                        }
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
                    FieldToggle(R.string.widget_automatic_text_colors, settings.automaticTextColors) {
                        settings = displayedColors.copy(automaticTextColors = it)
                    }
                    if (!settings.automaticTextColorsAvailable() || settings.backgroundMode == WidgetBackgroundMode.GRADIENT) {
                        Text(stringResource(R.string.widget_contrast_note), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    ColorInput(displayedColors.primaryColor, stringResource(R.string.widget_color_primary)) {
                        settings = displayedColors.copy(primaryColor = it, automaticTextColors = false)
                    }
                    ColorInput(displayedColors.secondaryColor, stringResource(R.string.widget_color_secondary)) {
                        settings = displayedColors.copy(secondaryColor = it, automaticTextColors = false)
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
                    Text(stringResource(R.string.widget_corners), fontWeight = FontWeight.Medium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WidgetCorners.entries.forEach { corners ->
                            FilterChip(
                                selected = settings.corners == corners,
                                onClick = { settings = settings.copy(corners = corners) },
                                label = { Text(stringResource(corners.labelResource())) },
                            )
                        }
                    }
                    SliderControl(
                        title = stringResource(R.string.widget_content_padding),
                        value = settings.contentPaddingDp,
                        range = 0..24,
                        valueLabel = "${settings.contentPaddingDp} dp",
                    ) { settings = settings.copy(contentPaddingDp = it) }
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
                EditorSection(stringResource(R.string.widget_date_time_format)) {
                    Text(stringResource(R.string.widget_date_format), fontWeight = FontWeight.Medium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WidgetDateFormat.entries.forEach { format ->
                            FilterChip(
                                selected = settings.dateFormat == format,
                                onClick = { settings = settings.copy(dateFormat = format) },
                                label = {
                                    Text(when (format) {
                                        WidgetDateFormat.SYSTEM -> stringResource(R.string.widget_format_system)
                                        WidgetDateFormat.CUSTOM -> stringResource(R.string.widget_date_custom)
                                        else -> widgetDate(LocalDate.now(), locale, settings.copy(dateFormat = format))
                                    })
                                },
                            )
                        }
                    }
                    if (settings.dateFormat == WidgetDateFormat.CUSTOM) {
                        OutlinedTextField(
                            value = settings.customDatePattern,
                            onValueChange = { settings = settings.copy(customDatePattern = it.take(MAX_WIDGET_DATE_PATTERN_LENGTH)) },
                            label = { Text(stringResource(R.string.widget_date_pattern)) },
                            placeholder = { Text("d.M.yyyy") },
                            isError = invalidDatePattern,
                            supportingText = {
                                Text(if (invalidDatePattern) stringResource(R.string.widget_date_pattern_error)
                                    else stringResource(R.string.widget_format_preview, widgetDate(LocalDate.now(), locale, settings)))
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(stringResource(R.string.widget_date_pattern_hint, MAX_WIDGET_DATE_PATTERN_LENGTH),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    Text(stringResource(R.string.widget_time_format), fontWeight = FontWeight.Medium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WidgetTimeFormat.entries.forEach { format ->
                            FilterChip(
                                selected = settings.timeFormat == format,
                                onClick = { settings = settings.copy(timeFormat = format) },
                                label = { Text(stringResource(when (format) {
                                    WidgetTimeFormat.SYSTEM -> R.string.widget_format_system
                                    WidgetTimeFormat.HOUR_12 -> R.string.widget_time_12
                                    WidgetTimeFormat.HOUR_24 -> R.string.widget_time_24
                                })) },
                            )
                        }
                    }
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
private fun SliderControl(title: String, value: Int, range: IntRange, valueLabel: String? = null, onValueChange: (Int) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(valueLabel ?: stringResource(R.string.widget_percent, value), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(stringResource(label), modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
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
    val previewHeight = when (size) {
        WidgetSize.COMPACT -> 64.dp
        WidgetSize.STANDARD -> 112.dp
        WidgetSize.TALL -> 184.dp
        WidgetSize.WIDE -> 174.dp
    }
    val previewWidthDp = minOf((configuration.screenWidthDp - 40).coerceAtLeast(1), when (size) {
        WidgetSize.COMPACT -> 152
        WidgetSize.STANDARD -> 250
        WidgetSize.TALL -> 168
        WidgetSize.WIDE -> Int.MAX_VALUE
    })
    val views by produceState<RemoteViews?>(null, context, settings, configuration, previewWidthDp, previewHeight) {
        value = withContext(Dispatchers.IO) {
            val options = Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, previewWidthDp)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, previewWidthDp)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, previewHeight.value.roundToInt())
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, previewHeight.value.roundToInt())
            }
            WeatherWidgetProvider.createViews(context, AppWidgetManager.INVALID_APPWIDGET_ID, options, settings)
        }
    }
    AndroidView(
        modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally)
            .width(previewWidthDp.dp).height(previewHeight),
        factory = { FrameLayout(it) },
        update = { host ->
            views?.let { remoteViews ->
                host.removeAllViews()
                val widget = remoteViews.apply(context, host)
                widget.findViewById<View>(R.id.widget_root).setOnClickListener(null)
                host.addView(widget, FrameLayout.LayoutParams(-1, -1))
            }
        },
    )
}

internal fun widgetPreviewFontName(fontStyle: WidgetFontStyle): String = when (fontStyle) {
    WidgetFontStyle.SYSTEM -> "sans-serif"
    WidgetFontStyle.MATERIAL -> "sans-serif-medium"
    WidgetFontStyle.ROUNDED -> "sans-serif-rounded"
    WidgetFontStyle.LIGHT -> "sans-serif-light"
}

internal val WidgetSettingsSaver = listSaver<WidgetSettings, Any>(
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
            settings.corners.name,
            settings.contentPaddingDp,
            settings.automaticTextColors,
            settings.dateFormat.name,
            settings.customDatePattern,
            settings.timeFormat.name,
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
            corners = widgetCorners(values.getOrNull(29) as? String),
            contentPaddingDp = values.getOrNull(30) as? Int ?: DEFAULT_WIDGET_PADDING_DP,
            automaticTextColors = values.getOrNull(31) as? Boolean
                ?: defaultAutomaticWidgetTextColors(values[3] as String, values[4] as String),
            dateFormat = widgetDateFormat(values.getOrNull(32) as? String),
            customDatePattern = values.getOrNull(33) as? String ?: "d.M.yyyy",
            timeFormat = widgetTimeFormat(values.getOrNull(34) as? String),
        )
    },
)

private fun WidgetBackgroundMode.labelResource(): Int = when (this) {
    WidgetBackgroundMode.APP_STYLE -> R.string.widget_app_style
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
    WidgetPreset.APP_STYLE -> R.string.widget_app_style
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

private fun WidgetCorners.labelResource(): Int = when (this) {
    WidgetCorners.SQUARE -> R.string.widget_corners_square
    WidgetCorners.SOFT -> R.string.widget_corners_soft
    WidgetCorners.ROUND -> R.string.widget_corners_round
}

private fun WidgetSize.labelResource(): Int = when (this) {
    WidgetSize.COMPACT -> R.string.widget_size_compact
    WidgetSize.STANDARD -> R.string.widget_size_standard
    WidgetSize.TALL -> R.string.widget_size_tall
    WidgetSize.WIDE -> R.string.widget_size_wide
}
