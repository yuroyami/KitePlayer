# Module contract for 0.0.23

Agreed for issue #123. This document defines the public dependency and activation contract before
its implementation. The 0.0.23 work prepares commits and artifacts for verification only. It does
not publish to Central, create a release or tag, or implement libass integration.

## Entry points

| Module | Contract |
|---|---|
| `kiteplayer` | Default playback assembly: core, FFmpeg, output, native view bindings and HTTP/HTTPS transport. No Compose dependency. |
| `kiteplayer-mobile` | Compatibility and convenience entry point using the same assembly. No second player implementation. |
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
with cue timing in core and text rasterization in output. Full ASS/libass is separate future work.
Its existing standalone renderer is not advertised as an integrated playback capability.

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
