package com.example.mafia.settings

import com.example.mafia.config.MafiaProperties
import com.example.mafia.db.GroupSettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertEquals

/**
 * The settings must survive the transaction they were changed in: the bot count is read again
 * in a brand new transaction when a lobby is opened.
 */
@DataJpaTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:mafia-settings-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "mafia.defaults.gather-seconds=120",
        "mafia.defaults.day-discussion-seconds=90",
        "mafia.defaults.day-vote-seconds=60",
        "mafia.defaults.night-seconds=45",
        "mafia.defaults.bot-count=0"
    ]
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(GroupSettingsStore::class, GroupSettingsService::class)
@EnableConfigurationProperties(MafiaProperties::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class GroupSettingsPersistenceTest {

    @Autowired
    private lateinit var service: GroupSettingsService

    @Autowired
    private lateinit var repository: GroupSettingsRepository

    @Test
    fun `bot count survives the transaction it was set in`() = runBlocking {
        val chatId = -2001L

        service.setBotCount(chatId, 4)

        assertEquals(4, repository.findById(chatId).orElseThrow().botCount)
        assertEquals(4, service.settings(chatId).botCount)
    }

    @Test
    fun `timings survive the transaction they were set in`() = runBlocking {
        val chatId = -2002L

        service.update(chatId, SettingKey.GATHER, 90)

        assertEquals(90, repository.findById(chatId).orElseThrow().gatherSeconds)
        assertEquals(90, service.settings(chatId).gatherSeconds)
    }
}
