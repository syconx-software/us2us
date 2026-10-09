package com.syconx.us2us.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.signal.libsignal.protocol.SignalProtocolAddress
import java.nio.ByteBuffer
import java.util.UUID

/**
 * A relay account id: a random UUID, lowercase hyphenated in JSON and 16 raw bytes inside protobuf
 * (PROTOCOL.md §2). It is also the stable identity of a user; the username is only a label.
 */
@JvmInline
@Serializable(with = AccountIdSerializer::class)
value class AccountId(val uuid: UUID) {

    fun toBytes(): ByteArray =
        ByteBuffer.allocate(BYTES)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .array()

    /**
     * The libsignal address of this account's only device. Kotlin always uses the lowercase
     * string; the session MAC covers the binary ServiceId, so it interoperates with Swift's
     * uppercase store keys (PROTOCOL.md §2).
     */
    fun protocolAddress(): SignalProtocolAddress = SignalProtocolAddress(uuid.toString(), DEVICE_ID)

    override fun toString(): String = uuid.toString()

    companion object {
        const val BYTES = 16

        /** Protocol v1 has exactly one device per account. */
        const val DEVICE_ID = 1

        private val CANONICAL = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        /** @throws IllegalArgumentException unless [bytes] is exactly 16 bytes. */
        fun fromBytes(bytes: ByteArray): AccountId {
            require(bytes.size == BYTES) { "account id must be $BYTES bytes, got ${bytes.size}" }
            val buffer = ByteBuffer.wrap(bytes)
            return AccountId(UUID(buffer.long, buffer.long))
        }

        /**
         * Parses the hyphenated 8-4-4-4-12 form. [UUID.fromString] alone also accepts shortened
         * groups such as `1-1-1-1-1`, which must not be treated as a valid account id.
         */
        fun parse(text: String): AccountId {
            require(CANONICAL.matches(text)) { "not a canonical UUID" }
            return AccountId(UUID.fromString(text))
        }

        fun fromAddress(address: SignalProtocolAddress): AccountId = parse(address.name)
    }
}

internal object AccountIdSerializer : KSerializer<AccountId> {
    override val descriptor = PrimitiveSerialDescriptor("com.syconx.us2us.core.AccountId", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: AccountId) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): AccountId = AccountId.parse(decoder.decodeString())
}
