package com.syconx.us2us.runtime.vault

import android.util.Base64
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.toJavaDuration

/** Why the secret code is needed instead of the fingerprint or face. */
enum class CodeRequiredReason {
    /** The code was not entered for 7 days (§5.2). */
    SEVEN_DAYS_WITHOUT_CODE,

    /** Faces or fingers were added or removed since biometric unlock was set up (the Keystore key is invalidated). */
    ENROLLMENT_CHANGED,

    /** 5 biometric attempts failed in a row (§5.2). */
    TOO_MANY_FAILURES,

    /** The system locked biometrics (its own failure limit). */
    SYSTEM_LOCKOUT,

    /** Biometrics are off, nothing is enrolled, or there is no secure lock screen. */
    UNAVAILABLE,

    /** The Keystore key could not be used; the next code unlock re-creates it. */
    KEY_PROBLEM,
}

/** Whether the lock screen may offer biometric quick unlock. */
sealed interface QuickUnlockStatus {
    /** The user has not turned it on (or the device cannot do it). */
    data object NotEnabled : QuickUnlockStatus

    data class Available(val kind: BiometryKind) : QuickUnlockStatus

    /** Turned on, but the code is required now. */
    data class CodeRequired(val reason: CodeRequiredReason) : QuickUnlockStatus
}

/**
 * What is stored next to the vault about biometric quick unlock. The wrap is the DEK encrypted to
 * a Keystore key that only a biometric match on this phone can use; nothing here is secret on its
 * own.
 *
 * Unlike iOS there is no enrollment hash to keep: Android invalidates the key itself when faces or
 * fingers change (`setInvalidatedByBiometricEnrollment`), which [BiometricKeyProtector.isKeyValid]
 * reports.
 */
class QuickUnlockRecord(
    val wrappedKey: ByteArray,
    /** Consecutive failed biometric attempts; counted *before* the prompt and taken back only on success or cancel. */
    val failures: Int = 0,
    /** The Keystore key failed; re-create it at the next code unlock. */
    val needsRewrap: Boolean = false,
) {
    fun copy(failures: Int = this.failures, needsRewrap: Boolean = this.needsRewrap) = QuickUnlockRecord(wrappedKey, failures, needsRewrap)

    override fun equals(other: Any?): Boolean = other is QuickUnlockRecord &&
        wrappedKey.contentEquals(other.wrappedKey) && failures == other.failures && needsRewrap == other.needsRewrap

    override fun hashCode(): Int = (wrappedKey.contentHashCode() * 31 + failures) * 31 + needsRewrap.hashCode()

    override fun toString(): String = "QuickUnlockRecord(failures=$failures, needsRewrap=$needsRewrap)"
}

/** The §5.2 rules for biometric quick unlock, as a pure function. */
object QuickUnlockPolicy {
    const val MAX_FAILURES = 5
    val CODE_INTERVAL: Duration = 7.days

    /**
     * @param keyValid whether the Keystore key behind the record still works (asked last, and only
     *   when everything else allows quick unlock: it touches the Keystore).
     */
    fun status(
        record: QuickUnlockRecord?,
        lastCodeEntry: Instant?,
        availability: BiometricAvailability,
        now: Instant,
        keyValid: () -> Boolean,
    ): QuickUnlockStatus {
        if (record == null) return QuickUnlockStatus.NotEnabled
        if (record.needsRewrap) return QuickUnlockStatus.CodeRequired(CodeRequiredReason.KEY_PROBLEM)
        if (record.failures >= MAX_FAILURES) return QuickUnlockStatus.CodeRequired(CodeRequiredReason.TOO_MANY_FAILURES)
        // A clock set backwards does not extend the 7 days: a future entry date counts as stale.
        if (lastCodeEntry == null || lastCodeEntry.isAfter(now) ||
            !lastCodeEntry.plus(CODE_INTERVAL.toJavaDuration()).isAfter(now)
        ) {
            return QuickUnlockStatus.CodeRequired(CodeRequiredReason.SEVEN_DAYS_WITHOUT_CODE)
        }
        if (availability.lockedOut) return QuickUnlockStatus.CodeRequired(CodeRequiredReason.SYSTEM_LOCKOUT)
        if (!availability.isUsable) return QuickUnlockStatus.CodeRequired(CodeRequiredReason.UNAVAILABLE)
        if (!keyValid()) return QuickUnlockStatus.CodeRequired(CodeRequiredReason.ENROLLMENT_CHANGED)
        return QuickUnlockStatus.Available(availability.kind)
    }
}

/**
 * Settings and records that must be readable *before* the vault is open, kept as one small JSON
 * file next to the vault (`vault-settings.json`). Nothing in it reveals content or contacts.
 */
data class VaultSidecar(
    /** The optional "wipe after 10 wrong codes" of §5.2 (default off). */
    val wipeAfterTenFailures: Boolean = false,
    /** The last time the user unlocked with the code (for the 7-day rule). */
    val lastCodeEntry: Instant? = null,
    val quickUnlock: QuickUnlockRecord? = null,
    /** The user wants biometric unlock (kept while the wrap is being re-created). */
    val quickUnlockWanted: Boolean = false,
) {
    fun toJson(): ByteArray {
        val json = JSONObject()
            .put(WIPE, wipeAfterTenFailures)
            .put(LAST_CODE_ENTRY, lastCodeEntry?.toEpochMilli() ?: JSONObject.NULL)
            .put(WANTED, quickUnlockWanted)
        quickUnlock?.let { record ->
            json.put(
                QUICK_UNLOCK,
                JSONObject()
                    .put(WRAPPED_KEY, Base64.encodeToString(record.wrappedKey, Base64.NO_WRAP))
                    .put(FAILURES, record.failures)
                    .put(NEEDS_REWRAP, record.needsRewrap),
            )
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        private const val WIPE = "wipeAfterTenFailures"
        private const val LAST_CODE_ENTRY = "lastCodeEntry"
        private const val WANTED = "quickUnlockWanted"
        private const val QUICK_UNLOCK = "quickUnlock"
        private const val WRAPPED_KEY = "wrappedKey"
        private const val FAILURES = "failures"
        private const val NEEDS_REWRAP = "needsRewrap"

        /** The sidecar in [bytes], or the defaults when it is unreadable (as on iOS). */
        fun fromJson(bytes: ByteArray): VaultSidecar = try {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            val record = json.optJSONObject(QUICK_UNLOCK)?.let { quick ->
                QuickUnlockRecord(
                    wrappedKey = Base64.decode(quick.getString(WRAPPED_KEY), Base64.NO_WRAP),
                    failures = quick.optInt(FAILURES, 0).coerceAtLeast(0),
                    needsRewrap = quick.optBoolean(NEEDS_REWRAP, false),
                )
            }
            VaultSidecar(
                wipeAfterTenFailures = json.optBoolean(WIPE, false),
                lastCodeEntry = if (json.isNull(LAST_CODE_ENTRY)) null else Instant.ofEpochMilli(json.getLong(LAST_CODE_ENTRY)),
                quickUnlock = record,
                quickUnlockWanted = json.optBoolean(WANTED, false),
            )
        } catch (_: JSONException) {
            VaultSidecar()
        } catch (_: IllegalArgumentException) {
            VaultSidecar()
        }
    }
}
