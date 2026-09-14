package com.example.mafia.telegram

import com.example.mafia.config.MafiaProperties
import com.example.mafia.metrics.MafiaMetrics
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot
import org.telegram.telegrambots.meta.api.objects.Update

/**
 * Long polling bot (no webhooks). Updates are received by the library thread and immediately
 * handed over to coroutines, so slow game logic never blocks polling. The consumer interface is
 * implemented directly: the library's single-thread executor would only add a useless hop.
 */
@Component
class MafiaBot(
    private val properties: MafiaProperties,
    private val updateHandler: UpdateHandler,
    private val metrics: MafiaMetrics
) : SpringLongPollingBot, LongPollingUpdateConsumer {

    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("mafia-updates"))

    override fun getBotToken(): String = properties.bot.token

    override fun getUpdatesConsumer(): LongPollingUpdateConsumer = this

    override fun consume(updates: List<Update>) {
        updates.forEach { update ->
            scope.launch {
                try {
                    updateHandler.handle(update)
                } catch (ex: Exception) {
                    metrics.error("update_handler", ex)
                    log.error("Ошибка обработки обновления {}", update.updateId, ex)
                }
            }
        }
    }

    @PreDestroy
    override fun close() {
        scope.cancel()
    }
}
