# 360 degree video

This document is the design for issue #460. Nothing in it is implemented yet. It states the public
API, what the player does with a 360 degree video, and how the renderers draw one, so that all
three can be agreed before the code is written.

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
the view by dragging or by moving the phone. The player today draws the whole picture flat, which
looks like a stretched panorama.

## What KiteFFmpeg already gives

KiteFFmpeg 0.5.1, which this repository pins, reports each stream's mapping as
`StreamInfo.spherical`, a `SphericalMapping` (KiteFFmpeg #139): the projection, with what that
projection carries, and the pose in degrees. Its projections are equirectangular, a tile of an
equirectangular picture with its four bounds, a cube map with its padding, half equirectangular,
rectilinear, fisheye and Apple's parametric immersive. It also reports a stream's stereo layout as
`StreamInfo.stereo3d`, which this design does not use.

FFmpeg reads the mapping from MP4, MOV and Matroska and applies none of it. So the player needs no
FFmpeg change. What is left is all in this repository: carry the mapping to the item's tracks and
to the renderer, keep the view's state, and draw the view.

## Public API

Every name below is new. Nothing that exists changes its meaning for a flat video.

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
    val yaw: Double,
    val pitch: Double,
    val roll: Double,
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
     * The video that plays is a 360 degree video, and the renderer that draws it shows the flat
     * picture, because it cannot draw a view into a sphere or into this [kind] of projection
     * (#460). Told once for each item and renderer.
     */
    public data class ProjectionUnavailable(val kind: ProjectionKind, val detail: String) : PlaybackWarning()
}
```

An application reads whether the video that plays is a 360 degree one from
`state.tracks.selectedTrack(TrackKind.Video)?.projection`, and offers its drag and its sensor only
then.

`PlayerMemento` keeps the view, as it keeps `VideoTransform`, so a restore looks where the viewer
looked.

## What the player does

### Which videos it projects

A video is drawn as a view into the sphere when its track has a projection the renderer draws,
and the view is `projected`. This design draws `Equirectangular` and `EquirectangularTile`. Every
other video is drawn exactly as now, including a 360 degree video whose projection the renderer
does not draw, which `ProjectionUnavailable` reports.

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
row. A tile shows its part of the sphere and draws the renderer's background where it has no
picture.

### What applies and what does not

- The picture controls, HDR tone mapping, the flash guard and the subtitles apply to the view as
  they apply to a flat picture. The subtitles are drawn over the view, in the output's space, as
  on every renderer.
- The scale mode and every field of `VideoTransform` do not apply while the view is projected: the
  aspect, the zoom, the pan, the turn and the mirrors are a flat picture's. The field of view is
  the view's zoom. They apply again when the view is switched to the flat picture.
- `captureFrame` returns the decoded picture, flat, as now. A capture of the view is not in this
  design.

### Turning the view

A change of the view reaches the renderer at once, through the renderer interface below, and is
drawn with the next display refresh. It costs no decoding, so turning the view while playing drops
no frame, and a paused player shows the turned view of the picture it holds.

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

The FFmpeg backend fills it from `StreamInfo.spherical`, on every platform including the web.

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
`ProjectionUnavailable` once for that item and renderer. A renderer that draws a view keeps the
picture it holds and draws it again for a new view, so `redrawsHeldPicture` stays true for it.

## How the renderers draw it

Each renderer that projects draws the view in one shader pass over its output. For each pixel of
the output, the pass finds the direction it looks in, turns that direction by the view and by the
file's pose, and reads the picture at that direction's longitude and latitude:

- A pixel at `(x, y)` of a view `w` by `h` looks along `(s * (x - w / 2), s * (h / 2 - y), -1)`,
  where `s` is the tangent of half the field of view over half the shorter side. The axes are
  OpenGL's: x to the right, y up and z toward the viewer.
- The view's roll, pitch and yaw turn that direction, in that order, into the direction in the
  world, and undoing the file's pose turns that into the direction on the picture's sphere.
- The longitude is `atan2(x, -z)` and the latitude is `asin(y)` of the turned direction. The
  picture is read at `0.5 + longitude / 360°` across and `0.5 - latitude / 180°` down, wrapping
  across the width so the left and right edges join without a seam.

The turns are computed once per draw on the processor, from the view and the pose, and reach the
shader as one 3 by 3 matrix, so the shader takes no sine or cosine of its own; only the longitude
and the latitude need trigonometry per pixel. The same law in plain Kotlin, in common code, is the
oracle that each renderer's output is compared against.

The renderers are taken in this order:

1. **The Compose canvas path**, `KiteVideo` in `kiteplayer-compose-video`. On the platforms where
   Compose draws through Skiko (the desktop JVM, iOS and macOS) the pass is a Skia runtime shader
   over the converted picture. On Android the same shader runs as an Android runtime shader, which
   needs Android 13; below it, the path draws flat and warns. This is the one path that can be
   checked against the oracle in this repository's JVM tests, where Skia draws without a display.
2. **The web's WebGL pass**, the one `KiteVideo` draws through on `wasmJs` (#125). The pass already
   reads one texture and draws one quad, and its fragment shader gains the view. It is checked in
   headless Chromium, where the browser tests already run.
3. **The platform-view defaults**: the Metal renderer on Apple and the Android GPU image renderer.
   Their shaders gain the same pass, but they can only be checked on a Mac and on an Android
   device, so they are a follow-up issue rather than part of this one.

Every other renderer draws flat and is told so by `projects`: the Core Graphics reference, the
sample buffer layer, the AWT canvas on the desktop, the web's 2D canvas, and a MediaCodec frame
that goes straight to Android's Surface, which no shader of the player's sees.

So once this issue is done, a 360 degree video turns in `KiteVideo` on the desktop, iOS, macOS, the
web and Android 13 and later, and in `KitePlayerVideo` wherever that draws through `KiteVideo`.
On Android, where `KitePlayerVideo` defaults to the platform view, an application asks for the
Compose path (`KiteRenderPath.ComposeCanvas`) until the follow-up lands.

## Tests

`ffmpeg` cannot write a projection into a file. `scripts/mkv-editions.py` already rewrites a
Matroska file that `ffmpeg` made, and becomes `scripts/mkv-rewrite.py`, gaining a `--projection`
option that writes the track's `Projection` element: its type, its pose, and for a tile its bounds.
FFmpeg reads that element back as the stream's spherical mapping, which was checked by hand while
writing this design.

The fixtures, in `testmedia/spherical/`, all 2:1 equirectangular pictures of eight vertical bands
of distinct colours, each 45 degrees of longitude wide, with a darker band along the top and the
bottom:

- `equirect.mkv`, with no pose;
- `equirect-yaw30.mkv`, with a pose of 30 degrees of yaw;
- `equirect-tile.mkv`, the middle half of the same picture as a tile.

The tests:

- The FFmpeg backend reports each fixture's projection and pose, and a flat clip reports none.
- The snapshot lists the projection on the video track, `setSphericalView` publishes the view and
  refuses a value out of range, the memento keeps it, and the renderer is told on attach, at an
  open and on a change, through a scripted renderer in `kiteplayer-core`.
- On the JVM, the Compose path draws views of a known picture: the default view shows the centre
  band, a yaw of 90 the band three quarters across, a yaw of -90 the band one quarter across, a
  pitch of 90 the top band, the tile its part and the background beyond it, and the fixture with
  a pose its centre band at a yaw of 30. Each view is compared with the oracle's picture within a
  small difference, and the band checks do not rely on the oracle at all. A flat video, and a
  360 degree one with `projected = false`, draw exactly the pixels they draw today.
- The whole player plays `equirect.mkv` through the Compose path on the JVM while the yaw turns
  through a full circle, and drops no frame.
- A renderer that cannot project warns `ProjectionUnavailable` once and draws the flat picture.
- In headless Chromium, the web pass draws the same views as the Compose path.

Each test is seen to fail with the projection taken out, before it is seen to pass.

## Not in this design

Each of these is a later issue if it is wanted:

- **Metal and the Android GPU renderer**, as step 3 above says.
- **Cube maps**, fisheye and the other projections, which `ProjectionKind` already names.
- **Stereo 3D**, where each eye has half the picture.
- **A drag and a sensor in the built-in controls**. An application turns the view itself for now.
- **A capture of the view**, which `captureFrame` does not take.

## The order of the work

1. The projection on `PlayerStreamInfo` and `TrackInfo`, `SphericalView`, `setSphericalView`, the
   snapshot, the memento, the web worker's messages and the Java wrapper, the renderer interface
   and the warning, the fixtures and the script. Nothing draws differently yet.
2. The Compose canvas path, with the oracle and its tests.
3. The web's WebGL pass and its browser test.
4. The user documentation, the changelog and a follow-up issue for Metal and the Android GPU
   renderer.

Each step regenerates the ABI dumps it changes and carries its own tests.
