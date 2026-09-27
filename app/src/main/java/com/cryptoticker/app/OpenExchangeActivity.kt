package com.cryptoticker.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

/**
 * Невидимый «переходник»: открывает страницу монеты на бирже, выбранной в настройках.
 * Запускается по нажатию на монету в уведомлении (на экране блокировки — после разблокировки).
 */
class OpenExchangeActivity : Activity() {

    companion object {
        const val EXTRA_PAIR = "pair"

        fun tradeUrl(ex: Exchange, p: CoinPair): String = when (ex) {
            Exchange.BINANCE -> "https://www.binance.com/en/trade/${p.base}_${p.quote}?type=spot"
            Exchange.BYBIT -> "https://www.bybit.com/en/trade/spot/${p.base}/${p.quote}"
            Exchange.OKX -> "https://www.okx.com/trade-spot/${p.base.lowercase()}-${p.quote.lowercase()}"
        }

        fun intentFor(ctx: android.content.Context, p: CoinPair): Intent =
            Intent(ctx, OpenExchangeActivity::class.java)
                .setData(Uri.parse("cryptolive://open/" + p.key)) // уникальный адрес для каждой монеты
                .putExtra(EXTRA_PAIR, p.key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = intent?.getStringExtra(EXTRA_PAIR)?.let { CoinPair.fromKey(it) }
        if (p != null) {
            val ex = Prefs.exchange(this)
            try {
                // Если установлено приложение биржи, Android откроет его, иначе — браузер
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(tradeUrl(ex, p))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                Toast.makeText(this, "Не удалось открыть ${ex.title}", Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }
}
