package cz.majkey.pocasicesko.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
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
): Intent = withContext(Dispatchers.IO) {
    val file = createHistoryShareFile(context.cacheDir, historyCsv(archive))
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        clipData = ClipData.newUri(context.contentResolver, "Weather history", uri)
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, historySharePrompt(archive, question, range, AppLocale.languageTag(context)))
        putExtra(Intent.EXTRA_SUBJECT, "Weather history: ${archive.location.name}")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
