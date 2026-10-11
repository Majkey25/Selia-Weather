package cz.majkey.pocasicesko.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
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
) {
    val configuration = LocalConfiguration.current
    val languageTag = configuration.locales[0]?.toLanguageTag()
    val navigate by rememberUpdatedState(onNavigate)
    val density = LocalDensity.current
    val swipeThreshold = with(density) { 48.dp.toPx() }
    var headerInset by remember { mutableIntStateOf(64) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(
                bottom = if (fullscreen) 0.dp else 84.dp,
            ),
    ) {
        ChmiWebScreen(
            localizedRadarUrl(languageTag, location.latitude, location.longitude, location.isInCzechia(), timezone),
            active = active,
            topInsetDp = headerInset,
        )
        Row(Modifier.fillMaxWidth().onSizeChanged {
            headerInset = kotlin.math.ceil(it.height / density.density).toInt()
        }.padding(8.dp).testTag("radar-header").then(
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
        ), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.weight(1f, fill = false)) {
                Text(location.name, modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
                IconButton(onClick = onToggleFullscreen, modifier = Modifier.size(48.dp)) {
                    Icon(if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                        contentDescription = stringResource(if (fullscreen) R.string.radar_exit_fullscreen else R.string.radar_fullscreen))
                }
            }
        }
    }
}
