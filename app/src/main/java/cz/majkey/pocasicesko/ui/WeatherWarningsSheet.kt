package cz.majkey.pocasicesko.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.data.CzechLocation
import cz.majkey.pocasicesko.data.WeatherWarning
import cz.majkey.pocasicesko.data.WeatherWarningSeverity
import cz.majkey.pocasicesko.data.WeatherWarningsResult
import cz.majkey.pocasicesko.data.WeatherWarningsStatus
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun WarningsAction(result: WeatherWarningsResult?, onClick: () -> Unit) {
    val count = if (result?.status == WeatherWarningsStatus.AVAILABLE) result.warnings.size else 0
    val severe = result?.status == WeatherWarningsStatus.AVAILABLE && result.warnings.any { it.severity >= WeatherWarningSeverity.SEVERE }
    val summary = when {
        result == null -> stringResource(R.string.warnings_not_checked)
        result.status == WeatherWarningsStatus.UNAVAILABLE -> stringResource(R.string.warnings_unavailable_short)
        result.status == WeatherWarningsStatus.UNSUPPORTED -> stringResource(R.string.warnings_unsupported_short)
        count == 0 -> stringResource(R.string.warnings_none_short)
        else -> pluralStringResource(R.plurals.warnings_count, count, count)
    }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xA61A252E),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.09f)),
    ) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.WarningAmber, contentDescription = null, modifier = Modifier.padding(end = 12.dp).size(22.dp),
                tint = if (severe) MaterialTheme.colorScheme.error else Color.White)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.warnings_title), fontWeight = FontWeight.SemiBold)
                Text(summary, fontSize = 12.sp, color = Color.White.copy(alpha = 0.72f))
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null,
                tint = Color.White.copy(alpha = 0.58f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WeatherWarningsSheet(
    location: CzechLocation,
    result: WeatherWarningsResult?,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenSource: (String) -> Unit,
    onDismiss: () -> Unit,
    timezone: String? = null,
) {
    val locale = LocalConfiguration.current.locales[0]
    val zone = remember(timezone) { timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault() }
    val formatter = remember(locale, zone) { DateTimeFormatter.ofPattern("d MMM, HH:mm z", locale).withZone(zone) }
    val refreshLabel = stringResource(if (refreshing) R.string.warnings_loading else R.string.warnings_refresh)
    val warnings = result?.takeIf { it.status == WeatherWarningsStatus.AVAILABLE }?.warnings.orEmpty()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF101820),
        contentColor = Color.White,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight().navigationBarsPadding()) {
            SheetHeader(stringResource(R.string.warnings_title), onBack = onDismiss, subtitle = location.name)
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(result?.sourceName ?: stringResource(R.string.warnings_official_sources), fontWeight = FontWeight.SemiBold)
                            result?.let {
                                Text(stringResource(R.string.warnings_checked, formatter.format(it.checkedAt)),
                                    fontSize = 12.sp, color = Color.White.copy(alpha = 0.72f))
                            }
                        }
                        IconButton(onClick = onRefresh, enabled = !refreshing,
                            modifier = Modifier.semantics { contentDescription = refreshLabel }) {
                            if (refreshing) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Rounded.Refresh, contentDescription = null)
                            }
                        }
                    }
                    TextButton(onClick = { onOpenSource(result?.sourceUrl ?: "https://severeweather.wmo.int/sources.html") }) {
                        Text(stringResource(R.string.warnings_open_source))
                    }
                }
                if (warnings.isEmpty()) {
                    item {
                        val message = when (result?.status) {
                            WeatherWarningsStatus.AVAILABLE -> R.string.warnings_none
                            WeatherWarningsStatus.UNAVAILABLE -> R.string.warnings_unavailable
                            WeatherWarningsStatus.UNSUPPORTED -> R.string.warnings_unsupported
                            null -> if (refreshing) R.string.warnings_loading else R.string.warnings_not_checked
                        }
                        Text(stringResource(message), fontSize = 16.sp, lineHeight = 24.sp)
                    }
                }
                items(warnings, key = { it.id }) { warning ->
                    WarningRow(warning, formatter, onOpenSource)
                    HorizontalDivider(Modifier.padding(top = 12.dp), color = Color.White.copy(alpha = 0.12f))
                }
            }
        }
    }
}

@Composable
private fun WarningRow(warning: WeatherWarning, formatter: DateTimeFormatter, onOpenSource: (String) -> Unit) {
    var expanded by rememberSaveable(warning.id) { mutableStateOf(false) }
    val expansionState = stringResource(if (expanded) R.string.hour_expanded else R.string.hour_collapsed)
    val toggleLabel = stringResource(if (expanded) R.string.warnings_hide_details else R.string.warnings_show_details)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .semantics { stateDescription = expansionState }
                .clickable(role = Role.Button, onClickLabel = toggleLabel) { expanded = !expanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(warning.severity.labelResource()), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = if (warning.severity >= WeatherWarningSeverity.SEVERE) MaterialTheme.colorScheme.error else Color.White.copy(alpha = 0.78f))
                Text(warning.headline, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp)
            }
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null, modifier = Modifier.padding(start = 12.dp))
        }
        Text(warning.source, fontSize = 13.sp, color = Color.White.copy(alpha = 0.72f))
        warning.onset?.let { Text(stringResource(R.string.warnings_from, formatter.format(it)), fontSize = 13.sp) }
        warning.expires?.let { Text(stringResource(R.string.warnings_until, formatter.format(it)), fontSize = 13.sp) }
        if (expanded) {
            if (warning.description.isNotBlank()) Text(warning.description, lineHeight = 23.sp)
            if (warning.instruction.isNotBlank()) {
                Text(stringResource(R.string.warnings_issuer_advice), fontWeight = FontWeight.SemiBold)
                Text(warning.instruction, lineHeight = 23.sp)
            }
            TextButton(onClick = { onOpenSource(warning.webUrl) }) {
                Text(stringResource(R.string.warnings_open_warning))
            }
        }
    }
}

private fun WeatherWarningSeverity.labelResource(): Int = when (this) {
    WeatherWarningSeverity.UNKNOWN -> R.string.warnings_severity_unknown
    WeatherWarningSeverity.MINOR -> R.string.warnings_severity_minor
    WeatherWarningSeverity.MODERATE -> R.string.warnings_severity_moderate
    WeatherWarningSeverity.SEVERE -> R.string.warnings_severity_severe
    WeatherWarningSeverity.EXTREME -> R.string.warnings_severity_extreme
}
