package com.cryptoticker.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Проигрывает зацикленное видео на поверхности обоев через OpenGL ES 2
 * и накладывает сверху прозрачную картинку с ценами.
 * Все методы вызываются только из одного GL-потока.
 */
class VideoGl(private val ctx: Context) {

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var videoProgram = 0
    private var overlayProgram = 0
    private var videoTex = 0
    private var overlayTex = 0
    private var overlayW = 0
    private var overlayH = 0
    private var overlayValid = false

    private var surfaceTexture: SurfaceTexture? = null
    private var videoSurface: Surface? = null
    private var player: MediaPlayer? = null
    private var frameAvailable = false
    private val texMatrix = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }

    private var width = 0
    private var height = 0
    private var videoW = 0
    private var videoH = 0

    private val pos: FloatBuffer = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private var videoUv: FloatBuffer = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    private val overlayUv: FloatBuffer = floats(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
    private val identity = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }

    val isReady: Boolean get() = surface != EGL14.EGL_NO_SURFACE

    /** @param onFrame вызывается (в GL-потоке через handler) при каждом новом кадре видео */
    fun start(window: Any, videoRes: Int, handler: android.os.Handler, onFrame: () -> Unit): Boolean {
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return false
            val ver = IntArray(2)
            if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) return false
            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) || num[0] == 0) return false
            val cfg = configs[0] ?: return false
            context = EGL14.eglCreateContext(
                display, cfg, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            if (context == EGL14.EGL_NO_CONTEXT) return false
            surface = EGL14.eglCreateWindowSurface(display, cfg, window, intArrayOf(EGL14.EGL_NONE), 0)
            if (surface == EGL14.EGL_NO_SURFACE) return false
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return false

            videoProgram = program(VERTEX, FRAG_OES)
            overlayProgram = program(VERTEX, FRAG_2D)
            if (videoProgram == 0 || overlayProgram == 0) return false

            val t = IntArray(2)
            GLES20.glGenTextures(2, t, 0)
            videoTex = t[0]
            overlayTex = t[1]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTex)
            texParams(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTex)
            texParams(GLES20.GL_TEXTURE_2D)

            val st = SurfaceTexture(videoTex)
            st.setOnFrameAvailableListener({
                frameAvailable = true
                onFrame()
            }, handler)
            surfaceTexture = st
            val vs = Surface(st)
            videoSurface = vs

            val afd = ctx.resources.openRawResourceFd(videoRes) ?: return false
            val mp = MediaPlayer()
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            mp.setSurface(vs)
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.prepare()
            videoW = mp.videoWidth
            videoH = mp.videoHeight
            player = mp
            updateCrop()
            return true
        } catch (e: Exception) {
            release()
            return false
        }
    }

    fun setSize(w: Int, h: Int) {
        width = w
        height = h
        if (isReady) GLES20.glViewport(0, 0, w, h)
        updateCrop()
    }

    fun play() {
        try { player?.let { if (!it.isPlaying) it.start() } } catch (_: Exception) {}
    }

    fun pause() {
        try { player?.let { if (it.isPlaying) it.pause() } } catch (_: Exception) {}
    }

    /** Загрузить картинку с ценами (вызывать в GL-потоке). */
    fun uploadOverlay(bmp: Bitmap) {
        if (!isReady || bmp.isRecycled) return
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTex)
        if (bmp.width != overlayW || bmp.height != overlayH) {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            overlayW = bmp.width
            overlayH = bmp.height
        } else {
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bmp)
        }
        overlayValid = true
    }

    fun render() {
        if (!isReady || width <= 0 || height <= 0) return
        val st = surfaceTexture ?: return
        if (frameAvailable) {
            frameAvailable = false
            try {
                st.updateTexImage()
                st.getTransformMatrix(texMatrix)
            } catch (_: Exception) {
            }
        }
        GLES20.glClearColor(0.02f, 0.25f, 0.33f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glDisable(GLES20.GL_BLEND)
        draw(videoProgram, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTex, videoUv, texMatrix)
        if (overlayValid) {
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            draw(overlayProgram, GLES20.GL_TEXTURE_2D, overlayTex, overlayUv, identity)
        }
        EGL14.eglSwapBuffers(display, surface)
    }

    fun release() {
        try { player?.release() } catch (_: Exception) {}
        player = null
        try { videoSurface?.release() } catch (_: Exception) {}
        videoSurface = null
        try { surfaceTexture?.release() } catch (_: Exception) {}
        surfaceTexture = null
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        }
        surface = EGL14.EGL_NO_SURFACE
        context = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
        overlayValid = false
        overlayW = 0
        overlayH = 0
    }

    // ---------- внутреннее ----------

    /** Обрезка видео по центру, чтобы заполнить экран без искажений. */
    private fun updateCrop() {
        if (width <= 0 || height <= 0 || videoW <= 0 || videoH <= 0) return
        val va = videoW.toFloat() / videoH
        val sa = width.toFloat() / height
        var x0 = 0f
        var x1 = 1f
        var y0 = 0f
        var y1 = 1f
        if (va > sa) {
            val f = sa / va
            x0 = (1f - f) / 2f; x1 = 1f - x0
        } else {
            val f = va / sa
            y0 = (1f - f) / 2f; y1 = 1f - y0
        }
        videoUv = floats(x0, y0, x1, y0, x0, y1, x1, y1)
    }

    private fun draw(prog: Int, target: Int, tex: Int, uv: FloatBuffer, m: FloatArray) {
        GLES20.glUseProgram(prog)
        val aPos = GLES20.glGetAttribLocation(prog, "aPos")
        val aTex = GLES20.glGetAttribLocation(prog, "aTex")
        val uM = GLES20.glGetUniformLocation(prog, "uTexM")
        val uS = GLES20.glGetUniformLocation(prog, "sTex")
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, tex)
        GLES20.glUniform1i(uS, 0)
        GLES20.glUniformMatrix4fv(uM, 1, false, m, 0)
        pos.position(0)
        uv.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, pos)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
    }

    private fun texParams(target: Int) {
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun program(vs: String, fs: String): Int {
        val v = shader(GLES20.GL_VERTEX_SHADER, vs)
        val f = shader(GLES20.GL_FRAGMENT_SHADER, fs)
        if (v == 0 || f == 0) return 0
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            GLES20.glDeleteProgram(p)
            return 0
        }
        return p
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            GLES20.glDeleteShader(s)
            return 0
        }
        return s
    }

    companion object {
        private val VERTEX = """
            attribute vec4 aPos;
            attribute vec2 aTex;
            uniform mat4 uTexM;
            varying vec2 vTex;
            void main() {
                gl_Position = aPos;
                vTex = (uTexM * vec4(aTex, 0.0, 1.0)).xy;
            }
        """.trimIndent()
        private val FRAG_OES = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES sTex;
            void main() { gl_FragColor = texture2D(sTex, vTex); }
        """.trimIndent()
        private val FRAG_2D = """
            precision mediump float;
            varying vec2 vTex;
            uniform sampler2D sTex;
            void main() { gl_FragColor = texture2D(sTex, vTex); }
        """.trimIndent()

        private fun floats(vararg v: Float): FloatBuffer {
            val b = ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            b.put(v)
            b.position(0)
            return b
        }
    }
}
