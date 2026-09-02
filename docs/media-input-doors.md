# Media input doors: the spec

> **For whoever executes this, human or AI.** Read `GOTCHAS.md` section 1 (working rules) and
> section 3 (the gate) before touching anything. This document is the written expansion for one
> program of work. It says what to build, in what order, with which tests. It does not replace
> `MASTER_PLAN.md`: when a task below lands, delete its row there in the same commit.

**Goal:** a consumer can hand KitePlayer media from anything Kotlin can read, through a handful of
named, tested doors, without writing byte-reader glue themselves.

**One-line shape:** one seam, many doors. `MediaIo` stays the only byte contract. Every door is a
small factory function that returns one. No new overloads on `KitePlayer.open`. The knobs a player
needs at open time are typed and built with a small DSL; everything else stays a raw string
behind an opt-in.

---

## 1. Rules that bind every task here

Copied from `GOTCHAS.md` so nobody has to go looking. Exact wording there wins on conflict.

- Work on `main`. Never create a branch. Commit locally, never push.
- Commit message: one imperative sentence, short prose body, no trailers of any kind.
- No em dashes anywhere. No register codes in shipped files.
- Every behavioural change: failing test FIRST, watch it fail at the predicted line, fix, watch it
  pass, then break the fix and watch it fail again. A test never seen red proves nothing.
- Design acts are their own commits. Task 0 below is the design commit. Do not fold code into it.
- `explicitApi()` is on everywhere. Any public API change runs `./gradlew updateKotlinAbi` in the
  same commit, and `./gradlew checkKotlinAbi` must pass.
- No new dependency, plugin or toolchain bump without an owner decision. Section 8 lists the
  decisions this spec needs. Do not guess them.
- Gate tier is chosen by changed path (`GOTCHAS.md` section 3). A new module, a `build.gradle.kts`
  edit or a platform source set edit is Tier 2. Run the AGGREGATE tasks (`./gradlew jvmTest`,
  `./gradlew macosArm64Test`), never a hand list of modules.
- `MASTER_PLAN.md` is updated by the same commit that changes the tree.
- KDoc on public things is short. One or two lines when the thing is simple.

---

## 2. What exists today, verified against the tree

| Door | How | File |
|---|---|---|
| Path or URL | `MediaItem(uri = "...")` | `kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt` |
| File descriptor | `MediaItem(uri = "fd:", openOptions = mapOf("fd" to "37"))` | `kiteplayer-ffmpeg/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/FdRewind.android.kt` rewinds it before each open |
| Your own bytes | `MediaItem(io = { myMediaIo })` | `MediaIo` interface, `MediaItem.kt` line 102 |
| URI resolver | `PlayerConfig.network.ioResolver` | `PlayerConfig.kt`, `NetworkConfig` |
| Raw FFmpeg options | `openOptions`, `videoFilter`, `formatHint`, `headers` | `MediaItem.kt`; respelled in `kiteplayer-ffmpeg/.../KiteFFmpegMediaBackend.kt` around line 140 |

FFmpeg's protocol list is pinned to `file, fd, pipe, data, http, tcp` in KiteFFmpeg's build
(`../KiteFFmpeg/buildSrc/src/main/kotlin/BuildFFmpegTask.kt` line 399). No https inside FFmpeg;
https rides `kiteplayer-network`'s Ktor resolver.

The engine wraps every `MediaIo` in `CachingMediaIo` (32 MiB window, `IoCachePolicy`). The FFmpeg
backend adapts the suspending `MediaIo` onto KiteFFmpeg's blocking `MediaByteSource` through
`BlockingMediaIo`. None of that changes here.

**What does not exist:** a `ByteArray` door, any platform file door (`File`, `Path`, Android `Uri`,
`AssetFileDescriptor`, `NSURL`), an `InputStream` door, a push-style source. Synkplay wrote its
own `content://` to `fd:` resolver because nothing here does it.

**Three defects this spec fixes on the way:**

1. `MediaItem.io` is typed `(suspend () -> MediaIo)?`. A paragraph of KDoc explains that it must be
   a factory because opens happen more than once. A type should say that.
2. `GOTCHAS.md` section 4 says "never pass demuxer options from a config map without an
   allowlist". `KiteFFmpegMediaBackend.kt` does `putAll(media.openOptions)` with no filter, and
   `openOptions` carries no opt-in annotation while `videoFilter` does. The rule and the code
   disagree. Section 4.2 below settles it.
3. `MediaItem.headers` never reaches https. `PlaybackCore.kt` line 1833 calls
   `ioResolver?.resolve(item.uri)`: the resolver gets the URI and nothing else, and
   `KtorMediaIoResolver` sends only the headers it was constructed with. Per-item headers on an
   https URL go to FFmpeg's http option funnel, which never opens for that item, and come back as
   an unused-option warning. Section 4.9 fixes the signature.

---

## 3. Design principles

1. **One seam.** `MediaIo` is the only contract a byte source implements. Doors produce it. The
   engine, the cache and the backend bridge never learn about doors.
2. **Doors are factories.** Every door returns a `MediaIoFactory`, never a live reader. The
   engine opens once per session and a track switch, a loop, a recovery or a queue wrap all
   reopen. A door that hands out one reader twice is the bug this rule prevents.
3. **Doors live where their platform type lives.** Common doors in `kiteplayer-core`. Platform
   doors in a new module `kiteplayer-io` (section 4.5 says why a new module). The core keeps its
   law: coroutines and atomicfu only, no platform API.
4. **A plain path stays a plain path.** `MediaItem(uri = "/some/file.mkv")` goes to FFmpeg's own
   `file` protocol and stays the fast path. Doors are for bytes FFmpeg cannot open itself, or for
   lifetimes FFmpeg cannot manage (a content resolver, a security-scoped URL, an asset window,
   an in-memory buffer, a live pipe).
5. **Every door has the same test.** One shared contract test, run against every door with a known
   byte pattern. Then one real-media proof per door family through the FFmpeg backend.
6. **Refuse typed, at construction.** A `MediaItem` that carries an option the engine cannot
   honour refuses in `init` with `IllegalArgumentException`, the same policy `PlayerConfig` uses.

---

## 4. The contracts, exactly

### 4.1 `MediaIoFactory` (core, commonMain, `MediaItem.kt`)

```kotlin
/** Makes a fresh [MediaIo] for one playback session. Called once per open; opens happen more than once. */
public fun interface MediaIoFactory {
    public suspend fun open(): MediaIo
}
```

`MediaItem.io` changes from `(suspend () -> MediaIo)?` to `MediaIoFactory?`. Kotlin converts a
lambda at the call site, so `MediaItem("x", io = { reader })` still compiles. Every existing test
that passes a lambda keeps compiling. The ABI dump moves; regenerate it.

`MediaBackend.open` KDoc already says the factory is invoked at most once per call. Keep that.

### 4.2 `openOptions` guard (core, commonMain, `MediaItem.kt`)

Decision recommended, owner confirms (section 8, decision A): **a short refusal list plus the
low-level opt-in, not an allowlist.** An allowlist would have to enumerate FFmpeg's option space
and would refuse Synkplay's `"fd"` key on day one. The escape hatch stays an escape hatch, but the
two keys that break engine laws are refused, and the property is marked the same way
`videoFilter` already is.

