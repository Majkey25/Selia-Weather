package cz.majkey.pocasicesko.ui

import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import cz.majkey.pocasicesko.data.CzechLocation
import cz.majkey.pocasicesko.data.HistoricalDay
import cz.majkey.pocasicesko.data.HistoryArchive
import cz.majkey.pocasicesko.data.historyCsv
import cz.majkey.pocasicesko.locale.AppLocale
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryShareIntentTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val archive = HistoryArchive(
        CzechLocation("Prague", "", 50.0755, 14.4378, "CZ"),
        listOf(
            HistoricalDay(LocalDate.of(2026, 1, 1), 1.0, 2.0, 0.0, 0.1, null, null, null),
            HistoricalDay(LocalDate.of(2026, 1, 3), null, null, null, 3.0, null, null, null),
        ), "test", 123L,
    )

    @Suppress("DEPRECATION")
    @Test
    fun genericExportUsesChooserAndReadableFullArchiveWithReadOnlyGrant() = runBlocking {
        val focus = LocalDate.of(2026, 1, 3)..LocalDate.of(2026, 1, 3)
        val chooser = createHistoryShareIntent(context, archive, "Choose app", "Rain on January 3?", focus)
        val send = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        // Huawei uses its own system chooser action.
        assertEquals(Intent.createChooser(send, "Choose app").action, chooser.action)
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/csv", send.type)
        assertNull(send.component)
        val uri = requireNotNull(send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.files", uri.authority)
        assertEquals(uri, requireNotNull(send.clipData).getItemAt(0).uri)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,
            send.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }
        assertEquals(historyCsv(archive), bytes.toString(Charsets.UTF_8))
        assertEquals(3, bytes.toString(Charsets.UTF_8).lines().size)
        val prompt = requireNotNull(send.getStringExtra(Intent.EXTRA_TEXT))
        assertTrue(prompt.contains("Question: Rain on January 3?"))
        assertTrue(prompt.contains("2026-01-01 to 2026-01-03"))
        assertTrue(prompt.contains("2026-01-03 to 2026-01-03 (UTC, inclusive)"))
        assertTrue(prompt.contains("(${AppLocale.languageTag(context)}), the language selected in the weather app"))
        assertTrue(prompt.contains("If no CSV attachment is accessible"))
    }

    @Suppress("DEPRECATION")
    @Test
    fun selectedTargetKeepsCsvAndQuestionWithoutLaunchingTheReceiver() = runBlocking {
        val component = ComponentName(context.packageName, "cz.majkey.pocasicesko.MainActivity")
        val target = HistoryAiTarget("Test receiver", component, ColorDrawable())
        val send = createHistoryShareIntent(context, archive, "Choose app", "How much rain?", target = target)
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals(component, send.component)
        assertEquals("text/csv", send.type)
        assertEquals(send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM), requireNotNull(send.clipData).getItemAt(0).uri)
        assertTrue(requireNotNull(send.getStringExtra(Intent.EXTRA_TEXT)).contains("Question: How much rain?"))
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,
            send.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
    }
}
