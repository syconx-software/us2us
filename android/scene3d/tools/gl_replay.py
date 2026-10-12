#!/usr/bin/env python3
"""Replays a dumped welcome-scene frame through a desktop OpenGL ES 3 implementation.

The JVM test SceneFrameDumpTest writes android/build/shots/android/scene3d/gl-frame-<theme>.json:
the frame uniforms, the sorted draws, every buffer and the GLSL ES 3.00 sources of
SceneShaders.kt. This script compiles those shaders with ANGLE (the GLES implementation the
Android emulator ships, Metal or SwiftShader backend), issues the same GL calls as
SceneRenderer.kt (4x MSAA target, resolve, copy into the window surface), reads the frame back
and compares it with the iOS app's Metal rendering in scene3d/src/test/resources/scene3d/.

Writes gl-<theme>.png (premultiplied RGBA, as rendered) and gl-<theme>-side.png
(iOS | GLES | |d| x4) next to the dump. Needs Pillow.

    python3 scene3d/tools/gl_replay.py [--backend metal|swiftshader] [dark|light ...]
"""
import argparse
import ctypes
import json
import os
import sys
from ctypes import POINTER, byref, c_char_p, c_float, c_int, c_uint, c_void_p

from PIL import Image, ImageChops

HERE = os.path.dirname(os.path.abspath(__file__))
ANDROID = os.path.dirname(os.path.dirname(HERE))
SHOTS = os.path.join(ANDROID, "build", "shots", "android", "scene3d")
RESOURCES = os.path.join(ANDROID, "scene3d", "src", "test", "resources", "scene3d")
ANGLE = os.path.join(os.environ.get("ANDROID_HOME", os.path.expanduser("~/Library/Android/sdk")), "emulator", "lib64", "gles_angle")
REFERENCE = {"dark": ("ios-metal-dark-4.5.png", 0x161826), "light": ("ios-metal-light-39.95.png", 0xF3F5FE)}

# GL / EGL constants
GL_ARRAY_BUFFER, GL_ELEMENT_ARRAY_BUFFER, GL_UNIFORM_BUFFER = 0x8892, 0x8893, 0x8A11
GL_STATIC_DRAW, GL_DYNAMIC_DRAW = 0x88E4, 0x88E8
GL_FLOAT, GL_UNSIGNED_BYTE, GL_UNSIGNED_SHORT = 0x1406, 0x1401, 0x1403
GL_VERTEX_SHADER, GL_FRAGMENT_SHADER = 0x8B31, 0x8B30
GL_COMPILE_STATUS, GL_LINK_STATUS = 0x8B81, 0x8B82
GL_TEXTURE_2D, GL_TEXTURE0 = 0x0DE1, 0x84C0
GL_TEXTURE_MIN_FILTER, GL_TEXTURE_MAG_FILTER, GL_TEXTURE_WRAP_S, GL_TEXTURE_WRAP_T = 0x2801, 0x2800, 0x2802, 0x2803
GL_LINEAR, GL_NEAREST, GL_LINEAR_MIPMAP_LINEAR, GL_CLAMP_TO_EDGE = 0x2601, 0x2600, 0x2703, 0x812F
GL_RGBA, GL_RGBA8, GL_SRGB8_ALPHA8, GL_DEPTH_COMPONENT24 = 0x1908, 0x8058, 0x8C43, 0x81A6
GL_FRAMEBUFFER, GL_READ_FRAMEBUFFER, GL_DRAW_FRAMEBUFFER, GL_RENDERBUFFER = 0x8D40, 0x8CA8, 0x8CA9, 0x8D41
GL_COLOR_ATTACHMENT0, GL_DEPTH_ATTACHMENT, GL_FRAMEBUFFER_COMPLETE = 0x8CE0, 0x8D00, 0x8CD5
GL_COLOR_BUFFER_BIT, GL_DEPTH_BUFFER_BIT = 0x4000, 0x0100
GL_DEPTH_TEST, GL_BLEND, GL_CULL_FACE, GL_LEQUAL, GL_FUNC_ADD, GL_CCW, GL_BACK = 0x0B71, 0x0BE2, 0x0B44, 0x0203, 0x8006, 0x0901, 0x0405
GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE = 0x0302, 0x0303, 1
GL_TRIANGLES, GL_TRIANGLE_STRIP = 0x0004, 0x0005
GL_MAX_SAMPLES, GL_RENDERER, GL_VERSION = 0x8D57, 0x1F01, 0x1F02
GL_INVALID_INDEX = 0xFFFFFFFF
EGL_NONE = 0x3038


