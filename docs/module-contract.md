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

## Apple presentation color

Planned behavior contract for #489. The current implementation does not yet satisfy these color
and drawable-format guarantees; production and platform qualification follow separately.

Apple SDR presentation preserves the source's encoding and uses the system's matching video
profile, including the native ITU-R 709 interpretation for ordinary HD. This is the chosen native
Apple/QuickTime appearance. Software sample buffers carry their source color metadata; shared
hardware buffers retain their existing metadata. Explicit metadata wins over fallbacks. Generic
BT.601 uses SMPTE170M for 480 and 486-line NTSC and EBU for 576-line PAL.

Changing an SDR drawable from eight-bit storage to linear half-float storage must preserve that
presentation intent. SDR adjustments and source-law linear filtering precede conversion through
the chosen native profile to extended-linear BT.709. Native profile transfer curves and white
adaptation must not be replaced by the source inverse OETF merely because the drawable is float.
Tone-mapped HDR uses the shader's actual BT.709/gamma2.2 encoding; extended HDR remains linear BT.709.
Subtitle source pixels are sRGB and are converted for their actual presentation target.

A bounded GPU lookup may approximate the native SDR profile conversion. The proposed 65-cubed
float table costs about 4.2 MiB per profile, with at most three owned or in-flight tables per
composer. A fourth profile waits for the oldest table's last GPU read before reusing that texture.
Creation uses two fixed-size float arrays, about 8.4 MiB combined, plus temporary Core Graphics image and
conversion storage. No frame undergoes CPU color conversion. Tests must report measured error
against direct native color matching, including nonneutral shadows/highlights, between-grid
samples, different white points, negative wide-gamut components and effects that reach encoding
bounds. These limits are implementation tradeoffs, not a claim of exact color equality or a
replacement for the issue's actual P3 display and picture-in-picture checks.

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

## Android media requests and playback resumption

Planned API contract for #431. The implementation and ABI update follow in separate changes. All of
it is Android-only, in `kiteplayer`'s `session` package, and with none of it set nothing changes.

**Requests for specific media.** `KitePlayerMediaSession.setMediaRequestHandler(kinds:
Set<MediaRequestKind>, handler: ((MediaRequest) -> Unit)?)` takes the requests the application
answers. `MediaRequestKind` is `Search`, `MediaId`, `Address` and `Prepare`. The session offers the
system exactly the matching actions: play and prepare from a search for `Search`, from an id for
`MediaId`, from an address for `Address`, and plain prepare for `Prepare`. A null handler or an
empty set offers none, as today. A later call replaces the earlier one.

`MediaRequest` is a sealed class whose cases have internal constructors: `Search(query)`,
`MediaId(mediaId)`, `Address(uri)` and `Prepare`. Each carries `play: Boolean`, false for a prepare,
and `extras: Bundle`, empty when the system sent none. A search the system sends without words, for
"play something", arrives as an empty query. The handler runs on the main thread and decides what to
open; the session opens and plays nothing by itself, because only the application knows its
catalogue. A request whose kind is not offered, which a stale controller can still send, is ignored.

**Media keys first.** `setMediaButtonHandler(handler: ((KeyEvent) -> Boolean)?)` sees each key event
of a media button intent on the main thread before the session's own rule from #437. True means the
application handled it and the session does nothing more; false, or no handler, leaves today's
behaviour, including play-pause acting at once and the headset's double press.

**The resume card.** Android 11 and later show a media resume card after a reboot or after the
application closed, for an application with an exported browser service that answers the recent
root. `KitePlayerResumptionService` is an abstract `MediaBrowserService` the application subclasses
and declares, exported, with the `android.media.browse.MediaBrowserService` intent filter.
`KitePlayerMediaService` stays unexported.

- `KitePlayerResumption.save(context, memento, title, subtitle = null)` keeps the state to resume
  in the application's private preferences, as `PlayerMemento.asProperties()` text, and `clear`
  removes it. The application decides when to save, for example at a pause and when the player
  closes.
- The service answers a root only for the recent hint, only when something is saved, and only to a
  caller the media session manager trusts for media control, which is the system interface and
  holders of the media control permission. Any other caller gets no root, so an exported service
  hands nothing to an arbitrary application. Its one child is the saved item, playable, with the
  saved title and subtitle.
- The service owns a session of its own, whose token it publishes. A play on it, which is what
  pressing the card sends, calls the abstract `onResume(memento: PlayerMemento)` on the main thread.
  The application builds its player there, restores the memento, attaches its own session and
  plays; the service then releases its own session, so the application's session is the only one.
  A memento that cannot be read clears the saved state and offers no root.

Tests: host tests of the offered actions for each kind set, of the request each callback turns into,
of a media button handler that consumes a key and one that declines, and of the root answer for the
recent hint, no hint, nothing saved and an untrusted caller. The device check, owed to the ASUS, is
the resume card appearing after the application is closed and playing the saved item at its saved
position.

## Default player controls

