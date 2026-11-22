package cz.dpf.monitor

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.companion.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

/**
 * Single-activity UI: automated initial wizard, quiet dashboard, charts and
 * diagnostics under Settings. Activity does not own the BLE connection.
 */
@Suppress("DEPRECATION")
@SuppressLint("MissingPermission")
class MainActivity : Activity() {
    private val baseColor = Color.rgb(10, 17, 27)
    private val panel = Color.rgb(19, 33, 48)
    private val elevated = Color.rgb(26, 43, 61)
    private val white = Color.rgb(239, 247, 250)
    private val muted = Color.rgb(155, 178, 191)
    private val green = Color.rgb(104, 231, 191)
    private val orange = Color.rgb(255, 188, 91)
    private val blue = Color.rgb(106, 173, 255)
    private val red = Color.rgb(255, 111, 114)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: LocalStore
    private lateinit var database: TelemetryDatabase
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var subtitle: TextView
    private val values = mutableMapOf<String, TextView>()
    private val graphs = mutableListOf<Pair<TelemetryChart, List<Pair<String, Int>>>>()
    private var dashboard = true
    private var days = 1
    private var lastGraphTime = 0L
    private var firstWizardTriggered = false
    private var chooserActive = false
    private var chooserOpened = false
    private var notificationRequested = false
    private val PAIR = 91
    private val ENABLE_BT = 92
    private val EXPORT = 93
    private val PERMISSIONS = 94

    private val ticker = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = LocalStore(this)
        database = TelemetryDatabase.get(this)
        window.statusBarColor = baseColor
        window.navigationBarColor = baseColor
        render()
        database.purgeExpired()
        if (store.adapterAddress == null) handler.postDelayed({
            if (!isFinishing && !firstWizardTriggered) {
                firstWizardTriggered = true
                beginSetup()
            }
        }, 500)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        if (store.adapterAddress != null && store.autoEnabled && hasBluetoothPermissions()) {
            runCatching {
                observe()
                MonitorService.start(this)
            }.onFailure { store.event("START", "Automatický start selhal: " + it.message) }
        }
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun dp(x: Int) = (resources.displayMetrics.density * x).toInt()

