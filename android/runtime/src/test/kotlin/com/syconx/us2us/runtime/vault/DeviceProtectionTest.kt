package com.syconx.us2us.runtime.vault

import org.junit.Assert.assertEquals
import org.junit.Test

/** Audit 2026-10-28 M-6: what the Settings notice says, from the vault format, Android version and key level. */
class DeviceProtectionTest {
    private val software = 0
    private val tee = 1
    private val strongBox = 2

    @Test
    fun aV1VaultIsBasicEverywhere() {
        for (sdk in listOf(28, 30, 33, 34, 35, 36)) {
            assertEquals(DeviceProtection.BASIC, DeviceProtection.of(1, sdk, tee))
            assertEquals(DeviceProtection.BASIC, DeviceProtection.of(null, sdk, tee))
        }
    }

    @Test
    fun aSoftwareWrappingKeyIsBasic() {
        assertEquals(DeviceProtection.BASIC, DeviceProtection.of(2, 36, software))
        assertEquals(DeviceProtection.BASIC, DeviceProtection.of(2, 30, software))
    }

    @Test
    fun v2IsFullOnlyFromAndroid15() {
        for (sdk in 28..34) assertEquals("sdk $sdk", DeviceProtection.LIMITED, DeviceProtection.of(2, sdk, tee))
        assertEquals(DeviceProtection.FULL, DeviceProtection.of(2, 35, tee))
        assertEquals(DeviceProtection.FULL, DeviceProtection.of(2, 36, strongBox))
        // An unknown level (no Keystore item store) is not taken as software.
        assertEquals(DeviceProtection.FULL, DeviceProtection.of(2, 36, null))
    }
}
