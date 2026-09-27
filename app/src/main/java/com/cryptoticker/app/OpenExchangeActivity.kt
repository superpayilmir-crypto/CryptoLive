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
        if (p != null) open(Prefs.exchange(this), p)
        finish()
    }

    /** Пакеты официальных приложений бирж. */
    private fun packagesFor(ex: Exchange): List<String> = when (ex) {
        Exchange.BYBIT -> listOf("com.bybit.app")
        Exchange.BINANCE -> listOf("com.binance.dev")
        Exchange.OKX -> listOf("com.okinc.okex.gp", "com.okinc.okex")
    }

    /** Ссылки на монету, которые может понять приложение биржи (по порядку). */
    private fun appLinks(ex: Exchange, p: CoinPair): List<String> {
        val web = tradeUrl(ex, p)
        return when (ex) {
            Exchange.BYBIT -> listOf(
                web,
                "https://www.bybit.com/trade/spot/${p.base}/${p.quote}",
                "bybitapp://open/route?targetUrl=" + Uri.encode(web)
            )
            Exchange.BINANCE -> listOf(
                web,
                "bnc://app.binance.com/trade/trade?at=spot&symbol=${p.base.lowercase()}${p.quote.lowercase()}"
            )
            Exchange.OKX -> listOf(
                web,
                "okx://wallet/dapp/url?dappUrl=" + Uri.encode(web)
            )
        }
    }

    private fun tryStart(i: Intent): Boolean = try {
        if (i.resolveActivity(packageManager) != null) {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        } else false
    } catch (e: Exception) {
        false
    }

    private fun open(ex: Exchange, p: CoinPair) {
        val installed = packagesFor(ex).firstOrNull { pkg ->
            try { packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }
        }
        if (installed != null) {
            // 1) Ссылка на монету прямо в приложение биржи
            for (link in appLinks(ex, p)) {
                if (tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(installed))) return
            }
            // 2) Приложение не понимает ссылку — просто открываем его, а тикер копируем для поиска
            val launch = packageManager.getLaunchIntentForPackage(installed)
            if (launch != null && tryStart(launch)) {
                try {
                    val cm = getSystemService(android.content.ClipboardManager::class.java)
                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("ticker", p.base))
                } catch (_: Exception) {}
                Toast.makeText(this, "${p.base} скопирован — вставьте в поиск ${ex.title}", Toast.LENGTH_LONG).show()
                return
            }
        }
        // 3) Приложения нет — открываем сайт
        if (!tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(tradeUrl(ex, p))))) {
            Toast.makeText(this, "Не удалось открыть ${ex.title}", Toast.LENGTH_SHORT).show()
        }
    }
}
