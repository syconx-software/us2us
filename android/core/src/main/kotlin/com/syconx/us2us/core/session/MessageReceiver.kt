package com.syconx.us2us.core.session

import com.google.protobuf.InvalidProtocolBufferException
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.proto.Content
import com.syconx.us2us.core.proto.SealedContent
import com.syconx.us2us.core.store.ProtocolStore
import com.syconx.us2us.core.username.Username
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage

/** Why an envelope was dropped. Every dropped envelope is still acknowledged (PROTOCOL.md §6.2). */
enum class DropReason {
    /** Unknown version, HPKE failure or malformed `SealedContent`. */
    INVALID_ENVELOPE,

    /** The envelope claims to come from this account. */
    SELF_SENT,

    /** The identity or username claimed by the sender does not match the relay (§6.3). */
    UNVERIFIED_SENDER,

    /** libsignal already decrypted this message. */
    DUPLICATE,

    /** libsignal could not decrypt (no session, unknown pre-key, bad MAC, reused base key, ...). */
    DECRYPTION_FAILED,

    /** Decrypted, but the padding or the `Content` protobuf is malformed. */
    INVALID_CONTENT,
}

sealed interface ReceiveResult {
    class Decrypted(
        val sender: AccountId,
        /** The verified `sender_username` of a message request, if the sender included one. */
        val senderUsername: Username?,
        val content: Content,
        /** True for a `PreKeySignalMessage`, i.e. the sender has not seen a reply from us yet. */
        val preKeyMessage: Boolean,
    ) : ReceiveResult

    class Dropped(val reason: DropReason) : ReceiveResult
}

/**
 * The receive path of PROTOCOL.md §6.3: open the envelope, verify the sender, decrypt with
 * libsignal (which enforces trust on first use), unpad and parse.
 *
 * [cryptoLock] must be the lock that also guards encryption for this account, because libsignal
 * session state is not thread-safe.
 */
class MessageReceiver(
    private val localAccountId: AccountId,
    private val store: ProtocolStore,
    private val sessions: SessionCrypto,
    private val verifier: SenderVerifier,
    private val cryptoLock: Mutex,
) {
    /**
     * @throws java.io.IOException if sender verification needed the relay and it could not be
     *   reached; the envelope should then be kept (not acknowledged) and retried.
     */
    suspend fun receive(envelope: ByteArray): ReceiveResult {
        // The bytes come from anyone who knows our account id: any failure to open them is a drop
        // (§6.2), never an exception that would stop the receive loop.
        val sealed = try {
            SealedEnvelope.open(envelope, store.identityKeyPair)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return ReceiveResult.Dropped(DropReason.INVALID_ENVELOPE)
        }
        val sender = AccountId.fromBytes(sealed.senderAccountId.toByteArray())
        if (sender == localAccountId) return ReceiveResult.Dropped(DropReason.SELF_SENT)

        val username = when {
            sealed.senderUsername.isEmpty() -> null
            // The sender must send the normalized form; anything else is not a valid claim.
            else -> Username.parse(sealed.senderUsername)?.takeIf { it.value == sealed.senderUsername }
                ?: return ReceiveResult.Dropped(DropReason.UNVERIFIED_SENDER)
        }
        val preKeyIdentity = if (sealed.type == CiphertextMessage.PREKEY_TYPE) {
            preKeyIdentityOf(sealed) ?: return ReceiveResult.Dropped(DropReason.DECRYPTION_FAILED)
        } else {
            null
        }
        if (!verifier.verify(sender, preKeyIdentity, username)) return ReceiveResult.Dropped(DropReason.UNVERIFIED_SENDER)

        // Everything below runs on attacker-controlled bytes. Any failure other than cancellation
        // drops the envelope; rethrowing would make it a poison message that is redelivered forever.
        val content = try {
            cryptoLock.withLock { sessions.decrypt(sender, sealed.type, sealed.ciphertext.toByteArray()) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: DuplicateMessageException) {
            return ReceiveResult.Dropped(DropReason.DUPLICATE)
        } catch (_: InvalidPaddingException) {
            return ReceiveResult.Dropped(DropReason.INVALID_CONTENT)
        } catch (_: InvalidProtocolBufferException) {
            return ReceiveResult.Dropped(DropReason.INVALID_CONTENT)
        } catch (_: Exception) {
            return ReceiveResult.Dropped(DropReason.DECRYPTION_FAILED)
        }
        return ReceiveResult.Decrypted(sender, username, content, preKeyIdentity != null)
    }

    private fun preKeyIdentityOf(sealed: SealedContent): IdentityKey? = try {
        PreKeySignalMessage(sealed.ciphertext.toByteArray()).identityKey
    } catch (_: Exception) {
        null
    }
}
