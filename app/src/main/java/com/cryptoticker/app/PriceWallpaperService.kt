package com.cryptoticker.app

import android.graphics.Canvas
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder

/** Живые обои: показывают цены на рабочем столе и экране блокировки. Сеть работает, только пока обои видны. */
class PriceWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = TickerEngine()

    inner class TickerEngine : Engine(), PriceHub.Listener {
        private var visible = false
        private var width = 0
        private var height = 0

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                PriceHub.subscribe(applicationContext, this)
                drawFrame()
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

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            PriceHub.unsubscribe(this)
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            PriceHub.unsubscribe(this)
            super.onDestroy()
        }

        override fun onPricesUpdated() {
            if (visible) drawFrame()
        }

        private fun drawFrame() {
            if (width <= 0 || height <= 0) return
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) WallRenderer.draw(canvas, width, height, applicationContext)
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
