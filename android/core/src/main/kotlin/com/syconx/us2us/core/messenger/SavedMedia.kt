package com.syconx.us2us.core.messenger

import com.syconx.us2us.core.AccountId
import com.syconx.us2us.core.contacts.ContactState
import com.syconx.us2us.core.proto.Attachment
import com.syconx.us2us.core.proto.DataMessage
import com.syconx.us2us.core.proto.ReceiptMessage

/**
 * The rules of `ReceiptMessage{SAVED}` (PROTOCOL.md §8.4): the recipient of a photo, video or file tells
 * the sender that it saved or handed on the item outside the app (the phone's photos, files, the share
 * sheet).
 *
 * Only a permanent item can be saved — never view-once, never with a disappearing timer — and only such
 * an item gets SAVED, to an accepted contact, whatever the read-receipt setting: saving is an explicit
 * act, and the app explains before the first save that the sender will know. Send it with
 * [Messenger.sendSavedReceipt], naming the account the item came from; on the sender's side [appliesTo]
 * decides whether a received SAVED marks one of its messages. Storing when an item was saved, and
 * checking that the item is still held and not deleted, are the app's (the core keeps no messages).
 */
object SavedMedia {
    private val SAVABLE_KINDS = setOf(Attachment.Kind.IMAGE, Attachment.Kind.VIDEO, Attachment.Kind.FILE)

    /** Whether [message] can be saved outside the app, and so be acknowledged with SAVED. */
    fun isSavable(message: DataMessage): Boolean =
        message.payloadCase == DataMessage.PayloadCase.ATTACHMENT &&
            !message.viewOnce &&
            message.expireTimerSeconds == 0 &&
            message.attachment.kind in SAVABLE_KINDS

    /** SAVED goes to accepted contacts only, never for a message request. */
    fun maySend(state: ContactState): Boolean = state == ContactState.ACCEPTED

    /**
     * Whether [incoming], a receipt, marks [sent] — a message this phone sent to [sentTo] and still
     * holds undeleted (the caller checks both; the core keeps no messages) — as saved by the contact: a SAVED from that same, accepted contact that names
     * [sent], which must be a photo, video or file that can be saved. Everything else is ignored
     * (another receipt type, a stranger or a request, a message to someone else, view-once,
     * disappearing, voice notes and other payloads). The app keeps the first one; later ones for the
     * same message change nothing.
     */
    fun appliesTo(incoming: IncomingMessage, sent: DataMessage, sentTo: AccountId): Boolean {
        val content = incoming.content
        if (!content.hasReceipt() || content.receipt.type != ReceiptMessage.Type.SAVED) return false
        if (incoming.sender != sentTo || !maySend(incoming.contactState)) return false
        return isSavable(sent) && content.receipt.messageIdsList.any { it == sent.messageId }
    }
}
