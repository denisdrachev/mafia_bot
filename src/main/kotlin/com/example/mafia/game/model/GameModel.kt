package com.example.mafia.game.model

enum class GamePhase(val title: String) {
    GATHERING("Сбор игроков"),
    DAY_DISCUSSION("Дневное обсуждение"),
    DAY_VOTE("Дневное голосование"),
    NIGHT("Ночь"),
    FINISHED("Игра завершена")
}

enum class Winner(val title: String) {
    TOWN("Мирные жители"),
    MAFIA("Мафия"),
    MANIAC("Маньяки"),
    MASOCHIST("Мазохист"),
    NOBODY("Никто")
}

enum class DeathReason(val title: String) {
    NIGHT_KILL("Убит ночью"),
    DAY_EXECUTION("Казнён днём")
}

data class PlayerRef(
    val userId: Long,
    val username: String?,
    val displayName: String
)

data class PlayerView(
    val userId: Long,
    val displayName: String,
    val role: Role,
    val alive: Boolean
)

data class GameOutcome(
    val winner: Winner,
    val winnerUserIds: Set<Long>
)

data class NightActionRequest(
    val actorId: Long,
    val role: Role,
    val action: NightAction,
    val targetId: Long
)

data class NightResolution(
    val deaths: Set<Long>,
    val healed: Set<Long>,
    val savedFromDeath: Set<Long>,
    val checkResults: Map<Long, CheckResult>,
    val silencedForNextVote: Set<Long>,
    val alibiForNextVote: Set<Long>
)

data class CheckResult(
    val targetId: Long,
    val canKill: Boolean
)

data class VoteResolution(
    val executed: Long?,
    val tally: Map<Long, Int>,
    val ignoredVoters: Set<Long>,
    val protectedByAlibi: Set<Long>,
    val tie: Boolean
)
