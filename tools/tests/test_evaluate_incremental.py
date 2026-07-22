import importlib.util
import sys
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "evaluate_incremental.py"
SPEC = importlib.util.spec_from_file_location("evaluate_incremental", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)
aggregate_sessions = MODULE.aggregate_sessions
percentile = MODULE.percentile
summarize_session = MODULE.summarize_session


class EvaluateIncrementalTest(unittest.TestCase):
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
                    {"final": False, "audioDurationMs": 4_000, "inferenceDurationMs": 2_000, "visibleLatencyMs": 2_100},
                    {"final": True, "audioDurationMs": 4_000, "inferenceDurationMs": 3_000, "visibleLatencyMs": 3_100},
                ],
            },
            "session.json",
        )

        self.assertTrue(summary["eligibleForManualG2Review"])
        self.assertEqual(0.625, summary["weightedRealTimeFactor"])
        self.assertEqual(1, summary["droppedPartialCount"])

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


if __name__ == "__main__":
    unittest.main()
