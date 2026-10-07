# Module contract

This page defines the public dependency and activation contract: which module a consumer depends
on, what each entry point brings in, and how the network transport and the libass typesetter are
found.

## Entry points

| Module | Contract |
|---|---|
| `kiteplayer` | Default playback assembly: core, FFmpeg, output, native view bindings and HTTP/HTTPS transport. No Compose dependency. |
| `kiteplayer-compose` | Complete playback, `KitePlayerVideo` with both Compose video paths and the runtime path switcher, and `rememberKitePlayer`. One dependency for a Compose app, including apps also using XML. |
| `kiteplayer-compose-ui` | `KitePlayerVideo`, which accepts an existing player. Does not depend on the playback assembly or automatically add networking. It depends on the two path modules with `implementation`, so their composables are not on a consumer's compile classpath; an app that calls `KitePlayerSurface` or `KiteVideo` directly depends on that module itself. |
| `kiteplayer-core` | Engine and service contracts. Does not depend on FFmpeg, Ktor or Compose. |

The individual backend, network, view and Compose renderer modules remain available for custom
assemblies. Public signatures that expose core types keep `api(core)`; hiding that edge would
hide types while retaining the runtime dependency. Gradle resolves one core dependency.

Native view renderer bindings belong below both playback construction and Compose interop.
They may use FFmpeg frames, but backend-neutral view and output contracts must remain neutral.
The Compose pixel renderer still uses its current FFmpeg conversion adapters. Removing that
coupling is outside this change.

Keep supported target variants consistent along dependency edges. Existing unavailable targets
must continue to answer unavailable honestly; a resolving metadata variant is not playback proof.
Mobile artifacts are selected for a mobile application by Gradle, not bundled with desktop natives.

On macOS, `kiteplayer` depends on `kiteplayer-view` with `api`. The macOS target of
`kiteplayer-view` holds only `KitePlayerPictureInPicture`, the class that puts a player in the
system's small floating window. iOS uses the same class. macOS has no view bindings module, because
the application owns the window: `AppKitWindow` in `kiteplayer-output`, built with
`AppKitSurface.SampleBuffer`, hosts the layer that the class needs.

On the web, `kiteplayer` also depends on `kiteplayer-view` with `api`. The web part of
`kiteplayer-view` is `KitePlayerPictureInPicture`, which puts the page's player canvas in a small
browser window. The class uses the Document Picture-in-Picture window where the browser has one.
Otherwise it uses the picture in picture of a video element.

On the desktop JVM, `kiteplayer` reaches `kiteplayer-view` through `kiteplayer-view-bindings`.
There, `KitePlayerPictureInPicture` opens an always-on-top window for a `KitePlayerAwtView`.
Compose Desktop has no hook for it, because `KitePlayerSurface` does not expose the view that it
hosts. A Compose app that draws video with `KiteVideo`, from `kiteplayer-compose-video` which it
adds itself, can open a second `Window(alwaysOnTop = true, undecorated = true)` that draws the same
`KiteVideoState`.

## Apple offscreen frame ownership

Planned API contract for #476. The implementation and ABI update follow in a separate change.

`MetalPictureReader`, in `kiteplayer-output`, will implement `AutoCloseable`. Its public
constructor and `readRgba(frame, picture, toneMapped)` signature stay available. One worker
thread constructs, uses and closes a reader; the reader does not support concurrent calls.

The caller owns the reader and calls `close()` when finished. Close waits for submitted GPU work
and its completion cleanup before releasing the CoreVideo texture cache and cached frame
storage. A second close does nothing. `readRgba` after close throws `IllegalStateException`
before allocating or submitting more work. Native resources require explicit close.

The reader borrows the frame and picture for `readRgba`; it does not close either input. They must
remain valid until the call returns. The returned RGBA byte array belongs to the caller and
remains valid after the reader closes.

