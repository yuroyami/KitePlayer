# kiteplayer-rt

The real-time audio core of the Apple audio output, written in C. It allocates once, when it is
created. It takes no lock, and no managed code ever runs on the audio device's thread.

## Why it is in C

On macOS, the audio render callback used to be a Kotlin lambda. Its first step was
`refCon.asStableRef<CoreAudioSink>().get()`, which made the device's real-time thread a Kotlin
thread that the garbage collector may have to pause. Many objects, atomic wrappers, virtual calls
and temporary cinterop views sat on that path. Nobody had heard a glitch, but the audio deadline
depended on a pause that nothing bounded.

So the callback, the AudioUnit and the sample ring moved into this module. `CoreAudioSink` in
`kiteplayer-output` now only owns two opaque handles to it.

## Layout

| Path | What it is |
|---|---|
| `native/include/kite_rt.h` | The whole public API: the `KPRT_API` functions and the constants. This is what cinterop reads. |
| `native/src/kite_rt_ring.c` | The writing side of the ring, and its create and destroy code. The only file here that allocates. |
| `native/src/kite_rt_render.c` | The code the device callback calls: it reads the ring and records the clock anchor. No create or destroy code and no allocator. |
| `native/src/kite_rt_coreaudio.c` | The device code and the shared `static` callback: DefaultOutput on macOS, RemoteIO on iOS, and an explicit refusal on other platforms. |
| `native/src/kite_rt_ring_internal.h`, `kite_rt_sink_internal.h` | The private struct layouts. They are not in `include/`, so Kotlin only sees opaque pointers. |
| `native/tests/*.c` | Thirteen table-driven test suites, one line per case. |
| `native/tests/interpose_alloc.c` | The allocation counter, installed through the Mach-O `__DATA,__interpose` section. |
| `native/scripts/build-host.sh` | Builds the host test binaries for one variant. It needs no make, cmake or ninja. |
| `native/scripts/run-c-tests.sh` | Runs the test suites in one of four modes. |
| `native/scripts/render-audit.sh` | Checks the symbols and instructions of both real-time objects, in the macOS archive and in the two iOS archives. |
| `native/scripts/source-discipline.sh` | Checks that each memory-ordering decision is still written where the design put it. |
| `native/kitert.def` | The cinterop definition. It names the archive and links AudioToolbox on macOS and iOS. |
| `module.yaml` | The Kotlin Toolchain module: its Kotlin/Native platforms, and the `kitert-c` build plugin that compiles the C code. |
| `../build-plugins/kitert-c/` | The `kitert-c` build plugin. It runs one task per Kotlin/Native target and gives each target its own cinterop definition. |
| `../build-plugins/build-support/src/CompileKiteRt.kt` | Compiles and archives the C code for each Kotlin/Native target, with Kotlin/Native's own clang and `llvm-ar`. |

## Build and test

```bash
cd native
./scripts/build-host.sh plain && ./scripts/run-c-tests.sh plain
./scripts/build-host.sh asan  && ./scripts/run-c-tests.sh asan
./scripts/build-host.sh tsan  && ./scripts/run-c-tests.sh tsan
./scripts/run-c-tests.sh interpose      # the plain binaries, with allocation counting required
./scripts/render-audit.sh               # add --prove-it-can-fail to run its negative controls
./scripts/source-discipline.sh          # add --prove-it-can-fail to run its planted defects
```

`run-c-tests.sh` never builds anything. Run the matching `build-host.sh` first after every change.

From the repository root, the Kotlin Toolchain builds the archives that ship, one per Kotlin/Native
target, under `build/tasks/`:

```bash
./kotlin build -m kiteplayer-rt -p macosArm64 -p iosArm64 -p iosSimulatorArm64
./kotlin test -m kiteplayer-rt -p macosArm64
./kotlin test -m kiteplayer-rt -p iosSimulatorArm64
```

KiteFFmpeg's `native/kitecodec-c/tests/` has a copy of the same test harness and allocation
counter, under another prefix (`KC_REQUIRE_ALLOC_ACCOUNTING` there, `KPRT_REQUIRE_ALLOC_ACCOUNTING`
here). Change both in the same change.

