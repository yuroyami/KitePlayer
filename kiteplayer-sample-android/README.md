# kiteplayer-sample-android

The provisional Android assembly proof (KPKMP.md S1.c.6). One plain Activity assembles
`KiteCodecMediaBackend`, `AndroidOutputBackend` and `AndroidSurfaceVideoRenderer` over a private
SurfaceView, plays the bundled sync clip, seeks, and tears down cleanly. Smoke mode
(`--ez s1c_smoke true`) writes the eleven-key JSON oracle the plan's jq predicate checks.

This app is glue, not product: no reusable view, no Compose, three project dependencies until
S1.d re-consumes it through one coordinate. Debug-signed release exists solely so the local
`run-as` oracle can read the result file; R8 still runs.
