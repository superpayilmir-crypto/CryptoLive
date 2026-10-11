package com.cryptoticker.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity(), PriceHub.Listener {

    private val bgExec = Executors.newSingleThreadExecutor()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private lateinit var listBox: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var input: EditText
    private lateinit var addBtn: Button
    private lateinit var notifySwitch: Switch
    private val rowViews = HashMap<String, Array<TextView>>()
    private lateinit var alertsBox: LinearLayout
    private lateinit var soundUpView: TextView
    private lateinit var soundDownView: TextView
    private var previewPlayer: android.media.MediaPlayer? = null

    private val cText = 0xFFFFFFFF.toInt()
    private val cMuted = 0xFF8A93A6.toInt()
    private val cCard = 0xFF151B2B.toInt()
    private val cAccent = 0xFF45E0A0.toInt()
    private val cChip = 0xFF1E2538.toInt()

    private val popular = listOf("BTC", "ETH", "SOL", "TON", "XRP", "BNB", "DOGE", "PEPE", "TRX", "SUI", "ADA", "LINK", "NOT", "WIF")

    // ---------- жизненный цикл ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        val scroll = ScrollView(this).apply {
            fitsSystemWindows = true
            isFillViewport = true
            addView(root)
        }

        // Заголовок
        root.addView(TextView(this).apply {
            text = "Crypto Live"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(cText)
        })
        statusView = TextView(this).apply {
            textSize = 13f
            setTextColor(cMuted)
            setPadding(0, dp(2), 0, dp(12))
        }
        root.addView(statusView)

        // Биржа
        root.addView(sectionTitle("Биржа (источник цен)"))
        root.addView(segment(Exchange.values().map { it.title }, Prefs.exchange(this).ordinal) { i ->
            Prefs.setExchange(this, Exchange.values()[i])
            PriceHub.reload(this)
            rebuildList()
        })

        // Добавление монеты
        root.addView(sectionTitle("Мои монеты"))
        val addRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        input = EditText(this).apply {
            hint = "Тикер: SOL, PEPE, ETH/BTC…"
            setHintTextColor(cMuted)
            setTextColor(cText)
            textSize = 16f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = rounded(cCard, dp(12))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    addPair(text.toString()); true
                } else false
            }
        }
        addRow.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addBtn = Button(this).apply {
            text = "Добавить"
            isAllCaps = false
            setTextColor(0xFF06140E.toInt())
            background = rounded(cAccent, dp(12))
            setOnClickListener { addPair(input.text.toString()) }
        }
        addRow.addView(addBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply {
            leftMargin = dp(8)
        })
        root.addView(addRow)

        // Быстрое добавление
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (sym in popular) {
            chips.addView(chip("+ $sym", false) { addPair(sym) }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(6) })
        }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
            setPadding(0, dp(10), 0, dp(10))
        })

        listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cCard, dp(16))
            setPadding(dp(14), dp(4), dp(6), dp(4))
        }
        root.addView(listBox)
        root.addView(hint("Нажмите на монету — откроется её страница на выбранной бирже. ↑ — поднять выше, ✕ — удалить."))

        // Ценовые уведомления
        root.addView(sectionTitle("Ценовые уведомления"))
        root.addView(hint("Нажмите 🔔 у монеты и задайте цену — когда монета до неё дойдёт, придёт уведомление со звуком. Работает и при выключенном экране."))
        alertsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cCard, dp(16))
            setPadding(dp(14), dp(4), dp(6), dp(4))
        }
        root.addView(alertsBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })
        soundUpView = settingRow("Звук при росте", Alerts.soundUp(this).title) { pickSound(true) }
        root.addView(soundUpView.parent as View)
        soundDownView = settingRow("Звук при падении", Alerts.soundDown(this).title) { pickSound(false) }
        root.addView(soundDownView.parent as View)
        root.addView(Button(this).apply {
            text = "🔊 Проверить уведомление"
            isAllCaps = false
            textSize = 13f
            setTextColor(cText)
            background = rounded(cChip, dp(12))
            setOnClickListener {
                ensureNotifyPermission()
                Alerts.test(this@MainActivity, true)
                toast("Пробное уведомление отправлено")
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(10) })
        root.addView(Button(this).apply {
            text = "Разрешить работу в фоне (без ограничений батареи)"
            isAllCaps = false
            textSize = 13f
            setTextColor(cText)
            background = rounded(cChip, dp(12))
            setOnClickListener { askIgnoreBattery() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(10) })
        root.addView(hint("Чтобы уведомления приходили надёжно, разрешите приложению работать без ограничений. На Xiaomi также включите «Автозапуск»."))

        // Экран блокировки
        root.addView(sectionTitle("Экран блокировки / обои"))
        root.addView(WallpaperPreview(this), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })

        root.addView(Button(this).apply {
            text = "Установить живые обои"
            isAllCaps = false
            textSize = 16f
            setTextColor(0xFF06140E.toInt())
            background = rounded(cAccent, dp(14))
            setOnClickListener { openWallpaperPicker() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        root.addView(hint("В окне установки выберите «Главный экран и экран блокировки»."))

        root.addView(label("Оформление"))
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(segment(WallRenderer.themes.map { it.name }, Prefs.theme(this@MainActivity), false) { i ->
                Prefs.setTheme(this@MainActivity, i); PriceHub.notifyListeners()
            })
        })
        root.addView(label("Положение списка"))
        root.addView(segment(listOf("Под часами", "По центру", "Ниже"), Prefs.position(this)) { i ->
            Prefs.setPosition(this, i); PriceHub.notifyListeners()
        })
        root.addView(toggle("Анимация фона (видео и фото)", Prefs.animate(this)) { v ->
            Prefs.setAnimate(this, v); PriceHub.notifyListeners()
        })
        root.addView(toggle("Цены только на экране блокировки", Prefs.lockOnly(this)) { v ->
            Prefs.setLockOnly(this, v); PriceHub.notifyListeners()
        })
        root.addView(hint("После разблокировки цены плавно исчезают — на рабочем столе остаётся только живой фон."))
        root.addView(toggle("График за 24 часа", Prefs.showChart(this)) { v ->
            Prefs.setShowChart(this, v); PriceHub.notifyListeners()
        })
        root.addView(label("Размер текста"))
        root.addView(SeekBar(this).apply {
            max = 80
            progress = ((Prefs.textScale(this@MainActivity) - 0.7f) * 100f).toInt().coerceIn(0, 80)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    Prefs.setTextScale(this@MainActivity, 0.7f + p / 100f)
                    PriceHub.notifyListeners()
                }

                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        })

        // Уведомление
        root.addView(sectionTitle("Уведомление с ценами"))
        notifySwitch = Switch(this).apply {
            text = "Показывать цены в шторке и на экране блокировки"
            setTextColor(cText)
            textSize = 15f
            isChecked = Prefs.notifyEnabled(this@MainActivity)
            setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, checked -> onNotifyToggled(checked) }
        }
        root.addView(notifySwitch)
        root.addView(hint("На заблокированном экране нажмите на монету в уведомлении — после разблокировки сразу откроется её страница на выбранной бирже. Работает на всех телефонах, включая Xiaomi/Redmi. Когда экран выключен, соединение на паузе — батарея не тратится."))

        setContentView(scroll)
        rebuildList()

        if (Prefs.notifyEnabled(this) || Alerts.hasActive(this)) {
            try { PriceNotificationService.start(this) } catch (_: Exception) {}
        }
        rebuildAlerts()
    }

    override fun onStart() {
        super.onStart()
        PriceHub.subscribe(this, this)
    }

    override fun onStop() {
        PriceHub.unsubscribe(this)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        notifySwitch.isChecked = Prefs.notifyEnabled(this)
    }

    override fun onDestroy() {
        bgExec.shutdown()
        super.onDestroy()
    }

    // ---------- цены ----------

    private var lastAlertsSig = ""

    override fun onPricesUpdated() {
        val sig = Alerts.all(this).joinToString { "${it.id}:${it.active}" }
        if (sig != lastAlertsSig && ::alertsBox.isInitialized) {
            lastAlertsSig = sig
            rebuildAlerts()
            rebuildList()
            return
        }
        val ex = Prefs.exchange(this).title
        statusView.text = when (PriceHub.state) {
            PriceHub.State.ONLINE -> if (PriceHub.lastUpdate > 0)
                "● Онлайн · $ex · обновлено ${timeFmt.format(Date(PriceHub.lastUpdate))}"
            else "● Онлайн · $ex"
            PriceHub.State.CONNECTING -> "○ Подключение к $ex…"
            PriceHub.State.RECONNECTING -> "○ Нет связи с $ex, переподключаюсь…"
            PriceHub.State.IDLE -> "○ Нет монет в списке"
        }
        statusView.setTextColor(if (PriceHub.state == PriceHub.State.ONLINE) cAccent else cMuted)

        for ((key, views) in rowViews) {
            val t = PriceHub.price(key)
            if (t == null) {
                views[0].text = "—"
                views[1].text = "ожидание"
                views[1].setTextColor(cMuted)
            } else {
                views[0].text = Fmt.price(t.price)
                views[1].text = Fmt.change(t.changePct)
                views[1].setTextColor(if (t.changePct >= 0) WallRenderer.UP else WallRenderer.DOWN)
            }
        }
    }

    private fun rebuildList() {
        listBox.removeAllViews()
        rowViews.clear()
        val pairs = Prefs.pairs(this)
        if (pairs.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = "Список пуст — добавьте монету выше"
                setTextColor(cMuted)
                setPadding(0, dp(16), 0, dp(16))
            })
        }
        for ((index, p) in pairs.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
            val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            left.addView(TextView(this).apply {
                text = p.base
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(cText)
            })
            left.addView(TextView(this).apply {
                text = "/" + p.quote
                textSize = 12f
                setTextColor(cMuted)
            })
            row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val right = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
            }
            val priceV = TextView(this).apply {
                textSize = 18f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(cText)
                fontFeatureSettings = "tnum"
                gravity = Gravity.END
            }
            val changeV = TextView(this).apply {
                textSize = 13f
                gravity = Gravity.END
            }
            right.addView(priceV)
            right.addView(changeV)
            row.addView(right)

            row.setOnClickListener { startActivity(OpenExchangeActivity.intentFor(this, p)) }
            val nAl = Alerts.forPair(this, p.key).count { it.active }
            row.addView(iconButton(if (nAl > 0) "🔔$nAl" else "🔔") { showAlertDialog(p) }.apply {
                if (nAl > 0) setTextColor(cAccent)
            })
            row.addView(iconButton("↑") { move(index) })
            row.addView(iconButton("✕") { confirmRemove(p) })

            if (index > 0) {
                listBox.addView(View(this).apply { setBackgroundColor(0x14FFFFFF) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            }
            listBox.addView(row)
            rowViews[p.key] = arrayOf(priceV, changeV)
        }
        onPricesUpdated()
    }

    // ---------- действия ----------

    private fun addPair(raw: String) {
        val p = CoinPair.parse(raw)
        if (p == null) {
            toast("Введите тикер, например SOL или ETH/BTC")
            return
        }
        if (Prefs.pairs(this).any { it.key == p.key }) {
            toast("${p.key} уже в списке")
            return
        }
        val ex = Prefs.exchange(this)
        addBtn.isEnabled = false
        toast("Проверяю ${p.key} на ${ex.title}…")
        bgExec.execute {
            val ok = PriceHub.checkPair(ex, p)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                addBtn.isEnabled = true
                if (ok == false) {
                    toast("Пара ${p.key} не найдена на ${ex.title}")
                } else {
                    val list = Prefs.pairs(this).toMutableList()
                    if (list.none { it.key == p.key }) list.add(p)
                    Prefs.setPairs(this, list)
                    input.setText("")
                    rebuildList()
                    PriceHub.reload(this)
                    toast(if (ok == null) "${p.key} добавлена (проверить не удалось — нет связи)" else "${p.key} добавлена")
                }
            }
        }
    }

    private fun move(index: Int) {
        if (index <= 0) return
        val list = Prefs.pairs(this).toMutableList()
        if (index >= list.size) return
        val item = list.removeAt(index)
        list.add(index - 1, item)
        Prefs.setPairs(this, list)
        rebuildList()
        PriceHub.reload(this)
    }

    private fun confirmRemove(p: CoinPair) {
        AlertDialog.Builder(this)
            .setMessage("Удалить ${p.key}?")
            .setPositiveButton("Удалить") { _, _ ->
                Prefs.setPairs(this, Prefs.pairs(this).filter { it.key != p.key })
                Alerts.removeForPair(this, p.key)
                rebuildList()
                rebuildAlerts()
                PriceHub.reload(this)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun openWallpaperPicker() {
        try {
            val i = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            i.putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(this, PriceWallpaperService::class.java)
            )
            startActivity(i)
        } catch (e: Exception) {
            try {
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
                toast("Выберите «${getString(R.string.wallpaper_name)}» в списке")
            } catch (e2: Exception) {
                toast("Телефон не поддерживает живые обои — включите уведомление ниже")
            }
        }
    }

    private fun onNotifyToggled(checked: Boolean) {
        if (checked) {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
                return
            }
            Prefs.setNotifyEnabled(this, true)
            try {
                PriceNotificationService.start(this)
            } catch (e: Exception) {
                toast("Не удалось запустить уведомление")
            }
        } else {
            Prefs.setNotifyEnabled(this, false)
            if (Alerts.hasActive(this)) {
                try { PriceNotificationService.start(this) } catch (_: Exception) {}
            } else {
                PriceNotificationService.stop(this)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 7) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Prefs.setNotifyEnabled(this, true)
            PriceNotificationService.start(this)
        } else {
            notifySwitch.isChecked = false
            toast("Без разрешения на уведомления цены в шторке не показать")
        }
    }

    // ---------- ценовые уведомления ----------

    private fun parsePrice(s: String): Double? =
        s.trim().replace(" ", "").replace("\u00A0", "").replace(",", ".").toDoubleOrNull()?.takeIf { it > 0 }

    private fun showAlertDialog(p: CoinPair) {
        val current = PriceHub.price(p.key)?.price
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        box.addView(TextView(this).apply {
            text = if (current != null) "Сейчас: ${Fmt.price(current)} ${p.quote}" else "Цена загружается…"
            textSize = 15f
        })
        val input = EditText(this).apply {
            hint = "Цена, например " + (current?.let { Fmt.price(it * 1.05) } ?: "100")
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            textSize = 18f
            isSingleLine = true
        }
        box.addView(input)
        val dirView = TextView(this).apply { textSize = 13f; setPadding(0, dp(2), 0, dp(8)) }
        box.addView(dirView)
        fun updateDir() {
            val v = parsePrice(input.text.toString())
            dirView.text = when {
                v == null -> "Введите цену — направление определится само"
                current == null || v >= current -> "▲ Уведомлю, когда цена поднимется до ${Fmt.price(v)}"
                else -> "▼ Уведомлю, когда цена опустится до ${Fmt.price(v)}"
            }
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { updateDir() }
        })
        updateDir()
        if (current != null) {
            val quick = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (pct in listOf(-10, -5, 5, 10)) {
                quick.addView(Button(this).apply {
                    text = (if (pct > 0) "+" else "") + pct + "%"
                    isAllCaps = false
                    textSize = 13f
                    setOnClickListener {
                        val v = current * (1 + pct / 100.0)
                        input.setText(Fmt.price(v).replace(" ", ""))
                        input.setSelection(input.text.length)
                    }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            box.addView(quick)
        }
        val existing = Alerts.forPair(this, p.key).filter { it.active }
        if (existing.isNotEmpty()) {
            box.addView(TextView(this).apply {
                text = "Уже заданы:"
                textSize = 13f
                setPadding(0, dp(10), 0, dp(2))
            })
            for (a in existing) box.addView(TextView(this).apply { text = "• " + a.describe(); textSize = 14f })
        }
        AlertDialog.Builder(this)
            .setTitle("🔔 Уведомление для ${p.key}")
            .setView(box)
            .setPositiveButton("Добавить") { _, _ ->
                val v = parsePrice(input.text.toString())
                if (v == null) {
                    toast("Введите цену числом")
                } else {
                    val a = Alerts.add(this, p.key, v, PriceHub.price(p.key)?.price)
                    ensureNotifyPermission()
                    try { PriceNotificationService.start(this) } catch (_: Exception) {}
                    rebuildAlerts()
                    rebuildList()
                    toast("Готово: ${p.base} " + a.describe())
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun rebuildAlerts() {
        if (!::alertsBox.isInitialized) return
        alertsBox.removeAllViews()
        val all = Alerts.all(this).sortedWith(compareBy({ !it.active }, { -it.id }))
        if (all.isEmpty()) {
            alertsBox.addView(TextView(this).apply {
                text = "Пока нет — нажмите 🔔 у монеты в списке выше"
                setTextColor(cMuted)
                setPadding(0, dp(14), 0, dp(14))
            })
            return
        }
        for ((i, a) in all.withIndex()) {
            if (i > 0) alertsBox.addView(View(this).apply { setBackgroundColor(0x14FFFFFF) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
            val p = a.pair
            row.addView(TextView(this).apply {
                text = (p?.base ?: a.key) + "  " + a.describe()
                textSize = 15f
                setTextColor(if (a.active) cText else cMuted)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (!a.active) {
                row.addView(TextView(this).apply {
                    text = "✓ сработало"
                    textSize = 12f
                    setTextColor(cAccent)
                })
            }
            row.addView(iconButton("✕") {
                Alerts.remove(this, a.id)
                rebuildAlerts()
                rebuildList()
            })
            alertsBox.addView(row)
        }
        lastAlertsSig = Alerts.all(this).joinToString { "${it.id}:${it.active}" }
    }

    /** Строка настройки «Название — значение ▸»; возвращает TextView значения. */
    private fun settingRow(title: String, value: String, onClick: () -> Unit): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(cCard, dp(12))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        row.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(cText)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val v = TextView(this).apply {
            text = "$value  ▸"
            textSize = 14f
            setTextColor(cAccent)
        }
        row.addView(v)
        return v
    }

    private fun playPreview(snd: AlertSound) {
        try {
            previewPlayer?.release()
            previewPlayer = null
            when {
                snd.res > 0 -> {
                    previewPlayer = android.media.MediaPlayer.create(this, snd.res)?.apply {
                        setOnCompletionListener { it.release(); if (previewPlayer === it) previewPlayer = null }
                        start()
                    }
                }
                snd.res == 0 -> android.media.RingtoneManager.getRingtone(
                    this, android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                )?.play()
                else -> {
                    val vib = getSystemService(android.os.Vibrator::class.java)
                    vib?.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 250, 150, 250, 150, 400), -1))
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun pickSound(up: Boolean) {
        val current = if (up) Alerts.soundUp(this) else Alerts.soundDown(this)
        var chosen = Alerts.sounds.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(if (up) "Звук при росте цены" else "Звук при падении цены")
            .setSingleChoiceItems(Alerts.sounds.map { it.title }.toTypedArray(), chosen) { _, which ->
                chosen = which
                playPreview(Alerts.sounds[which])
            }
            .setPositiveButton("Выбрать") { _, _ ->
                val snd = Alerts.sounds[chosen]
                if (up) {
                    Alerts.setSoundUp(this, snd.id); soundUpView.text = "${snd.title}  ▸"
                } else {
                    Alerts.setSoundDown(this, snd.id); soundDownView.text = "${snd.title}  ▸"
                }
                previewPlayer?.release(); previewPlayer = null
            }
            .setNegativeButton("Отмена") { _, _ -> previewPlayer?.release(); previewPlayer = null }
            .show()
    }

    private fun ensureNotifyPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 8)
        }
    }

    private fun askIgnoreBattery() {
        try {
            val pm = getSystemService(android.os.PowerManager::class.java)
            if (pm != null && pm.isIgnoringBatteryOptimizations(packageName)) {
                toast("Уже разрешено — приложение работает без ограничений")
                return
            }
            startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(android.net.Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                toast("Откройте настройки батареи телефона вручную")
            }
        }
    }

    // ---------- UI-помощники ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun sectionTitle(s: String) = TextView(this).apply {
        text = s
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(cText)
        setPadding(0, dp(20), 0, dp(8))
    }

    private fun label(s: String) = TextView(this).apply {
        text = s
        textSize = 13f
        setTextColor(cMuted)
        setPadding(0, dp(14), 0, dp(6))
    }

    private fun hint(s: String) = TextView(this).apply {
        text = s
        textSize = 12f
        setTextColor(cMuted)
        setPadding(dp(2), dp(6), dp(2), 0)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun chip(s: String, selected: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = s
        textSize = 13f
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(12), dp(8), dp(12), dp(8))
        styleChip(this, selected)
        setOnClickListener { onClick() }
    }

    private fun styleChip(v: TextView, selected: Boolean) {
        v.background = rounded(if (selected) cAccent else cChip, dp(10))
        v.setTextColor(if (selected) 0xFF06140E.toInt() else cText)
    }

    private fun toggle(title: String, value: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = title
        setTextColor(cText)
        textSize = 15f
        isChecked = value
        setPadding(0, dp(10), 0, dp(4))
        setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }

    private fun segment(options: List<String>, selected: Int, stretch: Boolean = true, onSelect: (Int) -> Unit): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val views = ArrayList<TextView>()
        for ((i, name) in options.withIndex()) {
            val v = chip(name, i == selected) {}
            if (stretch) v.setPadding(dp(4), dp(10), dp(4), dp(10)) else v.setPadding(dp(16), dp(10), dp(16), dp(10))
            v.setOnClickListener {
                for ((j, other) in views.withIndex()) styleChip(other, j == i)
                onSelect(i)
            }
            views.add(v)
            val lp = if (stretch) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            else LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (i > 0) lp.leftMargin = dp(6)
            box.addView(v, lp)
        }
        return box
    }

    private fun iconButton(s: String, onClick: () -> Unit) = TextView(this).apply {
        text = s
        textSize = 16f
        setTextColor(cMuted)
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setOnClickListener { onClick() }
    }
}
