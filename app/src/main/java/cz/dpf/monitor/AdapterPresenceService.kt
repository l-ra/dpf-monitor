package cz.dpf.monitor

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.content.Intent

/** CompanionDeviceManager notifies the process when the associated BLE adapter appears.
 * On the tested Android 15 device the AssociationInfo callback is used; older API
 * 31-32 versions use address callbacks. */
@Suppress("DEPRECATION")
class AdapterPresenceService : CompanionDeviceService() {
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        onPresent(associationInfo.deviceMacAddress?.toString())
    }
    override fun onDeviceAppeared(address: String) {
        onPresent(address)
    }
    private fun onPresent(address: String?) {
        if (address == null || !address.equals(LocalStore(this).adapterAddress, ignoreCase = true)) return
        try {
            MonitorService.start(this, automatic = true)
        } catch (e: Exception) {
            LocalStore(this).event("BACKGROUND_START_FAILED",
                "Automatický start služby zamítnut: " + e.message)
        }
    }
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        onGone(associationInfo.deviceMacAddress?.toString())
    }
    override fun onDeviceDisappeared(address: String) {
        onGone(address)
    }
    private fun onGone(address: String?) {
        if (address != null && address.equals(LocalStore(this).adapterAddress, ignoreCase = true))
            stopService(Intent(this, MonitorService::class.java))
    }
}
