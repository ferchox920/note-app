#!/usr/bin/env python3
"""Summarize incremental-ASR latency/backpressure evidence for the G2 review."""

from __future__ import annotations

import argparse
import csv
import json
import math
import re
from pathlib import Path


def percentile(values: list[float], probability: float) -> float | None:
    if not values:
        return None
    if not 0.0 <= probability <= 1.0:
        raise ValueError("probability must be between zero and one")
    ordered = sorted(values)
    position = (len(ordered) - 1) * probability
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    weight = position - lower
    return ordered[lower] * (1.0 - weight) + ordered[upper] * weight


def covered_audio_duration_ms(metrics: list[dict]) -> int:
    """Return unique timeline coverage so overlapping windows are not double-counted."""
    if not metrics:
        return 0
    if not all("windowStartMs" in item and "windowEndMs" in item for item in metrics):
        return sum(int(item["audioDurationMs"]) for item in metrics)
    intervals = sorted(
        (int(item["windowStartMs"]), int(item["windowEndMs"]))
        for item in metrics
    )
    if any(start < 0 or end <= start for start, end in intervals):
        raise ValueError("Incremental inference metrics contain an invalid window interval")
    covered = 0
    current_start, current_end = intervals[0]
    for start, end in intervals[1:]:
        if start <= current_end:
            current_end = max(current_end, end)
        else:
            covered += current_end - current_start
            current_start, current_end = start, end
    return covered + current_end - current_start


def normalized_words(text: str) -> list[str]:
    return re.findall(r"[^\W_]+", text.lower(), flags=re.UNICODE)


def word_error_rate(reference: str, hypothesis: str) -> float | None:
    expected = normalized_words(reference)
    actual = normalized_words(hypothesis)
    if not expected:
        return None
    previous = list(range(len(actual) + 1))
    for row, expected_word in enumerate(expected, start=1):
        current = [row]
        for column, actual_word in enumerate(actual, start=1):
            current.append(min(
                current[-1] + 1,
                previous[column] + 1,
                previous[column - 1] + (expected_word != actual_word),
            ))
        previous = current
    return previous[-1] / len(expected)


def summarize_session(
    data: dict,
    source: str = "",
    reference_text: str | None = None,
) -> dict[str, object]:
    metrics = data.get("inferenceMetrics", [])
    if not metrics:
        raise ValueError(f"No incremental inference metrics in {source or 'input'}")
    partials = [item for item in metrics if not item["final"]]
    visible = [float(item["visibleLatencyMs"]) for item in partials]
    inference = [float(item["inferenceDurationMs"]) for item in metrics]
    window_audio_ms = sum(int(item["audioDurationMs"]) for item in metrics)
    covered_audio_ms = covered_audio_duration_ms(metrics)
    inference_ms = sum(int(item["inferenceDurationMs"]) for item in metrics)
    first_text = data.get("timeToFirstTextMs")
    p95_visible = percentile(visible, 0.95)
    weighted_rtf = inference_ms / covered_audio_ms if covered_audio_ms else math.inf
    error_code = data.get("errorCode")
    automatic_thresholds = {
        "firstTextWithin4s": first_text is not None and int(first_text) <= 4_000,
        "visibleLatencyP95Within6s": p95_visible is not None and p95_visible <= 6_000,
        "sustainedRtfAtMost1": weighted_rtf <= 1.0,
        "noTechnicalError": error_code in (None, ""),
    }
    native_timing_totals = {
        name: sum(
            float(item.get("nativeTimings", {}).get(name, 0.0))
            for item in metrics
            if not item.get("reusedResult", False)
        )
        for name in ("sampleMs", "encodeMs", "decodeMs", "batchMs", "promptMs")
    }
    wer = word_error_rate(reference_text, data.get("stableText", "")) if reference_text else None
    return {
        "source": source,
        "modelId": data["modelId"],
        "capturePipelineId": data.get("capturePipelineId", "unknown"),
        "windowCount": len(metrics),
        "partialCount": len(partials),
        "finalCount": sum(1 for item in metrics if item["final"]),
        "timeToFirstTextMs": first_text,
        "visibleLatencyP50Ms": percentile(visible, 0.50),
        "visibleLatencyP95Ms": p95_visible,
        "inferenceDurationP50Ms": percentile(inference, 0.50),
        "inferenceDurationP95Ms": percentile(inference, 0.95),
        "windowAudioDurationMs": window_audio_ms,
        "coveredAudioDurationMs": covered_audio_ms,
        "totalInferenceDurationMs": inference_ms,
        "reusedResultCount": sum(1 for item in metrics if item.get("reusedResult") is True),
        "nativeTimingTotalsMs": native_timing_totals,
        "weightedRealTimeFactor": weighted_rtf,
        "droppedPartialCount": int(data.get("droppedPartialCount", 0)),
        "stableConflictCount": int(data.get("stableConflictCount", 0)),
        "errorCode": error_code,
        "automaticThresholds": automatic_thresholds,
        "eligibleForManualG2Review": all(automatic_thresholds.values()),
        "wordErrorRate": wer,
    }


