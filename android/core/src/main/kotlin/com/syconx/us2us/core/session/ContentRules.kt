package com.syconx.us2us.core.session

import com.google.protobuf.ByteString
import com.syconx.us2us.core.community.CommunityContent
import com.syconx.us2us.core.proto.Attachment
import com.syconx.us2us.core.proto.Content
import com.syconx.us2us.core.proto.ControlMessage
import com.syconx.us2us.core.proto.DataMessage

/**
 * A decrypted `Content` that fails the structural checks of [ContentRules]: the message decrypted
 * fine (its ratchet step stands) but is dropped and acknowledged, never handed on. [field] names the
 * protobuf field only, never its value.
 */
class InvalidContentException(val field: String) : Exception("malformed content field: $field")

/**
 * Structural checks every received `Content` must pass before anything acts on it (brp.proto field
 * comments; iOS `BRPContent.validateStructure()`): fixed-size ids and keys, the ghost timeout
 * range, and the community message rules. Unknown (newer) payloads and enum values pass, so an old
 * client keeps working when the schema grows; a handler treats an enum value it does not know as
 * "ignore".
 *
 * Without these checks a validly encrypted message with, say, a 3-byte `message_id` reached the
 * storage, which refused it, and the envelope was redelivered forever (audit 2026-10-28 H-1).
 */
object ContentRules {
    const val ID_BYTES = 16
    const val ATTACHMENT_KEY_BYTES = 64
    const val SHA256_BYTES = 32
    const val DELETE_TOKEN_BYTES = 32
    const val ACCESS_KEY_BYTES = 16

    /** Ghost-mode inactivity timeout (brp.proto `GhostMode.timeout_seconds`). */
    val GHOST_TIMEOUT_SECONDS = 60L..604_800L

    /** @throws InvalidContentException naming the first field that fails. */
    fun validateStructure(content: Content) {
        when (content.bodyCase) {
            Content.BodyCase.DATA -> validate(content.data)
            Content.BodyCase.RECEIPT ->
                if (content.receipt.messageIdsList.any { it.size() != ID_BYTES }) invalid("receipt.message_ids")
            Content.BodyCase.CONTROL -> validate(content.control)
            // Extension v2 (docs/COMMUNITIES.md): exactly one kind under the community's own rules.
            Content.BodyCase.COMMUNITY -> if (CommunityContent.kind(content.community) == null) invalid("community")
            Content.BodyCase.TYPING, Content.BodyCase.PRESENCE, Content.BodyCase.BODY_NOT_SET, null -> Unit
        }
    }

    /** Whether [content] passes [validateStructure]. */
    fun isStructurallyValid(content: Content): Boolean = try {
        validateStructure(content)
        true
    } catch (_: InvalidContentException) {
        false
    }

    private fun validate(data: DataMessage) {
        requireId(data.messageId, "message_id")
        if (data.hasQuote()) requireId(data.quote.targetMessageId, "quote.target_message_id")
        when (data.payloadCase) {
            DataMessage.PayloadCase.ATTACHMENT -> validate(data.attachment, "attachment")
            DataMessage.PayloadCase.LIVE_LOCATION -> requireId(data.liveLocation.sessionId, "live_location.session_id")
            DataMessage.PayloadCase.STICKER -> if (data.sticker.hasCustom()) validate(data.sticker.custom, "sticker.custom")
            DataMessage.PayloadCase.REACTION -> requireId(data.reaction.targetMessageId, "reaction.target_message_id")
            DataMessage.PayloadCase.DELETE -> requireId(data.delete.targetMessageId, "delete.target_message_id")
            DataMessage.PayloadCase.TEXT, DataMessage.PayloadCase.LOCATION, DataMessage.PayloadCase.PAYLOAD_NOT_SET, null -> Unit
        }
    }

    private fun validate(attachment: Attachment, field: String) {
        requireId(attachment.mediaId, "$field.media_id")
        if (attachment.deleteToken.size() != DELETE_TOKEN_BYTES) invalid("$field.delete_token")
        if (attachment.key.size() != ATTACHMENT_KEY_BYTES) invalid("$field.key")
        if (attachment.digest.size() != SHA256_BYTES) invalid("$field.digest")
    }

    private fun validate(control: ControlMessage) {
        when (control.actionCase) {
            ControlMessage.ActionCase.ACCESS_KEY ->
                if (control.accessKey.accessKey.size() != ACCESS_KEY_BYTES) invalid("access_key.access_key")
            ControlMessage.ActionCase.SETTINGS -> {
                val ghost = control.settings.ghost
                // A contact's out-of-range timeout would silently switch enforcement off (audit I-7).
                if (ghost.enabled && ghost.timeoutSeconds.toUInt().toLong() !in GHOST_TIMEOUT_SECONDS) {
                    invalid("settings.ghost.timeout_seconds")
                }
            }
            // A malformed profile picture reads as the initial letter (§8.7, §8.9); nothing else here
            // can make a ProfileUpdate unusable.
            ControlMessage.ActionCase.PROFILE,
            ControlMessage.ActionCase.REQUEST_RESPONSE,
            ControlMessage.ActionCase.WIPE,
            ControlMessage.ActionCase.SCREENSHOT,
            ControlMessage.ActionCase.ACTION_NOT_SET,
            null,
            -> Unit
        }
    }

    private fun requireId(id: ByteString, field: String) {
        if (id.size() != ID_BYTES) invalid(field)
    }

    private fun invalid(field: String): Nothing = throw InvalidContentException(field)
}
