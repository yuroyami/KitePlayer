# Desktop interop spike, measured 2026-08-30

The Phase 1 stop gate: can a heavyweight AWT canvas paint video independently of Compose's frame
clock, and can Compose still put working controls on top of it?

Everything below is measured by the spike itself rather than by watching the window. The canvas
paints pure red on its own thread through a `BufferStrategy`; one Compose box paints pure green
over it. A `Robot` reads the pixel where they overlap, which settles who is on top, and clicks
that same point, which settles who receives input. Those are two different questions on macOS.

Machine: this Mac (Apple silicon, Retina), JDK 21, Compose Multiplatform 1.12.0-rc01,
Kotlin 2.4.10. Each frame-rate arm runs 12 seconds.

## Results

| # | Host | Flags | Draws above | Receives clicks | Decoupling |
|---|---|---|---|---|---|
| A | Compose `Window` + `SwingPanel` | none | canvas above | no | PASS |
| B | Compose `Window` + `SwingPanel` | `compose.interop.blending` | **Compose above** | no | PASS |
| C | Compose `Window` + `SwingPanel` | blending + `swing.render.on.graphics` | **Compose above** | no | PASS |
| D | Swing `JLayeredPane` + `ComposePanel` | none | **Compose above** | no | PASS |
| E | Swing `JLayeredPane` + `ComposePanel` | `swing.render.on.graphics` | canvas above | no | PASS |
| F | as D, plus canvas mouse events forwarded to the `ComposePanel` | none | **Compose above** | no | PASS |
| **G** | **`JFrame` canvas + owned borderless `JWindow` of Compose** | none | **Compose above** | **YES** | **PASS** |
| control | Compose `Window`, NO interop component at all | none | **Compose above** | **yes** | n/a |

## What passed, and it is the load-bearing half

**Decoupling: PASS in all six configurations, decisively.** With the Compose frame clock choked
by 200 ms of work per frame, the canvas kept 100 to 102 percent of its own idle rate while
Compose kept 4 to 8 percent of its. Typical numbers: canvas 51.1 fps idle and 51.1 fps choked,
Compose 60.1 fps idle and 4.8 fps choked.

The comparison is deliberately against each side's OWN idle rate rather than an absolute frame
rate, because an absolute bar would only measure how busy this machine happened to be. The canvas
does not reach 60 because it is paced by `Thread.sleep`, which is a property of the spike and not
of the design.

So the premise the whole desktop native view rests on is true: video painted by a heavyweight
canvas does not care what the Compose UI is doing. That is exactly the problem the owner
reported, and this says the approach cures it.

## What failed

**Compose never received a click on the overlaid control, in any configuration.** Not with
blending, not with the Swing-layered host, and not when the canvas explicitly forwarded its mouse
events to the `ComposePanel`.

This is the macOS limitation Compose documents in `ComposeFeatureFlags.desktop.kt`: "On macOS,
render and event dispatching order differs. It means that interop view might catch the mouse
event even if visually it renders below Compose content." The spike measures exactly that split:
in B, C, D and F the green box is demonstrably ON TOP, and the click still does not reach it.

## G is the answer, and it says what the cause really was

Configurations A to F lose the click for one reason, and it is not Compose's fault. On macOS the
heavyweight canvas is a native view, and the window server decides where a click goes by asking
which NATIVE view is topmost under the cursor. What Compose painted over it afterwards never
enters that decision. That is why blending fixes the picture and cannot fix the input, and why
forwarding events by hand (F) did not help either: the event was already delivered to the wrong
place by the time our code saw it.

A separate window is not subject to that at all. In G the controls live in a borderless `JWindow`
owned by the video window, so where they sit they ARE the topmost native thing, and the mouse
goes to them because the window server agrees rather than because Compose drew last.

**G passes all three properties at once**: the canvas keeps 100 percent of its idle rate while
Compose is choked to 8, Compose draws above, and the click lands. Reproduced three times by the
robot, then CONFIRMED BY A HUMAN clicking the square: the owner reported the counter rising and
the process log recorded three separate human clicks reaching Compose.