```kotlin
@property:KitePlayerLowLevelApi
val openOptions: Map<String, String> = emptyMap(),
```

In `MediaItem`'s `init`:

```kotlin
init {
    openOptions["fflags"]?.let { flags ->
        require("fastseek" !in flags.split(',', '+')) {
            "openOptions fflags=$flags: fastseek breaks exact seeking and is never applied"
        }
    }
    require("usetoc" !in openOptions) {
        "openOptions usetoc: the engine owns MP3 seek strategy and this key is never applied"
    }
}
```

Why these two and no more: `GOTCHAS.md` section 4 states that MP3 seek correctness rests on
`usetoc=0` AND fast-seek unset together. Nothing else in the option space contradicts an engine
law today. Add a key here only when a law is found that it breaks.

The GOTCHAS line is rewritten in Task 0 to match: "Demuxer options from a consumer map: refuse the
keys that break an engine law (`fflags` with `fastseek`, `usetoc`); everything else passes under
the low-level opt-in and is reported typed when unused."

Synkplay uses `openOptions["fd"]` and will need `@OptIn(KitePlayerLowLevelApi::class)` at its call
site when it next bumps. The Android door in 4.6 is what replaces that use.

### 4.3 Common doors (core, commonMain, new file `MediaIoDoors.kt`)

`MediaIo` gains an empty `public companion object` so doors read as `MediaIo.ofBytes(...)`.

```kotlin
/** Plays bytes already in memory. Seekable, size known. Cheap to reopen: nothing is copied. */
public fun MediaIo.Companion.ofBytes(bytes: ByteArray): MediaIoFactory =
    MediaIoFactory { ByteArrayMediaIo(bytes) }

internal class ByteArrayMediaIo(private val bytes: ByteArray) : MediaIo {
    private var position = 0
    private var closed = false
    override val size: Long get() = bytes.size.toLong()
    override val seekable: Boolean get() = true
    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
        check(!closed) { "read after close" }
        if (length <= 0) return 0
        if (position >= bytes.size) return -1
        val count = minOf(length, bytes.size - position)
        bytes.copyInto(into, offset, position, position + count)
        position += count
        return count
    }
    override suspend fun seek(position: Long) {
        check(!closed) { "seek after close" }
        require(position in 0..bytes.size) { "seek to $position outside 0..${bytes.size}" }
        this.position = position.toInt()
    }
    override fun close() { closed = true }
}
```

A `MediaItem` helper so the common case is one line:

```kotlin
public companion object {
    /** A media item read through [io]. [label] is what logs and a track menu show. */
    public fun from(io: MediaIoFactory, label: String): MediaItem = MediaItem(uri = label, io = io)
}
```

### 4.4 Push source: `PipedMediaIo` (core, commonMain, new file `PipedMediaIo.kt`)

For producers that push: a socket you read yourself, a decryptor, a download in flight.
Unseekable, size unknown. The reader side is a `MediaIo`; the producer side is three calls.

```kotlin
/**
 * A bounded byte pipe. Your code writes, the engine reads. Unseekable, so it behaves like a live
 * stream: video track switches are refused (they reopen the container), audio and subtitle
 * switches work. Single use: a reopen after [finish] sees end of stream at once.
 */
public class PipedMediaIo(capacityChunks: Int = 16) : MediaIo {
    private val chunks = Channel<ByteArray>(capacityChunks)
    private var pending: ByteArray? = null
    private var pendingOffset = 0
    private var closed = false

    override val size: Long? get() = null
    override val seekable: Boolean get() = false

    /** Suspends while the pipe is full. Copies the slice; the caller may reuse [bytes] after return. */
    public suspend fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) { "bad slice $offset+$length of ${bytes.size}" }
        var start = offset
        val end = offset + length
        while (start < end) {
            val take = minOf(CHUNK, end - start)
            chunks.send(bytes.copyOfRange(start, start + take))
            start += take
        }
    }

    /** No more bytes will come. The engine sees end of stream after draining what was written. */
    public fun finish() { chunks.close() }

    /** The producer failed. The engine's next read throws [cause], and the open or playback fails typed. */
    public fun fail(cause: Throwable) { chunks.close(cause) }

    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
        check(!closed) { "read after close" }
        if (length <= 0) return 0
        var buffer = pending
        if (buffer == null || pendingOffset >= buffer.size) {
            val result = chunks.receiveCatching()
            if (result.isClosed) {
                result.exceptionOrNull()?.let { throw it }
                return -1
            }
            buffer = result.getOrThrow()
            pending = buffer
            pendingOffset = 0
        }
        val count = minOf(length, buffer.size - pendingOffset)
        buffer.copyInto(into, offset, pendingOffset, pendingOffset + count)
        pendingOffset += count
        return count
    }

    override suspend fun seek(position: Long): Unit = error("PipedMediaIo is not seekable")

    override fun close() {
        closed = true
        chunks.cancel()
    }

    private companion object { const val CHUNK = 256 * 1024 }
}
```

Capacity is `capacityChunks * 256 KiB`, 4 MiB by default. Say that in the KDoc.

A pipe is single use, so it is NOT wrapped in a factory by the library. The consumer writes
`MediaItem.from(MediaIoFactory { pipe }, "live")` and accepts that a reopen fails. That is the
same limit live network media already has (`MASTER_PLAN.md` section 7.3).

### 4.5 The new module `kiteplayer-io`

**Why a new module and not `kiteplayer-core`'s platform source sets:** the core's law is no
platform API (README, "Why the engine has no platform code"). A `java.io.File` door in the core
breaks that law and the README sentence with it.

**Why not `kiteplayer-ffmpeg`:** a file door has nothing to do with FFmpeg, and putting it there
makes it unavailable to any other backend. `FdRewind` lives there only because the `fd:`
protocol is FFmpeg's.

**Shape:** pure Kotlin plus platform stdlib. Depends on `:kiteplayer-core` only, `api`. Publishes.
Target list copied from `kiteplayer-subtitles/build.gradle.kts` (the widest pure-Kotlin matrix in
the repo), namespace `io.github.yuroyami.kiteplayer.io`. Source sets that carry code:

| Source set | Doors |
|---|---|
| `jvmAndAndroidMain` | `FileChannelMediaIo`, `InputStreamMediaIo`, `ofFile`, `ofPath`, `ofChannel`, `ofStream` |
| `androidMain` | `ofUri`, `ofAsset` |
| `posixMain` (apple + linux, new intermediate set) | `PosixFileMediaIo`, `ofPath(String)` |
| `appleMain` | `ofUrl(NSURL)` |
| `jsMain`, `wasmJsMain`, `mingwMain`, other native | nothing. Honest placeholders like every other module. |

`kiteplayer-mobile` adds `api(project(":kiteplayer-io"))` in `commonMain` so the default stack
carries the doors without another dependency line.

Bookkeeping that ships with the module, all in Task 4: `settings.gradle.kts` include with a
two-line comment like its neighbours; README `Modules` table row; the README sentence "Ten modules
publish in total" becomes eleven; `publish.yml`'s "12 modules" comment and job name become 13;
`checkPublicationReadiness` must pass; `ci.yml` job lists that name modules get the new one where
a test source set exists.