def aggregate_sessions(rows: list[dict[str, object]]) -> dict[str, dict[str, object]]:
    grouped: dict[tuple[str, str], list[dict[str, object]]] = {}
    for row in rows:
        key = (str(row["modelId"]), str(row["capturePipelineId"]))
        grouped.setdefault(key, []).append(row)
    result: dict[str, dict[str, object]] = {}
    for (model, pipeline), selected in sorted(grouped.items()):
        first_text = [float(row["timeToFirstTextMs"]) for row in selected if row["timeToFirstTextMs"] is not None]
        visible_p95 = [float(row["visibleLatencyP95Ms"]) for row in selected if row["visibleLatencyP95Ms"] is not None]
        word_error_rates = [
            float(row["wordErrorRate"])
            for row in selected
            if row.get("wordErrorRate") is not None
        ]
        result[f"{model}|{pipeline}"] = {
            "modelId": model,
            "capturePipelineId": pipeline,
            "sessionCount": len(selected),
            "timeToFirstTextP50Ms": percentile(first_text, 0.50),
            "timeToFirstTextP95Ms": percentile(first_text, 0.95),
            "worstSessionVisibleLatencyP95Ms": max(visible_p95) if visible_p95 else None,
            "worstSessionWeightedRealTimeFactor": max(float(row["weightedRealTimeFactor"]) for row in selected),
            "droppedPartialCount": sum(int(row["droppedPartialCount"]) for row in selected),
            "stableConflictCount": sum(int(row["stableConflictCount"]) for row in selected),
            "allEligibleForManualG2Review": all(bool(row["eligibleForManualG2Review"]) for row in selected),
            "worstSessionWordErrorRate": max(word_error_rates) if word_error_rates else None,
        }
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--results-dir", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--consent-confirmed", required=True, action="store_true")
    parser.add_argument("--reference-text", type=Path)
    args = parser.parse_args()
    reference_text = (
        args.reference_text.read_text(encoding="utf-8-sig")
        if args.reference_text is not None
        else None
    )

    candidates = sorted(args.results_dir.rglob("*.json"))
    rows: list[dict[str, object]] = []
    for path in candidates:
        # PowerShell-generated verification manifests may include an UTF-8 BOM.
        # utf-8-sig accepts both forms and keeps the recursive evidence scan portable.
        data = json.loads(path.read_text(encoding="utf-8-sig"))
        if "inferenceMetrics" in data:
            rows.append(summarize_session(data, str(path), reference_text))
    if not rows:
        raise FileNotFoundError("No incremental transcript result JSON files found")

    args.output_dir.mkdir(parents=True, exist_ok=True)
    report = {
        "schemaVersion": 3,
        "note": "Automatic thresholds do not approve G2; manual duplication and capture-stability review remains required.",
        "configurations": aggregate_sessions(rows),
        "sessions": rows,
    }
    (args.output_dir / "incremental-evaluation.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    flat_rows = [{key: value for key, value in row.items() if key != "automaticThresholds"} for row in rows]
    with (args.output_dir / "incremental-evaluation.csv").open("w", encoding="utf-8", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=list(flat_rows[0].keys()))
        writer.writeheader()
        writer.writerows(flat_rows)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
