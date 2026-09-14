package com.example.mafia.game

import com.example.mafia.game.model.PlayerRef

/**
 * Synthetic players controlled by the bot itself. Their ids are far below any real Telegram
 * user id, so they never clash with humans and are easy to recognize anywhere in the game.
 */
object BotPlayers {

    private const val ID_OFFSET = -1_000_000_000L

    fun isBot(userId: Long): Boolean = userId <= ID_OFFSET

    fun create(count: Int): List<PlayerRef> = (1..count).map { index ->
        PlayerRef(
            userId = ID_OFFSET - index,
            username = null,
            displayName = "🤖 Бот $index"
        )
    }
}
