package com.example.mafia.game

import com.example.mafia.db.FinalPlayerState
import com.example.mafia.db.GameHistoryService
import com.example.mafia.game.engine.NightResolver
import com.example.mafia.game.engine.VoteResolver
import com.example.mafia.game.engine.WinConditionChecker
import com.example.mafia.game.model.DeathReason
import com.example.mafia.game.model.Faction
import com.example.mafia.game.model.GameOutcome
import com.example.mafia.game.model.GamePhase
import com.example.mafia.game.model.NightActionRequest
import com.example.mafia.game.model.PlayerRef
import com.example.mafia.game.model.PlayerView
import com.example.mafia.game.model.Role
import com.example.mafia.game.model.RoleCatalog
import com.example.mafia.metrics.MafiaMetrics
import com.example.mafia.settings.GameSettings
import com.example.mafia.telegram.CallbackData
import com.example.mafia.telegram.Keyboards
import com.example.mafia.telegram.TelegramGateway
import com.example.mafia.telegram.escapeHtml
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

class PlayerState(
    val ref: PlayerRef,
    val role: Role,
    var alive: Boolean = true,
    var deathDay: Int? = null,
    var deathReason: DeathReason? = null
) {
    val userId: Long get() = ref.userId
    val name: String get() = ref.displayName
    val isBot: Boolean get() = BotPlayers.isBot(ref.userId)
}

@Component
class GameDependencies(
    val gateway: TelegramGateway,
    val history: GameHistoryService,
    val metrics: MafiaMetrics,
    val catalog: RoleCatalog,
    val nightResolver: NightResolver,
    val winConditionChecker: WinConditionChecker
)

/**
 * A single game in a single group chat. Whole game flow lives in one coroutine, player input
 * arrives from other coroutines and is synchronized with [mutex].
 */
