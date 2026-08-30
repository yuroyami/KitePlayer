# kiteplayer-rt

The real-time audio core, in C. One allocation at create, no lock anywhere, and no managed code on
the device's thread.

This module exists for one reason, and it is register item B1-17 in `MASTER_PLAN.md`. Until B1.8 the macOS
render callback was a Kotlin lambda whose first instruction was
`refCon.asStableRef<CoreAudioSink>().get()`. That made the device's real-time thread a Kotlin mutator
that the garbage collector has to stop at a safepoint. Thirteen long-lived objects, fourteen atomic
wrappers, two virtual interface calls, a scalar copy loop and up to five transient cinterop views were
on that path. The honest statement was never that audio glitched; it was that the deadline depended on
a pause nobody had bounded. So the callback, the AudioUnit and the sample ring moved here, and
`kiteplayer-output`'s `CoreAudioSink` became a thin owner of two opaque handles.

## Layout

| Path | What it is |
|---|---|
| `native/include/kite_rt.h` | The whole public surface: 28 `KPRT_API` functions and the constants. This is what cinterop reads. |
| `native/src/kite_rt_ring.c` | The ring's producer side and its lifecycle. The only file here that allocates. |
| `native/src/kite_rt_render.c` | The ring renderer and anchor translation unit called by the device callback; no lifecycle code or allocator. |
| `native/src/kite_rt_coreaudio.c` | The device glue and the common `static` callback: DefaultOutput on macOS, RemoteIO on iOS, and explicit refusal elsewhere. |
| `native/src/kite_rt_ring_internal.h`, `kite_rt_sink_internal.h` | The private layouts. Not in `include/`, so Kotlin gets opaque pointers with no field and no size. |
| `native/tests/*.c` | Eight suites, 132 cases, table driven, one line per case. |
| `native/tests/interpose_alloc.c` | The allocation interposer, through the Mach-O `__DATA,__interpose` section. |
| `native/scripts/build-host.sh` | Builds the host test binaries for one variant. No make, no cmake, no ninja: register item B1-15. |
| `native/scripts/run-c-tests.sh` | Runs the eight suites in one of four modes. |
| `native/scripts/render-audit.sh` | The symbol and instruction audit of both real-time objects in the macOS and two gated iOS archives. Assertion 1 of B1.8. |
| `native/scripts/source-discipline.sh` | The eighteen ordering decisions no runtime instrument fully covers. Level 4, and it says so. |
| `src/nativeInterop/cinterop/kitert.def` | The cinterop def. Names the archive and links AudioToolbox on macOS and iOS. |
| `build.gradle.kts` | Registers the seventeen native targets and their C compile tasks. |
| `../buildSrc/src/main/kotlin/CompileKiteRtTask.kt` | Compiles and archives the C per konan target, driving konan's own clang and `llvm-ar` directly. |

## Building and running it

```bash
cd native
./scripts/build-host.sh plain && ./scripts/run-c-tests.sh plain
./scripts/build-host.sh asan  && ./scripts/run-c-tests.sh asan
./scripts/build-host.sh tsan  && ./scripts/run-c-tests.sh tsan
./scripts/run-c-tests.sh interpose      # the plain binaries, with allocation accounting required
./scripts/render-audit.sh               # add --prove-it-can-fail for the seven negative controls
./scripts/source-discipline.sh          # add --prove-it-can-fail for the sixteen planted defects
```

The harness and the interposer exist twice, here and in KiteFFmpeg's `native/kitecodec-c/tests/`,
and the two are a pair: the same mechanism under two prefixes (`KPRT_REQUIRE_ALLOC_ACCOUNTING`
here, `KC_REQUIRE_ALLOC_ACCOUNTING` there), and a fix to either lands in both in the same change.
The interlude ported this tree's require mechanism to KiteFFmpeg after measuring what the
fork had already cost: a one-word blinding of KiteFFmpeg's interposer left its whole ownership gate
green while observing nothing.

From the repository root, the shipped per-target archives are built by Gradle, one directory per
konan target, never shared:

```bash
./gradlew :kiteplayer-rt:compileKiteRtCForMacosArm64 \
  :kiteplayer-rt:compileKiteRtCForIosArm64 \
  :kiteplayer-rt:compileKiteRtCForIosSimulatorArm64
./gradlew :kiteplayer-rt:macosArm64Test
./gradlew :kiteplayer-rt:iosSimulatorArm64Test --device "Test iPhone 17"
```