### 4.6 The doors, exact signatures

All in package `io.github.yuroyami.kiteplayer.io`. All return `MediaIoFactory`. All open their
own handle inside the factory, per session, and close it in `close()`.

**`jvmAndAndroidMain`, file `FileChannelMediaIo.kt`:**

```kotlin
/** Positional reads on a [FileChannel]: no shared cursor, so two sessions on one file cannot disturb each other. */
public fun MediaIo.Companion.ofChannel(open: () -> FileChannel): MediaIoFactory =
    MediaIoFactory { FileChannelMediaIo(open()) }

public fun MediaIo.Companion.ofFile(file: File): MediaIoFactory =
    ofChannel { FileChannel.open(file.toPath(), StandardOpenOption.READ) }

public fun MediaIo.Companion.ofPath(path: Path): MediaIoFactory =
    ofChannel { FileChannel.open(path, StandardOpenOption.READ) }

internal class FileChannelMediaIo(
    private val channel: FileChannel,
    private val start: Long = 0L,
    length: Long? = null,
) : MediaIo {
    private val end: Long = length?.let { start + it } ?: channel.size()
    private var position = start
    override val size: Long get() = end - start
    override val seekable: Boolean get() = true
    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
        if (length <= 0) return 0
        if (position >= end) return -1
        val want = minOf(length.toLong(), end - position).toInt()
        val count = channel.read(ByteBuffer.wrap(into, offset, want), position)
        if (count <= 0) return -1
        position += count
        return count
    }
    override suspend fun seek(position: Long) {
        require(position in 0..size) { "seek to $position outside 0..$size" }
        this.position = start + position
    }
    override fun close() { channel.close() }
}
```

`start` and `length` exist for the Android asset window; the JVM doors pass defaults.

**`jvmAndAndroidMain`, file `InputStreamMediaIo.kt`:**

```kotlin
/** Forward only. The engine treats it as live: no seeking, no video track switch. */
public fun MediaIo.Companion.ofStream(open: () -> InputStream): MediaIoFactory =
    MediaIoFactory { InputStreamMediaIo(open()) }

internal class InputStreamMediaIo(private val stream: InputStream) : MediaIo {
    override val size: Long? get() = null
    override val seekable: Boolean get() = false
    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
        if (length <= 0) return 0
        return stream.read(into, offset, length)   // -1 at end, never 0 for length > 0
    }
    override suspend fun seek(position: Long): Unit = error("stream is not seekable")
    override fun close() { stream.close() }
}
```

**`androidMain`, file `AndroidMediaIo.kt`:**

```kotlin
/**
 * A picked file, a media store item, anything a content provider serves. Seekable when the provider
 * hands back a real file; a provider that answers with a pipe plays forward only.
 */
public fun MediaIo.Companion.ofUri(resolver: ContentResolver, uri: Uri): MediaIoFactory = MediaIoFactory {
    val pfd = resolver.openFileDescriptor(uri, "r") ?: error("no descriptor for $uri")
    if (pfd.statSize >= 0) {
        PfdChannelMediaIo(pfd)
    } else {
        InputStreamMediaIo(ParcelFileDescriptor.AutoCloseInputStream(pfd))
    }
}

/** A file under `src/main/assets`. The asset's window inside the APK is honoured, so seeks stay inside it. */
public fun MediaIo.Companion.ofAsset(assets: AssetManager, name: String): MediaIoFactory = MediaIoFactory {
    val afd = assets.openFd(name)     // throws for a compressed asset; say so in the KDoc
    AfdChannelMediaIo(afd)
}

/** Owns the descriptor: the channel reads it positionally, and closing the reader closes both. */
internal class PfdChannelMediaIo(private val pfd: ParcelFileDescriptor) : MediaIo {
    private val stream = FileInputStream(pfd.fileDescriptor)
    private val inner = FileChannelMediaIo(stream.channel)
    override val size: Long? get() = inner.size
    override val seekable: Boolean get() = true
    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int = inner.read(into, offset, length)
    override suspend fun seek(position: Long) = inner.seek(position)
    override fun close() { inner.close(); stream.close(); pfd.close() }
}

internal class AfdChannelMediaIo(private val afd: AssetFileDescriptor) : MediaIo {
    private val stream = FileInputStream(afd.fileDescriptor)
    private val inner = FileChannelMediaIo(stream.channel, afd.startOffset, afd.length)
    override val size: Long? get() = inner.size
    override val seekable: Boolean get() = true
    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int = inner.read(into, offset, length)
    override suspend fun seek(position: Long) = inner.seek(position)
    override fun close() { inner.close(); stream.close(); afd.close() }
}
```

This is the door that retires Synkplay's hand-written resolver and reduces `MASTER_PLAN.md`
section 7.1's positional-read bullet: the descriptor is ours, opened per session, read
positionally, so the shared-offset defect cannot occur by construction. The `fd:` protocol route
keeps working for anyone already on it.

**`posixMain`, file `PosixFileMediaIo.kt`:**

```kotlin
/** Opens the path itself, per session, and reads with pread. For paths FFmpeg cannot be handed a plain string for. */
public fun MediaIo.Companion.ofPath(path: String): MediaIoFactory =
    MediaIoFactory { PosixFileMediaIo(path) }

internal class PosixFileMediaIo(path: String) : MediaIo {
    private val fd: Int = open(path, O_RDONLY).also { if (it < 0) throw IOException("open($path): errno $errno") }
    override val size: Long
    private var position = 0L
    init {
        size = memScoped {
            val st = alloc<stat>()
            if (fstat(fd, st.ptr) != 0) { close(fd); throw IOException("fstat($path): errno $errno") }
            st.st_size.toLong()
        }
    }
    override val seekable: Boolean get() = true
    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
        if (length <= 0) return 0
        if (position >= size) return -1
        val want = minOf(length.toLong(), size - position).toInt()
        val count = into.usePinned { pread(fd, it.addressOf(offset), want.convert(), position.convert()).toInt() }
        if (count < 0) throw IOException("pread: errno $errno")
        if (count == 0) return -1
        position += count
        return count
    }
    override suspend fun seek(position: Long) {
        require(position in 0..size) { "seek to $position outside 0..$size" }
        this.position = position
    }
    override fun close() { platform.posix.close(fd) }
}
```

`IOException` here is `kotlinx.io.IOException` only if that dependency exists (decision C). Without
it, declare a tiny `public class MediaIoException(message: String) : Exception(message)` in
`kiteplayer-io` commonMain and use that everywhere in the module. Default to the second.

`stat` field types differ per platform (`st_size` is `off_t`, which is `Long` on Apple and Linux
x64, but check `linuxArm64` compiles). `pread` argument types need `.convert()` on both size and
offset; the compiler tells you which. Compile `linuxX64` and `iosArm64` before writing tests.

**`appleMain`, file `AppleMediaIo.kt`:**

