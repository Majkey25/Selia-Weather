package cz.majkey.pocasicesko.ui

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

@Synchronized
internal fun createHistoryShareFile(cacheDirectory: File, csv: String): File {
    val directory = File(cacheDirectory, "history_exports")
    if (!directory.isDirectory && !directory.mkdirs()) {
        throw IOException("History export directory could not be created.")
    }
    val file = File.createTempFile(HISTORY_SHARE_PREFIX, ".csv", directory)
    try {
        file.writeText(csv, Charsets.UTF_8)
        val previous = directory.listFiles { candidate ->
            candidate != file && candidate.isFile &&
                ((candidate.name.startsWith(HISTORY_SHARE_PREFIX) && candidate.extension == "csv") ||
                    candidate.name == "selia_vetra_history.csv")
        } ?: throw IOException("History exports could not be listed.")
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
        previous.sortedByDescending(File::lastModified).forEachIndexed { index, candidate ->
            if (index >= MAX_HISTORY_SHARE_FILES - 1 || candidate.lastModified() < cutoff) {
                if (!candidate.delete() && candidate.exists()) {
                    throw IOException("Expired history export could not be removed.")
                }
            }
        }
        return file
    } catch (error: IOException) {
        if (!file.delete() && file.exists()) {
            error.addSuppressed(IOException("Incomplete history export could not be removed."))
        }
        throw error
    }
}

internal const val MAX_HISTORY_SHARE_FILES = 12
private const val HISTORY_SHARE_PREFIX = "selia_weather_history_"
