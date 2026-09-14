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

/**
 * Coroutine-friendly facade over [GroupSettingsStore]. The store is a separate bean on purpose:
 * calling a `@Transactional` method on `this` would bypass the Spring proxy and run without a
 * transaction, so entity changes would never be flushed.
 */
@Service
class GroupSettingsService(private val store: GroupSettingsStore) {

    suspend fun settings(chatId: Long, chatTitle: String? = null): GameSettings =
        withContext(Dispatchers.IO) { store.settings(chatId, chatTitle) }

    suspend fun update(chatId: Long, key: SettingKey, seconds: Int): GameSettings =
        withContext(Dispatchers.IO) { store.update(chatId, key, seconds) }

    suspend fun setRoleEnabled(chatId: Long, role: Role, enabled: Boolean): GameSettings =
        withContext(Dispatchers.IO) { store.setRoleEnabled(chatId, role, enabled) }

    suspend fun setBotCount(chatId: Long, count: Int): GameSettings =
        withContext(Dispatchers.IO) { store.setBotCount(chatId, count) }
}

@Service
class GroupSettingsStore(
    private val settingsRepository: GroupSettingsRepository,
    private val disabledRoleRepository: GroupDisabledRoleRepository,
    private val properties: MafiaProperties
) {

    @Transactional
    fun settings(chatId: Long, chatTitle: String? = null): GameSettings {
        val entity = findOrCreate(chatId, chatTitle)
        if (chatTitle != null && entity.chatTitle != chatTitle) {
            entity.chatTitle = chatTitle
            entity.updatedAt = Instant.now()
            settingsRepository.save(entity)
        }
        return entity.toSettings(disabledRoles(chatId))
    }

    @Transactional
    fun update(chatId: Long, key: SettingKey, seconds: Int): GameSettings {
        key.validate(seconds)
        val entity = findOrCreate(chatId)
        when (key) {
            SettingKey.GATHER -> entity.gatherSeconds = seconds
            SettingKey.DAY -> entity.dayDiscussionSeconds = seconds
            SettingKey.VOTE -> entity.dayVoteSeconds = seconds
            SettingKey.NIGHT -> entity.nightSeconds = seconds
        }
        entity.updatedAt = Instant.now()
        settingsRepository.save(entity)
        return entity.toSettings(disabledRoles(chatId))
    }

    @Transactional
    fun setBotCount(chatId: Long, count: Int): GameSettings {
        GameSettings.validateBotCount(count)
        val entity = findOrCreate(chatId)
        entity.botCount = count
        entity.updatedAt = Instant.now()
        settingsRepository.save(entity)
        return entity.toSettings(disabledRoles(chatId))
    }

    @Transactional
    fun setRoleEnabled(chatId: Long, role: Role, enabled: Boolean): GameSettings {
        require(!(role.mandatory && !enabled)) { "Роль «${role.title}» нельзя отключить" }
        val exists = disabledRoleRepository.existsByChatIdAndRole(chatId, role)
        if (enabled && exists) {
            disabledRoleRepository.deleteByChatIdAndRole(chatId, role)
        } else if (!enabled && !exists) {
            disabledRoleRepository.save(GroupDisabledRoleEntity(chatId = chatId, role = role))
        }
        return findOrCreate(chatId).toSettings(disabledRoles(chatId))
    }

    private fun findOrCreate(chatId: Long, chatTitle: String? = null): GroupSettingsEntity =
        settingsRepository.findById(chatId).orElseGet {
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
