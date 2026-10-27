package com.syconx.us2us.core.community

import com.google.protobuf.ByteString
import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.attachment.AttachmentCipher
import com.syconx.us2us.core.proto.Attachment
import com.syconx.us2us.core.proto.CommunityMessage
import com.syconx.us2us.core.proto.Sticker
import com.syconx.us2us.core.proto.CommunityReceipt
import com.syconx.us2us.core.proto.communityMessage
import com.syconx.us2us.core.proto.communityReceipt

/**
 * The content kinds of a `CommunityMessage` (docs/COMMUNITIES.md, Extensions v2/v3) and their structural
 * checks. Sender, roster, revision freshness and delete authorization are checked by the receiver
 * against local state, not here. The 1:1 `Attachment` rules (a 32-byte delete token, a 2048-byte
 * caption) do not apply: a community attachment carries no delete token or attachment caption. Only VOICE retains a bounded waveform.
 */
object CommunityContent {
    enum class Kind { TEXT, MEDIA, STICKER, RECEIPT, DELETE }

    const val MAX_TEXT_BYTES = CommunityState.MAX_TEXT_BYTES
    const val MAX_RECEIPT_IDS = 1000
    const val MAX_THUMBNAIL_BYTES = 10 * 1024
    const val MAX_NAME_BYTES = 255
    private const val ID_BYTES = 16
    private const val DIGEST_BYTES = 32

    /** The kind of [message], or `null` when it fails any step of the validation (it is dropped). */
    fun kind(message: CommunityMessage): Kind? {
        if (message.communityId.size() != ID_BYTES || message.messageId.size() != ID_BYTES) return null
        if (message.revision !in 1..CommunityState.MAX_REVISION) return null
        if (message.sentAt <= 0) return null
        val textBytes = message.text.toByteArray(Charsets.UTF_8).size
        if (textBytes > MAX_TEXT_BYTES) return null
        val hasAttachment = message.hasAttachment()
        val hasDelete = !message.deleteTargetMessageId.isEmpty || !message.deleteTargetSenderId.isEmpty
        val hasText = message.text.isNotEmpty()
        if (message.hasSticker()) {
            val sticker = message.sticker
            if (hasAttachment || message.hasReceipt() || hasDelete || sticker.hasCustom() ||
                !hasVisibleText(sticker.packId) || sticker.packId.toByteArray(Charsets.UTF_8).size > 128 ||
                sticker.stickerId == 0 || sticker.lang.toByteArray(Charsets.UTF_8).size > 35 ||
                sticker.emoji.toByteArray(Charsets.UTF_8).size > 64 || !hasVisibleText(sticker.emoji) || message.text != sticker.emoji) return null
            return Kind.STICKER
        }
        if (hasAttachment && message.attachment.kind == Attachment.Kind.VOICE && hasText) return null
        if (message.hasReceipt()) {
            if (hasAttachment || hasDelete || hasText) return null
            return if (validReceipt(message.receipt)) Kind.RECEIPT else null
        }
        if (hasDelete) {
            if (hasAttachment || hasText) return null
            if (message.deleteTargetMessageId.size() != ID_BYTES || message.deleteTargetSenderId.size() != ID_BYTES) return null
            return Kind.DELETE
        }
        if (hasAttachment) return if (validAttachment(message.attachment)) Kind.MEDIA else null
        return if (hasVisibleText(message.text)) Kind.TEXT else null
    }

    /** Whether [text] can travel as a text message: 1..8192 UTF-8 bytes with something that is not `White_Space`. */
    fun isSendableText(text: String): Boolean = text.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES && hasVisibleText(text)

    /** A caption as senders send it: trimmed of `White_Space`, and whitespace-only as empty. */
    fun caption(text: String?): String {
        val trimmed = trimWhiteSpace(text.orEmpty())
        return if (hasVisibleText(trimmed)) trimmed else ""
    }

