import importlib.util
import sys
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "evaluate_g1_matrix.py"
SPEC = importlib.util.spec_from_file_location("evaluate_g1_matrix", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)
evaluate = MODULE.evaluate


def verification(events, discontinuities=0):
    lifecycle_events = [
        {
            "event": event,
            "source": "system" if event == "INTERRUPTED" else "adb-harness",
            "errorCode": "AUDIO_CLIENT_SILENCED" if event == "INTERRUPTED" else None,
        }
        for event in events
    ]
    return {
        "status": "COMPLETED",
        "durationMs": 150_000,
        "totalBytes": 4_800_000,
        "checkpointErrorCode": None,
        "readErrorCount": 0,
        "discontinuityCount": discontinuities,
        "estimatedMissingFrames": 0,
        "pcm": {
            "unlistedPcm": [],
            "actualTotalBytes": 4_800_000,
            "segmentCount": 3,
            "listedSegmentCount": 3,
        },
        "vad": {"processedDurationMs": 150_000},
        "lifecycle": {"eventNames": events, "events": lifecycle_events},
    }


class EvaluateG1MatrixTest(unittest.TestCase):
    def setUp(self):
        self.device = {"productModel": "SM-S938B", "productDevice": "pa3q"}
        self.case_a = {"automaticThresholdsMet": True}
        self.case_b_verification = verification([
            "STARTED",
            "RECOVERY_STARTED",
            "RECOVERED",
            "PAUSED",
            "RESUMED",
            "COMPLETED",
        ])
        self.case_b_summary = {
            "checkpointStatusAfterForceStop": "RECORDING",
            "preCrashDurationMs": 60_000,
            "recoveredDurationMs": 65_000,
            "postRecoveryDurationMs": 125_000,
            "preExistingSegmentsPreserved": True,
            "protectedSegmentCount": 1,
            "finalSegmentCount": 3,
        }
        self.case_c_verification = verification([
            "STARTED",
            "INTERRUPTED",
            "RECOVERY_STARTED",
            "RECOVERED",
            "PAUSED",
            "RESUMED",
            "COMPLETED",
        ], discontinuities=1)
        self.case_c_summary = {
            "callStates": [{"state": 1}, {"state": 2}, {"state": 0}],
            "answeredCallDurationSeconds": 20,
            "statusAfterCall": "RECOVERING",
            "interruptionErrorCode": "AUDIO_CLIENT_SILENCED",
            "recoveryRequired": True,
            "preCallDurationMs": 60_000,
            "postCallDurationMs": 125_000,
            "finalReadErrorCount": 0,
            "finalDiscontinuityCount": 1,
            "finalEstimatedMissingFrames": 0,
        }

    def run_evaluation(self):
        return evaluate(
            self.case_a,
            self.device,
            self.case_b_verification,
            self.case_b_summary,
            self.device,
            self.case_c_verification,
            self.case_c_summary,
        )

    def test_requires_all_three_cases_and_keeps_manual_approval(self):
        report = self.run_evaluation()

        self.assertTrue(report["automaticThresholdsMet"])
        self.assertEqual("ELIGIBLE_FOR_MANUAL_REVIEW", report["status"])
        self.assertFalse(report["approved"])

    def test_rejects_recovery_without_immutable_recovered_event(self):
        self.case_b_verification["lifecycle"]["eventNames"].remove("RECOVERED")

        report = self.run_evaluation()

        self.assertFalse(report["automaticThresholdsMet"])
        self.assertFalse(report["caseBChecks"]["recoveryLifecycleRecorded"])

    def test_accepts_explicit_continuity_when_call_does_not_preempt_mic(self):
        self.case_c_verification = verification([
            "STARTED",
            "PAUSED",
            "RESUMED",
            "COMPLETED",
        ])
        self.case_c_summary.update({
            "statusAfterCall": "RECORDING",
            "interruptionErrorCode": None,
            "recoveryRequired": False,
            "finalDiscontinuityCount": 0,
        })

        self.assertTrue(self.run_evaluation()["automaticThresholdsMet"])

    def test_rejects_interruption_without_matching_system_event(self):
        interrupted = next(
            event
            for event in self.case_c_verification["lifecycle"]["events"]
            if event["event"] == "INTERRUPTED"
        )
        interrupted["source"] = "ui"

        report = self.run_evaluation()

        self.assertFalse(report["automaticThresholdsMet"])
        self.assertFalse(report["caseCChecks"]["callOutcomeExplicit"])


if __name__ == "__main__":
    unittest.main()
