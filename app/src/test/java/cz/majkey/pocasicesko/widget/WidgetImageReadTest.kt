package cz.majkey.pocasicesko.widget

import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.assertThrows
import org.junit.Test

class WidgetImageReadTest {
    @Test
    fun stalledProviderDoesNotKeepWidgetWorkWaitingOrGrowTheReadQueue() {
        val release = CountDownLatch(1)
        try {
            assertThrows(TimeoutException::class.java) {
                WidgetBackground.readCustomImage {
                    try {
                        release.await(20, TimeUnit.SECONDS)
                    } catch (_: InterruptedException) {
                        // Emulate provider IO that does not stop on interruption.
                        release.await(20, TimeUnit.SECONDS)
                    }
                    byteArrayOf(1)
                }
            }
            assertThrows(RejectedExecutionException::class.java) {
                WidgetBackground.readCustomImage { byteArrayOf(2) }
            }
        } finally {
            release.countDown()
        }
    }
}
