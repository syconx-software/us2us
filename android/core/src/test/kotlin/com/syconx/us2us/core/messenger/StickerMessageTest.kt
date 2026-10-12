package com.syconx.us2us.core.messenger

import com.google.protobuf.kotlin.toByteString
import com.syconx.us2us.core.proto.Attachment
import com.syconx.us2us.core.proto.Content
import com.syconx.us2us.core.proto.Sticker
import com.syconx.us2us.core.proto.attachment
import com.syconx.us2us.core.proto.content
import com.syconx.us2us.core.proto.dataMessage
import com.syconx.us2us.core.proto.sticker
import com.syconx.us2us.core.proto.text
import com.syconx.us2us.core.relay.RelayClient
import com.syconx.us2us.core.sticker.StickerCatalog
import com.syconx.us2us.core.sticker.StickerLanguage
import com.syconx.us2us.core.sticker.StickerResolution
import com.syconx.us2us.core.sticker.StickerSelection
import com.syconx.us2us.core.testing.FakeRelay
import com.syconx.us2us.core.testing.TestClient
import com.syconx.us2us.core.username.Username
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** `Sticker.lang` (field 5, PROTOCOL.md §8.8): sending, decoding and wire compatibility. */
class StickerMessageTest {
    private val hello = checkNotNull(StickerCatalog.sticker(101))
    private val cup = checkNotNull(StickerCatalog.sticker(1))

    @Test
    fun `lang survives a round trip`() {
        val sent = OutgoingSticker.of(StickerSelection(hello, StickerLanguage.TURKISH))
        assertEquals(OutgoingSticker("us2us.words", 101, "👋", "tr"), sent)

        val bytes = content { data = dataMessage { sticker = sent.toProto() } }.toByteArray()
        val received = checkNotNull(Content.parseFrom(bytes).data.stickerInfo)
        assertEquals(StickerInfo("us2us.words", 101, "👋", "tr"), received)
        assertEquals(StickerResolution.BuiltIn(StickerSelection(hello, StickerLanguage.TURKISH)), received.resolve(StickerLanguage.ENGLISH))
    }

    @Test
    fun `a wordless sticker sends no language, and nothing is written for it`() {
        val sent = OutgoingSticker.of(StickerSelection(cup, StickerLanguage.ENGLISH))
        assertEquals("", sent.lang)
        val withoutField = sticker { packId = "us2us.cups"; stickerId = 1; emoji = "👋" }
        assertArrayEquals(withoutField.toByteArray(), sent.toProto().toByteArray())
        assertEquals(StickerInfo("us2us.cups", 1, "👋"), StickerInfo.from(Sticker.parseFrom(sent.toProto().toByteArray())))
    }

    @Test
    fun `lang is field 5, and an empty lang keeps every earlier encoding byte-identical`() {
        val earlier = sticker { packId = "cb.basic"; stickerId = 3; emoji = "😂" }
        assertArrayEquals(earlier.toByteArray(), earlier.toBuilder().setLang("").build().toByteArray())

        val bytes = earlier.toBuilder().setLang("tr").build().toByteArray()
        // Tag (5 << 3) | 2 = 0x2A, length 2, "tr", appended after the earlier fields.
        assertArrayEquals(earlier.toByteArray() + byteArrayOf(0x2A, 0x02, 't'.code.toByte(), 'r'.code.toByte()), bytes)
    }

    @Test
    fun `a field a build does not know survives parsing and storage, as lang does in earlier builds`() {
        val known = sticker { packId = "us2us.words"; stickerId = 109; emoji = "👌"; lang = "en" }.toByteArray()
        // Field 6 (length-delimited), unknown to this build.
        val future = known + byteArrayOf(0x32, 0x01, 'x'.code.toByte())
        val parsed = Sticker.parseFrom(future)
        assertEquals("en", parsed.lang)
        assertArrayEquals(future, parsed.toByteArray())
    }