```kotlin
/**
 * A file URL, including one from a document picker. Security-scoped access is started when the
 * session opens and stopped when it closes, which a plain path string cannot do.
 */
public fun MediaIo.Companion.ofUrl(url: NSURL): MediaIoFactory = MediaIoFactory {
    val path = url.path ?: error("not a file URL: $url")
    val scoped = url.startAccessingSecurityScopedResource()
    try {
        ScopedMediaIo(PosixFileMediaIo(path), url, scoped)
    } catch (t: Throwable) {
        if (scoped) url.stopAccessingSecurityScopedResource()
        throw t
    }
}

internal class ScopedMediaIo(private val inner: MediaIo, private val url: NSURL, private val scoped: Boolean) : MediaIo by inner {
    override fun close() {
        inner.close()
        if (scoped) url.stopAccessingSecurityScopedResource()
    }
}
```

`startAccessingSecurityScopedResource` answers false for a URL that needs no scope (the app's own
container). That is not an error; the flag just decides whether to stop later.

### 4.7 What stays out, named so nobody re-adds it by accident

- `SubtitleSource.io`: declared and documented unwired. Stays that way; it rides the subtitle
  program in `MASTER_PLAN.md` Phase 4.
- kotlinx-io and okio adapters: decision C. Default no.
- Windows (`mingwX64`) doors: no output backend exists there and nobody has run the target. Add
  when Windows plays.
- A disk cache, retry or concat wrapper over `MediaIo`: network program, Phase 7.
- A `Flow<ByteArray>` door: `PipedMediaIo` plus a ten-line collector covers it; do not add API for
  it until a consumer asks.
- Overloads on `KitePlayer.open`. Never.
- Typed http knobs beyond headers (`userAgent`, `timeout`, `reconnect`): `kiteplayer-network` has no
  timeout, retry or reconnect today, so a typed field there would be a promise the Ktor path cannot
  keep. They ride Phase 7. A `User-Agent` header already works through `headers`.
- `seek2any`: lands on non-keyframes, which decodes garbage. Not a player knob.
- A generated, exhaustive typed surface over every FFmpeg option (`av_opt_next` over every demuxer
  and protocol class of the linked build). Real, and the wrong size: hundreds of types that change
  with the recipe, and a new C entry point. Recorded as a KiteFFmpeg horizon item in
  `MASTER_PLAN.md`, not scheduled.

### 4.8 Typed open options, the player layer (core, commonMain)

FFmpeg has hundreds of open-time options, and the set differs per build: this profile links six
protocols, so `http`'s thirty exist and `rtsp`'s do not. Typing all of them is the wrong size.
The player types the knobs a player needs, in its own words, and the FFmpeg backend translates.
This is already how `headers` and `formatHint` work; the new type extends that.

The core never learns an FFmpeg key name. Translation and the collision check both live in
`kiteplayer-ffmpeg` (4.8.3).

**4.8.1 The types**, new file `kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/DemuxPolicy.kt`:

```kotlin
/** How hard to look at the container before playing. */
public sealed interface ProbeDepth {
    /** The backend's own defaults. Right for files with a proper index, which is most of them. */
    public data object Default : ProbeDepth

    /** 512 KiB and 200 ms. Opens fast; can miss a stream that starts late in a transport stream. */
    public data object Fast : ProbeDepth

    /** 64 MiB and 20 s. Finds everything; slow on a network source. */
    public data object Thorough : ProbeDepth

    public data class Custom(val bytes: Long, val duration: Duration) : ProbeDepth {
        init {
            require(bytes > 0 && duration > Duration.ZERO) { "probe needs positive bytes and duration, got $bytes and $duration" }
        }
    }
}

/** What to do with a packet the container marks as damaged. */
public enum class CorruptPackets { Keep, Drop }

/**
 * Container-open knobs, typed. Every field is honoured by the backend or refused typed; none is
 * silently ignored. The defaults are the backend's defaults, so `DemuxPolicy()` changes nothing.
 */
public data class DemuxPolicy(
    val probe: ProbeDepth = ProbeDepth.Default,
    val corruptPackets: CorruptPackets = CorruptPackets.Keep,
    /** Rebuild presentation timestamps from decode order, for a file whose muxer wrote none. */
    val generateTimestamps: Boolean = false,
    /** Hand packets on as they arrive, no read-ahead. For live sources; costs smoothness on a file. */
    val lowLatency: Boolean = false,
    /** Bytes to skip before probing, for a file with junk in front of its header. */
    val skipInitialBytes: Long = 0,
) {
    init {
        require(skipInitialBytes >= 0) { "skipInitialBytes must not be negative, was $skipInitialBytes" }
    }
}
```

`MediaItem` gains one field, between `formatHint` and `openOptions`:

```kotlin
/** Typed container-open knobs. See [DemuxPolicy]. */
val demux: DemuxPolicy = DemuxPolicy(),
```

**4.8.2 The builder**, new file `kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItemBuilder.kt`.
The data class stays the value; the builder is the pleasant way to make one. House style for the
DSL is KiteFFmpeg's `videoFilters { }`: a `@DslMarker`, one receiver, nothing to leak.

```kotlin
@DslMarker
public annotation class MediaItemDsl

/** Builds a [MediaItem]. Every method is optional; an empty block is `MediaItem(uri)`. */
@MediaItemDsl
public class MediaItemBuilder internal constructor(private val uri: String) {
    private val headers = LinkedHashMap<String, String>()
    private val subtitles = ArrayList<SubtitleSource>()
    private var start: Duration? = null
    private var io: MediaIoFactory? = null
    private var formatHint: String? = null
    private var demux = DemuxPolicy()
    private var videoFilter: String? = null
    private val raw = LinkedHashMap<String, String>()

    public fun header(name: String, value: String) { headers[name] = value }
    public fun headers(vararg pairs: Pair<String, String>) { pairs.forEach { (n, v) -> headers[n] = v } }
    public fun startAt(position: Duration) { start = position }
    public fun subtitle(uri: String, title: String? = null, language: String? = null, selectImmediately: Boolean = false) {
        subtitles += SubtitleSource(uri, title, language, selectImmediately)
    }
    public fun io(factory: MediaIoFactory) { io = factory }
    public fun format(name: String) { formatHint = name }
    public fun probe(depth: ProbeDepth) { demux = demux.copy(probe = depth) }
    public fun corruptPackets(policy: CorruptPackets) { demux = demux.copy(corruptPackets = policy) }
    public fun generateTimestamps() { demux = demux.copy(generateTimestamps = true) }
    public fun lowLatency() { demux = demux.copy(lowLatency = true) }
    public fun skipBytes(count: Long) { demux = demux.copy(skipInitialBytes = count) }

    /** A raw FFmpeg filter chain. You have decided to depend on FFmpeg. */
    @KitePlayerLowLevelApi
    public fun videoFilter(chain: String) { videoFilter = chain }

    /** A raw open option. Refused if it sets what a typed call above already set. */
    @KitePlayerLowLevelApi
    public fun raw(key: String, value: String) { raw[key] = value }

    @OptIn(KitePlayerLowLevelApi::class)
    internal fun build(): MediaItem = MediaItem(
        uri = uri,
        headers = headers.toMap(),
        externalSubtitles = subtitles.toList(),
        videoFilter = videoFilter,
        startPosition = start,
        io = io,
        formatHint = formatHint,
        demux = demux,
        openOptions = raw.toMap(),
    )
}

public fun mediaItem(uri: String, block: MediaItemBuilder.() -> Unit = {}): MediaItem =
    MediaItemBuilder(uri).apply(block).build()
```