    private fun shape(color: Int, radius: Int = 17) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), Color.rgb(35, 52, 67))
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun label(value: String, size: Float = 15f, color: Int = white, bold: Boolean = false) =
        TextView(this).apply {
            text = value; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

    private fun spacer(height: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun card(parent: LinearLayout, color: Int = panel): LinearLayout {
        val c = column()
        c.background = shape(color)
        c.setPadding(dp(17), dp(17), dp(17), dp(17))
        parent.addView(c, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        return c
    }

    private fun render() {
        values.clear()
        graphs.clear()
        root = column().apply { setBackgroundColor(baseColor) }
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(19), dp(20), dp(16), dp(12))
        }
        val name = column().apply {
            addView(label("DPF MONITOR", 26f, white, true))
        }
        subtitle = label("1.0.0  •  PŘIPRAVUJI", 11f, muted)
        name.addView(subtitle)
        top.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
        val menu = label("☰", 27f, white, true).apply {
            gravity = Gravity.CENTER
            contentDescription = "Nastavení, připojení a export"
            background = shape(elevated)
            setOnClickListener { settings(this) }
        }
        top.addView(menu, LinearLayout.LayoutParams(dp(50), dp(46)))
        root.addView(top)
        val scroll = ScrollView(this)
        content = column().apply { setPadding(dp(16), dp(5), dp(16), dp(4)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val tabs = LinearLayout(this).apply {
            setPadding(dp(15), dp(8), dp(15), dp(11))
            background = shape(panel)
        }
        fun tab(title: String, selected: Boolean, choose: () -> Unit) {
            val b = label(title, 15f, if (selected) baseColor else muted, true).apply {
                gravity = Gravity.CENTER
                background = shape(if (selected) green else panel, 12)
                setOnClickListener { choose() }
            }
            tabs.addView(b, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(5) })
        }
        tab("◉  Přehled", dashboard) { dashboard = true; render() }
        tab("▥  Grafy", !dashboard) { dashboard = false; lastGraphTime = 0; render() }
        root.addView(tabs)
        setContentView(root)
        if (dashboard) drawDashboard() else drawDetails()
        update()
    }

    private fun drawDashboard() {
        val hero = card(content, elevated)
        hero.addView(label("STAV DPF", 12f, muted, true))
        hero.addView(spacer(8))
        values["phase"] = label("Zjišťuji stav…", 28f, green, true).also { hero.addView(it) }
        hero.addView(spacer(5))
        values["health"] = label("Čekám na řídicí jednotku", 13f, muted).also { hero.addView(it) }

        val criterion = card(content)
        val first = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        first.addView(label("Kritérium regenerace", 14f, muted),
            LinearLayout.LayoutParams(0, -2, 1f))
        values["criterion"] = label("— %", 23f, white, true).also { first.addView(it) }
        criterion.addView(first)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            tag = "criterionBar"
            progressTintList = android.content.res.ColorStateList.valueOf(green)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(48, 67, 80))
        }
        criterion.addView(bar, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(12) })
        values["next"] = label("Příští regenerace: —", 13f, muted).also {
            it.setPadding(0, dp(12), 0, 0)
            criterion.addView(it)
        }

        fun two(a: String, titleA: String, b: String, titleB: String) {
            val row = LinearLayout(this)
            for ((key, title) in listOf(a to titleA, b to titleB)) {
                val cell = column().apply {
                    background = shape(panel)
                    setPadding(dp(15), dp(17), dp(10), dp(17))
                    addView(label(title, 12f, muted))
                    addView(spacer(10))
                }
                values[key] = label("—", 23f, white, true).also { cell.addView(it) }
                row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    rightMargin = dp(5)
                })
            }
            content.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(11)
            })
        }
        two("distance", "Od regenerace", "since", "Čas od regenerace")
        two("elapsed", "Délka aktivní fáze", "remaining", "Do konce (odhad)")
        two("soot", "Vypočtené saze", "temperature", "Teploty DPF A / B")
        val note = card(content)
        note.addView(label("ŽIVÁ DIAGNOSTIKA", 12f, muted, true))
        values["detail"] = label("Bez aktuálních hodnot", 14f, white).also {
            it.setPadding(0, dp(8), 0, 0); note.addView(it)
        }
        content.addView(label(
            "Odhady jsou experimentální. Dokončení regenerace potvrzuje až reset čítačů ECU.",
            12f, muted
        ).apply { setPadding(dp(3), dp(5), dp(3), dp(13)) })
        if (store.adapterAddress == null) {
            val onboarding = card(content, elevated)
            onboarding.addView(label("První nastavení", 20f, green, true))
            onboarding.addView(spacer(8))
            onboarding.addView(label(
                "Vyber jediný BLE adaptér. Aplikace ověří VIN z ECU a další jízdy bude sledovat automaticky.",
                14f, muted
            ))
            onboarding.addView(Button(this).apply {
                text = "Nastavit automaticky"
                setOnClickListener { beginSetup() }
            })
        } else if (!store.setupComplete) {
            val verify = card(content, elevated)
            verify.addView(label("Čekám na ověření VIN", 18f, orange, true))
            verify.addView(label("Adaptér je uložen. ECU musí vrátit úplné VIN, aby se aktivoval monitoring.", 13f, muted))
            verify.addView(Button(this).apply {
                text = "Opakovat připojení"
                setOnClickListener { MonitorService.start(this@MainActivity) }
            })
        }
    }

    private fun drawDetails() {
        val select = card(content)
        select.addView(label("Historie senzorů", 22f, white, true))
        select.addView(label("30denní retence, lokální měření bez cloudové služby.", 13f, muted))
        val interval = LinearLayout(this).apply { setPadding(0, dp(11), 0, 0) }
        for ((n, title) in listOf(1 to "24 h", 7 to "7 dnů", 30 to "30 dnů")) {
            interval.addView(label(title, 14f, if (n == days) baseColor else white, true).apply {
                gravity = Gravity.CENTER
                background = shape(if (n == days) green else elevated, 10)
                setOnClickListener { days = n; lastGraphTime = 0; render() }
            }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { rightMargin = dp(5) })
        }
        select.addView(interval)

        fun chart(title: String, sensors: List<Pair<String, Int>>, scale: ClosedFloatingPointRange<Double>? = null) {
            val c = card(content)
            c.addView(label(title, 18f, white, true))
            c.addView(spacer(11))
            val graph = TelemetryChart(this).apply { fixedRange = scale }
            c.addView(graph, LinearLayout.LayoutParams(-1, dp(208)))
            c.addView(spacer(5))
            c.addView(label(sensors.joinToString("    •    ") { readable(it.first) }, 12f, muted))
            graphs.add(graph to sensors)
        }
        chart("Teploty výfuku · °C", listOf("22449B" to green, "22449A" to orange))
        chart("Hmotnost sazí · g", listOf("22208B" to blue, "22208C" to orange))
        chart("Kritérium regenerace · %", listOf("018B" to green), 0.0..100.0)
        chart("Vzdálenost od regenerace · km", listOf("22208D" to blue))
        chart("Délka aktivní regenerace · s", listOf("22207D" to orange))
        val recent = card(content)
        recent.addView(label("Poslední události", 18f, white, true))
        values["events"] = label("—", 13f, muted).also {
            it.setPadding(0, dp(8), 0, dp(12)); recent.addView(it)
        }
        recent.addView(Button(this).apply {
            text = "Exportovat kompletní diagnostiku (ZIP)"
            setOnClickListener { requestExport() }
        })
    }

    private fun readable(cmd: String): String = when (cmd) {
        "22449A" -> "Teplota A"
        "22449B" -> "Teplota B"
        "22208B" -> "Vypočtené saze"
        "22208C" -> "Naměřené saze"
        "018B" -> "Spuštění (%)"
        "22208D" -> "Vzdálenost"
        else -> "Běh regenerace"
    }

    private fun update() {
        if (!::content.isInitialized) return
        val state: JSONObject = store.view()
        val sampleAt = state.optLong("lastSample")
        val age = if (sampleAt > 0) (System.currentTimeMillis() - sampleAt).coerceAtLeast(0) / 1000
            else Long.MAX_VALUE
        val stale = age > 18
        subtitle.text = if (store.setupComplete) "1.0.0  •  VOZIDLO OVĚŘENO"
            else "1.0.0  •  ČEKÁ NA NASTAVENÍ"
        if (dashboard) {
            val phase = state.optString("phase", "UNKNOWN")
            val pair = when {
                stale && store.adapterAddress == null -> "Čekám na adaptér" to muted
                stale -> "Čekám na ECU" to muted
                phase == "ACTIVE" -> "Regenerace probíhá" to orange
                phase == "APPROACHING" -> "Blíží se regenerace" to orange
                phase == "COOL_DOWN" -> "Ověřuji dokončení" to blue
                phase == "COMPLETED" -> "Dokončeno" to green
                phase == "INTERRUPTED" -> "Možné přerušení" to red
                phase == "UNRESOLVED" -> "Neověřený konec" to red
                phase == "IDLE" -> "Regenerace neprobíhá" to green
                else -> "Stav neznámý" to muted
            }
            values["phase"]?.apply { text = pair.first; setTextColor(pair.second) }
            values["health"]?.text = if (stale) "Žádná čerstvá data" else
                "ECU online · poslední měření před " + age + " s"
            values["criterion"]?.text = if (stale) "— %" else decimal(state, "trigger", 0, " %")
            root.findViewWithTag<ProgressBar>("criterionBar")?.progress =
                if (stale) 0 else state.optDouble("trigger", 0.0).coerceIn(0.0, 100.0).toInt()
            values["next"]?.text = "Odhad do dalšího spuštění: " +
                if (stale) "—" else duration(state, "next")
            values["distance"]?.text = if (stale) "—" else decimal(state, "distance", 1, " km")
            values["since"]?.text = if (stale) "—" else duration(state, "since")
            values["elapsed"]?.text = if (stale) "—" else duration(state, "elapsed")
            values["remaining"]?.text = if (stale) "—" else duration(state, "remaining")
            values["soot"]?.text = if (stale) "—" else decimal(state, "soot", 1, " g")
            values["temperature"]?.text = if (stale) "—" else
                decimal(state, "tempA", 0, "") + " / " + decimal(state, "tempB", 0, " °C")
            values["detail"]?.text = when {
                store.adapterAddress == null -> "Přiřaď BLE adaptér v prvním nastavení."
                !store.setupComplete -> "Připojuji adaptér a ověřuji identitu ECU."
                stale -> "Poslední data jsou zastaralá. Čekám na spojení."
                else -> "Monitoring na pozadí · diagnostika se ukládá 30 dnů."
            }
        } else {
            val rows = store.events()
            values["events"]?.text = if (rows.length() == 0) "Zatím bez událostí" else
                (0 until rows.length()).joinToString("\n\n") { i ->
                    val obj = rows.getJSONObject(i)
                    SimpleDateFormat("dd.MM. HH:mm", Locale.forLanguageTag("cs-CZ"))
                        .format(Date(obj.getLong("at"))) + " · " + obj.optString("message")
                }
            if (System.currentTimeMillis() - lastGraphTime > 10_000) {
                lastGraphTime = System.currentTimeMillis()
                val selectedDays = days
                val currentGraphs = graphs.toList()
                Thread {
                    val series = runCatching {
                        currentGraphs.map { entry ->
                            entry.second.map { item ->
                                GraphSeries(readable(item.first), item.second,
                                    database.points(item.first, selectedDays))
                            }
                        }
                    }.getOrNull()
                    if (series != null) runOnUiThread {
                        if (!dashboard && selectedDays == days && graphs.size == series.size)
                            graphs.forEachIndexed { index, item -> item.first.update(series[index]) }
                    }
                }.start()
            }
        }
    }

    private fun decimal(obj: JSONObject, key: String, places: Int, unit: String): String {
        if (!obj.has(key) || obj.isNull(key)) return "—"
        return String.format(Locale.forLanguageTag("cs-CZ"), "%." + places + "f",
            obj.optDouble(key)) + unit
    }

    private fun duration(obj: JSONObject, key: String): String {
        if (!obj.has(key) || obj.isNull(key)) return "—"
        val s = obj.optDouble(key)
        if (!s.isFinite() || s < 0) return "—"
        return when {
            s < 90 -> s.toInt().toString() + " s"
            s < 3600 -> (s / 60).toInt().toString() + " min"
            else -> (s / 3600).toInt().toString() + " h " +
                ((s % 3600) / 60).toInt().toString() + " min"
        }
    }

    private fun settings(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add("Export diagnostiky · 30 dnů")
        popup.menu.add(if (store.autoEnabled) "Automatické sledování: zapnuto"
            else "Automatické sledování: vypnuto")
        popup.menu.add("Znovu připojit")
        popup.menu.add("Změnit BLE adaptér")
        popup.menu.add("Oprávnění aplikace")
        popup.menu.add("O aplikaci")
        popup.setOnMenuItemClickListener { item ->
            when {
                item.title.toString().startsWith("Export") -> requestExport()
                item.title.toString().startsWith("Automatické") -> {
                    store.autoEnabled = !store.autoEnabled
                    if (store.autoEnabled) {
                        runCatching { observe(); MonitorService.start(this) }
                            .onFailure { toast("Start monitoringu: " + it.message) }
                    } else {
                        runCatching {
                            store.adapterAddress?.let {
                                getSystemService(CompanionDeviceManager::class.java)
                                    .stopObservingDevicePresence(it)
                            }
                        }
                        stopService(Intent(this, MonitorService::class.java))
                    }
                    toast(if (store.autoEnabled) "Automatické sledování zapnuto" else
                        "Automatické sledování vypnuto")
                }
                item.title.toString().startsWith("Znovu") -> runCatching { MonitorService.start(this) }
                    .onFailure { toast("Spojení: " + it.message) }
                item.title.toString().startsWith("Změnit") -> unpair()
                item.title.toString().startsWith("Oprávnění") -> startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:" + packageName)))
                else -> AlertDialog.Builder(this).setTitle("DPF Monitor v1.0.0")
                    .setMessage("Data zůstávají v telefonu, automaticky se mažou po 30 dnech. Diagnostika je experimentální.")
                    .setPositiveButton("OK", null).show()
            }
            true
        }
        popup.show()
    }

    private fun unpair() {
        AlertDialog.Builder(this).setTitle("Vyměnit adaptér")
            .setMessage("Zruší se vazba BLE a VIN, historie měření zůstane v telefonu.")
            .setNegativeButton("Zrušit", null)
            .setPositiveButton("Pokračovat") { _, _ ->
                stopService(Intent(this, MonitorService::class.java))
                store.adapterAddress?.let { addr ->
                    runCatching {
                        val cdm = getSystemService(CompanionDeviceManager::class.java)
                        cdm.stopObservingDevicePresence(addr)
                        cdm.disassociate(addr)
                    }
                }
                store.adapterAddress = null
                store.vin = null
                store.setupComplete = false
                render()
                beginSetup()
            }.show()
    }

    private fun requestExport() {
        val file = "dpf-monitor-1.0.0-" +
            SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date()) + ".zip"
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, file)
        }, EXPORT)
    }

    private fun exportData(uri: android.net.Uri) {
        toast("Připravuji archiv diagnostiky")
        Thread {
            val problem = runCatching {
                contentResolver.openOutputStream(uri)?.use { database.exportZip(it) }
                    ?: error("Nelze otevřít soubor")
            }.exceptionOrNull()
            runOnUiThread { toast(if (problem == null) "Export dokončen" else
                "Export selhal: " + problem.message) }
        }.start()
    }

    private fun hasBluetoothPermissions(): Boolean =
        checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun beginSetup() {
        if (!hasBluetoothPermissions()) {
            val required = mutableListOf(Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                required.add(Manifest.permission.POST_NOTIFICATIONS)
            requestPermissions(required.toTypedArray(), PERMISSIONS)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && !notificationRequested &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationRequested = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), PERMISSIONS)
            return
        }
        proceedBluetooth()
    }

    private fun proceedBluetooth() {
        val bt = getSystemService(BluetoothManager::class.java).adapter
        if (bt == null) { toast("Bluetooth není dostupné"); return }
        if (!bt.isEnabled) {
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), ENABLE_BT)
            return
        }
        associate()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == PERMISSIONS) {
            if (hasBluetoothPermissions()) beginSetup()
            else toast("Povol Bluetooth oprávnění v nastavení aplikace.")
        }
    }

    private fun associate() {
        if (chooserActive) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) {
            toast("Systém nepodporuje asociaci BLE zařízení"); return
        }
        chooserActive = true
        chooserOpened = false
        val manager = getSystemService(CompanionDeviceManager::class.java)
        val filter = BluetoothLeDeviceFilter.Builder().setNamePattern(Pattern.compile("(?i).*OBD.*")).build()
        val request = AssociationRequest.Builder().setSingleDevice(true)
            .addDeviceFilter(filter).build()
        manager.associate(request, object : CompanionDeviceManager.Callback() {
            override fun onDeviceFound(chooserLauncher: IntentSender) = showChooser(chooserLauncher)
            override fun onAssociationPending(intentSender: IntentSender) = showChooser(intentSender)
            override fun onFailure(error: CharSequence?) {
                runOnUiThread {
                    chooserActive = false
                    toast("Párování selhalo: " + error)
                }
            }
        }, null)
    }

    private fun showChooser(sender: IntentSender) = runOnUiThread {
        if (chooserOpened) return@runOnUiThread
        chooserOpened = true
        try { startIntentSenderForResult(sender, PAIR, null, 0, 0, 0) }
        catch (e: Exception) {
            chooserActive = false
            toast("Nelze zobrazit výběr BLE: " + e.message)
        }
    }

    private fun observe() {
        val addr = store.adapterAddress ?: return
        if (!store.autoEnabled || !hasBluetoothPermissions()) return
        getSystemService(CompanionDeviceManager::class.java).startObservingDevicePresence(addr)
    }

    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        when (code) {
            ENABLE_BT -> if (result == RESULT_OK) associate() else toast("Bluetooth zůstalo vypnuté")
            EXPORT -> if (result == RESULT_OK) data?.data?.let(::exportData)
            PAIR -> {
                chooserActive = false
                if (result != RESULT_OK) return
                val association = if (Build.VERSION.SDK_INT >= 33)
                    data?.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
                else null
                val device = if (Build.VERSION.SDK_INT >= 33)
                    data?.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE, BluetoothDevice::class.java)
                else data?.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE)
                val mac = association?.deviceMacAddress?.toString() ?: device?.address
                if (mac == null) { toast("BLE zařízení bez čitelné adresy"); return }
                store.adapterAddress = mac
                store.vin = null
                store.setupComplete = false
                store.autoEnabled = true
                runCatching { observe() }.onFailure {
                    store.event("OBSERVE", "Sledování přítomnosti: " + it.message)
                }
                runCatching { MonitorService.start(this) }
                    .onFailure { toast("Monitor nelze spustit: " + it.message) }
                render()
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
