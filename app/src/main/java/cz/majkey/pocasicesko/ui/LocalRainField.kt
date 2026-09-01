package cz.majkey.pocasicesko.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.data.PrecipitationField
import cz.majkey.pocasicesko.data.PrecipitationFieldCell
import cz.majkey.pocasicesko.data.PrecipitationKind
import cz.majkey.pocasicesko.units.WeatherUnitFormatter
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.hypot

internal data class RainFieldLabels(
    val centre: String,
    val unavailable: String,
    val dry: String,
    val rain: String,
    val snow: String,
    val mixed: String,
    val probability: String,
    val agreement: String,
    val models: String,
    val range: String,
    val north: String,
    val east: String,
    val south: String,
    val west: String,
)

@Composable
internal fun LocalRainField(
    field: PrecipitationField,
    timezone: String,
    units: WeatherUnitFormatter,
    modifier: Modifier = Modifier,
) {
    var frameIndex by remember(field) { mutableIntStateOf(0) }
    var cellIndex by remember(field) { mutableIntStateOf(CENTRE_INDEX) }
    val frame = field.frames[frameIndex]
    val labels = rainFieldLabels()
    val formatter = remember(timezone) {
        DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of(timezone))
    }
    val timeLabel = formatter.format(frame.validTime)
    Column(modifier) {
        RainFieldGrid(
            cells = frame.cells,
            selectedIndex = cellIndex,
            timeLabel = timeLabel,
            units = units,
            labels = labels,
            onSelect = { cellIndex = it },
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 12.dp),
        ) {
            itemsIndexed(field.frames) { index, item ->
                val selected = index == frameIndex
                Surface(
                    onClick = {
                        frameIndex = index
                        cellIndex = CENTRE_INDEX
                    },
                    color = if (selected) Color(0xFF2E6474) else Color(0xFF172731),
                    shape = RoundedCornerShape(18.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        Color.White.copy(alpha = if (selected) 0.20f else 0.08f),
                    ),
                ) {
                    Text(
                        formatter.format(item.validTime),
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
        Text(
            rainFieldCellDescription(frame.cells[cellIndex], timeLabel, units, labels),
            color = Color.White.copy(alpha = 0.72f),
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 12.dp),
        )
        RainFieldLegend(units)
    }
}

@Composable
private fun RainFieldGrid(
    cells: List<PrecipitationFieldCell>,
    selectedIndex: Int,
    timeLabel: String,
    units: WeatherUnitFormatter,
    labels: RainFieldLabels,
    onSelect: (Int) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(294.dp),
    ) {
        Text(labels.north, Modifier.align(Alignment.TopCenter), color = DIRECTION_COLOR, fontSize = 11.sp)
        Text(labels.south, Modifier.align(Alignment.BottomCenter), color = DIRECTION_COLOR, fontSize = 11.sp)
        Text(labels.west, Modifier.align(Alignment.CenterStart), color = DIRECTION_COLOR, fontSize = 11.sp)
        Text(labels.east, Modifier.align(Alignment.CenterEnd), color = DIRECTION_COLOR, fontSize = 11.sp)
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            cells.chunked(GRID_SIZE).forEachIndexed { row, rowCells ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    rowCells.forEachIndexed { column, cell ->
                        val index = row * GRID_SIZE + column
                        RainFieldCell(
                            cell = cell,
                            selected = index == selectedIndex,
                            description = rainFieldCellDescription(cell, timeLabel, units, labels),
                            onClick = { onSelect(index) },
                        )
                    }
                }
            }
        }
        Text(
            stringResource(R.string.rain_field_radius, units.distance(FIELD_RADIUS_KM)),
            color = DIRECTION_COLOR,
            fontSize = 10.sp,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 18.dp, start = 24.dp),
        )
    }
}

@Composable
private fun RainFieldCell(
    cell: PrecipitationFieldCell,
    selected: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(shape)
            .background(rainFieldColor(cell))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) Color.White else Color.White.copy(alpha = 0.12f),
                shape = shape,
            )
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        when {
            cell.kind == PrecipitationKind.UNAVAILABLE -> Text("╱", color = Color.White.copy(alpha = 0.55f))
            cell.kind == PrecipitationKind.SNOW || cell.kind == PrecipitationKind.MIXED -> {
                Text("❄", color = Color.White, fontSize = 15.sp)
            }
        }
        if (cell.point.offsetEastKm == 0.0 && cell.point.offsetNorthKm == 0.0) {
            Canvas(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(5.dp)
                    .size(6.dp),
            ) { drawCircle(Color.White) }
        }
    }
}

