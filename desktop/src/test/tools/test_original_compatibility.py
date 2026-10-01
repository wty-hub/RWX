"""Preparation and protocol regressions; these tests never start game processes."""
import collections
import copy
import socket
import tempfile
import threading
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
import xml.etree.ElementTree as ET

import original_compatibility as probe


class OriginalCompatibilityTest(unittest.TestCase):
    def test_fixture_uses_registered_stock_names_and_preserves_terrain(self):
        repo = Path(__file__).resolve().parents[4]
        assets = repo / "assets"
        source = assets / "maps/skirmish/[p2]Small_Island (2p).tmx"
        enum = (repo / "core/src/main/java/com/corrodinggames/rts/game/units/UnitTypeEnum.java").read_text()
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / probe.MAP_NAME
            manifest = probe.fixture(source, assets, target)
            second = Path(temporary) / "repeat.tmx"
            repeated = probe.fixture(source, assets, second)
            self.assertEqual(manifest["sha256"], repeated["sha256"])
            self.assertEqual(136, manifest["unitCount"])
            self.assertEqual(13, manifest["types"])
            before, after = ET.parse(source).getroot(), ET.parse(target).getroot()
            for layer in before.findall("layer"):
                matching = next(item for item in after.findall("layer") if item.get("name") == layer.get("name"))
                if layer.get("name").lower() == "units":
                    self.assertTrue(all(value == 0 for value in probe.layer_values(matching)))
                else:
                    self.assertEqual(ET.tostring(layer), ET.tostring(matching))
            objects = after.find("objectgroup[@name='Compatibility Units']").findall("object")
            teams, names = collections.Counter(), collections.Counter()
            for obj in objects:
                properties = {p.get("name"): p.get("value") for p in obj.findall("properties/property")}
                names[properties["unit"]] += 1
                teams[properties["team"]] += 1
                self.assertIn("    " + properties["unit"] + " {", enum)
            self.assertEqual({"0": 68, "1": 68}, teams)
            self.assertEqual(manifest["units"], names)

    def test_clean_peer_copies_only_verified_base_jar_and_keeps_install_unchanged(self):
        with tempfile.TemporaryDirectory() as temporary:
            game, peer = Path(temporary) / "installed", Path(temporary) / "peer"
            game.mkdir()
            jar = game / "game-lib.jar"
            jar.write_bytes(b"isolated-test-base-jar")
            for name in ("assets", "res", "font"):
                (game / name).mkdir()
            (game / "RWPP-macos.jar").write_bytes(b"must-not-copy")
            with patch.object(probe, "STOCK_SHA256", probe.sha256(jar)):
                manifest = probe.prepare_peer(game, peer)
                self.assertEqual(jar.read_bytes(), (peer / "game-lib.jar").read_bytes())
                self.assertFalse((peer / "RWPP-macos.jar").exists())
                self.assertTrue((peer / "assets").is_symlink())
                self.assertEqual((game / "assets").resolve(), (peer / "assets").resolve())
                self.assertFalse(manifest["originalInstallModified"])
                self.assertFalse((game / "preferences.ini").exists())
                self.assertTrue((peer / "mods/maps").is_dir())
            with self.assertRaises(ValueError):
                probe.prepare_peer(game, Path(temporary) / "unverified")
            self.assertFalse((Path(temporary) / "unverified/game-lib.jar").exists())

    def test_original_debug_protocol_handles_nul_functions_and_non_nul_scripts(self):
        self.assertEqual(37, self.call_socket("function debug.getNumberOfDesyncPasses()\n", [b"ok\n", b"37\0"]))
        self.assertFalse(self.call_socket("function debug.isNetworkGameActive()\n", [b"ok\nfalse\0"]))
        self.assertTrue(self.call_socket("script joinServer('127.0.0.1:5123')\n", [b"do", b"ne"]))
        with self.assertRaisesRegex(RuntimeError, "Actual failure"):
            self.call_socket("function debug.isNetworkGameActive()\n", [b"crash\nActual failure\0"])

    def test_checksum_direction_and_rejection_use_real_client_counters(self):
        state = {"networkActive": True, "humanPlayers": 2, "desyncErrors": 0, "desyncPasses": 41,
                 "resyncs": 0, "pausedOnDesync": False,
                 "peers": [{"connected": True, "desyncErrors": 0, "syncMatches": 31}]}
        stock = {"networkActive": True, "humanPlayers": 2, "desyncErrors": 0, "desyncPasses": 29, "resyncs": 0}
        probe.validate_sample(state, stock)
        self.assertEqual(29, probe.client_checksum_passes("rwx-host", state, stock))
        self.assertEqual(41, probe.client_checksum_passes("original-host", state, stock))
        for field in ("desyncErrors", "resyncs", "pausedOnDesync"):
            bad = copy.deepcopy(state)
            bad[field] = 1
            with self.assertRaisesRegex(RuntimeError, "Desync or resync"):
                probe.validate_sample(bad, stock)
        bad = copy.deepcopy(state)
        bad["peers"][0]["desyncErrors"] = 1
        with self.assertRaisesRegex(RuntimeError, "Desync or resync"):
            probe.validate_sample(bad, stock)
        bad = copy.deepcopy(stock)
        bad["humanPlayers"] = 1
        with self.assertRaisesRegex(RuntimeError, "disconnected"):
            probe.validate_sample(state, bad)

    def test_hidden_window_evidence_requires_actual_tick_and_checksum_progress(self):
        log = ("RWX visibility probe: visible=false window=CanvasWrapper subsystem=PacedSwingWindowSubsystem "
               "canvasVisible=false canvasShowing=false frameVisible=false at 1000000000\n"
               "RWX visibility probe: visible=true window=CanvasWrapper subsystem=PacedSwingWindowSubsystem "
               "canvasVisible=true canvasShowing=true frameVisible=true at 11000000000")
        timing = [{"ownerMonotonicNs": i * 1_000_000_000, "tick": i * 60,
                   "stepRate": 1, "checksumIntervalFrames": 300, "lastSyncedTick": i // 5 * 300,
                   "clientChecksumPasses": i // 4} for i in range(0, 14)]
        evidence = probe.visibility_evidence(log, timing)
        self.assertEqual(10, evidence["actualSeconds"])
        self.assertEqual(10, evidence["samples"])
        self.assertEqual(60, evidence["first"]["tick"])
        self.assertEqual(600, evidence["last"]["tick"])
        stalled = [dict(item, tick=60) for item in timing]
        with self.assertRaisesRegex(RuntimeError, "Insufficient"):
            probe.visibility_evidence(log, stalled)
        unchecked = [dict(item, clientChecksumPasses=0) for item in timing]
        with self.assertRaisesRegex(RuntimeError, "Insufficient"):
            probe.visibility_evidence(log, unchecked)
        # Stock negotiation can make a checksum interval as long as the entire hide.
        negotiated = [dict(item, tick=item['tick'] // 2, stepRate=2,
                           clientChecksumPasses=0 if item["ownerMonotonicNs"] < 11_000_000_000 else 1)
                      for item in timing]
        evidence = probe.visibility_evidence(log, negotiated)
        self.assertEqual(0, evidence["insideChecksumAdvance"])
        self.assertEqual(1, evidence["checksumBracket"]["after"]["clientChecksumPasses"])
        self.assertEqual(10, evidence["checksumBracket"]["observedChecksumIntervalSeconds"])
        too_late = [dict(negotiated[0], ownerMonotonicNs=i * 1_000_000_000, tick=i * 30,
                         clientChecksumPasses=0 if i < 25 else 1) for i in range(0, 27)]
        with self.assertRaisesRegex(RuntimeError, "Insufficient"):
            probe.visibility_evidence(log, too_late)
        with self.assertRaisesRegex(RuntimeError, "Missing"):
            probe.visibility_evidence("", timing)
        with self.assertRaisesRegex(RuntimeError, "Missing"):
            probe.visibility_evidence(log.replace("canvasVisible=false", "canvasVisible=true"), timing)
        with self.assertRaisesRegex(RuntimeError, "Missing"):
            probe.visibility_evidence("RWX visibility probe: visible=false at 1000000000\n"
                                      "RWX visibility probe: visible=true at 11000000000", timing)

    def test_headless_adapter_keeps_original_jar_first_and_records_distinct_scope(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "desktop/src/test/tools/original_peer/OriginalHeadlessPeer.java"
            source.parent.mkdir(parents=True)
            source.write_text("public class OriginalHeadlessPeer {}")
            peer = root / "peer"
            peer.mkdir()
            args = SimpleNamespace(original_peer_mode="headless-core", original_java=Path("/test-jdk/bin/java"),
                                   repo=root, original_install=root / "installed", original_debug_port=55125)
            with patch.object(probe.subprocess, "run", return_value=SimpleNamespace(returncode=0, stdout="")) as compile_java:
                command, metadata = probe.original_command(args, peer)
            self.assertEqual(["--release", "17"], compile_java.call_args.args[0][1:3])
            self.assertEqual("OriginalHeadlessPeer", command[-2])
            classpath = command[command.index("-cp") + 1].split(":")
            self.assertEqual(str(peer / "game-lib.jar"), classpath[0])
            self.assertEqual(str(peer / "adapter-classes"), classpath[-1])
            self.assertFalse(metadata["nativeOriginalUi"])
            self.assertEqual("headless-original-core", metadata["peerMode"])
            self.assertEqual(probe.sha256(source), metadata["adapterSourceSha256"])

    def test_state_wait_fails_immediately_if_a_peer_process_dies(self):
        dead = SimpleNamespace(poll=lambda: 1)
        with self.assertRaisesRegex(RuntimeError, "Original exited"):
            probe.wait_for(lambda: {}, lambda _: False, processes=(("Original", dead),))

    @staticmethod
    def call_socket(expected, chunks):
        failures = []
        listener = socket.socket()
        listener.bind(("127.0.0.1", 0))
        listener.listen(1)
        port = listener.getsockname()[1]

        def serve():
            try:
                connection, _ = listener.accept()
                with connection:
                    line = connection.makefile("rb").readline().decode()
                    if line != expected: raise AssertionError((line, expected))
                    for chunk in chunks: connection.sendall(chunk)
            except BaseException as error:
                failures.append(error)
            finally:
                listener.close()

        server = threading.Thread(target=serve, daemon=True)
        server.start()
        try:
            command, expression = expected.rstrip("\n").split(" ", 1)
            return probe.OriginalPeer(port).call(expression, function=command == "function")
        finally:
            server.join(timeout=5)
            if failures: raise failures[0]
            if server.is_alive(): raise AssertionError("Protocol server did not finish")


if __name__ == "__main__":
    unittest.main()
