package com.example.mafia.game.engine

import com.example.mafia.config.RolesProperties
import com.example.mafia.game.model.Role
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoleAssignerTest {

    private val table = RoleTable(
        RolesProperties.defaultTables().map { RoleTableRule(it.minPlayers, it.maxPlayers, it.roles) }
    )
    private val assigner = RoleAssigner(table, Random(42))

    @Test
    fun `minimal game has mafia masochist and a civilian`() {
        val deck = assigner.buildDeck(3)
        assertEquals(3, deck.size)
        assertTrue(Role.MAFIA_KILLER in deck)
        assertTrue(Role.MASOCHIST in deck)
        assertTrue(Role.CIVILIAN in deck)
    }

    @Test
    fun `too few players are rejected`() {
        assertFailsWith<IllegalArgumentException> { assigner.buildDeck(2) }
        assertEquals(3, assigner.minPlayers())
    }

    @Test
    fun `extra seats are filled with civilians`() {
        val deck = assigner.buildDeck(6)
        assertEquals(6, deck.size)
        assertEquals(listOf(Role.MAFIA_KILLER, Role.DOCTOR, Role.COMMISSAR), deck.take(3))
        assertEquals(3, deck.count { it == Role.CIVILIAN })
    }

    @Test
    fun `big game gets the full role set`() {
        val deck = assigner.buildDeck(12)
        assertEquals(12, deck.size)
        assertTrue(listOf(Role.MANIAC, Role.MASOCHIST, Role.BELIEVER, Role.MAFIA_BOSS, Role.PROSTITUTE).all { it in deck })
        assertEquals(4, deck.count { it == Role.CIVILIAN })
    }

    @Test
    fun `disabled role is replaced by a civilian`() {
        val deck = assigner.buildDeck(12, disabledRoles = setOf(Role.MANIAC, Role.BELIEVER))
        assertFalse(Role.MANIAC in deck)
        assertFalse(Role.BELIEVER in deck)
        assertEquals(12, deck.size)
    }

    @Test
    fun `mandatory role cannot be disabled`() {
        val deck = assigner.buildDeck(5, disabledRoles = setOf(Role.MAFIA_KILLER))
        assertTrue(Role.MAFIA_KILLER in deck)
    }

    @Test
    fun `every player gets exactly one role`() {
        val ids = (1L..8L).toList()
        val assignment = assigner.assign(ids)
        assertEquals(ids.toSet(), assignment.keys)
        assertEquals(8, assignment.size)
    }
}