class GameSession(
    val chatId: Long,
    val chatTitle: String?,
    val gameId: Long,
    private val settings: GameSettings,
    players: Map<PlayerRef, Role>,
    private val deps: GameDependencies
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val mutex = Mutex()
    private val players: List<PlayerState> = players.map { (ref, role) -> PlayerState(ref, role) }
    private val startedAt = Instant.now()

    private var dayNumber = 0
    private var phase: GamePhase = GamePhase.GATHERING
    private var phaseCompletion: CompletableDeferred<Unit>? = null

    private val nightTargets = HashMap<Long, Long>()
    private val dayTargets = HashMap<Long, Long>()
    private val votes = LinkedHashMap<Long, Long>()
    private val panels = HashMap<Long, Int>()

    private var silencedForVote = emptySet<Long>()
    private var alibiForVote = emptySet<Long>()
    private var blessedForVote = emptySet<Long>()

    fun isPlayer(userId: Long): Boolean = players.any { it.userId == userId }

    suspend fun run() {
        deps.metrics.gameStarted(players.size)
        announceStart()
        sendRolesToPlayers()
        var outcome: GameOutcome? = null
        while (outcome == null) {
            dayNumber++
            runDayDiscussion()
            outcome = runDayVote()
            if (outcome != null) break
            outcome = runNight()
        }
        finish(requireNotNull(outcome))
    }

    // region phases

    private suspend fun runDayDiscussion() {
        val startedPhaseAt = Instant.now()
        mutex.withLock {
            phase = GamePhase.DAY_DISCUSSION
            dayTargets.clear()
            blessedForVote = emptySet()
        }
        deps.gateway.sendGroupMessage(
            chatId,
            buildString {
                appendLine("☀️ <b>День $dayNumber. Обсуждение</b>")
                appendLine("Время на обсуждение: ${settings.dayDiscussionSeconds} сек.")
                appendLine()
                append(alivePlayersBlock())
            }
        )
        val believers = aliveWithDayAction()
        if (believers.isEmpty()) {
            delay(settings.dayDiscussionSeconds.seconds())
        } else {
            val beforeAction = settings.dayDiscussionSeconds - settings.believerWindowSeconds
            if (beforeAction > 0) delay(beforeAction.seconds())
            openDayActionPanels(believers.filterNot { it.isBot })
            awaitPhaseEnd(
                seconds = settings.believerWindowSeconds,
                botActions = believers.filter { it.isBot }.map { bot -> suspend { botDayTarget(bot) } }
            ) { dayTargets.size >= believers.size }
            closePanels()
        }
        mutex.withLock {
            blessedForVote = dayTargets.values.toSet()
        }
        deps.metrics.phaseCompleted(GamePhase.DAY_DISCUSSION, Duration.between(startedPhaseAt, Instant.now()))
    }

    private suspend fun runDayVote(): GameOutcome? {
        val startedPhaseAt = Instant.now()
        val voters: List<PlayerState>
        mutex.withLock {
            phase = GamePhase.DAY_VOTE
            votes.clear()
            voters = alive().filter { it.userId !in silencedForVote }
        }
        deps.gateway.sendGroupMessage(
            chatId,
            buildString {
                appendLine("🗳 <b>День $dayNumber. Голосование</b>")
                appendLine("Каждый игрок получит кнопочную панель в личных сообщениях бота.")
                appendLine("Время: ${settings.dayVoteSeconds} сек. При равенстве голосов никого не казнят.")
            }
        )
        openVotePanels()
        awaitPhaseEnd(
            seconds = settings.dayVoteSeconds,
            botActions = voters.filter { it.isBot }.map { bot -> suspend { botVote(bot) } }
        ) { votes.size >= voters.size }
        closePanels()

        val resolution: com.example.mafia.game.model.VoteResolution
        val executedPlayer: PlayerState?
        mutex.withLock {
            resolution = VoteResolver.resolve(
                votes = votes.toMap(),
                silenced = silencedForVote,
                alibi = alibiForVote,
                blessed = blessedForVote
            )
            executedPlayer = resolution.executed?.let { id -> players.firstOrNull { it.userId == id } }
            executedPlayer?.let {
                it.alive = false
                it.deathDay = dayNumber
                it.deathReason = DeathReason.DAY_EXECUTION
            }
            silencedForVote = emptySet()
            alibiForVote = emptySet()
        }
        deps.gateway.sendGroupMessage(
            chatId,
            buildString {
                appendLine("📜 <b>Итоги голосования</b>")
                if (resolution.tally.isEmpty()) {
                    appendLine("Никто не проголосовал — казни не будет.")
                } else {
                    resolution.tally.entries
                        .sortedByDescending { it.value }
                        .forEach { (target, count) -> appendLine("• ${nameOf(target)} — $count голос(ов)") }
                    appendLine()
                    when {
                        executedPlayer != null -> appendLine("Казнён: <b>${escapeHtml(executedPlayer.name)}</b>")
                        resolution.tie -> appendLine("Равенство голосов — никого не казнят.")
                        else -> appendLine("Казни не будет.")
                    }
                }
            }
        )
        deps.history.recordEvent(
            gameId = gameId,
            dayNumber = dayNumber,
            phase = GamePhase.DAY_VOTE,
            type = if (executedPlayer != null) "EXECUTION" else "NO_EXECUTION",
            details = "votes=${resolution.tally}, executed=${executedPlayer?.userId}"
        )
        deps.metrics.phaseCompleted(GamePhase.DAY_VOTE, Duration.between(startedPhaseAt, Instant.now()))
        return checkOutcome(executedPlayer?.userId)
    }

    private suspend fun runNight(): GameOutcome? {
        val startedPhaseAt = Instant.now()
        val actors: List<PlayerState>
        mutex.withLock {
            phase = GamePhase.NIGHT
            nightTargets.clear()
            actors = aliveWithNightAction()
        }
        deps.gateway.sendGroupMessage(
            chatId,
            buildString {
                appendLine("🌙 <b>Ночь $dayNumber</b>")
                appendLine("Город засыпает. Те, у кого есть ночное действие, получат панель выбора в личных сообщениях бота.")
                appendLine("Время: ${settings.nightSeconds} сек.")
            }
        )
        openNightPanels(actors)
        awaitPhaseEnd(
            seconds = settings.nightSeconds,
            botActions = actors.filter { it.isBot }.map { bot -> suspend { botNightTarget(bot) } }
        ) { nightTargets.size >= actors.size }
        closePanels()

        val requests = mutex.withLock {
            nightTargets.mapNotNull { (actorId, targetId) ->
                val actor = players.firstOrNull { it.userId == actorId } ?: return@mapNotNull null
                val action = actor.role.nightAction ?: return@mapNotNull null
                NightActionRequest(actorId, actor.role, action, targetId)
            }
        }
        val resolution = deps.nightResolver.resolve(requests) { id -> players.firstOrNull { it.userId == id }?.role }
        val killed = mutex.withLock {
            val killed = players.filter { it.alive && it.userId in resolution.deaths }
            killed.forEach {
                it.alive = false
                it.deathDay = dayNumber
                it.deathReason = DeathReason.NIGHT_KILL
            }
            silencedForVote = resolution.silencedForNextVote
            alibiForVote = resolution.alibiForNextVote
            killed
        }
        resolution.checkResults.forEach { (actorId, result) ->
            if (BotPlayers.isBot(actorId)) return@forEach
            val verdict = if (result.canKill) "умеет убивать" else "не умеет убивать"
            deps.gateway.sendPrivateMessage(
                actorId,
                "🔍 Результат проверки: <b>${nameOf(result.targetId)}</b> — $verdict."
            )
        }
        deps.gateway.sendGroupMessage(
            chatId,
            buildString {
                appendLine("🌅 <b>Утро ${dayNumber + 1}</b>. Доброе утро, город!")
                appendLine()
                if (killed.isEmpty()) {
                    appendLine("Этой ночью все выжили.")
                } else {
                    killed.forEach { appendLine("☠️ Этой ночью погиб(ла): <b>${escapeHtml(it.name)}</b>") }
                }
                if (resolution.savedFromDeath.isNotEmpty()) {
                    appendLine("🏥 Кого-то этой ночью спасли врачи.")
                }
                appendLine()
                append(alivePlayersBlock())
            }
        )
        deps.history.recordEvent(
            gameId = gameId,
            dayNumber = dayNumber,
            phase = GamePhase.NIGHT,
            type = "NIGHT_RESOLVED",
            details = "deaths=${resolution.deaths}, healed=${resolution.healed}, actions=${requests.size}"
        )
        deps.metrics.phaseCompleted(GamePhase.NIGHT, Duration.between(startedPhaseAt, Instant.now()))
        return checkOutcome(null)
    }

    // endregion

    // region player input

    suspend fun onVote(userId: Long, callbackQueryId: String, targetId: Long) {
        val error = mutex.withLock {
            when {
                phase != GamePhase.DAY_VOTE -> "Сейчас не время голосования."
                !isAliveNow(userId) -> "Голосовать могут только живые участники игры."
                userId in silencedForVote -> "Вам запрещено голосовать на этом голосовании."
                userId == targetId -> "Голосовать за себя нельзя."
                !isAliveNow(targetId) -> "Этот игрок уже вне игры."
                else -> {
                    votes[userId] = targetId
                    null
                }
            }
        }
        respond(userId, callbackQueryId, error, "🗳 Ваш голос за <b>${nameOf(targetId)}</b> учтён.")
        if (error == null) {
            deps.metrics.playerAction("vote")
            completeIfEveryoneReady()
        }
    }

    suspend fun onNightTarget(userId: Long, callbackQueryId: String, targetId: Long) {
        val player = players.firstOrNull { it.userId == userId }
        val action = player?.role?.nightAction
        val error = mutex.withLock {
            when {
                phase != GamePhase.NIGHT -> "Сейчас не ночь."
                player == null || !isAliveNow(userId) -> "Действие доступно только живым участникам."
                action == null -> "У вашей роли нет ночного действия."
                !isAliveNow(targetId) -> "Этот игрок уже вне игры."
                targetId == userId && !player.role.canTargetSelf -> "На себя это действие направить нельзя."
                else -> {
                    nightTargets[userId] = targetId
                    null
                }
            }
        }
        respond(
            userId,
            callbackQueryId,
            error,
            "${action?.title ?: "Действие"}: цель — <b>${nameOf(targetId)}</b>. Выбор принят."
        )
        if (error == null) {
            deps.metrics.playerAction("night_${action?.name?.lowercase()}")
            completeIfEveryoneReady()
        }
    }

    suspend fun onDayTarget(userId: Long, callbackQueryId: String, targetId: Long) {
        val player = players.firstOrNull { it.userId == userId }
        val action = player?.role?.dayAction
        val error = mutex.withLock {
            when {
                phase != GamePhase.DAY_DISCUSSION -> "Сейчас не время дневного действия."
                player == null || !isAliveNow(userId) -> "Действие доступно только живым участникам."
                action == null -> "У вашей роли нет дневного действия."
                !isAliveNow(targetId) -> "Этот игрок уже вне игры."
                else -> {
                    dayTargets[userId] = targetId
                    null
                }
            }
        }
        respond(
            userId,
            callbackQueryId,
            error,
            "${action?.title ?: "Действие"}: цель — <b>${nameOf(targetId)}</b>. Выбор принят."
        )
        if (error == null) {
            deps.metrics.playerAction("day_${action?.name?.lowercase()}")
            completeIfEveryoneReady()
        }
    }

    /**
     * Replaces the personal action panel with the personal result: the keyboard disappears and
     * only the acting user sees what has been recorded.
     */
    private suspend fun respond(userId: Long, callbackQueryId: String, error: String?, successText: String) {
        if (error != null) {
            deps.gateway.answerCallback(callbackQueryId, error, alert = true)
            return
        }
        deps.gateway.answerCallback(callbackQueryId)
        val panelId = mutex.withLock { panels[userId] }
        val replaced = panelId != null && deps.gateway.editGroupMessage(
            chatId = userId,
            messageId = panelId,
            text = successText
        )
        if (replaced) return
        val message = deps.gateway.sendPrivatePanel(
            userId = userId,
            text = successText
        )
        rememberPanel(userId, message?.messageId)
    }

    // endregion

    // region bot players

    private suspend fun botVote(bot: PlayerState) {
        delay(botThinkingTime(settings.dayVoteSeconds))
        val voted = mutex.withLock {
            val target = alive().filter { it.userId != bot.userId }.randomOrNull()
            val allowed = phase == GamePhase.DAY_VOTE &&
                isAliveNow(bot.userId) &&
                bot.userId !in silencedForVote &&
                target != null
            if (allowed) votes[bot.userId] = target.userId
            allowed
        }
        if (voted) {
            deps.metrics.playerAction("vote_bot")
            completeIfEveryoneReady()
        }
    }

    private suspend fun botNightTarget(bot: PlayerState) {
        val action = bot.role.nightAction ?: return
        delay(botThinkingTime(settings.nightSeconds))
        val acted = mutex.withLock {
            val target = alive()
                .filter { bot.role.canTargetSelf || it.userId != bot.userId }
                .randomOrNull()
            val allowed = phase == GamePhase.NIGHT && isAliveNow(bot.userId) && target != null
            if (allowed) nightTargets[bot.userId] = target.userId
            allowed
        }
        if (acted) {
            deps.metrics.playerAction("night_${action.name.lowercase()}_bot")
            completeIfEveryoneReady()
        }
    }

    private suspend fun botDayTarget(bot: PlayerState) {
        val action = bot.role.dayAction ?: return
        delay(botThinkingTime(settings.believerWindowSeconds))
        val acted = mutex.withLock {
            val target = alive()
                .filter { bot.role.canTargetSelf || it.userId != bot.userId }
                .randomOrNull()
            val allowed = phase == GamePhase.DAY_DISCUSSION && isAliveNow(bot.userId) && target != null
            if (allowed) dayTargets[bot.userId] = target.userId
            allowed
        }
        if (acted) {
            deps.metrics.playerAction("day_${action.name.lowercase()}_bot")
            completeIfEveryoneReady()
        }
    }

    /** Bots act somewhere inside the phase window, so their choice does not look instant. */
    private fun botThinkingTime(phaseSeconds: Int): Long {
        val upperBound = (phaseSeconds.seconds() - 1_000L).coerceAtLeast(2_000L)
        return Random.nextLong(1_000L, upperBound)
    }

    // endregion

    // region panels

    private suspend fun openVotePanels() {
        val snapshot = mutex.withLock { alive().map { it.userId to it.name } }
        val silenced = mutex.withLock { silencedForVote }
        snapshot.forEach { (userId, _) ->
            if (BotPlayers.isBot(userId)) return@forEach
            if (userId in silenced) {
                deps.gateway.sendPrivateMessage(
                    userId,
                    "🤐 Босс мафии лишил вас голоса: на этом голосовании вы не голосуете."
                )
                return@forEach
            }
            val targets = snapshot.filter { it.first != userId }
            val message = deps.gateway.sendPrivatePanel(
                userId = userId,
                text = "🗳 <b>Голосование дня $dayNumber</b>\nВыберите, кого казнить:",
                markup = Keyboards.targets(targets) { id ->
                    CallbackData.encode(CallbackData.Vote(gameId, id))
                }
            )
            rememberPanel(userId, message?.messageId)
        }
    }

    private suspend fun openNightPanels(actors: List<PlayerState>) {
        val snapshot = mutex.withLock { alive().map { it.userId to it.name } }
        actors.forEach { actor ->
            if (actor.isBot) return@forEach
            val action = actor.role.nightAction ?: return@forEach
            val targets = snapshot.filter { actor.role.canTargetSelf || it.first != actor.userId }
            val message = deps.gateway.sendPrivatePanel(
                userId = actor.userId,
                text = "🌙 <b>${action.title}</b>\n${action.prompt}:",
                markup = Keyboards.targets(targets) { id ->
                    CallbackData.encode(CallbackData.NightTarget(gameId, id))
                }
            )
            rememberPanel(actor.userId, message?.messageId)
        }
    }

    private suspend fun openDayActionPanels(actors: List<PlayerState>) {
        val snapshot = mutex.withLock { alive().map { it.userId to it.name } }
        actors.forEach { actor ->
            val action = actor.role.dayAction ?: return@forEach
            val targets = snapshot.filter { actor.role.canTargetSelf || it.first != actor.userId }
            val message = deps.gateway.sendPrivatePanel(
                userId = actor.userId,
                text = "🙏 <b>${action.title}</b>\n${action.prompt} (${settings.believerWindowSeconds} сек):",
                markup = Keyboards.targets(targets) { id ->
                    CallbackData.encode(CallbackData.DayTarget(gameId, id))
                }
            )
            rememberPanel(actor.userId, message?.messageId)
        }
    }

    private suspend fun rememberPanel(userId: Long, messageId: Int?) {
        if (messageId == null) return
        mutex.withLock { panels[userId] = messageId }
    }

    private suspend fun closePanels() {
        val snapshot = mutex.withLock { panels.toMap().also { panels.clear() } }
        snapshot.forEach { (userId, messageId) ->
            deps.gateway.deleteMessage(userId, messageId)
        }
    }

    // endregion

    // region helpers

    /**
     * Waits until everyone has acted or the phase time is over. [botActions] run in parallel and
     * are cancelled as soon as the phase ends.
     */
    private suspend fun awaitPhaseEnd(
        seconds: Int,
        botActions: List<suspend () -> Unit> = emptyList(),
        everyoneReady: () -> Boolean
    ) = coroutineScope {
        val botJobs = botActions.map { action -> launch { action() } }
        val completion = CompletableDeferred<Unit>()
        mutex.withLock {
            phaseCompletion = completion
            if (everyoneReady()) completion.complete(Unit)
        }
        withTimeoutOrNull(seconds.seconds()) { completion.await() }
        mutex.withLock { phaseCompletion = null }
        botJobs.forEach { it.cancel() }
    }

    private suspend fun completeIfEveryoneReady() {
        mutex.withLock {
            val ready = when (phase) {
                GamePhase.DAY_VOTE -> votes.size >= alive().count { it.userId !in silencedForVote }
                GamePhase.NIGHT -> nightTargets.size >= aliveWithNightAction().size
                GamePhase.DAY_DISCUSSION -> dayTargets.size >= aliveWithDayAction().size
                else -> false
            }
            if (ready) phaseCompletion?.complete(Unit)
        }
    }

    private suspend fun checkOutcome(executedByVote: Long?): GameOutcome? {
        val views = mutex.withLock { players.map { PlayerView(it.userId, it.name, it.role, it.alive) } }
        return deps.winConditionChecker.check(views, executedByVote)
    }

    private suspend fun announceStart() {
        deps.gateway.sendGroupMessage(
            chatId,
            buildString {
                appendLine("🎭 <b>Игра в мафию началась!</b>")
                appendLine("Участников: ${players.size}")
                appendLine()
                players.forEachIndexed { index, player ->
                    appendLine("${index + 1}. ${escapeHtml(player.name)}")
                }
                appendLine()
                appendLine("Роли отправлены в личные сообщения. Удачи!")
            }
        )
    }

    private suspend fun sendRolesToPlayers() {
        players.filterNot { it.isBot }.forEach { player ->
            val teammates = players.filter {
                it.userId != player.userId &&
                    deps.catalog.factionOf(it.role) == Faction.MAFIA &&
                    deps.catalog.factionOf(player.role) == Faction.MAFIA
            }
            val text = buildString {
                appendLine("🎭 <b>Ваша роль: ${player.role.title}</b>")
                appendLine("Команда: ${deps.catalog.factionOf(player.role).title}")
                appendLine()
                appendLine(player.role.description)
                if (teammates.isNotEmpty()) {
                    appendLine()
                    appendLine("Ваши напарники: " + teammates.joinToString { "${escapeHtml(it.name)} (${it.role.title})" })
                }
                appendLine()
                appendLine("Игра идёт в чате: ${escapeHtml(chatTitle ?: "группа")}")
            }
            if (!deps.gateway.sendPrivateMessage(player.userId, text)) {
                deps.gateway.sendGroupMessage(chatId, "⚠️ Не удалось отправить роль в личку @${player.name}. Напишите боту /start и попросите админа перезапустить игру.")
            }
        }
    }

    private suspend fun finish(outcome: GameOutcome) {
        mutex.withLock { phase = GamePhase.FINISHED }
        val duration = Duration.between(startedAt, Instant.now())
        deps.gateway.sendGroupMessage(
            chatId,
            buildString {
                appendLine("🏁 <b>Игра завершена</b>")
                appendLine("Победа: <b>${outcome.winner.title}</b>")
                appendLine()
                appendLine("<b>Роли игроков:</b>")
                players.forEach { player ->
                    val status = if (player.alive) "жив" else player.deathReason?.title ?: "погиб"
                    val mark = if (player.userId in outcome.winnerUserIds) "🏆" else "•"
                    appendLine("$mark ${escapeHtml(player.name)} — ${player.role.title} ($status)")
                }
                appendLine()
                appendLine("Дней в игре: $dayNumber, длительность: ${duration.toMinutes()} мин.")
            }
        )
        withContext(NonCancellable) {
            deps.history.finishGame(
                gameId = gameId,
                winner = outcome.winner,
                daysPlayed = dayNumber,
                players = players.map {
                    FinalPlayerState(
                        userId = it.userId,
                        survived = it.alive,
                        winner = it.userId in outcome.winnerUserIds,
                        deathDay = it.deathDay,
                        deathReason = it.deathReason
                    )
                }
            )
        }
        deps.metrics.gameFinished(outcome.winner, dayNumber, duration)
        log.info("Игра {} в чате {} завершена: {}", gameId, chatId, outcome.winner)
    }

    suspend fun onAborted(reason: String) {
        withContext(NonCancellable) {
            closePanels()
            deps.gateway.sendGroupMessage(
                chatId,
                "🛑 <b>Игра прервана</b>\nПричина: ${escapeHtml(reason)}\n\n" +
                    "<b>Роли игроков:</b>\n" +
                    players.joinToString("\n") { "• ${escapeHtml(it.name)} — ${it.role.title}" }
            )
            deps.history.abortGame(gameId)
        }
        deps.metrics.gameAborted(reason)
    }

    private fun alive(): List<PlayerState> = players.filter { it.alive }

    private fun aliveWithNightAction(): List<PlayerState> =
        alive().filter { it.role.nightAction != null }

    private fun aliveWithDayAction(): List<PlayerState> =
        alive().filter { it.role.dayAction != null }

    private fun isAliveNow(userId: Long): Boolean = players.any { it.userId == userId && it.alive }

    private fun nameOf(userId: Long): String =
        players.firstOrNull { it.userId == userId }?.name?.let(::escapeHtml) ?: "игрок"

    private fun alivePlayersBlock(): String = buildString {
        appendLine("<b>В игре:</b>")
        alive().forEachIndexed { index, player -> appendLine("${index + 1}. ${escapeHtml(player.name)}") }
    }

    private fun Int.seconds(): Long = this * 1000L

    // endregion
}
