package com.example.mafia.config

import com.example.mafia.game.engine.NightResolver
import com.example.mafia.game.engine.RoleAssigner
import com.example.mafia.game.engine.RoleTable
import com.example.mafia.game.engine.RoleTableRule
import com.example.mafia.game.engine.WinConditionChecker
import com.example.mafia.game.model.Role
import com.example.mafia.game.model.RoleCatalog
import com.example.mafia.game.model.RoleDefinition
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(MafiaProperties::class)
class GameConfig {

    @Bean
    fun roleCatalog(properties: MafiaProperties): RoleCatalog {
        val definitions = Role.entries.associateWith { role ->
            RoleDefinition(
                role = role,
                faction = properties.roles.factions[role] ?: role.defaultFaction,
                canKill = properties.roles.canKill[role] ?: role.defaultCanKill
            )
        }
        return RoleCatalog(definitions)
    }

    @Bean
    fun roleTable(properties: MafiaProperties): RoleTable {
        val rules = properties.roles.tables
            .takeIf { it.isNotEmpty() }
            ?: RolesProperties.defaultTables()
        return RoleTable(rules.map { RoleTableRule(it.minPlayers, it.maxPlayers, it.roles) })
    }

    @Bean
    fun roleAssigner(roleTable: RoleTable): RoleAssigner = RoleAssigner(roleTable)

    @Bean
    fun nightResolver(roleCatalog: RoleCatalog): NightResolver = NightResolver(roleCatalog)

    @Bean
    fun winConditionChecker(roleCatalog: RoleCatalog): WinConditionChecker = WinConditionChecker(roleCatalog)
}
