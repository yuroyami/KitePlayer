# Module contract for 0.0.23

Agreed for issue #123. This document defines the public dependency and activation contract before
its implementation. The 0.0.23 work prepares commits and artifacts for verification only. It does
not publish to Central or create a release or tag. The libass integration landed after this
document was agreed and is described under Subtitles below.

## Entry points

| Module | Contract |
|---|---|
| `kiteplayer` | Default playback assembly: core, FFmpeg, output, native view bindings and HTTP/HTTPS transport. No Compose dependency. |
| `kiteplayer-compose` | Complete playback plus both Compose video paths and the runtime path switcher. One dependency for a Compose app, including apps also using XML. |
| `kiteplayer-compose-ui` | Both Compose presentation paths accepting an existing player. Does not depend on the playback assembly or automatically add networking. |
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

JVM and Android use service metadata; Native and web use target initialization/registration.
The Android network artifact supplies the normal `android.permission.INTERNET` permission through
manifest merging, so consumers do not need a separate permission declaration.
Kotlin's eager initialization is experimental/deprecated, so dependency-presence activation must
be verified against this pinned toolchain in optimized consumers before being claimed. A consumer
proof references only core APIs: touching a network symbol would hide a missing registration root.

The concrete core additions are `NetworkConfig.autoResolve` (default true), a default
`MediaIoResolver.resolve(uri, headers)` overload preserving existing resolver implementations,
and `MediaIoResolverProvider` / `MediaIoProviders.register` in the low-level provider surface.
Providers have stable identifiers; selection is sorted and registration is thread-safe.
The built-in automatic HTTP provider is stateless and gives each reader ownership of its own
lazy-created client, so reader close also releases the client.

## Subtitles and compatibility

Default playback continues to include the Kotlin subtitle parsers through the FFmpeg backend,
with cue timing in core and text rasterization in output. `kiteplayer-libass` joins the default
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
`kiteplayer-compose`, or adds `kiteplayer` explicitly. The mobile and phone compatibility entry
points continue to resolve without duplicating declarations from the moved assembly.

## Verification and release notes

Verify that each documented dependency resolves by itself where promised, required modules appear
in published metadata, presentation does not pull in playback construction/networking, and
network discovery survives release optimization. Check normal HTTP/HTTPS open, seeks, headers,
explicit overrides, disabled discovery, missing providers and cleanup on success/failure.

The 0.0.23 notes include the URL redaction, sleep timer and subtitle parser fixes committed after
0.0.22, plus these packaging and transport changes. Installation examples present alternatives
rather than a list of dependencies that appears to require all of them.

## In-memory media input, 0.0.24

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
