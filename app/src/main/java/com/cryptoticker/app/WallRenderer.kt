package com.cryptoticker.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Рисует обои с ценами. Все размеры считаются от ширины, поэтому годится и для предпросмотра. */
object WallRenderer {

    /**
     * video != 0 — живое видео (photo — его первый кадр для предпросмотра),
     * photo != 0 — фон из фотографии, иначе вертикальный градиент top→bottom.
     */
    class Theme(val name: String, val photo: Int, val top: Int, val bottom: Int, val video: Int = 0)

    val themes = listOf(
        Theme("Лагуна 4K", R.drawable.bg_lagoon_poster, 0, 0, R.raw.lagoon),
        Theme("Пальма 4K", R.drawable.bg_palm_poster, 0, 0, R.raw.palm),
        Theme("Мальдивы", R.drawable.bg_maldives, 0, 0),
        Theme("Пляж", R.drawable.bg_beach, 0, 0),
        Theme("Океан", 0, 0xFF03263A.toInt(), 0xFF0A6E86.toInt()),
        Theme("Космос", 0, 0xFF0B0F1A.toInt(), 0xFF26124A.toInt()),
        Theme("AMOLED", 0, 0xFF000000.toInt(), 0xFF000000.toInt())
    )

    fun current(ctx: Context): Theme = themes[Prefs.theme(ctx).coerceIn(0, themes.size - 1)]

    /** Анимация фото на Canvas (для видео-темы анимацию делает VideoGl). */
    fun isAnimated(ctx: Context): Boolean {
        val th = current(ctx)
        return th.photo != 0 && th.video == 0 && Prefs.animate(ctx)
    }

    val UP = 0xFF16C784.toInt()
    val DOWN = 0xFFEA3943.toInt()
    private val WHITE = 0xFFFFFFFF.toInt()
    private val WHITE_80 = 0xCCFFFFFF.toInt()
    private val WHITE_65 = 0xA6FFFFFF.toInt()
    private val AMBER = 0xFFFFC107.toInt()

    private val COIN_COLORS = mapOf(
        "BTC" to 0xFFF7931A.toInt(), "ETH" to 0xFF627EEA.toInt(), "SOL" to 0xFF9945FF.toInt(),
        "TON" to 0xFF0098EA.toInt(), "XRP" to 0xFF2E3B4E.toInt(), "BNB" to 0xFFF3BA2F.toInt(),
        "DOGE" to 0xFFC2A633.toInt(), "USDT" to 0xFF26A17B.toInt(), "ADA" to 0xFF0033AD.toInt(),
        "TRX" to 0xFFEB0029.toInt(), "LINK" to 0xFF2A5ADA.toInt(), "PEPE" to 0xFF3C9A3C.toInt()
    )
    private val FALLBACK_COLORS = intArrayOf(
        0xFF0EA5E9.toInt(), 0xFF8B5CF6.toInt(), 0xFFF97316.toInt(), 0xFF14B8A6.toInt(),
        0xFFEC4899.toInt(), 0xFF6366F1.toInt(), 0xFF84CC16.toInt(), 0xFFEAB308.toInt()
    )

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { fontFeatureSettings = "tnum" }
    private val bold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val line = Path()
    private val area = Path()
    private val rect = RectF()
    private val matrix = Matrix()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // ---------- фон ----------

    private class CachedBg(val res: Int, val w: Int, val h: Int, val bmp: Bitmap)

    private val cache = ArrayList<CachedBg>()