    @Test
    fun `received lang is kept as sent and resolved for drawing`() {
        val received = StickerInfo.from(sticker { packId = "us2us.words"; stickerId = 106; emoji = "🥺"; lang = "EN-gb" })
        assertEquals("EN-gb", received.lang)
        assertEquals(StickerResolution.BuiltIn(StickerSelection(checkNotNull(StickerCatalog.sticker(106)), StickerLanguage.ENGLISH)), received.resolve(StickerLanguage.TURKISH))

        val older = StickerInfo.from(sticker { packId = "us2us.words"; stickerId = 106; emoji = "🥺" })
        assertEquals("", older.lang)
        assertEquals(StickerResolution.BuiltIn(StickerSelection(checkNotNull(StickerCatalog.sticker(106)), StickerLanguage.TURKISH)), older.resolve(StickerLanguage.TURKISH))

        val legacy = StickerInfo.from(sticker { packId = "us2us.text"; emoji = "tamam!" })
        assertEquals(StickerResolution.LegacyText("tamam!"), legacy.resolve(StickerLanguage.ENGLISH))
    }

    @Test
    fun `a custom sticker keeps its image attachment`() {
        val image = attachment { kind = Attachment.Kind.STICKER_IMAGE; mediaId = ByteArray(16) { 1 }.toByteString(); contentType = "image/webp" }
        val received = StickerInfo.from(sticker { emoji = "🫶"; custom = image })
        assertEquals(image, received.custom)
        assertNull(StickerInfo.from(sticker { packId = "us2us.cups"; stickerId = 1; emoji = "👋" }).custom)
        assertNull(content { data = dataMessage { text = text { body = "hi" } } }.data.stickerInfo)
    }

    @Test
    fun `invalid outgoing stickers are refused`() {
        assertThrows<IllegalArgumentException> { OutgoingSticker("us2us.cups", 1, "👋", "en") } // wordless
        assertThrows<IllegalArgumentException> { OutgoingSticker("us2us.text", 0, "tamam!") }
        assertThrows<IllegalArgumentException> { OutgoingSticker("us2us.words", 101, " ") }
        assertThrows<IllegalArgumentException> { OutgoingSticker("", 101, "👋") }
        assertThrows<IllegalArgumentException> { OutgoingSticker("us2us.words", 101, "👋", "TR") }
        assertThrows<IllegalArgumentException> { OutgoingSticker("us2us.words", 101, "👋", "tr-TR") }
        // A sticker this build does not know may carry a language; the receiver decides.
        assertEquals("de", OutgoingSticker("us2us.future", 1, "🫶", "de").lang)
    }

    @Test
    fun `a sticker arrives with its pack, id, emoji and language`() = runBlocking {
        FakeRelay().start().use { relay ->
            val alice = Messenger.register(Username("alice"), RelayClient(relay.url), config = TestClient.testConfig)
            val bob = Messenger.register(Username("bob"), RelayClient(relay.url), config = TestClient.testConfig)
            alice.findByUsername("bob")
            val words = alice.sendSticker(bob.accountId, OutgoingSticker.of(StickerSelection(hello, StickerLanguage.ENGLISH)))
            alice.sendSticker(bob.accountId, OutgoingSticker.of(StickerSelection(cup, StickerLanguage.ENGLISH)), expireTimerSeconds = 60)

            val received = mutableListOf<IncomingMessage>()
            assertEquals(2, bob.fetchPending { received += it })
            assertArrayEquals(words.messageId, received[0].content.data.messageId.toByteArray())
            assertEquals(StickerInfo("us2us.words", 101, "👋", "en"), received[0].sticker)
            assertEquals(
                StickerResolution.BuiltIn(StickerSelection(hello, StickerLanguage.ENGLISH)),
                received[0].sticker?.resolve(StickerLanguage.TURKISH),
                "drawn in the sender's language, not the reader's",
            )
            assertEquals(StickerInfo("us2us.cups", 1, "👋", ""), received[1].sticker)
            assertEquals(60, received[1].content.data.expireTimerSeconds)
        }
    }
}
