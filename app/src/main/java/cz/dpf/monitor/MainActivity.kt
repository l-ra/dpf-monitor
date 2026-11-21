package cz.dpf.monitor

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.companion.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.widget.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

@Suppress("DEPRECATION")
@SuppressLint("MissingPermission")
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: LocalStore
    private lateinit var details: TextView
    private lateinit var events: TextView
    private var chooserShown = false
    private val chooserCode = 27
    private val ui = object : Runnable {
        override fun run() {
            showSnapshot()
            handler.postDelayed(this, 2000)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = LocalStore(this)
        val scroll = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 20, 24, 24)
        }
        scroll.addView(column)
        fun label(text: String, size: Float = 16f): TextView {
            return TextView(this).also {
                it.text = text; it.textSize = size; it.setPadding(0, 8, 0, 8)
                column.addView(it)
            }
        }
        fun button(text: String, click: () -> Unit) {
            column.addView(Button(this).apply {
                this.text = text; setOnClickListener { click() }
            })
        }
        label("DPF Monitor", 26f)
        label("Škoda Superb · EA288 Evo · Android BLE", 14f)
        details = label("", 17f)
        button("1 · Povolit Bluetooth a oznámení") { requestNeededPermissions() }
        button("2 · Přiřadit jediný adaptér OBDII") { associate() }
        button("3 · Spustit monitoring") {
            if (store.adapterAddress == null) toast("Nejdřív přiřaď adaptér")
            else runCatching { MonitorService.start(this) }
                .onFailure { toast("Nelze spustit: " + it.message) }
        }
        button("Zastavit monitoring") {
            startService(Intent(this, MonitorService::class.java).setAction(MonitorService.ACTION_STOP))
        }
        button("Sdílet diagnostický log") {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, store.diagnostic())
            }, "Export diagnostiky"))
        }
        button("Zrušit vazbu adaptéru a VIN") {
            android.app.AlertDialog.Builder(this)
                .setTitle("Zrušit vazbu?")
                .setMessage("Další jízda bude vyžadovat nové párování a ověření VIN.")
                .setNegativeButton("Zrušit", null)
                .setPositiveButton("Ano") { _, _ ->
                    store.adapterAddress?.let { mac ->
                        runCatching {
                            getSystemService(CompanionDeviceManager::class.java).disassociate(mac)
                        }
                    }
                    store.adapterAddress = null
                    store.vin = null
                    showSnapshot()
                }.show()
        }
        label("Poslední události", 20f)
        events = label("", 14f)
        setContentView(scroll)
        requestNeededPermissions()
    }
    override fun onResume() { super.onResume(); handler.post(ui) }
    override fun onPause() { handler.removeCallbacks(ui); super.onPause() }

    private fun requestNeededPermissions() {
        val needs = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
            needs += Manifest.permission.BLUETOOTH_SCAN
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            needs += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            needs += Manifest.permission.POST_NOTIFICATIONS
        if (needs.isNotEmpty()) requestPermissions(needs.toTypedArray(), 31)
    }

    private fun associate() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestNeededPermissions(); return
        }
        val cdm = getSystemService(CompanionDeviceManager::class.java)
        val filter = BluetoothLeDeviceFilter.Builder()
            .setNamePattern(Pattern.compile("(?i).*OBD.*")).build()
        val request = AssociationRequest.Builder().setSingleDevice(true)
            .addDeviceFilter(filter).build()
        chooserShown = false
        cdm.associate(request, object : CompanionDeviceManager.Callback() {
            override fun onDeviceFound(chooserLauncher: IntentSender) {
                launchChooser(chooserLauncher)
            }
            override fun onAssociationPending(intentSender: IntentSender) {
                launchChooser(intentSender)
            }
            override fun onFailure(error: CharSequence?) {
                runOnUiThread { toast("Párování selhalo: " + error) }
            }
        }, null)
    }

    private fun launchChooser(sender: IntentSender) {
        if (chooserShown) return
        chooserShown = true
        try { startIntentSenderForResult(sender, chooserCode, null, 0, 0, 0) }
        catch (e: Exception) { toast("Výběr adaptéru: " + e.message) }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != chooserCode || resultCode != RESULT_OK) return
        val association = if (Build.VERSION.SDK_INT >= 33)
            data?.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
        else null
        val device = if (Build.VERSION.SDK_INT >= 33)
            data?.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE, BluetoothDevice::class.java)
        else data?.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE)
        val mac = association?.deviceMacAddress?.toString() ?: device?.address
        if (mac == null) { toast("Adaptér nemá čitelnou adresu"); return }
        store.adapterAddress = mac
        // VIN is deliberately not supplied by the user; it will be fetched from ECU.
        store.vin = null
        try {
            getSystemService(CompanionDeviceManager::class.java).startObservingDevicePresence(mac)
            toast("Adaptér uložen. Spouštím ověření ECU/VIN.")
            MonitorService.start(this)
        } catch (e: Exception) { toast("Pozorování BLE: " + e.message) }
        showSnapshot()
    }
    private fun showSnapshot() {
        if (!::details.isInitialized) return
        val s: JSONObject = store.view()
        val phase = s.optString("phase", "UNKNOWN")
        fun num(field: String, format: String = "%.1f"): String =
            if (s.isNull(field) || !s.has(field)) "—"
            else String.format(Locale.forLanguageTag("cs-CZ"), format, s.optDouble(field))
        fun duration(field: String): String {
            if (s.isNull(field) || !s.has(field)) return "—"
            val seconds = s.optLong(field)
            return (seconds / 60).toString() + " min " + (seconds % 60) + " s"
        }
        val updated = s.optLong("updated")
        val age = if (updated == 0L) "bez dat" else
            ((System.currentTimeMillis() - updated) / 1000).toString() + " s"
        details.text =
            "Adaptér: " + (store.adapterAddress ?: "nepřiřazen") +
            "\nECU/VIN: " + (store.vin ?: "neověřeno") +
            "\nRežim: " + phase +
            "\nZáznam starý: " + age +
            "\n\nOd poslední regenerace: " + num("distance") + " km" +
            "\nČas od poslední regenerace: " + duration("since") +
            "\nKritérium regenerace: " + num("trigger") + " %" +
            "\nVypočtené saze: " + num("soot") + " g" +
            "\nTeplota A / B: " + num("tempA") + " / " + num("tempB") + " °C" +
            "\n\nProbíhající regenerace: " + (if (phase == "ACTIVE") "ANO" else "NE / neověřeno") +
            "\nDélka regenerace: " + duration("elapsed") +
            "\nOdhad do ukončení: " + duration("remaining") +
            "\nOdhad do zahájení: " + duration("next") +
            "\n\nÚdaje a odhady nejsou pokynem k jízdě; kontroluj provoz."
        val history = store.events()
        val formatter = SimpleDateFormat("dd.MM. HH:mm", Locale.getDefault())
        val list = mutableListOf<String>()
        for (i in (history.length() - 1) downTo maxOf(0, history.length() - 12)) {
            val e = history.optJSONObject(i) ?: continue
            list.add(formatter.format(Date(e.optLong("at"))) + " · " + e.optString("message"))
        }
        events.text = if (list.isEmpty()) "Zatím žádné události" else list.joinToString("\n\n")
    }
    private fun toast(s: String) { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }
}
