package cz.majkey.pocasicesko.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import cz.majkey.pocasicesko.data.HistoryArchive
import cz.majkey.pocasicesko.data.historyChatPrompt
import cz.majkey.pocasicesko.data.historyCsv
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun createHistoryShareIntent(
    context: Context,
    archive: HistoryArchive,
    chooserTitle: String,
): Intent = withContext(Dispatchers.IO) {
    val directory = File(context.cacheDir, "history_exports")
    if (!directory.isDirectory && !directory.mkdirs()) {
        throw IOException("History export directory could not be created.")
    }
    val file = File(directory, "selia_vetra_history.csv")
    file.writeText(historyCsv(archive), Charsets.UTF_8)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        clipData = ClipData.newUri(context.contentResolver, "Weather history", uri)
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, historyChatPrompt(archive))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
