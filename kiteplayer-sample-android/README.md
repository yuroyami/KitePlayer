# kiteplayer-sample-android

The Android assembly proof (KPKMP.md S1.c.6, re-consumed through the phone aggregate in
S1.d.4). One plain Activity builds a player from `phoneBackends()` and shows it in a
`KitePlayerView`, which owns the whole surface lifecycle; the app holds no SurfaceHolder
callback, no renderer and no Surface. It plays the bundled sync clip, seeks, and tears down
cleanly. Smoke mode (`--ez s1c_smoke true`) writes the eleven-key JSON oracle the plan's jq
predicate checks.

This app is glue, not product: exactly one project dependency, `:kiteplayer-phone`, plus the
Android Main-dispatcher artifact every ordinary app carries. No Compose here; that is
`:kiteplayer-compose`. Debug-signed release exists solely so the local `run-as` oracle can read
the result file; R8 still runs.
