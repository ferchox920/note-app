import importlib.util
import sys
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "evaluate_g1_capture.py"
SPEC = importlib.util.spec_from_file_location("evaluate_g1_capture", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)
evaluate = MODULE.evaluate
observed_screen_off_ms = MODULE.observed_screen_off_ms


def sample(at: datetime, **overrides):
    result = {
        "observedAt": at.isoformat().replace("+00:00", "Z"),
        "connected": True,
        "processAlive": True,
        "serviceForeground": True,
        "screenOn": False,
        "sessionStatus": "RECORDING",
        "readErrorCount": 0,
        "discontinuityCount": 0,
        "estimatedMissingFrames": 0,
    }
    result.update(overrides)
    return result


class EvaluateG1CaptureTest(unittest.TestCase):
    def setUp(self):
        start = datetime(2026, 7, 28, tzinfo=timezone.utc)
        self.samples = [
            sample(start + timedelta(minutes=minute))
            for minute in range(91)
        ]
        self.verification = {
            "status": "COMPLETED",
            "durationMs": 5_400_000,
            "totalBytes": 172_800_000,
            "checkpointErrorCode": None,
            "readErrorCount": 0,
            "discontinuityCount": 0,
            "estimatedMissingFrames": 0,
            "pcm": {
                "unlistedPcm": [],
                "actualTotalBytes": 172_800_000,
                "segmentCount": 3,
                "listedSegmentCount": 3,
            },
            "vad": {"processedDurationMs": 5_400_000},
            "lifecycle": {
                "eventCount": 4,
                "eventNames": ["STARTED", "PAUSED", "RESUMED", "COMPLETED"],
            },
        }
        self.actions = [
            {
                "observedAt": start.isoformat(),
                "action": "PAUSED_AT_30_MIN",
                "durationMs": 1_800_000,
                "result": "ok",
            },
            {
                "observedAt": (start + timedelta(seconds=10)).isoformat(),
                "action": "RESUMED_AFTER_10_SECONDS",
                "durationMs": 1_800_000,
                "result": "ok",
            },
            {"action": "SETTINGS_AT_60_MIN", "durationMs": 3_600_000, "result": "ok"},
            {
                "action": "BACKGROUND_65_SECONDS_VERIFIED",
                "durationMs": 3_665_000,
                "result": "RECORDING",
            },
            {"action": "NINETY_MINUTES_READY", "durationMs": 5_400_000, "result": "RECORDING"},
        ]

    def test_accepts_complete_case_a_evidence(self):
        report = evaluate(self.verification, self.samples, self.actions)

        self.assertTrue(report["automaticThresholdsMet"])
        self.assertEqual(5_400_000, report["metrics"]["screenOffObservedMs"])
        self.assertFalse(report["approved"])

    def test_rejects_monitor_gap_and_runner_error(self):
        self.samples[45]["connected"] = False
        self.actions.append({"action": "RUNNER_ERROR", "durationMs": 0, "result": "adb failed"})

        report = evaluate(self.verification, self.samples, self.actions)

        self.assertFalse(report["automaticThresholdsMet"])
        self.assertFalse(report["automaticChecks"]["monitorStayedHealthy"])
        self.assertFalse(report["automaticChecks"]["runnerReached90MinutesWithoutErrors"])

    def test_does_not_count_large_monitor_gaps(self):
        start = datetime(2026, 7, 28, tzinfo=timezone.utc)
        samples = [
            sample(start),
            sample(start + timedelta(minutes=1)),
            sample(start + timedelta(minutes=10)),
        ]

        self.assertEqual(60_000, observed_screen_off_ms(samples))


if __name__ == "__main__":
    unittest.main()
