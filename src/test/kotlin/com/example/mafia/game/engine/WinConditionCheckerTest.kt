package com.example.mafia.game.engine

import com.example.mafia.game.model.Role
import com.example.mafia.game.model.Winner
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WinConditionCheckerTest {

    private val checker = WinConditionChecker(catalog())

    @Test
    fun `town wins when no mafia and no maniac left`() {
        val outcome = checker.check(
            listOf(
                player(1, Role.CIVILIAN),
                player(2, Role.DOCTOR),
                player(3, Role.MAFIA_KILLER, alive = false)
            )
        )
        assertEquals(Winner.TOWN, outcome?.winner)
        assertEquals(setOf(1L, 2L), outcome?.winnerUserIds)
    }

    @Test
    fun `mafia wins when town is not bigger and no neutrals left`() {
        val outcome = checker.check(
            listOf(
                player(1, Role.MAFIA_KILLER),
                player(2, Role.CIVILIAN),
                player(3, Role.CIVILIAN, alive = false),
                player(4, Role.MANIAC, alive = false)
            )
        )
        assertEquals(Winner.MAFIA, outcome?.winner)
        assertEquals(setOf(1L), outcome?.winnerUserIds)
    }

    @Test
    fun `alive neutral blocks mafia win`() {
        val outcome = checker.check(
            listOf(
                player(1, Role.MAFIA_KILLER),
                player(2, Role.CIVILIAN),
                player(3, Role.MASOCHIST)
            )
        )
        assertNull(outcome)
    }

    @Test
    fun `maniac wins when town is not bigger and mafia is dead`() {
        val outcome = checker.check(
            listOf(
                player(1, Role.MANIAC),
                player(2, Role.CIVILIAN),
                player(3, Role.MAFIA_KILLER, alive = false)
            )
        )
        assertEquals(Winner.MANIAC, outcome?.winner)
        assertEquals(setOf(1L), outcome?.winnerUserIds)
    }

    @Test
    fun `masochist wins immediately when executed by day vote`() {
        val outcome = checker.check(
            listOf(
                player(1, Role.MASOCHIST, alive = false),
                player(2, Role.MAFIA_KILLER),
                player(3, Role.CIVILIAN),
                player(4, Role.CIVILIAN)
            ),
            executedByVote = 1
        )
        assertEquals(Winner.MASOCHIST, outcome?.winner)
        assertEquals(setOf(1L), outcome?.winnerUserIds)
    }

    @Test
    fun `masochist killed at night does not win`() {
        val outcome = checker.check(
            listOf(
                player(1, Role.MASOCHIST, alive = false),
                player(2, Role.MAFIA_KILLER),
                player(3, Role.CIVILIAN),
                player(4, Role.CIVILIAN)
            )
        )
        assertNull(outcome)
    }

    @Test
    fun `game continues while both sides are alive`() {
        val outcome = checker.check(
            listOf(
                player(1, Role.MAFIA_KILLER),
                player(2, Role.CIVILIAN),
                player(3, Role.CIVILIAN),
                player(4, Role.DOCTOR)
            )
        )
        assertNull(outcome)
    }
}
