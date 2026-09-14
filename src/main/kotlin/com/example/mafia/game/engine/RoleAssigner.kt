package com.example.mafia.game.engine

import com.example.mafia.game.model.Role
import kotlin.random.Random

data class RoleTableRule(
    val minPlayers: Int,
    val maxPlayers: Int?,
    val roles: List<Role>
) {
    fun matches(playersCount: Int): Boolean =
        playersCount >= minPlayers && (maxPlayers == null || playersCount <= maxPlayers)
}

class RoleTable(rules: List<RoleTableRule>) {

    private val rules = rules.sortedBy { it.minPlayers }

    val minPlayers: Int = this.rules.minOfOrNull { it.minPlayers } ?: DEFAULT_MIN_PLAYERS

    fun rolesFor(playersCount: Int): List<Role> =
        rules.lastOrNull { it.matches(playersCount) }?.roles
            ?: rules.lastOrNull { playersCount >= it.minPlayers }?.roles
            ?: listOf(Role.MAFIA_KILLER)

    companion object {
        const val DEFAULT_MIN_PLAYERS = 3
    }
}

class RoleAssigner(
    private val table: RoleTable,
    private val random: Random = Random.Default
) {

    fun minPlayers(): Int = table.minPlayers

    /**
     * Builds the role deck for a given amount of players: special roles come from the configured
     * table, every remaining seat is filled with a civilian. Roles disabled for the group are
     * skipped unless they are mandatory.
     */
    fun buildDeck(playersCount: Int, disabledRoles: Set<Role> = emptySet()): List<Role> {
        require(playersCount >= table.minPlayers) {
            "Нужно минимум ${table.minPlayers} игроков, а есть $playersCount"
        }
        val configured = table.rolesFor(playersCount)
            .filter { it.mandatory || it !in disabledRoles }
        val deck = ArrayList<Role>(playersCount)
        val mandatory = configured.filter { it.mandatory }
        val optional = configured.filterNot { it.mandatory }
        deck += mandatory.take(playersCount)
        for (role in optional) {
            if (deck.size >= playersCount) break
            deck += role
        }
        while (deck.size < playersCount) {
            deck += Role.CIVILIAN
        }
        return deck
    }

    fun assign(playerIds: List<Long>, disabledRoles: Set<Role> = emptySet()): Map<Long, Role> {
        val deck = buildDeck(playerIds.size, disabledRoles).shuffled(random)
        return playerIds.zip(deck).toMap()
    }
}
