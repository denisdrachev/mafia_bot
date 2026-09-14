package com.example.mafia.settings

import com.example.mafia.config.MafiaProperties
import com.example.mafia.db.GroupDisabledRoleEntity
import com.example.mafia.db.GroupDisabledRoleRepository
import com.example.mafia.db.GroupSettingsEntity
import com.example.mafia.db.GroupSettingsRepository
import com.example.mafia.game.model.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class GroupSettingsService(
    private val settingsRepository: GroupSettingsRepository,
    private val disabledRoleRepository: GroupDisabledRoleRepository,
    private val properties: MafiaProperties
) {

    suspend fun settings(chatId: Long, chatTitle: String? = null): GameSettings =
        withContext(Dispatchers.IO) { settingsBlocking(chatId, chatTitle) }

    suspend fun update(chatId: Long, key: SettingKey, seconds: Int): GameSettings =
        withContext(Dispatchers.IO) { updateBlocking(chatId, key, seconds) }

    suspend fun setRoleEnabled(chatId: Long, role: Role, enabled: Boolean): GameSettings =
        withContext(Dispatchers.IO) { setRoleEnabledBlocking(chatId, role, enabled) }

    suspend fun setBotCount(chatId: Long, count: Int): GameSettings =
        withContext(Dispatchers.IO) { setBotCountBlocking(chatId, count) }

    suspend fun changeBotCount(chatId: Long, delta: Int): GameSettings =
        withContext(Dispatchers.IO) {
            val current = settingsBlocking(chatId).botCount
            setBotCountBlocking(chatId, GameSettings.coerceBotCount(current + delta))
        }

    @Transactional
    fun settingsBlocking(chatId: Long, chatTitle: String? = null): GameSettings {
        val entity = settingsRepository.findById(chatId).orElseGet {
            settingsRepository.save(
                GroupSettingsEntity(
                    chatId = chatId,
                    chatTitle = chatTitle,
                    gatherSeconds = properties.defaults.gatherSeconds,
                    dayDiscussionSeconds = properties.defaults.dayDiscussionSeconds,
                    dayVoteSeconds = properties.defaults.dayVoteSeconds,
                    nightSeconds = properties.defaults.nightSeconds,
                    botCount = GameSettings.coerceBotCount(properties.defaults.botCount)
                )
            )
        }
        if (chatTitle != null && entity.chatTitle != chatTitle) {
            entity.chatTitle = chatTitle
            entity.updatedAt = Instant.now()
        }
        return entity.toSettings(disabledRoles(chatId))
    }

    @Transactional
    fun updateBlocking(chatId: Long, key: SettingKey, seconds: Int): GameSettings {
        key.validate(seconds)
        settingsBlocking(chatId)
        val entity = settingsRepository.findById(chatId).orElseThrow()
        when (key) {
            SettingKey.GATHER -> entity.gatherSeconds = seconds
            SettingKey.DAY -> entity.dayDiscussionSeconds = seconds
            SettingKey.VOTE -> entity.dayVoteSeconds = seconds
            SettingKey.NIGHT -> entity.nightSeconds = seconds
        }
        entity.updatedAt = Instant.now()
        return entity.toSettings(disabledRoles(chatId))
    }

    @Transactional
    fun setBotCountBlocking(chatId: Long, count: Int): GameSettings {
        GameSettings.validateBotCount(count)
        settingsBlocking(chatId)
        val entity = settingsRepository.findById(chatId).orElseThrow()
        entity.botCount = count
        entity.updatedAt = Instant.now()
        return entity.toSettings(disabledRoles(chatId))
    }

    @Transactional
    fun setRoleEnabledBlocking(chatId: Long, role: Role, enabled: Boolean): GameSettings {
        require(!(role.mandatory && !enabled)) { "Роль «${role.title}» нельзя отключить" }
        val exists = disabledRoleRepository.existsByChatIdAndRole(chatId, role)
        if (enabled && exists) {
            disabledRoleRepository.deleteByChatIdAndRole(chatId, role)
        } else if (!enabled && !exists) {
            disabledRoleRepository.save(GroupDisabledRoleEntity(chatId = chatId, role = role))
        }
        return settingsBlocking(chatId)
    }

    private fun disabledRoles(chatId: Long): Set<Role> =
        disabledRoleRepository.findAllByChatId(chatId).map { it.role }.toSet()

    private fun GroupSettingsEntity.toSettings(disabled: Set<Role>) = GameSettings(
        gatherSeconds = gatherSeconds,
        dayDiscussionSeconds = dayDiscussionSeconds,
        dayVoteSeconds = dayVoteSeconds,
        nightSeconds = nightSeconds,
        disabledRoles = disabled,
        botCount = GameSettings.coerceBotCount(botCount)
    )
}
