package com.example.mafia.telegram

import com.example.mafia.metrics.MafiaMetrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery
import org.telegram.telegrambots.meta.api.methods.GetMe
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteEphemeralMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditEphemeralMessageText
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.objects.ephemeral.EphemeralMessageParameters
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.io.Serializable

/**
 * Thin non-blocking wrapper around [TelegramClient]: every call is executed asynchronously,
 * failures are counted as metrics and never break the game loop.
 */
@Service
class TelegramGateway(
    private val client: TelegramClient,
    private val metrics: MafiaMetrics
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var botId: Long? = null

    suspend fun <T : Serializable, M : BotApiMethod<T>> execute(method: M, errorType: String): T? =
        try {
            client.executeAsync(method).await()
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            metrics.error(errorType, ex)
            log.warn("Не удалось выполнить {}: {}", method.method, ex.message)
            null
        }

    suspend fun sendGroupMessage(
        chatId: Long,
        text: String,
        markup: InlineKeyboardMarkup? = null
    ): Message? = execute(
        SendMessage.builder()
            .chatId(chatId)
            .text(text)
            .parseMode(PARSE_MODE)
            .apply { markup?.let { replyMarkup(it) } }
            .build(),
        "telegram_send_group"
    )

    suspend fun sendPrivateMessage(userId: Long, text: String): Boolean = execute(
        SendMessage.builder()
            .chatId(userId)
            .text(text)
            .parseMode(PARSE_MODE)
            .build(),
        "telegram_send_private"
    ) != null

    suspend fun sendPrivatePanel(
        userId: Long,
        text: String,
        markup: InlineKeyboardMarkup? = null
    ): Message? = execute(
        SendMessage.builder()
            .chatId(userId)
            .text(text)
            .parseMode(PARSE_MODE)
            .apply { markup?.let { replyMarkup(it) } }
            .build(),
        "telegram_send_private"
    )

    /**
     * Sends an ephemeral message visible to a single user inside the group chat (Bot API 10.3+).
     * When [callbackQueryId] is provided the previous ephemeral panel is replaced.
     */
    suspend fun sendEphemeralMessage(
        chatId: Long,
        userId: Long,
        text: String,
        markup: InlineKeyboardMarkup? = null,
        callbackQueryId: String? = null,
        replaceCallbackQueryMessage: Boolean = callbackQueryId != null
    ): Message? = execute(
        SendMessage.builder()
            .chatId(chatId)
            .text(text)
            .parseMode(PARSE_MODE)
            .apply { markup?.let { replyMarkup(it) } }
            .ephemeralMessageParameters(
                EphemeralMessageParameters.builder()
                    .receiverUserId(userId)
                    .apply {
                        if (callbackQueryId != null) {
                            callbackQueryId(callbackQueryId)
                            replaceCallbackQueryMessage(replaceCallbackQueryMessage)
                        }
                    }
                    .build()
            )
            .build(),
        "telegram_send_ephemeral"
    )

    suspend fun editEphemeralMessage(
        chatId: Long,
        userId: Long,
        ephemeralMessageId: Int,
        text: String,
        markup: InlineKeyboardMarkup? = null
    ): Boolean = execute(
        EditEphemeralMessageText.builder()
            .chatId(chatId.toString())
            .receiverUserId(userId)
            .ephemeralMessageId(ephemeralMessageId)
            .text(text)
            .parseMode(PARSE_MODE)
            .apply { markup?.let { replyMarkup(it) } }
            .build(),
        "telegram_edit_ephemeral"
    ) == true

    suspend fun deleteEphemeralMessage(chatId: Long, userId: Long, ephemeralMessageId: Int): Boolean = execute(
        DeleteEphemeralMessage.builder()
            .chatId(chatId.toString())
            .receiverUserId(userId)
            .ephemeralMessageId(ephemeralMessageId)
            .build(),
        "telegram_delete_ephemeral"
    ) == true

    suspend fun deleteMessage(chatId: Long, messageId: Int): Boolean = execute(
        DeleteMessage.builder()
            .chatId(chatId.toString())
            .messageId(messageId)
            .build(),
        "telegram_delete_message"
    ) == true

    suspend fun editGroupMessage(
        chatId: Long,
        messageId: Int,
        text: String,
        markup: InlineKeyboardMarkup? = null
    ): Boolean = execute(
        EditMessageText.builder()
            .chatId(chatId.toString())
            .messageId(messageId)
            .text(text)
            .parseMode(PARSE_MODE)
            .apply { markup?.let { replyMarkup(it) } }
            .build(),
        "telegram_edit_group"
    ) != null

    suspend fun answerCallback(callbackQueryId: String, text: String? = null, alert: Boolean = false) {
        execute(
            AnswerCallbackQuery.builder()
                .callbackQueryId(callbackQueryId)
                .apply { text?.let { text(it.take(200)) } }
                .showAlert(alert)
                .build(),
            "telegram_answer_callback"
        )
    }

    /** Cached getMe result: needed to detect that the bot itself was added to a group. */
    suspend fun botUserId(): Long? {
        botId?.let { return it }
        val me = execute(GetMe(), "telegram_get_me") ?: return null
        botId = me.id
        return me.id
    }

    suspend fun isChatAdmin(chatId: Long, userId: Long): Boolean {
        val member = execute(
            GetChatMember.builder()
                .chatId(chatId.toString())
                .userId(userId)
                .build(),
            "telegram_get_chat_member"
        ) ?: return false
        return member.status in ADMIN_STATUSES
    }

    companion object {
        const val PARSE_MODE = "HTML"
        private val ADMIN_STATUSES = setOf("creator", "administrator")
    }
}