What it reads like:

```kotlin
val item = mediaItem("https://cdn.example/movie.mkv") {
    headers("Authorization" to "Bearer $token")
    startAt(90.seconds)
    probe(ProbeDepth.Thorough)
    corruptPackets(CorruptPackets.Drop)
    subtitle("/sdcard/en.srt", language = "en")
}
```

**4.8.3 Translation and collisions**, in `kiteplayer-ffmpeg`, beside the existing respelling in
`KiteFFmpegMediaBackend.kt` (the function around line 140 that builds the pre-open map):

```kotlin
/** The FFmpeg keys one [DemuxPolicy] sets. Pure, so the golden test can pin it. */
internal fun DemuxPolicy.toFFmpegOptions(): Map<String, String> = buildMap {
    when (val depth = probe) {
        ProbeDepth.Default -> Unit
        ProbeDepth.Fast -> { put("probesize", "524288"); put("analyzeduration", "200000") }
        ProbeDepth.Thorough -> { put("probesize", "67108864"); put("analyzeduration", "20000000") }
        is ProbeDepth.Custom -> {
            put("probesize", depth.bytes.toString())
            put("analyzeduration", depth.duration.inWholeMicroseconds.toString())
        }
    }
    val flags = buildList {
        if (corruptPackets == CorruptPackets.Drop) add("discardcorrupt")
        if (generateTimestamps) add("genpts")
        if (lowLatency) add("nobuffer")
    }
    if (flags.isNotEmpty()) put("fflags", flags.joinToString("+", prefix = "+"))
    if (lowLatency) put("max_delay", "0")
    if (skipInitialBytes > 0) put("skip_initial_bytes", skipInitialBytes.toString())
}
```

The pre-open map is then: typed keys first (`headers`, `format_whitelist`, and everything
`toFFmpegOptions()` produced), raw keys after. A raw key that is already present is a collision
(decision E, recommended refuse):

```kotlin
val typed: Map<String, String> = /* headers, format_whitelist, demux.toFFmpegOptions() */
for (key in media.openOptions.keys) {
    if (key in typed) {
        throw PlaybackException(
            PlaybackError.ConfigurationInvalid(
                "openOptions '$key' collides with the typed field that sets it; use one or the other",
            ),
        )
    }
}
```

`MediaBackend.open` may throw and the engine turns it into a typed failure, so this refuses at
open with a message naming the key. The two KDoc sentences on `MediaItem.headers` and
`MediaItem.formatHint` that say "an explicit key there wins" are rewritten to say "collides and is
refused". `PreOpenOptionsTest.kt` pins the old rule and is rewritten to pin the new one.

### 4.9 The resolver sees the item (core, `MediaItem.kt`; network, `KtorMediaIo.kt`)

```kotlin
public fun interface MediaIoResolver {
    /**
     * A new [MediaIo] for [item], or null when this resolver does not handle it. Read
     * [MediaItem.uri] to decide, and [MediaItem.headers] to honour what the caller asked for.
     */
    public suspend fun resolve(item: MediaItem): MediaIo?
}
```

`PlaybackCore.kt` line 1833 becomes `config.network.ioResolver?.resolve(item)`.

`KtorMediaIoResolver.resolve(item)` merges: its own constructor headers first, the item's on top,
so a per-item `Authorization` wins over a resolver-wide default and a resolver-wide `User-Agent`
still applies when the item says nothing. Then `KtorMediaIo.open(item.uri, shared, merged)`.

This is a public SPI change. Anyone who wrote a resolver changes one parameter type. Pre-1.0, and
the alternative is a second resolver interface, which is worse.

### 4.10 The KiteFFmpeg sibling: `DemuxOptions` (other repository)

KiteFFmpeg already has the shape for decoders:
`kiteffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteffmpeg/dsl/DecoderOptions.kt`, a data
class with typed knobs, a raw `options` escape hatch, `compile()` to option pairs, named presets,
and a golden test in `dsl/KdGoldensTest.kt`. The demux half is the missing sibling. Same package,
same shape:

```kotlin
public enum class DemuxFlag(internal val ff: String) {
    DiscardCorrupt("discardcorrupt"), GenPts("genpts"), NoBuffer("nobuffer"), IgnIdx("ignidx"), IgnDts("igndts"),
}

/** Typed pre-open options, applied between allocation and open. Same two laws as [DecoderOptions]. */
public data class DemuxOptions(
    val probeSizeBytes: Long? = null,
    val analyzeDuration: Duration? = null,
    val flags: Set<DemuxFlag> = emptySet(),
    val maxDelay: Duration? = null,
    val skipInitialBytes: Long? = null,
    val formatWhitelist: List<String> = emptyList(),
    /** The escape hatch: raw pairs for anything the typed set lacks. A key a typed field also sets is refused. */
    val options: Map<String, String> = emptyMap(),
) {
    public fun compile(): List<Pair<String, String>> {
        val typed = buildList {
            probeSizeBytes?.let { add("probesize" to it.toString()) }
            analyzeDuration?.let { add("analyzeduration" to it.inWholeMicroseconds.toString()) }
            if (flags.isNotEmpty()) add("fflags" to flags.sortedBy { it.ordinal }.joinToString("+", prefix = "+") { it.ff })
            maxDelay?.let { add("max_delay" to it.inWholeMicroseconds.toString()) }
            skipInitialBytes?.let { add("skip_initial_bytes" to it.toString()) }
            if (formatWhitelist.isNotEmpty()) add("format_whitelist" to formatWhitelist.joinToString(","))
        }
        val typedKeys = typed.mapTo(HashSet()) { it.first }
        options.keys.firstOrNull { it in typedKeys }?.let {
            throw IllegalArgumentException("DemuxOptions.options '$it' collides with the typed field that sets it")
        }
        return typed + options.map { (k, v) -> k to v }
    }

    public companion object {
        /** A live source: no read-ahead, nothing held back. */
        public val Live: DemuxOptions = DemuxOptions(flags = setOf(DemuxFlag.NoBuffer), maxDelay = Duration.ZERO)

        /** Find every stream, however late it starts. */
        public val Thorough: DemuxOptions = DemuxOptions(probeSizeBytes = 64L * 1024 * 1024, analyzeDuration = 20.seconds)
    }
}
```

Two overloads on `MediaSource.Companion`, each delegating to the map overload that exists:

```kotlin
public fun open(path: String, demux: DemuxOptions): MediaSource = open(path, demux.compile().toMap())
public fun open(io: MediaByteSource, demux: DemuxOptions): MediaSource = open(io, demux.compile().toMap())
```

This does NOT block the player tasks. `kiteplayer-ffmpeg` translates `DemuxPolicy` itself (4.8.3)
and keeps working against the published `kiteffmpeg`. When a `kiteffmpeg` carrying `DemuxOptions`
is published and the pin moves, `toFFmpegOptions()` may be rewritten as a `DemuxOptions` build plus
`compile()`, and the golden test keeps the answer identical. Optional, one small commit, no rush.

