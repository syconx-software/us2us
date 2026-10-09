package com.syconx.us2us.core.contacts

import com.syconx.us2us.core.AccessKey
import com.syconx.us2us.core.proto.MessageRequestResponse
import com.syconx.us2us.core.proto.content
import com.syconx.us2us.core.proto.controlMessage
import com.syconx.us2us.core.proto.messageRequestResponse
import com.syconx.us2us.core.testing.textContent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MessageRequestsTest {
    private fun response(type: MessageRequestResponse.Type) =
        content { control = controlMessage { requestResponse = messageRequestResponse { this.type = type } } }

    @Test
    fun `only an explicit ACCEPT ends our request`() {
        assertEquals(ContactState.INCOMING_REQUEST, MessageRequests.afterIncoming(null, textContent("hi")))
        assertEquals(ContactState.OUTGOING_REQUEST, MessageRequests.afterIncoming(ContactState.OUTGOING_REQUEST, textContent("hi")))
        assertEquals(
            ContactState.OUTGOING_REQUEST,
            MessageRequests.afterIncoming(ContactState.OUTGOING_REQUEST, MessageRequests.accessKeyUpdateContent(AccessKey.generate())),
        )
        assertEquals(ContactState.OUTGOING_REQUEST, MessageRequests.afterIncoming(ContactState.OUTGOING_REQUEST, response(MessageRequestResponse.Type.BLOCK)))
        assertEquals(ContactState.ACCEPTED, MessageRequests.afterIncoming(ContactState.OUTGOING_REQUEST, response(MessageRequestResponse.Type.ACCEPT)))
        assertEquals(ContactState.INCOMING_REQUEST, MessageRequests.afterIncoming(ContactState.INCOMING_REQUEST, response(MessageRequestResponse.Type.ACCEPT)))
        assertEquals(ContactState.BLOCKED, MessageRequests.afterIncoming(ContactState.BLOCKED, textContent("hi")))
    }

    @Test
    fun `acceptance and access-key messages`() {
        assertTrue(MessageRequests.isAcceptance(MessageRequests.acceptContent()))
        val key = AccessKey.generate()
        val update = MessageRequests.accessKeyUpdateContent(key)
        assertFalse(MessageRequests.isAcceptance(update))
        assertEquals(key, AccessKey(update.control.accessKey.accessKey.toByteArray()))
    }

    @Test
    fun `nothing flows back while a request is pending`() {
        assertTrue(MessageRequests.maySend(ContactState.OUTGOING_REQUEST))
        assertTrue(MessageRequests.maySend(ContactState.ACCEPTED))
        assertFalse(MessageRequests.maySend(ContactState.INCOMING_REQUEST))
        assertFalse(MessageRequests.maySend(ContactState.BLOCKED))
        assertTrue(MessageRequests.maySendReceiptsAndTyping(ContactState.ACCEPTED))
        ContactState.entries.filter { it != ContactState.ACCEPTED }.forEach { assertFalse(MessageRequests.maySendReceiptsAndTyping(it), it.name) }
        assertTrue(MessageRequests.includeSenderUsername(ContactState.OUTGOING_REQUEST))
        assertFalse(MessageRequests.includeSenderUsername(ContactState.ACCEPTED))
    }
}
