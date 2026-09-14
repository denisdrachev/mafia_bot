package com.example.mafia.telegram

sealed interface CallbackData {

    data class LobbyJoin(val chatId: Long) : CallbackData
    data class LobbyLeave(val chatId: Long) : CallbackData
    data class Vote(val gameId: Long, val targetId: Long) : CallbackData
    data class NightTarget(val gameId: Long, val targetId: Long) : CallbackData
    data class DayTarget(val gameId: Long, val targetId: Long) : CallbackData

    companion object {
        private const val LOBBY_JOIN = "lj"
        private const val LOBBY_LEAVE = "ll"
        private const val VOTE = "v"
        private const val NIGHT = "n"
        private const val DAY = "d"

        fun encode(data: CallbackData): String = when (data) {
            is LobbyJoin -> "$LOBBY_JOIN:${data.chatId}"
            is LobbyLeave -> "$LOBBY_LEAVE:${data.chatId}"
            is Vote -> "$VOTE:${data.gameId}:${data.targetId}"
            is NightTarget -> "$NIGHT:${data.gameId}:${data.targetId}"
            is DayTarget -> "$DAY:${data.gameId}:${data.targetId}"
        }

        fun decode(raw: String?): CallbackData? {
            val parts = raw?.split(':') ?: return null
            return runCatching {
                when (parts[0]) {
                    LOBBY_JOIN -> LobbyJoin(parts[1].toLong())
                    LOBBY_LEAVE -> LobbyLeave(parts[1].toLong())
                    VOTE -> Vote(parts[1].toLong(), parts[2].toLong())
                    NIGHT -> NightTarget(parts[1].toLong(), parts[2].toLong())
                    DAY -> DayTarget(parts[1].toLong(), parts[2].toLong())
                    else -> null
                }
            }.getOrNull()
        }
    }
}