Each Compose video renderer will own its frame converter. On iOS, that converter creates its
Metal reader lazily on its worker when a hardware frame needs it. Separate renderers own separate
readers. Renderer close stops accepting frames, waits for an in-flight conversion to finish, then
closes the converter on that same worker before releasing the worker dispatcher. Closing a
renderer before its first conversion still completes its converter's lifecycle, and closing one
renderer does not retire another renderer's conversion state.

## Automatic network transport

A consumer should gain HTTP/HTTPS transport by adding the network module, including when it
constructs a player directly through core. Standard playback umbrellas include that module.
Resolution order is the item's own byte source, an explicitly configured resolver, then an
installed automatic provider if automatic selection is enabled, then the backend's URI handling.
An explicit resolver returning no reader is an intentional choice, not permission to replace it.
Expose a way to disable automatic selection, and preserve per-item HTTP headers.

Discovery is platform-specific and registers lightweight providers. HTTP clients are created
only when network media is opened. Automatically created resources must have a close owner;
caller-supplied resources remain caller-owned. Do not silently share mutable player state through
an extension registry. Competing providers must have deterministic selection.

JVM and Android use service metadata; Native and web use target initialisation/registration.
The Android network artifact supplies the normal `android.permission.INTERNET` permission through
manifest merging, so consumers do not need a separate permission declaration.
Kotlin's eager initialisation is experimental/deprecated, so dependency-presence activation must
be verified against this pinned toolchain in optimised consumers before being claimed. A consumer
proof references only core APIs: touching a network symbol would hide a missing registration root.

The concrete core additions are `NetworkConfig.autoResolve` (default true), a default
`MediaIoResolver.resolve(uri, headers)` overload preserving existing resolver implementations,
and `MediaIoResolverProvider` / `MediaIoProviders.register` in the low-level provider surface.
Providers have stable identifiers; selection is sorted and registration is thread-safe.
The built-in automatic HTTP provider is stateless and gives each reader ownership of its own
lazy-created client, so reader close also releases the client.

## Subtitles and compatibility

Default playback continues to include the Kotlin subtitle parsers through the FFmpeg backend,
with cue timing in core and text rasterisation in output. `kiteplayer-libass` joins the default
assembly the way the network module does: `kiteplayer` depends on it, so
`kiteplayer-compose` inherit it, and `kiteplayer-compose-ui` does not. Discovery mirrors the
transport providers exactly: `SubtitleTypesetterProvider` through service metadata on the JVM and
Android, eager registration on native, and `SubtitleTypesetters.register` for anyone else. The
engine routes the primary ASS or SSA track to the installed typesetter, on the raster lane, at
video frame cadence; `SubtitleConfig.typesetting` turns that off, and a provider that cannot start
warns once and leaves the Kotlin tier in charge. On the web the engine is a separate wasm module
the page hosts, delivered as the `web` zip on the wasmJs publication and loaded on first use; the
js variant resolves and installs nothing.

The existing broad `kiteplayer-compose` coordinate becomes the recommended complete Compose
entry point. A `kiteplayer-compose-ui` consumer that also needs default construction switches to
`kiteplayer-compose`, or adds `kiteplayer` explicitly. The deprecated `kiteplayer-phone` module is
no longer built; its published versions stay on Maven Central.

## Verification

A change to these modules verifies that each documented dependency resolves by itself where
promised, that required modules appear in published metadata, that presentation does not pull in
playback construction or networking, and that network discovery survives release optimisation. It
checks a normal HTTP and HTTPS open, seeks, headers, explicit overrides, disabled discovery,
missing providers and cleanup on success and on failure.

## In-memory media input

`MediaIo.ofBytes(bytes)` returns a `MediaIoFactory` in `kiteplayer-core`. Each open owns
an independent cursor and close state over the original array. No copy is made; the caller
must keep the bytes unchanged while a reader is open. The reader is seekable, reports its
size, accepts positions from zero through size, and rejects reads and seeks after close.
Invalid destination slices and seek positions throw `IllegalArgumentException` without
moving the cursor. Empty reads return zero, including at EOF; other EOF reads return -1.

