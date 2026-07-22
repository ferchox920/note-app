#!/usr/bin/env python3
"""Evaluate sanitized G0-G2 evidence without pretending to replace manual approval."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


TINY_MODEL = "whisper-tiny-multilingual-q5_1"
BASE_MODEL = "whisper-base-multilingual-q5_1"


def is_s25_ultra(device: dict) -> bool:
    model = str(device.get("productModel", ""))
    device_name = str(device.get("productDevice", ""))
    return bool(re.search(r"SM-S938|S25\s*Ultra", f"{model} {device_name}", re.IGNORECASE))


def pcm_complete(verification: dict) -> bool:
    pcm = verification.get("pcm", {})
    return (
        not pcm.get("unlistedPcm")
        and pcm.get("actualTotalBytes") == verification.get("totalBytes")
        and pcm.get("segmentCount") == pcm.get("listedSegmentCount")
    )


def capture_clean(verification: dict) -> bool:
    return (
        verification.get("status") == "COMPLETED"
        and verification.get("checkpointErrorCode") is None
        and verification.get("readErrorCount", 0) == 0
        and verification.get("discontinuityCount", 0) == 0
        and verification.get("estimatedMissingFrames", 0) == 0
        and pcm_complete(verification)
    )


def result(name: str, checks: dict[str, bool], manual_review: list[str]) -> dict[str, object]:
    passed = all(checks.values())
    return {
        "gate": name,
        "automaticChecks": checks,
        "automaticThresholdsMet": passed,
        "status": "ELIGIBLE_FOR_MANUAL_REVIEW" if passed else "AUTOMATIC_CHECKS_FAILED",
        "manualReviewRequired": manual_review,
        "approved": False,
    }


def evaluate_g0(device: dict, verification: dict) -> dict[str, object]:
    asr_by_model = {item["modelId"]: item for item in verification.get("asr", [])}
    required = [asr_by_model.get(TINY_MODEL), asr_by_model.get(BASE_MODEL)]
    valid_required = [item for item in required if item is not None]
    checks = {
        "s25Ultra": is_s25_ultra(device),
        "authorizedSessionAtLeast5Minutes": verification.get("durationMs", 0) >= 5 * 60 * 1_000,
        "captureIntegrity": capture_clean(verification),
        "vadProducedSpeechSegments": verification.get("vad", {}).get("segmentCount", 0) > 0,
        "tinyAndBaseCompleted": len(valid_required) == 2 and all(item.get("chunkCount", 0) > 0 for item in valid_required),
        "atLeastOneRtfAtMost1": bool(valid_required) and any(item.get("realTimeFactor", float("inf")) <= 1.0 for item in valid_required),
        "performanceTelemetryPresent": len(valid_required) == 2 and all(
            item.get("peakPssKb") is not None and item.get("maximumThermalStatus") is not None
            for item in valid_required
        ),
    }
    return result(
        "G0",
        checks,
        [
            "Confirmar que lectura, conversación, ruido y silencio fueron autorizados.",
            "Revisar calidad/utilidad del texto tiny y base, no solo RTF.",
            "Revisar temperatura, throttling y cualquier incidente observado.",
        ],
    )


def evaluate_g1(device: dict, verification: dict) -> dict[str, object]:
    processed_ms = verification.get("vad", {}).get("processedDurationMs", -1)
    duration_ms = verification.get("durationMs", 0)
    checks = {
        "s25Ultra": is_s25_ultra(device),
        "durationAtLeast90Minutes": verification.get("durationMs", 0) >= 90 * 60 * 1_000,
        "captureIntegrity": capture_clean(verification),
        "vadCoveredRecordedTimeline": 0 <= duration_ms - processed_ms < 20,
    }
    return result(
        "G1",
        checks,
        [
            "Confirmar pantalla apagada durante el intervalo documentado.",
            "Confirmar pausa/reanudación, checkpoint recuperable y notificación.",
            "Revisar llamadas/interrupciones y duración de pared frente a PCM.",
        ],
    )


def evaluate_g2(device: dict, verification: dict) -> dict[str, object]:
    incremental = verification.get("incremental") or {}
    checks = {
        "s25Ultra": is_s25_ultra(device),
        "durationAtLeast45Minutes": verification.get("durationMs", 0) >= 45 * 60 * 1_000,
        "captureRemainedCleanUnderLoad": capture_clean(verification),
        "incrementalEvidencePresent": incremental.get("metricCount", 0) > 0,
        "timeToFirstTextAtMost4s": incremental.get("timeToFirstTextMs") is not None
        and incremental["timeToFirstTextMs"] <= 4_000,
        "visibleLatencyP95AtMost6s": incremental.get("visibleLatencyP95Ms") is not None
        and incremental["visibleLatencyP95Ms"] <= 6_000,
        "weightedRtfAtMost1": incremental.get("weightedRealTimeFactor") is not None
        and incremental["weightedRealTimeFactor"] <= 1.0,
        "noIncrementalTechnicalError": incremental.get("errorCode") is None,
        "noStablePrefixConflicts": incremental.get("stableConflictCount", 0) == 0,
    }
    return result(
        "G2",
        checks,
        [
            "Revisar manualmente duplicaciones graves y reescrituras visibles.",
            "Confirmar estabilidad de captura, UI y pantalla apagada durante 45 minutos.",
            "Evaluar calidad en español y si los descartes parciales fueron sostenidos.",
        ],
    )


def load_evidence(directory: Path) -> tuple[dict, dict]:
    device_path = directory / "device.json"
    verification_path = directory / "verification.json"
    if not device_path.is_file() or not verification_path.is_file():
        raise FileNotFoundError(f"Expected device.json and verification.json in {directory}")
    return (
        json.loads(device_path.read_text(encoding="utf-8-sig")),
        json.loads(verification_path.read_text(encoding="utf-8-sig")),
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--g0-evidence", required=True, type=Path)
    parser.add_argument("--g1-evidence", required=True, type=Path)
    parser.add_argument("--g2-evidence", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--fail-on-automatic-check", action="store_true")
    args = parser.parse_args()

    g0 = evaluate_g0(*load_evidence(args.g0_evidence))
    g1 = evaluate_g1(*load_evidence(args.g1_evidence))
    g2 = evaluate_g2(*load_evidence(args.g2_evidence))
    gates = [g0, g1, g2]
    report = {
        "schemaVersion": 1,
        "status": "MANUAL_APPROVAL_REQUIRED",
        "note": "Automatic checks never approve a gate.",
        "gates": gates,
        "allEligibleForManualReview": all(gate["automaticThresholdsMet"] for gate in gates),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    if args.fail_on_automatic_check and not report["allEligibleForManualReview"]:
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
