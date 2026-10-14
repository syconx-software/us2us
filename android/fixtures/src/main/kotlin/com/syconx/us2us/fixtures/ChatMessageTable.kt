package com.syconx.us2us.fixtures

import com.syconx.us2us.domain.Message
import com.syconx.us2us.domain.mapState
import com.syconx.us2us.domain.nav.ChatId
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The messages of every chat, one flow per chat: collecting a chat's messages follows only that
 * chat; writing one notifies only its collectors. A chat read before it exists gets an empty flow,
 * so its collectors are told when it appears.
 */
internal class ChatMessageTable(initial: Map<ChatId, List<Message>> = emptyMap()) {
    private val flows = ConcurrentHashMap<ChatId, MutableStateFlow<List<Message>?>>()

    init {
        for ((chat, items) in initial) flows[chat] = MutableStateFlow(items)
    }

    private fun flow(chat: ChatId): MutableStateFlow<List<Message>?> = flows.getOrPut(chat) { MutableStateFlow(null) }

    /** The chat's messages; `null` while the chat holds none (never written, or removed). */
    operator fun get(chat: ChatId): List<Message>? = flow(chat).value

    operator fun set(chat: ChatId, items: List<Message>?) {
        flow(chat).value = items
    }

    /** The chat's messages as a flow (empty while there are none). */
    fun observe(chat: ChatId): StateFlow<List<Message>> = flow(chat).mapState { it.orEmpty() }

    /** Changes the chat's list in one step. */
    fun update(chat: ChatId, change: (List<Message>) -> List<Message>) {
        val flow = flow(chat)
        while (true) {
            val current = flow.value
            val next = change(current.orEmpty())
            if (flow.compareAndSet(current, next)) return
        }
    }

    /** Replaces every chat's list (flows are kept, so open screens update). */
    fun replace(lists: Map<ChatId, List<Message>>) {
        for ((chat, flow) in flows) if (chat !in lists) flow.value = null
        for ((chat, items) in lists) flow(chat).value = items
    }

    /** The message [id] wherever it is, with its chat. */
    fun find(id: com.syconx.us2us.domain.nav.MessageId): Pair<ChatId, Message>? {
        for ((chat, flow) in flows) {
            flow.value?.firstOrNull { it.id == id }?.let { return chat to it }
        }
        return null
    }
}
