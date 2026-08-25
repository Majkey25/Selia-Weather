package cz.majkey.pocasicesko.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.data.CzechLocation
import java.util.Locale

@Composable
internal fun PinnedLocationPicker(
    initialLocation: CzechLocation,
    saveError: String?,
    onBack: () -> Unit,
    onSave: (CzechLocation) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var latitude by remember { mutableStateOf(formatCoordinate(initialLocation.latitude)) }
    var longitude by remember { mutableStateOf(formatCoordinate(initialLocation.longitude)) }
    val location = pinnedLocationOrNull(name, latitude, longitude)
    val mapDescription = stringResource(R.string.pinned_location_map_description)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        Text(stringResource(R.string.pinned_location_title), fontSize = 24.sp)
        Text(
            stringResource(R.string.map_pick_hint),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
        PinnedLocationMap(
            onCoordinates = { point ->
                latitude = formatCoordinate(point.latitude)
                longitude = formatCoordinate(point.longitude)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .semantics { contentDescription = mapDescription },
        )
        OutlinedTextField(
            value = name,
            onValueChange = { if (it.length <= 60) name = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.location_name)) },
            singleLine = true,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CoordinateField(latitude, { latitude = it }, R.string.latitude, Modifier.weight(1f))
            CoordinateField(longitude, { longitude = it }, R.string.longitude, Modifier.weight(1f))
        }
        if (name.isNotEmpty() && location == null) {
            Text(stringResource(R.string.pinned_location_invalid), color = MaterialTheme.colorScheme.error)
        }
        saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text(
            stringResource(R.string.pinned_location_accuracy),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
        Button(onClick = { location?.let(onSave) }, enabled = location != null, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.save_and_use))
        }
    }
}

@Composable
private fun CoordinateField(
    value: String,
    onValueChange: (String) -> Unit,
    label: Int,
    modifier: Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(stringResource(label)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
    )
}

private fun formatCoordinate(value: Double): String = String.format(Locale.US, "%.6f", value)