---

## 5. The shared contract test

One abstract test, subclassed per door. It lives in `kiteplayer-core/src/commonTest` and is COPIED
verbatim into `kiteplayer-io/src/commonTest` with a header comment naming the original, because
Kotlin Multiplatform has no shared test fixtures between modules. The copy changes only its
`package` line to `io.github.yuroyami.kiteplayer.io`. When one copy changes, change the other in
the same commit.

```kotlin
package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Every door passes this. Subclasses supply a factory over a known byte pattern. */
abstract class MediaIoContract {

    /** A factory whose readers produce exactly [expected]. */
    abstract fun factory(expected: ByteArray): MediaIoFactory

    /** The pattern: 0..255 repeated, so a byte over 0x7F that got sign-mangled shows. */
    protected val pattern: ByteArray = ByteArray(300_000) { (it % 256).toByte() }

    private suspend fun MediaIo.readAll(chunk: Int = 7_001): ByteArray {
        val out = ArrayList<Byte>()
        val buf = ByteArray(chunk)
        while (true) {
            val n = read(buf, 0, buf.size)
            if (n < 0) break
            check(n > 0 || !seekable) { "a seekable reader returned 0 bytes" }
            for (i in 0 until n) out.add(buf[i])
        }
        return out.toByteArray()
    }

    @Test
    fun fullReadMatchesThePattern() = runTest {
        val io = factory(pattern).open()
        try { assertContentEquals(pattern, io.readAll()) } finally { io.close() }
    }

    @Test
    fun endOfStreamIsMinusOneAndStaysMinusOne() = runTest {
        val io = factory(pattern).open()
        try {
            io.readAll()
            assertEquals(-1, io.read(ByteArray(16), 0, 16))
            assertEquals(-1, io.read(ByteArray(16), 0, 16))
        } finally { io.close() }
    }

    @Test
    fun seekThenReadLandsExactly() = runTest {
        val io = factory(pattern).open()
        try {
            if (!io.seekable) return@runTest
            io.seek(100_003)
            val buf = ByteArray(5)
            assertEquals(5, io.read(buf, 0, 5))
            assertContentEquals(pattern.copyOfRange(100_003, 100_008), buf)
            assertEquals(pattern.size.toLong(), io.size)
        } finally { io.close() }
    }

    @Test
    fun closeTwiceIsHarmless() = runTest {
        val io = factory(pattern).open()
        io.close()
        io.close()
    }

    @Test
    fun theFactoryGivesAFreshReaderEveryTime() = runTest {
        val f = factory(pattern)
        val first = f.open()
        val head = ByteArray(10)
        first.read(head, 0, 10)
        first.close()
        val second = f.open()
        try {
            val again = ByteArray(10)
            assertEquals(10, second.read(again, 0, 10))
            assertContentEquals(head, again)
        } finally { second.close() }
    }
}
```

`closeTwiceIsHarmless` exists because `MediaIo.close` is documented to tolerate a second call and
the engine relies on it during a failed open. A door that throws there turns a handled failure
into a new one.

---

## 6. Tasks, in order

Each task is one commit unless it says otherwise. Each names its gate tier. Each ends by deleting
or reducing its `MASTER_PLAN.md` row.

### Task 0: the design commit (Tier 1, prose only)

- Add this file at `docs/media-input-doors.md`.
- `MASTER_PLAN.md`: add section "5.5 Media input doors and typed open options" under Phase 5 with
  one paragraph pointing here, the task list below as checkboxes, the https-headers defect named,
  and decisions A to E listed as the owner's. Reduce section 7.1's positional-read bullet to
  "lands with `MediaIo.ofUri` in docs/media-input-doors.md Task 6". Add the generated-option-surface
  horizon item to Phase 9. Add the Synkplay bump note to section 0.1.
- `GOTCHAS.md` section 4: rewrite the allowlist sentence as section 4.2 above says. This line
  waits for decision A; everything else in this task does not.
- No code. Commit: `design: media input doors and typed open options`.

### Task 1: `MediaIoFactory`, and the `openOptions` guard (Tier 1)

**Files:** modify `kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt`;
test `kiteplayer-core/src/commonTest/kotlin/io/github/yuroyami/kiteplayer/MediaItemTest.kt` (new).

- Red first: a test constructing `MediaItem("x", openOptions = mapOf("fflags" to "+fastseek"))`
  expects `IllegalArgumentException`; another for `usetoc`; a third asserts
  `mapOf("fd" to "12", "probesize" to "1000")` is accepted. Run
  `./gradlew :kiteplayer-core:jvmTest --tests '*MediaItemTest*'`, watch the first two fail.
- Add the `init` block and the annotation from 4.2. Change `io`'s type to `MediaIoFactory?` and
  add the interface. Every `io = { ... }` call site keeps compiling; run `./gradlew jvmTest` and one
  native compile (`./gradlew :kiteplayer-core:compileKotlinMacosArm64`) to be sure.
- `./gradlew updateKotlinAbi`, then `./gradlew checkKotlinAbi`.
- Falsify: remove one `require`, watch its test go red, restore.
- Commit: `core: the io factory is a type, and two demuxer options are refused`.

### Task 2: `MediaIo.ofBytes` and the contract test (Tier 1)

**Files:** create `kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaIoDoors.kt`;
create `kiteplayer-core/src/commonTest/kotlin/io/github/yuroyami/kiteplayer/MediaIoContract.kt`
(section 5) and `ByteArrayMediaIoTest.kt`:

```kotlin
class ByteArrayMediaIoTest : MediaIoContract() {
    override fun factory(expected: ByteArray) = MediaIo.ofBytes(expected)
}
```

- Red first: the test does not compile (no `ofBytes`). Add the companion, the door and
  `MediaItem.from`. Green on `jvmTest` and `macosArm64Test` for the core.
- Real-media proof, in `kiteplayer-ffmpeg/src/nativeTest/.../MediaIoBridgeTest.kt`: one new test
  mirroring the existing one at line 74, using `MediaIo.ofBytes(readFile("$mediaDir/subbed.mkv"))`
  instead of the hand-written `SuspendingMemoryIo`. Same assertions. `readFile` and `mediaDir`
  already exist in that file's neighbours.
- ABI dump moves. Commit: `core: a byte-array door, and the contract every door passes`.

### Task 3: `PipedMediaIo` (Tier 1)

**Files:** create `kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PipedMediaIo.kt`;
test `PipedMediaIoTest.kt` in core commonTest.

- Contract subclass: the factory launches a producer that writes `expected` in 1,000-byte slices
  on `backgroundScope` (kotlinx-coroutines-test) then calls `finish()`. Note
  `theFactoryGivesAFreshReaderEveryTime` needs a NEW pipe per `open()`, so the subclass builds one
  per call. That is the honest shape: the library does not pretend a pipe reopens.
- Three more tests: `write` suspends when the pipe is full (fill 17 chunks with a
  `withTimeoutOrNull(100.milliseconds)` around the 17th and assert null); `fail(cause)` makes the
  next `read` throw that cause; `close()` unblocks a suspended `write` (it must not hang forever;
  expect `CancellationException` or `ClosedSendChannelException`, assert one of them).