@Composable
private fun RainFieldLegend(units: WeatherUnitFormatter) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        listOf(
            0.1 to Color(0xFF4EC8E0),
            0.5 to Color(0xFF3987E3),
            2.0 to Color(0xFF7657D6),
            5.0 to Color(0xFFC441B8),
        ).forEach { (amount, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(
                    Modifier
                        .size(8.dp)
                        .background(color, RoundedCornerShape(3.dp)),
                )
                Spacer(Modifier.width(4.dp))
                Text(units.precipitation(amount), color = DIRECTION_COLOR, fontSize = 9.sp)
            }
        }
    }
}

internal fun rainFieldColor(cell: PrecipitationFieldCell): Color = when {
    cell.kind == PrecipitationKind.UNAVAILABLE || cell.kind == PrecipitationKind.DRY -> Color.Transparent
    requireNotNull(cell.precipitationMm) < 0.5 -> Color(0xFF4EC8E0)
    cell.precipitationMm < 2.0 -> Color(0xFF3987E3)
    cell.precipitationMm < 5.0 -> Color(0xFF7657D6)
    else -> Color(0xFFC441B8)
}

internal fun rainFieldCellDescription(
    cell: PrecipitationFieldCell,
    timeLabel: String,
    units: WeatherUnitFormatter,
    labels: RainFieldLabels,
): String {
    val place = rainFieldDirection(cell, labels)
    if (cell.kind == PrecipitationKind.UNAVAILABLE) return "$place · $timeLabel · ${labels.unavailable}"
    val distance = if (place == labels.centre) "" else " · ${units.distance(cell.distanceKm())}"
    val kind = when (cell.kind) {
        PrecipitationKind.DRY -> labels.dry
        PrecipitationKind.RAIN -> labels.rain
        PrecipitationKind.SNOW -> labels.snow
        PrecipitationKind.MIXED -> labels.mixed
        PrecipitationKind.UNAVAILABLE -> labels.unavailable
    }
    return "$place$distance · $timeLabel · ${units.precipitation(requireNotNull(cell.precipitationMm))}" +
        " · ${labels.range} ${units.precipitation(requireNotNull(cell.minimumMm))}–" +
        units.precipitation(requireNotNull(cell.maximumMm)) +
        " · $kind · ${labels.probability} ${cell.probabilityPercent}%" +
        " · ${labels.agreement} ${cell.agreementPercent}% · ${cell.contributorCount} ${labels.models}"
}

private fun rainFieldDirection(cell: PrecipitationFieldCell, labels: RainFieldLabels): String {
    val east = cell.point.offsetEastKm
    val north = cell.point.offsetNorthKm
    if (east == 0.0 && north == 0.0) return labels.centre
    val vertical = when {
        north > 0 -> labels.north
        north < 0 -> labels.south
        else -> ""
    }
    val horizontal = when {
        east > 0 -> labels.east
        east < 0 -> labels.west
        else -> ""
    }
    return vertical + horizontal
}

private fun PrecipitationFieldCell.distanceKm(): Double =
    hypot(point.offsetEastKm, point.offsetNorthKm)

@Composable
private fun rainFieldLabels() = RainFieldLabels(
    centre = stringResource(R.string.rain_field_centre),
    unavailable = stringResource(R.string.rain_field_unavailable),
    dry = stringResource(R.string.rain_field_dry),
    rain = stringResource(R.string.rain_field_rain),
    snow = stringResource(R.string.rain_field_snow),
    mixed = stringResource(R.string.rain_field_mixed),
    probability = stringResource(R.string.rain_field_probability),
    agreement = stringResource(R.string.rain_field_agreement),
    models = stringResource(R.string.rain_field_models),
    range = stringResource(R.string.rain_field_range),
    north = stringResource(R.string.rain_field_north),
    east = stringResource(R.string.rain_field_east),
    south = stringResource(R.string.rain_field_south),
    west = stringResource(R.string.rain_field_west),
)

private const val GRID_SIZE = 5
private const val CENTRE_INDEX = 12
private const val FIELD_RADIUS_KM = 20.0
private val DIRECTION_COLOR = Color.White.copy(alpha = 0.48f)
