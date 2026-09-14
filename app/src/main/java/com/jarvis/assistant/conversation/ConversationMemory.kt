package com.jarvis.assistant.conversation

/**
 * Short-term, session-scoped chat history for follow-up understanding.
 * Cleared when the conversation ends or a new assistant session begins.
 */
class ConversationMemory(
    private val maxTurns: Int = 12
) {
    data class Turn(
        val role: Role,
        val content: String
    )

    enum class Role { USER, ASSISTANT }

    private val turns = ArrayDeque<Turn>()

    fun addUser(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        turns.addLast(Turn(Role.USER, trimmed))
        trim()
    }

    fun addAssistant(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        turns.addLast(Turn(Role.ASSISTANT, trimmed))
        trim()
    }

    fun snapshot(): List<Turn> = turns.toList()

    fun clear() {
        turns.clear()
    }

    fun isEmpty(): Boolean = turns.isEmpty()

    private fun trim() {
        while (turns.size > maxTurns * 2) {
            turns.removeFirst()
        }
    }
}
