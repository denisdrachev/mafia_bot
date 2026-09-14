package com.example.mafia.game.engine

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoteResolverTest {

    @Test
    fun `player with most votes is executed`() {
        val resolution = VoteResolver.resolve(votes = mapOf(1L to 3L, 2L to 3L, 3L to 1L))
        assertEquals(3L, resolution.executed)
        assertEquals(mapOf(3L to 2, 1L to 1), resolution.tally)
    }

    @Test
    fun `tie means nobody is executed`() {
        val resolution = VoteResolver.resolve(votes = mapOf(1L to 2L, 2L to 1L))
        assertNull(resolution.executed)
        assertTrue(resolution.tie)
    }

    @Test
    fun `silenced voter is ignored`() {
        val resolution = VoteResolver.resolve(
            votes = mapOf(1L to 3L, 2L to 3L, 3L to 1L),
            silenced = setOf(1L, 2L)
        )
        assertEquals(1L, resolution.executed)
        assertEquals(setOf(1L, 2L), resolution.ignoredVoters)
    }

    @Test
    fun `player with alibi cannot be executed`() {
        val resolution = VoteResolver.resolve(
            votes = mapOf(1L to 3L, 2L to 3L, 3L to 1L),
            alibi = setOf(3L)
        )
        assertEquals(1L, resolution.executed)
        assertEquals(setOf(3L), resolution.protectedByAlibi)
    }

    @Test
    fun `blessed vote counts twice`() {
        val resolution = VoteResolver.resolve(
            votes = mapOf(1L to 2L, 2L to 3L, 3L to 2L, 4L to 3L),
            blessed = setOf(2L)
        )
        assertEquals(3L, resolution.executed)
        assertEquals(mapOf(2L to 2, 3L to 3), resolution.tally)
    }

    @Test
    fun `no votes means no execution`() {
        val resolution = VoteResolver.resolve(votes = emptyMap())
        assertNull(resolution.executed)
        assertTrue(resolution.tally.isEmpty())
    }
}
