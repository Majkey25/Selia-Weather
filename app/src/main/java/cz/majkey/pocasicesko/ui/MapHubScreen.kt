package cz.majkey.pocasicesko.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.data.CzechLocation
import cz.majkey.pocasicesko.data.isInCzechia

@Composable
internal fun MapHubScreen(
    location: CzechLocation,
    padding: PaddingValues,
    fullscreen: Boolean,
    timezone: String? = null,
    onToggleFullscreen: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val languageTag = configuration.locales[0]?.toLanguageTag()
    val compact = configuration.screenHeightDp < 480
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(
                start = if (fullscreen) 0.dp else 8.dp,
                top = if (fullscreen) 0.dp else 4.dp,
                end = if (fullscreen) 0.dp else 8.dp,
                bottom = if (fullscreen) 0.dp else 84.dp,
            ),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(stringResource(R.string.radar_title), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                if (!compact) Text(location.name, fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onToggleFullscreen, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                    contentDescription = stringResource(if (fullscreen) R.string.radar_exit_fullscreen else R.string.radar_fullscreen))
            }
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            color = Color(0xFF0B1117),
            shape = RoundedCornerShape(if (fullscreen) 0.dp else 18.dp),
            border = if (fullscreen) null else BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
        ) {
            ChmiWebScreen(
                localizedRadarUrl(
                    languageTag,
                    location.latitude,
                    location.longitude,
                    location.isInCzechia(),
                    timezone,
                ),
            )
        }
    }
}
