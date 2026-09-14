package com.example.mafia.game.engine

import com.example.mafia.game.model.Faction
import com.example.mafia.game.model.GameOutcome
import com.example.mafia.game.model.PlayerView
import com.example.mafia.game.model.Role
import com.example.mafia.game.model.RoleCatalog
import com.example.mafia.game.model.Winner

class WinConditionChecker(private val catalog: RoleCatalog) {

    /**
     * @param players every player of the game, dead ones included
     * @param executedByVote player executed by the day vote in the current round, if any
     * @return outcome if the game is over, null otherwise
     */
    fun check(players: List<PlayerView>, executedByVote: Long? = null): GameOutcome? {
        val executed = executedByVote?.let { id -> players.firstOrNull { it.userId == id } }
        if (executed != null && executed.role == Role.MASOCHIST) {
            return GameOutcome(Winner.MASOCHIST, setOf(executed.userId))
        }

        val alive = players.filter { it.alive }
        val aliveMafia = alive.filter { catalog.factionOf(it.role) == Faction.MAFIA }
        val aliveNeutrals = alive.filter { catalog.factionOf(it.role) == Faction.NEUTRAL }
        val aliveManiacs = aliveNeutrals.filter { catalog.canKill(it.role) }
        val aliveTown = alive.filter { catalog.factionOf(it.role) == Faction.TOWN }

        if (aliveMafia.isEmpty() && aliveManiacs.isEmpty()) {
            return GameOutcome(Winner.TOWN, players.byFaction(Faction.TOWN))
        }
        if (aliveTown.size <= aliveMafia.size && aliveNeutrals.isEmpty()) {
            return GameOutcome(Winner.MAFIA, players.byFaction(Faction.MAFIA))
        }
        if (aliveTown.size <= aliveManiacs.size && aliveMafia.isEmpty()) {
            return GameOutcome(Winner.MANIAC, players.maniacs())
        }
        return null
    }

    private fun List<PlayerView>.byFaction(faction: Faction): Set<Long> =
        filter { catalog.factionOf(it.role) == faction }.map { it.userId }.toSet()

    private fun List<PlayerView>.maniacs(): Set<Long> =
        filter { catalog.factionOf(it.role) == Faction.NEUTRAL && catalog.canKill(it.role) }
            .map { it.userId }
            .toSet()
}