def load():
    gles = ctypes.CDLL(os.path.join(ANGLE, "libGLESv2.dylib"), mode=ctypes.RTLD_GLOBAL)
    egl = ctypes.CDLL(os.path.join(ANGLE, "libEGL.dylib"), mode=ctypes.RTLD_GLOBAL)
    for name in ("eglGetDisplay", "eglGetPlatformDisplayEXT", "eglCreatePbufferSurface", "eglCreateContext"):
        getattr(egl, name).restype = c_void_p
    egl.eglGetDisplay.argtypes = [c_void_p]
    egl.eglGetPlatformDisplayEXT.argtypes = [c_uint, c_void_p, POINTER(c_int)]
    egl.eglInitialize.argtypes = [c_void_p, POINTER(c_int), POINTER(c_int)]
    egl.eglChooseConfig.argtypes = [c_void_p, POINTER(c_int), POINTER(c_void_p), c_int, POINTER(c_int)]
    egl.eglCreatePbufferSurface.argtypes = [c_void_p, c_void_p, POINTER(c_int)]
    egl.eglCreateContext.argtypes = [c_void_p, c_void_p, c_void_p, POINTER(c_int)]
    egl.eglMakeCurrent.argtypes = [c_void_p, c_void_p, c_void_p, c_void_p]
    gles.glGetString.restype = c_char_p
    gles.glGetUniformLocation.argtypes = [c_uint, c_char_p]
    gles.glGetUniformBlockIndex.argtypes = [c_uint, c_char_p]
    gles.glGetUniformBlockIndex.restype = c_uint
    gles.glVertexAttribPointer.argtypes = [c_uint, c_int, c_uint, c_uint, c_int, c_void_p]
    gles.glDrawElements.argtypes = [c_uint, c_int, c_uint, c_void_p]
    gles.glBufferData.argtypes = [c_uint, ctypes.c_ssize_t, c_void_p, c_uint]
    gles.glBufferSubData.argtypes = [c_uint, ctypes.c_ssize_t, ctypes.c_ssize_t, c_void_p]
    gles.glTexImage2D.argtypes = [c_uint, c_int, c_int, c_int, c_int, c_int, c_uint, c_uint, c_void_p]
    gles.glReadPixels.argtypes = [c_int, c_int, c_int, c_int, c_uint, c_uint, c_void_p]
    gles.glClearColor.argtypes = [c_float] * 4
    gles.glClearDepthf.argtypes = [c_float]
    gles.glUniformMatrix4fv.argtypes = [c_int, c_int, c_uint, POINTER(c_float)]
    gles.glUniform4fv.argtypes = [c_int, c_int, POINTER(c_float)]
    return gles, egl


def ints(values):
    return (c_int * len(values))(*values)


def floats(values):
    return (c_float * len(values))(*values)


def context(egl, backend, width, height):
    if backend == "swiftshader":
        # ANGLE on Vulkan with the SwiftShader ICD, the emulator's software GPU.
        os.environ.setdefault("VK_ICD_FILENAMES", os.path.join(ANGLE, "vk_swiftshader_icd.json"))
        attrs = ints([0x3203, 0x3450, 0x3209, 0x3487, EGL_NONE])
        display = egl.eglGetPlatformDisplayEXT(0x3202, None, attrs)
    else:
        display = egl.eglGetDisplay(None)
    major, minor = c_int(), c_int()
    assert egl.eglInitialize(display, byref(major), byref(minor)), "eglInitialize failed"
    config, count = c_void_p(), c_int()
    attributes = ints([0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3021, 8, 0x3040, 0x40, 0x3033, 0x1, EGL_NONE])
    assert egl.eglChooseConfig(display, attributes, byref(config), 1, byref(count)) and count.value > 0, "no config"
    egl.eglBindAPI(0x30A0)
    surface = egl.eglCreatePbufferSurface(display, config, ints([0x3057, width, 0x3056, height, EGL_NONE]))
    ctx = egl.eglCreateContext(display, config, None, ints([0x3098, 3, EGL_NONE]))
    assert surface and ctx, "EGL surface/context"
    assert egl.eglMakeCurrent(display, surface, surface, ctx), "eglMakeCurrent"