Planned API contract for #469. The implementation, the generated ABI and the size baseline follow
in separate changes. Everything lives in `kiteplayer-compose-ui`, in the
`io.github.yuroyami.kiteplayer.compose` package, beside `KitePlayerVideo`. The module gains
`implementation(compose.foundation)`, which an application already receives through
`kiteplayer-compose-video`, and no Material artifact: the owner decided on 2026-10-07 against
both a Material dependency and a new module.

**State holders.** Each is created with a `remember...State(player)` composable, reads the
player's `state` and `progress`, and calls the player's own commands. An application that wants
its own look builds it from these; the default controls use nothing else.

- `TransportState`: `showsPlay`, `canGoPrevious`, `canGoNext`, `togglePlay()`, `previous()` and
  `next()`. `showsPlay` is true unless the player was asked to play, so a buffering player shows
  pause, the answer to the listener's own request.
- `SeekBarState`: `position`, `duration`, `seekable`, `fraction` and `buffered`, the buffered
  ranges as fractions of the duration. When `Progress.bufferedRanges` is empty, which it is for
  HLS and with the byte cache off, one range runs from the position to `Progress.bufferedAhead`.
  `startScrub(fraction)`, `scrubTo(fraction)`, `endScrub()` and `cancelScrub()` move a scrub
  target without moving playback; each scrub step calls `requestSeek` with `KeyframeThenRefine`,
  and the end asks the last target once more. `stepBy(delta)` jumps from the position.
  `preview` is `KitePlayer.thumbnailAt` for the scrub target, or null.
- `TrackMenuState`: a list of `TrackMenuOption`s, each with a `label` and `selected`, and
  `select(option)`. Made by `rememberTrackMenuState(player, kind)` for audio or subtitles, where
  subtitles have an off option first; by `rememberQualityMenuState(player)`, with an automatic
  option first and the variants after it; and by `rememberSpeedMenuState(player, speeds)`. Every
  track is listed with its title and language, never cycled one per press.
- `VolumeState`: `level` in 0..1, `muted`, `setLevel(level)` and `toggleMute()`. The level
  follows hearing through a cube, as mpv's volume does: the amplitude is the level cubed, so half
  the slider is an eighth of the amplitude, about 18 dB down. A volume above 1 shows as a full
  slider and is left alone until the slider moves.
- `ControlsVisibility`: `visible`, `show()`, `hide()` and `toggle()`, from
  `rememberControlsVisibility(player, timeout = 3.seconds)`. The controls hide after the timeout
  only while the player plays, and any interaction, a key press or a scrub starts the timeout
  again. A tap on the picture toggles them, and a tap and the timeout both hide them the same way.

**The default controls.** `KitePlayerControls(player, modifier, visibility, style, labels,
onFullScreen, onPictureInPicture)` draws play and pause, previous and next for a queue, the seek
bar with its buffered ranges and the times, the volume, menus for audio, subtitles, quality and
speed, and full screen and picture-in-picture buttons when their callbacks are given. It is made
only from the state holders above, from Compose Foundation and `compose.ui`.

- Look: `KitePlayerControlsStyle`, with colours and sizes and a default, in place of a theme.
- Words: `KitePlayerControlsLabels`, with English defaults. Every string a listener or a screen
  reader meets comes from it, the time and track names included, so an application translates it
  whole.
- Icons are our own vectors. Play, previous, next and the seek bar are never mirrored: the whole
  transport row and the seek bar lay out left to right in a right-to-left layout too, as Media3
  learned in androidx/media issue 227. Text still follows the layout direction.
- Every control is focusable and reached by a D-pad and by the keyboard. Space and the media
  play-pause key toggle playback, and the arrow keys step the focused seek bar by 10 seconds.
  Focus draws a ring in the style's focus colour.
- Every control has a role and a label. The seek bar and the volume, which Compose has no slider
  role for, carry `progressBarRangeInfo` and a `setProgress` action, as the visualiser's panel
  does (#326), and state the position and duration in words.
- While the controls show, subtitles move up through `KitePlayer.setSubtitlePosition`, and settle
  back when they hide, unless the application changed the position itself in between. It is the
  same rule the session guards use for a pause they did not make.
- On a touch screen, a horizontal drag over the picture scrubs, showing the target time, and
  seeks where the finger lifts.

**The option on `KitePlayerVideo`.** A new overload takes a required
`controls: @Composable BoxScope.() -> Unit` after the existing parameters and draws it over the
video, for example `controls = { KitePlayerControls(player) }`. The existing signature stays as it
is, so no existing screen or compiled caller changes. On the desktop, a native view takes every
click, so controls drawn over `KiteRenderPath.NativeView` receive none; such an application asks
for `KiteRenderPath.ComposeCanvas` or places the controls beside the video, as the class already
documents.

Out of scope here: controls for the native views, which can follow once these settle, and a
volume above 100 percent in the default controls.

Tests: the state holders against a scripted player, including the scrub calls and their seek mode,
the buffered fractions, the volume curve's ends and middle, and the visibility timeout. Compose UI
tests on the JVM: every control's role and label, `setProgress` moving the player, Tab and the
arrow keys reaching every control, a tap and the timeout hiding the controls alike, the
unmirrored transport row in a right-to-left layout, and `KitePlayerVideo` without controls drawing
exactly what it drew before.
