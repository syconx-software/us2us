package com.syconx.us2us.feature.verification

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.LifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException

/**
 * The camera of 6.2, the only place the app opens it (design note: “the camera opens only on this
 * screen; the image never leaves the device”).
 *
 * * QR reading only: the preview and an analysis stream decoded with zxing; no photo or video
 *   output, no frames kept, nothing logged. The decoded text goes straight to the comparison.
 * * Runs only while 6.2 is scanning, visible and the app resumed; stopped on close, on “Kendi
 *   kodumu göster”, and as soon as the app pauses (the privacy cover and the lock come up over a
 *   stopped camera).
 *
 * Without a camera to show (the emulator and JVM tests, as the iOS simulator) the screen shows the
 * design's placeholder instead ([Status.Placeholder]).
 */
@Stable
class QrCameraModel(private val backend: QrCameraBackend) {
    enum class Status {
        /** Not started yet. */
        Idle,

        /** Waiting for the system's camera permission prompt. */
        Requesting,

        Running,

        /** Access refused → “Kamera izni gerekli”. */
        Denied,

        /** No usable camera. */
        Unavailable,

        /** No camera to show (emulator, tests): the design's static placeholder. */
        Placeholder,
    }

    var status: Status by mutableStateOf(Status.Idle)
        private set
    var torchAvailable: Boolean by mutableStateOf(false)
        private set
    var torchOn: Boolean by mutableStateOf(false)
        private set

    /** Called on the main thread with each QR text the camera reads. */
    var onCode: ((String) -> Unit)? = null

    val isPlaceholder: Boolean get() = backend.isPlaceholder

    private var session: QrCameraSession? = null
    private var wantsRunning = false
    private var askedForPermission = false
    private var forcedStatus: Status? = null

    /**
     * Starts scanning into [surface]. The permission is asked for once per model ([requestPermission]
     * shows the system prompt); refused, the denied panel shows from then on.
     */
    suspend fun start(
        context: Context,
        owner: LifecycleOwner,
        surface: Preview.SurfaceProvider?,
        requestPermission: suspend () -> Boolean,
    ) {
        wantsRunning = true
        forcedStatus?.let {
            status = it
            return
        }
        if (backend.isPlaceholder) {
            status = Status.Placeholder
            torchAvailable = true
            return
        }
        if (!backend.hasPermission(context)) {
            if (askedForPermission) {
                status = Status.Denied
                return
            }
            askedForPermission = true
            status = Status.Requesting
            val granted = try {
                requestPermission()
            } catch (cancelled: CancellationException) {
                if (status == Status.Requesting) status = Status.Idle
                throw cancelled
            }
            if (!granted) {
                status = Status.Denied
                return
            }
        }
        if (!wantsRunning || surface == null) return
        val opened = backend.open(context, owner, surface) { code -> onCode?.invoke(code) }
        if (!wantsRunning) {
            opened?.close()
            return
        }
        session = opened
        status = if (opened != null) Status.Running else Status.Unavailable
        torchAvailable = opened?.hasTorch == true
        if (torchOn) opened?.setTorch(true)
    }

    /** Stops the camera (and the torch) at once. */
    fun stop() {
        wantsRunning = false
        torchOn = false
        session?.let {
            it.setTorch(false)
            it.close()
        }
        session = null
        if (status == Status.Running) status = Status.Idle
    }

    fun setTorch(on: Boolean) {
        if (!torchAvailable) return
        torchOn = on
        session?.setTorch(on)
    }

    /** Harness: show a state the device cannot be put in on demand (`denied`). */
    fun forceStatus(status: Status) {
        forcedStatus = status
        this.status = status
    }
}

/** Where the camera comes from: CameraX on a phone, nothing on the emulator and in tests. */
interface QrCameraBackend {
    /** No camera to show here: the design's placeholder and the phone mock. */
    val isPlaceholder: Boolean

    fun hasPermission(context: Context): Boolean

