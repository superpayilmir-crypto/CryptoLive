package com.cryptoticker.app

import android.content.Context
import java.math.BigDecimal
import java.math.MathContext
import java.util.Locale
import kotlin.math.abs

enum class Exchange(val title: String) {
    BINANCE("Binance"),
    BYBIT("Bybit"),
    OKX("OKX")
}

/** Торговая пара, например BTC/USDT. */
data class CoinPair(val base: String, val quote: String) {
    val key: String get() = "$base/$quote"

    fun symbolFor(ex: Exchange): String = when (ex) {
        Exchange.OKX -> "$base-$quote"
        Exchange.BINANCE, Exchange.BYBIT -> base + quote
    }

    companion object {
        private val VALID = Regex("^[A-Z0-9]{1,20}$")
        private val QUOTES = listOf("FDUSD", "USDT", "USDC", "TUSD", "EUR", "TRY", "BTC", "ETH", "BNB")

        fun fromKey(k: String): CoinPair? {
            val p = k.split("/")
            if (p.size != 2) return null
            if (!VALID.matches(p[0]) || !VALID.matches(p[1])) return null
            return CoinPair(p[0], p[1])
        }

        /** "sol" -> SOL/USDT, "eth/btc" -> ETH/BTC, "PEPEUSDC" -> PEPE/USDC */
        fun parse(input: String): CoinPair? {
            val s = input.trim().uppercase(Locale.US).replace(" ", "")
            if (s.isEmpty()) return null
            val parts = s.split('/', '-', '_').filter { it.isNotEmpty() }
            if (parts.size == 2) {
                return if (VALID.matches(parts[0]) && VALID.matches(parts[1])) CoinPair(parts[0], parts[1]) else null
            }
            if (parts.size != 1) return null
            val one = parts[0]
            if (!VALID.matches(one)) return null
            for (q in QUOTES) {
                if (one.length - q.length >= 2 && one.endsWith(q)) {
                    return CoinPair(one.removeSuffix(q), q)
                }
            }
            return CoinPair(one, "USDT")
        }
    }
}

/** time = 1 означает «снимок из REST», иначе — время прихода из WebSocket. */
data class Ticker(val price: Double, val changePct: Double, val time: Long)

object Fmt {
    fun price(p: Double): String {
        val s = when {
            p >= 1000 -> String.format(Locale.US, "%,.2f", p)
            p >= 100 -> String.format(Locale.US, "%.2f", p)
            p >= 1 -> String.format(Locale.US, "%.4f", p)
            p >= 0.01 -> String.format(Locale.US, "%.5f", p)
            p > 0 -> BigDecimal(p).round(MathContext(4)).toPlainString()
            else -> "0"
        }
        return s.replace(",", " ")
    }

    fun change(c: Double): String =
        (if (c >= 0) "▲ " else "▼ ") + String.format(Locale.US, "%.2f%%", abs(c))
}

object Prefs {
    private const val DEFAULT_PAIRS = "BTC/USDT,ETH/USDT,SOL/USDT,TON/USDT,XRP/USDT"

    private fun sp(c: Context) =
        c.applicationContext.getSharedPreferences("crypto_live", Context.MODE_PRIVATE)

    fun pairs(c: Context): List<CoinPair> =
        (sp(c).getString("pairs", DEFAULT_PAIRS) ?: DEFAULT_PAIRS)
            .split(",")
            .mapNotNull { CoinPair.fromKey(it.trim()) }
            .distinctBy { it.key }

    fun setPairs(c: Context, list: List<CoinPair>) {
        sp(c).edit().putString("pairs", list.joinToString(",") { it.key }).apply()
    }

    fun exchange(c: Context): Exchange =
        runCatching { Exchange.valueOf(sp(c).getString("exchange", "BINANCE") ?: "BINANCE") }
            .getOrDefault(Exchange.BINANCE)

    fun setExchange(c: Context, e: Exchange) {
        sp(c).edit().putString("exchange", e.name).apply()
    }

    fun theme(c: Context): Int = sp(c).getInt("wp_theme2", 0)
    fun setTheme(c: Context, v: Int) { sp(c).edit().putInt("wp_theme2", v).apply() }

    /** 0 — выше, 1 — по центру, 2 — ниже */
    fun position(c: Context): Int = sp(c).getInt("wp_pos2", 1)
    fun setPosition(c: Context, v: Int) { sp(c).edit().putInt("wp_pos2", v).apply() }

    fun animate(c: Context): Boolean = sp(c).getBoolean("wp_anim", true)
    fun setAnimate(c: Context, v: Boolean) { sp(c).edit().putBoolean("wp_anim", v).apply() }

    /** Цены только на экране блокировки; на рабочем столе — чистый живой фон. */
    fun lockOnly(c: Context): Boolean = sp(c).getBoolean("wp_lock_only", true)
    fun setLockOnly(c: Context, v: Boolean) { sp(c).edit().putBoolean("wp_lock_only", v).apply() }

    fun showChart(c: Context): Boolean = sp(c).getBoolean("wp_chart", true)
    fun setShowChart(c: Context, v: Boolean) { sp(c).edit().putBoolean("wp_chart", v).apply() }

    fun textScale(c: Context): Float = sp(c).getFloat("wp_scale", 1f)
    fun setTextScale(c: Context, v: Float) { sp(c).edit().putFloat("wp_scale", v).apply() }

    fun notifyEnabled(c: Context): Boolean = sp(c).getBoolean("notify", false)
    fun setNotifyEnabled(c: Context, v: Boolean) { sp(c).edit().putBoolean("notify", v).apply() }
}
