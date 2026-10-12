package com.syconx.us2us.shot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The loader reads every file of ios/design/layout; it needs no Android. */
class LayoutSpecTest {
    @Test
    fun readsEveryLayout() {
        val all = LayoutSpec.all()
        assertEquals(38, all.size)
        assertEquals(2724, all.sumOf { it.elements.size })
        // Every screen is 402 × 874 except 9.3, a guidance card.
        assertEquals(listOf("9.3-buyuk-harf"), all.filter { it.width != 402f || it.height != 874f }.map { it.name })
        assertEquals("1.1-karsilama", all.first().name)
        assertEquals("9.2-chat-en-urgent", all.last().name)
    }

    @Test
    fun findsAScreenByNumberOrName() {
        assertEquals("3.2-kod-yanlis", LayoutSpec.load("3.2").name)
        assertEquals("4.1-sohbet-listesi", LayoutSpec.load("4.1").name)
        assertEquals("4.1-sohbet-listesi-urgent", LayoutSpec.load("4.1-sohbet-listesi-urgent").name)
        assertEquals("5.1-sohbet-urgent", LayoutSpec.load("urgent/5.1-sohbet-urgent").name)
        assertEquals("9.1-chats-en", LayoutSpec.load("9.1").name)
        assertTrue(LayoutSpec.exists("7.2-yedegi-disa-aktar"))
        assertTrue(!LayoutSpec.exists("lock_wrong"))
    }

    @Test
    fun elementsCarryFrameTextFontAndColour() {
        val spec = LayoutSpec.load("3.2")
        assertEquals("3.2", spec.screen)
        assertEquals("Kod yanlış", spec.title)
        assertEquals("reference/light/3.2-kod-yanlis.png", spec.references["light"])

        assertEquals(Frame(58f, 414f, 78f, 78f), spec.frame("r.3.0.0"))
        val title = spec["r.0.1"]
        assertEquals("Gizli kodunu gir", title.text)
        assertEquals("text", title.kind)
        assertEquals("Doto", title.font?.family)
        assertEquals(900f, title.font?.weight)
        assertEquals(30f, title.font?.size)
        assertEquals(LayoutColor("text", "#e9e9ed", "#292b31"), title.color)
        assertEquals(listOf(Frame(57f, 171f, 288f, 36f)), title.textLineRects)

        val key = spec["r.3.0.0"]
        assertEquals("text·9%", key.background?.token)
        assertEquals("#292b31@9%", key.background?.forTheme(ShotTheme.Light))
        assertEquals("grid", key.layout["display"])

        val icon = spec["r.3.0.11.0"]
        assertEquals("icon", icon.kind)
        assertEquals(28.0, icon.icon?.get("size"))
        assertNull(spec.find("r.99"))
        assertEquals(listOf("r.0.1"), spec.withText("Gizli kodunu gir").map { it.id })
    }

    @Test
    fun aMissingElementNamesItsNeighbours() {
        val error = runCatching { LayoutSpec.load("3.2")["r.3.0.99"] }.exceptionOrNull()
        val message = error?.message.orEmpty()
        assertTrue(message, message.startsWith("3.2-kod-yanlis has no element 'r.3.0.99'; near: r.3.0.0"))
    }

    @Test
    fun shotNamesFollowTheRoutes() {
        assertEquals("conversation_deniz_5.3", ShotPaths.routeName("conversation:deniz/5.3"))
        assertEquals("lock_wrong", ShotPaths.routeName("lock:wrong"))
        assertEquals("settings_7.2-weak", ShotPaths.routeName("settings:7.2-weak"))
        assertEquals("3.2-kod-yanlis", ShotPaths.fileName("3.2-kod-yanlis"))
        assertEquals("dark-tr", ShotPaths.variant(ShotTheme.Dark, "tr"))
    }

    @Test
    fun jsonReaderHandlesEscapesAndNumbers() {
        @Suppress("UNCHECKED_CAST")
        val value = Json.parse("""{"a": [1, -2.5e1, true, null], "b": "x\"yç\n", "c": {}}""") as Map<String, Any?>
        assertEquals(listOf(1.0, -25.0, true, null), value["a"])
        assertEquals("x\"yç\n", value["b"])
        assertEquals(emptyMap<String, Any?>(), value["c"])
    }
}
