# Original 1.15 core interoperability peer

`OriginalHeadlessPeer.java` is a test adapter around the unchanged Steam 1.15
`game-lib.jar`. It starts the original engine without its native desktop UI, so
the original peer can run on Apple Silicon when the installed x86 JVM and
proprietary LibRocket native libraries cannot run. It does not replace any
original game class, unit configuration, simulation method, command serializer,
network protocol, negotiated simulation step, or checksum calculation.

The retained Python runner in `../original_compatibility.py` prepares a clean
working directory, copies the verified original JAR, links the original assets,
compiles this adapter with `javac --release 17`, and starts an ARM JVM. The original
JAR comes first in the classpath; adapter classes come last. Each report records
the original JAR hash and adapter source hash. The adapter also prints the engine
class code source, game version and protocol version on startup.

The verified installed base JAR has SHA-256
`8a550a37e2d8a5430866090d4e7d5892f9010b47f52a5a09350fc66c620deec9` and reports
version `1.15`, protocol `176`. The helper accepts one positional argument, the
localhost control port, or `--boot-only` for a provenance/bootstrap check. The
Python runner's `--original-peer-mode headless-core` selects it explicitly.

## What the adapter supplies

- An original `ServerContext`, original desktop view with a test pointer, original
  null graphics/audio/music factories, and original desktop `-noresources`,
  `-nomods` and `-nobackground` flags. It creates the engine using the original
  `GameEngine` factory. It does not enable `-replay_debug` or `-canvasgl`.
- A single owner loop and a localhost control queue. Elapsed integer milliseconds
  are converted using the original Slick literal `milliseconds * 0.060000002f`
  and passed to the unchanged original game loop. Original battery-saving and
  high-refresh outer pacing options are retained. The original loop applies its
  own clamp, network accumulator and negotiated step rules.
- A narrow dispatcher for the runner's existing stock debug protocol. Debug
  status, checksum counters, player names, network port, map selection and unit
  movement call the actual original `Debug` methods. Hosting and joining use
  the original network controller and socket connector; starting invokes the
  original map-path selection and start operation.
- The engine portion of the original desktop start callback: the actual original
  Battleroom `setupGame` method loads the map, then the original started/loading
  flags are adopted before the next outer loop. The outer timing baseline is
  reset after load. UI-only `mp.refreshUI()` has no display to update.

The helper does not execute arbitrary debug scripts. Its control endpoint listens
only on `127.0.0.1`, and queued calls return after the operation executes on the
engine owner thread.

## Scope of evidence

A successful run is evidence that RWX interoperates with the genuine original
1.15 core, in both host/client roles, for the retained map, mixed units, commands
and duration reported by the runner. It is not evidence that the original Steam
desktop GUI launched, that its native renderer works on ARM, or that every
possible unit action and network condition has been exercised. The separate
native-launch attempt remains recorded by the runner. Performance measurements
belong to the RWX Vulkan scenarios, not this original peer.

## Built-in configuration comparison

`compare_builtin_configs.py` separately compares every `*.ini` relative path
under the two `assets/units` directories. Its retained report at
`docs/verification/original-1.15-builtin-config-values.json` records the exact
normalization scope, file counts, raw differences, mismatches and complete
manifest digests. Run from the repository root:

```sh
python3 desktop/src/test/tools/original_peer/compare_builtin_configs.py \
  '/Users/wty/Library/Application Support/Steam/steamapps/common/Rusted Warfare/assets/units' \
  assets/units docs/verification/original-1.15-builtin-config-values.json
```
