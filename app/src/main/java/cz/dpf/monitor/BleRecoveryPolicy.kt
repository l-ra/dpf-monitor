package cz.dpf.monitor

/** Pure, JVM-testable guards for advertising-address recovery. */
object BleRecoveryPolicy {
    /** Prefer the already associated address. Only a unique new name match may be probed. */
    fun chooseAddress(
        oldAddress: String, scannedAddresses: List<String>
    ): String? {
        val distinct = scannedAddresses.map { it.uppercase() }.distinct()
        distinct.firstOrNull { it.equals(oldAddress, true) }?.let { return it }
        return distinct.singleOrNull()
    }

    /**
     * A new BLE address is only promoted when it is the sole advertised
     * candidate and the ECU VIN exactly matches the previously paired vehicle.
     * The generic name "OBDII" alone never authorizes reassociation.
     */
    fun canPromoteAddress(
        previousVin: String?, candidateVin: String?,
        uniqueAdvertisement: Boolean
    ): Boolean = previousVin != null &&
        candidateVin != null &&
        previousVin == candidateVin &&
        uniqueAdvertisement
}
