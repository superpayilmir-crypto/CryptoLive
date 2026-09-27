package com.cryptoticker.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Единое WebSocket-подключение к бирже на весь процесс.
 * Подключено, пока есть хотя бы один подписчик (экран приложения, видимые обои или уведомление).
 * Всё состояние меняется только в главном потоке.
 */
object PriceHub {

    interface Listener {
        fun onPricesUpdated()
    }

    enum class State { IDLE, CONNECTING, ONLINE, RECONNECTING }

    val http: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private const val CHART_REFRESH_MS = 10 * 60_000L

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<Listener>()
    private val prices = HashMap<String, Ticker>()
    private val charts = HashMap<String, List<Double>>()
    private val pending = ConcurrentHashMap<String, Ticker>()
    private val flushScheduled = AtomicBoolean(false)

    private var appContext: Context? = null
    private var socket: WebSocket? = null

    @Volatile
    private var generation = 0
    private var connectedExchange: Exchange = Exchange.BINANCE
    private var connectedPairs: List<CoinPair> = emptyList()
    private var retryMs = 1_000L

    var state: State = State.IDLE
        private set
    var lastUpdate: Long = 0L
        private set
    val exchange: Exchange get() = connectedExchange

    fun price(key: String): Ticker? = prices[key]
    /** Часовые цены закрытия за последние 24 часа (старые → новые). */
    fun chart(key: String): List<Double>? = charts[key]

    fun subscribe(ctx: Context, l: Listener) {
        appContext = ctx.applicationContext
        val wasEmpty = listeners.isEmpty()
        listeners.add(l)
        if (wasEmpty && socket == null) connect()
        l.onPricesUpdated()
    }

    fun unsubscribe(l: Listener) {
        if (!listeners.remove(l)) return
        if (listeners.isEmpty()) disconnect()
    }

    /** Вызывать после изменения списка монет или биржи. */
    fun reload(ctx: Context) {
        appContext = ctx.applicationContext
        val ex = Prefs.exchange(ctx)
        val pairs = Prefs.pairs(ctx)
        if (listeners.isNotEmpty() && (ex != connectedExchange || pairs != connectedPairs)) {
            disconnect()
            connect()
        } else {
            notifyListeners()
        }
    }

    fun notifyListeners() {
        for (l in listeners.toList()) l.onPricesUpdated()
    }

