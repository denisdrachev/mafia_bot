package com.example.mafia.settings

import com.example.mafia.telegram.CallbackData
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GameSettingsTest {

    @Test
    fun `day discussion must be longer than the believer window`() {
        assertEquals(true, SettingKey.DAY.min > GameSettings.BELIEVER_WINDOW_SECONDS)
    }

    @Test
    fun `out of range values are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            GameSettings(gatherSeconds = 5, dayDiscussionSeconds = 90, dayVoteSeconds = 60, nightSeconds = 45)
        }
        assertFailsWith<IllegalArgumentException> {
            GameSettings(gatherSeconds = 120, dayDiscussionSeconds = 10, dayVoteSeconds = 60, nightSeconds = 45)
        }
    }

    @Test
    fun `settings are readable by key`() {
        val settings = GameSettings(120, 90, 60, 45)
        assertEquals(120, settings.seconds(SettingKey.GATHER))
        assertEquals(90, settings.seconds(SettingKey.DAY))
        assertEquals(60, settings.seconds(SettingKey.VOTE))
        assertEquals(45, settings.seconds(SettingKey.NIGHT))
    }

    @Test
    fun `aliases are resolved`() {
        assertEquals(SettingKey.VOTE, SettingKey.byAlias("vote"))
        assertEquals(SettingKey.NIGHT, SettingKey.byAlias("NIGHT"))
        assertEquals(null, SettingKey.byAlias("unknown"))
    }

    @Test
    fun `bot count is validated and coerced`() {
        assertFailsWith<IllegalArgumentException> {
            GameSettings(120, 90, 60, 45, botCount = GameSettings.MAX_BOTS + 1)
        }
        assertFailsWith<IllegalArgumentException> {
            GameSettings(120, 90, 60, 45, botCount = -1)
        }
        assertEquals(GameSettings.MAX_BOTS, GameSettings.coerceBotCount(GameSettings.MAX_BOTS + 5))
        assertEquals(GameSettings.MIN_BOTS, GameSettings.coerceBotCount(-3))
        assertEquals(0, GameSettings(120, 90, 60, 45).botCount)
    }

    @Test
    fun `callback data survives encoding`() {
        val data = CallbackData.Vote(gameId = 7, targetId = 42)
        assertEquals(data, CallbackData.decode(CallbackData.encode(data)))
        val night = CallbackData.NightTarget(gameId = 7, targetId = -42)
        assertEquals(night, CallbackData.decode(CallbackData.encode(night)))
        assertEquals(null, CallbackData.decode("garbage"))
        val bots = CallbackData.SettingsBots(chatId = -1001, delta = -1)
        assertEquals(bots, CallbackData.decode(CallbackData.encode(bots)))
    }
}
