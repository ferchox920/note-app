#!/usr/bin/env python3
"""Evaluate Note App ASR JSON files without external dependencies."""

from __future__ import annotations

import argparse
import csv
import json
import re
import unicodedata
from dataclasses import asdict, dataclass
from pathlib import Path


@dataclass(frozen=True)
class EditCounts:
    substitutions: int
    deletions: int
    insertions: int
    reference_units: int

    @property
    def rate(self) -> float | None:
        if self.reference_units == 0:
            return None
        return (self.substitutions + self.deletions + self.insertions) / self.reference_units


def normalize_words(text: str) -> list[str]:
    normalized = unicodedata.normalize("NFC", text).casefold()
    return re.findall(r"[^\W_]+(?:['’][^\W_]+)?", normalized, flags=re.UNICODE)


def edit_counts(reference: list[str], hypothesis: list[str]) -> EditCounts:
    rows = len(reference) + 1
    columns = len(hypothesis) + 1
    costs = [[0] * columns for _ in range(rows)]
    operations = [[""] * columns for _ in range(rows)]
    for i in range(1, rows):
        costs[i][0] = i
        operations[i][0] = "D"
    for j in range(1, columns):
        costs[0][j] = j
        operations[0][j] = "I"
    for i in range(1, rows):
        for j in range(1, columns):
            if reference[i - 1] == hypothesis[j - 1]:
                costs[i][j] = costs[i - 1][j - 1]
                operations[i][j] = "E"
                continue
            candidates = (
                (costs[i - 1][j - 1] + 1, "S"),
                (costs[i - 1][j] + 1, "D"),
                (costs[i][j - 1] + 1, "I"),
            )
            costs[i][j], operations[i][j] = min(candidates, key=lambda item: item[0])
    substitutions = deletions = insertions = 0
    i, j = len(reference), len(hypothesis)
    while i > 0 or j > 0:
        operation = operations[i][j]
        if operation in ("E", "S"):
            substitutions += operation == "S"
            i -= 1
            j -= 1
        elif operation == "D":
            deletions += 1
            i -= 1
        elif operation == "I":
            insertions += 1
            j -= 1
        else:
            raise ValueError("Unable to backtrack edit distance")
    return EditCounts(substitutions, deletions, insertions, len(reference))


def evaluate_pair(reference: str, hypothesis: str) -> tuple[EditCounts, EditCounts]:
    reference_words = normalize_words(reference)
    hypothesis_words = normalize_words(hypothesis)
    word_counts = edit_counts(reference_words, hypothesis_words)
    reference_chars = list(" ".join(reference_words).replace(" ", ""))
    hypothesis_chars = list(" ".join(hypothesis_words).replace(" ", ""))
    return word_counts, edit_counts(reference_chars, hypothesis_chars)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--results-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()

    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    if manifest.get("schemaVersion") != 1:
        raise ValueError("Unsupported corpus schema")
    if manifest.get("consentConfirmed") is not True:
        raise ValueError("Corpus manifest must confirm consent before evaluation")

    rows: list[dict[str, object]] = []
    for item in manifest["items"]:
        item_id = item["id"]
        reference_path = args.manifest.parent / item["referenceFile"]
        reference = reference_path.read_text(encoding="utf-8")
        result_files = sorted(args.results_dir.glob(f"{item_id}--*.json"))
        if not result_files:
            raise FileNotFoundError(f"No ASR results for {item_id}")
        for result_path in result_files:
            result = json.loads(result_path.read_text(encoding="utf-8"))
            hypothesis = result.get("transcript", "")
            capture_pipeline_id = result.get("capturePipelineId", "unknown")
            word_counts, char_counts = evaluate_pair(reference, hypothesis)
            rows.append(
                {
                    "itemId": item_id,
                    "category": item["category"],
                    "modelId": result["modelId"],
                    "capturePipelineId": capture_pipeline_id,
                    "wer": word_counts.rate,
                    "cer": char_counts.rate,
                    "wordSubstitutions": word_counts.substitutions,
                    "wordDeletions": word_counts.deletions,
                    "wordInsertions": word_counts.insertions,
                    "referenceWords": word_counts.reference_units,
                    "charSubstitutions": char_counts.substitutions,
                    "charDeletions": char_counts.deletions,
                    "charInsertions": char_counts.insertions,
                    "referenceChars": char_counts.reference_units,
                    "hallucinatedSilence": item["category"] == "silence" and bool(normalize_words(hypothesis)),
                    "rtf": result.get("realTimeFactor"),
                    "timeToFirstTextMs": result.get("timeToFirstTextMs"),
                    "peakPssKb": result.get("peakPssKb"),
                }
            )

    summaries: dict[str, dict[str, object]] = {}
    configurations = sorted({(str(row["modelId"]), str(row["capturePipelineId"])) for row in rows})
    for model_id, capture_pipeline_id in configurations:
        model_rows = [
            row for row in rows
            if row["modelId"] == model_id and row["capturePipelineId"] == capture_pipeline_id
        ]
        reference_words = sum(int(row["referenceWords"]) for row in model_rows)
        errors = sum(
            int(row["wordSubstitutions"]) + int(row["wordDeletions"]) + int(row["wordInsertions"])
            for row in model_rows
        )
        reference_chars = sum(int(row["referenceChars"]) for row in model_rows)
        char_errors = sum(
            int(row["charSubstitutions"]) + int(row["charDeletions"]) + int(row["charInsertions"])
            for row in model_rows
        )
        rtfs = [float(row["rtf"]) for row in model_rows if row["rtf"] is not None]
        configuration_id = f"{model_id}|{capture_pipeline_id}"
        summaries[configuration_id] = {
            "modelId": model_id,
            "capturePipelineId": capture_pipeline_id,
            "wer": errors / reference_words if reference_words else None,
            "referenceWords": reference_words,
            "wordErrors": errors,
            "cer": char_errors / reference_chars if reference_chars else None,
            "referenceChars": reference_chars,
            "charErrors": char_errors,
            "meanRtf": sum(rtfs) / len(rtfs) if rtfs else None,
            "silenceHallucinationCount": sum(bool(row["hallucinatedSilence"]) for row in model_rows),
            "evaluatedItems": len(model_rows),
        }

    args.output_dir.mkdir(parents=True, exist_ok=True)
    report = {
        "schemaVersion": 1,
        "corpusId": manifest["corpusId"],
        "language": manifest["language"],
        "models": summaries,
        "items": rows,
    }
    (args.output_dir / "asr-evaluation.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    with (args.output_dir / "asr-evaluation.csv").open("w", encoding="utf-8", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
