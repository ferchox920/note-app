package com.noteapp.asr

/**
 * Rejects obvious decoder loops before they can become stable UI text.
 *
 * This is intentionally conservative: normal repeated words are preserved unless
 * a short hypothesis is dominated by the same run or a longer hypothesis shrinks
 * by at least half after collapsing consecutive repeated n-grams.
 */
object IncrementalTranscriptSanitizer {
    data class Result(
        val text: String,
        val suppressedRepetition: Boolean,
    )

    fun sanitize(text: String): String = inspect(text).text

    fun inspect(text: String): Result {
        val tokens = text.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        if (tokens.size < MINIMUM_TOKENS) return Result(text.trim(), false)
        val collapsed = collapseConsecutiveRepetitions(tokens)
        val compressionRatio = collapsed.size.toDouble() / tokens.size
        val suppressed = (
            collapsed.repetitionRunCount >= SHORT_LOOP_REPETITIONS ||
                (tokens.size >= LONG_HYPOTHESIS_TOKENS && compressionRatio <= MAX_COMPRESSION_RATIO)
            )
        return Result(
            text = if (suppressed) "" else text.trim(),
            suppressedRepetition = suppressed,
        )
    }

    private fun collapseConsecutiveRepetitions(tokens: List<String>): CollapseResult {
        val output = mutableListOf<String>()
        var maximumRunCount = 1
        var index = 0
        while (index < tokens.size) {
            var repeatedSize = 0
            var repeatedCount = 1
            var removedTokenCount = 0
            val maximumSize = minOf(MAXIMUM_NGRAM_SIZE, (tokens.size - index) / 2)
            for (size in maximumSize downTo 1) {
                var count = 1
                while (
                    index + (count + 1) * size <= tokens.size &&
                    normalizedBlockEquals(tokens, index, index + count * size, size)
                ) {
                    count++
                }
                val removed = size * (count - 1)
                if (
                    count >= 2 &&
                    (removed > removedTokenCount ||
                        (removed == removedTokenCount && count > repeatedCount))
                ) {
                    repeatedSize = size
                    repeatedCount = count
                    removedTokenCount = removed
                }
            }
            if (repeatedSize == 0) {
                output += tokens[index++]
            } else {
                output += tokens.subList(index, index + repeatedSize)
                maximumRunCount = maxOf(maximumRunCount, repeatedCount)
                index += repeatedSize * repeatedCount
            }
        }
        return CollapseResult(output, maximumRunCount)
    }

    private fun normalizedBlockEquals(
        tokens: List<String>,
        leftStart: Int,
        rightStart: Int,
        size: Int,
    ): Boolean = (0 until size).all { offset ->
        normalize(tokens[leftStart + offset]) == normalize(tokens[rightStart + offset])
    }

    private fun normalize(token: String): String =
        token.lowercase().filter { it.isLetterOrDigit() }

    private data class CollapseResult(
        val tokens: List<String>,
        val repetitionRunCount: Int,
    ) {
        val size: Int get() = tokens.size
    }

    private const val MINIMUM_TOKENS = 6
    private const val SHORT_LOOP_REPETITIONS = 4
    private const val LONG_HYPOTHESIS_TOKENS = 12
    private const val MAX_COMPRESSION_RATIO = 0.5
    private const val MAXIMUM_NGRAM_SIZE = 8
}