**Why the human confirmation was worth insisting on.** The first attempt to confirm G by hand
used a sticky boolean printed once a second, which reported a click at second one, before anyone
had touched the mouse. A flag that is true cannot say who set it or when. It was replaced with a
counter that resets when the window opens and prints only changes, and that is what produced the
three confirmed clicks.

## The bound on the input result for A to F, stated because it decided the design

Every click here is synthetic, from `java.awt.Robot`. The positive control proves the mechanism
works: with no interop component in the window, the same synthetic click reaches the same Compose
box and the handler fires. So the failure is caused by the presence of the heavyweight canvas,
not by the robot being unable to click Compose.

That bound is now closed rather than open: the owner clicked configuration B by hand and the
counter did not move, which matches the robot exactly. So the robot's verdict was trustworthy in
both directions, failing where a human fails and succeeding where a human succeeds, and the
layered architectures are genuinely unusable for overlaid controls on macOS.

## A correction worth keeping, because it nearly produced a false verdict

The first run of the full matrix reported that Compose was BELOW the canvas in every
configuration, which would have killed the design. It was wrong. Compose reports layout positions
in pixels while AWT's `locationOnScreen` speaks logical points, and on a Retina display those
differ by the display density, so the probe was reading a point outside the green box and seeing
window background.

The positive control is what caught it: with no canvas at all the probe still failed to find
green, which cannot be a z-order problem. Without that arm the spike would have reported a
stop-gate failure that was purely an artefact of its own ruler.

## What this means for the design

The desktop native view survives, with no JAWT and no platform GPU code, but the shape changes:
the video is a heavyweight canvas in the window, and any Compose control that must be CLICKED
while sitting over the video belongs in an owned overlay window rather than in the same Compose
tree. Controls that never overlap the video are unaffected and can stay ordinary Compose content.

One cosmetic item found while confirming G and fixed in the harness: the overlay window must have
BOTH a transparent window background and a non-opaque `ComposePanel`, or it paints a grey slab
over the video. Setting only the window is not enough.

# End to end, on real video, 2026-08-30

The spike above measures a coloured rectangle. This measures the thing the phase is for: a real
player, a real 1080p30 clip on loop, the engine's own counters, one arm per process so the two
paths never share a window or a warmed decoder. Run it with `PathComparisonKt` and
`-Dcompare.path=native|compose -Dcompare.burn=true|false`.

| arm | engine submitted (15 s) | dropped late | compose fps |
|---|---|---|---|
| compose canvas, idle | 445 | 0 | 60.0 |
| compose canvas, UI choked | 442 | 0 | 4.7 |
| native view, idle | 445 | 0 | 60.1 |
| native view, UI choked | 439 | 0 | 4.7 |

## Reading it, including the part that surprised me

**The engine is untouched in every arm.** It submits about 445 frames in 15 seconds, which is the
clip's 30 fps, and drops none. That is correct rather than disappointing: the video scheduler runs
on its own dispatcher and keeps submitting whatever the UI does, so these counters CANNOT separate
the two paths and were never going to. A first run of this harness compared them and found nothing,
which is exactly what it should have found.

**The separation is at the draw step**, and that is why compose fps is in the table. On the
Compose canvas the picture is drawn BY Compose, so when Compose falls to 4.7 fps the picture falls
with it: about 70 draws in the window instead of 445. On the native view `present` paints
synchronously through the BufferStrategy, so its 439 submissions are 439 actual paints, about
29 frames a second of real video on screen, while the very same process was running its UI at
4.7 fps.

**So the phase's premise holds end to end**: a UI running at 4.7 fps and a picture running at 29.

## Two corrections this harness needed before it could say anything

The first version measured a 10 second clip over a 15 second window and reported both paths as
identical, because most of the window was not playing at all. It loops now.

The second version reported only engine counters, which cannot see the difference by
construction. Reporting the Compose frame rate beside them is what makes the arms comparable, and
the KDoc now says what the numbers mean so they are not read as saying more than they do.
