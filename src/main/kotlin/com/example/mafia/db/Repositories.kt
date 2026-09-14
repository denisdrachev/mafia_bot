package com.example.mafia.db

import com.example.mafia.game.model.Role
import com.example.mafia.game.model.Winner
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

interface WinnerCountProjection {
    val winner: Winner?
    val gamesCount: Long
}

interface PlayerStatProjection {
    val userId: Long
    val displayName: String
    val gamesCount: Long
    val winsCount: Long
}

@Repository
interface GroupSettingsRepository : JpaRepository<GroupSettingsEntity, Long>

@Repository
interface GroupDisabledRoleRepository : JpaRepository<GroupDisabledRoleEntity, Long> {
    fun findAllByChatId(chatId: Long): List<GroupDisabledRoleEntity>
    fun existsByChatIdAndRole(chatId: Long, role: Role): Boolean
    fun deleteByChatIdAndRole(chatId: Long, role: Role)
}

@Repository
interface GameRepository : JpaRepository<GameEntity, Long> {

    fun countByChatIdAndStatus(chatId: Long, status: GameStatus): Long

    fun findAllByStatus(status: GameStatus): List<GameEntity>

    fun findTop5ByChatIdAndStatusOrderByFinishedAtDesc(chatId: Long, status: GameStatus): List<GameEntity>

    @Query(
        """
        select g.winner as winner, count(g) as gamesCount
        from GameEntity g
        where g.chatId = :chatId and g.status = com.example.mafia.db.GameStatus.FINISHED
        group by g.winner
        """
    )
    fun winnerDistribution(@Param("chatId") chatId: Long): List<WinnerCountProjection>

    @Query(
        """
        select avg(g.durationSeconds)
        from GameEntity g
        where g.chatId = :chatId and g.status = com.example.mafia.db.GameStatus.FINISHED
        """
    )
    fun averageDurationSeconds(@Param("chatId") chatId: Long): Double?
}

@Repository
interface GamePlayerRepository : JpaRepository<GamePlayerEntity, Long> {

    fun findAllByGameId(gameId: Long): List<GamePlayerEntity>

    @Query(
        """
        select p.userId as userId,
               max(p.displayName) as displayName,
               count(p) as gamesCount,
               sum(case when p.winner = true then 1L else 0L end) as winsCount
        from GamePlayerEntity p
        where p.chatId = :chatId
        group by p.userId
        order by sum(case when p.winner = true then 1L else 0L end) desc, count(p) desc
        """
    )
    fun topPlayers(@Param("chatId") chatId: Long, pageable: Pageable): List<PlayerStatProjection>
}

@Repository
interface GameEventRepository : JpaRepository<GameEventEntity, Long> {
    fun findAllByGameIdOrderByIdAsc(gameId: Long): List<GameEventEntity>
}
