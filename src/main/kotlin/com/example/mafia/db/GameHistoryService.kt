package com.example.mafia.db

import com.example.mafia.game.model.DeathReason
import com.example.mafia.game.model.Faction
import com.example.mafia.game.model.GamePhase
import com.example.mafia.game.model.PlayerRef
import com.example.mafia.game.model.Role
import com.example.mafia.game.model.Winner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

data class NewPlayerRecord(
    val ref: PlayerRef,
    val role: Role,
    val faction: Faction
)

data class FinalPlayerState(
    val userId: Long,
    val survived: Boolean,
    val winner: Boolean,
    val deathDay: Int?,
    val deathReason: DeathReason?
)

@Service
class GameHistoryService(
    private val gameRepository: GameRepository,
    private val playerRepository: GamePlayerRepository,
    private val eventRepository: GameEventRepository
) {

    /**
     * [playersCount] may exceed [players] size: bot players take part in the game but are not
     * persisted, so they are not mixed into player statistics.
     */
    suspend fun createGame(
        chatId: Long,
        chatTitle: String?,
        players: List<NewPlayerRecord>,
        playersCount: Int = players.size
    ): Long = withContext(Dispatchers.IO) { createGameBlocking(chatId, chatTitle, players, playersCount) }

    suspend fun recordEvent(gameId: Long, dayNumber: Int, phase: GamePhase, type: String, details: String?) {
        withContext(Dispatchers.IO) { recordEventBlocking(gameId, dayNumber, phase, type, details) }
    }

    suspend fun finishGame(gameId: Long, winner: Winner, daysPlayed: Int, players: List<FinalPlayerState>) {
        withContext(Dispatchers.IO) { finishGameBlocking(gameId, winner, daysPlayed, players) }
    }

    suspend fun abortGame(gameId: Long) {
        withContext(Dispatchers.IO) { abortGameBlocking(gameId) }
    }

    @Transactional
    fun createGameBlocking(
        chatId: Long,
        chatTitle: String?,
        players: List<NewPlayerRecord>,
        playersCount: Int = players.size
    ): Long {
        val game = gameRepository.save(
            GameEntity(
                chatId = chatId,
                chatTitle = chatTitle,
                status = GameStatus.RUNNING,
                playersCount = playersCount,
                startedAt = Instant.now()
            )
        )
        val gameId = requireNonNullId(game)
        players.forEach { record ->
            playerRepository.save(
                GamePlayerEntity(
                    gameId = gameId,
                    chatId = chatId,
                    userId = record.ref.userId,
                    username = record.ref.username,
                    displayName = record.ref.displayName,
                    role = record.role,
                    faction = record.faction
                )
            )
        }
        return gameId
    }

    @Transactional
    fun recordEventBlocking(gameId: Long, dayNumber: Int, phase: GamePhase, type: String, details: String?) {
        eventRepository.save(
            GameEventEntity(
                gameId = gameId,
                dayNumber = dayNumber,
                phase = phase,
                eventType = type,
                details = details?.take(1024)
            )
        )
    }

    @Transactional
    fun finishGameBlocking(gameId: Long, winner: Winner, daysPlayed: Int, players: List<FinalPlayerState>) {
        val game = gameRepository.findById(gameId).orElse(null) ?: return
        val finishedAt = Instant.now()
        game.status = GameStatus.FINISHED
        game.winner = winner
        game.daysPlayed = daysPlayed
        game.finishedAt = finishedAt
        game.durationSeconds = Duration.between(game.startedAt, finishedAt).seconds
        val byUser = players.associateBy { it.userId }
        playerRepository.findAllByGameId(gameId).forEach { entity ->
            val state = byUser[entity.userId] ?: return@forEach
            entity.survived = state.survived
            entity.winner = state.winner
            entity.deathDay = state.deathDay
            entity.deathReason = state.deathReason
        }
    }

    @Transactional
    fun abortGameBlocking(gameId: Long) {
        val game = gameRepository.findById(gameId).orElse(null) ?: return
        if (game.status != GameStatus.RUNNING) return
        game.status = GameStatus.ABORTED
        game.finishedAt = Instant.now()
        game.durationSeconds = Duration.between(game.startedAt, game.finishedAt).seconds
    }

    @Transactional
    fun abortAllRunningBlocking(): List<GameEntity> {
        val running = gameRepository.findAllByStatus(GameStatus.RUNNING)
        running.forEach { game ->
            game.status = GameStatus.ABORTED
            game.finishedAt = Instant.now()
            game.durationSeconds = Duration.between(game.startedAt, game.finishedAt).seconds
        }
        return running
    }

    private fun requireNonNullId(game: GameEntity): Long =
        game.id ?: error("Не удалось сохранить игру: пустой идентификатор")
}
