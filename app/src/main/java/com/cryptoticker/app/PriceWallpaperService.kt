package com.cryptoticker.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Живые обои с ценами.
 *  - тема «Живое видео»: зацикленное видео через OpenGL (VideoGl) + цены поверх;
 *  - остальные темы: фото/градиент на Canvas с плавной анимацией.
 * Сеть, видео и анимация работают, только пока обои видны.
 */
class PriceWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = TickerEngine()

    inner class TickerEngine : Engine(), PriceHub.Listener {
        private val main = Handler(Looper.getMainLooper())
        private var visible = false
        private var surfaceReady = false
        private var width = 0
        private var height = 0
        private var useHardware = true

        // --- видео-режим ---
        private var videoMode = false
        private var videoFailed = false
        private var glThread: HandlerThread? = null
        private var glHandler: Handler? = null
        private var gl: VideoGl? = null // только из GL-потока
        private val overlayLock = Any()
        private var overlay: Bitmap? = null
        private var lastOverlay = 0L

        private val frame = object : Runnable {
            override fun run() {
                if (!visible || videoMode) return
                drawCanvasFrame()
                if (WallRenderer.isAnimated(applicationContext)) main.postDelayed(this, 40L)
            }
        }

        private val overlayRunnable = Runnable { updateOverlay() }

        // ---------- жизненный цикл ----------

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            surfaceReady = true
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            this.width = width
            this.height = height
            if (videoMode) {
                glHandler?.post { gl?.setSize(width, height) }
                updateOverlay()
            }
            applyMode()
            redraw()
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            redraw()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            main.removeCallbacks(frame)
            if (visible) {
                PriceHub.subscribe(applicationContext, this)
                applyMode()
                if (videoMode) {
                    val animate = Prefs.animate(applicationContext)
                    glHandler?.post { if (animate) gl?.play() else gl?.pause(); gl?.render() }
                    updateOverlay()
                } else {
                    main.post(frame)
                }
            } else {
                PriceHub.unsubscribe(this)
                glHandler?.post { gl?.pause() }
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            surfaceReady = false
            main.removeCallbacks(frame)
            PriceHub.unsubscribe(this)
            stopVideo()
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            main.removeCallbacks(frame)
            main.removeCallbacks(overlayRunnable)
            PriceHub.unsubscribe(this)
            stopVideo()
            overlay = null
            super.onDestroy()
        }

        override fun onPricesUpdated() {
            if (!visible) return
            applyMode()
            if (videoMode) {
                val animate = Prefs.animate(applicationContext)
                glHandler?.post { if (animate) gl?.play() else gl?.pause() }
                // обновляем картинку с ценами не чаще 2 раз в секунду
                val since = SystemClock.uptimeMillis() - lastOverlay
                main.removeCallbacks(overlayRunnable)
                if (since >= 500L) updateOverlay() else main.postDelayed(overlayRunnable, 500L - since)
            } else if (WallRenderer.isAnimated(applicationContext)) {
                main.removeCallbacks(frame)
                main.post(frame)
            } else {
                drawCanvasFrame()
            }
        }

        private fun redraw() {
            if (videoMode) glHandler?.post { gl?.render() } else drawCanvasFrame()
        }

        // ---------- выбор режима ----------

        private fun applyMode() {
            val wantVideo = WallRenderer.current(applicationContext).video != 0 && !videoFailed
            if (wantVideo && !videoMode && surfaceReady && width > 0 && height > 0) {
                startVideo()
            } else if (!wantVideo && videoMode) {
                stopVideo()
                main.removeCallbacks(frame)
                if (visible) main.post(frame)
            }
        }

        private fun startVideo() {
            val res = WallRenderer.current(applicationContext).video
            val thread = HandlerThread("wallpaper-gl").also { it.start() }
            val h = Handler(thread.looper)
            glThread = thread
            glHandler = h
            videoMode = true
            val w = width
            val hh = height
            val holderSurface = surfaceHolder.surface
            val shouldPlay = visible && Prefs.animate(applicationContext)
            h.post {
                val g = VideoGl(applicationContext)
                val ok = g.start(holderSurface, res, h) { gl?.render() }
                if (ok) {
                    gl = g
                    g.setSize(w, hh)
                    if (shouldPlay) g.play()
                    synchronized(overlayLock) { overlay?.let { g.uploadOverlay(it) } }
                    g.render()
                } else {
                    g.release()
                    main.post {
                        videoFailed = true // устройство не тянет видео — показываем фото
                        stopVideo()
                        if (visible) main.post(frame)
                    }
                }
            }
            updateOverlay()
        }

        private fun stopVideo() {
            val h = glHandler ?: run { videoMode = false; return }
            val latch = CountDownLatch(1)
            h.post {
                gl?.release()
                gl = null
                latch.countDown()
            }
            try {
                latch.await(2, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
            }
            glThread?.quitSafely()
            glThread = null
            glHandler = null
            videoMode = false
        }

        private fun updateOverlay() {
            if (!videoMode || width <= 0 || height <= 0) return
            lastOverlay = SystemClock.uptimeMillis()
            synchronized(overlayLock) {
                var bmp = overlay
                if (bmp == null || bmp.width != width || bmp.height != height) {
                    bmp = try {
                        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    } catch (e: OutOfMemoryError) {
                        null
                    }
                    overlay = bmp
                }
                if (bmp == null) return
                WallRenderer.draw(Canvas(bmp), width, height, applicationContext, false, true)
            }
            glHandler?.post {
                val g = gl ?: return@post
                synchronized(overlayLock) { overlay?.let { g.uploadOverlay(it) } }
                g.render()
            }
        }

        // ---------- Canvas-режим (фото) ----------

        private fun drawCanvasFrame() {
            if (videoMode || width <= 0 || height <= 0) return
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = if (useHardware) {
                    try {
                        holder.lockHardwareCanvas()
                    } catch (e: Exception) {
                        useHardware = false
                        holder.lockCanvas()
                    }
                } else {
                    holder.lockCanvas()
                }
                if (canvas != null) {
                    WallRenderer.draw(canvas, width, height, applicationContext, true)
                }
            } catch (_: Exception) {
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }
}
