#!/usr/bin/env python3
"""Evaluate the sanitized evidence for G1 case A."""

from __future__ import annotations

import argparse
import json
from datetime import datetime
from pathlib import Path


def parse_instant(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def read_jsonl(path: Path) -> list[dict]:
    return [
        json.loads(line)
        for line in path.read_text(encoding="utf-8-sig").splitlines()
        if line.strip()
    ]


def observed_screen_off_ms(samples: list[dict], maximum_gap_ms: int = 120_000) -> int:
    ordered = sorted(samples, key=lambda sample: parse_instant(sample["observedAt"]))
    duration_ms = 0
    for previous, current in zip(ordered, ordered[1:]):
        delta_ms = int(
            (parse_instant(current["observedAt"]) - parse_instant(previous["observedAt"])).total_seconds()
            * 1_000
        )
        healthy_and_off = all(
            bool(sample.get("connected"))
            and bool(sample.get("processAlive"))
            and bool(sample.get("serviceForeground"))
            and not bool(sample.get("screenOn"))
            and sample.get("sessionStatus") in {"RECORDING", "PAUSED"}
            for sample in (previous, current)
        )
        if healthy_and_off and 0 <= delta_ms <= maximum_gap_ms:
            duration_ms += delta_ms
    return duration_ms


def first_action(actions: list[dict], name: str) -> dict | None:
    return next((action for action in actions if action.get("action") == name), None)


def evaluate(verification: dict, monitor_samples: list[dict], timed_actions: list[dict]) -> dict:
    lifecycle_names = set(verification.get("lifecycle", {}).get("eventNames", []))
    pcm = verification.get("pcm", {})
    vad = verification.get("vad", {})
    duration_ms = int(verification.get("durationMs", 0))
    processed_duration_ms = int(vad.get("processedDurationMs", -1))
    screen_off_ms = observed_screen_off_ms(monitor_samples)
    pause = first_action(timed_actions, "PAUSED_AT_30_MIN")
    resume = first_action(timed_actions, "RESUMED_AFTER_10_SECONDS")
    background = first_action(timed_actions, "SETTINGS_AT_60_MIN")
    background_verified = first_action(timed_actions, "BACKGROUND_65_SECONDS_VERIFIED")
    ninety_minutes = first_action(timed_actions, "NINETY_MINUTES_READY")
    runner_errors = [action for action in timed_actions if action.get("action") == "RUNNER_ERROR"]
    pause_wall_ms = (
        int((parse_instant(resume["observedAt"]) - parse_instant(pause["observedAt"])).total_seconds() * 1_000)
        if pause is not None and resume is not None
        else -1
    )

    checks = {
        "completedAtLeast90Minutes": (
            verification.get("status") == "COMPLETED"
            and duration_ms >= 90 * 60 * 1_000
        ),
        "captureIntegrity": (
            verification.get("checkpointErrorCode") is None
            and verification.get("readErrorCount", 0) == 0
            and verification.get("discontinuityCount", 0) == 0
            and verification.get("estimatedMissingFrames", 0) == 0
            and not pcm.get("unlistedPcm")
            and pcm.get("actualTotalBytes") == verification.get("totalBytes")
            and pcm.get("segmentCount") == pcm.get("listedSegmentCount")
        ),
        "vadCoveredRecordedTimeline": 0 <= duration_ms - processed_duration_ms < 20,
        "lifecycleComplete": {
            "STARTED", "PAUSED", "RESUMED", "COMPLETED"
        }.issubset(lifecycle_names),
        "screenOffAtLeast80Minutes": screen_off_ms >= 80 * 60 * 1_000,
        "monitorStayedHealthy": bool(monitor_samples) and all(
            sample.get("connected")
            and sample.get("processAlive")
            and sample.get("serviceForeground")
            and sample.get("readErrorCount", 0) == 0
            and sample.get("discontinuityCount", 0) == 0
            and sample.get("estimatedMissingFrames", 0) == 0
            for sample in monitor_samples
            if sample.get("sessionStatus") in {"RECORDING", "PAUSED"}
        ),
        "pauseResumeBetween30And35Minutes": (
            pause is not None
            and resume is not None
            and 30 * 60 * 1_000 <= int(pause.get("durationMs", -1)) <= 35 * 60 * 1_000
            and 10_000 <= pause_wall_ms <= 30_000
        ),
        "backgroundBetween60And65Minutes": (
            background is not None
            and background_verified is not None
            and 60 * 60 * 1_000 <= int(background.get("durationMs", -1)) <= 65 * 60 * 1_000
            and int(background_verified.get("durationMs", 0)) - int(background.get("durationMs", 0)) >= 60_000
            and background_verified.get("result") == "RECORDING"
        ),
        "runnerReached90MinutesWithoutErrors": (
            ninety_minutes is not None
            and int(ninety_minutes.get("durationMs", 0)) >= 90 * 60 * 1_000
            and not runner_errors
        ),
    }
    passed = all(checks.values())
    return {
        "schemaVersion": 1,
        "case": "G1-A",
        "automaticChecks": checks,
        "automaticThresholdsMet": passed,
        "status": "ELIGIBLE_FOR_MANUAL_REVIEW" if passed else "AUTOMATIC_CHECKS_FAILED",
        "metrics": {
            "durationMs": duration_ms,
            "screenOffObservedMs": screen_off_ms,
            "monitorSampleCount": len(monitor_samples),
            "lifecycleEventCount": verification.get("lifecycle", {}).get("eventCount", 0),
        },
        "manualReviewRequired": [
            "Confirmar que el audio de prueba fue autorizado.",
            "Confirmar que la notificación reflejó grabación y pausa.",
            "Revisar cualquier incidente físico no representado por las métricas.",
        ],
        "approved": False,
        "contentIncluded": False,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--verification", required=True, type=Path)
    parser.add_argument("--monitor-jsonl", required=True, type=Path)
    parser.add_argument("--timed-actions-jsonl", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--fail-on-automatic-check", action="store_true")
    args = parser.parse_args()

    report = evaluate(
        json.loads(args.verification.read_text(encoding="utf-8-sig")),
        read_jsonl(args.monitor_jsonl),
        read_jsonl(args.timed_actions_jsonl),
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(report, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    if args.fail_on_automatic_check and not report["automaticThresholdsMet"]:
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
