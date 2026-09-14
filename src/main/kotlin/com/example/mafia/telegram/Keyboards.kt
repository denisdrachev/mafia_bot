package com.example.mafia.telegram

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow

object Keyboards {

    fun lobby(chatId: Long): InlineKeyboardMarkup = InlineKeyboardMarkup.builder()
        .keyboardRow(
            InlineKeyboardRow(
                button("✅ Участвую", CallbackData.encode(CallbackData.LobbyJoin(chatId))),
                button("🚪 Выйти", CallbackData.encode(CallbackData.LobbyLeave(chatId)))
            )
        )
        .build()

    fun targets(targets: List<Pair<Long, String>>, encode: (Long) -> String): InlineKeyboardMarkup {
        val rows = targets.map { (id, name) ->
            InlineKeyboardRow(button(name, encode(id)))
        }
        return InlineKeyboardMarkup.builder().keyboard(rows).build()
    }

    private fun button(text: String, callbackData: String): InlineKeyboardButton =
        InlineKeyboardButton.builder()
            .text(text)
            .callbackData(callbackData)
            .build()
}

fun escapeHtml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