`MediaItem.from(io, label)` constructs an item using that factory, with the label as its
URI for probing and display. Both functions are companion extensions in the core package,
so future platform input factories can use the same entry point without platform dependencies
in core. Existing constructors and custom reader factories remain source compatible.

## Platform input doors

`kiteplayer-io` holds the doors that need a platform type. On the JVM and Android these are
`MediaIo.ofFile`, `MediaIo.ofPath`, `MediaIo.ofChannel` and `MediaIo.ofStream`, in the package
`io.github.yuroyami.kiteplayer.io`. Each returns a `MediaIoFactory`, so the engine and the
backends never see a door. The module depends on `kiteplayer-core` only and declares the same
targets as `kiteplayer-subtitles`.

`kiteplayer` depends on it with `api`, so `kiteplayer-compose` gets the doors too, and
`kiteplayer-compose-ui` does not. A plain local path still goes to FFmpeg by name and needs no
door. The one exception is an item marked as still being written (`MediaItem.growth`): the module
registers a provider that serves local files, which automatic resolution never asks, and the engine
asks it for that item alone, because FFmpeg's own reader ends a growing file at the open's size. Each file door opens its own channel per open and reads it by position. `ofChannel` reads
a channel the caller owns and never closes it or moves its position.

On Apple and Linux, `MediaIo.ofPath(String)` opens the file for each open and reads it with
`pread`. On Apple, `MediaIo.ofUrl(NSURL)` does the same for a file URL. It also starts the URL's
security scope when it opens and stops it when it closes, so a file from the document picker needs
no cleanup from the caller. A door that cannot open its file throws `MediaIoException`, which names
the file. Android native and Windows have no path door: `pread` takes a 32-bit offset on the two
32-bit Android native targets, and Windows has no `pread`.

On Android, `MediaIo.ofUri(ContentResolver, Uri)` plays what a content provider serves, such as a
file from the system picker, and `MediaIo.ofAsset(AssetManager, String)` plays a file from the
app's `assets` directory. Each open gets a new descriptor from the provider or the asset manager
and reads it by position, so a reopen never moves a descriptor that the caller holds. An asset is
a window inside the app package, and the door reads only that window. A provider that answers
with a pipe plays forward only. An asset must be stored uncompressed; the Android build already
stores common media extensions that way.

## Display wake entry-point compatibility

Display wake is enabled by default. Its opt-out must preserve the original public entry points
as delegating overloads, so existing callers keep their constructor and startup signatures:

- `WebCanvasVideoRenderer(canvas, painter)` and `WebCanvasVideoRendererFactory(canvas, painter)`
  in `kiteplayer-output`, including a trailing `WebFramePainter` lambda.
- `AppKitVideoRenderer(window, convert, toneMapped = { false })` in `kiteplayer-output`, including
  a trailing tone-map lambda and the existing default callback.
- `WebCanvasRendererFactory(canvas)` in `kiteplayer`.
- `KitePlayerWorker.start(canvas, workerUrl = "./kiteplayer-web-worker.mjs", codecUrl = "./kite.mjs",
  libassUrl = "./kiteass.mjs")` in `kiteplayer`, including the original parameter defaults.

Each delegates with `keepDisplayAwake = true`. The newer forms that explicitly accept the wake
option remain available with their existing parameters and defaults; callers can still pass false.
Compatibility verification checks the restored exact symbols in compiler-generated ABI output and
compiles old and new source call forms. This is not a claim that every combination of published
Kotlin compiler, library and already-linked application versions is binary compatible.


## Native Apple media sessions