class Replay:
    def __init__(self, gl, dump):
        self.gl = gl
        self.dump = dump
        self.width, self.height = dump["width"], dump["height"]

    def gen(self, fn):
        out = c_uint()
        fn(1, byref(out))
        return out.value

    def shader(self, kind, source):
        gl = self.gl
        s = gl.glCreateShader(kind)
        src = c_char_p(source.encode())
        gl.glShaderSource(s, 1, byref(src), None)
        gl.glCompileShader(s)
        status = c_int()
        gl.glGetShaderiv(s, GL_COMPILE_STATUS, byref(status))
        if not status.value:
            log = ctypes.create_string_buffer(4096)
            gl.glGetShaderInfoLog(s, 4096, None, log)
            raise SystemExit("compile failed:\n" + log.value.decode() + "\n" + source)
        return s

    def program(self, vertex, fragment):
        gl = self.gl
        sh = self.dump["shaders"]
        p = gl.glCreateProgram()
        gl.glAttachShader(p, self.shader(GL_VERTEX_SHADER, sh[vertex]))
        gl.glAttachShader(p, self.shader(GL_FRAGMENT_SHADER, sh[fragment]))
        gl.glLinkProgram(p)
        status = c_int()
        gl.glGetProgramiv(p, GL_LINK_STATUS, byref(status))
        if not status.value:
            log = ctypes.create_string_buffer(4096)
            gl.glGetProgramInfoLog(p, 4096, None, log)
            raise SystemExit("link failed (%s, %s): %s" % (vertex, fragment, log.value.decode()))
        block = gl.glGetUniformBlockIndex(p, b"Frame")
        if block != GL_INVALID_INDEX:
            gl.glUniformBlockBinding(p, block, 0)
        loc = {n: gl.glGetUniformLocation(p, n.encode()) for n in ("uModel", "uNormalMatrix", "uColor", "uParams", "uDot", "uFrame")}
        gl.glUseProgram(p)
        if loc["uDot"] >= 0:
            gl.glUniform1i(loc["uDot"], 0)
        if loc["uFrame"] >= 0:
            gl.glUniform1i(loc["uFrame"], 0)
        return p, loc

    def vbo(self, data, target=GL_ARRAY_BUFFER, kind=c_float):
        gl = self.gl
        b = self.gen(gl.glGenBuffers)
        gl.glBindBuffer(target, b)
        arr = (kind * len(data))(*data)
        gl.glBufferData(target, ctypes.sizeof(arr), arr, GL_STATIC_DRAW)
        return b

    def mesh(self, m, lit):
        gl = self.gl
        vao = self.gen(gl.glGenVertexArrays)
        gl.glBindVertexArray(vao)
        self.vbo(m["vertices"])
        stride = (6 if lit else 3) * 4
        gl.glEnableVertexAttribArray(0)
        gl.glVertexAttribPointer(0, 3, GL_FLOAT, 0, stride, c_void_p(0))
        if lit:
            gl.glEnableVertexAttribArray(1)
            gl.glVertexAttribPointer(1, 3, GL_FLOAT, 0, stride, c_void_p(12))
        self.vbo(m["indices"], GL_ELEMENT_ARRAY_BUFFER, ctypes.c_uint16)
        gl.glBindVertexArray(0)
        return vao, len(m["indices"])

    def instanced(self, data, segments):
        gl = self.gl
        vao = self.gen(gl.glGenVertexArrays)
        gl.glBindVertexArray(vao)
        self.vbo(data)
        attributes = (0, 1) if segments else (0,)
        for a in attributes:
            gl.glEnableVertexAttribArray(a)
            gl.glVertexAttribPointer(a, 3, GL_FLOAT, 0, 24 if segments else 12, c_void_p(a * 12))
            gl.glVertexAttribDivisor(a, 1)
        gl.glBindVertexArray(0)
        return vao, len(data) // (6 if segments else 3)

    def run(self):
        gl, d, w, h = self.gl, self.dump, self.width, self.height
        progs = {
            "lit": self.program("litVertex", "litFragment"),
            "basic": self.program("basicVertex", "basicFragment"),
            "line": self.program("lineVertex", "lineFragment"),
            "ribbon": self.program("ribbonVertex", "ribbonFragment"),
            "sprite": self.program("spriteVertex", "spriteFragment"),
            "points": self.program("pointsVertex", "spriteFragment"),
            "copy": self.program("copyVertex", "copyFragment"),
        }
        b = d["buffers"]
        cup, rim, string = self.mesh(b["cup"], True), self.mesh(b["rim"], False), self.mesh(b["string"], False)
        lines = {k: self.instanced(b[k], True) for k in ("CupWire", "Lattice", "ScanRing", "RelayRing")}
        glow, dust = self.instanced(b["glowLine"], False), self.instanced(b["dust"], False)
        empty = self.gen(gl.glGenVertexArrays)

        ubo = self.gen(gl.glGenBuffers)
        gl.glBindBuffer(GL_UNIFORM_BUFFER, ubo)
        frame = floats(d["frame"])
        gl.glBufferData(GL_UNIFORM_BUFFER, ctypes.sizeof(frame), frame, GL_DYNAMIC_DRAW)
        gl.glBindBufferBase(GL_UNIFORM_BUFFER, 0, ubo)

        dot = self.gen(gl.glGenTextures)
        gl.glBindTexture(GL_TEXTURE_2D, dot)
        pixels = (ctypes.c_ubyte * len(b["dot"]))(*b["dot"])
        gl.glTexImage2D(GL_TEXTURE_2D, 0, GL_SRGB8_ALPHA8, 64, 64, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels)
        gl.glGenerateMipmap(GL_TEXTURE_2D)
        for k, v in ((GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR), (GL_TEXTURE_MAG_FILTER, GL_LINEAR),
                     (GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE), (GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)):
            gl.glTexParameteri(GL_TEXTURE_2D, k, v)

        max_samples = c_int()
        gl.glGetIntegerv(GL_MAX_SAMPLES, byref(max_samples))
        samples = min(d["samples"], max_samples.value)
        resolve_tex = self.gen(gl.glGenTextures)
        gl.glBindTexture(GL_TEXTURE_2D, resolve_tex)
        gl.glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, w, h)
        gl.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        gl.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        resolve = self.gen(gl.glGenFramebuffers)
        gl.glBindFramebuffer(GL_FRAMEBUFFER, resolve)
        gl.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, resolve_tex, 0)
        depth = self.gen(gl.glGenRenderbuffers)
        color = self.gen(gl.glGenRenderbuffers)
        gl.glBindRenderbuffer(GL_RENDERBUFFER, depth)
        gl.glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, GL_DEPTH_COMPONENT24, w, h)
        gl.glBindRenderbuffer(GL_RENDERBUFFER, color)
        gl.glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, GL_RGBA8, w, h)
        msaa = self.gen(gl.glGenFramebuffers)
        gl.glBindFramebuffer(GL_FRAMEBUFFER, msaa)
        gl.glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, color)
        gl.glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depth)
        assert gl.glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE

        gl.glViewport(0, 0, w, h)
        gl.glColorMask(1, 1, 1, 1)
        gl.glDepthMask(1)
        gl.glClearColor(0, 0, 0, 0)
        gl.glClearDepthf(1)
        gl.glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT)
        gl.glEnable(GL_DEPTH_TEST)
        gl.glDepthFunc(GL_LEQUAL)
        gl.glEnable(GL_BLEND)
        gl.glBlendEquation(GL_FUNC_ADD)
        gl.glFrontFace(GL_CCW)
        gl.glCullFace(GL_BACK)
        gl.glActiveTexture(GL_TEXTURE0)
        gl.glBindTexture(GL_TEXTURE_2D, dot)

        def use(name, blend, depth_write, cull):
            p, loc = progs[name]
            gl.glUseProgram(p)
            if blend == "depth":
                gl.glDisable(GL_BLEND)
                gl.glColorMask(0, 0, 0, 0)
            else:
                gl.glColorMask(1, 1, 1, 1)
                gl.glEnable(GL_BLEND)
                if blend == "add":
                    gl.glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE, GL_SRC_ALPHA, GL_ONE)
                else:
                    gl.glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
            gl.glDepthMask(1 if depth_write else 0)
            (gl.glEnable if cull else gl.glDisable)(GL_CULL_FACE)
            return loc

        def set_draw(loc, draw):
            gl.glUniformMatrix4fv(loc["uModel"], 1, 0, floats(draw["model"]))
            gl.glUniform4fv(loc["uColor"], 1, floats(draw["color"]))
            gl.glUniform4fv(loc["uParams"], 1, floats(draw["params"]))

        def elements(vao):
            gl.glBindVertexArray(vao[0])
            gl.glDrawElements(GL_TRIANGLES, vao[1], GL_UNSIGNED_SHORT, None)

        def instances(vao):
            gl.glBindVertexArray(vao[0])
            gl.glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, vao[1])

        for draw in d["draws"]:
            kind = draw["kind"]
            glow_blend = "add" if draw["additive"] else "normal"
            if kind == "Cup":
                loc = use("lit", "normal", True, False)
                set_draw(loc, draw)
                gl.glUniformMatrix4fv(loc["uNormalMatrix"], 1, 0, floats(draw["normalMatrix"]))
                elements(cup)
            elif kind == "Solid":
                set_draw(use("basic", "normal", True, True), draw)
                elements(rim)
            elif kind == "String":
                set_draw(use("basic", "depth", True, True), draw)
                elements(string)
                set_draw(use("ribbon", "normal", False, False), draw)
                gl.glBindVertexArray(empty)
                gl.glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
            elif kind == "Lines":
                set_draw(use("line", "normal", False, False), draw)
                instances(lines[draw["lines"]])
            elif kind == "GlowLine":
                set_draw(use("points", glow_blend, False, False), draw)
                instances(glow)
            elif kind == "Sprite":
                set_draw(use("sprite", glow_blend, False, False), draw)
                gl.glBindVertexArray(empty)
                gl.glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
            elif kind == "Dust":
                set_draw(use("points", "normal", False, False), draw)
                instances(dust)
            gl.glBindVertexArray(0)

        gl.glBindFramebuffer(GL_READ_FRAMEBUFFER, msaa)
        gl.glBindFramebuffer(GL_DRAW_FRAMEBUFFER, resolve)
        gl.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL_COLOR_BUFFER_BIT, GL_NEAREST)
        gl.glBindFramebuffer(GL_FRAMEBUFFER, 0)
        gl.glDisable(GL_DEPTH_TEST)
        gl.glDisable(GL_BLEND)
        gl.glDisable(GL_CULL_FACE)
        gl.glColorMask(1, 1, 1, 1)
        gl.glUseProgram(progs["copy"][0])
        gl.glBindVertexArray(empty)
        gl.glBindTexture(GL_TEXTURE_2D, resolve_tex)
        gl.glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)

        out = (ctypes.c_ubyte * (w * h * 4))()
        gl.glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, out)
        err = gl.glGetError()
        assert err == 0, "GL error 0x%x" % err
        image = Image.frombytes("RGBA", (w, h), bytes(out)).transpose(Image.FLIP_TOP_BOTTOM)
        return image, samples