    /** At least one code point outside Unicode `White_Space` (TAB, LF and CR included in that property). */
    fun hasVisibleText(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val cp = text.codePointAt(index)
            if (!isWhiteSpace(cp)) return true
            index += Character.charCount(cp)
        }
        return false
    }

    /** The receipt's ids without repeats, in the order sent. */
    fun distinctIds(receipt: CommunityReceipt): List<ByteString> = receipt.messageIdsList.distinct()

    /** The pointer every member gets: the upload's delete token stays with the sender; caption is empty; only VOICE retains waveform. */
    fun pointer(attachment: Attachment): Attachment = attachment.toBuilder()
        .setDeleteToken(ByteString.EMPTY)
        .setCaption("")
        .setWaveform(if (attachment.kind == Attachment.Kind.VOICE) attachment.waveform else ByteString.EMPTY)
        .build()

    fun text(community: AccountId, revision: Long, messageId: AccountId, sentAt: Long, text: String): CommunityMessage =
        base(community, revision, messageId, sentAt).setText(text).build()

    fun media(community: AccountId, revision: Long, messageId: AccountId, sentAt: Long, attachment: Attachment, caption: String): CommunityMessage =
        base(community, revision, messageId, sentAt).setAttachment(pointer(attachment)).setText(caption(caption)).build()

    fun sticker(community: AccountId, revision: Long, messageId: AccountId, sentAt: Long, sticker: Sticker): CommunityMessage =
        base(community, revision, messageId, sentAt).setSticker(sticker).setText(sticker.emoji).build()

    fun receipt(community: AccountId, revision: Long, messageId: AccountId, sentAt: Long, type: CommunityReceipt.Type, ids: List<AccountId>): CommunityMessage {
        require(ids.size in 1..MAX_RECEIPT_IDS)
        return base(community, revision, messageId, sentAt).setReceipt(
            communityReceipt {
                this.type = type
                messageIds += ids.map { ByteString.copyFrom(it.toBytes()) }
            },
        ).build()
    }

    fun delete(community: AccountId, revision: Long, messageId: AccountId, sentAt: Long, target: AccountId, targetSender: AccountId): CommunityMessage =
        base(community, revision, messageId, sentAt)
            .setDeleteTargetMessageId(ByteString.copyFrom(target.toBytes()))
            .setDeleteTargetSenderId(ByteString.copyFrom(targetSender.toBytes()))
            .build()

    /** The same event under another revision (a receipt or a delete refreshed after the roster changed). */
    fun withRevision(message: CommunityMessage, revision: Long): CommunityMessage = message.toBuilder().setRevision(revision).build()

    private fun base(community: AccountId, revision: Long, messageId: AccountId, sentAt: Long): CommunityMessage.Builder =
        communityMessage {
            communityId = ByteString.copyFrom(community.toBytes())
            this.revision = revision
            this.messageId = ByteString.copyFrom(messageId.toBytes())
            this.sentAt = maxOf(1L, sentAt)
        }.toBuilder()

    private fun validReceipt(receipt: CommunityReceipt): Boolean {
        if (receipt.type != CommunityReceipt.Type.DELIVERY && receipt.type != CommunityReceipt.Type.READ) return false
        val ids = receipt.messageIdsList
        if (ids.isEmpty() || ids.any { it.size() != ID_BYTES }) return false
        // A repeated id counts once.
        return ids.toSet().size <= MAX_RECEIPT_IDS
    }

    private fun validAttachment(attachment: Attachment): Boolean {
        when (attachment.kind) {
            Attachment.Kind.IMAGE, Attachment.Kind.VIDEO, Attachment.Kind.FILE, Attachment.Kind.VOICE -> Unit
            else -> return false
        }
        if (attachment.mediaId.size() != ID_BYTES || attachment.key.size() != AttachmentCipher.KEY_LENGTH || attachment.digest.size() != DIGEST_BYTES) return false
        if (!attachment.deleteToken.isEmpty || attachment.caption.isNotEmpty()) return false
        if (attachment.kind == Attachment.Kind.VOICE) {
            if (attachment.contentType != "audio/mp4" || attachment.size !in 1..16L * 1024 * 1024 ||
                attachment.durationMs !in 1000..300000 || attachment.waveform.size() > 100 ||
                !attachment.thumbnail.isEmpty || attachment.fileName.isNotEmpty() || attachment.width != 0 || attachment.height != 0) return false
        } else if (!attachment.waveform.isEmpty) return false
        if (attachment.thumbnail.size() > MAX_THUMBNAIL_BYTES) return false
        if (attachment.contentType.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES) return false
        if (attachment.fileName.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES) return false
        return attachment.size in 0..AttachmentCipher.MAX_PLAINTEXT_SIZE
    }

    private fun trimWhiteSpace(text: String): String {
        var start = 0
        var end = text.length
        while (start < end) {
            val cp = text.codePointAt(start)
            if (!isWhiteSpace(cp)) break
            start += Character.charCount(cp)
        }
        while (end > start) {
            val cp = text.codePointBefore(end)
            if (!isWhiteSpace(cp)) break
            end -= Character.charCount(cp)
        }
        return text.substring(start, end)
    }

    /** The `White_Space` property (PropList.txt). */
    private fun isWhiteSpace(cp: Int): Boolean = when (cp) {
        in 0x0009..0x000D, 0x0020, 0x0085, 0x00A0, 0x1680, in 0x2000..0x200A,
        0x2028, 0x2029, 0x202F, 0x205F, 0x3000,
        -> true
        else -> false
    }
}
