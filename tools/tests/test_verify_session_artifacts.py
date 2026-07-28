import hashlib
import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "verify_session_artifacts.py"
SPEC = importlib.util.spec_from_file_location("verify_session_artifacts", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)
verify_session = MODULE.verify_session


class VerifySessionArtifactsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.session = Path(self.temp.name)
        pcm = bytes([1, 2]) * 320
        (self.session / "segment-0000.pcm").write_bytes(pcm)
        digest = hashlib.sha256(pcm).hexdigest()
        checkpoint = {
            "schemaVersion": 1,
            "sessionId": "session-1",
            "status": "COMPLETED",
            "capturePipeline": "direct-16k",
            "sampleRateHz": 16_000,
            "channelCount": 1,
            "bitsPerSample": 16,
            "durationMs": 20,
            "totalBytes": 640,
            "readErrorCount": 0,
            "discontinuityCount": 0,
            "estimatedMissingFrames": 0,
            "segments": [{
                "sequence": 0,
                "fileName": "segment-0000.pcm",
                "startByteOffset": 0,
                "endByteOffset": 640,
                "byteCount": 640,
                "sha256": digest,
            }],
        }
        self.write_json("checkpoint.json", checkpoint)
        self.write_json("vad-segments.json", {
            "schemaVersion": 1,
            "sessionId": "session-1",
            "engine": "webrtc-vad",
            "sampleRateHz": 16_000,
            "processedDurationMs": 20,
            "segments": [{
                "sequence": 0,
                "startMs": 0,
                "endMs": 20,
                "startByteOffset": 0,
                "endByteOffset": 640,
            }],
        })
        self.write_json("asr-result-base.json", {
            "schemaVersion": 1,
            "modelId": "base",
            "chunkCount": 1,
            "audioDurationMs": 20,
            "inferenceDurationMs": 10,
            "realTimeFactor": 0.5,
            "timeToFirstTextMs": 10,
            "peakPssKb": 123,
            "segments": [{"startMs": 0, "endMs": 20, "text": "sensitive"}],
        })
        self.write_json("incremental-transcript.json", {
            "schemaVersion": 1,
            "modelId": "base",
            "partialCount": 1,
            "droppedPartialCount": 0,
            "stableConflictCount": 0,
            "timeToFirstTextMs": 10,
            "errorCode": None,
            "segments": [{"startMs": 0, "endMs": 20, "text": "sensitive"}],
            "inferenceMetrics": [
                {
                    "sequence": 0, "windowStartMs": 0, "windowEndMs": 20,
                    "final": False, "audioDurationMs": 20, "inferenceDurationMs": 10,
                    "visibleLatencyMs": 12, "realTimeFactor": 0.5,
                },
                {
                    "sequence": 1, "windowStartMs": 0, "windowEndMs": 20,
                    "final": True, "audioDurationMs": 20, "inferenceDurationMs": 10,
                    "visibleLatencyMs": 13, "realTimeFactor": 0.5,
                },
            ],
        })

    def tearDown(self):
        self.temp.cleanup()

    def write_json(self, name, value):
        (self.session / name).write_text(json.dumps(value), encoding="utf-8")

    def test_valid_session_returns_content_free_report(self):
        report = verify_session(
            self.session,
            required_models=["base"],
            require_incremental=True,
        )

        self.assertEqual(1, report["pcm"]["segmentCount"])
        self.assertEqual(1, report["vad"]["segmentCount"])
        self.assertEqual("base", report["asr"][0]["modelId"])
        self.assertEqual(12, report["incremental"]["visibleLatencyP95Ms"])
        self.assertEqual(0.5, report["incremental"]["weightedRealTimeFactor"])
        self.assertFalse(report["contentIncluded"])
        self.assertNotIn("sensitive", json.dumps(report))

    def test_rejects_pcm_checksum_mismatch(self):
        (self.session / "segment-0000.pcm").write_bytes(bytes(640))

        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            verify_session(self.session)

    def test_rejects_overlapping_vad_intervals(self):
        self.write_json("vad-segments.json", {
            "schemaVersion": 1,
            "sessionId": "session-1",
            "engine": "webrtc-vad",
            "sampleRateHz": 16_000,
            "processedDurationMs": 20,
            "segments": [
                {"sequence": 0, "startMs": 0, "endMs": 15, "startByteOffset": 0, "endByteOffset": 480},
                {"sequence": 1, "startMs": 10, "endMs": 20, "startByteOffset": 320, "endByteOffset": 640},
            ],
        })

        with self.assertRaisesRegex(ValueError, "overlap"):
            verify_session(self.session)

    def test_allows_contiguous_crash_orphan_with_checkpointed_prefix(self):
        checkpoint_path = self.session / "checkpoint.json"
        checkpoint = json.loads(checkpoint_path.read_text(encoding="utf-8"))
        checkpoint["status"] = "RECOVERING"
        checkpoint["totalBytes"] = 960
        checkpoint["durationMs"] = 30
        self.write_json("checkpoint.json", checkpoint)
        (self.session / "segment-0001.pcm").write_bytes(bytes([3, 4]) * 320)

        report = verify_session(
            self.session,
            expected_status="RECOVERING",
            allow_unlisted_pcm=True,
        )

        self.assertEqual(["segment-0001.pcm"], report["pcm"]["unlistedPcm"])
        self.assertEqual(1_280, report["pcm"]["actualTotalBytes"])

    def test_validates_required_lifecycle_events_without_exposing_content(self):
        directory = self.session / "lifecycle-events"
        directory.mkdir()
        events = [
            ("STARTED", "RECORDING", "ui", 0, 0),
            ("PAUSED", "PAUSED", "notification", 10, 320),
            ("RESUMED", "RECORDING", "ui", 10, 320),
            ("COMPLETED", "COMPLETED", "ui", 20, 640),
        ]
        for sequence, (event, status, source, duration_ms, total_bytes) in enumerate(events):
            (directory / f"event-{sequence:04d}.json").write_text(json.dumps({
                "schemaVersion": 1,
                "sequence": sequence,
                "sessionId": "session-1",
                "event": event,
                "status": status,
                "source": source,
                "observedAtEpochMs": 1_000 + sequence,
                "observedAtMonotonicMs": 2_000 + sequence,
                "audioDurationMs": duration_ms,
                "totalBytes": total_bytes,
                "errorCode": None,
            }), encoding="utf-8")

        report = verify_session(
            self.session,
            required_lifecycle_events=["STARTED", "PAUSED", "RESUMED", "COMPLETED"],
        )

        self.assertEqual(4, report["lifecycle"]["eventCount"])
        self.assertEqual("notification", report["lifecycle"]["events"][1]["source"])
        self.assertNotIn("sensitive", json.dumps(report))

    def test_rejects_missing_required_lifecycle_event(self):
        with self.assertRaisesRegex(ValueError, "Missing required lifecycle events"):
            verify_session(self.session, required_lifecycle_events=["STARTED"])


if __name__ == "__main__":
    unittest.main()
