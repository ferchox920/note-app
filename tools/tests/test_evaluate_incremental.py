import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "evaluate_incremental.py"
SPEC = importlib.util.spec_from_file_location("evaluate_incremental", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)
aggregate_sessions = MODULE.aggregate_sessions
covered_audio_duration_ms = MODULE.covered_audio_duration_ms
percentile = MODULE.percentile
reference_span_word_error = MODULE.reference_span_word_error
summarize_session = MODULE.summarize_session
word_error_rate = MODULE.word_error_rate


class EvaluateIncrementalTest(unittest.TestCase):
    def test_word_error_rate_normalizes_case_accents_and_punctuation(self):
        self.assertEqual(
            0.25,
            word_error_rate(
                "Hola, ¿cómo estás hoy?",
                "hola cómo estás mañana",
            ),
        )

    def test_reference_span_ignores_only_leading_and_trailing_words(self):
        aligned = reference_span_word_error(
            "esta es la prueba",
            "ruido previo esta es palabra la prueba ruido final",
        )

        self.assertIsNotNone(aligned)
        self.assertEqual(0.25, aligned["wordErrorRate"])
        self.assertEqual(2, aligned["leadingExcludedWordCount"])
        self.assertEqual(2, aligned["trailingExcludedWordCount"])

    def test_percentile_interpolates_sorted_values(self):
        self.assertEqual(25.0, percentile([40, 10, 20, 30], 0.50))
        self.assertEqual(38.5, percentile([40, 10, 20, 30], 0.95))

    def test_session_summary_applies_technical_thresholds(self):
        summary = summarize_session(
            {
                "modelId": "base",
                "capturePipelineId": "direct-16k",
                "timeToFirstTextMs": 3_500,
                "droppedPartialCount": 1,
                "stableConflictCount": 0,
                "errorCode": None,
                "inferenceMetrics": [
                    {
                        "final": False, "windowStartMs": 0, "windowEndMs": 4_000,
                        "audioDurationMs": 4_000, "inferenceDurationMs": 2_000, "visibleLatencyMs": 2_100,
                    },
                    {
                        "final": True, "windowStartMs": 4_000, "windowEndMs": 8_000,
                        "audioDurationMs": 4_000, "inferenceDurationMs": 3_000, "visibleLatencyMs": 3_100,
                    },
                ],
            },
            "session.json",
        )

        self.assertTrue(summary["eligibleForManualG2Review"])
        self.assertEqual(0.625, summary["weightedRealTimeFactor"])
        self.assertEqual(1, summary["droppedPartialCount"])
        self.assertEqual(8_000, summary["coveredAudioDurationMs"])
        self.assertEqual(0, summary["reusedResultCount"])

    def test_overlapping_windows_count_unique_audio_once(self):
        metrics = [
            {
                "final": False, "windowStartMs": 0, "windowEndMs": 4_000,
                "audioDurationMs": 4_000, "inferenceDurationMs": 2_000, "visibleLatencyMs": 2_100,
            },
            {
                "final": True, "windowStartMs": 0, "windowEndMs": 4_000,
                "audioDurationMs": 4_000, "inferenceDurationMs": 3_000, "visibleLatencyMs": 3_100,
            },
        ]

        self.assertEqual(4_000, covered_audio_duration_ms(metrics))
        summary = summarize_session({
            "modelId": "base",
            "capturePipelineId": "direct-16k",
            "timeToFirstTextMs": 3_500,
            "errorCode": None,
            "inferenceMetrics": metrics,
        })

        self.assertEqual(8_000, summary["windowAudioDurationMs"])
        self.assertEqual(4_000, summary["coveredAudioDurationMs"])
        self.assertEqual(1.25, summary["weightedRealTimeFactor"])
        self.assertFalse(summary["eligibleForManualG2Review"])

    def test_aggregate_uses_worst_session_for_g2_risk(self):
        rows = [
            {
                "modelId": "base", "capturePipelineId": "direct-16k", "timeToFirstTextMs": 2_000,
                "visibleLatencyP95Ms": 3_000, "weightedRealTimeFactor": 0.7,
                "droppedPartialCount": 0, "stableConflictCount": 0, "eligibleForManualG2Review": True,
            },
            {
                "modelId": "base", "capturePipelineId": "direct-16k", "timeToFirstTextMs": 5_000,
                "visibleLatencyP95Ms": 7_000, "weightedRealTimeFactor": 1.2,
                "droppedPartialCount": 3, "stableConflictCount": 2, "eligibleForManualG2Review": False,
            },
        ]

        aggregate = aggregate_sessions(rows)["base|direct-16k"]

        self.assertEqual(7_000, aggregate["worstSessionVisibleLatencyP95Ms"])
        self.assertEqual(1.2, aggregate["worstSessionWeightedRealTimeFactor"])
        self.assertFalse(aggregate["allEligibleForManualG2Review"])

    def test_cli_accepts_bom_in_verification_manifest(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            results = root / "results"
            output = root / "output"
            results.mkdir()
            (results / "verification.json").write_text(
                json.dumps({"verified": True}), encoding="utf-8-sig"
            )
            (results / "incremental-transcript.json").write_text(
                json.dumps({
                    "modelId": "tiny",
                    "capturePipelineId": "direct-16k",
                    "timeToFirstTextMs": 1_000,
                    "errorCode": None,
                    "inferenceMetrics": [{
                        "final": False,
                        "windowStartMs": 0,
                        "windowEndMs": 4_000,
                        "audioDurationMs": 4_000,
                        "inferenceDurationMs": 2_000,
                        "visibleLatencyMs": 2_000,
                    }],
                }),
                encoding="utf-8",
            )
            reference = root / "reference.txt"
            reference.write_text("texto de referencia", encoding="utf-8")

            completed = subprocess.run(
                [
                    sys.executable,
                    str(MODULE_PATH),
                    "--results-dir",
                    str(results),
                    "--output-dir",
                    str(output),
                    "--consent-confirmed",
                    "--reference-text",
                    str(reference),
                ],
                capture_output=True,
                text=True,
                check=False,
            )

            self.assertEqual(0, completed.returncode, completed.stderr)
            report = json.loads(
                (output / "incremental-evaluation.json").read_text(encoding="utf-8")
            )
            self.assertEqual(5, report["schemaVersion"])
            self.assertIn("referenceSpanWordErrorRate", report["sessions"][0])


if __name__ == "__main__":
    unittest.main()
