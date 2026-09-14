package com.example.mafia.db

import com.example.mafia.game.model.Winner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

data class PlayerStat(
    val userId: Long,
    val displayName: String,
    val games: Long,
    val wins: Long
)

data class RecentGame(
    val winner: Winner?,
    val playersCount: Int,
    val daysPlayed: Int,
    val finishedAt: Instant?
)

data class GroupStats(
    val finishedGames: Long,
    val abortedGames: Long,
    val winnerDistribution: Map<Winner, Long>,
    val averageDurationSeconds: Long?,
    val topPlayers: List<PlayerStat>,
    val recentGames: List<RecentGame>
)

@Service
class StatsService(
    private val gameRepository: GameRepository,
    private val playerRepository: GamePlayerRepository
) {

    suspend fun groupStats(chatId: Long, topSize: Int = 5): GroupStats =
        withContext(Dispatchers.IO) { groupStatsBlocking(chatId, topSize) }

    @Transactional(readOnly = true)
    fun groupStatsBlocking(chatId: Long, topSize: Int = 5): GroupStats = GroupStats(
        finishedGames = gameRepository.countByChatIdAndStatus(chatId, GameStatus.FINISHED),
        abortedGames = gameRepository.countByChatIdAndStatus(chatId, GameStatus.ABORTED),
        winnerDistribution = gameRepository.winnerDistribution(chatId)
            .mapNotNull { row -> row.winner?.let { it to row.gamesCount } }
            .toMap(),
        averageDurationSeconds = gameRepository.averageDurationSeconds(chatId)?.toLong(),
        topPlayers = playerRepository.topPlayers(chatId, PageRequest.of(0, topSize))
            .map { PlayerStat(it.userId, it.displayName, it.gamesCount, it.winsCount) },
        recentGames = gameRepository.findTop5ByChatIdAndStatusOrderByFinishedAtDesc(chatId, GameStatus.FINISHED)
            .map { RecentGame(it.winner, it.playersCount, it.daysPlayed, it.finishedAt) }
    )
}
