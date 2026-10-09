package com.syconx.us2us.core.session

import com.google.protobuf.InvalidProtocolBufferException
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.proto.SealedContent
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidMessageException
import org.signal.libsignal.protocol.message.CiphertextMessage

/** An envelope that cannot be opened or whose content is malformed. Drop and acknowledge it (§6.2). */
class InvalidEnvelopeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The sealed-sender envelope (PROTOCOL.md §6.1):
 *
 * ```
 * envelope = 0x01 ‖ HPKE-Seal(pkR = recipient identity key, info = "BRP/sealed/v1", aad = 0x01, pt = SealedContent)
 * ```
 *
 * HPKE is libsignal's RFC 9180 implementation, which the Swift client calls as well. Every seal
 * uses a fresh encapsulation, so two envelopes are never linkable by their bytes.
 */
object SealedEnvelope {
    const val VERSION: Byte = 0x01
    const val HPKE_INFO = "BRP/sealed/v1"

    private val hpkeInfo = HPKE_INFO.toByteArray(Charsets.UTF_8)
    private val hpkeAad = byteArrayOf(0x01)

    fun seal(content: SealedContent, recipientIdentityKey: IdentityKey): ByteArray =
        byteArrayOf(VERSION) + recipientIdentityKey.publicKey.seal(content.toByteArray(), hpkeInfo, hpkeAad)

    /**
     * Opens and structurally validates an envelope addressed to [identityKeyPair].
     *
     * @throws InvalidEnvelopeException on an unknown version, an HPKE failure, malformed protobuf,
     *   or a [SealedContent] that violates protocol v1 (id length, device id, message type).
     */
    fun open(envelope: ByteArray, identityKeyPair: IdentityKeyPair): SealedContent {
        if (envelope.isEmpty() || envelope[0] != VERSION) throw InvalidEnvelopeException("unknown envelope version")
        val plaintext = try {
            identityKeyPair.privateKey.open(envelope.copyOfRange(1, envelope.size), hpkeInfo, hpkeAad)
        } catch (e: InvalidMessageException) {
            throw InvalidEnvelopeException("HPKE open failed", e)
        } catch (e: IllegalArgumentException) {
            throw InvalidEnvelopeException("malformed HPKE ciphertext", e)
        }
        val content = try {
            SealedContent.parseFrom(plaintext)
        } catch (e: InvalidProtocolBufferException) {
            throw InvalidEnvelopeException("malformed SealedContent", e)
        }
        if (content.senderAccountId.size() != AccountId.BYTES) throw InvalidEnvelopeException("bad sender account id")
        if (content.senderDeviceId != AccountId.DEVICE_ID) throw InvalidEnvelopeException("bad sender device id")
        if (content.type != CiphertextMessage.WHISPER_TYPE && content.type != CiphertextMessage.PREKEY_TYPE) {
            throw InvalidEnvelopeException("unsupported ciphertext type ${content.type}")
        }
        if (content.ciphertext.isEmpty) throw InvalidEnvelopeException("empty ciphertext")
        return content
    }
}
