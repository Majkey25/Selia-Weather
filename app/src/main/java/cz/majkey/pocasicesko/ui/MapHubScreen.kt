package cz.majkey.pocasicesko.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.data.CzechLocation
import cz.majkey.pocasicesko.data.isInCzechia

@Composable
internal fun MapHubScreen(
    location: CzechLocation,
    padding: PaddingValues,
) {
    val languageTag = LocalConfiguration.current.locales[0]?.toLanguageTag()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(start = 14.dp, top = 16.dp, end = 14.dp, bottom = 96.dp),
    ) {
        Text(stringResource(R.string.radar_title), fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
        Text(
            stringResource(R.string.radar_subtitle),
            color = Color.White.copy(alpha = 0.62f),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 3.dp, bottom = 14.dp),
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(RADAR_CARD_ASPECT_RATIO),
            color = Color(0xFF0B1117),
            shape = RoundedCornerShape(26.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
        ) {
            ChmiWebScreen(
                localizedRadarUrl(
                    languageTag,
                    location.latitude,
                    location.longitude,
                    location.isInCzechia(),
                ),
            )
        }
        Text(
            stringResource(R.string.radar_footer),
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 5.dp, top = 7.dp),
        )
    }
}

private const val RADAR_CARD_ASPECT_RATIO = 0.9f
