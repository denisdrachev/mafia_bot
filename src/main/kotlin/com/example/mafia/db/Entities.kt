package com.example.mafia.db

import com.example.mafia.game.model.DeathReason
import com.example.mafia.game.model.Faction
import com.example.mafia.game.model.GamePhase
import com.example.mafia.game.model.Role
import com.example.mafia.game.model.Winner
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

enum class GameStatus { RUNNING, FINISHED, ABORTED }

@Entity
@Table(name = "group_settings")
class GroupSettingsEntity(
    @Id
    @Column(name = "chat_id")
    var chatId: Long = 0,

    @Column(name = "chat_title")
    var chatTitle: String? = null,

    @Column(name = "gather_seconds", nullable = false)
    var gatherSeconds: Int = 120,

    @Column(name = "day_discussion_seconds", nullable = false)
    var dayDiscussionSeconds: Int = 90,

    @Column(name = "day_vote_seconds", nullable = false)
    var dayVoteSeconds: Int = 60,

    @Column(name = "night_seconds", nullable = false)
    var nightSeconds: Int = 45,

    @Column(name = "bot_count", nullable = false)
    var botCount: Int = 0,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
)

@Entity
@Table(name = "group_disabled_role")
class GroupDisabledRoleEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "chat_id", nullable = false)
    var chatId: Long = 0,

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 32)
    var role: Role = Role.CIVILIAN
)

@Entity
@Table(name = "game")
class GameEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "chat_id", nullable = false)
    var chatId: Long = 0,

    @Column(name = "chat_title")
    var chatTitle: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: GameStatus = GameStatus.RUNNING,

    @Enumerated(EnumType.STRING)
    @Column(name = "winner", length = 16)
    var winner: Winner? = null,

    @Column(name = "players_count", nullable = false)
    var playersCount: Int = 0,

    @Column(name = "days_played", nullable = false)
    var daysPlayed: Int = 0,

    @Column(name = "started_at", nullable = false)
    var startedAt: Instant = Instant.now(),

    @Column(name = "finished_at")
    var finishedAt: Instant? = null,

    @Column(name = "duration_seconds")
    var durationSeconds: Long? = null
)

@Entity
@Table(name = "game_player")
class GamePlayerEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "game_id", nullable = false)
    var gameId: Long = 0,

    @Column(name = "chat_id", nullable = false)
    var chatId: Long = 0,

    @Column(name = "user_id", nullable = false)
    var userId: Long = 0,

    @Column(name = "username")
    var username: String? = null,

    @Column(name = "display_name", nullable = false)
    var displayName: String = "",

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 32)
    var role: Role = Role.CIVILIAN,

    @Enumerated(EnumType.STRING)
    @Column(name = "faction", nullable = false, length = 16)
    var faction: Faction = Faction.TOWN,

    @Column(name = "survived", nullable = false)
    var survived: Boolean = true,

    @Column(name = "winner", nullable = false)
    var winner: Boolean = false,

    @Column(name = "death_day")
    var deathDay: Int? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "death_reason", length = 32)
    var deathReason: DeathReason? = null
)

@Entity
@Table(name = "game_event")
class GameEventEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "game_id", nullable = false)
    var gameId: Long = 0,

    @Column(name = "day_number", nullable = false)
    var dayNumber: Int = 0,

    @Enumerated(EnumType.STRING)
    @Column(name = "phase", nullable = false, length = 32)
    var phase: GamePhase = GamePhase.DAY_DISCUSSION,

    @Column(name = "event_type", nullable = false, length = 64)
    var eventType: String = "",

    @Column(name = "details", length = 1024)
    var details: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
)
