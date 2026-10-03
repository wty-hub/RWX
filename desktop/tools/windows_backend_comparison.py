"""Compare OpenGL and Vulkan on Windows using frozen jars and isolated preferences.

Records 30 seconds of warmup and three consecutive 30-second samples per process.
Use --only to compare before/after jars in the same scene, and --default-backend
with a Vulkan --only case to verify Windows startup without a backend override.
"""
import argparse
import csv
from datetime import date
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time

PROJECT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(PROJECT / "desktop/tools"))
from analyze_vulkan_matrix import presentation_window, read_json_lines
from vulkan_native_matrix import java_command


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def system_state():
    script = """
    [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
    $battery = Get-CimInstance Win32_Battery | Select-Object BatteryStatus,EstimatedChargeRemaining
    $processor = Get-CimInstance Win32_Processor | Select-Object Name,NumberOfCores,NumberOfLogicalProcessors,CurrentClockSpeed,MaxClockSpeed
    $gpu = Get-CimInstance Win32_VideoController | Select-Object Name,DriverVersion,CurrentHorizontalResolution,CurrentVerticalResolution,CurrentRefreshRate
    $os = Get-CimInstance Win32_OperatingSystem | Select-Object Caption,Version,TotalVisibleMemorySize
    @{battery=$battery;processor=$processor;gpu=$gpu;os=$os} | ConvertTo-Json -Depth 5 -Compress
    """
    result = subprocess.run(["powershell", "-NoProfile", "-Command", script], capture_output=True, text=True, encoding="utf-8")
    return json.loads(result.stdout) if result.returncode == 0 else {"error": result.stderr}


