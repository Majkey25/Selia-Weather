package cz.majkey.pocasicesko.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.majkey.pocasicesko.BuildConfig
import cz.majkey.pocasicesko.R
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun PinnedLocationMap(
    onCoordinates: (MapCoordinates) -> Unit,
    modifier: Modifier = Modifier,
) {
    val result by produceState<Result<Bitmap>?>(null) {
        value = withContext(Dispatchers.IO) { runCatching(::downloadLocationMap) }
    }
    val bitmap = result?.getOrNull()
    var pin by remember(bitmap) { mutableStateOf<Offset?>(null) }

    Box(modifier = modifier.background(Color(0xFF061018)), contentAlignment = Alignment.Center) {
        when {
            bitmap != null -> {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(bitmap) {
                            detectTapGestures { position ->
                                val fractions = imagePositionToMapFractions(
                                    position.x.toDouble(),
                                    position.y.toDouble(),
                                    size.width.toDouble(),
                                    size.height.toDouble(),
                                    bitmap.width,
                                    bitmap.height,
                                ) ?: return@detectTapGestures
                                val coordinates = coordinatesFromMapPosition(
                                    fractions.first,
                                    fractions.second,
                                ) ?: return@detectTapGestures
                                pin = position
                                onCoordinates(coordinates)
                            }
                        },
                )
                pin?.let { center ->
                    Canvas(Modifier.fillMaxSize()) {
                        drawCircle(Color.White, radius = 10.dp.toPx(), center = center)
                        drawCircle(Color(0xFF47C7E2), radius = 6.dp.toPx(), center = center)
                    }
                }
                Text(
                    stringResource(R.string.map_attribution),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .background(Color(0xCC071018))
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 10.sp,
                )
            }
            result == null -> CircularProgressIndicator(color = Color(0xFF83D6E8))
            else -> Text(
                stringResource(R.string.pinned_location_map_unavailable),
                modifier = Modifier.padding(20.dp),
                color = Color.White.copy(alpha = 0.72f),
            )
        }
    }
}

private fun downloadLocationMap(): Bitmap {
    val connection = URL(MAP_URL).openConnection() as HttpURLConnection
    return try {
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("User-Agent", USER_AGENT)
        if (connection.responseCode !in 200..299) throw IOException("Map HTTP ${connection.responseCode}")
        BitmapFactory.decodeStream(
            connection.inputStream,
            null,
            BitmapFactory.Options().apply { inSampleSize = 2 },
        ) ?: throw IOException("Map image is invalid")
    } finally {
        connection.disconnect()
    }
}

private const val MAP_URL = "https://produkty.chmi.cz/radar/und/pacz2gmaps6.und3.png"
private val USER_AGENT =
    "ALADIN-weather/${BuildConfig.VERSION_NAME} (Android; https://github.com/Majkey25/ALADIN-weather)"
