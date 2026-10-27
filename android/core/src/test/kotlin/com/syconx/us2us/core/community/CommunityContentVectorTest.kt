package com.syconx.us2us.core.community

import com.google.protobuf.ByteString
import com.syconx.us2us.core.encoding.Hex
import com.syconx.us2us.core.proto.Attachment
import com.syconx.us2us.core.proto.CommunityMessage
import com.syconx.us2us.core.proto.CommunityReceipt
import com.syconx.us2us.core.proto.Content
import com.syconx.us2us.core.proto.attachment
import com.syconx.us2us.core.proto.communityMessage
import com.syconx.us2us.core.proto.communityReceipt
import com.syconx.us2us.core.proto.content
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `spec/test-vectors/communities-content.json` (Extension v2): every case is built here exactly as
 * the README table lists it and must give identical bytes for both encodings; parsing them gives
 * an equal message; the structural validation accepts exactly the `"valid": true` cases.
 */
class CommunityContentVectorTest {
    private val file = File(System.getProperty("us2us.specDir"), "test-vectors/communities-content.json")

    private fun id(n: Int): ByteString = ByteString.copyFrom(ByteArray(16) { n.toByte() })
    private fun bytes(count: Int, value: Int): ByteString = ByteString.copyFrom(ByteArray(count) { value.toByte() })
    private val community: ByteString = ByteString.copyFrom(Hex.decode("11111111222243338444555555555555"))
    private val m2: ByteString = ByteString.copyFrom(Hex.decode("00000000000040008000000000000002"))
    private val img: Attachment = attachment {
        kind = Attachment.Kind.IMAGE
        mediaId = id(0x23)
        key = bytes(64, 0x06)
        digest = bytes(32, 0x07)
        size = 123456
        contentType = "image/jpeg"
        width = 1920
        height = 1080
        thumbnail = ByteString.copyFrom(Hex.decode("ffd8ffd9"))
    }

    private fun message(messageId: Int, sentAt: Long, fill: CommunityMessage.Builder.() -> Unit): CommunityMessage =
        communityMessage {
            communityId = community
            revision = 2
            this.messageId = id(messageId)
            this.sentAt = sentAt
        }.toBuilder().apply(fill).build()

    private fun receipt(type: Int, vararg ids: ByteString): CommunityReceipt =
        communityReceipt { messageIds += ids.toList() }.toBuilder().setTypeValue(type).build()

    /** The README's table, case by case. */
    private val cases: Map<String, CommunityMessage> = mapOf(
        "text" to message(0x21, 1760000000000) { text = "Merhaba topluluk 👋" },
        "media_caption" to message(0x22, 1760000000001) { text = "Gün batımı"; attachment = img },
        "media_file_no_caption" to message(0x24, 1760000000002) {
            attachment = attachment {
                kind = Attachment.Kind.FILE
                mediaId = id(0x25)
                key = bytes(64, 0x08)
                digest = bytes(32, 0x09)
                size = 5000
                contentType = "application/pdf"
                fileName = "plan.pdf"
            }
        },
        "receipt_read" to message(0x26, 1760000000003) { receipt = receipt(CommunityReceipt.Type.READ_VALUE, id(0x21), id(0x22)) },
        "receipt_delivery" to message(0x27, 1760000000004) { receipt = receipt(CommunityReceipt.Type.DELIVERY_VALUE, id(0x24)) },
        "delete" to message(0x28, 1760000000005) { deleteTargetMessageId = id(0x22); deleteTargetSenderId = m2 },
        "invalid_empty" to message(0x29, 1760000000006) {},
        "invalid_whitespace_text" to message(0x2a, 1760000000007) { text = " \n　" },
        "invalid_receipt_with_text" to message(0x2b, 1760000000008) { text = "x"; receipt = receipt(CommunityReceipt.Type.READ_VALUE, id(0x21)) },
        "invalid_receipt_no_ids" to message(0x2c, 1760000000009) { receipt = receipt(CommunityReceipt.Type.DELIVERY_VALUE) },
        "invalid_receipt_short_id" to message(0x2d, 1760000000010) { receipt = receipt(CommunityReceipt.Type.READ_VALUE, id(0x21), bytes(15, 0x22)) },
        "invalid_receipt_unknown_type" to message(0x2e, 1760000000011) { receipt = receipt(3, id(0x21)) },
        "invalid_media_delete_token" to message(0x2f, 1760000000012) { attachment = img.toBuilder().setDeleteToken(bytes(32, 0x05)).build() },
        "invalid_media_attachment_caption" to message(0x30, 1760000000013) { attachment = img.toBuilder().setCaption("Foto").build() },
        "invalid_media_voice" to message(0x31, 1760000000014) {
            attachment = img.toBuilder().setKind(Attachment.Kind.VOICE).setContentType("audio/mp4").setThumbnail(ByteString.EMPTY).build()
        },
        "invalid_media_and_receipt" to message(0x32, 1760000000015) { attachment = img; receipt = receipt(CommunityReceipt.Type.DELIVERY_VALUE, id(0x21)) },
        "invalid_delete_without_sender" to message(0x33, 1760000000016) { deleteTargetMessageId = id(0x22) },
        "invalid_delete_short_target" to message(0x34, 1760000000017) { deleteTargetMessageId = bytes(15, 0x22); deleteTargetSenderId = m2 },
        "invalid_delete_with_text" to message(0x35, 1760000000018) { text = "silindi"; deleteTargetMessageId = id(0x22); deleteTargetSenderId = m2 },
        "invalid_zero_sent_at" to message(0x36, 0) { text = "Merhaba" },
    )

