package cz.majkey.pocasicesko.ui

import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HistoryShareFileTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun eachLocationShareKeepsItsOriginalContentAndOpaqueFilename() {
        val first = createHistoryShareFile(temporary.root, "location,precipitation\nPraha,12.5\n")
        val second = createHistoryShareFile(temporary.root, "location,precipitation\nTokyo,40.2\n")

        assertNotEquals(first, second)
        assertEquals("location,precipitation\nPraha,12.5\n", first.readText(Charsets.UTF_8))
        assertEquals("location,precipitation\nTokyo,40.2\n", second.readText(Charsets.UTF_8))
        listOf(first, second).forEach { file ->
            assertTrue(file.name.matches(Regex("selia_weather_history_[0-9]+\\.csv")))
            assertEquals(File(temporary.root, "history_exports"), file.parentFile)
        }
    }

    @Test
    fun keepsNewestExportsWithoutDeletingForeignFilesOrDirectories() {
        val directory = temporary.newFolder("history_exports")
        val unrelated = File(directory, "notes.csv").apply { writeText("keep") }
        val legacy = File(directory, "selia_vetra_history.csv").apply { writeText("legacy share") }
        val folder = File(directory, "selia_weather_history_folder.csv").apply { mkdir() }
        createHistoryShareFile(temporary.root, "first")
        assertEquals("legacy share", legacy.readText())
        var newest: File? = null
        repeat(MAX_HISTORY_SHARE_FILES + 5) { index ->
            newest = createHistoryShareFile(temporary.root, "export $index")
        }

        val exports = directory.listFiles().orEmpty().filter {
            it.isFile && (it.name.startsWith("selia_weather_history_") || it.name == "selia_vetra_history.csv")
        }
        assertEquals(MAX_HISTORY_SHARE_FILES, exports.size)
        assertEquals("export ${MAX_HISTORY_SHARE_FILES + 4}", requireNotNull(newest).readText())
        assertEquals("keep", unrelated.readText())
        assertTrue(folder.isDirectory)
    }

    @Test
    fun expiresOnlyOldOwnExports() {
        val old = createHistoryShareFile(temporary.root, "old")
        val recent = createHistoryShareFile(temporary.root, "recent")
        val legacy = File(old.parentFile, "selia_vetra_history.csv").apply { writeText("legacy share") }
        assertTrue(old.setLastModified(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(8)))
        assertTrue(legacy.setLastModified(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(8)))
        val fresh = createHistoryShareFile(temporary.root, "fresh")

        assertFalse(old.exists())
        assertFalse(legacy.exists())
        assertEquals("recent", recent.readText())
        assertEquals("fresh", fresh.readText())
    }

    @Test
    fun invalidDirectoryFailsWithoutOverwritingExistingFile() {
        val collision = temporary.newFile("history_exports").apply { writeText("keep") }
        assertThrows(IOException::class.java) { createHistoryShareFile(temporary.root, "new") }
        assertEquals("keep", collision.readText())
    }

    @Test
    fun concurrentSharesNeverOverwriteEachOther() {
        val worker = Executors.newFixedThreadPool(4)
        try {
            val files = worker.invokeAll((0 until MAX_HISTORY_SHARE_FILES).map { index ->
                Callable { createHistoryShareFile(temporary.root, "export $index") }
            }).map { it.get() }
            assertEquals(MAX_HISTORY_SHARE_FILES, files.distinct().size)
            files.forEachIndexed { index, file -> assertEquals("export $index", file.readText()) }
        } finally {
            worker.shutdownNow()
        }
    }
}
