package com.syconx.us2us.runtime.net

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The network watcher on a phone. */
@RunWith(AndroidJUnit4::class)
class NetworkDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun theWatcherReportsAtOnce() {
        val watcher = NetworkPathWatcher(context)
        val reported = CountDownLatch(1)
        var available: Boolean? = null
        watcher.start { value ->
            available = value
            reported.countDown()
        }
        assertTrue(reported.await(5, TimeUnit.SECONDS))
        assertEquals(watcher.isAvailable(), available)
        watcher.stop()
    }
}
