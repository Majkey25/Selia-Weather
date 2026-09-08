package cz.majkey.pocasicesko.ui

import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import cz.majkey.pocasicesko.R

internal const val SUPPORT_URL = "https://www.buymeacoffee.com/majkey"
internal const val OPEN_METEO_URL = "https://open-meteo.com/"

internal fun supportIntent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(SUPPORT_URL))

internal fun weatherDataIntent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(OPEN_METEO_URL))

enum class LegalPage(@param:StringRes val labelResource: Int, val path: String) {
    PRIVACY(R.string.legal_privacy, ""),
    TERMS(R.string.legal_terms, "terms.html"),
    REFUNDS(R.string.legal_refunds, "refunds.html"),
    COOKIES(R.string.legal_cookies, "cookies.html");

    val url: String get() = "https://majkey25.github.io/Selia-Weather/$path"
}

internal fun legalIntent(page: LegalPage): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(page.url))
