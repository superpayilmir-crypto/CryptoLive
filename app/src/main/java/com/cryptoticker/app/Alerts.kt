package com.cryptoticker.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ценовое уведомление: сработать, когда цена пары [key] поднимется до [target] (above = true)
 * или опустится до него (above = false). Срабатывает один раз.
 */
data class PriceAlert(
    val id: Long,
    val key: String,
    val target: Double,
    val above: Boolean,
    val active: Boolean = true,
    val firedAt: Long = 0L,
    val firedPrice: Double = 0.0
) {
    val pair: CoinPair? get() = CoinPair.fromKey(key)
    fun describe(): String = (if (above) "▲ поднимется до " else "▼ опустится до ") + Fmt.price(target)
}

object Alerts {
    private const val PREF = "alerts"
    const val CHANNEL = "price_alerts"

    @Volatile
    private var cache: List<PriceAlert>? = null

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("crypto_live", Context.MODE_PRIVATE)

    fun all(c: Context): List<PriceAlert> {
        cache?.let { return it }
        val list = ArrayList<PriceAlert>()
        try {
            val arr = JSONArray(sp(c).getString(PREF, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    PriceAlert(
                        id = o.getLong("id"),
                        key = o.getString("key"),
                        target = o.getDouble("target"),
                        above = o.getBoolean("above"),
                        active = o.optBoolean("active", true),
                        firedAt = o.optLong("firedAt", 0L),
                        firedPrice = o.optDouble("firedPrice", 0.0)
                    )
                )
            }
        } catch (_: Exception) {
        }
        cache = list
        return list
    }

    private fun save(c: Context, list: List<PriceAlert>) {
        val arr = JSONArray()
        for (a in list) {
            arr.put(
                JSONObject()
                    .put("id", a.id).put("key", a.key).put("target", a.target)
                    .put("above", a.above).put("active", a.active)
                    .put("firedAt", a.firedAt).put("firedPrice", a.firedPrice)
            )
        }
        sp(c).edit().putString(PREF, arr.toString()).apply()
        cache = list
    }

    fun active(c: Context): List<PriceAlert> = all(c).filter { it.active }
    fun hasActive(c: Context): Boolean = all(c).any { it.active }
    fun forPair(c: Context, key: String): List<PriceAlert> = all(c).filter { it.key == key }

    /** Направление выбирается по текущей цене: цель выше — ждём роста, ниже — падения. */
    fun add(c: Context, key: String, target: Double, current: Double?): PriceAlert {
        val above = current == null || target >= current
        val a = PriceAlert(System.currentTimeMillis(), key, target, above)
        save(c, all(c) + a)
        return a
    }

    fun remove(c: Context, id: Long) = save(c, all(c).filter { it.id != id })
    fun removeForPair(c: Context, key: String) = save(c, all(c).filter { it.key != key })
    fun clearFired(c: Context) = save(c, all(c).filter { it.active })

    /** Вызывается из PriceHub при каждом обновлении цен (главный поток). */
    fun onPrices(c: Context, updates: Map<String, Double>) {
        val list = all(c)
        if (list.none { it.active }) return
        var changed = false
        val now = System.currentTimeMillis()
        val out = list.map { a ->
            val price = updates[a.key]
            if (!a.active || price == null) return@map a
            val hit = if (a.above) price >= a.target else price <= a.target
            if (!hit) return@map a
            changed = true
            val fired = a.copy(active = false, firedAt = now, firedPrice = price)
            notifyFired(c, fired)
            fired
        }
        if (changed) save(c, out)
    }

    fun ensureChannel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val ch = NotificationChannel(CHANNEL, "Цена достигла цели", NotificationManager.IMPORTANCE_HIGH)
        ch.description = "Уведомления, когда монета достигает заданной цены"
        ch.enableVibration(true)
        ch.vibrationPattern = longArrayOf(0, 250, 150, 250, 150, 400)
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        ch.setSound(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        nm.createNotificationChannel(ch)
    }

    private fun notifyFired(c: Context, a: PriceAlert) {
        val p = a.pair ?: return
        try {
            ensureChannel(c)
            val nm = c.getSystemService(NotificationManager::class.java) ?: return
            val open = PendingIntent.getActivity(
                c, (a.id and 0x7FFFFFFF).toInt(), OpenExchangeActivity.intentFor(c, p),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val arrow = if (a.above) "▲" else "▼"
            val title = "$arrow ${p.base} достиг ${Fmt.price(a.target)}"
            val text = "Цена сейчас ${Fmt.price(a.firedPrice)} ${p.quote}. Нажмите — откроется ${Prefs.exchange(c).title}"
            val n = Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setColor(if (a.above) WallRenderer.UP else WallRenderer.DOWN)
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            nm.notify((a.id and 0x7FFFFFFF).toInt(), n)
        } catch (_: Exception) {
        }
    }
}
