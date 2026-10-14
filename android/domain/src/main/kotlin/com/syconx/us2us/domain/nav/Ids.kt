package com.syconx.us2us.domain.nav

/** A chat: the peer's username, as on iOS (`ChatID`). */
@JvmInline
value class ChatId(val value: String) {
    override fun toString(): String = value
}

/** A message within a chat (`MessageID`). */
@JvmInline
value class MessageId(val value: String) {
    override fun toString(): String = value
}

/** An incoming message request (`RequestID`). */
@JvmInline
value class RequestId(val value: String) {
    override fun toString(): String = value
}
