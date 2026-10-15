# 360 degree video

This document is the design for issue #460, and the player now does what it says. It states the
public API, what the player does with a 360 degree video, how the renderers draw one, and which of
them do. Where building it changed a detail, the detail here is the one that was built.

## What a 360 degree file says

A 360 degree camera stores the whole sphere around it in each picture, and the file says how the
picture covers the sphere: its projection. An MP4 or MOV file says it in Google's spherical video
boxes or Apple's video extension box, and a Matroska file in its track's `Projection` element.

- The common projection is **equirectangular**: longitude runs across the width, from 180 degrees
  to the left of the front at the left edge to 180 degrees to the right at the right edge, and
  latitude runs down the height, from straight up at the top to straight down at the bottom. The
  centre of the picture is straight ahead.
- A file can also state a **pose**: a yaw, a pitch and a roll that turn the sphere, so the front of
  the picture is not where a player should start looking.

A player that knows this draws only the part of the sphere the viewer looks at, and lets them turn
the view by dragging or by moving the phone. Drawn flat, the whole picture looks like a stretched
panorama, which is still what a renderer that cannot draw a view shows.

## What KiteFFmpeg gives

KiteFFmpeg 0.5.1, which this repository pins, reports each video stream's mapping as `spherical`, a
`SphericalMapping` (KiteFFmpeg #139): the projection, with what that projection carries, and the
pose in degrees. Its projections are equirectangular, a tile of an equirectangular picture with its
four bounds, a cube map with its padding, half equirectangular, rectilinear, fisheye and Apple's
parametric immersive. It also reports a stream's stereo layout as `stereo3d`, which the player does
not use.

FFmpeg reads the mapping from MP4, MOV and Matroska and applies none of it, so the player needed no
FFmpeg change. It carries the mapping to the item's tracks and to the renderer, keeps the view's
state, and draws the view.

## Public API

```kotlin
/** How a 360 degree video's pictures cover the sphere around the viewer, as its file states it (#460). */
public data class VideoProjection(
    /** The shape the picture takes on the sphere. */
    val kind: ProjectionKind,
    /**
     * The pose the file states, in degrees, which turns the sphere so the view starts where the
     * file asks. A positive yaw puts the picture's front to the right of where the view starts,
     * a positive pitch above it, and a positive roll tilts it clockwise.
     */
    val yaw: Double = 0.0,
    val pitch: Double = 0.0,
    val roll: Double = 0.0,
)

public sealed interface ProjectionKind {
    /** The whole sphere, longitude across the width and latitude down the height. */
    public data object Equirectangular : ProjectionKind

    /**
     * A part of an equirectangular picture. Each bound is the share of the whole picture beyond
     * this part's edge on that side, from 0 to 1.
     */
    public data class EquirectangularTile(val left: Double, val top: Double, val right: Double, val bottom: Double) : ProjectionKind

    /** Six cube faces in one picture, with [padding] pixels at each face's edges. Drawn flat for now. */
    public data class Cubemap(val padding: Int) : ProjectionKind

    /** A projection the player names but does not draw: half equirectangular, fisheye or Apple's parametric immersive. Drawn flat. */
    public data class Other(val name: String) : ProjectionKind
}

public data class TrackInfo(
    // ...
    /** How the track's pictures cover the sphere, for a 360 degree video, or null for a flat one (#460). */
    val projection: VideoProjection? = null,
)

/** Where the viewer looks into a 360 degree video, and how wide the view is (#460). */
public data class SphericalView(
    /** Degrees to the right of where the file starts the view. Any value: a whole turn is 360. */
    val yaw: Float = 0f,
    /** Degrees up, from -90 (straight down) to 90 (straight up). */
    val pitch: Float = 0f,
    /** Degrees the view is turned clockwise, as a phone held at an angle turns it. */
    val roll: Float = 0f,
    /** The angle the view spans across its shorter side, from 30 to 120 degrees. */
    val fieldOfView: Float = 80f,
    /** False draws the whole picture flat, as for a video that is not 360. */
    val projected: Boolean = true,
)

public class KitePlayer {
    /**
     * Turns the view into a 360 degree video, or switches it to the flat picture, for example from
     * a drag gesture or the device's rotation sensor. It applies to every item from now on and
     * changes nothing for a flat video. Published as [PlayerSnapshot.sphericalView]. A paused
     * player shows the new view at once.
     *
     * @throws IllegalArgumentException when a value is not finite, the pitch is outside -90 to 90,
     *         or the field of view is outside 30 to 120.
     */
    public fun setSphericalView(view: SphericalView)
}

public data class PlayerSnapshot(
    // ...
    /** The view into a 360 degree video (#460). See [KitePlayer.setSphericalView]. */
    val sphericalView: SphericalView = SphericalView(),
)

public sealed class PlaybackWarning {
    /**
     * The video that plays is a 360 degree video, and the attached renderer shows its whole
     * picture flat, because it cannot draw a view into a sphere or into this [kind] of projection
     * (#460). [detail] says which. Told once for each item and renderer.
     */
    public data class ProjectionUnavailable(val kind: ProjectionKind, val detail: String) : PlaybackWarning()
}
```

An application reads whether the video that plays is a 360 degree one from
`state.tracks.selectedTrack(TrackKind.Video)?.projection`, and offers its drag and its sensor only
then. The player has no drag of its own, so an application turns the view itself, for example:

```kotlin
var view by remember { mutableStateOf(SphericalView()) }
KitePlayerVideo(
    player,
    Modifier.fillMaxSize().pointerInput(player) {
        // Drag the picture: a tenth of a degree for each pixel the finger moves.
        detectDragGestures { _, drag ->
            view = view.copy(
                yaw = view.yaw - drag.x * 0.1f,
                pitch = (view.pitch + drag.y * 0.1f).coerceIn(-90f, 90f),
            )
            player.setSphericalView(view)
        }
    },
    // The native views draw a 360 degree video flat; see "Which renderers draw the view".
    path = KiteRenderPath.ComposeCanvas,
)
```

The view is kept in the application's own state rather than read back from the snapshot, which is
published after the player has handled the call and so can be a step behind a fast drag.

`PlayerMemento` keeps the view, as it keeps `VideoTransform`, so a restore looks where the viewer
looked. A memento saved before the view existed restores the default view.

Java code calls `getPlayer().setSphericalView(...)`. The call neither suspends nor takes a
`Duration`, so `KitePlayerJava` needs nothing of its own for it.

## What the player does

### Which videos it projects

A video is drawn as a view into the sphere when its track has a projection the renderer draws,
and the view is `projected`. The renderers that draw a view draw `Equirectangular` and
`EquirectangularTile`. Every other video is drawn exactly as before, including a 360 degree video
whose projection the renderer does not draw, which `ProjectionUnavailable` reports.

A rectilinear projection is a flat picture by definition, so the player reports it as no
projection at all.

### The view

The view fills the whole area the renderer draws into: there are no bars, because there is no edge
of the picture to fit. The view looks in the direction the view's yaw, pitch and roll turn it to,
counted from where the file's pose starts it, and spans `fieldOfView` degrees across its shorter
side. On a phone held upright the shorter side is the width, and on a television the height, so
the same view shows the same width of the world in either.

So with no pose and the default view, the centre of the view shows the centre of the picture. A
yaw of 90 shows the point three quarters of the way across the picture, and a pitch of 90 the top
row. A tile shows its part of the sphere and leaves the view transparent where it has no picture,
so whatever lies behind the video shows there.

### What applies and what does not

- The picture controls, HDR tone mapping, the flash guard and the subtitles apply to the view as
  they apply to a flat picture. The subtitles are drawn over the view, in the output's space, as
  on every renderer.
- The scale mode and every field of `VideoTransform` do not apply while the view is projected: the
  aspect, the zoom, the pan, the turn and the mirrors are a flat picture's. The field of view is
  the view's zoom. They apply again when the view is switched to the flat picture.
- `captureFrame` returns the decoded picture, flat. It does not capture the view.

### Turning the view

A change of the view reaches the renderer at once and is drawn with the next display refresh. It
costs no decoding, so turning the view while playing drops no frame, and a paused player shows the
turned view of the picture it holds. Calls that arrive faster than the player handles them are
merged into the newest, so a fast drag never queues behind itself.

## The backend interface

One member joins `PlayerStreamInfo` in the `spi` package, defaulted so that every existing backend
keeps compiling:

```kotlin
public data class PlayerStreamInfo(
    // ...
    /** How the stream's pictures cover the sphere, or null for a flat stream (#460). */
    val projection: VideoProjection? = null,
)
```

The FFmpeg backend fills it from the stream's `spherical`, on every platform including the web.

## The renderer interface

Two members join `VideoRenderer`, both defaulted, so a renderer that draws only flat pictures keeps
compiling and keeps drawing them:

```kotlin
public interface VideoRenderer {
    // ...
    /** True when this renderer draws [kind] as a view into the sphere. False by default. */
    public fun projects(kind: ProjectionKind): Boolean = false

    /**
     * The projection of the video the renderer draws, or null for a flat one, and the view into
     * it. Told on attach, at every open, at every change of the video track and on every change
     * of the view, like [setTransform].
     */
    public fun setProjection(projection: VideoProjection?, view: SphericalView) {}
}
```

The engine tells the renderer the projection of the selected video track and the player's view.
When the track has a projection that `projects` answers false for, the engine warns
`ProjectionUnavailable` once for that item and renderer, and its detail says whether the renderer
draws no view at all or draws views but not this kind of projection, such as a cube map. A renderer
that draws a view keeps the picture it holds and draws it again for a new view, so a paused player
is asked to redraw only when the attached renderer draws the view.

## How the renderers draw it

Each renderer that projects draws the view in one shader pass over its output. For each pixel of
the output, the pass finds the direction it looks in, turns that direction by the view and by the
file's pose, and reads the picture at that direction's longitude and latitude:

- A pixel at `(x, y)` of a view `w` by `h` looks along `(s * (x - w / 2), s * (h / 2 - y), -1)`,
  where `s` is the tangent of half the field of view over half the shorter side. The axes are
  OpenGL's: x to the right, y up and z toward the viewer.
- The view's roll, pitch and yaw turn that direction, in that order, into the direction in the
  world, and undoing the file's pose turns that into the direction on the picture's sphere.
- The longitude is `atan2(x, -z)` and the latitude `atan2(y, sqrt(x * x + z * z))` of the turned
  direction, which needs no normalising. The picture is read at `0.5 + longitude / 360°` across and
  `0.5 - latitude / 180°` down, wrapping across the width so the left and right edges join without
  a seam. A tile maps those shares onto its own bounds and has no picture outside them.

The turns are computed once per draw on the processor, from the view and the pose, and reach the
shader as one 3 by 3 matrix, so the shader takes no sine or cosine of its own; only the longitude
and the latitude need trigonometry per pixel. `SphericalLens`, in the core's `spi` package and
behind `KitePlayerInternalApi`, is that law in plain Kotlin: a renderer builds one for each draw
and hands its `rotation`, `scale`, centre and bounds to its shader, and its `pictureAt` is the
oracle each renderer's tests compare the drawn view against.

### Which renderers draw the view

- **`KiteVideo`**, the Compose canvas path in `kiteplayer-compose-video`:
  - On the desktop JVM and iOS, where Compose draws through Skia, as a Skia runtime shader over the
    converted picture.
  - On Android 13 and later, as the same program in an Android runtime shader. Below Android 13 it
    draws flat and warns. On Android the GPU tier that converts MediaCodec's frames for Compose
    cannot draw the view in its own blit, so while Compose draws a view it hands Compose the whole
    picture at its own size, up to 4096 pixels on its longer side, instead of one fitted to the
    viewport.
  - On the web, in its WebGL pass (#125), as a second fragment program beside the flat one.
- **Not yet, in #586**: the Metal renderer on iOS and macOS, and Android's platform view, where
  MediaCodec writes straight to the Surface and no shader of the player's sees the picture.
- **Not yet, in #587**: the AWT canvas of the desktop's native view, which paints without a shader.
- **Flat, by design for now**: the Core Graphics reference, the sample buffer layer and the web's
  2D canvas.

`KitePlayerVideo` draws through `KiteVideo` when it runs `KiteRenderPath.ComposeCanvas`, which it
always does on the web. Its default on Android, iOS and the desktop JVM is the native view, which
draws a 360 degree video flat until #586 and #587 are done, so an application that plays one asks
for `KiteRenderPath.ComposeCanvas` there.

## Tests

`ffmpeg` cannot write a projection into a file. `scripts/mkv-rewrite.py` (first named
`mkv-editions.py`) rewrites a Matroska file that `ffmpeg` made, and its `--projection` option writes
the track's `Projection` element: its type, its pose, and for a tile its bounds or for a cube map
its padding. FFmpeg reads that element back as the stream's spherical mapping.

The fixtures, which `scripts/testmedia.sh` makes in `testmedia/spherical/`, show a 512 by 256
equirectangular picture of eight vertical bands, each 45 degrees of longitude wide and centred on a
multiple of 45 degrees, so the band behind the viewer is split between the left and the right edge.
From the left: blue (180 degrees), magenta (-135), white (-90), orange (-45), red (0, the centre),
yellow (45), green (90), cyan (135) and blue again. A dark grey band runs along the top and a brown
one along the bottom, 22.5 degrees of latitude each.

- `equirect.mkv`, with no pose;
- `equirect-yaw30.mkv`, with a pose of 30 degrees of yaw;
- `equirect-tile.mkv`, the middle half of the picture's width, from -90 to 90 degrees, as a tile.

The tests:

- `SphericalLensTest` in `kiteplayer-core` reads the law at the centre and the edges of views, where
  each turn's direction can be worked out by hand: the yaw and the pitch, a clockwise roll, the
  field of view across the shorter side, the file's pose and a tile.
- `SphericalViewTest` in `kiteplayer-core`, through a scripted backend and renderer: the video track
  lists its projection, the view is published and a value out of range is refused, the renderer is
  told on attach, at an open and on a change, a picture that moves to a flat stream is told as flat,
  a renderer that draws only flat pictures is warned about once for the item, one that projects is
  not, the warning for one that projects a sphere and not a cube map names the cube map, a view
  turned a whole circle in three seconds while the player plays keeps it playing, never seeks and
  costs no frame, late or otherwise, in virtual time, and a paused player redraws a turned view
  only where the renderer draws the view. `MementoTest` checks that the memento keeps the view.
- `SphericalVideoTest` in `kiteplayer-ffmpeg` checks that the FFmpeg backend reports each fixture's
  projection and pose and a flat clip none, and that the whole player lists the projection on the
  video track and warns a renderer of flat pictures once.
- `SphereViewDrawTest` in `kiteplayer-compose-video` draws views of the same picture through Skia on
  the JVM. Each matches `SphericalLens` wherever the picture is one colour, and the band checks do
  not use the lens at all: the default view's centre shows the red band, a yaw of 90 the green
  band, a yaw of -90 the white band, half a turn the blue band where the edges join, a pitch of 90
  the top, a clockwise roll puts the bottom on the right, and the pose and the tile show what they
  should, with nothing beyond the tile. A video with no view draws exactly the pixels a flat one
  does, in every scale mode, and the renderer hands the draw a view only when the view is projected
  and the projection is one it draws.
- `SphereViewPlaybackTest` plays `equirect.mkv` through the whole player and `KiteVideo`'s renderer
  on the JVM while the yaw turns a whole circle in three seconds: it keeps playing, and no frame is
  thrown away before decoding or refused, and nothing rebuffers. Late frames are left to the
  virtual-time test, because on a busy machine they answer the load. The pose of
  `equirect-yaw30.mkv` and the tile of `equirect-tile.mkv` reach the draw.
- `WebSphereViewTest` draws the same views through the WebGL pass in headless Chromium.
- An Android host test checks that the GPU tier keeps the picture's own size, up to 4096 pixels,
  while Compose draws a view.

Each test was seen to fail with the projection taken out, or a turn's sign flipped, before it was
seen to pass.

## Not in this design

Each of these is a later issue if it is wanted:

- **The Metal renderer and Android's platform view**, which #586 follows.
- **The desktop's native view**, the AWT canvas, which has no shader to draw a view with and which
  #587 follows.
- **Cube maps**, fisheye and the other projections, which `ProjectionKind` already names.
- **Stereo 3D**, where each eye has half the picture.
- **A drag and a sensor in the built-in controls**. An application turns the view itself for now.
- **A capture of the view**, which `captureFrame` does not take.

## The order of the work

1. The projection on `PlayerStreamInfo` and `TrackInfo`, `SphericalView`, `setSphericalView`, the
   snapshot, the memento, the web worker's messages, the renderer interface and the warning, the
   fixtures and the script. Nothing drew differently yet.
2. The Compose canvas path, with the oracle and its tests.
3. The web's WebGL pass and its browser test.
4. The user documentation, the changelog and the follow-up issues: #586 for the Metal renderer and
   Android's platform view, and #587 for the desktop's native view.

Each step regenerates the ABI dumps it changes and carries its own tests.
