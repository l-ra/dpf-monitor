package cz.dpf.monitor

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService

/**
 * Companion presence is just a wake signal. Disappearance does NOT stop the
 * monitoring foreground service: false/missed presence edges are common when
 * the BLE adapter enters power save or an established GATT link stops advertising.
 */
@Suppress("DEPRECATION")
class AdapterPresenceService : CompanionDeviceService() {
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        onPresent(associationInfo.deviceMacAddress?.toString())
    }

    override fun onDeviceAppeared(address: String) = onPresent(address)

    private fun onPresent(address: String?) {
        val store = LocalStore(this)
        if (!store.autoEnabled || address == null ||
            !address.equals(store.adapterAddress, ignoreCase = true)) return
        store.appendDiagnostic("PRESENCE CDM appeared; requesting BLE monitor")
        try {
            MonitorService.start(this, automatic = true)
        } catch (e: Exception) {
            store.event("BACKGROUND_START_FAILED", "Companion start: " + e.message)
        }
    }

    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        onGone(associationInfo.deviceMacAddress?.toString())
    }

    override fun onDeviceDisappeared(address: String) = onGone(address)

    private fun onGone(address: String?) {
        val store = LocalStore(this)
        if (address != null && address.equals(store.adapterAddress, ignoreCase = true)) {
            // The monitor itself handles transient disconnects and scanning.
            // Do not stopService() here: there may be no subsequent "appeared"
            // callback, particularly with always-powered BLE ELM327 clones.
            store.appendDiagnostic("PRESENCE CDM disappeared; foreground reconnect remains active")
        }
    }
}
