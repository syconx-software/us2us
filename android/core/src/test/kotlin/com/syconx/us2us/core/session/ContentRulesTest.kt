package com.syconx.us2us.core.session

import com.google.protobuf.ByteString
import com.google.protobuf.kotlin.toByteString
import com.syconx.us2us.core.proto.Content
import com.syconx.us2us.core.proto.ReceiptMessage
import com.syconx.us2us.core.proto.accessKeyUpdate
import com.syconx.us2us.core.proto.attachment
import com.syconx.us2us.core.proto.communityMessage
import com.syconx.us2us.core.proto.content
import com.syconx.us2us.core.proto.controlMessage
import com.syconx.us2us.core.proto.conversationSettings
import com.syconx.us2us.core.proto.dataMessage
import com.syconx.us2us.core.proto.delete
import com.syconx.us2us.core.proto.ghostMode
import com.syconx.us2us.core.proto.liveLocation
import com.syconx.us2us.core.proto.presenceMessage
import com.syconx.us2us.core.proto.profileUpdate
import com.syconx.us2us.core.proto.quote
import com.syconx.us2us.core.proto.reaction
import com.syconx.us2us.core.proto.receiptMessage
import com.syconx.us2us.core.proto.sticker
import com.syconx.us2us.core.proto.text
import com.syconx.us2us.core.proto.typingMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

/** Audit 2026-10-28 H-1: the structural checks of iOS `BRPContent.validateStructure()`, ported. */
class ContentRulesTest {
    private fun bytes(count: Int): ByteString = ByteArray(count) { (it + 1).toByte() }.toByteString()

    private val id = bytes(16)

    private fun validAttachment() = attachment {
        mediaId = id
        deleteToken = bytes(32)
        key = bytes(64)
        digest = bytes(32)
    }

    private fun data(build: com.syconx.us2us.core.proto.DataMessageKt.Dsl.() -> Unit): Content = content {
        data = dataMessage {
            messageId = id
            sentAt = 1
            build()
        }
    }

    private fun failingField(content: Content): String = assertThrows<InvalidContentException> { ContentRules.validateStructure(content) }.field

    @Test
    fun `well-formed content of every kind passes`() {
        val valid = listOf(
            data { text = text { body = "merhaba" } },
            data { attachment = validAttachment() },
            data { sticker = sticker { packId = "us2us.cups"; stickerId = 1; emoji = "☕" } },
            data { sticker = sticker { emoji = "☕"; custom = validAttachment() } },
            data { liveLocation = liveLocation { sessionId = id } },
            data { reaction = reaction { targetMessageId = id; emoji = "👍" } },
            data { delete = delete { targetMessageId = id } },
            data { text = text { body = "re" }; quote = quote { targetMessageId = id } },
            content { receipt = receiptMessage { type = ReceiptMessage.Type.READ; messageIds += listOf(id, bytes(16)) } },
            content { control = controlMessage { accessKey = accessKeyUpdate { accessKey = bytes(16) } } },
            content { control = controlMessage { settings = conversationSettings { ghost = ghostMode { enabled = true; timeoutSeconds = 60 } } } },
            content { control = controlMessage { settings = conversationSettings { ghost = ghostMode { enabled = true; timeoutSeconds = 604_800 } } } },
            // A disabled ghost mode's timeout is not looked at.
            content { control = controlMessage { settings = conversationSettings { ghost = ghostMode { enabled = false; timeoutSeconds = 5 } } } },
            content { control = controlMessage { profile = profileUpdate { displayName = "A" } } },
            content { typing = typingMessage { started = true } },
            content { presence = presenceMessage { } },
            Content.getDefaultInstance(),
        )
        for (content in valid) assertDoesNotThrow { ContentRules.validateStructure(content) }
    }

    @Test
    fun `a message id that is not 16 bytes is refused`() {
        for (size in listOf(0, 3, 15, 17, 64)) {
            val content = content { data = dataMessage { messageId = bytes(size); text = text { body = "x" } } }
            assertEquals("message_id", failingField(content))
            assertFalse(ContentRules.isStructurallyValid(content))
        }
    }

    @Test
    fun `every other fixed-size id and key is checked`() {
        assertEquals("quote.target_message_id", failingField(data { text = text { body = "x" }; quote = quote { targetMessageId = bytes(3) } }))
        assertEquals("live_location.session_id", failingField(data { liveLocation = liveLocation { sessionId = bytes(3) } }))
        assertEquals("reaction.target_message_id", failingField(data { reaction = reaction { targetMessageId = bytes(15) } }))
        assertEquals("delete.target_message_id", failingField(data { delete = delete { targetMessageId = ByteString.EMPTY } }))
        assertEquals("receipt.message_ids", failingField(content { receipt = receiptMessage { messageIds += listOf(id, bytes(3)) } }))
        assertEquals("access_key.access_key", failingField(content { control = controlMessage { accessKey = accessKeyUpdate { accessKey = bytes(15) } } }))
        assertEquals("attachment.media_id", failingField(data { attachment = validAttachment().toBuilder().setMediaId(bytes(3)).build() }))
        assertEquals("attachment.delete_token", failingField(data { attachment = validAttachment().toBuilder().setDeleteToken(bytes(31)).build() }))
        assertEquals("attachment.key", failingField(data { attachment = validAttachment().toBuilder().setKey(bytes(32)).build() }))
        assertEquals("attachment.digest", failingField(data { attachment = validAttachment().toBuilder().setDigest(bytes(16)).build() }))
        assertEquals(
            "sticker.custom.key",
            failingField(data { sticker = sticker { emoji = "☕"; custom = validAttachment().toBuilder().setKey(bytes(1)).build() } }),
        )
    }

    @Test
    fun `an enabled ghost timeout outside 60 to 604800 seconds is refused (I-7)`() {
        for (seconds in listOf(0, 59, 604_801, -1)) {
            val content = content { control = controlMessage { settings = conversationSettings { ghost = ghostMode { enabled = true; timeoutSeconds = seconds } } } }
            assertEquals("settings.ghost.timeout_seconds", failingField(content))
        }
    }

    @Test
    fun `a community message must be one valid kind`() {
        val valid = content { community = communityMessage { communityId = id; messageId = id; revision = 1; sentAt = 1; text = "hi" } }
        assertTrue(ContentRules.isStructurallyValid(valid))
        val shortId = content { community = communityMessage { communityId = id; messageId = bytes(3); revision = 1; sentAt = 1; text = "hi" } }
        assertEquals("community", failingField(shortId))
    }

    @Test
    fun `the exception names the field, never its value`() {
        val error = assertThrows<InvalidContentException> {
            ContentRules.validateStructure(content { data = dataMessage { messageId = "secret".toByteArray().toByteString(); text = text { body = "private words" } } })
        }
        assertFalse(error.message!!.contains("private words"))
        assertFalse(error.message!!.contains("secret"))
    }
}