def pixels(image):
    return list(image.get_flattened_data() if hasattr(image, "get_flattened_data") else image.getdata())


def composite(premultiplied, background):
    bg = ((background >> 16) & 255, (background >> 8) & 255, background & 255)
    out = Image.new("RGB", premultiplied.size)
    out.putdata([tuple(min(255, round(p[c] + bg[c] * (255 - p[3]) / 255)) for c in range(3)) for p in pixels(premultiplied)])
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--backend", choices=("metal", "swiftshader"), default="metal")
    parser.add_argument("themes", nargs="*", default=["dark", "light"])
    args = parser.parse_args()
    gles, egl = load()
    first = json.load(open(os.path.join(SHOTS, "gl-frame-%s.json" % args.themes[0])))
    context(egl, args.backend, first["width"], first["height"])
    print("GL_RENDERER:", gles.glGetString(GL_RENDERER).decode(), "|", gles.glGetString(GL_VERSION).decode())
    worst = 0.0
    for theme in args.themes:
        dump = json.load(open(os.path.join(SHOTS, "gl-frame-%s.json" % theme)))
        image, samples = Replay(gles, dump).run()
        image.save(os.path.join(SHOTS, "gl-%s-%s.png" % (theme, args.backend)))
        name, bg = REFERENCE[theme]
        ios = composite(Image.open(os.path.join(RESOURCES, name)).convert("RGBA"), bg)
        gles_img = composite(image, bg)
        diff = ImageChops.difference(ios, gles_img)
        values = pixels(diff)
        mean = sum(sum(p) for p in values) / (len(values) * 3)
        over = sum(1 for p in values if max(p) > 24) * 100 / len(values)
        side = Image.new("RGB", (image.width * 3, image.height))
        side.paste(ios, (0, 0))
        side.paste(gles_img, (image.width, 0))
        side.paste(diff.point(lambda v: min(255, v * 4)), (image.width * 2, 0))
        side.save(os.path.join(SHOTS, "gl-%s-%s-side.png" % (theme, args.backend)))
        print("%s (%dx MSAA): GLES vs iOS Metal mean |d| %.3f, %.3f%% of pixels over 24" % (theme, samples, mean, over))
        worst = max(worst, mean)
    return 0 if worst < 1.5 else 1


if __name__ == "__main__":
    sys.exit(main())
