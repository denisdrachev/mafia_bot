package com.example.mafia.telegram

import com.example.mafia.db.StatsService
import com.example.mafia.game.GameManager
import com.example.mafia.game.model.PlayerRef
import com.example.mafia.game.model.Role
import com.example.mafia.metrics.MafiaMetrics
import com.example.mafia.settings.GameSettings
import com.example.mafia.settings.GroupSettingsService
import com.example.mafia.settings.SettingKey
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.User
import org.telegram.telegrambots.meta.api.objects.message.Message

@Service
class UpdateHandler(
    private val gateway: TelegramGateway,
    private val manager: GameManager,
    private val settingsService: GroupSettingsService,
    private val statsService: StatsService,
    private val metrics: MafiaMetrics
) {

    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun handle(update: Update) {
        when {
            update.hasCallbackQuery() -> handleCallback(update)
            update.hasMessage() && !update.message.newChatMembers.isNullOrEmpty() ->
                handleBotAddedToGroup(update.message)

            update.hasMessage() && update.message.hasText() -> handleMessage(update.message)
        }
    }

    private suspend fun handleCallback(update: Update) {
        val query = update.callbackQuery
        val data = CallbackData.decode(query.data)
        if (data == null) {
            gateway.answerCallback(query.id, "Неизвестная кнопка.")
            return
        }
        val user = query.from
        when (data) {
            is CallbackData.LobbyJoin -> manager.joinLobby(data.chatId, user.toPlayerRef(), query.id)
            is CallbackData.LobbyLeave -> manager.leaveLobby(data.chatId, user.id, query.id)
            is CallbackData.LobbyStart -> manager.finishGathering(data.chatId, auto = false)
            is CallbackData.Vote -> withSession(query.id, data.gameId) { it.onVote(user.id, query.id, data.targetId) }
            is CallbackData.NightTarget -> withSession(query.id, data.gameId) {
                it.onNightTarget(user.id, query.id, data.targetId)
            }
            is CallbackData.DayTarget -> withSession(query.id, data.gameId) {
                it.onDayTarget(user.id, query.id, data.targetId)
            }
        }
    }

    private suspend fun withSession(
        callbackQueryId: String,
        gameId: Long,
        block: suspend (com.example.mafia.game.GameSession) -> Unit
    ) {
        val session = manager.sessionByGameId(gameId)
        if (session == null) {
            gateway.answerCallback(callbackQueryId, "Эта игра уже завершена.", alert = true)
            return
        }
        block(session)
    }

    private suspend fun handleBotAddedToGroup(message: Message) {
        val botId = gateway.botUserId() ?: return
        if (message.newChatMembers.none { it.id == botId }) return
        val settings = settingsService.settings(message.chatId, message.chat.title)
        log.info("Бот добавлен в чат {}", message.chatId)
        gateway.sendGroupMessage(
            message.chatId,
            "👋 Всем привет! Готов вести игры в мафию в этой группе.\n\n" +
                helpText() + "\n" + settingsText(settings)
        )
    }

    private suspend fun handleMessage(message: Message) {
        val text = message.text.trim()
        if (!text.startsWith("/")) return
        val parts = text.split(Regex("\\s+"))
        val command = parts.first().removePrefix("/").substringBefore("@").lowercase()
        val args = parts.drop(1)
        val user = message.from ?: return
        val isGroup = message.chat.isGroupChat == true || message.chat.isSuperGroupChat == true

        if (!isGroup) {
            handlePrivateCommand(message, command)
            return
        }
        val chatId = message.chatId
        val chatTitle = message.chat.title
        when (command) {
            "help", "start" -> gateway.sendGroupMessage(chatId, helpText())
            "join_game", "gather" -> startGathering(chatId, chatTitle, user)
            "start_game", "go" -> ifAllowed(chatId, user.id) { manager.finishGathering(chatId, auto = false) }
            "cancel" -> ifAllowed(chatId, user.id) {
                gateway.sendGroupMessage(chatId, manager.cancel(chatId))
            }
            "settings" -> showSettings(chatId, chatTitle)
            "set" -> ifAdmin(chatId, user.id) { applySetting(chatId, args) }
            "roles" -> ifAdmin(chatId, user.id) { applyRoles(chatId, args) }
            "stats" -> gateway.sendGroupMessage(chatId, statsText(chatId))
            "ef" -> gateway.sendEphemeralMessage(chatId, user.id, "👀 Это эфемерное сообщение — его видите только вы.")
            else -> Unit
        }
    }

    private suspend fun handlePrivateCommand(message: Message, command: String) {
        val text = when (command) {
            "start" -> "👋 Привет! Я веду игры в мафию в групповых чатах.\n\n" +
                "Добавьте меня в группу и начните сбор командой /join_game.\n" +
                "Роль в игре я буду присылать сюда, в личные сообщения."

            else -> helpText()
        }
        gateway.sendPrivateMessage(message.chatId, text)
    }

    private suspend fun showSettings(chatId: Long, chatTitle: String?) {
        val settings = settingsService.settings(chatId, chatTitle)
        gateway.sendGroupMessage(chatId, settingsText(settings))
    }

    private suspend fun startGathering(chatId: Long, chatTitle: String?, user: User) {
        val settings = settingsService.settings(chatId, chatTitle)
        manager.openLobby(chatId, chatTitle, user.toPlayerRef(), settings)
    }

    private suspend fun applySetting(chatId: Long, args: List<String>) {
        if (args.size < 2) {
            gateway.sendGroupMessage(
                chatId,
                "Формат: <code>/set &lt;параметр&gt; &lt;значение&gt;</code>\n" +
                    "Параметры: " + SettingKey.entries.joinToString { it.alias } + ", bots"
            )
            return
        }
        if (args[0].equals("bots", ignoreCase = true)) {
            applyBotCount(chatId, args[1])
            return
        }
        val key = SettingKey.byAlias(args[0])
        val seconds = args[1].toIntOrNull()
        if (key == null || seconds == null) {
            gateway.sendGroupMessage(
                chatId,
                "⚠️ Не понял параметр. Доступны: " + SettingKey.entries.joinToString { it.alias } + ", bots"
            )
            return
        }
        try {
            val updated = settingsService.update(chatId, key, seconds)
            gateway.sendGroupMessage(chatId, "✅ Настройка обновлена.\n\n" + settingsText(updated))
        } catch (ex: IllegalArgumentException) {
            gateway.sendGroupMessage(chatId, "⚠️ ${ex.message}")
        }
    }

    private suspend fun applyBotCount(chatId: Long, rawCount: String) {
        val count = rawCount.toIntOrNull()
        if (count == null) {
            gateway.sendGroupMessage(chatId, "⚠️ Укажите число ботов, например: <code>/set bots 3</code>")
            return
        }
        try {
            val updated = settingsService.setBotCount(chatId, count)
            gateway.sendGroupMessage(chatId, "✅ Настройка обновлена.\n\n" + settingsText(updated))
        } catch (ex: IllegalArgumentException) {
            gateway.sendGroupMessage(chatId, "⚠️ ${ex.message}")
        }
    }

    private suspend fun applyRoles(chatId: Long, args: List<String>) {
        val settings = settingsService.settings(chatId)
        if (args.isEmpty()) {
            gateway.sendGroupMessage(chatId, rolesText(settings))
            return
        }
        if (args.size < 2) {
            gateway.sendGroupMessage(chatId, "Формат: <code>/roles on|off РОЛЬ</code>")
            return
        }
        val enabled = when (args[0].lowercase()) {
            "on", "вкл" -> true
            "off", "выкл" -> false
            else -> null
        }
        val role = Role.entries.firstOrNull { it.name.equals(args[1], ignoreCase = true) }
        if (enabled == null || role == null) {
            gateway.sendGroupMessage(
                chatId,
                "⚠️ Формат: <code>/roles on|off РОЛЬ</code>\nРоли: " + Role.entries.joinToString { it.name }
            )
            return
        }
        try {
            val updated = settingsService.setRoleEnabled(chatId, role, enabled)
            gateway.sendGroupMessage(chatId, "✅ Готово.\n\n" + rolesText(updated))
        } catch (ex: IllegalArgumentException) {
            gateway.sendGroupMessage(chatId, "⚠️ ${ex.message}")
        }
    }

    private suspend fun statsText(chatId: Long): String {
        val stats = statsService.groupStats(chatId)
        if (stats.finishedGames == 0L) {
            return "📊 В этой группе ещё не было завершённых игр."
        }
        return buildString {
            appendLine("📊 <b>Статистика группы</b>")
            appendLine("Завершённых игр: ${stats.finishedGames}, прерванных: ${stats.abortedGames}")
            stats.averageDurationSeconds?.let { appendLine("Средняя длительность: ${it / 60} мин ${it % 60} сек") }
            appendLine()
            appendLine("<b>Победы по командам:</b>")
            stats.winnerDistribution.forEach { (winner, count) -> appendLine("• ${winner.title} — $count") }
            if (stats.topPlayers.isNotEmpty()) {
                appendLine()
                appendLine("<b>Топ игроков:</b>")
                stats.topPlayers.forEachIndexed { index, player ->
                    appendLine("${index + 1}. ${escapeHtml(player.displayName)} — ${player.wins} побед из ${player.games}")
                }
            }
            if (stats.recentGames.isNotEmpty()) {
                appendLine()
                appendLine("<b>Последние игры:</b>")
                stats.recentGames.forEach { game ->
                    appendLine("• ${game.winner?.title ?: "—"}, игроков: ${game.playersCount}, дней: ${game.daysPlayed}")
                }
            }
        }
    }

    private fun settingsText(settings: GameSettings): String = buildString {
        appendLine("⚙️ <b>Настройки группы</b>")
        SettingKey.entries.forEach { key ->
            appendLine("• ${key.title} (<code>${key.alias}</code>): ${settings.seconds(key)} сек (${key.min}–${key.max})")
        }
        appendLine(
            "• Ботов в игре: ${settings.botCount} " +
                "(${GameSettings.MIN_BOTS}–${GameSettings.MAX_BOTS})"
        )
        appendLine()
        appendLine("Изменить тайминги: <code>/set ${SettingKey.DAY.alias} 120</code>")
        appendLine("Изменить количество ботов: <code>/set bots 3</code> (только админы)")
    }

    private fun rolesText(settings: GameSettings): String = buildString {
        appendLine("🎭 <b>Роли в группе</b>")
        Role.entries.forEach { role ->
            val state = when {
                role.mandatory -> "всегда в игре"
                role in settings.disabledRoles -> "отключена"
                else -> "включена"
            }
            appendLine("• <b>${role.title}</b> (<code>${role.name}</code>) — $state")
        }
        appendLine()
        appendLine("Переключить: <code>/roles off MANIAC</code>")
    }

    private fun helpText(): String = buildString {
        appendLine("🎭 <b>Мафия — команды</b>")
        appendLine("/join_game — начать сбор желающих")
        appendLine("/start_game — запустить игру с набранными участниками")
        appendLine("/cancel — отменить сбор или остановить игру")
        appendLine("/settings — настройки группы и количество игроков-ботов")
        appendLine("/set &lt;параметр&gt; &lt;секунды&gt; — изменить настройку")
        appendLine("/roles [on|off РОЛЬ] — список и переключение ролей")
        appendLine("/stats — статистика игр в группе")
        appendLine("/ef — отправить эфемерное сообщение (видно только вам)")
        appendLine()
        appendLine("Запускать игру, отменять её и менять настройки могут админы группы и инициатор сбора.")
        appendLine("Перед игрой нажмите Start в личке бота — туда придёт роль.")
    }

    private suspend fun ifAllowed(chatId: Long, userId: Long, block: suspend () -> Unit) {
        val allowed = manager.lobbyInitiator(chatId) == userId || gateway.isChatAdmin(chatId, userId)
        if (!allowed) {
            gateway.sendGroupMessage(chatId, "⛔️ Это может сделать только админ группы или инициатор сбора.")
            metrics.playerAction("forbidden_command")
            return
        }
        block()
    }

    private suspend fun ifAdmin(chatId: Long, userId: Long, block: suspend () -> Unit) {
        if (!gateway.isChatAdmin(chatId, userId)) {
            gateway.sendGroupMessage(chatId, "⛔️ Менять настройки могут только админы группы.")
            return
        }
        block()
    }

    private fun User.toPlayerRef(): PlayerRef = PlayerRef(
        userId = id,
        username = userName,
        displayName = listOfNotNull(firstName, lastName).joinToString(" ").ifBlank { userName ?: id.toString() }
    )
}