    private val expectedKinds = mapOf(
        "text" to CommunityContent.Kind.TEXT,
        "media_caption" to CommunityContent.Kind.MEDIA,
        "media_file_no_caption" to CommunityContent.Kind.MEDIA,
        "receipt_read" to CommunityContent.Kind.RECEIPT,
        "receipt_delivery" to CommunityContent.Kind.RECEIPT,
        "delete" to CommunityContent.Kind.DELETE,
    )

    private fun vectors(): List<JsonObject> {
        assumeTrue(file.exists(), "spec/test-vectors/communities-content.json is not published yet")
        return Json.parseToJsonElement(file.readText()).jsonObject["vectors"]!!.jsonArray.map { it.jsonObject }
    }

    @Test fun `every content case builds to identical bytes and parses back equal`() {
        val vectors = vectors()
        assertEquals(20, vectors.size)
        assertEquals(cases.keys, vectors.map { it["case"]!!.jsonPrimitive.content }.toSet())
        for (vector in vectors) {
            val name = vector["case"]!!.jsonPrimitive.content
            val built = cases.getValue(name)
            val wire = Hex.decode(vector["communityMessage"]!!.jsonPrimitive.content)
            val wrapped = Hex.decode(vector["content"]!!.jsonPrimitive.content)
            assertArrayEquals(wire, built.toByteArray(), "communityMessage $name")
            assertArrayEquals(wrapped, content { community = built }.toByteArray(), "content $name")
            assertEquals(built, CommunityMessage.parseFrom(wire), "parsed communityMessage $name")
            assertEquals(built, Content.parseFrom(wrapped).community, "parsed content $name")
        }
    }

    @Test fun `structural validation accepts exactly the valid cases`() {
        for (vector in vectors()) {
            val name = vector["case"]!!.jsonPrimitive.content
            val valid = vector["valid"]!!.jsonPrimitive.boolean
            val parsed = Content.parseFrom(Hex.decode(vector["content"]!!.jsonPrimitive.content)).community
            val kind = CommunityContent.kind(parsed)
            assertEquals(valid, kind != null, "validation of $name")
            if (valid) assertEquals(expectedKinds[name], kind, "kind of $name")
        }
    }

    @Test fun `v3 shared vectors bound voice and reject mixed sticker kinds`() {
        val source = File(System.getProperty("us2us.specDir"), "test-vectors/communities-v3.json")
        val vectors = Json.parseToJsonElement(source.readText()).jsonObject.getValue("vectors").jsonArray
        assertEquals(27, vectors.size)
        for (value in vectors) {
            val row = value.jsonObject
            val name = row.getValue("case").jsonPrimitive.content
            val message = CommunityMessage.parseFrom(Hex.decode(row.getValue("communityMessage").jsonPrimitive.content))
            val content = Content.parseFrom(Hex.decode(row.getValue("content").jsonPrimitive.content))
            assertEquals(message, content.community)
            val expected = when (row.getValue("kind").jsonPrimitive.content) { "sticker" -> CommunityContent.Kind.STICKER; "media" -> CommunityContent.Kind.MEDIA; else -> null }
            assertEquals(expected, CommunityContent.kind(message), name)
            if (expected == CommunityContent.Kind.STICKER) assertEquals(message.sticker.emoji, message.text)
            if (name == "voice") assertEquals(message.attachment.waveform, CommunityContent.pointer(message.attachment).waveform)
        }
    }

    @Test fun `limits beyond the vectors`() {
        val base = cases.getValue("text")
        assertNull(CommunityContent.kind(base.toBuilder().setText("x".repeat(8193)).build()), "text over 8192 bytes")
        assertTrue(CommunityContent.kind(base.toBuilder().setText("ç".repeat(4096)).build()) == CommunityContent.Kind.TEXT)
        val manyIds = (0 until 1001).map { ByteString.copyFrom(ByteArray(16).also { b -> b[0] = (it shr 8).toByte(); b[1] = it.toByte() }) }
        val tooMany = message(1, 1) { receipt = communityReceipt { type = CommunityReceipt.Type.READ; messageIds += manyIds } }
        assertNull(CommunityContent.kind(tooMany), "more than 1000 distinct ids")
        val repeated = message(1, 1) { receipt = communityReceipt { type = CommunityReceipt.Type.READ; messageIds += List(1500) { id(9) } } }
        assertEquals(CommunityContent.Kind.RECEIPT, CommunityContent.kind(repeated), "a repeated id counts once")
        val bigThumbnail = message(1, 1) { attachment = img.toBuilder().setThumbnail(bytes(10241, 1)).build() }
        assertNull(CommunityContent.kind(bigThumbnail), "thumbnail over 10 KiB")
        val sticker = message(1, 1) { attachment = img.toBuilder().setKind(Attachment.Kind.STICKER_IMAGE).build() }
        assertNull(CommunityContent.kind(sticker))
        val longName = message(1, 1) { attachment = img.toBuilder().setKind(Attachment.Kind.FILE).setFileName("a".repeat(256)).build() }
        assertNull(CommunityContent.kind(longName))
        assertEquals("", CommunityContent.caption(" 　\n"))
        assertEquals("Foto", CommunityContent.caption("  Foto\n"))
        assertFalse(CommunityContent.isSendableText("\t\r\n "))
        // A whitespace-only caption leaves as empty text, never as a text message of its own.
        val media = CommunityContent.media(
            com.syconx.us2us.core.AccountId.fromBytes(community.toByteArray()), 2,
            com.syconx.us2us.core.AccountId.fromBytes(id(0x40).toByteArray()), 5,
            img.toBuilder().setDeleteToken(bytes(32, 5)).setCaption("x").build(), "   ",
        )
        assertEquals(CommunityContent.Kind.MEDIA, CommunityContent.kind(media))
        assertTrue(media.text.isEmpty() && media.attachment.deleteToken.isEmpty && media.attachment.caption.isEmpty())
    }
}
