package com.syconx.us2us.feature.verification

import android.content.Context
import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.syconx.us2us.shot.ShotHost
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** When 6.2's camera runs, asks, refuses and lets go (iOS QRCameraModel). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ShotHost.SDK])
class QrCameraModelTest {
    private class FakeSession(override val hasTorch: Boolean) : QrCameraSession {
        var torchLit = false
        var closed = false

        override fun setTorch(on: Boolean) {
            torchLit = on
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeBackend(var permitted: Boolean, val camera: Boolean = true, val torch: Boolean = true) : QrCameraBackend {
        override val isPlaceholder: Boolean = false
        val sessions = mutableListOf<FakeSession>()
        var onCode: ((String) -> Unit)? = null

        override fun hasPermission(context: Context): Boolean = permitted

        override suspend fun open(context: Context, owner: LifecycleOwner, surface: Preview.SurfaceProvider, onCode: (String) -> Unit): QrCameraSession? {
            if (!camera) return null
            this.onCode = onCode
            return FakeSession(torch).also { sessions += it }
        }
    }

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val owner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
    }
    private val surface = Preview.SurfaceProvider { }

    @Test
    fun aGrantedCameraRunsReadsAndStops() = runBlocking {
        val backend = FakeBackend(permitted = true)
        val camera = QrCameraModel(backend)
        val read = mutableListOf<String>()
        camera.onCode = { read += it }
        camera.start(context, owner, surface) { error("no prompt when granted") }
        assertEquals(QrCameraModel.Status.Running, camera.status)
        assertTrue(camera.torchAvailable)
        backend.onCode?.invoke("U2SN:ABC")
        assertEquals(listOf("U2SN:ABC"), read)
        camera.setTorch(true)
        assertTrue(backend.sessions.single().torchLit)
        camera.stop()
        assertTrue(backend.sessions.single().closed)
        assertFalse("the torch goes off with the camera", camera.torchOn)
        assertEquals(QrCameraModel.Status.Idle, camera.status)
    }

    @Test
    fun thePermissionIsAskedOnceAndARefusalShowsThePanel() = runBlocking {
        val backend = FakeBackend(permitted = false)
        val camera = QrCameraModel(backend)
        var asked = 0
        camera.start(context, owner, surface) {
            asked++
            false
        }
        assertEquals(QrCameraModel.Status.Denied, camera.status)
        camera.stop()
        camera.start(context, owner, surface) {
            asked++
            false
        }
        assertEquals("never asked again (no prompt loop on every return)", 1, asked)
        assertEquals(QrCameraModel.Status.Denied, camera.status)
        // Allowed in the app's settings meanwhile: the camera runs on the next start.
        backend.permitted = true
        camera.start(context, owner, surface) { error("no prompt when granted") }
        assertEquals(QrCameraModel.Status.Running, camera.status)
    }

    @Test
    fun aGrantedPromptStartsTheCamera() = runBlocking {
        val backend = FakeBackend(permitted = false)
        val camera = QrCameraModel(backend)
        camera.start(context, owner, surface) {
            backend.permitted = true
            true
        }
        assertEquals(QrCameraModel.Status.Running, camera.status)
    }

    @Test
    fun noCameraIsUnavailableAndATorchlessOneHasNoTorch() = runBlocking {
        val none = QrCameraModel(FakeBackend(permitted = true, camera = false))
        none.start(context, owner, surface) { true }
        assertEquals(QrCameraModel.Status.Unavailable, none.status)
        val torchless = QrCameraModel(FakeBackend(permitted = true, torch = false))
        torchless.start(context, owner, surface) { true }
        assertFalse(torchless.torchAvailable)
        torchless.setTorch(true)
        assertFalse(torchless.torchOn)
    }

    @Test
    fun withoutACameraThePlaceholderShows() = runBlocking {
        // Robolectric is not a phone: the design's placeholder, as on the iOS simulator.
        assertTrue(CameraXQrBackend.isPlaceholder)
        val camera = QrCameraModel(CameraXQrBackend)
        camera.start(context, owner, null) { error("no prompt for the placeholder") }
        assertEquals(QrCameraModel.Status.Placeholder, camera.status)
        assertTrue(camera.torchAvailable)
    }

    @Test
    fun theHarnessCanForceTheDeniedPanel() = runBlocking {
        val camera = QrCameraModel(FakeBackend(permitted = true))
        camera.forceStatus(QrCameraModel.Status.Denied)
        camera.start(context, owner, surface) { true }
        assertEquals(QrCameraModel.Status.Denied, camera.status)
    }
}
