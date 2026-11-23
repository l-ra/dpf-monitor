package cz.dpf.monitor

import org.junit.Assert.*
import org.junit.Test

class BleRecoveryPolicyTest {
    @Test fun knownMacAlwaysPreferred() {
        assertEquals("AA:BB:CC:DD:EE:01", BleRecoveryPolicy.chooseAddress(
            "AA:BB:CC:DD:EE:01",
            listOf("AA:BB:CC:DD:EE:02", "AA:BB:CC:DD:EE:01")
        ))
    }

    @Test fun uniqueRotatedMacIsOnlyCandidateForVinProbe() {
        assertEquals("AA:BB:CC:DD:EE:02", BleRecoveryPolicy.chooseAddress(
            "AA:BB:CC:DD:EE:01",
            listOf("AA:BB:CC:DD:EE:02")
        ))
    }

    @Test fun ambiguityCannotSilentlyRebind() {
        assertNull(BleRecoveryPolicy.chooseAddress(
            "AA:BB:CC:DD:EE:01",
            listOf("AA:BB:CC:DD:EE:02", "AA:BB:CC:DD:EE:03")
        ))
    }

    @Test fun missingOrDifferentVinCannotRebind() {
        assertFalse(BleRecoveryPolicy.canPromoteAddress(null, "TMB12345678901234", true))
        assertFalse(BleRecoveryPolicy.canPromoteAddress("TMB12345678901234", null, true))
        assertFalse(BleRecoveryPolicy.canPromoteAddress("TMB12345678901234", "TMB99999999999999", true))
        assertFalse(BleRecoveryPolicy.canPromoteAddress("TMB12345678901234", "TMB12345678901234", false))
        assertTrue(BleRecoveryPolicy.canPromoteAddress("TMB12345678901234", "TMB12345678901234", true))
    }
}
