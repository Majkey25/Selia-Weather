package cz.majkey.pocasicesko.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.data.WeatherKind
import cz.majkey.pocasicesko.widget.ColorInput
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSheet(selectedAppearance: AppearanceConfig, onAppearance: (AppearanceConfig) -> Unit, onDismiss: () -> Unit) {
    var customOpen by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable(selectedAppearance.custom, stateSaver = CustomAppearanceSaver) { mutableStateOf(selectedAppearance.custom) }
    var draftScale by rememberSaveable(selectedAppearance.textScale) { mutableStateOf(selectedAppearance.textScale) }
    var draftCorners by rememberSaveable(selectedAppearance.cornerRadius) { mutableStateOf(selectedAppearance.cornerRadius.toFloat()) }
    var previewDraft by rememberSaveable(stateSaver = CustomAppearanceSaver) { mutableStateOf(selectedAppearance.normalized().custom) }
    val themesList = rememberLazyListState()
    val customList = rememberLazyListState()
    LaunchedEffect(draft) {
        if (customAppearanceReadable(draft)) previewDraft = draft
    }
    val selectedDark = selectedAppearance.isDark(isSystemInDarkTheme())
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !customOpen)) {
        SheetSystemBars()
        BackHandler(customOpen) { customOpen = false }
        SheetHeader(stringResource(if (customOpen) R.string.appearance_custom else R.string.appearance_title),
            onBack = { if (customOpen) customOpen = false else onDismiss() })
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp),
            state = if (customOpen) customList else themesList,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (customOpen) {
                item {
                    ThemePreview(previewDraft)
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .toggleable(value = draft.automaticText, role = Role.Switch,
                            onValueChange = { draft = draft.copy(automaticText = it) }),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.appearance_auto_text), Modifier.weight(1f))
                        Switch(draft.automaticText, onCheckedChange = null)
                    }
                }
                item { ColorInput(draft.backgroundStart, stringResource(R.string.appearance_start_color), opaqueOnly = true) { draft = draft.copy(backgroundStart = it) } }
                item { ColorInput(draft.backgroundEnd, stringResource(R.string.appearance_end_color), opaqueOnly = true) { draft = draft.copy(backgroundEnd = it) } }
                item { ColorInput(draft.surface, stringResource(R.string.appearance_surface_color), opaqueOnly = true) { draft = draft.copy(surface = it) } }
                item { ColorInput(draft.accent, stringResource(R.string.appearance_accent_color), opaqueOnly = true) { draft = draft.copy(accent = it) } }
                if (!draft.automaticText) {
                    item { ColorInput(draft.primaryText, stringResource(R.string.appearance_text_color), opaqueOnly = true) { draft = draft.copy(primaryText = it) } }
                    item { ColorInput(draft.secondaryText, stringResource(R.string.appearance_muted_color), opaqueOnly = true) { draft = draft.copy(secondaryText = it) } }
                }
                item {
                    val readable = customAppearanceReadable(draft)
                    if (!readable) Text(stringResource(R.string.appearance_contrast_help), color = MaterialTheme.colorScheme.error)
                    Button(enabled = readable, onClick = {
                        onAppearance(if (selectedDark) selectedAppearance.copy(custom = draft, darkTheme = AppAppearance.CUSTOM)
                            else selectedAppearance.copy(custom = draft, lightTheme = AppAppearance.CUSTOM))
                        customOpen = false
                    }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(stringResource(R.string.appearance_apply)) }
                    TextButton(onClick = { draft = CustomAppearance() }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.appearance_reset))
                    }
                }
            } else {
                item {
                    Text(stringResource(R.string.appearance_mode), style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppearanceMode.entries.forEach { mode ->
                            FilterChip(selectedAppearance.mode == mode, { onAppearance(selectedAppearance.copy(mode = mode)) },
                                label = { Text(stringResource(mode.labelResource())) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                        }
                    }
                }
                item {
                    Text(stringResource(R.string.appearance_themes), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.appearance_variant_help), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AppAppearance.entries.chunked(2).forEach { themes ->
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            themes.forEach { theme ->
                                ThemeCard(theme, selectedAppearance, Modifier.weight(1f)) { dark ->
                                    onAppearance(if (dark) selectedAppearance.copy(darkTheme = theme) else selectedAppearance.copy(lightTheme = theme))
                                }
                            }
                        }
                    }
                }
                item { Button(onClick = { draft = selectedAppearance.custom; customOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.appearance_edit_custom))
                } }
                item {
                    Text(stringResource(R.string.appearance_typography), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppearanceFont.entries.forEach { font ->
                            FilterChip(selectedAppearance.font == font, { onAppearance(selectedAppearance.copy(font = font)) },
                                label = { Text(stringResource(font.labelResource())) })
                        }
                    }
                    Text(stringResource(R.string.feels_like_temperature, "20°"),
                        fontSize = 18.sp * (draftScale / selectedAppearance.textScale), modifier = Modifier.padding(vertical = 8.dp))
                    val scaleLabel = stringResource(R.string.appearance_text_scale, (draftScale * 100).roundToInt())
                    Text(scaleLabel)
                    Slider(draftScale, { draftScale = it }, onValueChangeFinished = { onAppearance(selectedAppearance.copy(textScale = draftScale)) },
                        valueRange = 0.9f..1.25f, steps = 6, modifier = Modifier.semantics { contentDescription = scaleLabel })
                    val corners = stringResource(R.string.appearance_corners, draftCorners.roundToInt())
                    Text(corners)
                    Slider(draftCorners, { draftCorners = it }, onValueChangeFinished = { onAppearance(selectedAppearance.copy(cornerRadius = draftCorners.roundToInt())) },
                        valueRange = 8f..28f, steps = 9, modifier = Modifier.semantics { contentDescription = corners })
                }
                item { Text(stringResource(R.string.appearance_widgets_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 24.dp)) }
            }
        }
    }
}

