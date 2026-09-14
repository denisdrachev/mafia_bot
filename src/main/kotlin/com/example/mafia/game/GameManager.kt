package com.example.mafia.game

import com.example.mafia.db.GameHistoryService
import com.example.mafia.db.NewPlayerRecord
import com.example.mafia.game.engine.RoleAssigner
import com.example.mafia.game.model.PlayerRef
import com.example.mafia.metrics.MafiaMetrics
import com.example.mafia.settings.GameSettings
import com.example.mafia.telegram.Keyboards
import com.example.mafia.telegram.TelegramGateway
import com.example.mafia.telegram.escapeHtml
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

class Lobby(
    val chatId: Long,
    val chatTitle: String?,
    val initiatorId: Long,
    val settings: GameSettings
) {
    val players = LinkedHashMap<Long, PlayerRef>()
    var messageId: Int? = null
    var timerJob: Job? = null
}

class RunningGame(
    val session: GameSession,
    val job: Job
)

/**
 * Owns lobbies and running games. Every group chat is fully independent: its own lobby,
 * its own game coroutine and its own lock, so games in different groups run in parallel.
 */
@Service
class GameManager(
    private val gateway: TelegramGateway,
    private val history: GameHistoryService,
    private val metrics: MafiaMetrics,
    private val roleAssigner: RoleAssigner,
    private val deps: GameDependencies
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("mafia-games"))

    private val lobbies = ConcurrentHashMap<Long, Lobby>()
    private val games = ConcurrentHashMap<Long, RunningGame>()
    private val chatLocks = ConcurrentHashMap<Long, Mutex>()

    fun minPlayers(): Int = roleAssigner.minPlayers()

    fun hasRunningGame(chatId: Long): Boolean = games.containsKey(chatId)

    fun lobbyInitiator(chatId: Long): Long? = lobbies[chatId]?.initiatorId

    fun session(chatId: Long): GameSession? = games[chatId]?.session

    fun sessionByGameId(gameId: Long): GameSession? =
        games.values.firstOrNull { it.session.gameId == gameId }?.session

    suspend fun openLobby(chatId: Long, chatTitle: String?, initiator: PlayerRef, settings: GameSettings) {
        lock(chatId).withLock {
            if (games.containsKey(chatId)) {
                gateway.sendGroupMessage(chatId, "⚠️ В этой группе уже идёт игра. Дождитесь её завершения или используйте /cancel.")
                return
            }
            if (lobbies.containsKey(chatId)) {
                gateway.sendGroupMessage(chatId, "⚠️ Сбор уже идёт. Нажмите «Участвую» под сообщением о сборе.")
                return
            }
            val lobby = Lobby(chatId, chatTitle, initiator.userId, settings)
            lobbies[chatId] = lobby
            metrics.lobbyOpened()
            val message = gateway.sendGroupMessage(chatId, lobbyText(lobby, emptyList()), Keyboards.lobby(chatId))
            lobby.messageId = message?.messageId
            lobby.timerJob = scope.launch {
                delay(settings.gatherSeconds * 1000L)
                finishGathering(chatId, auto = true)
            }
        }
    }

    suspend fun joinLobby(chatId: Long, player: PlayerRef, callbackQueryId: String) {
        val lobby = lobbies[chatId]
        if (lobby == null) {
            gateway.answerCallback(callbackQueryId, "Сбор уже закрыт.", alert = true)
            return
        }
        if (lobby.players.containsKey(player.userId)) {
            gateway.answerCallback(callbackQueryId, "Вы уже в списке участников.")
            return
        }
        val dmAvailable = gateway.sendPrivateMessage(
            player.userId,
            "✅ Вы записались в игру в чате «${escapeHtml(lobby.chatTitle ?: "группа")}». Роль пришлю сюда же."
        )
        if (!dmAvailable) {
            gateway.answerCallback(
                callbackQueryId,
                "Сначала напишите мне в личные сообщения (нажмите Start), иначе я не смогу отправить роль.",
                alert = true
            )
            return
        }
        lock(chatId).withLock { lobby.players[player.userId] = player }
        gateway.answerCallback(callbackQueryId, "Вы в игре!")
        refreshLobbyMessage(lobby)
    }

    suspend fun leaveLobby(chatId: Long, userId: Long, callbackQueryId: String) {
        val lobby = lobbies[chatId]
        if (lobby == null) {
            gateway.answerCallback(callbackQueryId, "Сбор уже закрыт.", alert = true)
            return
        }
        val removed = lock(chatId).withLock { lobby.players.remove(userId) != null }
        gateway.answerCallback(callbackQueryId, if (removed) "Вы вышли из игры." else "Вас не было в списке.")
        if (removed) refreshLobbyMessage(lobby)
    }

    suspend fun finishGathering(chatId: Long, auto: Boolean) {
        val lobby = lock(chatId).withLock { lobbies[chatId] } ?: run {
            if (!auto) gateway.sendGroupMessage(chatId, "⚠️ Сейчас нет активного сбора. Начните его командой /gather.")
            return
        }
        val humans = lock(chatId).withLock { lobby.players.values.toList() }
        val bots = BotPlayers.create(lobby.settings.botCount)
        val players = humans + bots
        if (players.size < minPlayers()) {
            closeLobby(chatId, "недобор игроков")
            gateway.sendGroupMessage(
                chatId,
                "🚫 Сбор отменён: набралось ${players.size} из ${minPlayers()} необходимых игроков " +
                    "(живых: ${humans.size}, ботов: ${bots.size})."
            )
            return
        }
        closeLobby(chatId, "игра запущена")
        startGame(chatId, lobby.chatTitle, players, lobby.settings)
    }

    suspend fun cancel(chatId: Long): String {
        val lobby = lobbies[chatId]
        if (lobby != null) {
            closeLobby(chatId, "отменён вручную")
            return "🚫 Сбор игроков отменён."
        }
        val running = games[chatId]
        if (running != null) {
            running.job.cancelAndJoin()
            return "🛑 Игра остановлена."
        }
        return "⚠️ Нечего отменять: ни сбора, ни игры в этой группе нет."
    }

    private suspend fun startGame(
        chatId: Long,
        chatTitle: String?,
        players: List<PlayerRef>,
        settings: GameSettings
    ) {
        val roles = roleAssigner.assign(players.map { it.userId }, settings.disabledRoles)
        val records = players
            .filterNot { BotPlayers.isBot(it.userId) }
            .map { ref ->
                NewPlayerRecord(ref, roles.getValue(ref.userId), deps.catalog.factionOf(roles.getValue(ref.userId)))
            }
        val gameId = history.createGame(chatId, chatTitle, records, playersCount = players.size)
        val session = GameSession(
            chatId = chatId,
            chatTitle = chatTitle,
            gameId = gameId,
            settings = settings,
            players = players.associateWith { roles.getValue(it.userId) },
            deps = deps
        )
        val job = scope.launch {
            try {
                session.run()
            } catch (ex: CancellationException) {
                session.onAborted("игра остановлена")
                throw ex
            } catch (ex: Exception) {
                log.error("Игра {} в чате {} упала", gameId, chatId, ex)
                metrics.error("game_loop", ex)
                session.onAborted(ex.message ?: "внутренняя ошибка")
            } finally {
                games.remove(chatId)
            }
        }
        games[chatId] = RunningGame(session, job)
    }

    private suspend fun closeLobby(chatId: Long, result: String) {
        val lobby = lock(chatId).withLock { lobbies.remove(chatId) } ?: return
        lobby.timerJob?.cancel()
        metrics.lobbyClosed(result)
        val players = lobby.players.values.toList()
        lobby.messageId?.let { messageId ->
            gateway.editGroupMessage(chatId, messageId, lobbyText(lobby, players, closed = true))
        }
    }

    private suspend fun refreshLobbyMessage(lobby: Lobby) {
        val messageId = lobby.messageId ?: return
        val players = lock(lobby.chatId).withLock { lobby.players.values.toList() }
        gateway.editGroupMessage(lobby.chatId, messageId, lobbyText(lobby, players), Keyboards.lobby(lobby.chatId))
    }

    private fun lobbyText(lobby: Lobby, players: List<PlayerRef>, closed: Boolean = false): String = buildString {
        appendLine(if (closed) "📋 <b>Сбор закрыт</b>" else "📣 <b>Сбор на игру в мафию!</b>")
        if (!closed) {
            appendLine("Время сбора: ${lobby.settings.gatherSeconds} сек. Минимум игроков: ${minPlayers()}.")
            if (lobby.settings.botCount > 0) {
                appendLine("К игре добавятся игроки-боты: ${lobby.settings.botCount}.")
            }
            appendLine("Важно: нажмите Start в личке бота, иначе роль не придёт.")
        }
        appendLine()
        if (players.isEmpty()) {
            appendLine("Пока никто не записался.")
        } else {
            appendLine("<b>Участники (${players.size}):</b>")
            players.forEachIndexed { index, ref ->
                appendLine("${index + 1}. ${escapeHtml(ref.displayName)}")
            }
        }
    }

    private fun lock(chatId: Long): Mutex = chatLocks.computeIfAbsent(chatId) { Mutex() }

    @PreDestroy
    fun shutdown() {
        log.info("Останавливаю {} активных игр", games.size)
        scope.cancel()
    }
}
