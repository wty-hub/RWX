#!/usr/bin/env python3
"""Compare original and RWX INIs without changing either asset directory."""
import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path


def digest(data):
    return hashlib.sha256(data).hexdigest()


def normalized_records(data):
    records = []
    for source_line in data.decode("utf-8-sig").splitlines():
        line = source_line.strip()
        if not line or line.startswith(("#", ";")):
            continue
        if line.startswith("[") and line.endswith("]"):
            records.append(["section", line[1:-1].strip()])
            continue
        positions = [line.find(separator) for separator in (":", "=") if separator in line]
        if positions:
            position = min(positions)
            records.append(["property", line[:position].strip(), line[position + 1:].strip()])
        else:
            # Preserve unsupported syntax rather than silently discarding it.
            records.append(["literal", line])
    return records


def inventory(directory):
    return {path.relative_to(directory).as_posix(): path.read_bytes()
            for path in directory.rglob("*.ini")}


def manifest_hash(entries):
    return digest(json.dumps(entries, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))


def compare(original_directory, rwx_directory):
    original = inventory(original_directory)
    rwx = inventory(rwx_directory)
    shared = sorted(original.keys() & rwx.keys())
    original_records = {path: normalized_records(original[path]) for path in shared}
    rwx_records = {path: normalized_records(rwx[path]) for path in shared}
    mismatches = [path for path in shared if original_records[path] != rwx_records[path]]
    normalized_hash = lambda records: manifest_hash([[path, records[path]] for path in shared])
    raw_hash = lambda files: manifest_hash([[path, digest(files[path])] for path in sorted(files)])
    return {
        "verification": "original-1.15-built-in-ini-values",
        "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
        "originalDirectory": str(original_directory.resolve()),
        "rwxDirectory": str(rwx_directory.resolve()),
        "scope": "All relative *.ini paths under assets/units; ordered section/key/value records, not engine bytecode or every possible unit action.",
        "normalization": {
            "ignored": ["UTF-8 BOM", "line-ending style", "blank lines", "full-line # and ; comments",
                        "leading/trailing line, section, key and value whitespace", "colon/equal property separator style"],
            "preserved": ["relative file paths", "section and property order", "duplicate properties", "key/value case",
                          "numeric literals", "interior value whitespace", "inline comments and color literals", "unsupported non-empty lines"],
        },
        "originalFileCount": len(original),
        "rwxFileCount": len(rwx),
        "comparedFileCount": len(shared),
        "sameRelativePaths": original.keys() == rwx.keys(),
        "onlyOriginalPaths": sorted(original.keys() - rwx.keys()),
        "onlyRwxPaths": sorted(rwx.keys() - original.keys()),
        "rawByteDifferenceCount": sum(original[path] != rwx[path] for path in shared),
        "normalizedMatchCount": len(shared) - len(mismatches),
        "normalizedMismatchCount": len(mismatches),
        "normalizedMismatchPaths": mismatches,
        "orderedRecordCount": sum(len(records) for records in original_records.values()),
        "preservedLiteralRecordCount": sum(record[0] == "literal" for records in original_records.values() for record in records),
        "relativePathManifestSha256": manifest_hash(shared),
        "originalRawManifestSha256": raw_hash(original),
        "rwxRawManifestSha256": raw_hash(rwx),
        "originalNormalizedManifestSha256": normalized_hash(original_records),
        "rwxNormalizedManifestSha256": normalized_hash(rwx_records),
        "passed": original.keys() == rwx.keys() and not mismatches,
        "reproductionHelper": "desktop/src/test/tools/original_peer/compare_builtin_configs.py",
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("original_units", type=Path)
    parser.add_argument("rwx_units", type=Path)
    parser.add_argument("output", type=Path)
    arguments = parser.parse_args()
    report = compare(arguments.original_units, arguments.rwx_units)
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: report[key] for key in ("comparedFileCount", "rawByteDifferenceCount", "normalizedMismatchCount", "passed")}))
    raise SystemExit(0 if report["passed"] else 1)