private val CustomAppearanceSaver = listSaver<CustomAppearance, String>(
    save = { listOf(it.backgroundStart, it.backgroundEnd, it.surface, it.accent,
        it.primaryText, it.secondaryText, it.automaticText.toString()) },
    restore = { CustomAppearance(it[0], it[1], it[2], it[3], it[4], it[5], it[6].toBooleanStrict()) },
)

@Composable
private fun ThemeCard(theme: AppAppearance, config: AppearanceConfig, modifier: Modifier, onSelect: (Boolean) -> Unit) {
    val context = LocalContext.current
    val name = stringResource(theme.labelResource())
    val selectedDark = config.isDark(isSystemInDarkTheme())
    Column(modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
        .clickable { onSelect(selectedDark) }
        .padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            listOf(false, true).forEach { dark ->
                val rendered = renderedAppearance(context,
                    config.copy(mode = if (dark) AppearanceMode.DARK else AppearanceMode.LIGHT,
                        lightTheme = theme, darkTheme = theme), WeatherKind.CLEAR, true)
                val selected = config.theme(dark) == theme
                val mode = stringResource(if (dark) R.string.appearance_dark else R.string.appearance_light)
                Box(Modifier.size(54.dp).background(Brush.verticalGradient(rendered.palette.background), CircleShape)
                    .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(dark) })
                    .semantics { contentDescription = "$name · $mode" }, contentAlignment = Alignment.Center) {
                    Icon(if (selected) Icons.Rounded.Check else if (dark) Icons.Rounded.DarkMode else Icons.Rounded.LightMode,
                        contentDescription = null, tint = rendered.colors.onBackground, modifier = Modifier.size(20.dp))
                }
            }
        }
        Text(name, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun ThemePreview(custom: CustomAppearance) {
    val safe = AppearanceConfig(custom = custom).normalized().custom
    val palette = appearancePalette(AppAppearance.CUSTOM, WeatherKind.CLEAR, true, custom = safe)
    val colors = appearanceColors(palette, AppAppearance.CUSTOM, true, safe)
    Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(palette.background), RoundedCornerShape(16.dp))
        .padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.appearance_preview), color = colors.onBackground, style = MaterialTheme.typography.labelMedium)
        Text("22°", fontSize = 44.sp, color = colors.onBackground)
        Text(stringResource(R.string.condition_partly_cloudy), color = colors.onSurfaceVariant)
        Text(stringResource(R.string.feels_like_temperature, "20°"), color = colors.onSurfaceVariant)
    }
}

internal fun AppAppearance.labelResource(): Int = when (this) {
    AppAppearance.WEATHER -> R.string.appearance_weather
    AppAppearance.OCEAN -> R.string.appearance_ocean
    AppAppearance.SUNSET -> R.string.appearance_sunset
    AppAppearance.FOREST -> R.string.appearance_forest
    AppAppearance.MATERIAL -> R.string.appearance_material
    AppAppearance.MINIMAL -> R.string.appearance_minimal
    AppAppearance.SAND -> R.string.appearance_sand
    AppAppearance.LAVENDER -> R.string.appearance_lavender
    AppAppearance.ROSE -> R.string.appearance_rose
    AppAppearance.SLATE -> R.string.appearance_slate
    AppAppearance.AMOLED -> R.string.appearance_amoled
    AppAppearance.CUSTOM -> R.string.appearance_custom
}

private fun AppearanceMode.labelResource(): Int = when (this) {
    AppearanceMode.SYSTEM -> R.string.appearance_system
    AppearanceMode.LIGHT -> R.string.appearance_light
    AppearanceMode.DARK -> R.string.appearance_dark
}

private fun AppearanceFont.labelResource(): Int = when (this) {
    AppearanceFont.SYSTEM -> R.string.appearance_font_system
    AppearanceFont.SERIF -> R.string.appearance_font_serif
    AppearanceFont.MONOSPACE -> R.string.appearance_font_mono
}
