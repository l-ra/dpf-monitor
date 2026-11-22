package cz.dpf.monitor

import android.annotation.SuppressLint
import android.companion.CompanionDeviceManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restores companion presence observation at boot, without launching a foreground service. */
@SuppressLint("MissingPermission")
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!LocalStore(context).autoEnabled) return
        val mac = LocalStore(context).adapterAddress ?: return
        try {
            val manager = context.getSystemService(CompanionDeviceManager::class.java)
            @Suppress("DEPRECATION")
            if (manager.associations.any { it.equals(mac, ignoreCase = true) })
                manager.startObservingDevicePresence(mac)
        } catch (e: Exception) {
            LocalStore(context).event("BOOT", "Obnova sledování adaptéru selhala: " + e.message)
        }
    }
}
