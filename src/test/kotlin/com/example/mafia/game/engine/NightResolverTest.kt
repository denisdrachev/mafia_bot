package com.example.mafia.game.engine

import com.example.mafia.game.model.NightAction
import com.example.mafia.game.model.NightActionRequest
import com.example.mafia.game.model.Role
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NightResolverTest {

    private val roles = mapOf(
        1L to Role.MAFIA_KILLER,
        2L to Role.DOCTOR,
        3L to Role.COMMISSAR,
        4L to Role.CIVILIAN,
        5L to Role.MANIAC,
        6L to Role.MAFIA_BOSS,
        7L to Role.PROSTITUTE
    )
    private val resolver = NightResolver(catalog())

    private fun roleOf(id: Long): Role? = roles[id]

    @Test
    fun `heal cancels the shot`() {
        val resolution = resolver.resolve(
            listOf(
                NightActionRequest(1, Role.MAFIA_KILLER, NightAction.KILL, 4),
                NightActionRequest(2, Role.DOCTOR, NightAction.HEAL, 4)
            ),
            ::roleOf
        )
        assertTrue(resolution.deaths.isEmpty())
        assertEquals(setOf(4L), resolution.savedFromDeath)
    }

    @Test
    fun `two shooters on the same target produce a single death`() {
        val resolution = resolver.resolve(
            listOf(
                NightActionRequest(1, Role.MAFIA_KILLER, NightAction.KILL, 4),
                NightActionRequest(5, Role.MANIAC, NightAction.KILL, 4)
            ),
            ::roleOf
        )
        assertEquals(setOf(4L), resolution.deaths)
    }

    @Test
    fun `heal blocks both shots at the same target`() {
        val resolution = resolver.resolve(
            listOf(
                NightActionRequest(1, Role.MAFIA_KILLER, NightAction.KILL, 4),
                NightActionRequest(5, Role.MANIAC, NightAction.KILL, 4),
                NightActionRequest(2, Role.DOCTOR, NightAction.HEAL, 4)
            ),
            ::roleOf
        )
        assertTrue(resolution.deaths.isEmpty())
    }

    @Test
    fun `commissar check reports killing ability`() {
        val resolution = resolver.resolve(
            listOf(
                NightActionRequest(3, Role.COMMISSAR, NightAction.CHECK, 1),
            ),
            ::roleOf
        )
        assertTrue(resolution.checkResults.getValue(3).canKill)

        val bossCheck = resolver.resolve(
            listOf(NightActionRequest(3, Role.COMMISSAR, NightAction.CHECK, 6)),
            ::roleOf
        )
        assertFalse(bossCheck.checkResults.getValue(3).canKill)
    }

    @Test
    fun `commissar can kill when configured`() {
        val configured = NightResolver(catalog(mapOf(Role.COMMISSAR to true)))
        val resolution = configured.resolve(
            listOf(NightActionRequest(1, Role.MAFIA_KILLER, NightAction.CHECK, 3)),
            ::roleOf
        )
        assertTrue(resolution.checkResults.getValue(1).canKill)
    }

    @Test
    fun `silence and alibi are collected for the next vote`() {
        val resolution = resolver.resolve(
            listOf(
                NightActionRequest(6, Role.MAFIA_BOSS, NightAction.SILENCE, 4),
                NightActionRequest(7, Role.PROSTITUTE, NightAction.ALIBI, 3)
            ),
            ::roleOf
        )
        assertEquals(setOf(4L), resolution.silencedForNextVote)
        assertEquals(setOf(3L), resolution.alibiForNextVote)
    }

    @Test
    fun `missing choice means skipped action`() {
        val resolution = resolver.resolve(emptyList(), ::roleOf)
        assertTrue(resolution.deaths.isEmpty())
        assertTrue(resolution.checkResults.isEmpty())
    }
}
