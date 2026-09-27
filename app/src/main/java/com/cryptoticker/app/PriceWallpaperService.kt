package com.cryptoticker.app

import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder

/**
 * Живые обои: фото с плавной анимацией + цены в реальном времени.
 * Сеть и анимация работают, только пока обои видны.
 */
class PriceWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = TickerEngine()

    inner class TickerEngine : Engine(), PriceHub.Listener {
        private val handler = Handler(Looper.getMainLooper())
        private var visible = false
        private var width = 0
        private var height = 0
        private var useHardware = true

        private val frame = object : Runnable {
            override fun run() {
                if (!visible) return
                drawFrame()
                if (WallRenderer.isAnimated(applicationContext)) handler.postDelayed(this, 40L)
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            handler.removeCallbacks(frame)
            if (visible) {
                PriceHub.subscribe(applicationContext, this)
                handler.post(frame)
            } else {
                PriceHub.unsubscribe(this)
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            this.width = width
            this.height = height
            drawFrame()
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            drawFrame()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            handler.removeCallbacks(frame)
            PriceHub.unsubscribe(this)
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            handler.removeCallbacks(frame)
            PriceHub.unsubscribe(this)
            super.onDestroy()
        }

        override fun onPricesUpdated() {
            if (!visible) return
            if (WallRenderer.isAnimated(applicationContext)) {
                // кадры и так идут; если цикл остановлен (сменили настройку) — перезапустить
                handler.removeCallbacks(frame)
                handler.post(frame)
            } else {
                drawFrame()
            }
        }

        private fun drawFrame() {
            if (width <= 0 || height <= 0) return
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