- Commit: `core: a bounded pipe for sources that push`.

### Task 4: the `kiteplayer-io` module, and the JVM doors (Tier 2)

**Files:** create `kiteplayer-io/build.gradle.kts` (copy `kiteplayer-subtitles/build.gradle.kts`,
change the namespace and the header comment, keep the target list identical); modify
`settings.gradle.kts`, `README.md` (Modules table and the "Ten modules" sentence),
`.github/workflows/publish.yml` (the two "12" mentions), `.github/workflows/ci.yml` (add
`:kiteplayer-io:jvmTest` and `:kiteplayer-io:macosArm64Test` beside their neighbours; the file
explains why aggregate tasks are not used there, follow its pattern); create
`kiteplayer-io/src/jvmAndAndroidMain/kotlin/io/github/yuroyami/kiteplayer/io/FileChannelMediaIo.kt`
and `InputStreamMediaIo.kt`; tests in `kiteplayer-io/src/jvmTest/...`.

- `jvmAndAndroidMain` is an intermediate source set; declare it the way `kiteplayer-core`'s
  build file does (grep `jvmAndAndroidMain` there and copy the block).
- Copy `MediaIoContract.kt` into `kiteplayer-io/src/commonTest` with the header comment.
- Tests, `jvmTest`: `FileChannelMediaIoTest : MediaIoContract` writes the pattern to a temp file
  in `factory` and returns `MediaIo.ofFile(file)`; `InputStreamMediaIoTest : MediaIoContract`
  returns `MediaIo.ofStream { ByteArrayInputStream(expected) }`. One extra test: two readers from
  one `ofFile` factory, read alternately, each sees its own bytes (the positional-read point).
- Real-media proof, `kiteplayer-mobile/src/jvmTest/.../DesktopPlaybackTest.kt`: a second test
  method identical to the first but opening `MediaItem.from(MediaIo.ofFile(file), file.name)`.
  Add `api(project(":kiteplayer-io"))` to `kiteplayer-mobile`'s `commonMain` dependencies.
- `./gradlew checkPublicationReadiness` must pass. `./gradlew updateKotlinAbi` for the new module.
- Commit: `io: a module for the doors, and the JVM file and stream ones`.

### Task 5: the posix door, and the Apple URL door (Tier 2)

