#!/usr/bin/env python3
"""Compare the installed engine's automatic CPU path against forced ARMv8 via adb.

Uses Stockfish's fixed-depth bench, one search thread, identical embedded network.
Alternates run order to reduce warm-up/thermal bias; never changes app settings.
"""
import argparse
import json
import re
import shlex
import statistics
import subprocess
from pathlib import Path


def shell(adb, words, timeout=300):
    result = subprocess.run(adb + ["exec-out", shlex.join(words)], text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            timeout=timeout, check=True)
    return result.stdout


def bench(adb, binary, baseline, depth):
    flags = ["--chesspilot-baseline"] if baseline else []
    output = shell(adb, [binary] + flags + ["bench", "16", "1", str(depth), "default", "depth"])
    def number(label):
        match = re.search(r"^" + label + r"\s*:\s*(\d+)\s*$", output, re.MULTILINE)
        if not match:
            raise RuntimeError("Incomplete Stockfish bench output:\n" + output[-2000:])
        return int(match.group(1))
    cpu = re.search(r"^info string ChessPilot CPU (\S+)", output, re.MULTILINE)
    if not cpu:
        raise RuntimeError("Engine does not contain the ChessPilot CPU dispatcher")
    return {"path": "baseline" if baseline else "auto", "cpu": cpu.group(1),
            "ms": number("Total time \\(ms\\)"), "nodes": number("Nodes searched"),
            "nps": number("Nodes/second")}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", help="adb device serial")
    parser.add_argument("--binary", help="Override installed nativeLibraryDir/libstockfish.so")
    parser.add_argument("--depth", type=int, choices=range(6, 19), default=12, metavar="6..18")
    parser.add_argument("--repeats", type=int, choices=range(1, 11), default=3, metavar="1..10")
    parser.add_argument("--output", type=Path, help="Write raw measurements and comparison as JSON")
    args = parser.parse_args()
    adb = [args.adb] + (["-s", args.serial] if args.serial else [])
    binary = args.binary
    if not binary:
        package = shell(adb, ["dumpsys", "package", "com.chessassist"], timeout=30)
        directory = re.search(r"^\s*(?:legacyNativeLibraryDir|nativeLibraryDir)=(\S+)", package, re.MULTILINE)
        if not directory:
            parser.error("Cannot find installed ChessPilot engine; supply --binary /data/app/.../lib/arm64/libstockfish.so")
        binary = directory.group(1).rstrip("/") + "/libstockfish.so"
    print("Warm-up: baseline and auto (stop the app's engine service first).", flush=True)
    bench(adb, binary, True, args.depth)
    bench(adb, binary, False, args.depth)
    rows = []
    for round_index in range(args.repeats):
        for baseline in ([True, False] if round_index % 2 == 0 else [False, True]):
            row = bench(adb, binary, baseline, args.depth)
            rows.append(row)
            print(f"{row['path']:8s} {row['cpu']:14s} {row['nps']:>10,d} nodes/s  {row['ms']} ms", flush=True)
    if len({row["nodes"] for row in rows}) != 1:
        raise RuntimeError("Single-thread fixed-depth node counts differ; inspect correctness before comparing speed")
    medians = {kind: statistics.median(row["nps"] for row in rows if row["path"] == kind)
               for kind in ("baseline", "auto")}
    comparison = {"depth": args.depth, "threads": 1, "hash_mb": 16, "runs": rows,
                  "median_nps": medians, "auto_over_baseline": medians["auto"] / medians["baseline"]}
    print(f"Median auto / baseline: {comparison['auto_over_baseline']:.3f}x")
    if rows[-1]["cpu"] == "armv8" and all(row["cpu"] == "armv8" for row in rows):
        print("This device selected the baseline for both runs (no dot-product capability).")
    if args.output:
        args.output.write_text(json.dumps(comparison, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
