package com.syconx.us2us.core.vectors

import com.google.protobuf.MessageLite
import com.google.protobuf.kotlin.toByteString
import com.syconx.us2us.core.proto.Attachment
import com.syconx.us2us.core.proto.Content
import com.syconx.us2us.core.proto.ConversationWipe
import com.syconx.us2us.core.proto.PresenceMessage
import com.syconx.us2us.core.proto.ReceiptMessage
import com.syconx.us2us.core.proto.SealedContent
import com.syconx.us2us.core.proto.accessKeyUpdate
import com.syconx.us2us.core.proto.attachment
import com.syconx.us2us.core.proto.avatarPhoto
import com.syconx.us2us.core.proto.content
import com.syconx.us2us.core.proto.controlMessage
import com.syconx.us2us.core.proto.conversationSettings
import com.syconx.us2us.core.proto.conversationWipe
import com.syconx.us2us.core.proto.dataMessage
import com.syconx.us2us.core.proto.ghostMode
import com.syconx.us2us.core.proto.liveLocation
import com.syconx.us2us.core.proto.location
import com.syconx.us2us.core.proto.presenceMessage
import com.syconx.us2us.core.proto.profileAvatar
import com.syconx.us2us.core.proto.profileUpdate
import com.syconx.us2us.core.proto.quote
import com.syconx.us2us.core.proto.reaction
import com.syconx.us2us.core.proto.receiptMessage
import com.syconx.us2us.core.proto.sealedContent
import com.syconx.us2us.core.proto.sticker
import com.syconx.us2us.core.proto.text
import com.syconx.us2us.core.proto.typingMessage

/** The named messages of spec/test-vectors/README.md ("Content cases"), built exactly as listed. */
object ContentCases {
    /** `id(n)`: 16 bytes all equal to `n`. */
    private fun id(n: Int) = ByteArray(16) { n.toByte() }.toByteString()

    private fun repeated(count: Int, value: Int) = ByteArray(count) { value.toByte() }.toByteString()

    val all: Map<String, MessageLite> by lazy {
        linkedMapOf(
            "text" to content { data = dataMessage { messageId = id(1); sentAt = 1760000000000; text = text { body = "Merhaba 👋 dünya" } } },
            "text_expiring_quote" to content {
                data = dataMessage {
                    messageId = id(2)
                    sentAt = 1760000000001
                    expireTimerSeconds = 3600
                    quote = quote { targetMessageId = id(1); previewText = "Merhaba" }
                    text = text { body = "Hi" }
                }
            },
            "attachment" to content {
                data = dataMessage {
                    messageId = id(3)
                    sentAt = 1760000000002
                    viewOnce = true
                    attachment = attachment {
                        kind = Attachment.Kind.IMAGE
                        mediaId = id(4)
                        deleteToken = repeated(32, 0x05)
                        key = repeated(64, 0x06)
                        digest = repeated(32, 0x07)
                        size = 123456
                        contentType = "image/jpeg"
                        width = 1920
                        height = 1080
                        caption = "Foto"
                    }
                }
            },
            "location" to content {
                data = dataMessage {
                    messageId = id(8)
                    sentAt = 1760000000003
                    location = location { latitude = 41.0082; longitude = 28.9784; accuracyMeters = 12.5f; name = "İstanbul" }
                }
            },
            "live_location" to content {
                data = dataMessage {
                    messageId = id(9)
                    sentAt = 1760000000004
                    liveLocation = liveLocation {
                        sessionId = id(10)
                        latitude = 39.9334
                        longitude = 32.8597
                        accuracyMeters = 5f
                        headingDegrees = -1f
                        speedMps = -1f
                        expiresAt = 1760003600000
                        sequence = 7
                    }
                }
            },
            "sticker" to content {
                data = dataMessage { messageId = id(11); sentAt = 1760000000005; sticker = sticker { packId = "cb.basic"; stickerId = 3; emoji = "😂" } }
            },
            "sticker_lang" to content {
                data = dataMessage {
                    messageId = id(13)
                    sentAt = 1760000000008
                    sticker = sticker { packId = "us2us.words"; stickerId = 101; emoji = "👋"; lang = "tr" }
                }
            },
            "reaction" to content {
                data = dataMessage { messageId = id(12); sentAt = 1760000000006; reaction = reaction { targetMessageId = id(1); emoji = "❤️" } }
            },
            "receipt" to content { receipt = receiptMessage { type = ReceiptMessage.Type.READ; messageIds += listOf(id(1), id(2)) } },
            "receipt_saved" to content { receipt = receiptMessage { type = ReceiptMessage.Type.SAVED; messageIds += id(3) } },
            "typing" to content { typing = typingMessage { started = true } },
            "presence" to content { presence = presenceMessage { state = PresenceMessage.State.ONLINE; validForSeconds = 300; reply = true } },
            "ghost_settings" to content {
                control = controlMessage {
                    settings = conversationSettings {
                        updatedAt = 1760000000007
                        defaultExpireTimerSeconds = 0
                        ghost = ghostMode { enabled = true; timeoutSeconds = 3600; wipeContact = false }
                    }
                }
            },
            "wipe" to content { control = controlMessage { wipe = conversationWipe { requestedAt = 1760000000008; reason = ConversationWipe.Reason.GHOST_TIMEOUT } } },
            "access_key" to content { control = controlMessage { accessKey = accessKeyUpdate { accessKey = id(13) } } },
            "profile" to content {
                control = controlMessage {
                    profile = profileUpdate { displayName = "Kağan"; updatedAt = 1760000000009; username = "kagan_y"; presenceSharing = true }
                }
            },
            "profile_avatar_preset" to content {
                control = controlMessage {
                    profile = profileUpdate {
                        displayName = "Kağan"
                        updatedAt = 1760000000010
                        username = "kagan_y"
                        presenceSharing = true
                        profileAvatar = profileAvatar { presetId = 1 }
                    }
                }
            },
            "profile_avatar_photo" to content {
                control = controlMessage {
                    profile = profileUpdate {
                        updatedAt = 1760000000011
                        username = "kagan_y"
                        profileAvatar = profileAvatar {
                            photo = avatarPhoto { contentType = "image/jpeg"; data = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte()).toByteString() }
                        }
                    }
                }
            },
            "sealed_content" to sealedContent {
                senderAccountId = id(14)
                senderDeviceId = 1
                type = 3
                ciphertext = byteArrayOf(1, 2, 3, 4, 5).toByteString()
                senderUsername = "alice_01"
            },
        )
    }

    /** Parses [bytes] as the message type of [case]. */
    fun parse(case: String, bytes: ByteArray): MessageLite =
        if (case == "sealed_content") SealedContent.parseFrom(bytes) else Content.parseFrom(bytes)
}
