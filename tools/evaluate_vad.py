#!/usr/bin/env python3
"""Compare VAD timelines with human speech intervals."""

from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path


Interval = tuple[int, int]


def merge_intervals(intervals: list[Interval]) -> list[Interval]:
    merged: list[Interval] = []
    for start, end in sorted(intervals):
        if end <= start:
            continue
        if merged and start <= merged[-1][1]:
            merged[-1] = (merged[-1][0], max(merged[-1][1], end))
        else:
            merged.append((start, end))
    return merged


def duration(intervals: list[Interval]) -> int:
    return sum(end - start for start, end in merge_intervals(intervals))


def intersection_duration(left: list[Interval], right: list[Interval]) -> int:
    left = merge_intervals(left)
    right = merge_intervals(right)
    i = j = total = 0
    while i < len(left) and j < len(right):
        start = max(left[i][0], right[j][0])
        end = min(left[i][1], right[j][1])
        if end > start:
            total += end - start
        if left[i][1] <= right[j][1]:
            i += 1
        else:
            j += 1
    return total


def boundary_metrics(reference: list[Interval], detected: list[Interval]) -> tuple[float | None, float | None, float]:
    start_errors: list[int] = []
    end_errors: list[int] = []
    fragments = 0
    for ref_start, ref_end in reference:
        overlapping = [
            candidate for candidate in detected
            if min(ref_end, candidate[1]) > max(ref_start, candidate[0])
        ]
        fragments += len(overlapping)
        if not overlapping:
            continue
        best = max(overlapping, key=lambda item: min(ref_end, item[1]) - max(ref_start, item[0]))
        start_errors.append(abs(best[0] - ref_start))
        end_errors.append(abs(best[1] - ref_end))
    mean_start = sum(start_errors) / len(start_errors) if start_errors else None
    mean_end = sum(end_errors) / len(end_errors) if end_errors else None
    return mean_start, mean_end, fragments / len(reference) if reference else 0.0


def load_intervals(data: dict) -> list[Interval]:
    return [(int(item["startMs"]), int(item["endMs"])) for item in data.get("segments", [])]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--timelines-dir", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args()

    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    if manifest.get("consentConfirmed") is not True:
        raise ValueError("Corpus manifest must confirm consent before evaluation")
    rows: list[dict[str, object]] = []
    for item in manifest["items"]:
        reference_data = json.loads(
            (args.manifest.parent / item["vadReferenceFile"]).read_text(encoding="utf-8")
        )
        reference = merge_intervals(load_intervals(reference_data))
        timeline_files = sorted(args.timelines_dir.glob(f"{item['id']}--*.json"))
        if not timeline_files:
            raise FileNotFoundError(f"No VAD timelines for {item['id']}")
        for timeline_path in timeline_files:
            timeline = json.loads(timeline_path.read_text(encoding="utf-8"))
            detected = merge_intervals(load_intervals(timeline))
            reference_ms = duration(reference)
            detected_ms = duration(detected)
            true_positive_ms = intersection_duration(reference, detected)
            precision = true_positive_ms / detected_ms if detected_ms else (1.0 if reference_ms == 0 else 0.0)
            recall = true_positive_ms / reference_ms if reference_ms else 1.0
            f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
            mean_start, mean_end, fragments = boundary_metrics(reference, detected)
            rows.append(
                {
                    "itemId": item["id"],
                    "category": item["category"],
                    "engine": timeline["engine"],
                    "capturePipelineId": timeline.get("capturePipelineId", "unknown"),
                    "precision": precision,
                    "recall": recall,
                    "f1": f1,
                    "referenceSpeechMs": reference_ms,
                    "detectedSpeechMs": detected_ms,
                    "truePositiveMs": true_positive_ms,
                    "missedSpeechMs": reference_ms - true_positive_ms,
                    "falsePositiveMs": detected_ms - true_positive_ms,
                    "meanStartBoundaryErrorMs": mean_start,
                    "meanEndBoundaryErrorMs": mean_end,
                    "fragmentsPerReference": fragments,
                }
            )

    summaries: dict[str, dict[str, object]] = {}
    configurations = sorted({(str(row["engine"]), str(row["capturePipelineId"])) for row in rows})
    for engine, pipeline in configurations:
        selected = [row for row in rows if row["engine"] == engine and row["capturePipelineId"] == pipeline]
        reference_ms = sum(int(row["referenceSpeechMs"]) for row in selected)
        detected_ms = sum(int(row["detectedSpeechMs"]) for row in selected)
        true_positive_ms = sum(int(row["truePositiveMs"]) for row in selected)
        precision = true_positive_ms / detected_ms if detected_ms else (1.0 if reference_ms == 0 else 0.0)
        recall = true_positive_ms / reference_ms if reference_ms else 1.0
        summaries[f"{engine}|{pipeline}"] = {
            "engine": engine,
            "capturePipelineId": pipeline,
            "precision": precision,
            "recall": recall,
            "f1": 2 * precision * recall / (precision + recall) if precision + recall else 0.0,
            "referenceSpeechMs": reference_ms,
            "detectedSpeechMs": detected_ms,
            "truePositiveMs": true_positive_ms,
            "missedSpeechMs": reference_ms - true_positive_ms,
            "falsePositiveMs": detected_ms - true_positive_ms,
            "evaluatedItems": len(selected),
        }

    args.output_dir.mkdir(parents=True, exist_ok=True)
    report = {"schemaVersion": 1, "corpusId": manifest["corpusId"], "engines": summaries, "items": rows}
    (args.output_dir / "vad-evaluation.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    with (args.output_dir / "vad-evaluation.csv").open("w", encoding="utf-8", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
