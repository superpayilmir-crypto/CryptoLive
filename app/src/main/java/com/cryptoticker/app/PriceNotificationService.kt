package com.cryptoticker.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock

/** Постоянное уведомление с ценами — видно на экране блокировки на любом телефоне. */
class PriceNotificationService : Service(), PriceHub.Listener {

    companion object {
        const val CHANNEL = "prices"
        const val NOTIF_ID = 42
        const val ACTION_STOP = "com.cryptoticker.app.STOP"

        fun start(ctx: Context) {
            val i = Intent(ctx, PriceNotificationService::class.java)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, PriceNotificationService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastPost = 0L
    private var subscribed = false
    private var receiverRegistered = false

    private val postRunnable = Runnable { postNow() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> setSubscribed(false)
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> setSubscribed(true)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL, "Цены в реальном времени", NotificationManager.IMPORTANCE_LOW)
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        ch.setShowBadge(false)
        nm?.createNotificationChannel(ch)

        val n = build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }

        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, f)
        }
        receiverRegistered = true
        setSubscribed(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.setNotifyEnabled(this, false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(postRunnable)
        setSubscribed(false)
        if (receiverRegistered) {
            try {
                unregisterReceiver(screenReceiver)
            } catch (_: Exception) {
            }
            receiverRegistered = false
        }
        super.onDestroy()
    }

    private fun setSubscribed(on: Boolean) {
        if (on == subscribed) return
        subscribed = on
        if (on) PriceHub.subscribe(this, this) else PriceHub.unsubscribe(this)
    }

    override fun onPricesUpdated() {
        // Не чаще раза в 1.5 секунды, чтобы система не глушила обновления
        val since = SystemClock.elapsedRealtime() - lastPost
        handler.removeCallbacks(postRunnable)
        if (since >= 1500L) postNow() else handler.postDelayed(postRunnable, 1500L - since)
    }

    private fun postNow() {
        lastPost = SystemClock.elapsedRealtime()
        try {
            getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, build())
        } catch (_: Exception) {
        }
    }

    private fun line(p: CoinPair): String {
        val t = PriceHub.price(p.key) ?: return "${p.base}  —"
        return "${p.base}  ${Fmt.price(t.price)}  ${Fmt.change(t.changePct)}"
    }

    private fun build(): Notification {
        val pairs = Prefs.pairs(this)
        val title: String
        val text: String
        val big: String
        if (pairs.isEmpty()) {
            title = "Crypto Live"
            text = "Добавьте монеты в приложении"
            big = text
        } else {
            title = line(pairs[0])
            val rest = pairs.drop(1)
            text = rest.take(3).joinToString(" · ") { p ->
                val t = PriceHub.price(p.key)
                if (t == null) "${p.base} —" else "${p.base} ${Fmt.price(t.price)}"
            }
            big = rest.joinToString("\n") { line(it) }
        }

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, PriceNotificationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val first = pairs.firstOrNull()?.let { PriceHub.price(it.key) }
        val color = if (first == null || first.changePct >= 0) WallRenderer.UP else WallRenderer.DOWN

        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(big))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setColor(color)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Выключить", stopPi).build())
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return b.build()
    }
}
