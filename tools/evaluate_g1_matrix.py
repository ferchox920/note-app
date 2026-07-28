#!/usr/bin/env python3
"""Evaluate the complete G1 physical matrix without granting manual approval."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


def read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def read_device_evidence(directory: Path) -> tuple[dict, dict]:
    return read_json(directory / "device.json"), read_json(directory / "verification.json")


def is_s25_ultra(device: dict) -> bool:
    identity = f"{device.get('productModel', '')} {device.get('productDevice', '')}"
    return bool(re.search(r"SM-S938|S25\s*Ultra", identity, re.IGNORECASE))


def pcm_complete(verification: dict) -> bool:
    pcm = verification.get("pcm", {})
    return (
        not pcm.get("unlistedPcm")
        and pcm.get("actualTotalBytes") == verification.get("totalBytes")
        and pcm.get("segmentCount") == pcm.get("listedSegmentCount")
    )


def vad_complete(verification: dict) -> bool:
    duration_ms = int(verification.get("durationMs", 0))
    processed_ms = int(verification.get("vad", {}).get("processedDurationMs", -1))
    return 0 <= duration_ms - processed_ms < 20


def event_names(verification: dict) -> set[str]:
    return set(verification.get("lifecycle", {}).get("eventNames", []))


def lifecycle_events(verification: dict) -> list[dict]:
    return list(verification.get("lifecycle", {}).get("events", []))


def evaluate_case_b(device: dict, verification: dict, summary: dict) -> dict[str, bool]:
    events = event_names(verification)
    return {
        "s25Ultra": is_s25_ultra(device),
        "completedWithIntegrity": (
            verification.get("status") == "COMPLETED"
            and verification.get("checkpointErrorCode") is None
            and verification.get("readErrorCount", 0) == 0
            and verification.get("discontinuityCount", 0) == 0
            and verification.get("estimatedMissingFrames", 0) == 0
            and pcm_complete(verification)
            and vad_complete(verification)
        ),
        "forceStopWasRecoverable": (
            summary.get("checkpointStatusAfterForceStop") in {"RECORDING", "RECOVERING"}
            and int(summary.get("preCrashDurationMs", 0)) >= 60_000
        ),
        "recoveryLifecycleRecorded": {
            "STARTED",
            "RECOVERY_STARTED",
            "RECOVERED",
            "PAUSED",
            "RESUMED",
            "COMPLETED",
        }.issubset(events),
        "postRecoveryAudioAtLeast60Seconds": (
            int(summary.get("postRecoveryDurationMs", 0))
            - int(summary.get("recoveredDurationMs", 0))
            >= 60_000
        ),
        "preExistingSegmentsPreserved": (
            summary.get("preExistingSegmentsPreserved") is True
            and int(summary.get("protectedSegmentCount", 0)) >= 1
            and int(summary.get("finalSegmentCount", 0))
            > int(summary.get("protectedSegmentCount", 0))
        ),
    }


def evaluate_case_c(device: dict, verification: dict, summary: dict) -> dict[str, bool]:
    events = event_names(verification)
    interruption_events = [
        event
        for event in lifecycle_events(verification)
        if event.get("event") == "INTERRUPTED"
    ]
    recovery_required = summary.get("recoveryRequired") is True
    error_code = summary.get("interruptionErrorCode")
    explicit_interruption = (
        recovery_required
        and summary.get("statusAfterCall") == "RECOVERING"
        and error_code in {
            "AUDIO_CLIENT_SILENCED",
            "AUDIO_DEAD_OBJECT",
            "AUDIO_READ_FAILED",
        }
        and any(
            event.get("source") == "system"
            and event.get("errorCode") == error_code
            for event in interruption_events
        )
        and {"INTERRUPTED", "RECOVERY_STARTED", "RECOVERED"}.issubset(events)
        and int(verification.get("discontinuityCount", 0)) >= 1
    )
    explicit_continuity = (
        not recovery_required
        and summary.get("statusAfterCall") == "RECORDING"
        and "INTERRUPTED" not in events
        and int(verification.get("readErrorCount", 0)) == 0
        and int(verification.get("discontinuityCount", 0)) == 0
        and int(verification.get("estimatedMissingFrames", 0)) == 0
    )
    return {
        "s25Ultra": is_s25_ultra(device),
        "realAnsweredCallObserved": (
            any(int(item.get("state", -1)) == 2 for item in summary.get("callStates", []))
            and 15 <= float(summary.get("answeredCallDurationSeconds", 0)) <= 120
        ),
        "callOutcomeExplicit": explicit_interruption or explicit_continuity,
        "postCallAudioAtLeast60Seconds": (
            int(summary.get("postCallDurationMs", 0))
            - int(summary.get("preCallDurationMs", 0))
            >= 60_000
        ),
        "completedWithAccountedMetrics": (
            verification.get("status") == "COMPLETED"
            and verification.get("checkpointErrorCode") is None
            and pcm_complete(verification)
            and vad_complete(verification)
            and int(summary.get("finalReadErrorCount", -1))
            == int(verification.get("readErrorCount", 0))
            and int(summary.get("finalDiscontinuityCount", -1))
            == int(verification.get("discontinuityCount", 0))
            and int(summary.get("finalEstimatedMissingFrames", -1))
            == int(verification.get("estimatedMissingFrames", 0))
            and {"STARTED", "PAUSED", "RESUMED", "COMPLETED"}.issubset(events)
        ),
    }


def evaluate(
    case_a: dict,
    case_b_device: dict,
    case_b_verification: dict,
    case_b_summary: dict,
    case_c_device: dict,
    case_c_verification: dict,
    case_c_summary: dict,
) -> dict:
    case_b_checks = evaluate_case_b(case_b_device, case_b_verification, case_b_summary)
    case_c_checks = evaluate_case_c(case_c_device, case_c_verification, case_c_summary)
    checks = {
        "caseALongCapturePassed": case_a.get("automaticThresholdsMet") is True,
        "caseBRecoveryPassed": all(case_b_checks.values()),
        "caseCCallInterruptionPassed": all(case_c_checks.values()),
    }
    passed = all(checks.values())
    return {
        "schemaVersion": 1,
        "gate": "G1",
        "automaticChecks": checks,
        "caseBChecks": case_b_checks,
        "caseCChecks": case_c_checks,
        "automaticThresholdsMet": passed,
        "status": "ELIGIBLE_FOR_MANUAL_REVIEW" if passed else "AUTOMATIC_CHECKS_FAILED",
        "manualReviewRequired": [
            "Confirmar consentimiento y condiciones físicas de las tres sesiones.",
            "Revisar el intervalo de llamada y cualquier hueco explícitamente contabilizado.",
            "Emitir decisión CONTINUAR, AJUSTAR o DETENER; la herramienta nunca aprueba G1.",
        ],
        "approved": False,
        "contentIncluded": False,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--case-a-evaluation", required=True, type=Path)
    parser.add_argument("--case-b-evidence", required=True, type=Path)
    parser.add_argument("--case-b-summary", required=True, type=Path)
    parser.add_argument("--case-c-evidence", required=True, type=Path)
    parser.add_argument("--case-c-summary", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--fail-on-automatic-check", action="store_true")
    args = parser.parse_args()

    case_b_device, case_b_verification = read_device_evidence(args.case_b_evidence)
    case_c_device, case_c_verification = read_device_evidence(args.case_c_evidence)
    report = evaluate(
        read_json(args.case_a_evaluation),
        case_b_device,
        case_b_verification,
        read_json(args.case_b_summary),
        case_c_device,
        case_c_verification,
        read_json(args.case_c_summary),
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
