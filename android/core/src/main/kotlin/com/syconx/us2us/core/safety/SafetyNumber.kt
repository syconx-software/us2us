package com.syconx.us2us.core.safety

import com.syconx.us2us.core.AccountId
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.fingerprint.FingerprintParsingException
import org.signal.libsignal.protocol.fingerprint.FingerprintVersionMismatchException
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator
import org.signal.libsignal.protocol.fingerprint.ScannableFingerprint

/**
 * The safety number of a conversation (PROTOCOL.md §5): libsignal's numeric fingerprint with 5200
 * iterations, version 2, the 16 account-id bytes as stable identifiers and the identity keys.
 * Both sides compute the same digits; the QR code carries [scannable].
 */
class SafetyNumber private constructor(
    /** 60 digits. */
    val digits: String,
    private val scannableFingerprint: ScannableFingerprint,
) {
    /** Bytes to encode in the QR code. */
    val scannable: ByteArray get() = scannableFingerprint.serialized

    /** The digits in the twelve groups of five shown to users. */
    val groups: List<String> get() = digits.chunked(GROUP_LENGTH)

    /**
     * Compares with the peer's QR code. A QR code of another version or format is reported as a
     * mismatch, which is what the user needs to know.
     */
    fun matchesScanned(scanned: ByteArray): Boolean = try {
        scannableFingerprint.compareTo(scanned)
    } catch (_: FingerprintVersionMismatchException) {
        false
    } catch (_: FingerprintParsingException) {
        false
    }

    companion object {
        const val ITERATIONS = 5200
        const val VERSION = 2
        private const val GROUP_LENGTH = 5

        fun compute(localId: AccountId, localKey: IdentityKey, remoteId: AccountId, remoteKey: IdentityKey): SafetyNumber {
            val fingerprint = NumericFingerprintGenerator(ITERATIONS)
                .createFor(VERSION, localId.toBytes(), localKey, remoteId.toBytes(), remoteKey)
            return SafetyNumber(fingerprint.displayableFingerprint.displayText, fingerprint.scannableFingerprint)
        }
    }
}
