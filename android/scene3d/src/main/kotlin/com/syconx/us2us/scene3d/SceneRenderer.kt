// us2us — OpenGL ES 3 resources and drawing of the welcome scene: programs,
// static buffers, the dot texture and the multisampled target, and the
// encoding of one SceneFrame (the GL counterpart of SceneRenderer.swift's
// encoder and SceneResources.swift).
//
// Every call must be made on the thread that owns the EGL context
// (SceneRenderThread).

package com.syconx.us2us.scene3d

import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

internal class SceneRenderer {
    private enum class Blend { Normal, Additive, DepthOnly }

    private enum class Cull { None, Back }

    private class Program(val id: Int) {
        val model = GLES30.glGetUniformLocation(id, "uModel")
        val normalMatrix = GLES30.glGetUniformLocation(id, "uNormalMatrix")
        val color = GLES30.glGetUniformLocation(id, "uColor")
        val params = GLES30.glGetUniformLocation(id, "uParams")
    }

    private class Mesh(val vao: Int, val indexCount: Int)

    private class Instanced(val vao: Int, val count: Int)

    private lateinit var lit: Program
    private lateinit var basic: Program
    private lateinit var line: Program
    private lateinit var ribbon: Program
    private lateinit var sprite: Program
    private lateinit var points: Program
    private var copyProgram = 0

    private lateinit var cup: Mesh
    private lateinit var rim: Mesh
    private lateinit var string: Mesh
    private val lines = HashMap<SceneLines, Instanced>()
    private lateinit var glowLine: Instanced
    private lateinit var dust: Instanced
    private var emptyVao = 0
    private var dotTexture = 0
    private var frameBuffer = 0
    private val frameData: ByteBuffer = ByteBuffer.allocateDirect(SceneShaders.FRAME_BYTES).order(ByteOrder.nativeOrder())

    /** Multisampled colour + depth target, resolved into [resolveTexture]. */
    private var samples = 0
    private var msaaFbo = 0
    private var msaaColor = 0
    private var depth = 0
    private var resolveFbo = 0
    private var resolveTexture = 0
    private var targetWidth = 0
    private var targetHeight = 0

    private val buffers = ArrayList<Int>()
    private val vaos = ArrayList<Int>()

