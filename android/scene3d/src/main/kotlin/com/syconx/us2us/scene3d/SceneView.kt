// us2us — the GL-backed view of the welcome scene: a transparent TextureView
// whose SurfaceTexture is drawn by SceneRenderThread (Us2usSceneView on iOS).
// The buffer is rendered at 2 px per point at most — the pixel density the
// design's web scene and the iOS app cap at — and scaled to the view.

package com.syconx.us2us.scene3d

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.PowerManager
import android.view.TextureView
import android.view.View
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
internal class SceneView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    /** Once, after the first frame is on screen. */
    var onFirstFrame: (() -> Unit)? = null

    /** The canvas opacity, when it changes. */
    var onOpacityChange: ((Double) -> Unit)? = null

    private var renderThread: SceneRenderThread? = null
    private var content: SceneContent? = null
    private var isRunning = false
    private var isForeground = true
    private val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private val callbacks = object : SceneRenderThread.Callbacks {
        override fun onFirstFrame() {
            this@SceneView.onFirstFrame?.invoke()
        }

        override fun onOpacityChange(opacity: Double) {
            this@SceneView.onOpacityChange?.invoke(opacity)
        }

        override fun isConstrained(): Boolean = isPowerConstrained()
    }

    init {
        isOpaque = false
        surfaceTextureListener = this
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        isClickable = false
        isFocusable = false
    }

    /**
     * What to draw and whether to animate. [isRunning]: visible, active and not covered;
     * [isForeground]: the app is started (no frames at all in the background).
     */
    fun configure(content: SceneContent, isRunning: Boolean, isForeground: Boolean) {
        this.content = content
        this.isRunning = isRunning
        this.isForeground = isForeground
        renderThread?.configure(content, isRunning, isForeground)
    }

    /** Stops the loop; the surface and the context go with the view. */
    fun stop() {
        isRunning = false
        content?.let { renderThread?.configure(it, isRunning = false, isForeground = isForeground) }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        val thread = SceneRenderThread(surface, callbacks)
        renderThread = thread
        thread.start(sceneSize(width, height))
        content?.let { thread.configure(it, isRunning, isForeground) }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        renderThread?.resize(sceneSize(width, height))
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        renderThread?.release()
        renderThread = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    /** The view in points (the camera's aspect) and the buffer at min(2, density) px per point. */
    private fun sceneSize(width: Int, height: Int): SceneSize {
        val density = resources.displayMetrics.density.coerceAtLeast(0.5f)
        val scale = density.coerceIn(1f, 2f)
        val points = { px: Int -> px / density.toDouble() }
        return SceneSize(
            canvasWidth = points(width),
            canvasHeight = points(height),
            pixelWidth = (points(width) * scale).roundToInt().coerceAtLeast(1),
            pixelHeight = (points(height) * scale).roundToInt().coerceAtLeast(1),
        )
    }

    /** Low Power Mode or a hot device on iOS: battery saver, or a severe thermal state. */
    private fun isPowerConstrained(): Boolean {
        val pm = power ?: return false
        if (pm.isPowerSaveMode) return true
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
    }
}
