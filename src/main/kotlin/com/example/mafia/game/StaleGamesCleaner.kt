package com.example.mafia.game

import com.example.mafia.db.GameHistoryService
import com.example.mafia.telegram.TelegramGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/**
 * Games are kept in memory, so a restart cannot resume them. Everything that stayed RUNNING
 * in the database is closed as ABORTED and the group is notified.
 */
@Component
class StaleGamesCleaner(
    private val history: GameHistoryService,
    private val gateway: TelegramGateway
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val aborted = withContextBlocking()
        if (aborted.isEmpty()) return
        log.info("Помечено прерванными игр после рестарта: {}", aborted.size)
        runBlocking {
            aborted.forEach { chatId ->
                gateway.sendGroupMessage(
                    chatId,
                    "🛑 Бот перезапускался, поэтому предыдущая игра прервана. Начните новую: /join_game"
                )
            }
        }
    }

    private fun withContextBlocking(): List<Long> = runBlocking {
        withContext(Dispatchers.IO) { history.abortAllRunningBlocking().map { it.chatId } }
    }
}
