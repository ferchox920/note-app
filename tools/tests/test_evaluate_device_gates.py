import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


MODULE_PATH = Path(__file__).parents[1] / "evaluate_device_gates.py"
SPEC = importlib.util.spec_from_file_location("evaluate_device_gates", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class EvaluateDeviceGatesTest(unittest.TestCase):
    def setUp(self):
        self.device = {"productModel": "SM-S938B", "productDevice": "pa3q"}
        self.verification = {
            "status": "COMPLETED",
            "checkpointErrorCode": None,
            "durationMs": 5_400_000,
            "totalBytes": 172_800_000,
            "readErrorCount": 0,
            "discontinuityCount": 0,
            "estimatedMissingFrames": 0,
            "pcm": {
                "unlistedPcm": [], "actualTotalBytes": 172_800_000,
                "segmentCount": 2, "listedSegmentCount": 2,
            },
            "vad": {"segmentCount": 5, "processedDurationMs": 5_400_000},
            "asr": [
                {"modelId": MODULE.TINY_MODEL, "chunkCount": 2, "realTimeFactor": 0.8, "peakPssKb": 10, "maximumThermalStatus": 1},
                {"modelId": MODULE.BASE_MODEL, "chunkCount": 2, "realTimeFactor": 1.1, "peakPssKb": 20, "maximumThermalStatus": 2},
            ],
            "incremental": {
                "metricCount": 100, "timeToFirstTextMs": 3_000,
                "visibleLatencyP95Ms": 5_000, "weightedRealTimeFactor": 0.9,
                "errorCode": None, "stableConflictCount": 0,
            },
        }

    def test_clean_s25_evidence_is_only_eligible_not_approved(self):
        g0 = MODULE.evaluate_g0(self.device, self.verification)
        g1 = MODULE.evaluate_g1(self.device, self.verification)
        g2 = MODULE.evaluate_g2(self.device, self.verification)

        self.assertTrue(g0["automaticThresholdsMet"])
        self.assertTrue(g1["automaticThresholdsMet"])
        self.assertTrue(g2["automaticThresholdsMet"])
        self.assertFalse(g0["approved"])
        self.assertEqual("ELIGIBLE_FOR_MANUAL_REVIEW", g2["status"])

    def test_g0_selects_fastest_result_when_debug_and_benchmark_coexist(self):
        device = dict(self.device)
        verification = json.loads(json.dumps(self.verification))
        verification["asr"].extend([
            {
                "modelId": "whisper-tiny-multilingual-q5_1",
                "benchmarkConfigId": None,
                "chunkCount": 16,
                "realTimeFactor": 5.34,
                "timeToFirstTextMs": 51_716,
                "peakPssKb": 390_000,
                "maximumThermalStatus": 1,
            },
            {
                "modelId": "whisper-base-multilingual-q5_1",
                "benchmarkConfigId": None,
                "chunkCount": 16,
                "realTimeFactor": 6.91,
                "timeToFirstTextMs": 118_294,
                "peakPssKb": 477_000,
                "maximumThermalStatus": 0,
            },
        ])

        g0 = MODULE.evaluate_g0(device, verification)

        self.assertTrue(g0["automaticThresholdsMet"])
        selected = {item["modelId"]: item for item in g0["selectedAsrResults"]}
        self.assertEqual(0.8, selected["whisper-tiny-multilingual-q5_1"]["realTimeFactor"])
        self.assertEqual(1.1, selected["whisper-base-multilingual-q5_1"]["realTimeFactor"])

    def test_wrong_device_blocks_all_gates(self):
        device = {"productModel": "Pixel 10", "productDevice": "mustang"}

        self.assertFalse(MODULE.evaluate_g0(device, self.verification)["automaticChecks"]["s25Ultra"])
        self.assertFalse(MODULE.evaluate_g1(device, self.verification)["automaticThresholdsMet"])
        self.assertFalse(MODULE.evaluate_g2(device, self.verification)["automaticThresholdsMet"])

    def test_incremental_latency_and_rtf_fail_g2(self):
        self.verification["incremental"]["visibleLatencyP95Ms"] = 7_500
        self.verification["incremental"]["weightedRealTimeFactor"] = 1.3

        gate = MODULE.evaluate_g2(self.device, self.verification)

        self.assertFalse(gate["automaticThresholdsMet"])
        self.assertFalse(gate["automaticChecks"]["visibleLatencyP95AtMost6s"])
        self.assertFalse(gate["automaticChecks"]["weightedRtfAtMost1"])

    def test_cli_writes_sanitized_manual_review_report(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            evidence_dirs = []
            for name in ("g0", "g1", "g2"):
                directory = root / name
                directory.mkdir()
                (directory / "device.json").write_text(json.dumps(self.device), encoding="utf-8")
                (directory / "verification.json").write_text(json.dumps(self.verification), encoding="utf-8")
                evidence_dirs.append(directory)
            output = root / "report.json"
            arguments = [
                "evaluate_device_gates.py",
                "--g0-evidence", str(evidence_dirs[0]),
                "--g1-evidence", str(evidence_dirs[1]),
                "--g2-evidence", str(evidence_dirs[2]),
                "--output", str(output),
                "--fail-on-automatic-check",
            ]

            with patch.object(sys, "argv", arguments):
                exit_code = MODULE.main()

            report = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(0, exit_code)
            self.assertEqual("MANUAL_APPROVAL_REQUIRED", report["status"])
            self.assertTrue(report["allEligibleForManualReview"])
            self.assertTrue(all(not gate["approved"] for gate in report["gates"]))


if __name__ == "__main__":
    unittest.main()