## Build variants

| Variant | Flags | What it catches |
|---|---|---|
| `plain` | `-O2 -Wall -Wextra -Werror -Werror=vla` | Compile errors and wrong results. This is the only variant in which the allocation counter works. |
| `asan` | `-fsanitize=address,undefined -fno-omit-frame-pointer -O1` | An out-of-bounds ring index, a wrap-around mistake or a signed overflow, reported at the exact byte and line. |
| `tsan` | `-fsanitize=thread -O1` | Data races in the two seqlocks. A seqlock written with plain or `volatile` fields instead of C11 atomics is a real data race, and this variant reports it. |

`interpose` is a run mode, not a fourth build. It runs the `plain` binaries with
`KPRT_REQUIRE_ALLOC_ACCOUNTING=1`, which fails the run when the allocation counter was not active.
Without it, "the counter did not run" and "nothing allocated" look the same. ASan and TSan replace
the allocator before the interpose section loads, so under them the counter is inactive, and the
cases that depend on it report a partial result instead of a pass.

## How the "no allocation" claim is checked

Kotlin/Native has no allocation hook, and LeakSanitizer does not run on macOS arm64. A malloc
counter cannot see Kotlin allocations either: one million Kotlin objects moved it from 229 to 230
mallocs, because the runtime takes whole pages with `mmap`. So the claim rests on several checks,
each with its own limits.

| Check | What it shows | What it cannot show |
|---|---|---|
| `render-audit.sh` | The shipped render and callback objects call no allocator, lock, logger or forbidden framework on the real-time path. It pins DefaultOutput on macOS, RemoteIO on both iOS archives, and the set of calls the callback makes. It also compiles the ring header for 32-bit ARMv7, where the 64-bit counters must be lock-free, and for ARMv5, where the header must refuse to build. | An allocation that the optimiser removed, or any target whose archive was not built. The iOS check builds all three archives and allows no skip. |
| `run-c-tests.sh interpose` | No `malloc`, `calloc`, `realloc`, `free` or `mmap` across five million synthetic callbacks through the shipped render code. | Anything about Kotlin allocation, as explained above. |
| A supervised run on one device | The slowest callback against half the device period, while the garbage collector runs thousands of times. This was observed by hand on one machine with a debug build. | A release build, or any other device. It relies on the two checks above. |
| The Kotlin heap drift check | A large leak would show up. | Where an allocation comes from. A flat heap fits both a callback that allocates nothing and one whose allocations are collected. |
| `source-discipline.sh` | Every memory-ordering decision, including teardown, ring publication and each release and acquire, is still written where the design put it. It rejects a set of planted defects. | That the ordering is correct. It only reads source text. It exists because broken orderings passed the runtime checks, TSan included. TSan detects races, not orderings that are too weak. |

## Two implementations of one ring

`KotlinAudioRing` in `kiteplayer-core` stays. `commonMain` also targets js and wasmJs, which can
never contain C, and the Kotlin ring is the reference that the C ring is checked against.

On macOS, the `AudioRingTest` cases no longer cover the shipped path. The C suites cover it,
together with `AudioRingDifferentialTest` in `kiteplayer-core/src/nativeTest`. That test drives one
scripted sequence through both rings at four sample rates and at one, two, six and eight channels.
It compares the samples bit for bit, the published anchor to the microsecond, and the counters
exactly.

## What is not here

- **tvOS and watchOS.** There is no device code for them. Their entry points return
  `KPRT_SINK_UNSUPPORTED_PLATFORM`, an explicit refusal instead of a link error.
- **The iOS audio session.** iOS uses the same C callback through RemoteIO, but
  `kiteplayer-output` owns the `AVAudioSession`, not this module. The iOS path has run on a
  simulator and links for `iosArm64`. It has not run on a physical iPhone from this module's tests.
- **Targets that only compile.** Every Kotlin/Native target builds an archive here. A target that
  only compiles says nothing about how it behaves.
