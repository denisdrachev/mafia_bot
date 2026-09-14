package com.example.mafia.settings

import com.example.mafia.game.model.Role

enum class SettingKey(
    val alias: String,
    val title: String,
    val min: Int,
    val max: Int
) {
    GATHER("gather", "Длительность сбора игроков", 10, 600),
    DAY("day", "Длительность дневного обсуждения", 10, 600),
    VOTE("vote", "Длительность дневного голосования", 10, 300),
    NIGHT("night", "Длительность ночного выбора", 10, 300);

    fun validate(seconds: Int) {
        require(seconds in min..max) { "«$title»: допустимо от $min до $max секунд" }
    }

    companion object {
        fun byAlias(alias: String): SettingKey? =
            entries.firstOrNull { it.alias.equals(alias, ignoreCase = true) || it.name.equals(alias, ignoreCase = true) }
    }
}

data class GameSettings(
    val gatherSeconds: Int,
    val dayDiscussionSeconds: Int,
    val dayVoteSeconds: Int,
    val nightSeconds: Int,
    val disabledRoles: Set<Role> = emptySet(),
    val botCount: Int = 0
) {
    init {
        SettingKey.GATHER.validate(gatherSeconds)
        SettingKey.DAY.validate(dayDiscussionSeconds)
        SettingKey.VOTE.validate(dayVoteSeconds)
        SettingKey.NIGHT.validate(nightSeconds)
        validateBotCount(botCount)
    }

    fun seconds(key: SettingKey): Int = when (key) {
        SettingKey.GATHER -> gatherSeconds
        SettingKey.DAY -> dayDiscussionSeconds
        SettingKey.VOTE -> dayVoteSeconds
        SettingKey.NIGHT -> nightSeconds
    }

    /** Window at the end of the day discussion when the believer can use its day action. */
    val believerWindowSeconds: Int get() = BELIEVER_WINDOW_SECONDS

    companion object {
        const val BELIEVER_WINDOW_SECONDS = 5
        const val MIN_BOTS = 0
        const val MAX_BOTS = 12

        fun validateBotCount(count: Int) {
            require(count in MIN_BOTS..MAX_BOTS) { "Количество ботов: допустимо от $MIN_BOTS до $MAX_BOTS" }
        }

        fun coerceBotCount(count: Int): Int = count.coerceIn(MIN_BOTS, MAX_BOTS)
    }
}