    private fun connect() {
        val ctx = appContext ?: return
        val ex = Prefs.exchange(ctx)
        val pairs = Prefs.pairs(ctx)
        if (ex != connectedExchange) {
            prices.clear(); charts.clear(); pending.clear()
        }
        connectedExchange = ex
        connectedPairs = pairs
        main.removeCallbacks(reconnectRunnable)
        main.removeCallbacks(pingRunnable)
        main.removeCallbacks(chartRunnable)
        val gen = ++generation

        if (pairs.isEmpty()) {
            state = State.IDLE
            notifyListeners()
            return
        }

        val symbolToKey = HashMap<String, String>()
        for (p in pairs) symbolToKey[p.symbolFor(ex)] = p.key

        val url = when (ex) {
            Exchange.BINANCE -> "wss://stream.binance.com:9443/stream?streams=" +
                pairs.joinToString("/") { it.symbolFor(ex).lowercase() + "@miniTicker" }
            Exchange.BYBIT -> "wss://stream.bybit.com/v5/public/spot"
            Exchange.OKX -> "wss://ws.okx.com:8443/ws/v5/public"
        }

        if (state != State.RECONNECTING) state = State.CONNECTING
        notifyListeners()

        if (ex == Exchange.BINANCE) fetchBinanceSnapshot(pairs, gen)
        fetchCharts(ex, pairs, gen)
        main.postDelayed(chartRunnable, CHART_REFRESH_MS)

        socket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (gen != generation) return
                when (ex) {
                    Exchange.BYBIT -> for (chunk in pairs.chunked(10)) {
                        val args = JSONArray()
                        for (p in chunk) args.put("tickers." + p.symbolFor(ex))
                        webSocket.send(JSONObject().put("op", "subscribe").put("args", args).toString())
                    }
                    Exchange.OKX -> {
                        val args = JSONArray()
                        for (p in pairs) {
                            args.put(JSONObject().put("channel", "tickers").put("instId", p.symbolFor(ex)))
                        }
                        webSocket.send(JSONObject().put("op", "subscribe").put("args", args).toString())
                    }
                    Exchange.BINANCE -> Unit
                }
                main.post {
                    if (gen != generation) return@post
                    state = State.ONLINE
                    retryMs = 1_000L
                    if (ex != Exchange.BINANCE) main.postDelayed(pingRunnable, 20_000L)
                    notifyListeners()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen != generation) return
                try {
                    parse(ex, text, symbolToKey)
                } catch (_: Exception) {
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onDrop(gen)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onDrop(gen)
            }
        })
    }

    private fun disconnect() {
        generation++
        main.removeCallbacks(reconnectRunnable)
        main.removeCallbacks(pingRunnable)
        main.removeCallbacks(chartRunnable)
        socket?.close(1000, "bye")
        socket = null
        state = State.IDLE
    }

    private fun onDrop(gen: Int) {
        main.post {
            if (gen != generation) return@post
            socket = null
            main.removeCallbacks(pingRunnable)
            if (listeners.isEmpty()) {
                state = State.IDLE
                return@post
            }
            state = State.RECONNECTING
            notifyListeners()
            main.postDelayed(reconnectRunnable, retryMs)
            retryMs = (retryMs * 2).coerceAtMost(30_000L)
        }
    }

    private val chartRunnable: Runnable = object : Runnable {
        override fun run() {
            if (listeners.isEmpty()) return
            fetchCharts(connectedExchange, connectedPairs, generation)
            main.postDelayed(this, CHART_REFRESH_MS)
        }
    }

    private val reconnectRunnable = Runnable {
        if (listeners.isNotEmpty()) connect()
    }

    private val pingRunnable: Runnable = object : Runnable {
        override fun run() {
            val s = socket ?: return
            when (connectedExchange) {
                Exchange.BYBIT -> s.send("{\"op\":\"ping\"}")
                Exchange.OKX -> s.send("ping")
                Exchange.BINANCE -> Unit
            }
            main.postDelayed(this, 20_000L)
        }
    }

    // ---------- разбор сообщений (поток OkHttp) ----------

    private fun parse(ex: Exchange, text: String, map: Map<String, String>) {
        val now = System.currentTimeMillis()
        when (ex) {
            Exchange.BINANCE -> {
                val o = JSONObject(text)
                val d = o.optJSONObject("data") ?: o
                val key = map[d.optString("s")] ?: return
                val c = d.optString("c").toDoubleOrNull() ?: return
                val open = d.optString("o").toDoubleOrNull() ?: 0.0
                offer(key, Ticker(c, if (open > 0) (c - open) / open * 100.0 else 0.0, now))
            }
            Exchange.BYBIT -> {
                val o = JSONObject(text)
                if (!o.optString("topic").startsWith("tickers.")) return
                val d = o.optJSONObject("data") ?: return
                val key = map[d.optString("symbol")] ?: return
                val c = d.optString("lastPrice").toDoubleOrNull() ?: return
                val pct = d.optString("price24hPcnt").toDoubleOrNull()?.times(100.0) ?: Double.NaN
                offer(key, Ticker(c, pct, now))
            }
            Exchange.OKX -> {
                if (text == "pong") return
                val o = JSONObject(text)
                val arr = o.optJSONArray("data") ?: return
                for (i in 0 until arr.length()) {
                    val d = arr.optJSONObject(i) ?: continue
                    val key = map[d.optString("instId")] ?: continue
                    val c = d.optString("last").toDoubleOrNull() ?: continue
                    val open = d.optString("open24h").toDoubleOrNull() ?: 0.0
                    offer(key, Ticker(c, if (open > 0) (c - open) / open * 100.0 else 0.0, now))
                }
            }
        }
    }

    private fun offer(key: String, t: Ticker) {
        pending[key] = t
        if (flushScheduled.compareAndSet(false, true)) {
            main.postDelayed(flushRunnable, 250L)
        }
    }

    private val flushRunnable = Runnable {
        flushScheduled.set(false)
        if (pending.isEmpty()) return@Runnable
        val now = System.currentTimeMillis()
        for (key in pending.keys.toList()) {
            val t = pending.remove(key) ?: continue
            val old = prices[key]
            if (old != null && t.time < old.time) continue
            val fixed = if (t.changePct.isNaN()) t.copy(changePct = old?.changePct ?: 0.0) else t
            prices[key] = fixed
            if (t.time > 1L) lastUpdate = now
        }
        notifyListeners()
    }

    // ---------- REST ----------

    private fun fetchCharts(ex: Exchange, pairs: List<CoinPair>, gen: Int) {
        for (p in pairs) {
            val sym = p.symbolFor(ex)
            val url = when (ex) {
                Exchange.BINANCE -> "https://api.binance.com/api/v3/klines?symbol=$sym&interval=1h&limit=24"
                Exchange.BYBIT -> "https://api.bybit.com/v5/market/kline?category=spot&symbol=$sym&interval=60&limit=24"
                Exchange.OKX -> "https://www.okx.com/api/v5/market/candles?instId=$sym&bar=1H&limit=24"
            }
            http.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {}

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use { r ->
                            if (!r.isSuccessful) return
                            val body = r.body?.string() ?: return
                            val closes = ArrayList<Double>()
                            when (ex) {
                                Exchange.BINANCE -> {
                                    val arr = JSONArray(body)
                                    for (i in 0 until arr.length()) {
                                        arr.optJSONArray(i)?.optString(4)?.toDoubleOrNull()?.let { closes.add(it) }
                                    }
                                }
                                Exchange.BYBIT -> {
                                    val arr = JSONObject(body).optJSONObject("result")?.optJSONArray("list") ?: return
                                    for (i in arr.length() - 1 downTo 0) {
                                        arr.optJSONArray(i)?.optString(4)?.toDoubleOrNull()?.let { closes.add(it) }
                                    }
                                }
                                Exchange.OKX -> {
                                    val arr = JSONObject(body).optJSONArray("data") ?: return
                                    for (i in arr.length() - 1 downTo 0) {
                                        arr.optJSONArray(i)?.optString(4)?.toDoubleOrNull()?.let { closes.add(it) }
                                    }
                                }
                            }
                            if (closes.size < 2) return
                            main.post {
                                if (gen != generation) return@post
                                charts[p.key] = closes
                                notifyListeners()
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            })
        }
    }

    private fun fetchBinanceSnapshot(pairs: List<CoinPair>, gen: Int) {
        for (p in pairs) {
            val url = "https://api.binance.com/api/v3/ticker/24hr?symbol=" + p.symbolFor(Exchange.BINANCE)
            http.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {}

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use { r ->
                            if (!r.isSuccessful || gen != generation) return
                            val o = JSONObject(r.body?.string() ?: return)
                            val last = o.optString("lastPrice").toDoubleOrNull() ?: return
                            val pct = o.optString("priceChangePercent").toDoubleOrNull() ?: 0.0
                            offer(p.key, Ticker(last, pct, 1L))
                        }
                    } catch (_: Exception) {
                    }
                }
            })
        }
    }

    /**
     * Проверка, торгуется ли пара на бирже. Блокирующий вызов — только из фонового потока.
     * true — есть, false — нет, null — не удалось проверить (нет сети / регион заблокирован).
     */
    fun checkPair(ex: Exchange, p: CoinPair): Boolean? {
        val sym = p.symbolFor(ex)
        val url = when (ex) {
            Exchange.BINANCE -> "https://api.binance.com/api/v3/ticker/price?symbol=$sym"
            Exchange.BYBIT -> "https://api.bybit.com/v5/market/tickers?category=spot&symbol=$sym"
            Exchange.OKX -> "https://www.okx.com/api/v5/market/ticker?instId=$sym"
        }
        return try {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                val body = r.body?.string() ?: ""
                when (ex) {
                    Exchange.BINANCE -> when {
                        r.isSuccessful -> true
                        r.code == 400 -> false
                        else -> null
                    }
                    Exchange.BYBIT -> {
                        val o = JSONObject(body)
                        val list = o.optJSONObject("result")?.optJSONArray("list")
                        o.optInt("retCode", -1) == 0 && list != null && list.length() > 0
                    }
                    Exchange.OKX -> {
                        val o = JSONObject(body)
                        val data = o.optJSONArray("data")
                        o.optString("code") == "0" && data != null && data.length() > 0
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
