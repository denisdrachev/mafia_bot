package com.example.mafia.game.engine

import com.example.mafia.game.model.CheckResult
import com.example.mafia.game.model.NightAction
import com.example.mafia.game.model.NightActionRequest
import com.example.mafia.game.model.NightResolution
import com.example.mafia.game.model.Role
import com.example.mafia.game.model.RoleCatalog

class NightResolver(private val catalog: RoleCatalog) {

    /**
     * Resolves all collected night actions at once. Several shooters aiming at the same player
     * produce a single death; a healed player survives every shot of that night.
     */
    fun resolve(actions: Collection<NightActionRequest>, roleOf: (Long) -> Role?): NightResolution {
        val healed = actions.filter { it.action == NightAction.HEAL }.map { it.targetId }.toSet()
        val shots = actions.filter { it.action == NightAction.KILL }.map { it.targetId }.toSet()
        val deaths = shots - healed
        val checks = actions.filter { it.action == NightAction.CHECK }.associate { action ->
            val targetRole = roleOf(action.targetId)
            action.actorId to CheckResult(
                targetId = action.targetId,
                canKill = targetRole != null && catalog.canKill(targetRole)
            )
        }
        return NightResolution(
            deaths = deaths,
            healed = healed,
            savedFromDeath = shots intersect healed,
            checkResults = checks,
            silencedForNextVote = actions.filter { it.action == NightAction.SILENCE }.map { it.targetId }.toSet(),
            alibiForNextVote = actions.filter { it.action == NightAction.ALIBI }.map { it.targetId }.toSet()
        )
    }
}
