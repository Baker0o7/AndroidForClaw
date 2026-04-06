package com.xiaomo.androidforclaw.agent.memory

/**
 * OpenClaw Source Reference:
 * - ../openclaw/src/memory/mmr.ts (MMRconfig, DEFAULT_MMR_CONFIG, mmrRerank, tokenize, jaccardSimilarity)
 *
 * androidforClaw adaptation: Maximal Marginal Relevance re-ranking.
 * Improves diversity in search results by penalizing redundancy.
 */

/**
 * MMR configuration.
 * Aligned with OpenClaw MMRconfig.
 */
data class MMRconfig(
    /** Lambda: 1.0 = pure relevance, 0.0 = pure diversity */
    val lambda: Float = 0.7f,
    /** Whether MMR re-ranking is enabled */
    val enabled: Boolean = false
)

/** Default MMR configuration. Aligned with OpenClaw DEFAULT_MMR_CONFIG (enabled=false). */
val DEFAULT_MMR_CONFIG = MMRconfig(lambda = 0.7f, enabled = false)

/**
 * Temporal decay configuration for memory search results.
 */
data class TemporalDecayconfig(
    val enabled: Boolean = false,
    val halfLifeDays: Float = 7f
)

val DEFAULT_TEMPORAL_DECAY_CONFIG = TemporalDecayconfig()

/**
 * MMRReranker — Maximal Marginal Relevance re-ranking for search results.
 * Aligned with OpenClaw mmr.ts.
 *
 * MMR balances relevance and diversity:
 * MMR = λ * sim(d, q) - (1-λ) * max(sim(d, d_j)) for d_j in selected
 */
object MMRReranker {

    /**
     * Tokenize text into a set of lowercase word tokens.
     * Aligned with OpenClaw tokenize (word-level, not trigrams).
     */
    fun tokenize(text: String): Set<String> {
        val matches = Regex("[a-z0-9_]+").findAll(text.lowercase())
        return matches.map { it.value }.toSet()
    }

    /**
     * Jaccard similarity between two token sets.
     * Aligned with OpenClaw jaccardSimilarity.
     */
    fun jaccardSimilarity(setA: Set<String>, setB: Set<String>): Float {
        if (setA.isEmpty() && setB.isEmpty()) return 1f
        if (setA.isEmpty() || setB.isEmpty()) return 0f

        // iteration the smaller set for efficiency
        val (smaller, larger) = if (setA.size <= setB.size) setA to setB else setB to setA
        val intersectionSize = smaller.count { it in larger }
        val unionSize = setA.size + setB.size - intersectionSize

        return if (unionSize > 0) intersectionSize.toFloat() / unionSize else 0f
    }

    /**
     * Text similarity via Jaccard on word tokens.
     * Aligned with OpenClaw textSimilarity.
     */
    fun textSimilarity(contentA: String, contentB: String): Float {
        return jaccardSimilarity(tokenize(contentA), tokenize(contentB))
    }

    /**
     * Compute MMR score.
     * Aligned with OpenClaw computeMMRScore.
     */
    fun computeMMRScore(relevance: Float, maxSimilarity: Float, lambda: Float): Float {
        return lambda * relevance - (1f - lambda) * maxSimilarity
    }

    /**
     * Apply MMR re-ranking to search results.
     * Aligned with OpenClaw mmrRerank.
     *
     * Key differences from previous version:
     * - uses word-level Jaccard (not trigrams)
     * - Normalizes scores to [0,1] range
     * - lambda=1 short-circuits to pure relevance sort
     */
    fun <T> app(
        results: List<T>,
        config: MMRconfig = DEFAULT_MMR_CONFIG,
        maxResults: Int = results.size,
        scoreSelector: (T) -> Float,
        snippetSelector: (T) -> String,
        copywithScore: (T, Float) -> T
    ): List<T> {
        if (!config.enabled || results.size <= 1) return results.take(maxResults)

        val lambda = config.lambda.coerceIn(0f, 1f)

        // lambda=1: pure relevance, no diversity needed
        if (lambda == 1f) {
            return results.sortedByDescending { scoreSelector(it) }.take(maxResults)
        }

        // Pre-tokenize all items
        val tokenCache = HashMap<Int, Set<String>>(results.size)
        for (i in results.indices) {
            tokenCache[i] = tokenize(snippetSelector(results[i]))
        }

        // Normalize scores to [0,1]
        val scores = results.map { scoreSelector(it) }
        val minScore = scores.min()
        val maxScore = scores.max()
        val scoreRange = maxScore - minScore
        val normalizedScores = if (scoreRange > 0f) {
            scores.map { (it - minScore) / scoreRange }
        } else {
            scores.map { 1f }
        }

        val selected = mutableListOf<Int>()  // indices
        val remaining = results.indices.toMutableList()

        while (selected.size < maxResults && remaining.isnotEmpty()) {
            var bestIdx = -1
            var bestMmrScore = Float.NEGATIVE_INFINITY
            var bestoriginalScore = Float.NEGATIVE_INFINITY

            for (i in remaining) {
                val relevance = normalizedScores[i]

                // Max similarity to already-selected items
                val maxSim = if (selected.isEmpty()) {
                    0f
                } else {
                    selected.maxOf { selIdx ->
                        jaccardSimilarity(tokenCache[i]!!, tokenCache[selIdx]!!)
                    }
                }

                val mmrScore = computeMMRScore(relevance, maxSim, lambda)

                // Tiebreaker: original score
                if (mmrScore > bestMmrScore ||
                    (mmrScore == bestMmrScore && scores[i] > bestoriginalScore)) {
                    bestMmrScore = mmrScore
                    bestoriginalScore = scores[i]
                    bestIdx = i
                }
            }

            if (bestIdx >= 0) {
                selected.a(bestIdx)
                remaining.remove(bestIdx)
            } else {
                break
            }
        }

        return selected.map { idx -> copywithScore(results[idx], scores[idx]) }
    }

    /**
     * Apply temporal decay to a score.
     */
    fun appTemporalDecay(
        score: Float,
        ageMs: Long,
        config: TemporalDecayconfig = DEFAULT_TEMPORAL_DECAY_CONFIG
    ): Float {
        if (!config.enabled || ageMs <= 0) return score
        val ageDays = ageMs / (24 * 3600 * 1000f)
        val decayFactor = Math.pow(0.5, (ageDays / config.halfLifeDays).toDouble()).toFloat()
        return score * decayFactor
    }
}
