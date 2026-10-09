package com.syconx.us2us.core.session

import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.proto.Content
import com.syconx.us2us.core.store.ProtocolStore
import org.signal.libsignal.protocol.InvalidMessageException
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle
import java.time.Clock

/**
 * libsignal 1:1 sessions of the local account (PROTOCOL.md §5): PQXDH session setup from a pre-key
 * bundle, then Double Ratchet + SPQR encryption of padded `Content`.
 *
 * Not thread-safe (neither is libsignal's `SessionCipher`): callers serialize all calls for one
 * account, as [com.syconx.us2us.core.messenger.Messenger] does.
 */
class SessionCrypto(
    private val store: ProtocolStore,
    localAccountId: AccountId,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val localAddress = localAccountId.protocolAddress()

    fun hasUsableSession(peer: AccountId): Boolean = store.hasUsableSession(peer, clock.instant())

    /**
     * Starts a session from a bundle. libsignal verifies both pre-key signatures against the
     * bundle's identity key and applies the identity store's trust decision.
     *
     * @throws org.signal.libsignal.protocol.InvalidKeyException on a bad signature or key.
     * @throws org.signal.libsignal.protocol.UntrustedIdentityException if the identity key changed.
     */
    fun startSession(peer: AccountId, bundle: PreKeyBundle) {
        SessionBuilder(store, store, store, store, peer.protocolAddress(), localAddress).process(bundle, clock.instant())
    }

    /**
     * Serializes, pads (§5.1) and encrypts [content]. The result is a `PreKeySignalMessage` until the
     * peer has replied, a `SignalMessage` afterwards.
     *
     * @throws org.signal.libsignal.protocol.NoSessionException if there is no usable session.
     * @throws org.signal.libsignal.protocol.UntrustedIdentityException if the identity is not trusted for sending.
     */
    fun encrypt(peer: AccountId, content: Content): CiphertextMessage =
        cipher(peer).encrypt(ContentPadding.pad(content.toByteArray()), clock.instant())

    /**
     * Decrypts a ciphertext of the given libsignal [type], strips the padding and parses `Content`.
     *
     * @throws InvalidMessageException and the other libsignal decryption exceptions,
     *   [InvalidPaddingException], or `InvalidProtocolBufferException`.
     */
    fun decrypt(peer: AccountId, type: Int, ciphertext: ByteArray): Content {
        val padded = when (type) {
            CiphertextMessage.PREKEY_TYPE -> cipher(peer).decrypt(PreKeySignalMessage(ciphertext))
            CiphertextMessage.WHISPER_TYPE -> cipher(peer).decrypt(SignalMessage(ciphertext))
            else -> throw InvalidMessageException("unsupported ciphertext type $type")
        }
        return Content.parseFrom(ContentPadding.unpad(padded))
    }

    private fun cipher(peer: AccountId) =
        SessionCipher(store, store, store, store, store, localAddress, peer.protocolAddress())
}
