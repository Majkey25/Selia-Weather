package cz.majkey.pocasicesko.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
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
    active: Boolean = true,
    onNavigate: (Destination) -> Unit = {},
    bottomInsetDp: Int = 76,
) {
    val configuration = LocalConfiguration.current
    val languageTag = configuration.locales[0]?.toLanguageTag()
    val navigate by rememberUpdatedState(onNavigate)
    val density = LocalDensity.current
    val swipeThreshold = with(density) { 48.dp.toPx() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        Row(Modifier.fillMaxWidth().padding(8.dp).testTag("radar-header").then(
            if (fullscreen) Modifier else Modifier.pointerInput(swipeThreshold) {
                var distance = 0f
                detectHorizontalDragGestures(
                    onDragStart = { distance = 0f },
                    onDragCancel = { distance = 0f },
                    onDragEnd = {
                        when {
                            distance <= -swipeThreshold -> navigate(Destination.AI)
                            distance >= swipeThreshold -> navigate(Destination.WEATHER)
                        }
                    },
                ) { change, amount -> change.consume(); distance += amount }
            },
        ), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(location.name, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface) {
                IconButton(onClick = onToggleFullscreen, modifier = Modifier.size(48.dp)) {
                    Icon(if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                        contentDescription = stringResource(if (fullscreen) R.string.radar_exit_fullscreen else R.string.radar_fullscreen))
                }
            }
        }
        ChmiWebScreen(
            localizedRadarUrl(languageTag, location.latitude, location.longitude, location.isInCzechia()),
            modifier = Modifier.weight(1f),
            active = active,
            topInsetDp = 0,
            bottomInsetDp = if (fullscreen) 0 else bottomInsetDp,
            timezone = timezone,
        )
    }
}
