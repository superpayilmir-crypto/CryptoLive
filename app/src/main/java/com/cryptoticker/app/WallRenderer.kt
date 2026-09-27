package com.cryptoticker.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Рисует экран с ценами. Все размеры считаются от ширины, поэтому годится и для предпросмотра. */
object WallRenderer {

    class Theme(val name: String, val top: Int, val bottom: Int, val accent: Int, val glow: Boolean)

    val themes = listOf(
        Theme("Космос", 0xFF0B0F1A.toInt(), 0xFF26124A.toInt(), 0xFF8B7CFF.toInt(), true),
        Theme("AMOLED", 0xFF000000.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), false),
        Theme("Океан", 0xFF02111F.toInt(), 0xFF0A4A6E.toInt(), 0xFF4FC3F7.toInt(), true),
        Theme("Изумруд", 0xFF03120D.toInt(), 0xFF0D4634.toInt(), 0xFF45E0A0.toInt(), true),
        Theme("Закат", 0xFF1A0B12.toInt(), 0xFF5A2A1A.toInt(), 0xFFFFA05A.toInt(), true)
    )

    val UP = 0xFF26D07C.toInt()
    val DOWN = 0xFFFF5A67.toInt()
    private val WHITE = 0xFFFFFFFF.toInt()
    private val MUTED = 0xA6FFFFFF.toInt()
    private val AMBER = 0xFFFFC107.toInt()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { fontFeatureSettings = "tnum" }
    private val bold: Typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private val regular: Typeface = Typeface.DEFAULT
    private val path = Path()
    private val rect = RectF()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun draw(c: Canvas, w: Int, h: Int, ctx: Context) {
        if (w <= 0 || h <= 0) return
        val th = themes[Prefs.theme(ctx).coerceIn(0, themes.size - 1)]
        val W = w.toFloat()
        val H = h.toFloat()

        // Фон
        fill.shader = LinearGradient(0f, 0f, 0f, H, th.top, th.bottom, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W, H, fill)
        if (th.glow) {
            val glowColor = (th.accent and 0x00FFFFFF) or 0x2E000000
            fill.shader = RadialGradient(W * 0.85f, H * 0.18f, W * 0.8f, glowColor, 0, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, W, H, fill)
            fill.shader = RadialGradient(W * 0.1f, H * 0.85f, W * 0.7f, glowColor, 0, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, W, H, fill)
        }
        fill.shader = null

        val u = W / 400f * Prefs.textScale(ctx)
        val pairs = Prefs.pairs(ctx)
        val padX = W * 0.06f
        val headerH = 42f * u
        val rowH = 62f * u
        val maxRows = max(1, ((H * 0.62f - headerH) / rowH).toInt())
        val rows = pairs.take(maxRows)
        val blockH = headerH + max(1, rows.size) * rowH + 10f * u

        val minTop = H * 0.08f
        val maxTop = max(minTop, H - blockH - H * 0.04f)
        val top = when (Prefs.position(ctx)) {
            0 -> H * 0.30f
            1 -> (H - blockH) / 2f
            else -> H * 0.84f - blockH
        }.coerceIn(minTop, maxTop)

        // Карточка
        rect.set(padX, top, W - padX, top + blockH)
        fill.color = 0x1CFFFFFF
        c.drawRoundRect(rect, 24f * u, 24f * u, fill)
        stroke.color = 0x22FFFFFF
        stroke.strokeWidth = max(1f, 1.2f * u)
        c.drawRoundRect(rect, 24f * u, 24f * u, stroke)

        val x0 = padX + 18f * u
        val x1 = W - padX - 18f * u

        // Заголовок
        val state = PriceHub.state
        fill.color = when (state) {
            PriceHub.State.ONLINE -> UP
            PriceHub.State.CONNECTING, PriceHub.State.RECONNECTING -> AMBER
            PriceHub.State.IDLE -> MUTED
        }
        val headY = top + 26f * u
        c.drawCircle(x0 + 4f * u, headY - 4f * u, 4f * u, fill)
        text.typeface = bold
        text.textSize = 12f * u
        text.letterSpacing = 0.12f
        text.color = MUTED
        text.textAlign = Paint.Align.LEFT
        c.drawText("LIVE · " + Prefs.exchange(ctx).title.uppercase(Locale.US), x0 + 14f * u, headY, text)
        text.typeface = regular
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
            text.textSize = 15f * u
            text.color = WHITE
            c.drawText("Добавьте монеты в приложении", x0, top + headerH + 36f * u, text)
            return
        }

        for ((i, p) in rows.withIndex()) {
            val yTop = top + headerH + i * rowH
            if (i > 0) {
                fill.color = 0x14FFFFFF
                c.drawRect(x0, yTop, x1, yTop + max(1f, u), fill)
            }
            val b1 = yTop + 29f * u
            val b2 = yTop + 49f * u
            val t = PriceHub.price(p.key)
            val chColor = if (t == null) MUTED else if (t.changePct >= 0) UP else DOWN

            // Название
            text.textAlign = Paint.Align.LEFT
            text.typeface = bold
            text.textSize = 19f * u
            text.color = WHITE
            c.drawText(p.base, x0, b1, text)
            text.typeface = regular
            text.textSize = 11.5f * u
            text.color = MUTED
            c.drawText("/" + p.quote, x0, b2, text)

            // Цена и изменение
            text.textAlign = Paint.Align.RIGHT
            text.typeface = bold
            text.textSize = 19f * u
            text.color = WHITE
            val priceStr = if (t != null) Fmt.price(t.price) else "—"
            c.drawText(priceStr, x1, b1, text)
            val priceW = text.measureText(priceStr)
            text.typeface = regular
            text.textSize = 13f * u
            text.color = chColor
            c.drawText(if (t != null) Fmt.change(t.changePct) else "нет данных", x1, b2, text)

            // Мини-график
            val hist = PriceHub.history(p.key)
            val sx0 = x0 + (x1 - x0) * 0.30f
            val sx1 = min(x0 + (x1 - x0) * 0.56f, x1 - priceW - 14f * u)
            if (hist.size >= 2 && sx1 - sx0 > 24f * u) {
                val sy0 = yTop + 16f * u
                val sy1 = yTop + rowH - 16f * u
                var lo = hist[0]
                var hi = hist[0]
                for (v in hist) {
                    if (v < lo) lo = v
                    if (v > hi) hi = v
                }
                val span = if (hi - lo > 0) hi - lo else 1.0
                path.reset()
                val n = hist.size
                for (k in 0 until n) {
                    val x = sx0 + (sx1 - sx0) * k / (n - 1)
                    val y = sy1 - ((hist[k] - lo) / span).toFloat() * (sy1 - sy0)
                    if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                val trendUp = hist[n - 1] >= hist[0]
                stroke.color = ((if (trendUp) UP else DOWN) and 0x00FFFFFF) or (0xCC shl 24)
                stroke.strokeWidth = 2f * u
                c.drawPath(path, stroke)
            }
        }
    }
}
