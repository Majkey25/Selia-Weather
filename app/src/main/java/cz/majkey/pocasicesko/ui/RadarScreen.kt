package cz.majkey.pocasicesko.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.webkit.WebViewAssetLoader
import cz.majkey.pocasicesko.BuildConfig
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.locale.normalizeLanguageTag
import java.net.URLEncoder

const val RADAR_APP_URL = "https://appassets.androidplatform.net/assets/radar.html"

internal fun localizedRadarUrl(
    languageTag: String?,
    latitude: Double,
    longitude: Double,
    chmiDetail: Boolean,
    timezone: String? = null,
): String {
    require(latitude.isFinite() && latitude in -90.0..90.0)
    require(longitude.isFinite() && longitude in -180.0..180.0)
    val language = normalizeLanguageTag(languageTag).ifEmpty { "en" }
    return "$RADAR_APP_URL?lang=$language&lat=$latitude&lon=$longitude" +
        "&chmi=${if (chmiDetail) 1 else 0}" +
        (timezone?.let { "&tz=${URLEncoder.encode(it, "UTF-8")}" } ?: "")
}

@Composable
@SuppressLint("SetJavaScriptEnabled")
fun ChmiWebScreen(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val radarUnavailable = stringResource(R.string.radar_unavailable)
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loading by remember(url) { mutableStateOf(true) }
    var error by remember(url) { mutableStateOf<String?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF071018)),
    ) {
        key(url) {
            AndroidView(
                factory = { viewContext ->
                    if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
                    val assets = WebViewAssetLoader.Builder()
                        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(viewContext))
                        .build()
                    WebView(viewContext).apply web@{
                        webView = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.userAgentString =
                            "${settings.userAgentString} Selia-Weather/${BuildConfig.VERSION_NAME}"
                        CookieManager.getInstance().apply {
                            setAcceptCookie(false)
                            setAcceptThirdPartyCookies(this@web, false)
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest,
                            ): WebResourceResponse? = assets.shouldInterceptRequest(request.url)

                            override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: Bitmap?) {
                                loading = true
                                error = null
                            }

                            override fun onPageCommitVisible(view: WebView?, pageUrl: String?) {
                                loading = false
                            }

                            override fun onPageFinished(view: WebView?, pageUrl: String?) {
                                loading = false
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                webError: WebResourceError?,
                            ) {
                                if (request?.isForMainFrame == true) {
                                    loading = false
                                    error = webError?.description?.toString() ?: radarUnavailable
                                }
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                if (request?.isForMainFrame != true) return true
                                val uri = request.url
                                if (uri.scheme == "https") {
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
                    if (webView === view) webView = null
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

        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(36.dp),
                color = Color(0xFF6DD3EA),
            )
        }
        error?.let { message ->
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = message, color = Color(0xFFFFB4AB))
                TextButton(onClick = {
                    error = null
                    webView?.reload()
                }) {
                    Text(stringResource(R.string.radar_retry))
                }
            }
        }
    }
}
