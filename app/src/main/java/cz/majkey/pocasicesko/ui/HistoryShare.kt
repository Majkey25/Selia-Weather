package cz.majkey.pocasicesko.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.data.HistoryArchive
import cz.majkey.pocasicesko.data.historyCsv
import cz.majkey.pocasicesko.locale.AppLocale
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun createHistoryShareIntent(
    context: Context,
    archive: HistoryArchive,
    chooserTitle: String,
    question: String = "",
    range: ClosedRange<LocalDate>? = null,
    target: HistoryAiTarget? = null,
): Intent = withContext(Dispatchers.IO) {
    val file = createHistoryShareFile(context.cacheDir, historyCsv(archive))
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val title = context.getString(R.string.history_title)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        clipData = ClipData.newUri(context.contentResolver, title, uri)
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, historySharePrompt(archive, question, range, AppLocale.languageTag(context)))
        putExtra(Intent.EXTRA_SUBJECT, "$title: ${archive.location.name}")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    if (target != null) send.setComponent(target.component)
    else Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
