package com.example.mafia.game.engine

import com.example.mafia.game.model.VoteResolution

object VoteResolver {

    const val BLESSED_VOTE_WEIGHT = 2
    const val DEFAULT_VOTE_WEIGHT = 1

    /**
     * @param votes voter id -> target id
     * @param silenced voters whose vote is discarded (mafia boss effect)
     * @param alibi players who cannot be executed this vote (prostitute effect)
     * @param blessed voters whose vote counts twice (believer effect)
     */
    fun resolve(
        votes: Map<Long, Long>,
        silenced: Set<Long> = emptySet(),
        alibi: Set<Long> = emptySet(),
        blessed: Set<Long> = emptySet()
    ): VoteResolution {
        val tally = HashMap<Long, Int>()
        for ((voter, target) in votes) {
            if (voter in silenced) continue
            if (target in alibi) continue
            val weight = if (voter in blessed) BLESSED_VOTE_WEIGHT else DEFAULT_VOTE_WEIGHT
            tally[target] = (tally[target] ?: 0) + weight
        }
        val max = tally.values.maxOrNull()
        val leaders = tally.filterValues { it == max }.keys
        val tie = leaders.size > 1
        return VoteResolution(
            executed = if (max == null || tie) null else leaders.first(),
            tally = tally,
            ignoredVoters = votes.keys intersect silenced,
            protectedByAlibi = votes.values.toSet() intersect alibi,
            tie = tie
        )
    }
}