## The three variants, and one run mode

| Variant | Flags | What it is for |
|---|---|---|
| `plain` | `-O2 -Wall -Wextra -Werror -Werror=vla` | Compile fidelity and correctness. Also the only variant in which the allocation interposer works. |
| `asan` | `-fsanitize=address,undefined -fno-omit-frame-pointer -O1` | An out-of-bounds ring index, a wrap arithmetic mistake, or a signed overflow, named at the byte and the line where it happens. |
| `tsan` | `-fsanitize=thread -O1` | The two seqlocks. A seqlock written with plain or `volatile` fields instead of C11 atomics is a real data race and this variant says so. |

`interpose` is not a fourth build. It runs the `plain` binaries with
`KPRT_REQUIRE_ALLOC_ACCOUNTING=1`, which turns "the interposer was not effective" into a hard failure.
That distinction is the whole point: without it, "the instrument was dead" and "nothing allocated" read
identically. ASan and TSan replace the allocator before dyld reaches the interpose section, so under
those two the interposer is inert and the cases that depend on it report a partial rather than a pass.

## What each instrument can and cannot prove

Plan section 15.2 B1.8 fixes an order of authority for the four assertions behind the claim that the
callback does not allocate, and it fixes it because the obvious instrument does not exist here. There is
no allocation hook in Kotlin/Native, LeakSanitizer is unsupported on macOS arm64, and a malloc
interposer is a false negative for managed allocation: measured, 229 mallocs before and 230 after one
million Kotlin objects, because the runtime takes pages by `mmap` and hands objects out of them. No
report may present a weaker instrument here as a stronger one.

| Instrument | Level | Proves | Cannot prove |
|---|---|---|---|
| `render-audit.sh` | 2 | The shipped render and device-callback objects have no allocator, lock, log or forbidden framework symbol to call on the real-time path. It pins DefaultOutput on macOS, RemoteIO on both iOS archives and the callback call set, and rejects its seven negative controls. | Anything about an allocation the optimiser deleted, and anything about an optional target whose archive was never built. The S1.b.3 gate builds all three named archives and permits no skip. |
| `run-c-tests.sh interpose` | 2 | Zero `malloc`, `calloc`, `realloc`, `free` and `mmap` across five million synthetic callbacks driving the shipped render body. | Anything at all about Kotlin allocation. See above. |
| The supervised device run | 6, a manual observation with saved metrics (one machine, one debug binary, one operator; corrected from a wrongly claimed 1) | The worst callback body against half the device period, with the collector running thousands of times, on a named device. Its negative control fails. | Release-mode qualification, which is B10's, and any platform other than this one. Its authority rests on the two level 2 rows above. |
| The Kotlin heap drift check | 5 | A gross leak would show. | Attribution. A flat heap is equally consistent with a callback that allocates nothing and one that allocates and is collected. |
| `source-discipline.sh` | 4 | That all eighteen ordering decisions are still written where the design put them, including teardown, ring publication and every release/acquire edge. Its sixteen planted defects are rejected. | That the ordering is correct. It reads source text. It exists because mutants on these decisions passed runtime instruments in the gate, including TSan, which grades atomicity and not ordering strength. |

## The two implementations of one ring, permanently

`KotlinAudioRing` in `kiteplayer-core` is not going away, and this is register item B1-20.
`commonMain` targets js and wasmJs, which can never contain C, and the Kotlin ring is the only oracle
the C ring can be checked against. So on macOS the eighteen `AudioRingTest` cases no longer cover the
shipped path. What covers it is the eight C suites plus the differential oracle at
`kiteplayer-core/src/nativeTest/.../AudioRingDifferentialTest.kt`, which drives one scripted sequence
through both rings at four sample rates and at one, two, six and eight channels and compares the
samples bit for bit, the published anchor to the microsecond, and the counters exactly.

## What is not here

No tvOS or watchOS device implementation. Their entry points answer
`KPRT_SINK_UNSUPPORTED_PLATFORM`, which is a loud refusal rather than a link error. iOS now uses the
same pure-C callback and lifecycle through RemoteIO; `kiteplayer-output`, not this module, makes
`AVAudioSession` ownership explicit. That result is limited to the named simulator plus an iosArm64
link. It is not a physical-device result, an iosX64 qualification, a runnable player or a support-tier
claim.

No claim about any target whose archive was only compiled. Seventeen targets build an
architecture-verified archive here; compilation is level 7 evidence and says nothing about behaviour.
