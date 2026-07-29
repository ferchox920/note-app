package com.noteapp.recording

import com.noteapp.asr.IncrementalTranscriptSegment
import java.util.Locale

internal data class IncrementalTranscriptPresentation(
    val finalizedSegments: List<IncrementalTranscriptSegment>,
    val provisionalStableText: String,
    val provisionalUnstableText: String,
) {
    val hasProvisionalText: Boolean
        get() = provisionalStableText.isNotBlank() || provisionalUnstableText.isNotBlank()
}

internal fun incrementalTranscriptPresentation(
    stableText: String,
    unstableText: String,
    finalizedSegments: List<IncrementalTranscriptSegment>,
): IncrementalTranscriptPresentation {
    val finalizedText = finalizedSegments.joinToString(" ") { it.text.trim() }.trim()
    val normalizedStableText = stableText.trim()
    val provisionalStableText = when {
        finalizedText.isBlank() -> normalizedStableText
        normalizedStableText == finalizedText -> ""
        normalizedStableText.startsWith("$finalizedText ") ->
            normalizedStableText.removePrefix(finalizedText).trimStart()
        else -> normalizedStableText
    }
    return IncrementalTranscriptPresentation(
        finalizedSegments = finalizedSegments,
        provisionalStableText = provisionalStableText,
        provisionalUnstableText = unstableText.trim(),
    )
}

internal fun formatTranscriptTimestamp(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0L) / 1_000
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}

internal fun transcriptSegmentRange(segment: IncrementalTranscriptSegment): String =
    "${formatTranscriptTimestamp(segment.startMs)}–${formatTranscriptTimestamp(segment.endMs)}"
