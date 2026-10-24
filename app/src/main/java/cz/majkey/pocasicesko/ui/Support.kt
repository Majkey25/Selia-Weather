package cz.majkey.pocasicesko.ui

import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import cz.majkey.pocasicesko.BuildConfig
import cz.majkey.pocasicesko.R
import java.util.Locale

internal const val SUPPORT_URL = BuildConfig.EXTERNAL_DONATION_URL
private const val LEGAL_ROOT = "https://majkey25.github.io/Selia-Weather/"
internal val WEATHER_DATA_URL = if (BuildConfig.DISTRIBUTION_CHANNEL == "play") {
    "${LEGAL_ROOT}play.html#data-sources"
} else {
    "${LEGAL_ROOT}terms.html#data-sources"
}

internal fun supportIntent(): Intent? = if (SUPPORT_URL.isEmpty()) null
    else Intent(Intent.ACTION_VIEW, Uri.parse(SUPPORT_URL))

internal fun weatherDataIntent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(WEATHER_DATA_URL))

enum class LegalPage(@param:StringRes val labelResource: Int, val path: String) {
    PRIVACY(R.string.legal_privacy, ""),
    TERMS(R.string.legal_terms, "terms.html"),
    REFUNDS(R.string.legal_refunds, "refunds.html"),
    COOKIES(R.string.legal_cookies, "cookies.html");

    val url: String get() = if (BuildConfig.DISTRIBUTION_CHANNEL == "play") {
        "${LEGAL_ROOT}play.html#${name.lowercase(Locale.ROOT)}"
    } else {
        "$LEGAL_ROOT$path"
    }
}

internal fun legalIntent(page: LegalPage): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(page.url))
