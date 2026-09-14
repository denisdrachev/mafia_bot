package com.example.mafia.db

import com.example.mafia.game.model.DeathReason
import com.example.mafia.game.model.Faction
import com.example.mafia.game.model.GamePhase
import com.example.mafia.game.model.PlayerRef
import com.example.mafia.game.model.Role
import com.example.mafia.game.model.Winner
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that Flyway schema, JPA mappings and statistics queries agree with each other.
 */
@DataJpaTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:mafia-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
    ]
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class GamePersistenceTest {

    @Autowired
    private lateinit var gameRepository: GameRepository

    @Autowired
    private lateinit var playerRepository: GamePlayerRepository

    @Autowired
    private lateinit var eventRepository: GameEventRepository

    @Test
    fun `game with players and events is stored and aggregated`() {
        val history = GameHistoryService(gameRepository, playerRepository, eventRepository)
        val stats = StatsService(gameRepository, playerRepository)
        val chatId = -1001L

        val gameId = history.createGameBlocking(
            chatId = chatId,
            chatTitle = "Тестовая группа",
            players = listOf(
                NewPlayerRecord(PlayerRef(1, "one", "Игрок 1"), Role.MAFIA_KILLER, Faction.MAFIA),
                NewPlayerRecord(PlayerRef(2, "two", "Игрок 2"), Role.CIVILIAN, Faction.TOWN),
                NewPlayerRecord(PlayerRef(3, null, "Игрок 3"), Role.MASOCHIST, Faction.NEUTRAL)
            )
        )
        history.recordEventBlocking(gameId, 1, GamePhase.NIGHT, "NIGHT_RESOLVED", "deaths=[2]")
        history.finishGameBlocking(
            gameId = gameId,
            winner = Winner.MAFIA,
            daysPlayed = 3,
            players = listOf(
                FinalPlayerState(1, survived = true, winner = true, deathDay = null, deathReason = null),
                FinalPlayerState(2, survived = false, winner = false, deathDay = 1, deathReason = DeathReason.NIGHT_KILL),
                FinalPlayerState(
                    3,
                    survived = false,
                    winner = false,
                    deathDay = 2,
                    deathReason = DeathReason.DAY_EXECUTION
                )
            )
        )

        val groupStats = stats.groupStatsBlocking(chatId)
        assertEquals(1, groupStats.finishedGames)
        assertEquals(mapOf(Winner.MAFIA to 1L), groupStats.winnerDistribution)
        assertEquals(3, groupStats.recentGames.first().playersCount)
        assertEquals(3, groupStats.recentGames.first().daysPlayed)
        assertTrue(groupStats.topPlayers.any { it.userId == 1L && it.wins == 1L })
        assertEquals(1, eventRepository.findAllByGameIdOrderByIdAsc(gameId).size)
        assertEquals(3, playerRepository.findAllByGameId(gameId).size)
    }

    @Test
    fun `running games are aborted`() {
        val history = GameHistoryService(gameRepository, playerRepository, eventRepository)
        val gameId = history.createGameBlocking(
            chatId = -2002L,
            chatTitle = null,
            players = listOf(NewPlayerRecord(PlayerRef(9, null, "Игрок 9"), Role.CIVILIAN, Faction.TOWN))
        )
        val aborted = history.abortAllRunningBlocking()
        assertTrue(aborted.any { it.id == gameId })
        assertEquals(1, gameRepository.countByChatIdAndStatus(-2002L, GameStatus.ABORTED))
    }
}