    private fun background(ctx: Context, res: Int, w: Int, h: Int): Bitmap? {
        for (c in cache) if (c.res == res && c.w == w && c.h == h) return c.bmp
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeResource(ctx.resources, res, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val need = max(w * 1.12f / bounds.outWidth, h * 1.12f / bounds.outHeight)
            var sample = 1
            while (need * sample * 2 <= 1f) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bmp = BitmapFactory.decodeResource(ctx.resources, res, opts) ?: return null
            if (cache.size >= 2) cache.removeAt(0)
            cache.add(CachedBg(res, w, h, bmp))
            bmp
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    private fun drawBackground(c: Canvas, ctx: Context, th: Theme, W: Float, H: Float, animated: Boolean) {
        val bmp = if (th.photo != 0) background(ctx, th.photo, W.toInt(), H.toInt()) else null
        if (bmp == null) {
            val top = if (th.photo != 0) 0xFF0A8FA0.toInt() else th.top
            val bottom = if (th.photo != 0) 0xFF06445E.toInt() else th.bottom
            fill.shader = LinearGradient(0f, 0f, 0f, H, top, bottom, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, W, H, fill)
            fill.shader = null
            return
        }
        // Медленное «дыхание» и дрейф фото — плавно, без рывков
        val phase = if (animated) (SystemClock.uptimeMillis() % 48_000L) / 48_000.0 * 2.0 * PI else 0.0
        val zoom = 1.06f + 0.035f * sin(phase).toFloat()
        val base = max(W / bmp.width, H / bmp.height)
        val s = base * zoom
        val dx = (W - bmp.width * s) / 2f + W * 0.012f * cos(phase).toFloat()
        val dy = (H - bmp.height * s) / 2f + H * 0.006f * sin(phase * 2).toFloat()
        matrix.setScale(s, s)
        matrix.postTranslate(dx, dy)
        c.drawBitmap(bmp, matrix, bmpPaint)
    }

    // ---------- основная отрисовка ----------

    /** @param overlayOnly только панель с ценами на прозрачном фоне (для видео-обоев) */
    @Synchronized
    fun draw(c: Canvas, w: Int, h: Int, ctx: Context, animated: Boolean, overlayOnly: Boolean = false) {
        if (w <= 0 || h <= 0) return
        val th = current(ctx)
        val W = w.toFloat()
        val H = h.toFloat()

        if (overlayOnly) {
            c.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
        } else {
            // Полностью непрозрачный фон на каждом кадре — никаких «следов»
            c.drawColor(0xFF000000.toInt())
            drawBackground(c, ctx, th, W, H, animated && th.video == 0)
        }

        val u = W / 400f * Prefs.textScale(ctx)
        val pairs = Prefs.pairs(ctx)
        val showChart = Prefs.showChart(ctx)
        val padX = W * 0.05f
        val headerH = 44f * u
        val rowH = 66f * u
        val maxRows = max(1, ((H * 0.6f - headerH) / rowH).toInt())
        val rows = pairs.take(maxRows)
        val blockH = headerH + max(1, rows.size) * rowH + 8f * u

        val minTop = H * 0.08f
        val maxTop = max(minTop, H - blockH - H * 0.05f)
        val top = when (Prefs.position(ctx)) {
            0 -> H * 0.24f
            1 -> (H - blockH) / 2f + H * 0.04f
            else -> H * 0.80f - blockH
        }.coerceIn(minTop, maxTop)

        // Тёмная стеклянная подложка — белый текст читается на любом фоне
        rect.set(padX, top, W - padX, top + blockH)
        val r = 26f * u
        fill.color = if (th.photo != 0) 0xB804151F.toInt() else 0x33FFFFFF
        c.drawRoundRect(rect, r, r, fill)
        stroke.color = 0x2EFFFFFF
        stroke.strokeWidth = max(1f, 1f * u)
        c.drawRoundRect(rect, r, r, stroke)

        val x0 = padX + 16f * u
        val x1 = W - padX - 16f * u

        // Заголовок
        val state = PriceHub.state
        fill.color = when (state) {
            PriceHub.State.ONLINE -> UP
            PriceHub.State.CONNECTING, PriceHub.State.RECONNECTING -> AMBER
            PriceHub.State.IDLE -> WHITE_65
        }
        val headY = top + 27f * u
        c.drawCircle(x0 + 4f * u, headY - 4.5f * u, 4f * u, fill)
        text.typeface = bold
        text.textSize = 12.5f * u
        text.letterSpacing = 0.08f
        text.color = WHITE_80
        text.textAlign = Paint.Align.LEFT
        val title = "LIVE · " + Prefs.exchange(ctx).title.uppercase(Locale.US) + if (showChart) " · 24Ч" else ""
        c.drawText(title, x0 + 14f * u, headY, text)
        text.typeface = medium
        text.letterSpacing = 0f
        text.textAlign = Paint.Align.RIGHT
        val right = when {
            state == PriceHub.State.RECONNECTING -> "переподключение…"
            PriceHub.lastUpdate > 0 -> timeFmt.format(Date(PriceHub.lastUpdate))
            else -> "подключение…"
        }
        c.drawText(right, x1, headY, text)

        if (rows.isEmpty()) {
            text.textAlign = Paint.Align.LEFT
            text.typeface = medium
            text.textSize = 15f * u
            text.color = WHITE
            c.drawText("Добавьте монеты в приложении", x0, top + headerH + 38f * u, text)
            return
        }

        for ((i, p) in rows.withIndex()) {
            val yTop = top + headerH + i * rowH
            fill.color = 0x1FFFFFFF
            c.drawRect(x0, yTop, x1, yTop + max(1f, 0.8f * u), fill)
            drawRow(c, p, yTop, rowH, x0, x1, u, showChart)
        }
    }

    private fun drawRow(c: Canvas, p: CoinPair, yTop: Float, rowH: Float, x0: Float, x1: Float, u: Float, showChart: Boolean) {
        val mid = yTop + rowH / 2f
        val t = PriceHub.price(p.key)

        // Значок монеты
        val badgeR = 15f * u
        val bx = x0 + badgeR
        fill.color = COIN_COLORS[p.base] ?: FALLBACK_COLORS[abs(p.base.hashCode()) % FALLBACK_COLORS.size]
        c.drawCircle(bx, mid, badgeR, fill)
        text.typeface = bold
        text.textAlign = Paint.Align.CENTER
        text.color = WHITE
        text.textSize = (if (p.base.length > 3) 9.5f else 11f) * u
        c.drawText(p.base.take(if (p.base.length > 3) 3 else p.base.length), bx, mid + text.textSize * 0.36f, text)

        // Тикер
        val nameX = x0 + badgeR * 2 + 10f * u
        text.textAlign = Paint.Align.LEFT
        text.typeface = bold
        text.textSize = 18f * u
        text.color = WHITE
        c.drawText(p.base, nameX, mid - 2f * u, text)
        val nameW = text.measureText(p.base)
        text.typeface = regular
        text.textSize = 12f * u
        text.color = WHITE_65
        c.drawText(p.quote, nameX, mid + 15f * u, text)

        // Цена
        text.textAlign = Paint.Align.RIGHT
        text.typeface = bold
        text.textSize = 19f * u
        text.color = WHITE
        val priceStr = if (t != null) Fmt.price(t.price) else "—"
        c.drawText(priceStr, x1, mid - 2f * u, text)
        val priceW = text.measureText(priceStr)

        // Изменение за 24ч — плашка с чётким цветом
        text.typeface = bold
        text.textSize = 11.5f * u
        val chStr = if (t != null) Fmt.change(t.changePct) else "нет данных"
        val chW = text.measureText(chStr)
        val pillPadX = 7f * u
        val pillTop = mid + 5f * u
        val pillBottom = mid + 22f * u
        rect.set(x1 - chW - pillPadX * 2, pillTop, x1, pillBottom)
        fill.color = when {
            t == null -> 0x33FFFFFF
            t.changePct >= 0 -> UP
            else -> DOWN
        }
        c.drawRoundRect(rect, 6f * u, 6f * u, fill)
        text.color = WHITE
        c.drawText(chStr, x1 - pillPadX, pillBottom - 5f * u, text)

        // График за 24 часа (часовые свечи, не прыгает)
        if (!showChart) return
        val closes = PriceHub.chart(p.key) ?: return
        val cx0 = nameX + max(nameW, 44f * u) + 14f * u
        val cx1 = x1 - max(priceW, chW + pillPadX * 2) - 14f * u
        if (cx1 - cx0 < 36f * u) return
        val cy0 = yTop + rowH * 0.22f
        val cy1 = yTop + rowH * 0.78f

        val n = closes.size
        val values = DoubleArray(n)
        for (k in 0 until n) values[k] = closes[k]
        if (t != null) values[n - 1] = t.price
        var lo = values[0]
        var hi = values[0]
        for (v in values) {
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        val span = if (hi - lo > 0) hi - lo else 1.0
        line.reset()
        area.reset()
        for (k in 0 until n) {
            val x = cx0 + (cx1 - cx0) * k / (n - 1)
            val y = cy1 - ((values[k] - lo) / span).toFloat() * (cy1 - cy0)
            if (k == 0) {
                line.moveTo(x, y); area.moveTo(x, cy1); area.lineTo(x, y)
            } else {
                line.lineTo(x, y); area.lineTo(x, y)
            }
        }
        area.lineTo(cx1, cy1)
        area.close()
        val col = if (values[n - 1] >= values[0]) UP else DOWN
        fill.shader = LinearGradient(0f, cy0, 0f, cy1, (col and 0x00FFFFFF) or 0x44000000, col and 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawPath(area, fill)
        fill.shader = null
        stroke.color = col
        stroke.strokeWidth = 2f * u
        c.drawPath(line, stroke)
        val lastY = cy1 - ((values[n - 1] - lo) / span).toFloat() * (cy1 - cy0)
        fill.color = col
        c.drawCircle(cx1, lastY, 2.8f * u, fill)
        fill.color = WHITE
        c.drawCircle(cx1, lastY, 1.2f * u, fill)
    }
}