    /** Compiles the programs and uploads the static geometry; false when the GPU cannot run the scene. */
    fun setUp(): Boolean = try {
        lit = Program(program(SceneShaders.LIT_VERTEX, SceneShaders.LIT_FRAGMENT))
        basic = Program(program(SceneShaders.BASIC_VERTEX, SceneShaders.BASIC_FRAGMENT))
        line = Program(program(SceneShaders.LINE_VERTEX, SceneShaders.LINE_FRAGMENT))
        ribbon = Program(program(SceneShaders.RIBBON_VERTEX, SceneShaders.RIBBON_FRAGMENT))
        sprite = Program(program(SceneShaders.SPRITE_VERTEX, SceneShaders.SPRITE_FRAGMENT))
        points = Program(program(SceneShaders.POINTS_VERTEX, SceneShaders.SPRITE_FRAGMENT))
        copyProgram = program(SceneShaders.COPY_VERTEX, SceneShaders.COPY_FRAGMENT)
        for (p in listOf(lit, basic, line, ribbon, sprite, points)) {
            val block = GLES30.glGetUniformBlockIndex(p.id, "Frame")
            if (block != GLES30.GL_INVALID_INDEX) GLES30.glUniformBlockBinding(p.id, block, SceneShaders.FRAME_BINDING)
        }
        GLES30.glUseProgram(sprite.id)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(sprite.id, "uDot"), 0)
        GLES30.glUseProgram(points.id)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(points.id, "uDot"), 0)
        GLES30.glUseProgram(copyProgram)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(copyProgram, "uFrame"), 0)

        cup = mesh(SceneMeshes.cup, lit = true)
        rim = mesh(SceneMeshes.rim, lit = false)
        string = mesh(SceneMeshes.string, lit = false)
        for (kind in SceneLines.entries) lines[kind] = segments(SceneMeshes.lines(kind))
        glowLine = centers(SceneMeshes.glowLine)
        dust = centers(SceneMeshes.dust)
        emptyVao = genVertexArray()

        frameBuffer = genBuffer()
        GLES30.glBindBuffer(GLES30.GL_UNIFORM_BUFFER, frameBuffer)
        GLES30.glBufferData(GLES30.GL_UNIFORM_BUFFER, SceneShaders.FRAME_BYTES, null, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glBindBufferBase(GLES30.GL_UNIFORM_BUFFER, SceneShaders.FRAME_BINDING, frameBuffer)

        dotTexture = makeDotTexture()

        val max = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_SAMPLES, max, 0)
        samples = minOf(SAMPLE_COUNT, max[0]).let { if (it < 2) 0 else it }
        checkError("set up")
        true
    } catch (e: RuntimeException) {
        Log.e(TAG, "3D scene unavailable: ${e.message}")
        false
    }

    /** (Re)creates the render targets for a [width] × [height] px surface. */
    fun resize(width: Int, height: Int) {
        if (width == targetWidth && height == targetHeight && resolveFbo != 0) return
        releaseTargets()
        targetWidth = width
        targetHeight = height

        resolveTexture = genTexture()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, resolveTexture)
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, width, height)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        resolveFbo = genFramebuffer()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, resolveFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, resolveTexture, 0)

        depth = genRenderbuffer()
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, depth)
        if (samples > 0) {
            GLES30.glRenderbufferStorageMultisample(GLES30.GL_RENDERBUFFER, samples, GLES30.GL_DEPTH_COMPONENT24, width, height)
            msaaColor = genRenderbuffer()
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, msaaColor)
            GLES30.glRenderbufferStorageMultisample(GLES30.GL_RENDERBUFFER, samples, GLES30.GL_RGBA8, width, height)
            msaaFbo = genFramebuffer()
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, msaaFbo)
            GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_RENDERBUFFER, msaaColor)
            GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, depth)
            if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                // No multisampling on this GPU: draw straight into the resolve target.
                Log.w(TAG, "multisampled target incomplete; drawing without MSAA")
                samples = 0
                releaseTargets()
                resize(width, height)
                return
            }
        } else {
            GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT24, width, height)
            GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, depth)
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        checkError("targets")
    }

    /** Draws [frame] and copies it into the window surface (framebuffer 0). */
    fun draw(frame: SceneFrame) {
        if (targetWidth < 1 || targetHeight < 1) return
        val target = if (samples > 0) msaaFbo else resolveFbo
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target)
        GLES30.glViewport(0, 0, targetWidth, targetHeight)
        GLES30.glColorMask(true, true, true, true)
        GLES30.glDepthMask(true)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClearDepthf(1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
        GLES30.glFrontFace(GLES30.GL_CCW)
        GLES30.glCullFace(GLES30.GL_BACK)

        uploadFrame(frame.uniforms)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dotTexture)

        for (draw in frame.draws) encode(draw)

        if (samples > 0) {
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, msaaFbo)
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, resolveFbo)
            GLES30.glBlitFramebuffer(
                0, 0, targetWidth, targetHeight, 0, 0, targetWidth, targetHeight,
                GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST,
            )
            discard.rewind()
            GLES30.glInvalidateFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 2, discard)
        }

        // Present: a straight copy (no blending) into the window surface.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, targetWidth, targetHeight)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glColorMask(true, true, true, true)
        GLES30.glUseProgram(copyProgram)
        GLES30.glBindVertexArray(emptyVao)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, resolveTexture)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
    }

    private val discard: IntBuffer = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder()).asIntBuffer().apply {
        put(GLES30.GL_COLOR_ATTACHMENT0)
        put(GLES30.GL_DEPTH_ATTACHMENT)
        rewind()
    }

    private fun encode(d: SceneDraw) {
        when (d.kind) {
            SceneDrawKind.Cup -> {
                use(lit, Blend.Normal, depthWrite = true, cull = Cull.None)
                setDraw(lit, d)
                GLES30.glUniformMatrix4fv(lit.normalMatrix, 1, false, d.normalMatrix.m, 0)
                drawMesh(cup)
            }
            SceneDrawKind.Solid -> {
                use(basic, Blend.Normal, depthWrite = true, cull = Cull.Back)
                setDraw(basic, d)
                drawMesh(rim)
            }
            SceneDrawKind.String -> {
                // Depth from the real cylinder, colour from the antialiased ribbon.
                use(basic, Blend.DepthOnly, depthWrite = true, cull = Cull.Back)
                setDraw(basic, d)
                drawMesh(string)
                use(ribbon, Blend.Normal, depthWrite = false, cull = Cull.None)
                setDraw(ribbon, d)
                GLES30.glBindVertexArray(emptyVao)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            }
            SceneDrawKind.Lines -> {
                val segments = lines.getValue(requireNotNull(d.lines))
                use(line, Blend.Normal, depthWrite = false, cull = Cull.None)
                setDraw(line, d)
                GLES30.glBindVertexArray(segments.vao)
                GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, segments.count)
            }
            SceneDrawKind.GlowLine -> {
                use(points, if (d.additive) Blend.Additive else Blend.Normal, depthWrite = false, cull = Cull.None)
                setDraw(points, d)
                GLES30.glBindVertexArray(glowLine.vao)
                GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, glowLine.count)
            }
            SceneDrawKind.Sprite -> {
                use(sprite, if (d.additive) Blend.Additive else Blend.Normal, depthWrite = false, cull = Cull.None)
                setDraw(sprite, d)
                GLES30.glBindVertexArray(emptyVao)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            }
            SceneDrawKind.Dust -> {
                use(points, Blend.Normal, depthWrite = false, cull = Cull.None)
                setDraw(points, d)
                GLES30.glBindVertexArray(dust.vao)
                GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, dust.count)
            }
        }
        GLES30.glBindVertexArray(0)
    }

    private fun use(program: Program, blend: Blend, depthWrite: Boolean, cull: Cull) {
        GLES30.glUseProgram(program.id)
        when (blend) {
            Blend.Normal -> {
                // three.js NormalBlending (straight alpha)
                GLES30.glColorMask(true, true, true, true)
                GLES30.glEnable(GLES30.GL_BLEND)
                GLES30.glBlendFuncSeparate(
                    GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA, GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA,
                )
            }
            Blend.Additive -> {
                // three.js AdditiveBlending (straight alpha)
                GLES30.glColorMask(true, true, true, true)
                GLES30.glEnable(GLES30.GL_BLEND)
                GLES30.glBlendFuncSeparate(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE, GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
            }
            Blend.DepthOnly -> {
                GLES30.glDisable(GLES30.GL_BLEND)
                GLES30.glColorMask(false, false, false, false)
            }
        }
        GLES30.glDepthMask(depthWrite)
        if (cull == Cull.Back) GLES30.glEnable(GLES30.GL_CULL_FACE) else GLES30.glDisable(GLES30.GL_CULL_FACE)
    }

    private fun setDraw(program: Program, d: SceneDraw) {
        GLES30.glUniformMatrix4fv(program.model, 1, false, d.model.m, 0)
        GLES30.glUniform4fv(program.color, 1, d.color, 0)
        GLES30.glUniform4fv(program.params, 1, d.params, 0)
    }

    private fun drawMesh(mesh: Mesh) {
        GLES30.glBindVertexArray(mesh.vao)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, mesh.indexCount, GLES30.GL_UNSIGNED_SHORT, 0)
    }

    private fun uploadFrame(u: SceneFrameUniforms) {
        val b = frameData
        b.clear()
        for (v in u.view.m) b.putFloat(v)
        for (v in u.projection.m) b.putFloat(v)
        for (vector in listOf(
            u.viewport, u.keyDirection, u.keyColor, u.rimDirection, u.rimColor,
            u.hemiDirection, u.skyColor, u.groundColor,
        )) {
            for (v in vector) b.putFloat(v)
        }
        b.flip()
        GLES30.glBindBuffer(GLES30.GL_UNIFORM_BUFFER, frameBuffer)
        GLES30.glBufferSubData(GLES30.GL_UNIFORM_BUFFER, 0, SceneShaders.FRAME_BYTES, b)
        GLES30.glBindBufferBase(GLES30.GL_UNIFORM_BUFFER, SceneShaders.FRAME_BINDING, frameBuffer)
    }

    // Resources

    private fun mesh(mesh: SceneMesh, lit: Boolean): Mesh {
        val vao = genVertexArray()
        GLES30.glBindVertexArray(vao)
        val vbo = genBuffer()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, mesh.vertices.size * 4, floats(mesh.vertices), GLES30.GL_STATIC_DRAW)
        val stride = mesh.stride(lit) * 4
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        if (lit) {
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12)
        }
        val ibo = genBuffer()
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        val indices = ByteBuffer.allocateDirect(mesh.indices.size * 2).order(ByteOrder.nativeOrder())
        indices.asShortBuffer().put(mesh.indices)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, mesh.indices.size * 2, indices, GLES30.GL_STATIC_DRAW)
        GLES30.glBindVertexArray(0)
        return Mesh(vao, mesh.indices.size)
    }

    /** Segment pairs as per-instance attributes: start at offset 0, end at offset 12, 24 bytes apart. */
    private fun segments(positions: FloatArray): Instanced {
        val vao = genVertexArray()
        GLES30.glBindVertexArray(vao)
        val vbo = genBuffer()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, positions.size * 4, floats(positions), GLES30.GL_STATIC_DRAW)
        for (attribute in 0..1) {
            GLES30.glEnableVertexAttribArray(attribute)
            GLES30.glVertexAttribPointer(attribute, 3, GLES30.GL_FLOAT, false, 24, attribute * 12)
            GLES30.glVertexAttribDivisor(attribute, 1)
        }
        GLES30.glBindVertexArray(0)
        return Instanced(vao, positions.size / 6)
    }

    /** Point centres as a per-instance attribute. */
    private fun centers(positions: FloatArray): Instanced {
        val vao = genVertexArray()
        GLES30.glBindVertexArray(vao)
        val vbo = genBuffer()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, positions.size * 4, floats(positions), GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
        GLES30.glVertexAttribDivisor(0, 1)
        GLES30.glBindVertexArray(0)
        return Instanced(vao, positions.size / 3)
    }

    /**
     * Dot texture: sRGB colour (like three.js' SRGBColorSpace canvas texture), linear alpha, full
     * mip chain, trilinear sampling.
     */
    private fun makeDotTexture(): Int {
        val size = SceneGeometry.DOT_TEXTURE_SIZE
        val texture = genTexture()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        val pixels = ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder())
        pixels.put(SceneMeshes.dotTexture).flip()
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_SRGB8_ALPHA8, size, size, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels,
        )
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return texture
    }

    private fun program(vertexSource: String, fragmentSource: String): Int {
        val vertex = shader(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragment = shader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertex)
        GLES30.glAttachShader(program, fragment)
        GLES30.glLinkProgram(program)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        GLES30.glDeleteShader(vertex)
        GLES30.glDeleteShader(fragment)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            throw IllegalStateException("program link failed: $log")
        }
        programs += program
        return program
    }

    private fun shader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw IllegalStateException("shader compile failed: $log")
        }
        return shader
    }

    private val programs = ArrayList<Int>()

    private fun floats(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(values)
            rewind()
        }

    private fun genBuffer(): Int = IntArray(1).also { GLES30.glGenBuffers(1, it, 0) }[0].also { buffers += it }

    private fun genVertexArray(): Int = IntArray(1).also { GLES30.glGenVertexArrays(1, it, 0) }[0].also { vaos += it }

    private fun genTexture(): Int = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]

    private fun genFramebuffer(): Int = IntArray(1).also { GLES30.glGenFramebuffers(1, it, 0) }[0]

    private fun genRenderbuffer(): Int = IntArray(1).also { GLES30.glGenRenderbuffers(1, it, 0) }[0]

    private fun releaseTargets() {
        if (msaaFbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(msaaFbo), 0)
        if (resolveFbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(resolveFbo), 0)
        if (msaaColor != 0) GLES30.glDeleteRenderbuffers(1, intArrayOf(msaaColor), 0)
        if (depth != 0) GLES30.glDeleteRenderbuffers(1, intArrayOf(depth), 0)
        if (resolveTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(resolveTexture), 0)
        msaaFbo = 0
        resolveFbo = 0
        msaaColor = 0
        depth = 0
        resolveTexture = 0
        targetWidth = 0
        targetHeight = 0
    }

    /** Frees every GL object (the context is about to go). */
    fun release() {
        releaseTargets()
        if (dotTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(dotTexture), 0)
        dotTexture = 0
        if (buffers.isNotEmpty()) GLES30.glDeleteBuffers(buffers.size, buffers.toIntArray(), 0)
        if (vaos.isNotEmpty()) GLES30.glDeleteVertexArrays(vaos.size, vaos.toIntArray(), 0)
        buffers.clear()
        vaos.clear()
        for (p in programs) GLES30.glDeleteProgram(p)
        programs.clear()
    }

    private fun checkError(stage: String) {
        val error = GLES30.glGetError()
        check(error == GLES30.GL_NO_ERROR) { "GL error 0x${Integer.toHexString(error)} in $stage" }
    }

    companion object {
        private const val TAG = "Scene3D"

        /** 4× MSAA where supported. */
        const val SAMPLE_COUNT = 4
    }
}
