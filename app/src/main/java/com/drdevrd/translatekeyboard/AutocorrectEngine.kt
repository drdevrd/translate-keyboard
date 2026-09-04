package com.drdevrd.translatekeyboard

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Small on-device autocorrect: bundled English word list + edit-distance
 * lookup, grouped by first letter so we only score a manageable subset.
 */
class AutocorrectEngine(context: Context) {

    private val wordsByFirstLetter: Map<Char, List<String>>
    private val wordSet: HashSet<String>

    init {
        val all = ArrayList<String>()
        context.assets.open("words_en.txt").use { input ->
            BufferedReader(InputStreamReader(input)).forEachLine { line ->
                val w = line.trim()
                if (w.isNotEmpty()) all.add(w)
            }
        }
        wordsByFirstLetter = all.groupBy { it[0] }
        wordSet = all.toHashSet()
    }

    private val wordSet: HashSet<String>

    fun isKnown(word: String): Boolean = wordSet.contains(word.lowercase())

    /** Returns up to [max] suggestions ordered by closeness, best first. */
    fun suggest(word: String, max: Int = 3): List<String> {
        val lower = word.lowercase()
        if (lower.length < 2) return emptyList()
        if (wordSet.contains(lower)) return listOf(lower)

        val candidates = wordsByFirstLetter[lower[0]] ?: return emptyList()
        return candidates
            .asSequence()
            .map { it to editDistance(lower, it) }
            .filter { it.second <= 2 }
            .sortedBy { it.second }
            .take(max)
            .map { it.first }
            .toList()
    }

    /** Best single suggestion if it's a confident (distance-1) fix; else null. */
    fun autoFix(word: String): String? {
        val lower = word.lowercase()
        if (wordSet.contains(lower) || lower.length < 3) return null
        val candidates = wordsByFirstLetter[lower[0]] ?: return null
        val best = candidates
            .asSequence()
            .map { it to editDistance(lower, it) }
            .minByOrNull { it.second } ?: return null
        return if (best.second == 1) best.first else null
    }

    private fun editDistance(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) {
                    dp[i - 1][j - 1]
                } else {
                    1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
                }
            }
        }
        return dp[a.length][b.length]
    }
}
