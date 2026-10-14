// us2us — the welcome scene's render thread: an EGL context on the scene
// view's SurfaceTexture, paced by Choreographer on this thread (the Android
// counterpart of Us2usSceneView's display link and SceneRenderer's clock).
//
// • Animates only while running is requested, a still frame is not pinned and
//   the app is in the foreground; otherwise draws single frames on demand.
// • At most 60 fps, 30 fps in battery saver or when the device is hot.
// • A long gap (paused, dropped frames) advances the clock by at most 50 ms,
//   so nothing jumps on resume.

package com.syconx.us2us.scene3d

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.Choreographer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** What the scene shows: changes to any of these redraw a still frame. */
internal data class SceneContent(
    val palette: ScenePalette,
    val state: SceneState,
    /** When set, the scene is pinned to this time (reduce-motion still frame, screenshots). */
    val frozenTime: Double?,
)

/** The canvas in points (camera aspect) and the render target in pixels. */
internal data class SceneSize(val canvasWidth: Double, val canvasHeight: Double, val pixelWidth: Int, val pixelHeight: Int)

internal class SceneRenderThread(
    private val surfaceTexture: SurfaceTexture,
    private val callbacks: Callbacks,
) {
    /** [onFirstFrame] and [onOpacityChange] are called on the main thread, [isConstrained] on the render thread. */
    interface Callbacks {
        /** Once, after the first frame has been presented. */
        fun onFirstFrame()

        /** The canvas opacity (`dim`, narrow canvases), when it changes. */
        fun onOpacityChange(opacity: Double)

        /** Whether the device asks for fewer frames (battery saver, thermal), read about once a second. */
        fun isConstrained(): Boolean
    }

    private val thread = HandlerThread("us2us.scene", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    // Render-thread state.
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var config: EGLConfig? = null
    private var renderer: SceneRenderer? = null
    private var failed = false

    private var content: SceneContent? = null
    private var size: SceneSize? = null
    private var isRunningRequested = false
    private var isForeground = true
    private var isReleased = false

    private val clock = SceneClock()
    private val pacer = SceneFramePacer()
    private var lastOpacity = -1.0
    private var didRenderFirstFrame = false
    private var isRedrawScheduled = false
    private var isRedrawDeferred = false

    private var choreographer: Choreographer? = null
    private var isLooping = false
    private var lastConstraintCheck = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isLooping) return
            tick(frameTimeNanos)
            if (isLooping) choreographer?.postFrameCallback(this)
        }
    }

    // Main-thread API

    fun start(size: SceneSize) {
        handler.post {
            choreographer = Choreographer.getInstance()
            this.size = size
            surfaceTexture.setDefaultBufferSize(size.pixelWidth, size.pixelHeight)
            setUpGL()
            updateLoop()
            setNeedsRender()
        }
    }

    fun resize(size: SceneSize) {
        handler.post {
            if (this.size == size) return@post
            this.size = size
            surfaceTexture.setDefaultBufferSize(size.pixelWidth, size.pixelHeight)
            // A new window surface dequeues buffers of the new size right away.
            recreateSurface()
            setNeedsRender()
        }
    }

    fun configure(content: SceneContent, isRunning: Boolean, isForeground: Boolean) {
        handler.post {
            val changed = this.content != content
            this.content = content
            this.isRunningRequested = isRunning
            this.isForeground = isForeground
            updateLoop()
            if (changed || (isRedrawDeferred && isForeground)) setNeedsRender()
        }
    }

    /** Stops drawing and frees the context; returns once the surface is no longer used. */
    fun release() {
        val done = CountDownLatch(1)
        handler.post {
            isReleased = true
            isLooping = false
            choreographer?.removeFrameCallback(frameCallback)
            tearDownGL()
            done.countDown()
        }
        done.await(RELEASE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        thread.quitSafely()
    }

    // Loop

    private val shouldAnimate: Boolean
        get() = isRunningRequested && renderer != null && content?.frozenTime == null && isForeground && !isReleased

    private fun updateLoop() {
        if (shouldAnimate) {
            if (isLooping) return
            isLooping = true
            pacer.reset()
            choreographer?.postFrameCallback(frameCallback)
        } else if (isLooping) {
            isLooping = false
            choreographer?.removeFrameCallback(frameCallback)
        }
    }

    private fun tick(frameTimeNanos: Long) {
        if (frameTimeNanos - lastConstraintCheck > CONSTRAINT_CHECK_NANOS) {
            lastConstraintCheck = frameTimeNanos
            pacer.constrained = callbacks.isConstrained()
        }
        val dt = pacer.frame(frameTimeNanos) ?: return
        clock.advance(dt)
        draw()
    }

    /** One frame on the next turn of the render loop when the loop is not running (still frame, theme or size change). */
    private fun setNeedsRender() {
        if (isLooping || isRedrawScheduled) return
        isRedrawScheduled = true
        handler.post {
            isRedrawScheduled = false
            if (isLooping || renderer == null || isReleased) return@post
            if (!isForeground) {
                isRedrawDeferred = true
                return@post
            }
            isRedrawDeferred = false
            draw()
        }
    }

    private fun draw() {
        val renderer = renderer ?: return
        val content = content ?: return
        val size = size ?: return
        if (size.pixelWidth < 1 || size.pixelHeight < 1 || size.canvasWidth < 1 || size.canvasHeight < 1) return
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            recoverFromLoss()
            return
        }
        val width = IntArray(1)
        val height = IntArray(1)
        EGL14.eglQuerySurface(display, surface, EGL14.EGL_WIDTH, width, 0)
        EGL14.eglQuerySurface(display, surface, EGL14.EGL_HEIGHT, height, 0)
        val targetWidth = if (width[0] > 0) width[0] else size.pixelWidth
        val targetHeight = if (height[0] > 0) height[0] else size.pixelHeight
        renderer.resize(targetWidth, targetHeight)

        val t = content.frozenTime ?: clock.time
        val dustT = content.frozenTime ?: clock.dustTime
        val frame = SceneFrameBuilder.build(
            content.state, content.palette, t, dustT,
            canvasWidth = size.canvasWidth, canvasHeight = size.canvasHeight,
            targetWidth = targetWidth.toFloat(), targetHeight = targetHeight.toFloat(),
        )
        if (frame.opacity != lastOpacity) {
            lastOpacity = frame.opacity
            val opacity = frame.opacity
            main.post { callbacks.onOpacityChange(opacity) }
        }
        renderer.draw(frame)
        if (!EGL14.eglSwapBuffers(display, surface)) {
            recoverFromLoss()
            return
        }
        if (!didRenderFirstFrame) {
            didRenderFirstFrame = true
            main.post { callbacks.onFirstFrame() }
        }
    }

    // EGL

    private fun setUpGL() {
        if (failed || isReleased) return
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
            val attributes = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 0,
                EGL14.EGL_STENCIL_SIZE, 0,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "no RGBA8888 ES 3 config" }
            config = configs[0]
            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
            )
            check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
            surface = EGL14.eglCreateWindowSurface(display, config, surfaceTexture, intArrayOf(EGL14.EGL_NONE), 0)
            check(surface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }
            val r = SceneRenderer()
            check(r.setUp()) { "GL resources" }
            renderer = r
        } catch (e: IllegalStateException) {
            // The scene is decorative: without OpenGL ES 3 it simply stays empty.
            Log.e(TAG, "3D scene unavailable: ${e.message} (EGL 0x${Integer.toHexString(EGL14.eglGetError())})")
            failed = true
            tearDownGL()
        }
    }

    /** A window surface for the SurfaceTexture's new buffer size, in the same context. */
    private fun recreateSurface() {
        if (display == EGL14.EGL_NO_DISPLAY || context == EGL14.EGL_NO_CONTEXT) return
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
        surface = EGL14.eglCreateWindowSurface(display, config, surfaceTexture, intArrayOf(EGL14.EGL_NONE), 0)
        if (surface == EGL14.EGL_NO_SURFACE) recoverFromLoss()
    }

    private fun tearDownGL() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (context != EGL14.EGL_NO_CONTEXT && surface != EGL14.EGL_NO_SURFACE) {
                if (EGL14.eglMakeCurrent(display, surface, surface, context)) renderer?.release()
            }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
        }
        renderer = null
        surface = EGL14.EGL_NO_SURFACE
        context = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
    }

    /** The context was lost (or the surface went bad): build everything again once. */
    private fun recoverFromLoss() {
        val error = EGL14.eglGetError()
        Log.w(TAG, "EGL error 0x${Integer.toHexString(error)}; recreating the context")
        isLooping = false
        choreographer?.removeFrameCallback(frameCallback)
        renderer = null
        tearDownGL()
        if (error == EGL14.EGL_CONTEXT_LOST) {
            setUpGL()
        } else {
            failed = true
        }
        updateLoop()
        setNeedsRender()
    }

    private companion object {
        const val TAG = "Scene3D"
        const val CONSTRAINT_CHECK_NANOS = 1_000_000_000L
        const val RELEASE_TIMEOUT_MS = 1_000L
    }
}