**Files:** create `kiteplayer-io/src/posixMain/kotlin/io/github/yuroyami/kiteplayer/io/PosixFileMediaIo.kt`,
`kiteplayer-io/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/io/AppleMediaIo.kt`; declare
`posixMain` in the build file as an intermediate set over `appleMain` and `linuxMain`
(`linuxMain` may need declaring too; core's build file shows the pattern for `nativeMain`);
tests in `kiteplayer-io/src/posixTest`.

- Compile first: `./gradlew :kiteplayer-io:compileKotlinMacosArm64 :kiteplayer-io:compileKotlinLinuxX64 :kiteplayer-io:compileKotlinIosArm64`.
  Fix `.convert()` calls until all three compile. Linux compiles only on this machine; it
  EXECUTES in `./scripts/linux-tests.sh`, add the module there.
- `PosixFileMediaIoTest : MediaIoContract` writes the pattern with `fopen`/`fwrite` to a path under
  `getenv("TMPDIR")` and returns `MediaIo.ofPath(path)`.
- `ofUrl` on Apple: one test that `NSURL.fileURLWithPath(tempPath)` opens, reads and closes;
  scope handling cannot be exercised without a picker, say so in the test name.
- `./scripts/linux-tests.sh` green. `macosArm64Test` green. Commit:
  `io: posix and NSURL doors, per session and positional`.

### Task 6: the Android doors (Tier 2, device half rides DEVICE-DAY)

**Files:** create `kiteplayer-io/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/io/AndroidMediaIo.kt`;
tests in `kiteplayer-io/src/androidHostTest` for what the host can reach.

- The host `android.jar` is stubs (`GOTCHAS.md` section 7), so `ContentResolver`, `Uri` and
  `AssetManager` cannot be exercised here. What CAN: `PfdChannelMediaIo` over
  `ParcelFileDescriptor.open(File, MODE_READ_ONLY)` is also stubbed. So the host test covers
  `FileChannelMediaIo` with a `start`/`length` window (the asset case) in `jvmAndAndroidMain`, via
  `androidHostTest`: write pattern with 1,000 junk bytes before and after, open the window, run
  the contract. That proves the window arithmetic; the descriptor plumbing is device evidence.
- Add to `MASTER_PLAN.md` DEVICE-DAY, Android section, step 10b: "Pick a file with the system
  picker, play it through `MediaIo.ofUri`; then play an asset through `MediaIo.ofAsset`. PASS: both
  play and seek." Add the same two calls to the Android sample behind a menu entry so the step
  has something to tap (`kiteplayer-sample-android/.../SampleController.kt`, beside the existing
  `player.open(MediaItem(uri = materialiseClip().absolutePath))` at line 72).
- Reduce `MASTER_PLAN.md` section 7.1's positional-read bullet to its device half.
- Commit: `io: Android content and asset doors, the descriptor is ours`.

### Task 7: `DemuxPolicy`, and the builder (Tier 1)

**Files:** create `kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/DemuxPolicy.kt`
and `MediaItemBuilder.kt` (section 4.8.1 and 4.8.2); modify `MediaItem.kt` (the `demux` field);
tests `kiteplayer-core/src/commonTest/kotlin/io/github/yuroyami/kiteplayer/MediaItemBuilderTest.kt`.

- Red first, three tests: an empty block equals `MediaItem(uri)` (data class equality); the
  example block in 4.8.2 produces a `MediaItem` whose every field matches the hand-built one;
  `ProbeDepth.Custom(0, 1.seconds)` and `DemuxPolicy(skipInitialBytes = -1)` throw
  `IllegalArgumentException`. Run `./gradlew :kiteplayer-core:jvmTest --tests '*MediaItemBuilderTest*'`,
  watch the first two fail to compile and the third fail to throw.
- Write the types and the builder. Green. One native compile
  (`./gradlew :kiteplayer-core:compileKotlinMacosArm64`), because a `data object` in a sealed
  interface and a backtick test name are the two things `jvmTest` cannot vouch for.
- `./gradlew updateKotlinAbi`, then `checkKotlinAbi`.
- Commit: `core: typed container-open knobs, and a builder for the item`.

### Task 8: the resolver sees the item (Tier 1, plus the network module's own suite)

**Files:** modify `kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt`
(`MediaIoResolver`), `kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:1833`,
`kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/KtorMediaIo.kt`
(`KtorMediaIoResolver.resolve`); tests in `kiteplayer-network/src/jvmTest`.

- Red first, in `kiteplayer-network`'s jvmTest, following the `embeddedServer(CIO)` pattern its
  DASH tests already use: a server that records `call.request.headers` and serves 1,000 bytes;
  `KtorMediaIoResolver(headers = mapOf("User-Agent" to "kite", "Authorization" to "resolver"))`
  resolving `MediaItem("http://127.0.0.1:$port/x", headers = mapOf("Authorization" to "Bearer item"))`;
  assert the server saw `User-Agent: kite` AND `Authorization: Bearer item`. This fails to compile
  until the signature changes, then fails on the assertion until the merge is written.
- Change the interface, the engine call, and the resolver. Every `MediaIoResolver` in a test
  (grep `MediaIoResolver {` across `src/*Test`) changes its parameter type.
- Run `./gradlew :kiteplayer-network:jvmTest :kiteplayer-core:jvmTest`, then `updateKotlinAbi`.
- Falsify: drop the item headers from the merge, watch the assertion go red, restore.
- Commit: `core: the resolver receives the item, so per-item headers reach https`.

### Task 9: the backend translates, and collisions refuse (Tier 2, real media)

**Files:** modify `kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteFFmpegMediaBackend.kt`
(the pre-open map function around line 140); create `DemuxPolicyTranslation.kt` beside it for
`toFFmpegOptions()`; modify `MediaItem.kt` KDoc on `headers` and `formatHint`; rewrite
`kiteplayer-ffmpeg/src/commonTest/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PreOpenOptionsTest.kt`.

- Red first, golden: a test table of `DemuxPolicy` values to expected maps, including
  `DemuxPolicy()` to an empty map, `Fast` to the two keys, `Drop + lowLatency` to
  `fflags=+discardcorrupt+nobuffer` and `max_delay=0`. Fails to compile until the function exists.
- Red first, collision: `MediaItem("x", formatHint = "mpegts", openOptions = mapOf("format_whitelist" to "mov"))`
  opened through `KiteFFmpegMediaBackend` throws `PlaybackException` whose `error` is
  `ConfigurationInvalid` and whose message names `format_whitelist`. The existing
  `PreOpenOptionsTest` asserts the opposite; rewrite that case, do not add a second one beside it.
- Real media, `kiteplayer-ffmpeg/src/nativeTest`: `MediaItem("$mediaDir/sync1080p30.mp4", demux = DemuxPolicy(probe = ProbeDepth.Fast))`
  opens and reports both a video and an audio stream. Proves the fast probe is not too fast for the
  house fixture.
- `./gradlew :kiteplayer-ffmpeg:jvmTest :kiteplayer-ffmpeg:macosArm64Test`. ABI dump moves for the
  KDoc-only change? No; only if a signature changed. Check with `checkKotlinAbi` anyway.
- Commit: `ffmpeg: typed knobs translate to options, and a raw key cannot shadow a typed one`.

### Task 10: `DemuxOptions` in KiteFFmpeg (other repository, Tier 2 there)

**Files:** create `../KiteFFmpeg/kiteffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteffmpeg/dsl/DemuxOptions.kt`
(section 4.10); modify `../KiteFFmpeg/kiteffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteffmpeg/MediaSource.kt`
(two companion overloads); extend `dsl/KdGoldensTest.kt`.

- Design commit first, in that repository, per its own rule 13: the type's shape as a commit that
  adds only KDoc-level prose to `PLANNING.md` or the `MASTER_PLAN.md` row here. Then the code.
- Red first: goldens for `DemuxOptions()` (empty), `Live`, `Thorough`, a `Custom` with every field,
  and the collision (`probeSizeBytes = 1, options = mapOf("probesize" to "2")` throws naming
  `probesize`). Mirror how `KdGoldensTest` pins `DecoderOptions`.
- Real media there: `MediaSource.open("$fixture", DemuxOptions.Thorough)` opens and lists streams.
- `./gradlew apiDump -Pkiteffmpeg.hostTargetsOnly=true`, then `apiCheck` with the same flag, both
  on that machine's rules (`GOTCHAS.md` section 4, first bullet).
- Commit there: `dsl: typed demux options, the sibling DecoderOptions never had`.
- Publishing the result is the owner's; nothing in this repository waits for it.

### Task 11: docs, and the consumer bump note (Tier 1)

- README: a short section "Feeding it media" between "Playing a file" and "What you can control":
  the table from section 2 rewritten for consumers, one row per door, the builder example from
  4.8.2, three lines of prose. State the plain-path rule from section 3, point 4.
- `docs/spi-cookbook.md`: one paragraph under "The source" saying a backend that reads bytes gets
  them through `MediaIo`, so it inherits every door for free; and one saying a backend receives
  `MediaItem.demux` typed and owes each field an honour or a typed refusal.
- Delete section 5.5 from `MASTER_PLAN.md` when every checkbox above is gone.
- Commit: `docs: the doors, the knobs, and what a plain path still is`.

---

## 7. Gate per task, spelled out

Tier 1 (every task):

```bash
./gradlew checkKitertCoupling checkKotlinAbi
./gradlew :kiteplayer-core:jvmTest :kiteplayer-subtitles:jvmTest
git ls-files -z | xargs -0 grep -n $'\u2014'     # must print nothing; exit 1 is the pass
```

Tier 2 (Tasks 4, 5, 6), on top of Tier 1:

```bash
./scripts/testmedia.sh                          # fixtures are gitignored
./gradlew jvmTest
./gradlew macosArm64Test
./gradlew :kiteplayer-io:testAndroidHostTest
./scripts/linux-tests.sh
./gradlew checkPublicationReadiness
```

`./gradlew ... | tail` hides the exit code. Pipe to a file and read the file.

---

## 8. Decisions the owner makes before execution

Answer these in the design commit's message or in `MASTER_PLAN.md` section 5.5. An executor does
not guess them.

- **A. `openOptions` policy.** Recommended: refusal list of two keys plus `@KitePlayerLowLevelApi`
  (section 4.2). Alternative: a full allowlist, which breaks Synkplay's `fd` use and turns the
  escape hatch into a form.
- **B. New module `kiteplayer-io` vs doors inside `kiteplayer-ffmpeg`.** Recommended: new module
  (section 4.5). Alternative costs nothing today and costs a move the day a second backend exists.
- **C. kotlinx-io.** Recommended: no. A `RawSource` is forward-only, which `ofStream` already
  covers on JVM, and it adds a dependency to a module whose point is having none. Revisit when a
  consumer asks.
- **D. `MediaItem.io` type change.** It is a source-compatible ABI change on a 0.0.x line. Recommended:
  yes, now, before the first Central publish makes the lambda type permanent for anyone.
- **E. When a raw key and a typed field set the same FFmpeg option.** Today the raw key wins,
  silently, and the KDoc on `headers` and `formatHint` says so. KiteFFmpeg's encoder specs refuse
  the same collision, naming both sides. Recommended: refuse, at open, as
  `PlaybackError.ConfigurationInvalid` naming the key and the field. One rule for the whole pipe,
  and a typo can no longer quietly override a typed choice. `PreOpenOptionsTest.kt` in
  `kiteplayer-ffmpeg` pins the old behaviour and moves with the decision.

---

## 9. Self-check for the executor, before calling any task done

- Did the test go red at the line you predicted, before the code existed?
- Did you break the fix and watch it go red again?
- Did `updateKotlinAbi` run in the same commit as the public change?
- Did `MASTER_PLAN.md` lose or shrink a row in the same commit?
- Is there an em dash anywhere? A register code in a shipped file?
- Did you read every changed file once more, top to bottom?
