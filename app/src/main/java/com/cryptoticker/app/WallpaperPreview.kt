package com.cryptoticker.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** Уменьшенный «телефон» с предпросмотром обоев внутри приложения. */
class WallpaperPreview(context: Context) : View(context), PriceHub.Listener {

    private val clip = Path()
    private val frame = RectF()
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x55FFFFFF
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val pw = (w * 0.58f).toInt()
        setMeasuredDimension(w, (pw * 2.1f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pw = (width * 0.58f).toInt()
        val ph = height
        val left = (width - pw) / 2f
        val r = pw * 0.09f
        frame.set(left, 0f, left + pw, ph.toFloat())
        clip.reset()
        clip.addRoundRect(frame, r, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        canvas.translate(left, 0f)
        WallRenderer.draw(canvas, pw, ph, context, false)
        canvas.restore()
        border.strokeWidth = resources.displayMetrics.density * 1.5f
        canvas.drawRoundRect(frame, r, r, border)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        PriceHub.subscribe(context, this)
    }

    override fun onDetachedFromWindow() {
        PriceHub.unsubscribe(this)
        super.onDetachedFromWindow()
    }

    override fun onPricesUpdated() {
        invalidate()
    }
}