    /** Opens the back camera bound to [owner], previewing into [surface]; `null` without a usable camera. */
    suspend fun open(context: Context, owner: LifecycleOwner, surface: Preview.SurfaceProvider, onCode: (String) -> Unit): QrCameraSession?
}

/** A running camera. */
interface QrCameraSession {
    val hasTorch: Boolean

    fun setTorch(on: Boolean)

    fun close()
}

/** The camera 6.2 uses; tests provide their own. */
val LocalQrCameraBackend = staticCompositionLocalOf<QrCameraBackend> { CameraXQrBackend }

/** CameraX: the back camera, a preview and a QR analysis stream (zxing). */
object CameraXQrBackend : QrCameraBackend {
    override val isPlaceholder: Boolean get() = runsWithoutCamera()

    override fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override suspend fun open(
        context: Context,
        owner: LifecycleOwner,
        surface: Preview.SurfaceProvider,
        onCode: (String) -> Unit,
    ): QrCameraSession? {
        val provider = try {
            ProcessCameraProvider.awaitInstance(context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        val selector = CameraSelector.DEFAULT_BACK_CAMERA
        val hasCamera = try {
            provider.hasCamera(selector)
        } catch (_: Exception) {
            false
        }
        if (!hasCamera) return null
        val preview = Preview.Builder().build().also { it.surfaceProvider = surface }
        // Codes are held close: 720p is plenty to read a 37 × 37 code, and cheap to decode.
        val resolution = ResolutionSelector.Builder()
            .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
            .build()
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolution)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        val executor = Executors.newSingleThreadExecutor()
        val main = Handler(Looper.getMainLooper())
        analysis.setAnalyzer(executor, QrFrameReader { text -> main.post { onCode(text) } })
        val camera = try {
            provider.unbindAll()
            provider.bindToLifecycle(owner, selector, preview, analysis)
        } catch (_: Exception) {
            analysis.clearAnalyzer()
            executor.shutdown()
            return null
        }
        return CameraXSession(provider, camera, preview, analysis, executor)
    }

    /** The emulator (its camera is a test scene) and the JVM: as the iOS simulator, the design's placeholder. */
    private fun runsWithoutCamera(): Boolean {
        val fingerprint = Build.FINGERPRINT.orEmpty()
        return fingerprint == "robolectric" ||
            fingerprint.startsWith("generic") ||
            fingerprint.contains("emulator") ||
            Build.HARDWARE == "ranchu" ||
            Build.HARDWARE == "goldfish" ||
            Build.PRODUCT.orEmpty().startsWith("sdk_")
    }
}

private class CameraXSession(
    private val provider: ProcessCameraProvider,
    private val camera: Camera,
    private val preview: Preview,
    private val analysis: ImageAnalysis,
    private val executor: ExecutorService,
) : QrCameraSession {
    override val hasTorch: Boolean get() = camera.cameraInfo.hasFlashUnit()

    override fun setTorch(on: Boolean) {
        if (hasTorch) camera.cameraControl.enableTorch(on)
    }

    override fun close() {
        analysis.clearAnalyzer()
        provider.unbind(preview, analysis)
        executor.shutdown()
    }
}

/** Reads QR codes from the luminance plane of each analysis frame; nothing is kept. */
private class QrFrameReader(private val onCode: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = QRCodeReader()
    private val hints = mapOf(DecodeHintType.CHARACTER_SET to "UTF-8")

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes.firstOrNull() ?: return
            val buffer = plane.buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            val source = PlanarYUVLuminanceSource(data, plane.rowStride, image.height, 0, 0, image.width, image.height, false)
            val text = try {
                reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
            } catch (_: ReaderException) {
                null
            } finally {
                reader.reset()
            }
            if (!text.isNullOrEmpty()) onCode(text)
        } catch (_: RuntimeException) {
            // A frame that cannot be read is skipped.
        } finally {
            image.close()
        }
    }
}

/** A preview view for [CameraXQrBackend] (aspect-fill, a texture so it fades with the screen). */
internal fun makePreviewView(context: Context): PreviewView = PreviewView(context).apply {
    scaleType = PreviewView.ScaleType.FILL_CENTER
    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
}
