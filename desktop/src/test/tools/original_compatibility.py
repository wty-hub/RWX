#!/usr/bin/env python3
"""Opt-in stock 1.15 ↔ RWX acceptance. Preparation never starts a game or modifies Steam files.

Run --prepare-only to inspect the clean peer and shared vanilla fixture. --run requires an RWX
launch command containing {rwx_root}; the process gets an isolated storage/preferences root.
This test creates units through the host's map loader and issues original movement commands.
It does not override simulation rate, checksum interval, health, or command processing rules.
"""
from __future__ import annotations

import argparse
import base64
import collections
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import socket
import struct
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET

STOCK_SHA256 = "8a550a37e2d8a5430866090d4e7d5892f9010b47f52a5a09350fc66c620deec9"
MAP_NAME = "[p2]RWX Original 1.15 Interop.tmx"
MAP_PATH = "/SD/mods/maps/" + MAP_NAME
PALETTE = {
    "land": ("tank", "hoverTank", "artillery", "heavyTank"),
    "air": ("helicopter", "airShip", "amphibiousJet"),
    "water": ("gunBoat", "missileShip", "attackSubmarine", "builderShip"),
}


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def layer_values(layer):
    data = layer.find("data")
    if data.get("encoding") != "base64" or data.get("compression") != "gzip":
        raise ValueError("Fixture source requires gzip/base64 tiled layers")
    raw = gzip.decompress(base64.b64decode(data.text))
    return list(struct.unpack("<%dI" % (len(raw) // 4), raw))


def fixture(source: Path, assets: Path, destination: Path, copies=6):
    """Terrain is unchanged; unit objects are part of the transferred host map."""
    tree = ET.parse(source)
    root = tree.getroot()
    width, height = int(root.get("width")), int(root.get("height"))
    tile_width, tile_height = int(root.get("tilewidth")), int(root.get("tileheight"))
    tile_properties = {}
    for tileset in root.findall("tileset"):
        definition = ET.parse(assets / "tilesets" / tileset.get("source")).getroot() if tileset.get("source") else tileset
        first = int(tileset.get("firstgid"))
        for tile in definition.findall("tile"):
            tile_properties[first + int(tile.get("id"))] = {
                p.get("name"): p.get("value") for p in tile.findall("properties/property")
            }
    ground = layer_values(next(l for l in root.findall("layer") if l.get("name").lower() == "ground"))
    unit_layer = next(l for l in root.findall("layer") if l.get("name").lower() == "units")
    unit_layer.find("data").text = base64.b64encode(gzip.compress(bytes(width * height * 4), mtime=0)).decode()
    groups = root.findall("objectgroup")
    unit_objects = ET.SubElement(root, "objectgroup", {"name": "Compatibility Units"})
    next_id = max((int(o.get("id", "0")) for g in groups for o in g.findall("object")), default=0) + 1
    represented = collections.Counter()
    points = []
    for y in range(3, height - 3):
        for x in range(3, width - 3):
            prop = tile_properties.get(ground[y * width + x] & 0x1FFFFFFF, {})
            if any(k in prop for k in ("cliff", "cliff-soft", "large-rock", "small-rock", "tree")):
                continue
            points.append((x, y, "water" in prop))
    for team in (0, 1):
        used = set()
        center_x = width * (.25 if team == 0 else .75)
        for category, types in {**PALETTE, "base": ("commandCenter", "builder")}.items():
            water = category == "water"
            candidates = sorted((p for p in points if p[2] == water),
                                key=lambda p: (abs(p[0] - center_x) + abs(p[1] - height * .5), p[1], p[0]))
            for unit in types:
                for _ in range(1 if category == "base" else copies):
                    point = next((p for p in candidates if p[:2] not in used and
                                  all(abs(p[0] - q[0]) + abs(p[1] - q[1]) >= 3 for q in used)), None)
                    if point is None:
                        raise ValueError("Not enough separated terrain for " + unit)
                    used.add(point[:2])
                    obj = ET.SubElement(unit_objects, "object", {
                        "id": str(next_id), "name": unit, "type": "unit",
                        "x": str((point[0] + .5) * tile_width), "y": str((point[1] + .5) * tile_height),
                        "width": "0", "height": "0", "rotation": str(0 if team == 0 else 180),
                    })
                    properties = ET.SubElement(obj, "properties")
                    for key, value in (("unit", unit), ("team", str(team))):
                        ET.SubElement(properties, "property", {"name": key, "value": value})
                    represented[unit] += 1
                    next_id += 1
    root.set("nextobjectid", str(next_id))
    destination.parent.mkdir(parents=True, exist_ok=True)
    tree.write(destination, encoding="utf-8", xml_declaration=True)
    return {"map": MAP_PATH, "sha256": sha256(destination), "units": dict(represented),
            "unitCount": sum(represented.values()), "types": len(represented),
            "width": width * tile_width, "height": height * tile_height,
            "coverage": "mixed land, air and sea movement; no all-unit GPU visual or performance coverage claim"}


def prepare_peer(game: Path, peer: Path):
    peer.mkdir(parents=True, exist_ok=True)
    original = game / "game-lib.jar"
    if sha256(original) != STOCK_SHA256:
        raise ValueError("Installed base jar differs from the locally inspected 1.15 jar; inspect it before acceptance")
    shutil.copy2(original, peer / "game-lib.jar")  # Manifest Class-Path '.' now resolves in the clean peer.
    for name in ("assets", "res", "font"):
        target = peer / name
        if not target.exists():
            target.symlink_to(game / name, target_is_directory=True)
    for name in ("cache", "saves", "replays", "mods/maps", "mods/units"):
        (peer / name).mkdir(parents=True, exist_ok=True)
    return {"gameJarSha256": STOCK_SHA256, "version": "1.15", "protocolVersion": 176,
            "workingDirectory": str(peer), "originalInstallModified": False}


class RwxPeer:
    def __init__(self, port):
        self.port, self.execution_sequence = port, 0

    def call(self, op, **parameters):
        body = json.dumps({"op": op, **parameters}).encode()
        request = urllib.request.Request(f"http://127.0.0.1:{self.port}/probe", body, {"Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=125) as response:
            result = json.load(response)
        if not result.get("ok"):
            raise RuntimeError(result)
        return result["data"]

    def status(self):
        value = self.call("status", sinceExecution=self.execution_sequence)
        self.execution_sequence = value.get("executionSequence", self.execution_sequence)
        return value


class OriginalPeer:
    def __init__(self, port):
        self.port = port

    def call(self, expression, function=True):
        with socket.create_connection(("127.0.0.1", self.port), timeout=15) as stream:
            stream.settimeout(120)
            stream.sendall((("function " if function else "script ") + expression + "\n").encode())
            data = bytearray()
            while True:
                part = stream.recv(4096)
                if not part:
                    break
                data.extend(part)
                if function and b"\0" in data or not function and (data == b"done" or b"crash" in data):
                    break
            result = data.decode(errors="replace").rstrip("\0")
        if function:
            if not result.startswith("ok\n"):
                raise RuntimeError("Original script failed: " + result)
            raw = result[3:].strip()
            if raw == "<NULL>": return None
            if raw in ("true", "false"): return raw == "true"
            try: return int(raw)
            except ValueError: return raw
        if result != "done":
            raise RuntimeError("Original script failed: " + result)
        return True

    def status(self):
        expressions = {
            "networkActive": "debug.isNetworkGameActive()", "humanPlayers": "debug.numberOfHumanPlayers()",
            "connections": "debug.numberOfPlayerConnections()", "localTeam": "debug.getLocalPlayerId()",
            "desyncErrors": "debug.getNumberOfDesyncErrors()", "desyncPasses": "debug.getNumberOfDesyncPasses()",
            "resyncs": "debug.getNumberOfResyncSendsOrRecv()",
        }
        return {key: self.call(expression) for key, expression in expressions.items()}


def wait_for(action, predicate, timeout=120, processes=()):
    deadline, last = time.monotonic() + timeout, None
    while time.monotonic() < deadline:
        for label, process in processes:
            if process.poll() is not None:
                raise RuntimeError(label + " exited during startup/state transition; inspect its log")
        try:
            last = action()
            if predicate(last): return last
        except (OSError, RuntimeError, KeyError) as error:
            last = str(error)
        time.sleep(.5)
    raise RuntimeError("Peer did not reach required state: " + repr(last))


def stop_process(process):
    if process.poll() is None:
        process.terminate()
        try: process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill(); process.wait(timeout=5)


def validate_sample(state, stock):
    if (not state["networkActive"] or not stock["networkActive"] or
            state["humanPlayers"] < 2 or stock["humanPlayers"] < 2 or
            not any(p["connected"] for p in state["peers"])):
        raise RuntimeError("A peer disconnected")
    errors = state["desyncErrors"] + stock["desyncErrors"] + sum(p["desyncErrors"] for p in state["peers"])
    if errors or state["resyncs"] or stock["resyncs"] or state["pausedOnDesync"]:
        raise RuntimeError("Desync or resync observed")


def client_checksum_passes(direction, state, stock):
    # The original client increments its global pass count; the original host increments
    # per-connection matches instead. RWX exposes both, but stock's debug API exposes only
    # its global count. Read the client in each direction to avoid a false host-side stall.
    return stock["desyncPasses"] if direction == "rwx-host" else state["desyncPasses"]


def visibility_evidence(log, timing):
    events = [(visible == "true", dict(re.findall(r"(\w+)=(\S+)", details)), int(nanos))
              for visible, details, nanos in
              re.findall(r"RWX visibility probe: visible=(true|false) ([^\n]*?) at (\d+)", log)]
    def actual_visibility(event):
        visible, details, _ = event
        expected = str(visible).lower()
        return (details.get("window") and details.get("subsystem") and
                all(details.get(field) == expected for field in ("canvasVisible", "canvasShowing", "frameVisible")))
    hidden = next((event for event in events if not event[0] and actual_visibility(event)), None)
    restored = next((event for event in events if event[0] and actual_visibility(event)
                     and hidden is not None and event[2] > hidden[2]), None)
    hidden_at = hidden[2] if hidden else None
    restored_at = restored[2] if restored else None
    if hidden_at is None or restored_at is None:
        raise RuntimeError("Missing real window hide/restore evidence")
    duration = (restored_at - hidden_at) / 1_000_000_000
    during = [sample for sample in timing if hidden_at <= sample["ownerMonotonicNs"] < restored_at]
    before = next((sample for sample in reversed(timing) if sample["ownerMonotonicNs"] < hidden_at), None)
    # The original host can negotiate a 30 tick/s step, so its unchanged 300-tick checksum
    # interval is ten seconds. The eight or nine samples strictly inside a ten-second hide
    # need not contain a completed comparison. Keep exact inside progress, and additionally
    # bound the first post-restore comparison by the actual interval and a network margin.
    observation = ([before] if before else []) + during
    observed_tick_rate = ((observation[-1]["tick"] - observation[0]["tick"]) * 1_000_000_000 /
                          (observation[-1]["ownerMonotonicNs"] - observation[0]["ownerMonotonicNs"])) if len(observation) > 1 else 0
    checksum_ticks = during[-1].get("checksumIntervalFrames", 0) if during else 0
    checksum_interval = checksum_ticks / observed_tick_rate if observed_tick_rate > 0 and checksum_ticks > 0 else 0
    network_margin = 3.0
    comparison_deadline = restored_at + int((checksum_interval + network_margin) * 1_000_000_000)
    after = next((sample for sample in timing if restored_at <= sample["ownerMonotonicNs"] <= comparison_deadline
                  and before is not None and sample["clientChecksumPasses"] > before["clientChecksumPasses"]), None)
    if (duration < 9.5 or len(during) < 2 or during[-1]["tick"] <= during[0]["tick"] or
            before is None or after is None or hidden_at - before["ownerMonotonicNs"] > 3_000_000_000 or
            checksum_interval <= 0):
        raise RuntimeError("Insufficient tick/checksum progress during actual ten-second hidden window")
    return {"configuredSeconds": 10, "actualSeconds": duration, "samples": len(during),
            "first": during[0], "last": during[-1], "hiddenAtOwnerNanos": hidden_at, "restoredAtOwnerNanos": restored_at,
            "hiddenVisibility": hidden[1], "restoredVisibility": restored[1],
            "insideChecksumAdvance": during[-1]["clientChecksumPasses"] - during[0]["clientChecksumPasses"],
            "insideChecksumObserved": during[-1]["clientChecksumPasses"] > during[0]["clientChecksumPasses"],
            "checksumBracket": {"before": before, "after": after,
                                "negotiatedStepRate": during[-1].get("stepRate"),
                                "checksumIntervalTicks": checksum_ticks, "observedTicksPerSecond": observed_tick_rate,
                                "observedChecksumIntervalSeconds": checksum_interval, "networkMarginSeconds": network_margin,
                                "beforeHideSeconds": (hidden_at - before["ownerMonotonicNs"]) / 1_000_000_000,
                                "afterRestoreSeconds": (after["ownerMonotonicNs"] - restored_at) / 1_000_000_000}}


def original_command(args, original_root):
    classpath = str(original_root / "game-lib.jar") + ":" + str(args.original_install / "libs/*")
    if args.original_peer_mode == "headless-core":
        java = args.original_java or Path("/opt/homebrew/opt/openjdk@25/bin/java")
        source = args.repo / "desktop/src/test/tools/original_peer/OriginalHeadlessPeer.java"
        classes = original_root / "adapter-classes"
        classes.mkdir(parents=True, exist_ok=True)
        compile_command = [str(java.with_name("javac")), "--release", "17", "-encoding", "UTF-8",
                           "-cp", classpath, "-d", str(classes), str(source)]
        compiled = subprocess.run(compile_command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=60)
        (original_root / "adapter-compile.log").write_text(compiled.stdout)
        if compiled.returncode: raise RuntimeError("Original core adapter failed compilation: " + compiled.stdout)
        command = [str(java), "-Djava.awt.headless=true", "-Duser.home=" + str(original_root / "isolated-home"),
                   "-cp", classpath + ":" + str(classes), "OriginalHeadlessPeer", str(args.original_debug_port)]
        metadata = {"peerMode": "headless-original-core", "adapterSourceSha256": sha256(source),
                    "nativeOriginalUi": False, "scope": "unchanged original 1.15 engine and network; bootstrap/control adapter with dummy graphics"}
        return command, metadata
    java = args.original_java or args.original_install / "jvm-rwpp-x64/bin/java"
    if not java.is_file(): raise ValueError("Expected installed x86_64 JVM: " + str(java))
    command = ["arch", "-x86_64", str(java), "-Dfile.encoding=UTF-8",
               "-Duser.home=" + str(original_root / "isolated-home"),
               "-Djava.library.path=" + str(args.original_install), "-cp", classpath,
               "com.corrodinggames.rts.java.Main", "-nodisplay", "-noresources", "-nosound", "-nomusic",
               "-nomods", "-nobackground", "-debug", f"{args.original_debug_port}:local-acceptance"]
    return command, {"peerMode": "native-original-main", "nativeOriginalUi": True}


def run_direction(args, direction, scenario):
    root = args.output / direction
    original_root, rwx_root = root / "original", root / "rwx"
    manifest = prepare_peer(args.original_install, original_root)
    for runtime in (original_root, rwx_root):
        destination = runtime / "mods/maps" / MAP_NAME
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(args.output / MAP_NAME, destination)
    env = os.environ.copy()
    for name in ("RWX_BENCHMARK_UNITS", "RWX_BENCHMARK_OUTPUT", "RWX_DEBUG_AUTO_EXIT_SECONDS", "RWX_FULL_STATE_LOG", "RWX_CHECKSUM_LOG",
                 "RWX_DEBUG_HIDE_AFTER_SECONDS", "RWX_DEBUG_HIDE_SECONDS", "RWX_KOOL_MSAA_SAMPLES"):
        env.pop(name, None)
    if args.hide_window:
        env["RWX_DEBUG_HIDE_AFTER_SECONDS"] = str(args.hide_after_seconds)
        env["RWX_DEBUG_HIDE_SECONDS"] = "10"
    env["RWX_COMPAT_PROBE_PORT"] = str(args.rwx_probe_port)
    env["RWX_ASSETS_DIR"] = str(args.assets)
    java_options = f'-Dlaunch.dir="{rwx_root}" -Duser.home="{rwx_root / "isolated-home"}"'
    env["JAVA_TOOL_OPTIONS"] = env.get("JAVA_TOOL_OPTIONS", "") + " " + java_options
    native_env = os.environ.copy()
    native_env.pop("JAVA_TOOL_OPTIONS", None)
    native_env.pop("JDK_JAVA_OPTIONS", None)
    native_env["DYLD_FALLBACK_LIBRARY_PATH"] = str(args.original_install)
    stock_command, stock_metadata = original_command(args, original_root)
    rwx_command = shlex.split(args.rwx_command.format(rwx_root=str(rwx_root)))
    if "-jar" in rwx_command:
        rwx_jar = Path(rwx_command[rwx_command.index("-jar") + 1])
        if not rwx_jar.is_absolute(): rwx_jar = args.repo / rwx_jar
        manifest["rwxJarSha256"] = sha256(rwx_jar)
    manifest.update({"direction": direction, "fixture": scenario, "durationSeconds": args.seconds,
                     "hideAfterSeconds": args.hide_after_seconds if args.hide_window else None,
                     "hideDurationSeconds": 10 if args.hide_window else None,
                     "originalCommand": stock_command, "rwxCommand": rwx_command, **stock_metadata})
    (root / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2))
    with (root / "rwx.log").open("wb") as rwx_log, (root / "original.log").open("wb") as stock_log:
        rwx_process = subprocess.Popen(rwx_command, cwd=args.repo, env=env, stdout=rwx_log, stderr=subprocess.STDOUT)
        original_process = None
        completed_network_window = None
        try:
            original_process = subprocess.Popen(stock_command, cwd=original_root, env=native_env, stdout=stock_log, stderr=subprocess.STDOUT)
            print(json.dumps({"event": "processes-started", "direction": direction,
                              "rwxPid": rwx_process.pid, "originalPid": original_process.pid}), flush=True)
            rwx, original = RwxPeer(args.rwx_probe_port), OriginalPeer(args.original_debug_port)
            def await_state(action, predicate, timeout=120):
                return wait_for(action, predicate, timeout, (("RWX", rwx_process), ("Original", original_process)))
            await_state(rwx.status, lambda value: value.get("engineReady"))
            await_state(lambda: original.call("debug.isNetworkGameActive()"), lambda _: True)
            original.call("debug.setLocalPlayerName('Original 1.15')", function=False)
            if direction == "rwx-host":
                rwx.call("host", map=MAP_PATH, port=args.game_port)
                original.call(f"joinServer('127.0.0.1:{args.game_port}')", function=False)
            else:
                original.call(f"debug.networkSetPortNumber({args.game_port})", function=False)
                original.call("hostStartWithPasswordAndMods(false,null,false)", function=False)
                original.call(f"debug.setMultiplayerMap(1,{json.dumps(MAP_NAME)})", function=False)
                original.call("mp.refreshUI()", function=False)
                rwx.call("join", address=f"127.0.0.1:{args.game_port}")
            await_state(rwx.status, lambda v: v.get("humanPlayers", 0) >= 2 and any(p["connected"] for p in v.get("peers", [])))
            await_state(original.status, lambda v: v.get("humanPlayers", 0) >= 2 and v.get("connections", 0) >= 1)
            if direction == "rwx-host": rwx.call("start")
            else: original.call("mp.multiplayerStart()", function=False)
            started = await_state(rwx.status, lambda v: v.get("started"))
            if not started.get("runningMap", "").endswith(MAP_NAME): rwx.call("adopt")
            first = await_state(rwx.status, lambda v: v.get("mapLoaded") and v.get("runningMap", "").endswith(MAP_NAME) and v.get("unitCount", 0) >= scenario["unitCount"])
            # Observe the client's real original checksum comparisons in each host direction.
            first, stock_first = await_state(lambda: (rwx.status(), original.status()),
                                         lambda v: client_checksum_passes(direction, *v) > 0)
            validate_sample(first, stock_first)
            initial_tick, previous_tick = first["tick"], first["tick"]
            initial_passes = client_checksum_passes(direction, first, stock_first)
            initial_execution = first["executionSequence"]
            timing = []
            started_at = last_tick_change = time.monotonic()
            deadline, next_move, sample, commands = started_at + args.seconds, 0, 0, 0
            maximum_passes = initial_passes
            print(json.dumps({"event": "timed-match-started", "direction": direction, "tick": initial_tick,
                              "clientChecksumPasses": initial_passes, "seconds": args.seconds}), flush=True)
            with (root / "samples.ndjson").open("w") as output:
                while time.monotonic() < deadline:
                    if rwx_process.poll() is not None or original_process.poll() is not None: raise RuntimeError("A game process exited early")
                    state, stock = rwx.status(), original.status()
                    validate_sample(state, stock)
                    if state["tick"] != previous_tick: last_tick_change = time.monotonic()
                    if time.monotonic() - last_tick_change > 30:
                        raise RuntimeError("Simulation did not advance for 30 seconds")
                    maximum_passes = max(maximum_passes, client_checksum_passes(direction, state, stock))
                    if args.hide_window:
                        if "ownerMonotonicNs" not in state: raise RuntimeError("Frozen probe lacks owner clock; rebuild the interop jar")
                        timing.append({"ownerMonotonicNs": state["ownerMonotonicNs"], "tick": state["tick"],
                                       "stepRate": state["stepRate"], "checksumIntervalFrames": state["checksumIntervalFrames"],
                                       "lastSyncedTick": state["lastSyncedTick"], "executionSequence": state["executionSequence"],
                                       "clientChecksumPasses": client_checksum_passes(direction, state, stock)})
                    command_results = []
                    if time.monotonic() >= next_move:
                        phase = (commands // 2) % 4
                        for name, local_team in (("rwx", state["localTeam"]), ("original", stock["localTeam"])):
                            x = scenario["width"] * (.25 if local_team == 0 else .75)
                            y = scenario["height"] * (.4 if phase % 2 == 0 else .6)
                            if name == "rwx": result = rwx.call("move", x=x, y=y)
                            else: result = original.call(f"debug.moveAllUnitsOnTeam({local_team},{x},{y})")
                            if result is False: raise RuntimeError("Original move command rejected")
                            command_results.append({"peer": name, "x": x, "y": y, "result": result})
                            commands += 1
                        next_move = time.monotonic() + 20
                    output.write(json.dumps({"sample": sample, "elapsedSeconds": args.seconds - max(0, deadline - time.monotonic()),
                                             "rwx": state, "original": stock, "submittedCommands": command_results}) + "\n")
                    output.flush()
                    if sample % 30 == 0:
                        print(json.dumps({"event": "match-progress", "direction": direction,
                                          "elapsedSeconds": round(time.monotonic() - started_at, 1), "tick": state["tick"],
                                          "clientChecksumPasses": maximum_passes, "unitCount": state["unitCount"],
                                          "issuedCommands": commands}), flush=True)
                    previous_tick, sample = state["tick"], sample + 1
                    time.sleep(1)
            final, stock_final = rwx.status(), original.status()
            validate_sample(final, stock_final)
            elapsed = time.monotonic() - started_at
            if (final["tick"] <= initial_tick or maximum_passes <= initial_passes or
                    final["executionSequence"] <= initial_execution or commands < 2):
                raise RuntimeError("Insufficient simulation, checksum or command-execution evidence")
            result = {"passed": elapsed >= 600, "requestedDurationSeconds": args.seconds,
                      "durationSeconds": elapsed, "samples": sample,
                      "initialTick": initial_tick, "finalTick": final["tick"], "submittedCommands": commands,
                      "rwxExecutedCommands": final["executionSequence"] - initial_execution,
                      "initialClientChecksumPasses": initial_passes, "finalClientChecksumPasses": maximum_passes,
                      "finalRwx": final, "finalOriginal": stock_final,
                      "originalPeerMode": stock_metadata["peerMode"], "nativeOriginalUi": stock_metadata["nativeOriginalUi"],
                      "scope": "original 1.15 mixed land/air/sea loopback using " + stock_metadata["peerMode"] +
                               "; no claim of performance or full unit scenario coverage"}
            completed_network_window = result
            if args.hide_window:
                result["hiddenWindow"] = visibility_evidence((root / "rwx.log").read_text(errors="replace"), timing)
            (root / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2))
            print(json.dumps({"event": "direction-complete", "direction": direction, "passed": result["passed"],
                              "seconds": result["durationSeconds"]}), flush=True)
            return result
        except Exception as error:
            failure = dict(completed_network_window or {})
            failure.update(passed=False, error=str(error))
            (root / "result.json").write_text(json.dumps(failure, indent=2))
            raise
        finally:
            if original_process is not None: stop_process(original_process)
            stop_process(rwx_process)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[4])
    parser.add_argument("--original-install", type=Path, default=Path.home() / "Library/Application Support/Steam/steamapps/common/Rusted Warfare")
    parser.add_argument("--original-peer-mode", choices=("native", "headless-core"), default="native")
    parser.add_argument("--original-java", type=Path, help="Explicit JVM for original peer; native mode requires x86_64")
    parser.add_argument("--assets", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--rwx-command", help="RWX command; may contain {rwx_root}; storage is isolated via launch.dir")
    parser.add_argument("--seconds", type=int, default=600)
    parser.add_argument("--game-port", type=int, default=55123)
    parser.add_argument("--rwx-probe-port", type=int, default=55124)
    parser.add_argument("--original-debug-port", type=int, default=55125)
    parser.add_argument("--run", action="store_true", help="Starts actual game peers; run only after performance benchmarks end")
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--hide-window", action="store_true", help="Hide RWX for ten seconds after startup and require live tick/checksum progress")
    parser.add_argument("--hide-after-seconds", type=int, default=60, help="Startup delay for the ten-second hidden-window probe; reduce only for smoke runs")
    args = parser.parse_args()
    args.repo = args.repo.resolve(); args.original_install = args.original_install.resolve()
    args.assets = (args.assets or args.repo / "assets").resolve()
    args.output = (args.output or args.repo / "desktop/build/original-interop").resolve()
    if args.run and args.prepare_only: parser.error("Choose --run or --prepare-only")
    if args.run and not args.rwx_command: parser.error("--run requires --rwx-command")
    if args.seconds <= 0: parser.error("--seconds must be positive")
    if args.hide_after_seconds <= 0: parser.error("--hide-after-seconds must be positive")
    ports = (args.game_port, args.rwx_probe_port, args.original_debug_port)
    if any(port < 1024 or port > 65535 for port in ports) or len(set(ports)) != len(ports):
        parser.error("Game, RWX probe and original debug ports must be distinct ports between 1024 and 65535")
    args.output.mkdir(parents=True, exist_ok=True)
    scenario = fixture(args.assets / "maps/skirmish/[p2]Small_Island (2p).tmx", args.assets, args.output / MAP_NAME)
    prepared = prepare_peer(args.original_install, args.output / "prepared-original")
    (args.output / "fixture.json").write_text(json.dumps(scenario, ensure_ascii=False, indent=2))
    (args.output / "original.json").write_text(json.dumps(prepared, ensure_ascii=False, indent=2))
    if not args.run:
        print(json.dumps({"prepared": True, "launchedProcesses": 0, "original": prepared, "fixture": scenario}, ensure_ascii=False, indent=2))
        return
    results = {direction: run_direction(args, direction, scenario) for direction in ("rwx-host", "original-host")}
    (args.output / "result.json").write_text(json.dumps({"passed": all(r["passed"] for r in results.values()), "directions": results}, indent=2))


if __name__ == "__main__":
    main()
