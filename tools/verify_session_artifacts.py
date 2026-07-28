#!/usr/bin/env python3
"""Validate a private Note App session without emitting transcript or audio content."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def percentile(values: list[float], probability: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    position = (len(ordered) - 1) * probability
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    weight = position - lower
    return ordered[lower] * (1.0 - weight) + ordered[upper] * weight


def validate_segments(session_dir: Path, checkpoint: dict, allow_unlisted_pcm: bool) -> dict[str, object]:
    expected_offset = 0
    listed_names: set[str] = set()
    checksums: list[dict[str, object]] = []
    for expected_sequence, segment in enumerate(checkpoint.get("segments", [])):
        require(segment["sequence"] == expected_sequence, "PCM segment sequence is not contiguous")
        require(segment["startByteOffset"] == expected_offset, "PCM start offset is not contiguous")
        require(segment["endByteOffset"] == expected_offset + segment["byteCount"], "PCM end offset is invalid")
        require(segment["byteCount"] > 0 and segment["byteCount"] % 2 == 0, "PCM byte count must be positive and even")
        file_name = segment["fileName"]
        require(Path(file_name).name == file_name, "PCM file name must not contain a path")
        path = session_dir / file_name
        require(path.is_file(), f"Missing PCM file: {file_name}")
        require(path.stat().st_size == segment["byteCount"], f"PCM size mismatch: {file_name}")
        actual_sha = sha256(path)
        require(actual_sha == segment["sha256"], f"PCM checksum mismatch: {file_name}")
        listed_names.add(file_name)
        checksums.append({"fileName": file_name, "byteCount": segment["byteCount"], "sha256": actual_sha})
        expected_offset = segment["endByteOffset"]

    pcm_names = {path.name for path in session_dir.glob("segment-*.pcm") if path.is_file()}
    unlisted = sorted(pcm_names - listed_names)
    require(allow_unlisted_pcm or not unlisted, f"Unlisted PCM files: {', '.join(unlisted)}")
    actual_total_bytes = expected_offset
    if allow_unlisted_pcm:
        for expected_sequence, file_name in enumerate(unlisted, start=len(listed_names)):
            require(file_name == f"segment-{expected_sequence:04d}.pcm", "Unlisted PCM sequence is not contiguous")
            path = session_dir / file_name
            byte_count = path.stat().st_size
            require(byte_count > 0 and byte_count % 2 == 0, "Unlisted PCM must be positive and even")
            checksums.append({"fileName": file_name, "byteCount": byte_count, "sha256": sha256(path), "listed": False})
            actual_total_bytes += byte_count
        require(
            expected_offset <= checkpoint["totalBytes"] <= actual_total_bytes,
            "Checkpoint totalBytes is outside listed and recovered PCM bounds",
        )
    else:
        require(checkpoint["totalBytes"] == expected_offset, "Checkpoint totalBytes does not match listed PCM")
    return {
        "segmentCount": len(pcm_names),
        "listedSegmentCount": len(listed_names),
        "actualTotalBytes": actual_total_bytes,
        "checksums": checksums,
        "unlistedPcm": unlisted,
    }


def validate_vad(session_dir: Path, checkpoint: dict) -> dict[str, object]:
    path = session_dir / "vad-segments.json"
    require(path.is_file(), "Missing vad-segments.json")
    timeline = json.loads(path.read_text(encoding="utf-8"))
    require(timeline.get("sessionId") == checkpoint["sessionId"], "VAD sessionId mismatch")
    require(timeline.get("sampleRateHz") == checkpoint["sampleRateHz"], "VAD sample rate mismatch")
    previous_end_ms = 0
    previous_end_byte = 0
    for expected_sequence, segment in enumerate(timeline.get("segments", [])):
        require(segment["sequence"] == expected_sequence, "VAD sequence is not contiguous")
        require(0 <= segment["startMs"] < segment["endMs"], "Invalid VAD time interval")
        require(segment["startMs"] >= previous_end_ms, "VAD time intervals overlap")
        require(0 <= segment["startByteOffset"] < segment["endByteOffset"], "Invalid VAD byte interval")
        require(segment["startByteOffset"] >= previous_end_byte, "VAD byte intervals overlap")
        require(segment["endByteOffset"] <= checkpoint["totalBytes"], "VAD byte interval exceeds PCM")
        previous_end_ms = segment["endMs"]
        previous_end_byte = segment["endByteOffset"]
    require(timeline["processedDurationMs"] <= checkpoint["durationMs"], "VAD processed duration exceeds audio")
    return {
        "engine": timeline.get("engine"),
        "segmentCount": len(timeline.get("segments", [])),
        "processedDurationMs": timeline["processedDurationMs"],
    }


def validate_asr_results(session_dir: Path, required_models: list[str]) -> list[dict[str, object]]:
    results: list[dict[str, object]] = []
    found_models: set[str] = set()
    for path in sorted(session_dir.glob("asr-result-*.json")):
        data = json.loads(path.read_text(encoding="utf-8"))
        model_id = data["modelId"]
        found_models.add(model_id)
        previous_start = 0
        for segment in data.get("segments", []):
            require(0 <= segment["startMs"] <= segment["endMs"], f"Invalid ASR timestamps for {model_id}")
            require(segment["startMs"] >= previous_start, f"ASR timestamps are out of order for {model_id}")
            previous_start = segment["startMs"]
        require(data["audioDurationMs"] >= 0 and data["inferenceDurationMs"] >= 0, "Invalid ASR duration")
        require(math.isfinite(data["realTimeFactor"]) and data["realTimeFactor"] >= 0, "Invalid ASR RTF")
        results.append({
            "modelId": model_id,
            "benchmarkConfigId": data.get("benchmarkConfigId"),
            "threadCount": data.get("threadCount"),
            "maxChunkMs": data.get("maxChunkMs"),
            "chunkCount": data["chunkCount"],
            "segmentCount": len(data.get("segments", [])),
            "audioDurationMs": data["audioDurationMs"],
            "inferenceDurationMs": data["inferenceDurationMs"],
            "realTimeFactor": data["realTimeFactor"],
            "timeToFirstTextMs": data.get("timeToFirstTextMs"),
            "peakPssKb": data.get("peakPssKb"),
            "maximumThermalStatus": data.get("maximumThermalStatus"),
            "maximumBatteryTemperatureC": data.get("maximumBatteryTemperatureC"),
            "nativeSystemInfo": data.get("nativeSystemInfo"),
            "nativeTimings": data.get("nativeTimings"),
        })
    missing = sorted(set(required_models) - found_models)
    require(not missing, f"Missing required ASR models: {', '.join(missing)}")
    return results


def validate_incremental(session_dir: Path, required: bool) -> dict[str, object] | None:
    path = session_dir / "incremental-transcript.json"
    if not path.is_file():
        require(not required, "Missing incremental-transcript.json")
        return None
    data = json.loads(path.read_text(encoding="utf-8"))
    metrics = data.get("inferenceMetrics", [])
    require(not required or metrics, "Incremental evidence has no inference metrics")
    for sequence, metric in enumerate(metrics):
        require(metric["sequence"] == sequence, "Incremental metric sequence is not contiguous")
        require(0 <= metric["windowStartMs"] < metric["windowEndMs"], "Invalid incremental window")
        require(metric["audioDurationMs"] > 0, "Incremental audio duration must be positive")
        require(metric["inferenceDurationMs"] >= 0, "Incremental inference duration is negative")
        require(metric["visibleLatencyMs"] >= 0, "Incremental visible latency is negative")
        require(math.isfinite(metric["realTimeFactor"]) and metric["realTimeFactor"] >= 0, "Invalid incremental RTF")
    partial_latencies = [float(metric["visibleLatencyMs"]) for metric in metrics if not metric["final"]]
    total_audio_ms = sum(int(metric["audioDurationMs"]) for metric in metrics)
    total_inference_ms = sum(int(metric["inferenceDurationMs"]) for metric in metrics)
    previous_end = 0
    for segment in data.get("segments", []):
        require(0 <= segment["startMs"] < segment["endMs"], "Invalid incremental segment")
        require(segment["startMs"] >= previous_end, "Incremental segments overlap")
        previous_end = segment["endMs"]
    return {
        "modelId": data["modelId"],
        "partialCount": data.get("partialCount", 0),
        "metricCount": len(metrics),
        "finalizedSegmentCount": len(data.get("segments", [])),
        "droppedPartialCount": data.get("droppedPartialCount", 0),
        "stableConflictCount": data.get("stableConflictCount", 0),
        "timeToFirstTextMs": data.get("timeToFirstTextMs"),
        "errorCode": data.get("errorCode"),
        "visibleLatencyP50Ms": percentile(partial_latencies, 0.50),
        "visibleLatencyP95Ms": percentile(partial_latencies, 0.95),
        "weightedRealTimeFactor": total_inference_ms / total_audio_ms if total_audio_ms else None,
    }


def verify_session(
    session_dir: Path,
    expected_status: str = "COMPLETED",
    allow_unlisted_pcm: bool = False,
    required_models: list[str] | None = None,
    require_incremental: bool = False,
) -> dict[str, object]:
    checkpoint_path = session_dir / "checkpoint.json"
    require(checkpoint_path.is_file(), "Missing checkpoint.json")
    checkpoint = json.loads(checkpoint_path.read_text(encoding="utf-8"))
    require(checkpoint.get("schemaVersion") == 1, "Unsupported checkpoint schema")
    require(checkpoint["status"] == expected_status, f"Expected status {expected_status}, found {checkpoint['status']}")
    require(checkpoint["bitsPerSample"] == 16 and checkpoint["channelCount"] == 1, "Expected mono PCM16")
    bytes_per_second = checkpoint["sampleRateHz"] * checkpoint["channelCount"] * checkpoint["bitsPerSample"] // 8
    expected_duration = checkpoint["totalBytes"] * 1_000 // bytes_per_second
    require(checkpoint["durationMs"] == expected_duration, "Checkpoint duration does not match PCM format")
    pcm = validate_segments(session_dir, checkpoint, allow_unlisted_pcm)
    vad = validate_vad(session_dir, checkpoint)
    asr = validate_asr_results(session_dir, required_models or [])
    incremental = validate_incremental(session_dir, require_incremental)
    return {
        "schemaVersion": 1,
        "sessionId": checkpoint["sessionId"],
        "status": checkpoint["status"],
        "capturePipelineId": checkpoint.get("capturePipeline", "unknown"),
        "sampleRateHz": checkpoint["sampleRateHz"],
        "durationMs": checkpoint["durationMs"],
        "totalBytes": checkpoint["totalBytes"],
        "readErrorCount": checkpoint.get("readErrorCount", 0),
        "discontinuityCount": checkpoint.get("discontinuityCount", 0),
        "estimatedMissingFrames": checkpoint.get("estimatedMissingFrames", 0),
        "checkpointErrorCode": checkpoint.get("errorCode"),
        "pcm": pcm,
        "vad": vad,
        "asr": asr,
        "incremental": incremental,
        "contentIncluded": False,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--session-dir", required=True, type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--expected-status", default="COMPLETED")
    parser.add_argument("--allow-unlisted-pcm", action="store_true")
    parser.add_argument("--require-asr-model", action="append", default=[])
    parser.add_argument("--require-incremental", action="store_true")
    args = parser.parse_args()
    report = verify_session(
        args.session_dir,
        expected_status=args.expected_status,
        allow_unlisted_pcm=args.allow_unlisted_pcm,
        required_models=args.require_asr_model,
        require_incremental=args.require_incremental,
    )
    rendered = json.dumps(report, ensure_ascii=False, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8")
    else:
        print(rendered)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
