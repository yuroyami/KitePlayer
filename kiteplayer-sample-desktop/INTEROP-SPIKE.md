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

## The bound on the input result, stated because it decides a phase

Every click here is synthetic, from `java.awt.Robot`. The positive control proves the mechanism
works: with no interop component in the window, the same synthetic click reaches the same Compose
box and the handler fires. So the failure is caused by the presence of the heavyweight canvas,
not by the robot being unable to click Compose.

What that does NOT rule out is an interaction specific to synthetic events and heavyweight
components on macOS. A human clicking the green box in configuration B would settle it in half a
minute, and until someone does, the input verdict is "failed under synthetic input" rather than
"impossible".

## A correction worth keeping, because it nearly produced a false verdict

The first run of the full matrix reported that Compose was BELOW the canvas in every
configuration, which would have killed the design. It was wrong. Compose reports layout positions
in pixels while AWT's `locationOnScreen` speaks logical points, and on a Retina display those
differ by the display density, so the probe was reading a point outside the green box and seeing
window background.

The positive control is what caught it: with no canvas at all the probe still failed to find
green, which cannot be a z-order problem. Without that arm the spike would have reported a
stop-gate failure that was purely an artefact of its own ruler.
