package cz.dpf.monitor

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.companion.CompanionDeviceManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Restore redundant wake mechanisms after boot, Bluetooth enable, and package
 * replacement. A CDM callback alone is not guaranteed for an already-present
 * continuously powered BLE ELM adapter.
 */
@SuppressLint("MissingPermission")
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in listOf(Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                BluetoothAdapter.ACTION_STATE_CHANGED)) return
        val store = LocalStore(context)
        store.appendDiagnostic("BOOT/BT state receiver action=" + action)
        if (!store.autoEnabled || store.adapterAddress == null) return
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED) return
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter ?: return
        if (action == BluetoothAdapter.ACTION_STATE_CHANGED &&
            intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) != BluetoothAdapter.STATE_ON)
            return
        if (!adapter.isEnabled) return
        val manager = context.getSystemService(CompanionDeviceManager::class.java)
        try {
            if (manager.associations.isNotEmpty()) {
                // CDM tracking might already be active, which can throw; harmless.
                runCatching { manager.startObservingDevicePresence(store.adapterAddress!!) }
                    .onFailure { store.appendDiagnostic("BOOT CDM observe: " + it.message) }
            }
        } catch (e: Exception) {
            store.appendDiagnostic("BOOT association check failed: " + e.message)
        }
        runCatching { PersistentAdapterScan.register(context) }.onFailure {
            store.appendDiagnostic("BOOT background scan registration: " + it.message)
        }
        // connectedDevice FGS may start from BOOT_COMPLETED on Android 15 if its
        // permissions are granted. Catch OEM/background restrictions explicitly.
        runCatching { MonitorService.start(context, automatic = true) }.onFailure {
            store.event("BOOT_START_FAILED", "Automatický start: " + it.message)
        }
    }
}
