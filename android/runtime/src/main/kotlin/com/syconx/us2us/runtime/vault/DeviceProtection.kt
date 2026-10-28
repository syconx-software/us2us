package com.syconx.us2us.runtime.vault

/**
 * How much the phone's secure hardware adds to the secret code (PROTOCOL.md §5.2), for the
 * Settings notice (audit 2026-10-28 M-6). Ordered from the strongest.
 */
enum class DeviceProtection {
    /**
     * Vault v2 on Android 15+ (API 35): every guess needs the hardware key, and that key works only
     * while the phone is unlocked.
     */
    FULL,

    /**
     * Vault v2 below Android 15: every guess needs the hardware key, but the key also works while
     * the phone is locked. Binding it to device unlock is avoided there on purpose: on Android
     * 12–14 such keys can be lost when the screen lock is removed (the vault could never be opened
     * again, and there is no reset), and a key made on Android 9–11 keeps its flags when the phone
     * is upgraded to 12–14.
     */
    LIMITED,

    /**
     * Vault v1 (the hardware upgrade failed or is not possible on this phone), or the device-secret
     * key is not in secure hardware: the code is protected by the key derivation and the stored
     * device secret only. The upgrade is tried again at every code unlock.
     */
    BASIC,
    ;

    companion object {
        /** `KeyProperties.SECURITY_LEVEL_SOFTWARE`, as a plain constant (usable in host tests). */
        private const val SECURITY_LEVEL_SOFTWARE = 0

        /**
         * @param formatVersion the vault file's format byte (1 or 2), `null` without a vault file.
         * @param sdk `Build.VERSION.SDK_INT`.
         * @param wrappingKeyLevel the `KeyProperties.SECURITY_LEVEL_*` of the device-secret wrapping
         *   key, `null` when unknown (no Keystore store, as in tests).
         */
        fun of(formatVersion: Int?, sdk: Int, wrappingKeyLevel: Int?): DeviceProtection = when {
            formatVersion != 2 -> BASIC
            wrappingKeyLevel == SECURITY_LEVEL_SOFTWARE -> BASIC
            !unlockedWrappingKeyRequired(sdk) -> LIMITED
            else -> FULL
        }
    }
}
