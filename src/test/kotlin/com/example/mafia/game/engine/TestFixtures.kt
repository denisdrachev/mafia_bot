package com.example.mafia.game.engine

import com.example.mafia.game.model.PlayerView
import com.example.mafia.game.model.Role
import com.example.mafia.game.model.RoleCatalog
import com.example.mafia.game.model.RoleDefinition

fun catalog(canKillOverrides: Map<Role, Boolean> = emptyMap()): RoleCatalog = RoleCatalog(
    Role.entries.associateWith { role ->
        RoleDefinition(
            role = role,
            faction = role.defaultFaction,
            canKill = canKillOverrides[role] ?: role.defaultCanKill
        )
    }
)

fun player(id: Long, role: Role, alive: Boolean = true): PlayerView =
    PlayerView(userId = id, displayName = "player$id", role = role, alive = alive)
