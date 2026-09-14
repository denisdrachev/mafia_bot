package com.example.mafia.config

import com.example.mafia.game.model.Faction
import com.example.mafia.game.model.Role
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "mafia")
data class MafiaProperties(
    val bot: BotProperties = BotProperties(),
    val defaults: DefaultsProperties = DefaultsProperties(),
    val roles: RolesProperties = RolesProperties()
)

data class BotProperties(
    val token: String = "",
    val username: String = ""
)

data class DefaultsProperties(
    val gatherSeconds: Int = 120,
    val dayDiscussionSeconds: Int = 90,
    val dayVoteSeconds: Int = 60,
    val nightSeconds: Int = 45,
    /** How many bot players are added to every new game by default. */
    val botCount: Int = 0
)

data class RolesProperties(
    /** Player count -> role set table, configurable without code changes. */
    val tables: List<RoleTableProperties> = defaultTables(),
    /** Allows to flip the "can kill" flag used by the commissar check. */
    val canKill: Map<Role, Boolean> = emptyMap(),
    /** Allows to move a role to another faction without code changes. */
    val factions: Map<Role, Faction> = emptyMap()
) {
    companion object {
        fun defaultTables(): List<RoleTableProperties> = listOf(
            RoleTableProperties(
                minPlayers = 3,
                maxPlayers = 3,
                roles = listOf(Role.MAFIA_KILLER, Role.MASOCHIST)
            ),
            RoleTableProperties(
                minPlayers = 4,
                maxPlayers = 6,
                roles = listOf(Role.MAFIA_KILLER, Role.DOCTOR, Role.COMMISSAR)
            ),
            RoleTableProperties(
                minPlayers = 7,
                maxPlayers = 9,
                roles = listOf(
                    Role.MAFIA_KILLER,
                    Role.MAFIA_BOSS,
                    Role.DOCTOR,
                    Role.COMMISSAR,
                    Role.PROSTITUTE
                )
            ),
            RoleTableProperties(
                minPlayers = 10,
                maxPlayers = null,
                roles = listOf(
                    Role.MAFIA_KILLER,
                    Role.MAFIA_BOSS,
                    Role.DOCTOR,
                    Role.COMMISSAR,
                    Role.PROSTITUTE,
                    Role.MANIAC,
                    Role.MASOCHIST,
                    Role.BELIEVER
                )
            )
        )
    }
}

data class RoleTableProperties(
    val minPlayers: Int,
    val maxPlayers: Int? = null,
    val roles: List<Role> = emptyList()
)
