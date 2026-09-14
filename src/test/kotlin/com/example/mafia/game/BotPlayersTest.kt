package com.example.mafia.game

import com.example.mafia.game.engine.RoleAssigner
import com.example.mafia.game.engine.RoleTable
import com.example.mafia.game.engine.RoleTableRule
import com.example.mafia.game.model.Role
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BotPlayersTest {

    @Test
    fun `bot ids never clash with human ids`() {
        val bots = BotPlayers.create(3)
        assertEquals(3, bots.size)
        assertEquals(3, bots.map { it.userId }.distinct().size)
        assertTrue(bots.all { BotPlayers.isBot(it.userId) })
        assertFalse(BotPlayers.isBot(1L))
        assertFalse(BotPlayers.isBot(-999_999_999L))
    }

    @Test
    fun `empty bot count produces no players`() {
        assertTrue(BotPlayers.create(0).isEmpty())
    }

    @Test
    fun `bots take part in the role deck like humans`() {
        val assigner = RoleAssigner(
            RoleTable(listOf(RoleTableRule(3, null, listOf(Role.MAFIA_KILLER, Role.DOCTOR, Role.COMMISSAR))))
        )
        val humans = listOf(1L, 2L)
        val bots = BotPlayers.create(3).map { it.userId }
        val roles = assigner.assign(humans + bots)
        assertEquals(5, roles.size)
        assertTrue(roles.containsKey(bots.first()))
        assertTrue(roles.values.contains(Role.MAFIA_KILLER))
    }
}
