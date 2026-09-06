package cz.majkey.pocasicesko.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cz.majkey.pocasicesko.R
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun ColorInput(value: String, label: String, onValueChange: (String) -> Unit) {
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val description = stringResource(R.string.widget_choose_color, label)
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(widgetColorInput(it)) },
        label = { Text(label) },
        leadingIcon = {
            IconButton(onClick = { showPicker = true }, modifier = Modifier.semantics { contentDescription = description }) {
                ColorSwatch(value)
            }
        },
        isError = !isWidgetColor(value),
        supportingText = { Text(stringResource(if (isWidgetColor(value)) R.string.widget_hex_format else R.string.widget_invalid_hex)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    if (showPicker) WidgetColorDialog(value, label, onDismiss = { showPicker = false }) {
        onValueChange(it)
        showPicker = false
    }
}

@Composable
private fun ColorSwatch(value: String) {
    Box(Modifier.size(30.dp).clip(CircleShape)
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .background(Color(widgetArgbOrNull(value) ?: 0))
        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
}

@Composable
private fun WidgetColorDialog(value: String, label: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var draft by rememberSaveable { mutableStateOf(widgetHexOrNull(value) ?: "#FFFFFFFF") }
    val argb = widgetArgbOrNull(draft) ?: widgetArgbOrNull(value) ?: -1
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label) },
        text = {
            Column(Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.6f)
                .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = widgetColorInput(it) },
                    label = { Text("HEX") },
                    leadingIcon = { ColorSwatch(draft) },
                    supportingText = { Text(stringResource(R.string.widget_hex_format)) },
                    isError = !isWidgetColor(draft), singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    COLOR_SWATCHES.forEach { color ->
                        IconButton(onClick = { draft = color }, modifier = Modifier.semantics { contentDescription = color }) {
                            ColorSwatch(color)
                        }
                    }
                }
                listOf(R.string.widget_red to 16, R.string.widget_green to 8, R.string.widget_blue to 0, R.string.widget_alpha to 24).forEach { (resource, shift) ->
                    val channelLabel = stringResource(resource)
                    val channel = (argb ushr shift) and 255
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(channelLabel)
                        Text(channel.toString())
                    }
                    Slider(
                        value = channel.toFloat(),
                        onValueChange = { draft = widgetColorChannel(String.format(Locale.ROOT, "#%08X", argb), shift, it.roundToInt()) },
                        valueRange = 0f..255f,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = channelLabel },
                    )
                }
            }
        },
        confirmButton = { TextButton(enabled = isWidgetColor(draft), onClick = { onConfirm(requireNotNull(widgetHexOrNull(draft))) }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

private val COLOR_SWATCHES = listOf("#FFFFFFFF", "#FF000000", "#FF173042", "#FF28758D", "#FF66C9DF", "#FF705CF0", "#FFDB5346", "#FFE4A748")
