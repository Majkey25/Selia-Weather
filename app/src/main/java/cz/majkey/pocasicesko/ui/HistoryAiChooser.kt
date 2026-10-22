package cz.majkey.pocasicesko.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import cz.majkey.pocasicesko.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class HistoryAiTarget(val name: String, val component: ComponentName, val icon: Drawable)

internal suspend fun historyAiTargets(context: Context): List<HistoryAiTarget> = withContext(Dispatchers.IO) {
    val manager = context.packageManager
    HISTORY_AI_PACKAGES.mapNotNull { packageName ->
        val intent = Intent(Intent.ACTION_SEND).setType("text/csv").setPackage(packageName)
        val resolved = manager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).firstOrNull { result ->
            val info = result.activityInfo
            info.packageName == packageName && info.exported && info.enabled && info.applicationInfo.enabled &&
                (info.permission == null || context.checkSelfPermission(info.permission) == PackageManager.PERMISSION_GRANTED)
        } ?: return@mapNotNull null
        HistoryAiTarget(resolved.loadLabel(manager).toString(), ComponentName(packageName, resolved.activityInfo.name), resolved.loadIcon(manager))
    }
}

@Composable
internal fun HistoryAiChooserDialog(
    targets: List<HistoryAiTarget>,
    onSelect: (HistoryAiTarget) -> Unit,
    onExport: () -> Unit,
    onDismiss: () -> Unit,
) {
    val maximumListHeight = LocalConfiguration.current.screenHeightDp.dp / 2
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_ai), contentDescription = null) },
        title = { Text(stringResource(R.string.history_choose_ai_title)) },
        text = {
            Column(Modifier.heightIn(max = maximumListHeight).verticalScroll(rememberScrollState())) {
                Text(stringResource(if (targets.isEmpty()) R.string.history_ai_no_apps else R.string.history_ai_apps_description))
                targets.forEach { target ->
                    TextButton(onClick = { onSelect(target) }, modifier = Modifier.fillMaxWidth()) {
                        val bitmap = remember(target) { target.icon.toBitmap(32, 32).asImageBitmap() }
                        Image(bitmap, contentDescription = null, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(target.name, modifier = Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onExport) { Text(stringResource(R.string.history_export_csv)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

private val HISTORY_AI_PACKAGES = listOf(
    "com.openai.chatgpt",
    "com.google.android.apps.bard",
    "com.anthropic.claude",
    "ai.x.grok",
)
