package cz.majkey.pocasicesko.ui

import android.content.Intent
import android.net.Uri

internal const val SUPPORT_URL = "https://www.buymeacoffee.com/majkey"

internal fun supportIntent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(SUPPORT_URL))
