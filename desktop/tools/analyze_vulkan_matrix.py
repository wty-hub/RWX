#!/usr/bin/env python3
"""Recompute exact 30-second acceptance intervals from the buffered raw frame CSV."""

import argparse
import bisect
import csv
import json
from pathlib import Path


def percentile(values: list[float], fraction: float) -> float | None:
    ordered = sorted(values)
    return ordered[int((len(ordered) - 1) * fraction)] if ordered else None


def presentation_window(trace: list[tuple[int, int, int]], sample: dict) -> dict:
    start, end = sample["sampleStartNanos"], sample["sampleEndNanos"]
    if end <= start:
        raise ValueError("Sample end must follow its start")
    times = [row[0] for row in trace]
    if any(a > b for a, b in zip(times, times[1:])):
        raise ValueError("Accepted presentation timestamps must be ordered")
    first, last = bisect.bisect_left(times, start), bisect.bisect_right(times, end)
    count = last - first
    fresh = sum(i == 0 or trace[i][1:] != trace[i - 1][1:] for i in range(first, last))
    intervals = [(trace[i][0] - trace[i - 1][0]) / 1e6 for i in range(max(first, 1), last)]
    seconds = (end - start) / 1e9
    return {**sample, "acceptedPresentFps": count / seconds,
            "freshSnapshotHz": fresh / seconds,
            "repeatRatio": (count - fresh) / count if count else None,
            "presentIntervalP95Ms": percentile(intervals, .95),
            "presentIntervalP99Ms": percentile(intervals, .99),
            "presentIntervalMaxMs": max(intervals) if intervals else None}


def read_json_lines(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line]


def analyze(directory: Path) -> dict:
    entries = read_json_lines(directory / "vulkan-native-matrix-manifest.ndjson")
    report = []
    for run in (entry for entry in entries if entry.get("kind") == "run"):
        name = run["name"]
        trace = []
        with (directory / f"{name}-trace.csv").open(encoding="utf-8") as stream:
            for index, row in enumerate(csv.reader(stream)):
                if index == 0 and row and not row[0].isdigit():
                    continue
                if len(row) != 3:
                    raise ValueError(f"Malformed frame row {index + 1} in {name}")
                trace.append(tuple(map(int, row)))
        scenario = read_json_lines(directory / f"{name}-scenario.ndjson")
        windows = [presentation_window(trace, sample) for sample in scenario if sample.get("kind") == "engine-window"]
        setup = scenario[0] if scenario else {}
        report.append({**run, "viewportWidth": setup.get("viewportWidth"),
                       "viewportHeight": setup.get("viewportHeight"),
                       "representedTypes": len(setup.get("initialRepresentedTypes", {})),
                       "windows": windows,
                       "frameWindows": read_json_lines(directory / f"{name}-frame.jsonl"),
                       "nativeWindows": read_json_lines(directory / f"{name}-native.jsonl")})
    return {"note": "Accepted queue-present intervals; physical scanout is not measured. Combat casualties and idle capacity are separate.",
            "quantileMethod": "sorted_values[int((sample_count - 1) * fraction)]",
            "runtime": next((entry for entry in entries if entry.get("kind") == "runtime"), None),
            "runs": report}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    report = analyze(args.directory)
    target = args.output or args.directory / "vulkan-native-matrix-summary.json"
    target.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    for run in report["runs"]:
        print(run["name"], "exit", run["exitCode"], "viewport", run["viewportWidth"], run["viewportHeight"])
        for window in run["windows"]:
            print(json.dumps({key: window.get(key) for key in (
                "repetition", "minimumLivingUnits", "engineFps", "acceptedPresentFps", "freshSnapshotHz",
                "repeatRatio", "presentIntervalP95Ms", "presentIntervalP99Ms", "eligible2000CombatWindow", "eligible2000IdleWindow")}))


if __name__ == "__main__":
    main()
