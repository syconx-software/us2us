package com.syconx.us2us.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MessageLinksTest {
    @Test fun webLinksAndUtf16Ranges() {
        val text = "👋 Bak: https://example.com/a_(b)). ve www.example.org!"
        val matches = MessageLinks.detect(text)
        assertEquals(listOf("https://example.com/a_(b)", "www.example.org"), matches.map { text.substring(it.start, it.end) })
        assertEquals(MessageLinks.Target.Web("https://www.example.org"), matches.last().target)
    }
    @Test fun invitationsStayLocalInMixedMessages() {
        val code = "us2us-community:v1:YWJj_-09"
        val matches = MessageLinks.detect("Davet: $code\nhttps://example.org")
        assertEquals(2, matches.size)
        assertEquals(code, (matches.first().target as MessageLinks.Target.CommunityInvite).code)
        assertFalse(matches.first().target.toString().contains(code))
    }
    @Test fun unsafeSchemesAndCredentialsAreNotLinks() {
        assertTrue(MessageLinks.detect("javascript:alert(1) file:///tmp/a data:text/plain,a https://user:pass@example.com").isEmpty())
    }
    @Test fun malformedAndOversizedInvitationsAreNotLinks() {
        assertTrue(MessageLinks.detect("us2us-community:v2:abc us2us-community:v1: us2us-community:v1:" + "a".repeat(2048)).isEmpty())
    }
    @Test fun literalMarkdownAndUppercaseScheme() {
        val matches = MessageLinks.detect("[görünür](HTTPS://example.org)")
        assertEquals(MessageLinks.Target.Web("HTTPS://example.org"), matches.single().target)
    }
    @Test fun longPunctuationAndEmptyText() {
        assertEquals(MessageLinks.Target.Web("https://example.org"), MessageLinks.detect("https://example.org" + ")".repeat(20_000)).single().target)
        assertTrue(MessageLinks.detect("").isEmpty())
    }
}
