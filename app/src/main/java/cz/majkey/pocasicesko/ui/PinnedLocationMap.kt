package cz.majkey.pocasicesko.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import cz.majkey.pocasicesko.BuildConfig
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.data.CzechLocation

@Composable
@SuppressLint("SetJavaScriptEnabled")
internal fun PinnedLocationMap(
    initialLocation: CzechLocation,
    onCoordinates: (MapCoordinates) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val unavailable = stringResource(R.string.pinned_location_map_unavailable)
    val url = remember(initialLocation.latitude, initialLocation.longitude) {
        "$LOCATION_PICKER_URL?lat=${initialLocation.latitude}&lon=${initialLocation.longitude}"
    }
    var loading by remember(url) { mutableStateOf(true) }
    var error by remember(url) { mutableStateOf<String?>(null) }

    Box(modifier.background(Color(0xFF071018)), contentAlignment = Alignment.Center) {
        key(url) {
            AndroidView(
                factory = { viewContext ->
                    WebView(viewContext).apply web@{
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = false
                        settings.allowFileAccess = true
                        settings.allowContentAccess = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.setSupportZoom(false)
                        settings.userAgentString =
                            "${settings.userAgentString} Selia-Weather/${BuildConfig.VERSION_NAME}"
                        CookieManager.getInstance().apply {
                            setAcceptCookie(false)
                            setAcceptThirdPartyCookies(this@web, false)
                        }
                        addJavascriptInterface(
                            LocationBridge { latitude, longitude ->
                                post { onCoordinates(MapCoordinates(latitude, longitude)) }
                            },
                            LOCATION_BRIDGE,
                        )
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: Bitmap?) {
                                loading = true
                                error = null
                            }

                            override fun onPageCommitVisible(view: WebView?, pageUrl: String?) {
                                loading = false
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                webError: WebResourceError?,
                            ) {
                                if (request?.isForMainFrame == true) {
                                    loading = false
                                    error = webError?.description?.toString() ?: unavailable
                                }
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                if (request?.isForMainFrame != true) return true
                                val uri = request.url
                                if (uri.host == "www.openstreetmap.org" && uri.scheme == "https") {
                                    try {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                    } catch (_: ActivityNotFoundException) {
                                        Toast.makeText(context, R.string.support_unavailable, Toast.LENGTH_LONG).show()
                                    }
                                }
                                return true
                            }
                        }
                        loadUrl(url)
                    }
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { view ->
                    view.removeJavascriptInterface(LOCATION_BRIDGE)
                    view.stopLoading()
                    view.destroy()
                },
                update = { view ->
                    if (lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) {
                        view.onResume()
                    } else {
                        view.onPause()
                    }
                },
            )
        }
        if (loading) CircularProgressIndicator(color = Color(0xFF83D6E8))
        error?.let {
            Text(
                text = it,
                modifier = Modifier.padding(20.dp),
                color = Color.White.copy(alpha = 0.72f),
            )
        }
    }
}

private class LocationBridge(private val onCoordinates: (Double, Double) -> Unit) {
    @JavascriptInterface
    fun onLocationSelected(latitude: Double, longitude: Double) {
        if (latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0
        ) {
            onCoordinates(latitude, longitude)
        }
    }
}

private const val LOCATION_PICKER_URL = "file:///android_asset/location_picker.html"
private const val LOCATION_BRIDGE = "LocationBridge"