def analyze_run(output, run):
    name = run["name"]
    scenario_path = output / f"{name}-scenario.ndjson"
    trace_path = output / f"{name}-trace.csv"
    scenario = read_json_lines(scenario_path) if scenario_path.exists() else []
    trace = []
    if trace_path.exists():
        with trace_path.open(encoding="utf-8") as stream:
            for row in csv.reader(stream):
                if row and row[0].isdigit():
                    trace.append(tuple(map(int, row)))
    windows = [presentation_window(trace, sample) for sample in scenario if sample.get("kind") == "engine-window"]
    log = (output / f"{name}.log").read_text(encoding="utf-8", errors="replace")
    evidence = [line for line in log.splitlines() if re.search(
        r"Using Kool render backend|OpenGL version|OpenGL renderer|device:|Device:|device name|Device name|swapchain|Swapchain|MSAA|samples|samples=|Present mode|present mode|Vulkan version|Vulkan device|LWJGL|Exception|ERROR|Error|FATAL|Failed", line)]
    frame_path = output / f"{name}-frame.jsonl"
    return {**run, "setup": scenario[0] if scenario else None, "windows": windows,
            "frameWindows": read_json_lines(frame_path) if frame_path.exists() else [],
            "backendEvidence": evidence[:80],
            "validMeasurement": run["exitCode"] == 0 and len(windows) == 3 and all(w["acceptedPresentFps"] > 0 for w in windows)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--java", default=shutil.which("java"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--smoke", action="store_true")
    parser.add_argument("--diagnostics", action="store_true")
    parser.add_argument("--default-backend", action="store_true")
    parser.add_argument("--only", choices=("661-opengl-idle", "661-vulkan-idle", "2000-opengl-idle", "2000-vulkan-idle", "2000-opengl-combat", "2000-vulkan-combat"))
    args = parser.parse_args()
    if args.default_backend and (not args.only or "-vulkan-" not in args.only):
        parser.error("--default-backend requires a Vulkan --only case")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    digest = hashlib.sha256(args.jar.read_bytes()).hexdigest()
    snapshot = output / f"runtime-{digest[:16]}.jar"
    shutil.copy2(args.jar, snapshot)
    seed = (PROJECT / "preferences.toml").read_text(encoding="utf-8")
    for key, value in {"renderVsync": "false", "slick2dFullScreen": "false", "maxFrameRate": "300"}.items():
        seed, count = re.subn(rf'(?m)^{key}\s*=\s*"[^"\n]*"', f'{key} = "{value}"', seed)
        if count != 1:
            raise RuntimeError(f"Expected exactly one preference entry for {key}")
    (output / "seed-preferences.toml").write_text(seed, encoding="utf-8")
    version = subprocess.run([args.java, "-version"], capture_output=True, text=True)
    report = {"date": date.today().isoformat(), "runtimeSha256": digest, "runtime": str(snapshot),
              "javaVersion": version.stdout + version.stderr, "machine": system_state(),
              "protocol": {"width": 1920, "height": 1080, "msaa": 4, "vsync": False,
                           "targetFps": 300, "mix": "all", "selected": True,
                           "warmupSeconds": 30, "sampleSeconds": 30, "samplesPerProcess": 3,
                           "freshProcesses": True, "isolatedPreferences": True,
                           "nativeGpuDiagnostics": args.diagnostics,
                           "backendSelection": "platform-default" if args.default_backend else "explicit",
                           "measurement": "OpenGL swap return / Vulkan successful queue-present acceptance; not physical monitor scanout",
                           "operatingState": "normal interactive Windows session; windows requested visible; lock/occlusion not independently measured",
                           "limitation": "Current laptop power state; combat evolves at different speeds and casualties must be compared."}, "runs": []}
    write_json(output / "summary.json", report)
    cases = [(661, "idle", ["opengl", "vulkan"]),
             (2000, "idle", ["vulkan", "opengl"]),
             (2000, "combat", ["opengl", "vulkan"])]
    if args.smoke:
        cases = [(661, "idle", ["opengl", "vulkan"])]
    if args.only:
        unit_arg, backend_arg, mode_arg = args.only.split("-")
        cases = [(int(unit_arg), mode_arg, [backend_arg])]
    for units, mode, backends in cases:
        for backend in backends:
            name = f"{units}-all-{backend}-{mode}"
            sandbox = output / name
            sandbox.mkdir()
            (sandbox / "preferences.toml").write_text(seed, encoding="utf-8")
            command = java_command(args.java, snapshot, PROJECT / "desktop/build/slick-natives")
            flags = [f"-Dlaunch.dir={sandbox}", f"-Drwx.assetsDir={PROJECT / 'assets'}", "-Xms512m", "-Xmx2g"]
            if args.default_backend:
                if backend != "vulkan":
                    raise ValueError("Default-backend verification is a Windows Vulkan case")
            else:
                flags.append(f"-Drwx.kool.backend={backend}")
            command[1:1] = flags
            if args.diagnostics:
                command[1:1] = [f"-XX:StartFlightRecording=filename={output / (name + '.jfr')},settings=profile,dumponexit=true"]
            env = {k: v for k, v in os.environ.items() if not k.startswith("RWX_") and k not in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "MVK_CONFIG_SYNCHRONOUS_QUEUE_SUBMITS")}
            env.update({"APPDATA": str(sandbox / "appdata"), "RWX_WINDOW_WIDTH": "1920", "RWX_WINDOW_HEIGHT": "1080",
                        "RWX_KOOL_MSAA_SAMPLES": "4", "RWX_FRAME_METRICS": str(output / f"{name}-frame.jsonl"),
                        "RWX_FRAME_TRACE": str(output / f"{name}-trace.csv"), "RWX_BENCHMARK_UNITS": str(units),
                        "RWX_BENCHMARK_MIX": "all", "RWX_BENCHMARK_MODE": mode, "RWX_BENCHMARK_SELECTED": "1",
                        "RWX_BENCHMARK_OUTPUT": str(output / f"{name}-scenario.ndjson"), "RWX_PERF_LOG": "1",
                        "RWX_DESKTOP_TARGET_FPS": "300", "RWX_DEBUG_AUTO_EXIT_SECONDS": "18" if args.smoke else "135"})
            if args.diagnostics:
                env.update({"RWX_VK_METRICS": str(output / f"{name}-native.jsonl"), "RWX_CANVAS_PERF": "1"})
            started = time.monotonic()
            print(f"START {name}", flush=True)
            before = system_state()
            with (output / f"{name}.log").open("w", encoding="utf-8") as log:
                process = subprocess.Popen(command, cwd=PROJECT, env=env, stdout=log, stderr=subprocess.STDOUT)
                print(f"PID {process.pid}", flush=True)
                try:
                    exit_code = process.wait(timeout=210)
                except subprocess.TimeoutExpired:
                    process.kill()
                    exit_code = process.wait()
            run = {"name": name, "backend": backend, "units": units, "mode": mode,
                   "exitCode": exit_code, "elapsedSeconds": time.monotonic() - started,
                   "powerBefore": before.get("battery"), "powerAfter": system_state().get("battery"), "argv": command}
            analyzed = analyze_run(output, run)
            report["runs"].append(analyzed)
            write_json(output / "summary.json", report)
            print("DONE " + json.dumps({k: run[k] for k in ("name", "exitCode", "elapsedSeconds")}), flush=True)
            for window in analyzed["windows"]:
                print(json.dumps({k: window.get(k) for k in ("repetition", "minimumLivingUnits", "minimumVisibleUnits", "acceptedPresentFps", "freshSnapshotHz", "repeatRatio", "presentIntervalP95Ms", "presentIntervalP99Ms")}), flush=True)
            if exit_code != 0 or (not args.smoke and not analyzed["validMeasurement"]):
                print(f"INVALID {name}: inspect the saved log and partial summary", flush=True)
                return 1
    print(f"SUMMARY {output / 'summary.json'}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