The `kiteplayer` Apple API declares `KitePlayerMediaSession(player, skipInterval = 15.seconds)`
and `KitePlayer.attachMediaSession(background = ContinueAudio, interruptions = InterruptionPolicy(),
pictureInPicture = null, skipInterval = 15.seconds)` in the shared Apple source set. The iOS actual
keeps the existing `setArtwork(UIImage?)` member and constructor signature. The macOS actual adds
`setArtwork(NSImage?)`. Both expose `platformToken: Any?` as null, `isAvailable: Boolean` as true,
and idempotent `close()`. Construction, artwork updates and close belong to the main thread.
An invalid nonpositive skip interval is refused before registering a system handler.

One process has one Now Playing owner. A new successfully registered session takes ownership,
retires the previous owner's command targets and policy subscriptions, and leaves the previous
player's transport alone. Late callbacks and subsequent closes from a retired session cannot
control or clear the new owner's card. A setup failure removes every handler acquired so far.
The session returned by `attachMediaSession` closes with its player. Artwork supplied by the
application wins over the item's embedded artwork; null restores the embedded picture.

The shared controller uses MediaPlayer for metadata, position, buttons and command forwarding.
The macOS adapter also writes `MPNowPlayingInfoCenter.playbackState`: Playing, Paused and Stopped
map directly; Opening and Buffering map to Unknown with a zero playback rate, not to a fabricated
OS interruption or continuing playback. The iOS audio-session and application-background adapters
remain iOS-only. A macOS application losing focus can still have a visible window, so the mobile
background argument has no effect there; picture in picture retains its own lifetime. The desktop
JVM stub is unchanged. Sharing the existing view-module edge at `appleMain` makes the established
picture-in-picture parameter available to both native Apple callers without a new external library.

A noisy route is observed by the audio sink that actually owns the output binding. A default-bound
sink follows that output; a pinned sink ignores unrelated default changes and retains its existing
unavailable-device failure if its device disappears. A headphone-to-speaker transition pauses when
`InterruptionPolicy.pauseWhenBecomingNoisy` is enabled. This includes a selected data-source change
within the same built-in output ID. An unclassified Bluetooth or Bluetooth LE route becoming proven
built-in speakers also pauses conservatively; an explicit speaker terminal overrides that fallback.
Transport type alone does not label a device as headphones. Initial inspection, speaker-to-speaker
changes, unknown non-Bluetooth endpoints and reconnection cause no transport action.

The general output SPI gains `AudioSinkEvent.BecameNoisy(atNanos: Long)`. Its timestamp is captured
when the route notice is received, in the owning output clock's domain, before any owner-queue
refresh or asynchronous delivery. It describes the observed notice, not the unknowable exact physical
unplug time. The latest pending occurrence is retained independently of lossy diagnostic notices,
including across the engine's first subscription. Several undelivered noisy notices may coalesce to
the newest because one pause suffices. No real-time render callback performs this work.

The core gains `PlayerEvent.AudioOutputBecameNoisy(transportMark: Long)`. It forwards only a current
session's current audio sink occurrence newer than the last explicit transport request. A tied
monotonic tick favors the explicit request. The existing transport counter keeps its exact
increment-before-validation behavior, including rejected requests. A session collects
`losslessEvents` before attachment returns and compares the event's mark again when applying policy
on the main lane. Thus an occurrence retained before a later play cannot be dated as a fresh
interruption after that play, and a later request also supersedes an already-forwarded occurrence.
Reconnection does not resume sound. Other backends may implement the typed SPI without Apple types.

Both event subtypes are public sealed-hierarchy additions: callers with exhaustive `when` expressions
must account for the new case when rebuilding. Existing event constructors and the existing iOS
session signatures remain. The web worker encodes the transport mark as an exact decimal long and
round-trips the new event; it does not invent a web noisy-route implementation. Compiler-generated
ABI output is regenerated for affected published modules in the implementation commit. Native
source/API checks, fake-HAL route tests, media-key behavior and physical headphone trials are
separate evidence; none stands in for another.
