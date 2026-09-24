package cz.majkey.pocasicesko.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.data.WeatherKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSheet(
    selectedAppearance: AppAppearance,
    onAppearance: (AppAppearance) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        SheetHeader(stringResource(R.string.appearance_title), onBack = onDismiss)
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .selectableGroup()
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            AppAppearance.entries.forEach { appearance ->
                if (appearance == AppAppearance.OCEAN || appearance == AppAppearance.MATERIAL) {
                    Text(
                        stringResource(
                            if (appearance == AppAppearance.OCEAN) R.string.appearance_fixed else R.string.appearance_simple,
                        ),
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AppearanceOption(appearance, appearance == selectedAppearance) { onAppearance(appearance) }
            }
            Text(
                stringResource(R.string.appearance_widgets_note),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AppearanceOption(appearance: AppAppearance, selected: Boolean, onSelect: () -> Unit) {
    val preview = appearancePalette(appearance, WeatherKind.CLEAR, isDay = true)
    ListItem(
        headlineContent = { Text(stringResource(appearance.labelResource())) },
        supportingContent = appearance.summaryResource()?.let { summary -> { Text(stringResource(summary)) } },
        leadingContent = {
            Box(
                Modifier
                    .size(48.dp)
                    .background(Brush.verticalGradient(preview.background), RoundedCornerShape(12.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(12.dp)),
            )
        },
        trailingContent = { RadioButton(selected = selected, onClick = null) },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

internal fun AppAppearance.labelResource(): Int = when (this) {
    AppAppearance.WEATHER -> R.string.appearance_weather
    AppAppearance.OCEAN -> R.string.appearance_ocean
    AppAppearance.SUNSET -> R.string.appearance_sunset
    AppAppearance.FOREST -> R.string.appearance_forest
    AppAppearance.MATERIAL -> R.string.appearance_material
    AppAppearance.MINIMAL -> R.string.appearance_minimal
}

private fun AppAppearance.summaryResource(): Int? = when (this) {
    AppAppearance.WEATHER -> R.string.appearance_weather_summary
    AppAppearance.OCEAN, AppAppearance.SUNSET, AppAppearance.FOREST -> null
    AppAppearance.MATERIAL -> R.string.appearance_material_summary
    AppAppearance.MINIMAL -> R.string.appearance_minimal_summary
}
