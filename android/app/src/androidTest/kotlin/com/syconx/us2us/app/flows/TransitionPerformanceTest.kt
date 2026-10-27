package com.syconx.us2us.app.flows

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import com.syconx.us2us.app.MainActivity
import com.syconx.us2us.app.Us2usApplication
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Native window frame timings; reports observations, never labels emulator results as phone FPS. */
@RunWith(AndroidJUnit4::class)
class TransitionPerformanceTest {
    private data class Sample(val total: Long, val deadline: Long, val draw: Long, val layout: Long, val gpu: Long, val uiDelay: Long)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val recording = AtomicBoolean(false)

    private fun find(label: String): UiObject2 {
        val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end) {
            (device.findObject(By.text(label)) ?: device.findObject(By.desc(label))
                ?: device.findObject(By.descContains(label)) ?: device.findObject(By.textContains(label)))?.let { return it }
            Thread.sleep(50)
        }
        throw AssertionError("$label did not appear")
    }
    private fun tap(label: String) {
        val bounds = find(label).visibleBounds
        // Accessibility snapshots can block the app; resolve the target before measuring.
        Thread.sleep(100)
        recording.set(true)
        try {
            device.click(bounds.centerX(), bounds.centerY())
            // Real display frames; no Compose test clock advances the transition virtually.
            Thread.sleep(350)
        } finally { recording.set(false) }
    }

    @Test fun chatsConversationAndSheets() {
        device.wakeUp()
        device.pressMenu()
        val idleTimeout = Configurator.getInstance().waitForIdleTimeout
        Configurator.getInstance().waitForIdleTimeout = 500
        val context = instrumentation.targetContext
        instrumentation.runOnMainSync { (context.applicationContext as Us2usApplication).startOver() }
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
            .putExtra("U2Fixtures", "YES").putExtra("U2Start", "chats")
            .putExtra("U2StressChats", "500").putExtra("U2FrozenClock", "NO")
            .putExtra("U2Language", "tr").putExtra("U2Theme", "dark")
            .putExtra("U2ResetPreferences", "YES"))
        try {
            find("deniz")
            val frames = ConcurrentLinkedQueue<Sample>()
            val dropped = AtomicInteger()
            val worker = HandlerThread("us2us-test-frames").apply { start() }
            var window: Window? = null
            var budgetMs = 16.67
            val listener = Window.OnFrameMetricsAvailableListener { _, metrics, missed ->
                if (recording.get() && metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 0L) {
                    val nanos = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                    if (nanos > 0) frames.add(Sample(
                        nanos, if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.DEADLINE) else (budgetMs * 1_000_000).toLong(),
                        metrics.getMetric(FrameMetrics.DRAW_DURATION), metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),
                        if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.GPU_DURATION) else -1,
                        metrics.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION)))
                }
                dropped.addAndGet(missed)
            }
            scenario.onActivity {
                window = it.window
                budgetMs = 1000.0 / it.window.decorView.display.refreshRate
                it.window.addOnFrameMetricsAvailableListener(listener, Handler(worker.looper))
            }
            try {
                repeat(5) {
                    tap("deniz")
                    tap("Geri")
                    tap("Yeni sohbet")
                    tap("Vazgeç")
                    find("deniz")
                }
            } finally {
                scenario.onActivity { window?.removeOnFrameMetricsAvailableListener(listener) }
                worker.quitSafely()
                worker.join(2_000)
            }
            val samples = frames.toList()
            val sorted = samples.map { it.total / 1_000_000.0 }.sorted()
            assertTrue("native frame metrics were collected", sorted.isNotEmpty())
            fun percentile(fraction: Double) = sorted[((sorted.size - 1) * fraction).toInt()]
            val report = Bundle().apply {
                putInt("u2.frames", sorted.size)
                putDouble("u2.frameBudgetMs", budgetMs)
                putDouble("u2.p50Ms", percentile(.50))
                putDouble("u2.p95Ms", percentile(.95))
                putInt("u2.overRefreshInterval", sorted.count { it > budgetMs })
                // Android's allocated deadline accounts for pipelining; display interval alone is not jank.
                putInt("u2.missedDeadline", samples.count { it.deadline > 0 && it.total >= it.deadline })
                fun phaseP95(read: (Sample) -> Long): Double {
                    val values = samples.map(read).filter { it >= 0 }.sorted()
                    return if (values.isEmpty()) -1.0 else values[((values.size - 1) * .95).toInt()] / 1_000_000.0
                }
                putDouble("u2.deadlineP95Ms", phaseP95 { it.deadline })
                putDouble("u2.drawP95Ms", phaseP95 { it.draw })
                putDouble("u2.layoutP95Ms", phaseP95 { it.layout })
                putDouble("u2.gpuP95Ms", phaseP95 { it.gpu })
                putDouble("u2.uiDelayP95Ms", phaseP95 { it.uiDelay })
                putInt("u2.over50Ms", sorted.count { it > 50 })
                putInt("u2.callbackDrops", dropped.get())
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(0, report)
        } finally {
            scenario.close()
            Configurator.getInstance().waitForIdleTimeout = idleTimeout
        }
    }
}
