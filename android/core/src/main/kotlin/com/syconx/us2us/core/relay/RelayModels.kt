package com.syconx.us2us.core.relay

import com.syconx.us2us.core.AccessKey
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.encoding.Base64Url
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/*
 * JSON bodies of the relay API (PROTOCOL.md §4). Field names are part of the wire format. Binary
 * fields are base64url without padding; 64-bit integers that may exceed 2^53 are decimal strings.
 * Classes holding key material are deliberately not data classes, so they never print bytes.
 */

internal object Base64UrlBytes : KSerializer<ByteArray> {
    override val descriptor = PrimitiveSerialDescriptor("com.syconx.us2us.core.Base64Url", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ByteArray) = encoder.encodeString(Base64Url.encode(value))
    override fun deserialize(decoder: Decoder): ByteArray = Base64Url.decode(decoder.decodeString())
}

/** A one-time EC pre-key (unsigned). */
@Serializable
class PreKeyEntity(
    val keyId: Int,
    @Serializable(with = Base64UrlBytes::class) val publicKey: ByteArray,
)

/** A signed EC pre-key, Kyber last-resort key or Kyber one-time key. */
@Serializable
class SignedPreKeyEntity(
    val keyId: Int,
    @Serializable(with = Base64UrlBytes::class) val publicKey: ByteArray,
    @Serializable(with = Base64UrlBytes::class) val signature: ByteArray,
)

@Serializable
class PowSubmission(val token: String, val nonce: String)

@Serializable
class PowChallenge(val token: String, val difficulty: Int, val expiresAt: Long)

@Serializable
class RegistrationRequest(
    @Serializable(with = Base64UrlBytes::class) val usernameHash: ByteArray,
    @Serializable(with = Base64UrlBytes::class) val identityKey: ByteArray,
    val registrationId: Int,
    val signedPreKey: SignedPreKeyEntity,
    val kyberLastResortKey: SignedPreKeyEntity,
    val oneTimePreKeys: List<PreKeyEntity>,
    val kyberOneTimePreKeys: List<SignedPreKeyEntity>,
    val pow: PowSubmission,
)

@Serializable
internal class RegistrationResponse(val accountId: AccountId, val authToken: String)

@Serializable
class AccountInfo(
    val accountId: AccountId,
    @Serializable(with = Base64UrlBytes::class) val usernameHash: ByteArray,
)

@Serializable
internal class UsernameChangeRequest(@Serializable(with = Base64UrlBytes::class) val usernameHash: ByteArray)

@Serializable
internal class TokenResponse(val authToken: String)

@Serializable
internal class AccessKeyRequest(@Serializable(with = Base64UrlBytes::class) val accessKeyHash: ByteArray?)

/** Response of the identity lookup (§4.3) and of the directory (§4.4). */
@Serializable
class AccountIdentity(
    val accountId: AccountId,
    @Serializable(with = Base64UrlBytes::class) val identityKey: ByteArray,
)

/** `PUT /v1/keys` (§4.5): any subset of the four key groups. Absent groups are not sent. */
@Serializable
class KeyUploadRequest(
    val signedPreKey: SignedPreKeyEntity? = null,
    val kyberLastResortKey: SignedPreKeyEntity? = null,
    val oneTimePreKeys: List<PreKeyEntity>? = null,
    val kyberOneTimePreKeys: List<SignedPreKeyEntity>? = null,
    val replaceAll: Boolean = false,
) {
    val isEmpty: Boolean
        get() = signedPreKey == null && kyberLastResortKey == null &&
            oneTimePreKeys.isNullOrEmpty() && kyberOneTimePreKeys.isNullOrEmpty() && !replaceAll
}

@Serializable
data class KeyStatus(
    val oneTimePreKeys: Int,
    val kyberOneTimePreKeys: Int,
    val signedPreKeyId: Int? = null,
    val kyberLastResortKeyId: Int? = null,
)

/** `GET /v1/keys/{accountId}` (§4.5). */
@Serializable
class PreKeyBundleEntity(
    val accountId: AccountId,
    val deviceId: Int,
    val registrationId: Int,
    @Serializable(with = Base64UrlBytes::class) val identityKey: ByteArray,
    val signedPreKey: SignedPreKeyEntity,
    val kyberPreKey: SignedPreKeyEntity,
    val oneTimePreKey: PreKeyEntity? = null,
)

/**
 * `202` response of a sealed send (§4.6). Ephemeral sends get the same answer whether or not the
 * recipient was online, so nobody can probe a user's online status.
 */
@Serializable
data class SendReceipt(val id: String, val serverTimestamp: Long)

/** One envelope of `POST /v1/messages/ephemeral` (§4.6): delivered only if [to] is connected. */
class EphemeralEnvelope(val to: AccountId, val accessKey: AccessKey, val envelope: ByteArray)

@Serializable
internal class EphemeralBatchRequest(val messages: List<Item>) {
    @Serializable
    class Item(
        val to: AccountId,
        @Serializable(with = Base64UrlBytes::class) val accessKey: ByteArray,
        @Serializable(with = Base64UrlBytes::class) val envelope: ByteArray,
    )
}

/** A stored envelope, from `GET /v1/messages` or a WebSocket `message` frame (§4.6, §4.7). */
@Serializable
class StoredEnvelope(
    val id: String,
    val serverTimestamp: Long,
    @Serializable(with = Base64UrlBytes::class) val envelope: ByteArray,
)

@Serializable
class EnvelopePage(val messages: List<StoredEnvelope>, val more: Boolean)

@Serializable
internal class AckRequest(val ids: List<String>)

/** `201` response of a media upload (§4.9). */
@Serializable
class MediaUpload(
    @Serializable(with = Base64UrlBytes::class) val mediaId: ByteArray,
    @Serializable(with = Base64UrlBytes::class) val deleteToken: ByteArray,
    val expiresAt: Long,
)

@Serializable
data class RelayHealth(val ok: Boolean, val version: String)

@Serializable
internal class ErrorBody(val error: ErrorDetail) {
    @Serializable
    class ErrorDetail(val code: String, val message: String = "")
}

/** WebSocket frames (§4.7). */
@Serializable
internal sealed class SocketFrame {
    @Serializable
    @SerialName("message")
    class Message(
        val id: String,
        val serverTimestamp: Long,
        @Serializable(with = Base64UrlBytes::class) val envelope: ByteArray,
    ) : SocketFrame()

    @Serializable
    @SerialName("queue_empty")
    data object QueueEmpty : SocketFrame()

    @Serializable
    @SerialName("pong")
    data object Pong : SocketFrame()

    @Serializable
    @SerialName("ack")
    class Ack(val ids: List<String>) : SocketFrame()

    @Serializable
    @SerialName("ping")
    data object Ping : SocketFrame()
}
