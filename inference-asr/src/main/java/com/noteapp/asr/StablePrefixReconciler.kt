package com.noteapp.asr

data class IncrementalTranscript(
    val stableText: String,
    val unstableText: String,
    val stableConflict: Boolean = false,
)

/** Commits only words shared by two consecutive cumulative hypotheses. */
class StablePrefixReconciler(
    private val minimumUnstableWords: Int = 1,
) {
    private var committed = emptyList<String>()
    private var previousTail = emptyList<String>()

    init {
        require(minimumUnstableWords >= 0)
    }

    fun update(hypothesis: String): IncrementalTranscript {
        val current = tokenize(hypothesis)
        val committedStillMatches = normalizedPrefixMatches(committed, current)
        val currentTail = if (committedStillMatches) current.drop(committed.size) else current
        val commonCount = commonPrefixLength(previousTail, currentTail)
        val stableCandidateCount = minOf(
            commonCount,
            (currentTail.size - minimumUnstableWords).coerceAtLeast(0),
        )
        val overlap = suffixPrefixOverlap(previousTail, currentTail)
        var conflict = false
        when {
            stableCandidateCount > 0 -> {
                committed = committed + previousTail.take(stableCandidateCount)
                previousTail = currentTail.drop(stableCandidateCount)
            }
            previousTail.isEmpty() -> previousTail = currentTail
            overlap > 0 -> {
                committed = committed + previousTail.dropLast(overlap)
                previousTail = currentTail
            }
            committedStillMatches -> previousTail = currentTail
            else -> {
                conflict = committed.isNotEmpty()
                previousTail = currentTail
            }
        }
        return view(conflict)
    }

    fun finalizeSegment(finalHypothesis: String): IncrementalTranscript {
        val reconciled = update(finalHypothesis)
        val result = IncrementalTranscript(
            stableText = listOf(reconciled.stableText, reconciled.unstableText)
                .filter(String::isNotBlank)
                .joinToString(" "),
            unstableText = "",
            stableConflict = reconciled.stableConflict,
        )
        reset()
        return result
    }

    fun reset() {
        committed = emptyList()
        previousTail = emptyList()
    }

    private fun view(conflict: Boolean) = IncrementalTranscript(
        stableText = committed.joinToString(" "),
        unstableText = previousTail.joinToString(" "),
        stableConflict = conflict,
    )

    private fun normalizedPrefixMatches(prefix: List<String>, tokens: List<String>): Boolean =
        prefix.size <= tokens.size && prefix.indices.all { normalize(prefix[it]) == normalize(tokens[it]) }

    private fun commonPrefixLength(left: List<String>, right: List<String>): Int {
        var count = 0
        while (count < left.size && count < right.size && normalize(left[count]) == normalize(right[count])) {
            count++
        }
        return count
    }

    private fun suffixPrefixOverlap(left: List<String>, right: List<String>): Int {
        for (length in minOf(left.size, right.size) downTo 1) {
            val leftStart = left.size - length
            if ((0 until length).all { normalize(left[leftStart + it]) == normalize(right[it]) }) {
                return length
            }
        }
        return 0
    }

    private fun tokenize(text: String): List<String> = text.trim()
        .split(Regex("\\s+"))
        .filter(String::isNotBlank)

    private fun normalize(token: String): String = token
        .lowercase()
        .filter { it.isLetterOrDigit() }
}
