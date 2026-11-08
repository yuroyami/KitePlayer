# KitePlayer and KiteFFmpeg audit by Claude Opus 5.5

Written on 2026-11-08 against KitePlayer `d5bb627` and KiteFFmpeg `f79ba08`, both on `main`. This file is a draft for the owner to check by hand before anything is posted to either issue tracker. Nothing here has been filed.

## How this audit was made

The two trees were read, not built. Twelve auditors each took one subsystem and applied every lens the brief asked for: performance, memory, concurrency and lifecycle, Kotlin and platform memory model traps, semantic correctness against the specs and FFmpeg, input bounds, native interop ownership, public API and packaging, documentation truth, test reliability, licensing, and comparison with mpv, VLC, Media3 and AVPlayer. Ten of them covered the subsystems listed below, and two more were then sent into the areas the first ten said they could not reach.

Every finding then went to a separate verifier whose only job was to disprove it: open the cited lines, retrace the path from a public entry point, look for a guard elsewhere, check it against both CLAUDE.md files and the 95 open issues, and fix drifted line numbers. Findings that did not survive are listed at the end of each section with the reason. Every finding of the first ten lanes that came out high was then given to a third, independent reader who had not seen the verifier's reasoning; five of those were moved down to medium, and the result is written under **Second check** in each one. The two high findings from the gap lanes were spot checked against the code by the coordinator instead, and say so.

Limits worth knowing before you trust any of it:

- No build, test, benchmark or device run was made, because a `./kotlin` build in a shared checkout would have broken the other readers and the brief asked for code reading across both trees. Every cost figure is an estimate from the code unless it says it was measured.
- FFmpeg's own source is not in the container. Claims about what libavformat or libavcodec do come from the vendored release where a reader found it, and otherwise from knowledge of FFmpeg 9.0.2, and each such claim says how to confirm it.
- **Plausible** means the reasoning holds in the code but a run is needed to prove it; each one says what run would.
- No critical finding survived. That is a result, not an omission: the candidates for critical were either refuted, already open as issues, or found to need a hostile local account.

## Totals

138 findings: 127 defects, risks, documentation and test findings, plus 11 opportunities kept apart from them.

| Severity | Count |
|---|---|
| critical | 0 |
| high | 11 |
| medium | 68 |
| low | 48 |

By repository: KitePlayer 107, KiteFFmpeg 21, both 10. A finding marked KiteFFmpeg is written so that it can be fixed in KiteFFmpeg with no knowledge of KitePlayer; a finding marked Both names the half that belongs to each repository in its suggested fix.

## Findings that share one root

These were found from two sides by two lanes. Post each group as one issue.

| Group | What they share |
|---|---|
| VID-01, BRG-05, WEB-16 | HDR is tone mapped after the picture has been cut to 8 bits, on the Kotlin software converter, the native converter and KiteFFmpeg's web display converter |
| ENG-07, VID-06 | The display refresh rate every renderer reports is stored and never read, while the interface says it improves pacing |
| NET-01, WEB-02 | The worker's synchronous request timeout covers the whole 16 MiB window, so links under about 4.5 Mbit/s stall |
| WEB-03, API-02 | A worker binary from another KitePlayer version hangs on the first message it cannot read |
| NAT-04, API-04 | No artifact carries dav1d's copyright notice |

## The high findings, in one place

| Id | Repo | Severity | Kind | Verdict | Title |
|---|---|---|---|---|---|
| [GKF-01](#gkf-01-a-remuxed-iphone-hevc-video-is-retagged-hev1-so-the-mp4-or-mov-it-becomes-no-longer-plays-in-apples-players) | KiteFFmpeg | high | defect | Confirmed | A remuxed iPhone HEVC video is retagged hev1, so the MP4 or MOV it becomes no longer plays in Apple's players |
| [GKP-01](#gkp-01-coming-back-to-a-music-app-cuts-the-sound-for-a-moment-and-leaving-or-coming-back-in-a-tracks-last-seconds-breaks-the-gapless-join) | KitePlayer | high | defect | Confirmed | Coming back to a music app cuts the sound for a moment, and leaving or coming back in a track's last seconds breaks the gapless join |
| [NET-01](#net-01-in-a-web-worker-a-progressive-file-stops-with-a-stall-on-any-connection-slower-than-about-45-mbits) | KitePlayer | high | defect | Confirmed | In a Web Worker a progressive file stops with a stall on any connection slower than about 4.5 Mbit/s |
| [NET-02](#net-02-a-live-dash-timeline-whose-segment-lasts-under-a-microsecond-hangs-the-player-for-ever) | KitePlayer | high | defect | Confirmed | A live DASH timeline whose segment lasts under a microsecond hangs the player for ever |
| [NET-03](#net-03-a-webm-dash-file-whose-header-declares-one-element-of-about-4-gib-hangs-the-player-for-ever) | KitePlayer | high | defect | Confirmed | A WebM DASH file whose header declares one element of about 4 GiB hangs the player for ever |
| [SUB-01](#sub-01-starting-libass-on-desktop-linux-can-delete-another-folders-files-through-a-link-planted-in-the-shared-temp-folder) | KitePlayer | high | defect | Confirmed | Starting libass on desktop Linux can delete another folder's files through a link planted in the shared temp folder |
| [SUB-02](#sub-02-on-desktop-linux-another-local-user-can-make-the-player-load-their-own-code-in-place-of-the-libass-adapter) | KitePlayer | high | defect | Plausible | On desktop Linux another local user can make the player load their own code in place of the libass adapter |
| [SUB-03](#sub-03-hls-webvtt-subtitles-ignore-x-timestamp-map-so-on-most-packaged-streams-every-line-shows-at-the-wrong-time) | Both | high | defect | Plausible | HLS WebVTT subtitles ignore X-TIMESTAMP-MAP, so on most packaged streams every line shows at the wrong time |
| [VIZ-01](#viz-01-the-flash-guard-lets-fast-flashes-through-whenever-the-dark-state-before-them-has-not-reached-a-new-low-for-03-seconds) | KitePlayer | high | defect | Confirmed | The flash guard lets fast flashes through whenever the dark state before them has not reached a new low for 0.3 seconds |
| [WEB-01](#web-01-network-playback-in-the-worker-drops-sound-and-freezes-the-picture-while-each-read-ahead-window-downloads) | KitePlayer | high | defect | Plausible | Network playback in the worker drops sound and freezes the picture while each read-ahead window downloads |
| [WEB-02](#web-02-a-worker-player-on-a-slow-connection-fails-a-long-file-with-a-stall-error-while-bytes-are-still-arriving) | KitePlayer | high | defect | Confirmed | A worker player on a slow connection fails a long file with a stall error while bytes are still arriving |

## Every finding

| Id | Repo | Severity | Kind | Verdict | Title |
|---|---|---|---|---|---|
| [GKF-01](#gkf-01-a-remuxed-iphone-hevc-video-is-retagged-hev1-so-the-mp4-or-mov-it-becomes-no-longer-plays-in-apples-players) | KiteFFmpeg | high | defect | Confirmed | A remuxed iPhone HEVC video is retagged hev1, so the MP4 or MOV it becomes no longer plays in Apple's players |
| [GKP-01](#gkp-01-coming-back-to-a-music-app-cuts-the-sound-for-a-moment-and-leaving-or-coming-back-in-a-tracks-last-seconds-breaks-the-gapless-join) | KitePlayer | high | defect | Confirmed | Coming back to a music app cuts the sound for a moment, and leaving or coming back in a track's last seconds breaks the gapless join |
| [NET-01](#net-01-in-a-web-worker-a-progressive-file-stops-with-a-stall-on-any-connection-slower-than-about-45-mbits) | KitePlayer | high | defect | Confirmed | In a Web Worker a progressive file stops with a stall on any connection slower than about 4.5 Mbit/s |
| [NET-02](#net-02-a-live-dash-timeline-whose-segment-lasts-under-a-microsecond-hangs-the-player-for-ever) | KitePlayer | high | defect | Confirmed | A live DASH timeline whose segment lasts under a microsecond hangs the player for ever |
| [NET-03](#net-03-a-webm-dash-file-whose-header-declares-one-element-of-about-4-gib-hangs-the-player-for-ever) | KitePlayer | high | defect | Confirmed | A WebM DASH file whose header declares one element of about 4 GiB hangs the player for ever |
| [SUB-01](#sub-01-starting-libass-on-desktop-linux-can-delete-another-folders-files-through-a-link-planted-in-the-shared-temp-folder) | KitePlayer | high | defect | Confirmed | Starting libass on desktop Linux can delete another folder's files through a link planted in the shared temp folder |
| [SUB-02](#sub-02-on-desktop-linux-another-local-user-can-make-the-player-load-their-own-code-in-place-of-the-libass-adapter) | KitePlayer | high | defect | Plausible | On desktop Linux another local user can make the player load their own code in place of the libass adapter |
| [SUB-03](#sub-03-hls-webvtt-subtitles-ignore-x-timestamp-map-so-on-most-packaged-streams-every-line-shows-at-the-wrong-time) | Both | high | defect | Plausible | HLS WebVTT subtitles ignore X-TIMESTAMP-MAP, so on most packaged streams every line shows at the wrong time |
| [VIZ-01](#viz-01-the-flash-guard-lets-fast-flashes-through-whenever-the-dark-state-before-them-has-not-reached-a-new-low-for-03-seconds) | KitePlayer | high | defect | Confirmed | The flash guard lets fast flashes through whenever the dark state before them has not reached a new low for 0.3 seconds |
| [WEB-01](#web-01-network-playback-in-the-worker-drops-sound-and-freezes-the-picture-while-each-read-ahead-window-downloads) | KitePlayer | high | defect | Plausible | Network playback in the worker drops sound and freezes the picture while each read-ahead window downloads |
| [WEB-02](#web-02-a-worker-player-on-a-slow-connection-fails-a-long-file-with-a-stall-error-while-bytes-are-still-arriving) | KitePlayer | high | defect | Confirmed | A worker player on a slow connection fails a long file with a stall error while bytes are still arriving |
| [API-01](#api-01-a-web-page-that-serves-the-codec-module-of-another-kiteffmpeg-release-is-accepted-and-then-read-at-the-wrong-offsets) | KiteFFmpeg | medium | risk | Confirmed | A web page that serves the codec module of another KiteFFmpeg release is accepted and then read at the wrong offsets |
| [API-02](#api-02-a-web-page-whose-worker-file-comes-from-another-kiteplayer-version-waits-for-ever-on-the-first-call-the-worker-cannot-read) | KitePlayer | medium | risk | Confirmed | A web page whose worker file comes from another KitePlayer version waits for ever on the first call the worker cannot read |
| [API-03](#api-03-the-rememberkiteplayer-example-crashes-the-app-as-soon-as-the-address-it-is-keyed-on-changes) | KitePlayer | medium | docs | Confirmed | The rememberKitePlayer example crashes the app as soon as the address it is keyed on changes |
| [API-04](#api-04-no-artifact-carries-dav1ds-copyright-notice-and-licence-text-which-its-bsd-licence-requires-in-every-binary) | KiteFFmpeg | medium | defect | Confirmed | No artifact carries dav1d's copyright notice and licence text, which its BSD licence requires in every binary |
| [API-05](#api-05-the-readmes-track-sample-does-not-compile-against-the-release-it-tells-people-to-install) | KitePlayer | medium | docs | Confirmed | The README's track sample does not compile against the release it tells people to install |
| [API-06](#api-06-an-app-that-adds-another-ktor-engine-silently-changes-the-http-stack-the-player-streams-through) | KitePlayer | medium | design | Plausible | An app that adds another Ktor engine silently changes the HTTP stack the player streams through |
| [API-07](#api-07-a-swift-app-sees-every-player-time-as-a-raw-encoded-number-and-has-no-simple-way-to-listen-to-the-player) | KitePlayer | medium | design | Plausible | A Swift app sees every player time as a raw encoded number and has no simple way to listen to the player |
| [API-08](#api-08-the-readmes-linker-flags-for-a-static-ios-framework-leave-out-libraries-that-libass-and-the-players-apple-code-need) | KitePlayer | medium | docs | Plausible | The README's linker flags for a static iOS framework leave out libraries that libass and the player's Apple code need |
| [AUD-01](#aud-01-a-music-app-that-builds-audioplayback-on-its-own-gets-a-clock-on-a-different-time-base-from-every-shipped-audio-sink) | KitePlayer | medium | defect | Confirmed | A music app that builds AudioPlayback on its own gets a clock on a different time base from every shipped audio sink |
| [AUD-02](#aud-02-a-surround-layout-with-height-speakers-plays-only-its-front-pair-on-stereo-losing-the-dialogue) | KitePlayer | medium | defect | Confirmed | A surround layout with height speakers plays only its front pair on stereo, losing the dialogue |
| [AUD-03](#aud-03-when-the-audio-runs-dry-mid-stream-the-position-keeps-moving-through-the-silence-and-then-jumps-back) | KitePlayer | medium | defect | Plausible | When the audio runs dry mid-stream the position keeps moving through the silence and then jumps back |
| [AUD-04](#aud-04-on-android-the-clock-runs-ahead-of-the-sound-after-every-start-seek-and-resume-until-the-track-reports-a-timestamp) | KitePlayer | medium | risk | Plausible | On Android the clock runs ahead of the sound after every start, seek and resume until the track reports a timestamp |
| [AUD-05](#aud-05-raising-the-volume-just-above-100-percent-makes-loud-passages-quieter-and-distorts-them) | KitePlayer | medium | design | Confirmed | Raising the volume just above 100 percent makes loud passages quieter and distorts them |
| [BRG-01](#brg-01-a-video-in-a-pixel-format-outside-the-eleven-the-engine-models-plays-with-no-picture-on-every-renderer-except-the-web) | KitePlayer | medium | defect | Confirmed | A video in a pixel format outside the eleven the engine models plays with no picture on every renderer except the web |
| [BRG-02](#brg-02-decoders-are-opened-without-a-packet-time-base-so-the-first-sound-after-encoder-priming-keeps-its-untrimmed-time) | KiteFFmpeg | medium | defect | Plausible | Decoders are opened without a packet time base, so the first sound after encoder priming keeps its untrimmed time |
| [BRG-03](#brg-03-a-packet-sent-to-a-drained-decoder-is-reported-as-decoded-and-silently-thrown-away) | KiteFFmpeg | medium | defect | Confirmed | A packet sent to a drained decoder is reported as decoded and silently thrown away |
| [BRG-04](#brg-04-webvtt-captions-read-through-ffmpeg-lose-their-position-line-and-alignment-settings) | Both | medium | defect | Confirmed | WebVTT captions read through FFmpeg lose their position, line and alignment settings |
| [BRG-05](#brg-05-hdr-video-drawn-by-the-software-converter-is-cut-to-8-bits-before-tone-mapping-so-dark-gradients-band) | KitePlayer | medium | defect | Confirmed | HDR video drawn by the software converter is cut to 8 bits before tone mapping, so dark gradients band |
| [BRG-06](#brg-06-on-apple-devices-aac-and-dolby-audio-decode-to-16-bit-integers-by-default-which-clips-loud-peaks) | KitePlayer | medium | design | Plausible | On Apple devices AAC and Dolby audio decode to 16-bit integers by default, which clips loud peaks |
| [ENG-01](#eng-01-pressing-play-at-the-end-right-after-a-seek-bar-drag-restarts-the-item-from-the-beginning-instead-of-from-the-dragged-position) | KitePlayer | medium | defect | Confirmed | Pressing play at the end right after a seek bar drag restarts the item from the beginning instead of from the dragged position |
| [ENG-02](#eng-02-closing-the-player-seeking-or-changing-tracks-near-the-end-of-a-queue-item-releases-the-preloaded-next-item-on-the-player-loop-with-no-time-limit) | KitePlayer | medium | defect | Confirmed | Closing the player, seeking or changing tracks near the end of a queue item releases the preloaded next item on the player loop with no time limit |
| [ENG-03](#eng-03-taking-a-screenshot-of-a-paused-player-or-turning-the-picture-back-on-cancels-the-seek-the-viewer-just-asked-for) | KitePlayer | medium | defect | Confirmed | Taking a screenshot of a paused player, or turning the picture back on, cancels the seek the viewer just asked for |
| [ENG-04](#eng-04-a-screenshot-asked-for-while-playing-waits-until-playback-resumes-if-the-player-is-paused-first-and-never-returns-while-the-picture-is-turned-off) | KitePlayer | medium | defect | Confirmed | A screenshot asked for while playing waits until playback resumes if the player is paused first, and never returns while the picture is turned off |
| [ENG-05](#eng-05-choosing-the-current-video-quality-again-does-not-cancel-a-different-quality-change-that-is-still-on-its-way) | KitePlayer | medium | defect | Confirmed | Choosing the current video quality again does not cancel a different quality change that is still on its way |
| [ENG-06](#eng-06-an-unexpected-failure-between-close-and-the-player-loop-reaching-it-leaves-the-whole-session-running-with-sound-still-playing) | KitePlayer | medium | risk | Plausible | An unexpected failure between close() and the player loop reaching it leaves the whole session running, with sound still playing |
| [ENG-07](#eng-07-the-display-refresh-rate-a-renderer-reports-is-stored-and-never-used-although-the-interface-promises-smoother-pacing-with-it) | KitePlayer | medium | docs | Confirmed | The display refresh rate a renderer reports is stored and never used, although the interface promises smoother pacing with it |
| [GKF-02](#gkf-02-a-trimmed-remux-or-copy-transcode-ends-its-sound-early-because-every-stream-stops-at-the-first-picture-packet-past-the-end) | KiteFFmpeg | medium | defect | Confirmed | A trimmed remux or copy transcode ends its sound early, because every stream stops at the first picture packet past the end |
| [GKF-03](#gkf-03-an-mp4-written-to-a-byte-sink-with-the-faststart-option-the-docs-suggest-loses-its-index-and-cannot-be-played-at-all) | KiteFFmpeg | medium | defect | Confirmed | An MP4 written to a byte sink with the faststart option the docs suggest loses its index and cannot be played at all |
| [GKP-02](#gkp-02-pressing-play-during-a-phone-call-or-another-apps-short-sound-on-android-plays-over-it-without-asking-for-audio-focus-again) | KitePlayer | medium | defect | Confirmed | Pressing play during a phone call or another app's short sound on Android plays over it without asking for audio focus again |
| [GKP-03](#gkp-03-on-the-iphone-lock-screen-and-in-carplay-a-music-queue-shows-15-second-skip-buttons-instead-of-previous-and-next-track) | KitePlayer | medium | defect | Confirmed | On the iPhone lock screen and in CarPlay a music queue shows 15 second skip buttons instead of previous and next track |
| [GKP-04](#gkp-04-after-ios-resets-its-media-services-the-player-stays-silent-with-its-clock-stopped-until-the-item-is-opened-again) | KitePlayer | medium | risk | Plausible | After iOS resets its media services, the player stays silent with its clock stopped until the item is opened again |
| [NAT-01](#nat-01-encrypted-hls-that-cuts-one-file-into-byte-ranges-plays-corrupted-after-the-first-segment-when-a-nested-opener-serves-it) | KiteFFmpeg | medium | defect | Confirmed | Encrypted HLS that cuts one file into byte ranges plays corrupted after the first segment when a nested opener serves it |
| [NAT-02](#nat-02-on-the-web-an-hls-key-or-playlist-given-inline-as-a-data-address-fails-to-open-though-the-opener-documentation-says-ffmpeg-reads-it) | KiteFFmpeg | medium | defect | Confirmed | On the web an HLS key or playlist given inline as a data: address fails to open, though the opener documentation says FFmpeg reads it |
| [NAT-03](#nat-03-no-fuzz-target-reaches-the-aes-128-reader-the-nested-opener-or-the-dolby-vision-and-display-converters-that-kiteffmpeg-wrote-itself) | KiteFFmpeg | medium | test | Confirmed | No fuzz target reaches the AES-128 reader, the nested opener or the Dolby Vision and display converters that KiteFFmpeg wrote itself |
| [NET-04](#net-04-a-hostile-hls-playlist-with-one-very-long-segment-address-ties-up-the-player-for-minutes-to-hours-while-its-addresses-are-resolved) | KitePlayer | medium | defect | Confirmed | A hostile HLS playlist with one very long segment address ties up the player for minutes to hours while its addresses are resolved |
| [NET-05](#net-05-dashmediaitemfor-resolves-a-redirected-manifests-relative-segment-addresses-against-the-address-before-the-redirect) | KitePlayer | medium | defect | Confirmed | Dash.mediaItemFor resolves a redirected manifest's relative segment addresses against the address before the redirect |
| [NET-06](#net-06-the-public-dash-segment-plan-drops-segments-of-a-repeating-timeline-when-the-template-has-a-presentation-time-offset) | KitePlayer | medium | defect | Confirmed | The public DASH segment plan drops segments of a repeating timeline when the template has a presentation time offset |
| [NET-07](#net-07-a-live-dash-clock-that-fails-to-answer-once-is-never-asked-again-and-the-device-clock-rules-the-whole-session) | KitePlayer | medium | design | Confirmed | A live DASH clock that fails to answer once is never asked again, and the device clock rules the whole session |
| [NET-08](#net-08-a-live-dash-stream-with-a-long-time-shift-window-fails-to-open-past-the-url-budget-and-rewrites-its-whole-window-on-every-reload) | KitePlayer | medium | design | Confirmed | A live DASH stream with a long time-shift window fails to open past the URL budget and rewrites its whole window on every reload |
| [NET-09](#net-09-an-items-custom-headers-follow-a-redirect-to-another-host-though-the-docs-say-they-go-only-to-the-items-own-origin) | KitePlayer | medium | risk | Plausible | An item's custom headers follow a redirect to another host, though the docs say they go only to the item's own origin |
| [NET-10](#net-10-the-web-worker-reader-can-splice-the-bytes-of-two-versions-of-a-file-into-one-stream) | KitePlayer | medium | risk | Plausible | The Web Worker reader can splice the bytes of two versions of a file into one stream |
| [NET-11](#net-11-a-stored-segment-cut-short-by-a-power-loss-turns-the-segment-store-off-on-every-later-play-of-that-item-and-is-never-removed) | KitePlayer | medium | defect | Plausible | A stored segment cut short by a power loss turns the segment store off on every later play of that item, and is never removed |
| [SUB-04](#sub-04-a-utf-8-subtitle-file-with-a-cut-last-character-or-one-stray-byte-is-shown-as-mojibake-on-every-line) | KitePlayer | medium | defect | Confirmed | A UTF-8 subtitle file with a cut last character or one stray byte is shown as mojibake on every line |
| [SUB-05](#sub-05-the-built-in-ass-styling-draws-text-the-script-made-transparent-and-a-colour-override-makes-translucent-text-opaque) | KitePlayer | medium | defect | Confirmed | The built-in ASS styling draws text the script made transparent, and a colour override makes translucent text opaque |
| [SUB-06](#sub-06-webvtt-lines-show-lrm-rlm-and-numeric-character-references-as-literal-text) | KitePlayer | medium | defect | Confirmed | WebVTT lines show &lrm;, &rlm; and numeric character references as literal text |
| [SUB-07](#sub-07-after-a-reopen-or-a-new-queue-item-the-first-subtitle-can-show-the-previous-items-text-on-android-software-video-and-on-metal) | KitePlayer | medium | defect | Plausible | After a reopen or a new queue item the first subtitle can show the previous item's text on Android software video and on Metal |
| [SUB-08](#sub-08-every-ass-track-switch-or-new-item-on-android-and-linux-reads-up-to-24-mb-of-system-fonts-from-disk-again) | KitePlayer | medium | design | Plausible | Every ASS track switch or new item on Android and Linux reads up to 24 MB of system fonts from disk again |
| [VID-01](#vid-01-hdr-video-bands-on-every-software-path-because-pq-is-tone-mapped-from-8-bit-values) | Both | medium | defect | Confirmed | HDR video bands on every software path because PQ is tone mapped from 8-bit values |
| [VID-02](#vid-02-a-paused-web-canvas-player-does-not-show-a-new-subtitle-scale-mode-or-zoom-until-playback-resumes) | KitePlayer | medium | defect | Confirmed | A paused web canvas player does not show a new subtitle, scale mode or zoom until playback resumes |
| [VID-03](#vid-03-rotating-an-iphone-while-the-video-is-paused-stretches-the-metal-picture-out-of-shape) | KitePlayer | medium | defect | Plausible | Rotating an iPhone while the video is paused stretches the Metal picture out of shape |
| [VID-04](#vid-04-the-desktop-canvas-allocates-a-new-full-size-image-for-every-frame-and-copies-converts-and-repacks-each-frame-on-the-scheduler-thread) | KitePlayer | medium | risk | Plausible | The desktop canvas allocates a new full-size image for every frame and copies, converts and repacks each frame on the scheduler thread |
| [VID-05](#vid-05-on-apple-every-software-decoded-frame-is-copied-into-new-kotlin-arrays-before-it-is-copied-again-into-textures) | KitePlayer | medium | risk | Plausible | On Apple, every software-decoded frame is copied into new Kotlin arrays before it is copied again into textures |
| [VID-06](#vid-06-the-display-refresh-interval-every-renderer-reports-is-stored-and-never-used-yet-the-interface-says-it-improves-smoothness) | KitePlayer | medium | docs | Confirmed | The display refresh interval every renderer reports is stored and never used, yet the interface says it improves smoothness |
| [VIZ-02](#viz-02-the-flash-capture-test-measures-brightness-on-gamma-encoded-pixels-so-it-under-reads-flashes-in-bright-pictures-and-skips-some-outright) | KitePlayer | medium | test | Confirmed | The flash capture test measures brightness on gamma-encoded pixels, so it under-reads flashes in bright pictures and skips some outright |
| [VIZ-03](#viz-03-the-flash-counter-cannot-see-a-pattern-that-reverses-because-areas-brightening-and-darkening-at-once-cancel-out) | KitePlayer | medium | test | Confirmed | The flash counter cannot see a pattern that reverses, because areas brightening and darkening at once cancel out |
| [VIZ-04](#viz-04-the-rule-against-saturated-red-flashes-is-checked-for-one-drawing-out-of-fifteen-and-that-check-allows-three-a-second) | KitePlayer | medium | test | Confirmed | The rule against saturated red flashes is checked for one drawing out of fifteen, and that check allows three a second |
| [VIZ-05](#viz-05-a-new-song-after-one-with-a-song-map-starts-from-the-old-songs-loudness-reference-so-a-quiet-track-can-look-dim-for-tens-of-seconds) | KitePlayer | medium | defect | Confirmed | A new song after one with a song map starts from the old song's loudness reference, so a quiet track can look dim for tens of seconds |
| [VIZ-06](#viz-06-when-the-first-visualiser-view-of-a-state-leaves-while-a-second-remains-the-director-stops-changing-drawings-and-mutations-stop-reaching-the-screen) | KitePlayer | medium | defect | Confirmed | When the first visualiser view of a state leaves while a second remains, the director stops changing drawings and mutations stop reaching the screen |
| [VIZ-07](#viz-07-the-finishing-pass-flares-on-every-kick-and-tears-the-picture-on-a-drop-outside-the-flash-guard-and-no-flash-fixture-ever-contains-a-drop) | KitePlayer | medium | design | Plausible | The finishing pass flares on every kick and tears the picture on a drop outside the flash guard, and no flash fixture ever contains a drop |
| [VIZ-08](#viz-08-reduced-motion-promises-no-flashes-at-all-but-its-own-test-accepts-up-to-three-a-second) | KitePlayer | medium | docs | Confirmed | Reduced motion promises no flashes at all, but its own test accepts up to three a second |
| [VIZ-09](#viz-09-the-published-flash-results-describe-a-24-drawing-catalogue-while-the-catalogue-now-has-15) | KitePlayer | medium | docs | Confirmed | The published flash results describe a 24-drawing catalogue, while the catalogue now has 15 |
| [WEB-03](#web-03-a-page-whose-worker-binary-is-a-different-version-hangs-for-ever-on-any-call-the-worker-cannot-read) | KitePlayer | medium | defect | Confirmed | A page whose worker binary is a different version hangs for ever on any call the worker cannot read |
| [WEB-04](#web-04-the-worker-players-lip-sync-uses-an-output-latency-read-once-at-start-and-never-again) | KitePlayer | medium | defect | Confirmed | The worker player's lip sync uses an output latency read once at start and never again |
| [WEB-05](#web-05-a-worker-player-given-its-own-libass-address-can-still-fall-back-to-plain-subtitle-styling-because-the-first-ass-track-starts-a-second-load-from-beside-the-worker) | KitePlayer | medium | defect | Confirmed | A worker player given its own libass address can still fall back to plain subtitle styling because the first ASS track starts a second load from beside the worker |
| [WEB-06](#web-06-the-browser-only-web-tests-pass-as-green-when-their-setup-is-missing-so-a-broken-mount-would-hide-every-worker-and-format-test) | KitePlayer | medium | test | Confirmed | The browser-only web tests pass as green when their setup is missing, so a broken mount would hide every worker and format test |
| [WEB-07](#web-07-the-published-web-codec-modules-are-built-with-an-emscripten-no-test-ever-runs) | KiteFFmpeg | medium | test | Confirmed | The published web codec modules are built with an emscripten no test ever runs |
| [WEB-08](#web-08-the-shipped-libass-web-module-is-compiled-and-linked-by-three-different-unpinned-emscripten-versions) | Both | medium | risk | Plausible | The shipped libass web module is compiled and linked by three different unpinned emscripten versions |
| [WEB-09](#web-09-the-worker-player-silently-lacks-playlist-subtitle-line-and-item-detail-calls-that-its-kdoc-and-the-readme-say-it-has) | KitePlayer | medium | docs | Confirmed | The worker player silently lacks playlist, subtitle-line and item-detail calls that its KDoc and the README say it has |
| [API-09](#api-09-java-code-cannot-catch-the-typed-error-that-kiteplayerjavacreate-throws) | KitePlayer | low | defect | Confirmed | Java code cannot catch the typed error that KitePlayerJava.create throws |
| [API-10](#api-10-adding-the-compose-artifacts-moves-an-app-onto-a-pre-release-compose-multiplatform-and-only-a-readme-badge-says-so) | KitePlayer | low | risk | Confirmed | Adding the Compose artifacts moves an app onto a pre-release Compose Multiplatform, and only a README badge says so |
| [API-11](#api-11-three-documentation-pages-send-readers-to-source-folders-that-do-not-exist-in-this-repositorys-layout) | KitePlayer | low | docs | Confirmed | Three documentation pages send readers to source folders that do not exist in this repository's layout |
| [API-12](#api-12-the-contributor-notes-say-the-libass-web-module-comes-inside-the-maven-publication-when-it-is-a-release-page-download) | KitePlayer | low | docs | Confirmed | The contributor notes say the libass web module comes inside the Maven publication when it is a release-page download |
| [API-13](#api-13-the-web-readers-refusal-message-is-garbled-and-does-not-name-the-worker-player-that-solves-the-problem) | KitePlayer | low | docs | Confirmed | The web reader's refusal message is garbled and does not name the worker player that solves the problem |
| [API-14](#api-14-on-a-processor-the-desktop-jar-does-not-carry-it-loads-the-x64-linux-library-and-fails-with-a-confusing-error) | KiteFFmpeg | low | defect | Confirmed | On a processor the desktop jar does not carry, it loads the x64 Linux library and fails with a confusing error |
| [AUD-06](#aud-06-after-a-seek-with-night-mode-on-the-new-position-starts-too-quiet-and-swells-up) | KitePlayer | low | defect | Confirmed | After a seek with night mode on, the new position starts too quiet and swells up |
| [AUD-07](#aud-07-desktop-audio-is-cut-to-16-bits-without-dither-so-quiet-passages-and-low-volumes-carry-quantisation-distortion) | KitePlayer | low | defect | Confirmed | Desktop audio is cut to 16 bits without dither, so quiet passages and low volumes carry quantisation distortion |
| [AUD-08](#aud-08-the-public-audioplaybackdrain-waits-for-ever-when-the-device-stops-pulling) | KitePlayer | low | defect | Confirmed | The public AudioPlayback.drain waits for ever when the device stops pulling |
| [AUD-09](#aud-09-the-public-kdoc-of-audioplaybackvolume-still-says-the-volume-is-applied-by-the-pipeline-on-the-next-converted-buffer) | KitePlayer | low | docs | Confirmed | The public KDoc of AudioPlayback.volume still says the volume is applied by the pipeline on the next converted buffer |
| [AUD-10](#aud-10-the-audio-conversion-kdoc-says-integer-samples-are-scaled-by-their-full-scale-value-while-the-code-divides-by-a-power-of-two) | KitePlayer | low | docs | Confirmed | The audio conversion KDoc says integer samples are scaled by their full-scale value while the code divides by a power of two |
| [BRG-07](#brg-07-interrupting-a-source-while-it-closes-reads-a-freed-ffmpeg-context-although-a-safe-interrupt-already-exists) | Both | low | risk | Confirmed | Interrupting a source while it closes reads a freed FFmpeg context, although a safe interrupt already exists |
| [BRG-08](#brg-08-a-filter-graph-that-fails-to-build-or-flush-leaks-the-decoded-picture-it-was-handed) | KitePlayer | low | defect | Confirmed | A filter graph that fails to build or flush leaks the decoded picture it was handed |
| [BRG-09](#brg-09-the-video-filter-notes-say-timestamps-keep-the-streams-time-base-but-the-deinterlacer-the-player-adds-changes-it) | KitePlayer | low | docs | Confirmed | The video filter notes say timestamps keep the stream's time base, but the deinterlacer the player adds changes it |
| [ENG-08](#eng-08-a-setting-changed-twice-while-the-player-is-busy-can-jump-ahead-of-a-command-sent-between-the-two-changes) | KitePlayer | low | defect | Confirmed | A setting changed twice while the player is busy can jump ahead of a command sent between the two changes |
| [ENG-09](#eng-09-the-seek-bar-can-snap-back-for-a-moment-because-the-player-clears-a-seeks-position-mask-before-reading-the-seek) | KitePlayer | low | defect | Confirmed | The seek bar can snap back for a moment because the player clears a seek's position mask before reading the seek |
| [ENG-10](#eng-10-a-subtitle-file-whose-selection-was-replaced-is-taken-back-out-by-a-side-task-rather-than-by-the-player-loop-and-that-task-can-publish-a-snapshot-after-close) | KitePlayer | low | risk | Plausible | A subtitle file whose selection was replaced is taken back out by a side task rather than by the player loop, and that task can publish a snapshot after close |
| [ENG-11](#eng-11-a-seek-made-while-the-player-waits-for-the-network-answers-where-it-landed-in-the-files-time-instead-of-the-items) | KitePlayer | low | risk | Confirmed | A seek made while the player waits for the network answers where it landed in the file's time instead of the item's |
| [GKF-04](#gkf-04-trimming-a-song-or-audiobook-that-carries-cover-art-reads-the-whole-input-because-the-cover-picture-becomes-the-stream-that-ends-the-cut) | KiteFFmpeg | low | defect | Confirmed | Trimming a song or audiobook that carries cover art reads the whole input, because the cover picture becomes the stream that ends the cut |
| [GKF-05](#gkf-05-a-playlist-read-from-a-byte-source-without-an-opener-can-make-ffmpeg-fetch-plain-http-addresses-and-local-media-files-on-its-own) | KiteFFmpeg | low | risk | Plausible | A playlist read from a byte source without an opener can make FFmpeg fetch plain http addresses and local media files on its own |
| [GKF-06](#gkf-06-closing-a-dolby-vision-composition-while-another-thread-still-composes-a-band-frees-the-pictures-under-it) | KiteFFmpeg | low | risk | Plausible | Closing a Dolby Vision composition while another thread still composes a band frees the pictures under it |
| [GKF-07](#gkf-07-every-ffmpeg-log-line-from-a-decoder-worker-thread-attaches-that-thread-to-the-jvm-and-detaches-it-again) | KiteFFmpeg | low | risk | Plausible | Every FFmpeg log line from a decoder worker thread attaches that thread to the JVM and detaches it again |
| [GKP-05](#gkp-05-an-iphone-app-resumed-after-the-system-suspended-it-can-find-its-player-paused-by-an-interruption-that-never-happened) | KitePlayer | low | defect | Plausible | An iPhone app resumed after the system suspended it can find its player paused by an interruption that never happened |
| [NAT-04](#nat-04-no-published-artifact-or-release-zip-carries-the-dav1d-copyright-notice-that-its-bsd-licence-asks-a-binary-distributor-to-reproduce) | KiteFFmpeg | low | docs | Confirmed | No published artifact or release zip carries the dav1d copyright notice that its BSD licence asks a binary distributor to reproduce |
| [NAT-05](#nat-05-the-libass-chain-release-ships-fribidi-under-the-lgpl-with-no-source-attached-and-no-provenance-unlike-the-ffmpeg-release) | KiteFFmpeg | low | docs | Confirmed | The libass chain release ships FriBidi under the LGPL with no source attached and no provenance, unlike the FFmpeg release |
| [NAT-06](#nat-06-the-ffmpeg-recipe-comments-point-at-desktop-gpl-and-base-profiles-that-no-longer-exist) | KiteFFmpeg | low | docs | Confirmed | The FFmpeg recipe comments point at desktop GPL and base profiles that no longer exist |
| [NET-12](#net-12-dashmediaitemfor-says-the-hls-path-holds-no-segment-whole-but-joined-periods-hold-each-segment-whole-up-to-64-mib) | KitePlayer | low | docs | Confirmed | Dash.mediaItemFor says the HLS path holds no segment whole, but joined Periods hold each segment whole up to 64 MiB |
| [NET-13](#net-13-a-windows-network-share-file-address-written-with-its-server-as-host-opens-the-wrong-path) | KitePlayer | low | defect | Confirmed | A Windows network-share file address written with its server as host opens the wrong path |
| [SUB-09](#sub-09-a-subrip-file-without-blank-lines-between-cues-shows-each-next-index-number-at-the-end-of-the-line-before) | KitePlayer | low | defect | Confirmed | A SubRip file without blank lines between cues shows each next index number at the end of the line before |
| [SUB-10](#sub-10-a-utf-16-subtitle-file-without-a-byte-order-mark-loads-as-no-cues) | KitePlayer | low | defect | Confirmed | A UTF-16 subtitle file without a byte-order mark loads as no cues |
| [VID-07](#vid-07-render-quality-and-360-view-changes-do-not-reach-a-paused-picture-on-the-android-compose-gpu-path) | KitePlayer | low | defect | Confirmed | Render quality and 360 view changes do not reach a paused picture on the Android Compose GPU path |
| [VID-08](#vid-08-turning-the-flash-guard-off-while-paused-leaves-the-picture-dimmed-on-the-web-desktop-and-compose-renderers) | KitePlayer | low | defect | Confirmed | Turning the flash guard off while paused leaves the picture dimmed on the web, desktop and Compose renderers |
| [VID-09](#vid-09-the-web-canvas-renderer-never-reports-that-it-tone-mapped-hdr-so-the-hdr-warning-never-fires-there) | KitePlayer | low | defect | Confirmed | The web canvas renderer never reports that it tone mapped HDR, so the HDR warning never fires there |
| [VID-10](#vid-10-on-iphone-the-metal-path-shows-bt2020-standard-range-video-with-the-wrong-primaries) | KitePlayer | low | defect | Confirmed | On iPhone the Metal path shows BT.2020 standard range video with the wrong primaries |
| [VID-11](#vid-11-the-web-canvas-tone-maps-every-hdr-stream-from-a-fixed-1000-nits-and-kiteffmpegs-documentation-wrongly-says-that-matches-kiteplayer) | Both | low | docs | Confirmed | The web canvas tone maps every HDR stream from a fixed 1000 nits, and KiteFFmpeg's documentation wrongly says that matches KitePlayer |
| [VID-12](#vid-12-the-sample-buffer-renderer-creates-a-new-iosurface-pixel-buffer-for-every-software-frame) | KitePlayer | low | risk | Plausible | The sample buffer renderer creates a new IOSurface pixel buffer for every software frame |
| [VID-13](#vid-13-the-desktop-canvas-renderers-own-documentation-contradicts-itself-about-which-thread-paints) | KitePlayer | low | docs | Confirmed | The desktop canvas renderer's own documentation contradicts itself about which thread paints |
| [VIZ-10](#viz-10-the-palette-swatches-have-no-name-or-selected-state-for-a-screen-reader-and-the-sliders-cannot-be-reached-with-a-keyboard-or-remote) | KitePlayer | low | defect | Confirmed | The palette swatches have no name or selected state for a screen reader, and the sliders cannot be reached with a keyboard or remote |
| [VIZ-11](#viz-11-night-drive-still-runs-two-float-clocks-that-speed-up-after-18-hours-and-stop-after-36-at-144-hz) | KitePlayer | low | defect | Confirmed | Night Drive still runs two Float clocks that speed up after 18 hours and stop after 36 at 144 Hz |
| [VIZ-12](#viz-12-two-clips-of-one-file-with-the-same-length-share-one-song-map-cache-entry) | KitePlayer | low | defect | Confirmed | Two clips of one file with the same length share one song map cache entry |
| [VIZ-13](#viz-13-a-burst-of-decoded-audio-longer-than-a-quarter-second-throws-away-the-analysis-of-the-sound-heard-first-after-a-seek-at-double-speed) | KitePlayer | low | risk | Plausible | A burst of decoded audio longer than a quarter second throws away the analysis of the sound heard first after a seek at double speed |
| [VIZ-14](#viz-14-the-spectrum-timeline-and-event-history-keep-only-two-seconds-so-an-output-with-more-delay-than-that-would-show-an-empty-picture) | KitePlayer | low | risk | Plausible | The spectrum timeline and event history keep only two seconds, so an output with more delay than that would show an empty picture |
| [WEB-10](#web-10-closing-a-worker-player-can-leave-other-calls-that-were-still-waiting-suspended-for-ever) | KitePlayer | low | risk | Plausible | Closing a worker player can leave other calls that were still waiting suspended for ever |
| [WEB-11](#web-11-the-worker-players-sound-still-crosses-into-javascript-one-sample-at-a-time-and-the-kdoc-wrongly-says-a-worker-would-remove-the-copy) | KitePlayer | low | docs | Confirmed | The worker player's sound still crosses into JavaScript one sample at a time, and the KDoc wrongly says a worker would remove the copy |
| [WEB-12](#web-12-the-page-player-starts-the-browsers-audio-only-after-an-await-which-its-own-web-docs-say-a-browser-refuses) | KitePlayer | low | risk | Plausible | The page player starts the browser's audio only after an await, which its own web docs say a browser refuses |
| [WEB-13](#web-13-a-relative-linked-file-or-thumbnail-address-in-a-worker-item-is-never-read-as-a-network-address) | KitePlayer | low | defect | Confirmed | A relative linked-file or thumbnail address in a worker item is never read as a network address |
| [WEB-14](#web-14-the-common-tests-of-kiteplayer-io-never-run-on-wasmjs-and-the-coverage-check-cannot-see-a-common-test-folder-at-all) | KitePlayer | low | test | Confirmed | The common tests of kiteplayer-io never run on wasmJs, and the coverage check cannot see a common test folder at all |
| [WEB-15](#web-15-claudemd-and-the-web-error-messages-still-describe-a-karma-timeout-file-and-describe-the-worker-as-future-work) | KitePlayer | low | docs | Confirmed | CLAUDE.md and the web error messages still describe a karma timeout file and describe the worker as future work |

## Opportunities

These are capability gaps and proposals, not defects, weighed in each entry by user need, reach and cost. API-15 holds the platform capability matrix.

| Id | Repo | Severity | Kind | Verdict | Title |
|---|---|---|---|---|---|
| [API-15](#api-15-what-plays-end-to-end-on-each-platform-today-and-where-the-gaps-are) | Both | medium | opportunity | Confirmed | What plays end to end on each platform today, and where the gaps are |
| [API-16](#api-16-apple-tv-cannot-play-anything-because-the-media-library-has-no-tvos-build) | Both | medium | opportunity | Confirmed | Apple TV cannot play anything, because the media library has no tvOS build |
| [API-17](#api-17-a-compose-web-app-cannot-stream-a-web-address-although-the-media-library-already-has-the-asynchronous-reader-that-would-allow-it) | KitePlayer | medium | opportunity | Confirmed | A Compose web app cannot stream a web address, although the media library already has the asynchronous reader that would allow it |
| [VIZ-15](#viz-15-the-visualiser-ignores-the-systems-reduce-motion-setting-and-starts-with-flashing-and-camera-shake-on) | KitePlayer | medium | opportunity | Confirmed | The visualiser ignores the system's reduce-motion setting and starts with flashing and camera shake on |
| [API-18](#api-18-ad-and-promo-breaks-that-a-stream-announces-in-its-hls-playlist-are-ignored) | KitePlayer | low | opportunity | Confirmed | Ad and promo breaks that a stream announces in its HLS playlist are ignored |
| [API-19](#api-19-files-on-a-home-network-share-cannot-be-played-without-a-web-server-in-front-of-them) | Both | low | opportunity | Confirmed | Files on a home network share cannot be played without a web server in front of them |
| [API-20](#api-20-the-music-visualiser-is-missing-on-the-native-mac-and-on-the-web) | KitePlayer | low | opportunity | Confirmed | The music visualiser is missing on the native Mac and on the web |
| [BRG-10](#brg-10-taking-several-thumbnails-opens-and-tears-down-a-new-video-decoder-for-every-picture) | KiteFFmpeg | low | opportunity | Confirmed | Taking several thumbnails opens and tears down a new video decoder for every picture |
| [NET-14](#net-14-a-dash-presentation-with-video-always-streams-its-highest-bitrate-audio-whatever-the-network-allows) | KitePlayer | low | opportunity | Confirmed | A DASH presentation with video always streams its highest bitrate audio, whatever the network allows |
| [SUB-11](#sub-11-captions-on-cc2-cc3-and-cc4-of-a-broadcast-can-never-be-chosen) | Both | low | opportunity | Confirmed | Captions on CC2, CC3 and CC4 of a broadcast can never be chosen |
| [WEB-16](#web-16-hdr-video-on-the-web-canvas-is-tone-mapped-after-it-has-been-cut-to-8-bits-so-smooth-gradients-can-band) | KiteFFmpeg | low | opportunity | Confirmed | HDR video on the web canvas is tone mapped after it has been cut to 8 bits, so smooth gradients can band |

## What goes upstream to KiteFFmpeg

These need a change in KiteFFmpeg. The same entries are copied, alone, into `audit/Claude_Opus_5.5_AUDIT.md` in the KiteFFmpeg repository, so that tracker can be filled without reading this one.

| Id | Repo | Severity | Kind | Verdict | Title |
|---|---|---|---|---|---|
| [GKF-01](#gkf-01-a-remuxed-iphone-hevc-video-is-retagged-hev1-so-the-mp4-or-mov-it-becomes-no-longer-plays-in-apples-players) | KiteFFmpeg | high | defect | Confirmed | A remuxed iPhone HEVC video is retagged hev1, so the MP4 or MOV it becomes no longer plays in Apple's players |
| [SUB-03](#sub-03-hls-webvtt-subtitles-ignore-x-timestamp-map-so-on-most-packaged-streams-every-line-shows-at-the-wrong-time) | Both | high | defect | Plausible | HLS WebVTT subtitles ignore X-TIMESTAMP-MAP, so on most packaged streams every line shows at the wrong time |
| [API-01](#api-01-a-web-page-that-serves-the-codec-module-of-another-kiteffmpeg-release-is-accepted-and-then-read-at-the-wrong-offsets) | KiteFFmpeg | medium | risk | Confirmed | A web page that serves the codec module of another KiteFFmpeg release is accepted and then read at the wrong offsets |
| [API-04](#api-04-no-artifact-carries-dav1ds-copyright-notice-and-licence-text-which-its-bsd-licence-requires-in-every-binary) | KiteFFmpeg | medium | defect | Confirmed | No artifact carries dav1d's copyright notice and licence text, which its BSD licence requires in every binary |
| [API-15](#api-15-what-plays-end-to-end-on-each-platform-today-and-where-the-gaps-are) | Both | medium | opportunity | Confirmed | What plays end to end on each platform today, and where the gaps are |
| [API-16](#api-16-apple-tv-cannot-play-anything-because-the-media-library-has-no-tvos-build) | Both | medium | opportunity | Confirmed | Apple TV cannot play anything, because the media library has no tvOS build |
| [BRG-02](#brg-02-decoders-are-opened-without-a-packet-time-base-so-the-first-sound-after-encoder-priming-keeps-its-untrimmed-time) | KiteFFmpeg | medium | defect | Plausible | Decoders are opened without a packet time base, so the first sound after encoder priming keeps its untrimmed time |
| [BRG-03](#brg-03-a-packet-sent-to-a-drained-decoder-is-reported-as-decoded-and-silently-thrown-away) | KiteFFmpeg | medium | defect | Confirmed | A packet sent to a drained decoder is reported as decoded and silently thrown away |
| [BRG-04](#brg-04-webvtt-captions-read-through-ffmpeg-lose-their-position-line-and-alignment-settings) | Both | medium | defect | Confirmed | WebVTT captions read through FFmpeg lose their position, line and alignment settings |
| [GKF-02](#gkf-02-a-trimmed-remux-or-copy-transcode-ends-its-sound-early-because-every-stream-stops-at-the-first-picture-packet-past-the-end) | KiteFFmpeg | medium | defect | Confirmed | A trimmed remux or copy transcode ends its sound early, because every stream stops at the first picture packet past the end |
| [GKF-03](#gkf-03-an-mp4-written-to-a-byte-sink-with-the-faststart-option-the-docs-suggest-loses-its-index-and-cannot-be-played-at-all) | KiteFFmpeg | medium | defect | Confirmed | An MP4 written to a byte sink with the faststart option the docs suggest loses its index and cannot be played at all |
| [NAT-01](#nat-01-encrypted-hls-that-cuts-one-file-into-byte-ranges-plays-corrupted-after-the-first-segment-when-a-nested-opener-serves-it) | KiteFFmpeg | medium | defect | Confirmed | Encrypted HLS that cuts one file into byte ranges plays corrupted after the first segment when a nested opener serves it |
| [NAT-02](#nat-02-on-the-web-an-hls-key-or-playlist-given-inline-as-a-data-address-fails-to-open-though-the-opener-documentation-says-ffmpeg-reads-it) | KiteFFmpeg | medium | defect | Confirmed | On the web an HLS key or playlist given inline as a data: address fails to open, though the opener documentation says FFmpeg reads it |
| [NAT-03](#nat-03-no-fuzz-target-reaches-the-aes-128-reader-the-nested-opener-or-the-dolby-vision-and-display-converters-that-kiteffmpeg-wrote-itself) | KiteFFmpeg | medium | test | Confirmed | No fuzz target reaches the AES-128 reader, the nested opener or the Dolby Vision and display converters that KiteFFmpeg wrote itself |
| [VID-01](#vid-01-hdr-video-bands-on-every-software-path-because-pq-is-tone-mapped-from-8-bit-values) | Both | medium | defect | Confirmed | HDR video bands on every software path because PQ is tone mapped from 8-bit values |
| [WEB-07](#web-07-the-published-web-codec-modules-are-built-with-an-emscripten-no-test-ever-runs) | KiteFFmpeg | medium | test | Confirmed | The published web codec modules are built with an emscripten no test ever runs |
| [WEB-08](#web-08-the-shipped-libass-web-module-is-compiled-and-linked-by-three-different-unpinned-emscripten-versions) | Both | medium | risk | Plausible | The shipped libass web module is compiled and linked by three different unpinned emscripten versions |
| [API-14](#api-14-on-a-processor-the-desktop-jar-does-not-carry-it-loads-the-x64-linux-library-and-fails-with-a-confusing-error) | KiteFFmpeg | low | defect | Confirmed | On a processor the desktop jar does not carry, it loads the x64 Linux library and fails with a confusing error |
| [API-19](#api-19-files-on-a-home-network-share-cannot-be-played-without-a-web-server-in-front-of-them) | Both | low | opportunity | Confirmed | Files on a home network share cannot be played without a web server in front of them |
| [BRG-07](#brg-07-interrupting-a-source-while-it-closes-reads-a-freed-ffmpeg-context-although-a-safe-interrupt-already-exists) | Both | low | risk | Confirmed | Interrupting a source while it closes reads a freed FFmpeg context, although a safe interrupt already exists |
| [BRG-10](#brg-10-taking-several-thumbnails-opens-and-tears-down-a-new-video-decoder-for-every-picture) | KiteFFmpeg | low | opportunity | Confirmed | Taking several thumbnails opens and tears down a new video decoder for every picture |
| [GKF-04](#gkf-04-trimming-a-song-or-audiobook-that-carries-cover-art-reads-the-whole-input-because-the-cover-picture-becomes-the-stream-that-ends-the-cut) | KiteFFmpeg | low | defect | Confirmed | Trimming a song or audiobook that carries cover art reads the whole input, because the cover picture becomes the stream that ends the cut |
| [GKF-05](#gkf-05-a-playlist-read-from-a-byte-source-without-an-opener-can-make-ffmpeg-fetch-plain-http-addresses-and-local-media-files-on-its-own) | KiteFFmpeg | low | risk | Plausible | A playlist read from a byte source without an opener can make FFmpeg fetch plain http addresses and local media files on its own |
| [GKF-06](#gkf-06-closing-a-dolby-vision-composition-while-another-thread-still-composes-a-band-frees-the-pictures-under-it) | KiteFFmpeg | low | risk | Plausible | Closing a Dolby Vision composition while another thread still composes a band frees the pictures under it |
| [GKF-07](#gkf-07-every-ffmpeg-log-line-from-a-decoder-worker-thread-attaches-that-thread-to-the-jvm-and-detaches-it-again) | KiteFFmpeg | low | risk | Plausible | Every FFmpeg log line from a decoder worker thread attaches that thread to the JVM and detaches it again |
| [NAT-04](#nat-04-no-published-artifact-or-release-zip-carries-the-dav1d-copyright-notice-that-its-bsd-licence-asks-a-binary-distributor-to-reproduce) | KiteFFmpeg | low | docs | Confirmed | No published artifact or release zip carries the dav1d copyright notice that its BSD licence asks a binary distributor to reproduce |
| [NAT-05](#nat-05-the-libass-chain-release-ships-fribidi-under-the-lgpl-with-no-source-attached-and-no-provenance-unlike-the-ffmpeg-release) | KiteFFmpeg | low | docs | Confirmed | The libass chain release ships FriBidi under the LGPL with no source attached and no provenance, unlike the FFmpeg release |
| [NAT-06](#nat-06-the-ffmpeg-recipe-comments-point-at-desktop-gpl-and-base-profiles-that-no-longer-exist) | KiteFFmpeg | low | docs | Confirmed | The FFmpeg recipe comments point at desktop GPL and base profiles that no longer exist |
| [SUB-11](#sub-11-captions-on-cc2-cc3-and-cc4-of-a-broadcast-can-never-be-chosen) | Both | low | opportunity | Confirmed | Captions on CC2, CC3 and CC4 of a broadcast can never be chosen |
| [VID-11](#vid-11-the-web-canvas-tone-maps-every-hdr-stream-from-a-fixed-1000-nits-and-kiteffmpegs-documentation-wrongly-says-that-matches-kiteplayer) | Both | low | docs | Confirmed | The web canvas tone maps every HDR stream from a fixed 1000 nits, and KiteFFmpeg's documentation wrongly says that matches KitePlayer |
| [WEB-16](#web-16-hdr-video-on-the-web-canvas-is-tone-mapped-after-it-has-been-cut-to-8-bits-so-smooth-gradients-can-band) | KiteFFmpeg | low | opportunity | Confirmed | HDR video on the web canvas is tone mapped after it has been cut to 8 bits, so smooth gradients can band |

## Sections

- [01. Engine core](#01-engine-core-actor-lanes-quiesce-seek-machine-epochs-queues) (11 findings)
- [02. Audio path, clocks and A/V sync](#02-audio-path-clocks-and-av-sync) (10 findings)
- [03. Video output, rendering, colour, HDR, Compose and views](#03-video-output-rendering-colour-hdr-compose-and-views) (13 findings)
- [04. FFmpeg bridge](#04-ffmpeg-bridge-kiteplayer-ffmpeg-and-kiteffmpeg-kotlin-surface) (10 findings)
- [05. KiteFFmpeg native C, build flags, provenance, licensing](#05-kiteffmpeg-native-c-build-flags-provenance-licensing) (6 findings)
- [06. Network, adaptive streaming, IO, segment cache](#06-network-adaptive-streaming-io-segment-cache) (14 findings)
- [07. Subtitles, captions, libass, charset and text](#07-subtitles-captions-libass-charset-and-text) (11 findings)
- [08. Audio visualiser](#08-audio-visualiser-module) (15 findings)
- [09. Web target, web worker, platform parity, tests and CI](#09-web-target-web-worker-platform-parity-tests-and-ci) (16 findings)
- [10. Public API, distribution, documentation truth, capability matrix](#10-public-api-distribution-documentation-truth-capability-matrix) (20 findings)
- [11. KitePlayer areas the first round could not reach](#11-kiteplayer-areas-the-first-round-could-not-reach) (5 findings)
- [12. KiteFFmpeg areas the first round could not reach](#12-kiteffmpeg-areas-the-first-round-could-not-reach) (7 findings)

## 01. Engine core: actor, lanes, quiesce, seek machine, epochs, queues

This lane read the actor loop and its mailbox in `PlaybackCore.kt` (enqueue and the latest-value slots, drainCommands, the preemption checks, the error branches of runLoop), the legality table and execute(), the seek machine (queueSeek, handleQueuedSeek, SeekRequest.merge, the seek mask), the release paths (dropPending, takeRingBack, teardownSession, releaseWithinDeadline, releaseSession, runClose and the close finalizer), the variant, programme and edition selections, frame capture and frame step, the external subtitle adoption, and the renderer event collector. Every finding below was re-traced by hand by a second reader who tried to refute it, and the line numbers were checked against the tree as it stands. Nothing was built or run, so where a scenario needs a timing window that is said plainly. Not covered in depth: the video scheduler loop and frame ownership inside it, the gapless handoff state machine beyond its release calls, subtitle decoding on the actor, ordered-edition run switching, and the single-threaded scheduling of the web worker.

### ENG-01. Pressing play at the end right after a seek bar drag restarts the item from the beginning instead of from the dragged position

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | medium | Confirmed | high | none |

**Second check:** Upheld at medium by the second reader, lines confirmed. Narrowed: it needs the Play command to be handled before the queued seek starts. A Play that arrives while the seek runs is held and applied after the landing, when the status has already left Ended. So calling `requestSeek` and then `play()` back to back from code, as when restoring a saved position, is the likely trigger, while a human tap after a drag hits it only while a slow seek is still queued.

**Where:** `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:2987-3000`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:9321-9328`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:9272-9318`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:11961-11970`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:12059-12060`

**What happens:** When the media has ended, the player stays in the Ended state and still accepts seeks, which keep it Ended until they land. Pressing play at the end is a restart, and the restart is written straight into the one slot that holds the waiting seek. It does not merge with or keep a seek that is already waiting there, unlike every other seek the engine makes for itself. So a seek the viewer asked for a moment before pressing play is thrown away, and anyone awaiting that seek is told it applied, at the start of the item.

**Evidence:**
```kotlin
// PlaybackCore.kt:2995-2997 (Play)
if (status == PlaybackStatus.Ended && session?.source?.seekable == true) {
    restartFrom(Pts.Zero)
}
```
```kotlin
// PlaybackCore.kt:9321-9328
private fun restartFrom(target: Pts) {
    endOfStream.reset()
    ...
    playRequested = true
    setStatus(PlaybackStatus.Buffering)
    pendingSeek = SeekRequest(SeekTarget.Absolute(target), SeekMode.Precise)
}
```
```kotlin
// PlaybackCore.kt:11961-11969 (queueSeek, for contrast)
if (pendingSeek != null) { ... resolveSeekReplies(SeekResult.Superseded(by)) }
pendingSeek = pendingSeek?.merge(request) ?: request
reply?.let { pendingSeekReplies += it }
```
```kotlin
// PlaybackCore.kt:12059-12060
// Ended keeps Ended until the landing below proves the position moved.
if (playRequested && status != PlaybackStatus.Ended) setStatus(PlaybackStatus.Buffering)
```
The path: `SeekLater` or `Seek` runs `queueSeek`, which leaves the status at Ended and puts the request in `pendingSeek`. A `Play` in the same drain then sees Ended and calls `restartFrom`, which overwrites `pendingSeek`. The suspended caller's reply is still in `pendingSeekReplies`, so `handleQueuedSeek` later answers it `Applied` with the start position. `handleLoop` (pass position after `handleEof`, before `handleQueuedSeek`) calls `restartFrom` the same way for a repeat and for an A-B loop wrap.

**Failure scenario:** A film has ended. The viewer drags the seek bar to 40:00 (one `requestSeek`) and taps play straight away, or the app restores a saved position with `requestSeek` and then calls `play()`. Both commands land in one drain. Playback starts from 0:00, and a suspended `seek(40.minutes)` returns normally as if it landed. With `LoopMode.One` the same overwrite happens without any tap if a drag lands in the pass where the item is Ended.

**Suggested fix:** In `restartFrom`, keep a request that is already waiting (`pendingSeek = pendingSeek ?: SeekRequest(...)`), or in the Play branch skip the restart when `pendingSeek != null`, because the user's seek already leaves Ended when it lands.

**How to check:** Write a harness test: open, play to the end, then call `seekLater(40 s)` and `play()` back to back without running the harness between them, run, and assert the position is near 40 s rather than near zero.

### ENG-02. Closing the player, seeking or changing tracks near the end of a queue item releases the preloaded next item on the player loop with no time limit

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | medium | Confirmed | medium | none |

**Where:** `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:2915`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:11346-11381`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:11393-11400`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:11414-11428`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:11157`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:11246`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:12502-12530`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:12662-12679`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:12889-12927`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:13000-13105`

**What happens:** An earlier fix moved the release of the playing item off the player loop and gave it a deadline, because a release that never returned had frozen the player and made a later close wait for ever. The preloaded next item of a queue did not get that treatment. Almost every command throws the preload away, and the throw-away calls the full release inline on the player loop, with no deadline: interrupt the source, stop each worker one after another with up to two seconds each, join every job, close each decoder and the backend. The sharpest case is close itself: `Close` is in the list of commands that drop the preload, and `execute()` drops it before `runClose` starts, so the careful close path that was written to release the preload inside its own deadline never sees it.

**Evidence:**
```kotlin
// PlaybackCore.kt:2915 (execute, before the command runs)
if (pendingNext != null && dropsPreload(command)) dropPending(null)
```
```kotlin
// PlaybackCore.kt:11417-11422
is CoreCommand.Open, is CoreCommand.OpenQueue, is CoreCommand.QueuePrevious,
is CoreCommand.EditQueue, is CoreCommand.SetShuffle, is CoreCommand.RestoreQueueOrder,
is CoreCommand.Seek, is CoreCommand.SeekLater, is CoreCommand.Close,
is CoreCommand.SelectTrack, is CoreCommand.SelectSecondarySubtitle,
is CoreCommand.AttachRenderer, is CoreCommand.DetachRenderer, is CoreCommand.StepFrame,
```
```kotlin
// PlaybackCore.kt:11365-11380 (dropPending)
prepared == null && next.job.isCompleted -> if (!next.job.isCancelled) {
    next.job.await().getOrNull()?.session?.let { releaseSession(it) }
}
...
next.handedOff && active != null -> takeRingBack(active, prepared.session, ...)
else -> { ...; releaseSession(prepared.session) }
```
```kotlin
// PlaybackCore.kt:12512-12515 (runClose, which never gets the preload)
// Nothing here may block before the release below starts, because the close deadline is
// the release's: finishing a recording, releasing the preload and waiting for preload
// builds still unwinding all run inside it. ... (#473).
val preload = takePreloadForClose()
```
```kotlin
// PlaybackCore.kt:12896-12899 (releaseWithinDeadline KDoc)
// Every other teardown, a stop's, a failed worker's or a rebuild's,
// used to release inline on the actor with no deadline, so a device stop that never returned
// kept the actor from its mailbox and a later close waited for ever.
```
`releaseSession` runs entirely under `NonCancellable` (line 13005) and quiesces each worker in series (`session.workers.forEach { worker -> runCatching { worker.quiesce(QUIESCE_DEADLINE) } }`, line 13056) and then joins every job (line 13058). `takeRingBack` (line 11400), the handoff swap (line 11157) and the run pass (line 11246) call `releaseSession` inline the same way. `teardownSession` also calls `dropPending(null)` before its bounded release (line 12891).

**Failure scenario:** A queue of network items with gapless preload. In the last seconds of item 1, item 2's session is open and its decoder or source sits in a native call that does not return promptly. The user calls `close()` (or seeks back, or picks another audio track). The actor releases item 2 inline: two seconds per worker that will not park, then a join that waits on the stuck native call. For that whole time position updates, play, pause and stop do not move, and in the close case `closeAndAwait` does not start its own deadline, so the "close reports a compromised runtime instead of hanging" promise of the earlier fix does not hold whenever a preload exists.

**Suggested fix:** Remove `Close` from `dropsPreload` so `runClose` takes the preload through `takePreloadForClose` as it was written to. For the other commands, detach the preload (and the session a handoff retires) from the actor and release it through `releaseWithinDeadline` or one release lane job, keeping the ring and device ordering that `takeRingBack` needs.

**How to check:** Read `execute()` at line 2915 next to `dropsPreload` at line 11414 and `runClose` at line 12515; `takePreloadForClose` can only ever see a preload if `Close` were missing from that list. A harness test with a queue whose second item's decoder blocks in `close()` for ten seconds, then `closeAndAwait()` with a one second deadline, should report the compromised runtime after one second; today it should take ten.

### ENG-03. Taking a screenshot of a paused player, or turning the picture back on, cancels the seek the viewer just asked for

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | medium | Confirmed | high | none |

**Where:** `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:11551-11578`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:3227-3259`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/SeekRequest.kt:44-52`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:11961-11969`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:13618-13636`

**What happens:** A paused capture works by queuing a precise seek to the current position, so one frame passes the capture gate. That position is read from the clock, not from the seek target the viewer just asked for. Seek requests merge by taking the newer target, so a seek that is still waiting is replaced by a seek back to where the player already was, and its waiting callers are answered as superseded. Turning the picture back on with `setVideoEnabled(true)` queues the same kind of seek and has the same effect. The frame-step-back path already handles this case by reading the masked target first, which shows the intended rule.

**Evidence:**
```kotlin
// PlaybackCore.kt:11575-11577 (requestCapture)
if (!playRequested) {
    queueSeek(SeekRequest(SeekTarget.Absolute(currentPosition()), SeekMode.Precise), null)
}
```
```kotlin
// PlaybackCore.kt:3250-3255 (SetVideoEnabled, coming back on)
if (session?.source?.seekable == true && status.isActive) {
    queueSeek(
        SeekRequest(SeekTarget.Absolute(currentPosition()), SeekMode.Precise),
        CompletableDeferred(),
    )
}
```
```kotlin
// SeekRequest.kt:44-49 (merge)
val mergedTarget = when {
    next.target is SeekTarget.Relative && target is SeekTarget.Relative -> ...
    else -> next.target
}
```
```kotlin
// PlaybackCore.kt:11496 (stepOneFrameBack does the right thing)
val waitingTargetUs = maskedSeekTargetMicros.value.takeIf { pendingSeek != null && it != NO_SEEK_MASK }
```
`currentPosition()` (line 13618) reads the audio or video clock and never the seek mask. `queueSeek` answers the replaced request's callers `Superseded`, and `KitePlayer.seek` treats a superseded result as success.

**Failure scenario:** A thumbnail picker keeps the player paused. The user scrubs to 12:30 with `requestSeek` and the app at once calls `captureFrame()`. The waiting 12:30 seek, still held by the 300 ms coalescing rule or sitting in the same drain, is replaced by a precise seek to the old position, say 3:00. The player stays at 3:00, the thumbnail is the 3:00 frame, and a suspended `seek(12:30)` returns normally. Likewise an app that restores a position with `requestSeek` when it comes back to the foreground and then calls `setVideoEnabled(true)` stays where it was.

**Suggested fix:** In both places, queue the re-present seek only when `pendingSeek == null`. When a seek is waiting, let its own landing present the frame through the capture gate (or, for the picture coming back, let it supply the keyframe the decoder needs).

**How to check:** A harness test: open, pause, `seekLater(60 s)`, then `captureFrame()` in a child coroutine without running the harness between them; run, and assert the captured frame's time and the position are near 60 s.

### ENG-04. A screenshot asked for while playing waits until playback resumes if the player is paused first, and never returns while the picture is turned off

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | medium | Confirmed | high | none |

**Where:** `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:11551-11578`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/VideoPlayback.kt:476-486`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:7845-7858`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:1989-2010`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:12875-12878`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/KitePlayer.kt:1207-1230`

**What happens:** While playing, a capture only arms a one-shot slot that the next presented frame fills. Only the presenting code fills it and only a session detach fails it. Pausing parks the schedule but leaves the slot armed, and the capture never switches to the paused path, so the caller waits until playback resumes. With the picture turned off, the video lane throws every packet away, so no frame is ever presented, playing or paused, and the capture checks do not refuse that state. The suspending call has no deadline of its own.

**Evidence:**
```kotlin
// PlaybackCore.kt:11572-11577 (requestCapture)
video.captureRequest.getAndSet(reply)?.completeExceptionally(...)
if (!playRequested) {
    queueSeek(...)
}
```
```kotlin
// VideoPlayback.kt:478-486 (present, the only place the slot is filled)
val frame = queue.advance() ?: return IDLE_WAIT
...
captureRequest.getAndSet(null)?.let { request -> ... }
```
```kotlin
// PlaybackCore.kt:7845-7849 (applyPause leaves the slot alone)
private suspend fun applyPause() {
    val session = session ?: return
    session.schedulerMode.value = SCHEDULER_IDLE
```
```kotlin
// PlaybackCore.kt:1993 (captureFrame waits with no deadline)
val frame = reply.await()
```
A repository search finds `captureRequest` written only at line 11572, cleared by `WithdrawCapture` at 2986 and by `detachSession` at 12876, and read in `present()`. `requestCapture` rejects no session, no selected video track, Ended and a paused unseekable source, but not `videoParked`. `setVideoEnabled(false)` keeps `videoStream` and `video` and only sets `videoParked` (line 3230).

**Failure scenario:** The viewer taps a screenshot button and then pause within one frame interval (two commands in one drain is enough). The capture stays suspended for as long as the player is paused, which a UI shows as a spinner that never ends. Or an app in an audio-only background mode calls `setVideoEnabled(false)`, then later `captureFrame()`: the call hangs until stop, close, a new open or a track change. The KDoc lists only those endings, and does not mention that a pause stalls a capture.

**Suggested fix:** When the actor parks the schedule (pause, and Ended) with a capture armed, convert it to the paused path by queuing the precise re-present seek (subject to ENG-03), or fail it with a clear message. Refuse `captureFrame` while `videoParked` is set. Give the capture a deadline, as frame step has.

**How to check:** A harness test: open, play, then call `captureFrame()` in a child coroutine and `pause()` without running between them; run for two seconds and assert the capture completed. A second test with `setVideoEnabled(false)` then `captureFrame()`.

### ENG-05. Choosing the current video quality again does not cancel a different quality change that is still on its way

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | medium | Confirmed | high | none |

**Where:** `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:3029-3041`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:3052-3062`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:6962-6969`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:7173-7209`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/KitePlayer.kt:1632-1660`

**What happens:** A variant choice is treated as a no-op when it names the variant the item already records. That record is only updated when an in-place move or a reopen finishes, so while a different choice is still on its way, a call that names the current variant (or null, for automatic) answers at once and leaves the earlier request waiting. The earlier request then applies, so the last call does not win. The edition choice beside it guards against exactly this with `pendingEdition == null`; the variant and programme choices do not. The public KDoc promises that "a later call ended the change first".

**Evidence:**
```kotlin
// PlaybackCore.kt:3032-3040 (SelectVariant)
val asked = media?.demux?.variant
if (command.index == asked && (command.index == null || command.index == tracks.selectedVariant)) {
    command.reply.complete(Unit)
} else {
    pendingVariant?.reply?.completeExceptionally(...)
    pendingVariant = VariantRequest(command.index, command.reply)
}
```
```kotlin
// PlaybackCore.kt:3043 (SelectEdition, for contrast)
if (command.index == session?.edition && pendingEdition == null) {
```
```kotlin
// PlaybackCore.kt:7194-7205 (finishVariantInPlace)
if (moved) {
    tracks = tracks.copy(selectedVariant = from.source.selectedVariant)
    media = media?.let { it.copy(demux = it.demux.copy(variant = request.index)) }
    ...
}
if (pendingVariant !== request) return
...
pendingVariant = null
request.reply.complete(Unit)
```
The in-place move runs off the actor (`startVariantInPlace`, line 7173, which may read a playlist over the network) and reports back as a command, so the window lasts as long as that network read.

**Failure scenario:** An HLS stream plays in automatic mode (`demux.variant` is null, 720p playing). The user picks 1080p, which starts an in-place move. Before it answers, the user picks Auto again. `selectVariant(null)` matches `asked == null` and returns at once. The 1080p move then finishes, `demux.variant` becomes the 1080p index, and automatic adaptation stays off for the rest of the item. The same happens with a hand-picked 720p: pick 1080p, then 720p again while the move is in flight. `selectProgram` has the same shape, though only inside one drain, since a programme change always rebuilds in the same pass.

**Suggested fix:** Treat the call as a no-op only when no different request is waiting (`pendingVariant == null`), as `SelectEdition` does; otherwise replace the waiting request with one for the current variant. Apply the same to `SelectProgram`.

**How to check:** A harness test with a fake source whose `switchVariant` suspends until released: `selectVariant(1)` in one child, then `selectVariant(null)`, release the switch, run, and assert `media.demux.variant` is null.

### ENG-06. An unexpected failure between close() and the player loop reaching it leaves the whole session running, with sound still playing

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | risk | medium | Plausible | medium | none |

**Where:** `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:2569-2614`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:2420-2435`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:12697-12710`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:12723-12770`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:216-218`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:16404-16410`

**What happens:** The loop has two ways to die. If it is cancelled, it tears the session down first. If an ordinary exception escapes a pass after close was requested, it only settles the waiting commands and rethrows: `runClose` never runs, nothing calls `teardownSession`, and the close finalizer reports a compromised runtime and closes no session. The workers live in a supervisor scope that close never cancels, so they keep running, and the sound device is never stopped.

**Evidence:**
```kotlin
// PlaybackCore.kt:2591-2604
withContext(NonCancellable) { teardownSession() }
throw cancellation
} catch (failure: Throwable) {
    if (closedNow.value) {
        settleOutstandingForClose()
        throw failure
    }
    ...
    teardownSession()
    fail(error)
```
```kotlin
// PlaybackCore.kt:216-218
private val scope = CoroutineScope(
    dispatchers.session + SupervisorJob(parent) + CoroutineName("kiteplayer-session"),
)
```
`closedNow` is set on the caller's thread in `requestClose` (line 2421) before the `Close` command is queued, so the window is the rest of the pass in flight plus any commands queued ahead of `Close`. `launchCloseFinalizer` only closes dispatchers (skipped when a release is wedged) and publishes the terminal snapshot. `scope.cancel` appears nowhere in the file, and the workers are launched into `scope` (for example lines 13717-13726).

**Failure scenario:** The app calls `close()` while a pass is running. A handler later in that pass, or a command queued ahead of `Close`, throws an unexpected exception that the ordinary branch would have turned into an Internal failure plus teardown. `closeAndAwait` reports a compromised runtime. The demux, decode and feed lanes keep running, the device keeps playing, and the decoders and FFmpeg contexts are never freed. This needs a second bug to throw in that window, which is why it is a risk rather than a defect, but the cost when it happens is a player that cannot be stopped short of ending the process.

**Suggested fix:** In the `closedNow` failure branch, detach the session and the preload as `runClose` does and run the same bounded release (`awaitRelease`) before rethrowing, so the report stays honest and the graph is still released.

**How to check:** A harness test with a renderer or subtitle decoder that throws an `IllegalStateException` from a pass handler, triggered in the same drain as `close()`; assert the fake sink saw `stop()` and the fake decoders saw `close()`. What would prove it fully is that test failing today.

### ENG-07. The display refresh rate a renderer reports is stored and never used, although the interface promises smoother pacing with it

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | docs | medium | Confirmed | high | none |

**Same root as:** VID-06. Post these as one issue.

**Where:** `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/VideoPlayback.kt:123-129`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/spi/VideoRenderer.kt:22-23`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:1587-1588`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:3483`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:3792-3795`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:4360`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:5876`, `KitePlayer/kiteplayer-core/test/io/github/yuroyami/kiteplayer/VsyncAwarenessTest.kt:16-57`

**What happens:** The engine copies each renderer's refresh interval into the video schedule at attach, at open and on every refresh change event, and nothing ever reads it except a getter that exists for one test. The schedule paces frames without it. So the interface's promise that a renderer reporting its refresh rate gets smoother pacing is untrue today, and the test named for this checks only that the number is stored.

**Evidence:**
```kotlin
// VideoPlayback.kt:123-129
/**
 * The display's refresh interval, seeded at renderer attach and moved by
 * [...RendererEvent.VsyncChanged]. Advisory, exactly as the
 * SPI says: null costs smoothness on a high-refresh display and nothing else. Held here
 * because the schedule is the one consumer pacing against a display.
 */
internal var vsyncIntervalNanos: Long? = null
```
```kotlin
// spi/VideoRenderer.kt:22-23
 * 4. [vsyncIntervalNanos] is advisory. Returning null costs smoothness on a high refresh display
 *    and nothing else.
```
```kotlin
// PlaybackCore.kt:1588 (the only read)
val videoScheduleVsyncNanos: Long? get() = session?.video?.vsyncIntervalNanos
```
A search of the whole tree for `vsyncIntervalNanos` outside renderer implementations and their tests finds only the four assignments listed above and this getter. `VsyncAwarenessTest` says in its header that "a 120 Hz phone and a ProMotion Mac were paced no better than a display nobody asked", then asserts only the stored number.

**Failure scenario:** A renderer author implements `vsyncIntervalNanos()` and emits `VsyncChanged`, as the interface asks, expecting cadence to follow 60 Hz or 120 Hz refreshes. Nothing changes, and a 24 fps film on a 60 Hz panel keeps whatever cadence the schedule produces, while the suite reports the vsync test green.

**Suggested fix:** Either make the schedule use the interval (choose repeat and drop by refresh phase, or snap frame targets to refresh boundaries) and test the cadence it produces, or correct the interface, `VideoPlayback` and collector comments and rename the test to say the value is recorded for later use.

**How to check:** `rg -n "vsyncIntervalNanos" KitePlayer/kiteplayer-core/src` and look for any read inside `VideoPlayback.kt`.

### ENG-08. A setting changed twice while the player is busy can jump ahead of a command sent between the two changes

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | low | Confirmed | medium | none |

**Where:** `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:2475-2513`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:16777-16782`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:7760-7792`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:11588-11600`

**What happens:** For settings such as volume or subtitle delay, a newer call takes the waiting call's place, and the newest value applies at the mailbox position of the first call. The rule for which settings may do this only looks at what the setting's own handler reads. It ignores commands of other kinds that read or write the same value. Two such pairs exist: stepping the subtitle delay to a line reads and writes the subtitle delay, and "next in queue" reads the loop mode.

**Evidence:**
```kotlin
// PlaybackCore.kt:16777-16781
/**
 * A setting whose newest value is all that applies, so a newer one of its kind takes an older one's
 * place while both wait in the mailbox (#483). Only a setting with a plain Unit reply, whose handler
 * sets a value and reads nothing a command of another kind changes, is one.
 */
```
```kotlin
// PlaybackCore.kt:7766 and 7787 (answerSubtitleLine)
val delayUs = subtitleDelay.inWholeMicroseconds
...
subtitleDelay = delay
```
```kotlin
// PlaybackCore.kt:11596-11598 (neighbourInOrder)
loop == LoopMode.All ->
    queueOrder[((target % queueOrder.size) + queueOrder.size) % queueOrder.size]
else -> null
```
`SetSubtitleDelay` and `SetLoop` are both `LatestValueSetting` (lines 16929 and 16905).

**Failure scenario:** While an open or a rebuild keeps the actor busy, the app sends `setSubtitleDelay(1 s)`, `stepSubtitleDelay(+1)`, `setSubtitleDelay(5 s)`. They apply as 5 s, then the step, so the final delay is the step's value instead of the 5 s set last. Likewise `setLoop(All)`, `queueNext()` on the last item, `setLoop(Off)` applies Off first and the next call finds no next item instead of wrapping. It needs all three calls inside one busy stretch, hence low.

**Suggested fix:** When a command that reads a setting is enqueued, apply any waiting slot of that kind before it (flush the subtitle delay slot ahead of `SubtitleLine`, the loop slot ahead of `QueueNext`), or narrow the documented rule and drop those two kinds from coalescing.

**How to check:** A harness test that holds the actor in a slow open, sends the three subtitle calls, releases it and asserts the delay is 5 s.

### ENG-09. The seek bar can snap back for a moment because the player clears a seek's position mask before reading the seek

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | low | Confirmed | medium | none |

**Where:** `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:2072-2104`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:2341-2344`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:7865-7874`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:1655-1676`

**What happens:** `seek()` and `seekLater()` write the position mask on the caller's thread before the command is queued, so `position()` answers the target at once. The pass clears the mask in `handlePlaybackTime` whenever no seek is waiting. That handler runs eight handlers after `drainCommands`, several of which suspend. A request that lands after the drain but before that handler in the same pass has its mask wiped and the published position reset to the clock, until the next pass drains it. The handler's comment assumes the drain always comes first, which holds only for requests already in the mailbox when the pass started.

**Evidence:**
```kotlin
// PlaybackCore.kt:2101-2103 (seekLater)
maskedSeekTargetMicros.value = maskFor(to)
val target = Pts(fileTime(to.micros))
post(CoreCommand.SeekLater(...))
```
```kotlin
// PlaybackCore.kt:7866-7872 (handlePlaybackTime)
// The mask lives exactly as long as a request is queued or held. drainCommands runs before
// this handler, so a newly accepted request re-arms the mask before this pass can clear it,
if (pendingSeek == null) {
    maskedSeekTargetMicros.value = NO_SEEK_MASK
    publishedPositionMicros.value = currentPosition().micros
}
```

**Failure scenario:** A seek bar polls `position()` every frame. The first `requestSeek` of a drag lands while the actor is inside `handleTrackChanges` or a suspending handler before `handlePlaybackTime`. The mask is wiped, the next poll reads the old position, and the thumb jumps back for a frame before the next pass re-arms it, the very artifact the mask exists to prevent (#255). It only affects a request that arrives when no seek is waiting, so the start of a drag or a single tap.

**Suggested fix:** Clear the mask with a compare-and-set against the value the actor last saw accepted (or a sequence number the caller bumps), so a mask written after the drain survives the clear.

**How to check:** A unit-level test that sets `onHandlerRun` to call `seekLater` when `handleTrackChanges` runs, then reads `position()` after the pass: it answers the old clock instead of the target.

### ENG-10. A subtitle file whose selection was replaced is taken back out by a side task rather than by the player loop, and that task can publish a snapshot after close

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | risk | low | Plausible | medium | none |

**Where:** `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:893-930`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:1036-1088`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:6410-6420`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:12697-12701`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:12723-12765`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlatformDispatchers.kt:54`

**What happens:** When an added subtitle file has to go through the full selection path, a separate task waits for that selection and, if it did not apply, removes the file's row: it rewrites the track table and the subtitle selection, clears the session's cues, refreshes typesetting and publishes a snapshot. Its comment argues that running on the actor's single-threaded lane gives it the actor's confinement. That keeps it free of data races, but it runs in the gaps where the actor suspends inside a handler, outside any command or handler, which the project's rule forbids. At close, `settleOutstandingForClose` completes the selection after the release, and the actor then returns, so the removal task runs after the actor has ended, on the session lane, at the same time as the close finalizer publishes the terminal snapshot from another thread.

**Evidence:**
```kotlin
// PlaybackCore.kt:1038-1040 (KDoc)
// Launched on the session dispatcher, which is the actor's own lane, so the rollback below
// touches actor state under exactly the confinement every handler runs in.
```
```kotlin
// PlaybackCore.kt:1077-1087
private suspend fun withdrawExternalSubtitle(id: TrackId) {
    externalSubtitleTracks = externalSubtitleTracks.filterNot { it.id == id }
    tracks = tracks.copy(all = tracks.all.filterNot { it.id == id })
    ...
    publishSnapshot()
}
```
The session lane is `blocking.limitedParallelism(1)` (PlatformDispatchers.kt:54), so the task is serial with the actor but interleaves at every actor suspension point. CLAUDE.md: "All session mutation happens on the actor, in a command execution or a pass handler." The second reader traced the in-place subtitle change and the rebuild and found that both re-read `tracks` after their suspensions, so no stale write-back was demonstrated; what remains is the rule breach and the race with the finalizer.

**Failure scenario:** A container subtitle shows, the app calls `addExternalSubtitle(file)`, and `close()` lands while that file's selection still waits. After the release, the selection is discarded, the actor ends, and the removal task publishes a snapshot built from fields the finalizer is rewriting on a `Dispatchers.Default` thread. The last snapshot an app sees can be torn between the two. This needs close to arrive inside a short window, hence low.

**Suggested fix:** Send the outcome back as a command (as `startVariantInPlace` does with `commands.trySend`) and remove the row inside `execute()`, and cancel this task, the variant task and the thumbnail task at close so no engine coroutine outlives the actor.

**How to check:** Read `awaitSubtitleAdd` and then the end of `runClose`, where `settleOutstandingForClose` runs after `awaitRelease`; nothing cancels the launched task.

### ENG-11. A seek made while the player waits for the network answers where it landed in the file's time instead of the item's

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | risk | low | Confirmed | high | none |

**Where:** `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:6179-6208`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:11991-11996`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:16639`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/KitePlayer.kt:209-212`

**What happens:** Every seek answer normally goes through `resolveSeekReplies`, which converts the landing time into the item's own time (#456). The seek that runs while the player waits for the network answers directly with a file time, origin included, in both of its branches. Today no public caller reads that landing time: `SeekResult` is internal and `KitePlayer.seek` only checks for a rejection, so nothing a user sees is wrong yet. It is a trap for the next internal caller, for example a frame step or a test that compares landings.

**Evidence:**
```kotlin
// PlaybackCore.kt:11993 (resolveSeekReplies)
val reported = if (result is SeekResult.Applied) SeekResult.Applied(Pts(itemTime(result.landedAt.micros))) else result
```
```kotlin
// PlaybackCore.kt:6202-6207 (seekWhileReconnecting)
val at = target.coerceAtLeast(origin).let { ... }
...
reply?.complete(SeekResult.Applied(Pts(at)))
```
The ordered-edition branch (line 6193) does the same with `at = timeline.runs[run].fileUsAt(itemUs)`.

**Failure scenario:** An item clipped to start at 60 s loses its network. An internal caller seeks to 10 s and reads 70 s as the landing, while the same call with the network up reads 10 s. Nothing user-facing reads it today, which is why this is a low risk rather than a defect.

**Suggested fix:** Answer with `SeekResult.Applied(Pts(itemTime(at)))` in both branches, or add the reply to `pendingSeekReplies` and call `resolveSeekReplies`.

**How to check:** Read lines 6193 and 6207 next to line 11993.

#### Rejected during verification

None. Two findings were downgraded: ENG-10 from a medium defect to a low risk, because no stale write-back could be shown and the remaining harm needs close inside a short window; and ENG-11 from a low defect to a low risk, because `SeekResult` is internal and no public caller reads the landing time. The variant finding also gained the KDoc contradiction, ENG-03 gained `setVideoEnabled(true)` as a second trigger, and ENG-02 gained the close path, which drops the preload inline before `runClose` can take it under its deadline.


## 02. Audio path, clocks and A/V sync

This lane read the audio half of the engine end to end: the public `AudioPlayback` class (open, submit, anchoring, the pause freeze, the resume re-anchor and floor, fade, flush, drain, the gain push), the media clock, both audio rings (the Kotlin one and the C one in `kiteplayer-rt`), the channel mixer against FFmpeg's own `swr_build_matrix` rules (read from the FFmpeg 9.0.2 source), the night mode stage, the Android AudioTrack sink's deadline and timestamp logic, the desktop `javax.sound` sink's packing, and the audio sample conversion in the FFmpeg adapter. Every finding below was re-read by a second pass that tried to refute it, and line numbers were corrected where they had drifted. Ring memory ordering, the resampler's phase arithmetic, the sync thresholds and the Android lock order were checked and found sound. This lane did not cover the tempo stage's internals, the CoreAudio device and route watch, the iOS session latency path, the output reopen in progress under #563, or the libass and visualiser taps. No build or test was run; every claim comes from reading the code, and the two findings marked Plausible say what would prove them.

### AUD-01. A music app that builds AudioPlayback on its own gets a clock on a different time base from every shipped audio sink

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | medium (was high) | Confirmed | high | none |

**Second check:** Downgraded from high to medium. The second reader confirmed the two time bases and the wrong position reading, but it only bites a caller who builds `AudioPlayback` by hand and leaves its clock at the default, the audio itself plays correctly, and passing the sink's clock fixes it. The fix belongs on `AudioPlayback` (and the same default at `VideoPlayback.kt:47`), not on the sinks. The fade wait is capped and applies only to sinks that cut sound on stop.

**Where:** `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/AudioPlayback.kt:34-35`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/AudioPlayback.kt:73-75`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/AudioPlayback.kt:729-743`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/AudioPlayback.kt:858-860`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/MonotonicClock.kt:26-35`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/MediaClock.kt:87-131`, `KitePlayer/kiteplayer-output/src@jvm/io/github/yuroyami/kiteplayer/output/DesktopAudioSink.kt:54-57`, `KitePlayer/kiteplayer-output/src@jvm/io/github/yuroyami/kiteplayer/output/DesktopMonotonicClock.kt:21-23`, `KitePlayer/kiteplayer-output/src@android/io/github/yuroyami/kiteplayer/output/AndroidMonotonicClock.kt:21-23`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:6476-6478`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/VideoPlayback.kt:45-47`

**What happens:** `AudioPlayback` is public, and its own documentation says that on its own it is a complete audio player and that a music application needs exactly it. Its clock parameter defaults to `MonotonicClock.System`, which counts nanoseconds from the moment that object was first touched. Every shipped sink dates the moment each block will be heard on a different origin: the public no-argument `DesktopAudioSink()` and `AudioTrackSink()` constructors use `System.nanoTime()`, and the Apple sink uses the mach host clock. The audio clock pairs a media time with the sink's deadline and then reads it back against its own `clock.nanos()`, so every reading is off by the whole distance between the two origins, which is typically hours. The engine never hits this because it always passes the backend's clock, and each backend's own KDoc says the pairing is "the whole point" so the mismatch "cannot be assembled". The public constructor assembles it by default.

**Evidence:**

```kotlin
// AudioPlayback.kt:34-35
 * The full player composes this with the video path. On its own it is a complete audio player, which
 * is why it is public: a music application needs exactly this and nothing more.
// AudioPlayback.kt:73-75
public class AudioPlayback(
    private val sink: AudioSink,
    private val clock: MonotonicClock = MonotonicClock.System,
```

```kotlin
// MonotonicClock.kt:27-35
        /** The platform's own monotonic clock. This is the production implementation. */
        public val System: MonotonicClock = SystemMonotonicClock
...
private object SystemMonotonicClock : MonotonicClock {
    private val origin = TimeSource.Monotonic.markNow()
    override fun nanos(): Long = origin.elapsedNow().inWholeNanoseconds
}
```

```kotlin
// DesktopAudioSink.kt:54-57
    public constructor() : this(
        PlatformSourceDataLineDriverFactory,
        DesktopMonotonicClock,      // System.nanoTime()
    )
// AudioPlayback.kt:742 (running clock)
            playoutClock.setAt(anchor.pts, generation, anchor.audibleAtNanos)
// MediaClock.kt:90 and 127-128
        driftNanos = pts.micros * 1_000L - atSystemNanos
        val elapsed = systemNanos - lastSetNanos
        val mediaNanos = driftNanos + systemNanos - ((elapsed * (1.0 - speed)).toLong())
```

The traced path: the sink's render callback publishes an anchor dated on `System.nanoTime()`, `position()` calls `anchorLocked()`, which feeds `anchor.audibleAtNanos` to `MediaClock.setAt`, and `nowOrNull()` then reads with `monotonic.nanos()` from the default clock. The engine avoids it only because `PlaybackCore.newAudioPlayback` passes `clock` explicitly (`PlaybackCore.kt:6476-6478`). `VideoPlayback` carries the same default (`VideoPlayback.kt:47`).

**Failure scenario:** A desktop music app writes `val audio = AudioPlayback(DesktopAudioSink())`, opens it, submits decoded audio and plays. The first anchor is dated near `System.nanoTime()` (on Linux, time since boot, easily 10^13 ns or more) while `clock.nanos()` is a few seconds since class load, so `position()` reports a media time hours before the start of the track and stays there offset; a progress bar or lyric sync built on it is wrong from the first frame. The paused branch of `anchorLocked` (line 736-740) computes `anchor.audibleAtNanos - now` as hours "still to come" and subtracts it, and `fadeOut` (line 858-860) waits its full 250 ms cap on every pause because `due - clock.nanos()` is huge. The same happens on Android with `AudioTrackSink()` and on Apple with a `CoreAudioSink`.

**Suggested fix:** Remove the default so a caller must pass a clock, or better, let the sink carry its clock (a `val clock: MonotonicClock` on `AudioSink`, or a constructor that takes an `OutputBackend` and reads both from it), so the pairing cannot be got wrong. At the very least document on the parameter that it must be the clock the sink dates its deadlines on, and stop calling `MonotonicClock.System` "the production implementation" in its KDoc. Give `VideoPlayback` the same treatment.

**How to check:** Read `AudioPlayback.kt:73-75` next to `DesktopAudioSink.kt:54-57`. A JVM test that builds `AudioPlayback(sink)` around a fake sink whose render deadlines are `System.nanoTime()`-based, plays one second, and asserts `position()` is near one second will fail by the JVM's `nanoTime` origin.

### AUD-02. A surround layout with height speakers plays only its front pair on stereo, losing the dialogue

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | medium | Confirmed | high | none |

**Where:** `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/ChannelMixer.kt:575-593`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/ChannelMixer.kt:599-606`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/ChannelMixer.kt:286-289`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/ChannelMixer.kt:351-365`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/ChannelMixer.kt:383-384`, `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/KiteFFmpegSource.kt:1569-1577`

**What happens:** The downmix refuses the whole matrix when either side's speaker mask has any bit above side right, which covers the height speakers, a second LFE, and wide or surround-direct speakers. With no matrix the mixer copies the first N channels in source order. The KDoc justifies this with "FFmpeg refuses the same masks", but it does not: FFmpeg's `sane_layout` only asks for a front speaker, whole left and right pairs (including the top front pair) and fewer than the maximum channels, and `swr_build_matrix2` then mixes every speaker it has a rule for. In FFmpeg 9.0.2 that even includes a rule folding the top front pair into front left and right at -3 dB. So where FFmpeg keeps the centre, the surrounds and the heights in a stereo downmix, this engine keeps front left and front right and nothing else.

**Evidence:**

```kotlin
// ChannelMixer.kt:575-593
        /** The eleven speakers the downmix rules cover, bits 0 to 10. */
        private const val RULED_SPEAKERS: Int = 11
...
        /**
         * True when every speaker has a rule and every pair is whole. FFmpeg refuses the same
         * masks: a left speaker without its right has no symmetric place to go.
         */
        private fun ruled(mask: Long): Boolean {
            if (mask shr RULED_SPEAKERS != 0L) return false
// ChannelMixer.kt:606
            if (!ruled(sourceMask) || !ruled(targetMask)) return null
// ChannelMixer.kt:286-289
        if (rows == null) {
            passThrough(input, output, frames)
// ChannelMixer.kt:357 (passThrough)
        val copied = min(sourceChannels, targetChannels)
```

```c
// FFmpeg 9.0.2 libswresample/rematrix.c:118-131 (sane_layout) accepts heights in pairs
    if(!even(av_channel_layout_subset(ch_layout, (AV_CH_TOP_FRONT_LEFT | AV_CH_TOP_FRONT_RIGHT))))
        return 0;
    return 1;
// rematrix.c:283-295 then mixes them
    if (unaccounted & AV_CH_TOP_FRONT_LEFT) {
        ...
        } else if (av_channel_layout_index_from_channel(out_ch_layout, AV_CHAN_FRONT_LEFT) >= 0) {
            if (av_channel_layout_index_from_channel(in_ch_layout, AV_CHAN_FRONT_LEFT) >= 0) {
                matrix[FRONT_LEFT ][TOP_FRONT_LEFT ] += M_SQRT1_2;
```

The source mask reaches the mixer whenever its bit count equals the channel count (`KiteFFmpegSource.kt:1576`, `channelLayoutMask = mask?.takeIf { it.countOneBits() == channels }`, with `MAX_MODELLED_CHANNELS = 8`), which holds for an eight-channel 5.1.2 layout. `matrixFor` (line 449-457) sends a two-channel target to `downmix`, which returns null, and the warning at line 383-384 says "has a speaker the downmix rules do not cover".

**Failure scenario:** A 5.1.2 WAV or FLAC (an Atmos bed export, mask 0x503F: FL FR FC LFE BL BR TFL TFR) played on headphones or laptop speakers. The player warns `ChannelLayoutUnknown` and plays channels 0 and 1 only. The centre, which carries the dialogue, both rear surrounds and both heights are silent. `ffmpeg -ac 2` on the same file keeps the centre at -3 dB, the surrounds, and the heights.

**Suggested fix:** In `downmix()`, accept any mask that passes FFmpeg's own `sane_layout` test and treat a speaker with no rule as unaccounted with zero gain, as FFmpeg's final loop does, adding FFmpeg's top front rule so the result matches the reference. Keep the pass-through only for masks FFmpeg itself would refuse (an unpaired speaker, no front speaker). Correct the KDoc sentence. Add a reference PCM row for a 5.1.2 source against FFmpeg's matrix, with the same explicit matrix on both sides as CLAUDE.md asks.

**How to check:** Read `ChannelMixer.kt:584-606`, then `libswresample/rematrix.c` lines 108-132 and 283-301 in the FFmpeg source. A unit test that builds a `ChannelMixer` from mask 0x503F to stereo and feeds a signal only on the centre channel gets silence out.

### AUD-03. When the audio runs dry mid-stream the position keeps moving through the silence and then jumps back

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | medium | Plausible | medium | none |

**Where:** `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/KotlinAudioRing.kt:525-543`, `KitePlayer/kiteplayer-rt/native/src/kite_rt_render.c:530-554`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/AudioPlayback.kt:741-743`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:7971-7985`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:13657-13658`

**What happens:** Both rings publish a clock anchor only when a render hands over at least one real frame. A render that is all underrun silence counts an underrun and publishes nothing, so the running audio clock keeps extrapolating from the last anchor as though sound were still playing. When audio comes back, its frames are dated by continuity from the frames before the gap, so the next anchor pulls the clock back by the length of the gap. The engine's buffering path would freeze the clock, but it engages only when a demuxer queue is also empty, so an underrun caused on the decode or feed side, with packets still queued, is never treated as a stall. ffplay behaves differently here: its audio callback re-sets the audio clock from the last decoded data on every callback, so silence written for a decode shortfall leaves the clock standing.

**Evidence:**

```kotlin
// KotlinAudioRing.kt:525-543
        if (toRead < frames) {
            destination.writeSilence(frameOffset = toRead, frames = frames - toRead)
            ...
            } else if (!ending.value) {
                underrunCount.incrementAndGet()
            }
        }

        if (toRead > 0) {
            val boundaryNanos = deadlineNanos - ((frames - toRead) * nanosPerFrame).toLong()
            publishAnchor(lastRealFrame = startFrame + toRead - 1, atNanos = boundaryNanos)
        }
```

```c
/* kite_rt_render.c:547-554, the same rule */
    if (to_read > 0) {
        int64_t boundary_nanos = sub_saturating(deadline_nanos, ...);
        publish_anchor(ring, start + to_read - 1, boundary_nanos);
    }
```

```kotlin
// PlaybackCore.kt:7980 and 13657-13658
        if (!demuxUnderrunSeen || !outputStarved(session)) return
    private fun demuxerRanShort(session: OpenSession): Boolean =
        session.selectedQueues().any { it.count == 0 && !it.isEndOfStream }
```

`AudioPlayback.anchorLocked`'s running branch (line 742) applies whatever anchor is newest, so the backward step is taken as given. The only engine reaction to an underrun alone is the `AudioUnderrun` warning at `PlaybackCore.kt:13363-13365`.

**Failure scenario:** A phone under thermal throttling, or a long garbage collection pause, starves the audio feeder for 300 ms while the packet queue is full. The device plays 300 ms of silence; `position()` and the published snapshot advance 300 ms through it; video is paced on that clock and runs 300 ms ahead of the silent sound. When audio resumes, the first anchor moves the clock 300 ms back: the position bar steps backwards and the sync law holds pictures to wait for the sound.

**Suggested fix:** On a render with no real frames, no hold and no ending flag, publish an anchor that keeps the last boundary's media time with the new deadline, so the running clock stands still across the gap, in both rings with a differential oracle row. Alternatively let `AudioPlayback` freeze its playout clock while the ring reports a starved device and re-anchor when real frames return.

**How to check:** A virtual-time test with the scripted device and `stallAudioDecodeReceive` (the CLAUDE.md way to force an underrun) while the packet queue stays full: sample `position()` across the stall and after it. If the reading rises through the stall and then decreases, the finding holds.

### AUD-04. On Android the clock runs ahead of the sound after every start, seek and resume until the track reports a timestamp

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | risk | medium | Plausible | medium | KitePlayer#495 |

**Where:** `KitePlayer/kiteplayer-output/src@android/io/github/yuroyami/kiteplayer/output/AudioTrackSink.kt:505-520`, `KitePlayer/kiteplayer-output/src@android/io/github/yuroyami/kiteplayer/output/AudioTrackSink.kt:550-563`, `KitePlayer/kiteplayer-output/src@android/io/github/yuroyami/kiteplayer/output/AudioTrackSink.kt:343-348`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/AudioPlayback.kt:729-743`

**What happens:** Until `AudioTrack.getTimestamp` gives an acceptable reading, the sink dates each block from the playback head: deadline equals now plus (submitted minus head plus one block) over the rate. The playback head counts frames the system mixer has taken, not frames that have reached the speaker, so the mixer, HAL and DAC delay after it (and on Bluetooth the radio and headset delay) is left out and each deadline is early by that much. This path runs after every fresh start and after every resume, because resume sets a timestamp floor that rejects every reading taken before it. When a real timestamp arrives the deadline source switches and the clock steps back by the left-out delay. Media3's position tracker subtracts the track's reported latency in the same fallback. The resume floor in `AudioPlayback` only rejects anchors older than the pre-pause one, so these early anchors are applied.

**Evidence:**

```kotlin
// AudioTrackSink.kt:505-519
    private fun deadlineForBlock(d: AudioTrackDriver, format: AudioFormat): Long {
        val ts = readTimestamp(d, writerTimestamp)
        if (ts != null && acceptTimestamp(ts)) { ... return timestampDeadline(...) }
        timestampSourceObserved = "head"
        val queued = submittedFrames - extendedHead(d)
        return clock.nanos() + framesToNanos(queued.coerceAtLeast(0) + blockFrames, format.sampleRate)
    }
// AudioTrackSink.kt:346 (resume)
                timestampFloorNanos = clock.nanos()
// AudioTrackSink.kt:559 (acceptTimestamp)
        if (ts.nanoTime < timestampFloorNanos) return false
```

There is no latency term anywhere on the head path, and `latencyNanos()` (line 354-360) is also computed from frames only.

**Failure scenario:** Watching a video on Android over Bluetooth headphones: after each start, seek or pause and resume, for the time before the HAL produces a fresh timestamp, the audio clock reads up to the output delay (on Bluetooth often 150 to 250 ms) ahead of what is heard, so pictures are shown that much early; then the clock steps back and video holds frames to catch up. On wired or speaker output the same step is the shorter output path delay.

**Suggested fix:** On the head path, subtract an estimate of the delay after the head, for example the difference between the last accepted timestamp and the head at the same moment, kept across a pause, or the track's latency as Media3 reads it. Better still, keep the last accepted timestamp across a pause and extrapolate from it instead of falling back to the head.

**How to check:** On a device, log `timestampSourceObserved` and the anchor deadline per block across a resume over Bluetooth, and compare the last head-based deadline with the first timestamp-based deadline for the same frame. A step of tens to hundreds of milliseconds proves it. The `adb shell dumpsys SurfaceFlinger --latency` method from CLAUDE.md shows the matching picture timing.

### AUD-05. Raising the volume just above 100 percent makes loud passages quieter and distorts them

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | design | medium | Confirmed | high | KitePlayer#15 |

**Where:** `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/KotlinAudioRing.kt:565-588`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/KotlinAudioRing.kt:876-886`, `KitePlayer/kiteplayer-rt/native/src/kite_rt_render.c:224-237`, `KitePlayer/kiteplayer-rt/native/src/kite_rt_render.c:484`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/KitePlayer.kt:365-367`

**What happens:** Above unity every sample is multiplied by the volume and then folded through a fixed saturator whose knee sits at 0.75 of full scale, and the lookahead limiter is switched off for a boost. The fold does not depend on how far the volume is above 1, so a volume of 1.001 bends every sample above about -2.5 dBFS as hard as any larger boost. Loud material therefore gets quieter the moment a boost starts: a full-scale peak plays at 1.0 at volume 1.0, at 0.875 at volume 1.0001, and only 0.958 at volume 2. The documented intent ("a boosted loud passage compresses instead of squaring off") is met, but the side effect is a volume control that is not monotonic at 100 percent.

**Evidence:**

```kotlin
// KotlinAudioRing.kt:578-585
        limitScale = if (gain > 1f && wanted > 1f) 0f else min(if (gain > wanted) gain else wanted, 1f)
        if (gain == wanted) {
            if (wanted > 1f) {
                for (i in 0 until frames * channels) {
                    samples[i] *= wanted
                    samples[i] = softClip(samples[i])
                }
// KotlinAudioRing.kt:876-886
        internal fun softClip(x: Float): Float {
            val knee = 0.75f
            ...
            val excess = (mag - knee) / (1f - knee)
            var folded = excess / (1f + excess)
            folded = (1f - knee) * folded
            folded = knee + folded
```

`kprt_soft_clip` in `kite_rt_render.c:224-237` is the same law, used at line 484. Worked example: a sample of 0.9 at volume 1.001 becomes 0.9009, excess 0.6036, output 0.844, which is 0.56 dB below the 0.9 heard at volume 1.0.

**Failure scenario:** A listener with `volumeCeiling` raised to 1.5 nudges the slider from 100 to 101 percent on a mastered pop track whose peaks sit near 0 dBFS. Every chorus gets flatter and slightly quieter, with odd-harmonic distortion on every peak, the opposite of what a louder setting should do.

**Suggested fix:** Apply the boost as clean gain and let the existing lookahead limiter catch only what passes full scale (it already handles the at-or-below-unity case), or move the knee with the gain so that a sample whose boosted level stays under full scale passes untouched. Keep the C and Kotlin rings identical and add an oracle row that asserts a full-scale peak never drops when the volume rises.

**How to check:** Call `KotlinAudioRing.softClip(1.0001f)` in a scratch test: it returns 0.875. Or render a full-scale sine through the ring at gains 1.0 and 1.01 and compare peaks.

### AUD-06. After a seek with night mode on, the new position starts too quiet and swells up

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | low | Confirmed | high | none |

**Where:** `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/AudioPipeline.kt:589-602`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/NightStage.kt:34-51`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/NightStage.kt:134-135`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/AudioPlayback.kt:903-905`

**What happens:** `AudioPipeline.reset`, which the seek path calls from `AudioPlayback.flush`, resets the mixer, resampler, tempo, pitch, silence and equaliser stages, with a comment that a filter keeping its history would splice the old position onto the new one. It does not touch the night mode compressor, whose peak follower and gain carry the level of the abandoned position into the new one, and `NightStage` has no reset at all. A gain pulled down by a loud scene then recovers at the 300 ms release rate, through both the falling peak and the gain release.

**Evidence:**

```kotlin
// AudioPipeline.kt:589-602
    fun reset() {
        clearPieces()
        mixer.reset()
        resampler?.reset()
        tempo.reset()
        pitchStage.reset()
        lineCount = 0
        silence.reset()
        equalizer.reset()
    }
// NightStage.kt:37-41
    private var peak = 0f
    private var gainDb = 0.0
    private var gain = 1f
```

By the stage's own constants, a peak near full scale asks for about -11 dB and a dialogue level near -30 dBFS asks for about +8 dB, so the gain has nearly 20 dB to climb back.

**Failure scenario:** Night mode on, the viewer seeks away from an explosion to a quiet dialogue scene: the first few hundred milliseconds of speech play many decibels low and swell up, the pumping the stage is tuned to avoid, on every such seek.

**Suggested fix:** Give `NightStage` a `reset()` that clears `peak`, `gainDb`, `gain`, `step` and `inBlock` while keeping the on or off setting, and call it from `AudioPipeline.reset` beside `equalizer.reset()`.

**How to check:** Read `AudioPipeline.kt:589-602` and look for any reset in `NightStage.kt`. A unit test that feeds the stage full-scale noise, calls the pipeline's `reset()`, then feeds a -30 dBFS tone and reads the first block's gain shows it below the settled value.

### AUD-07. Desktop audio is cut to 16 bits without dither, so quiet passages and low volumes carry quantisation distortion

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | low | Confirmed | high | none |

**Where:** `KitePlayer/kiteplayer-output/src@jvm/io/github/yuroyami/kiteplayer/output/DesktopAudioSink.kt:483-496`, `KitePlayer/kiteplayer-output/src@jvm/io/github/yuroyami/kiteplayer/output/SourceDataLineDriver.kt:92-101`, `KitePlayer/kiteplayer-output/src@android/io/github/yuroyami/kiteplayer/output/AudioTrackDriver.kt:113-123`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/spi/AudioSink.kt:98`

**What happens:** The desktop sink packs the engine's floats into 16-bit PCM by rounding alone. The desktop sink does not override `setDeviceGain`, so the whole volume is applied in the ring before the packing, and most codecs decode to float. At reduced volume or on fades the signal is requantised to fewer effective bits, with error that follows the music. The Android 16-bit fallback adds triangular dither for exactly this reason, and its KDoc says so. The rounding is deliberate for one property, bit-exact output of a 16-bit source at unity gain (#260), which a fix should keep.

**Evidence:**

```kotlin
// DesktopAudioSink.kt:489-492
    private fun packBlock(samples: Int) {
        var b = 0
        for (i in 0 until samples) {
            val v = (blockBuffer[i] * 32768f).roundToInt().coerceIn(-32768, 32767)
// AudioTrackDriver.kt:113-118
 * Converts [count] floats of [source] from [offset] to 16-bit PCM in [destination], with triangular
 * dither of one step, so quantisation leaves an even hiss and not distortion that follows the music.
// SourceDataLineDriver.kt:94-96
 * MEASURED on this machine (macOS 15, JDK 21, 2026-08-17) with `AudioSystem.isLineSupported`:
 * `PCM_FLOAT` is refused, 32-bit `PCM_SIGNED` is refused, and every mixer lists 16-bit and 24-bit
 * signed little-endian.
```

**Failure scenario:** A desktop JVM app at volume 0.05 (about -26 dB, applied in the ring), playing a piano fade-out on headphones: the signal keeps only about 11 bits, and its tail breaks into gritty, signal-correlated distortion instead of fading into a smooth noise floor.

**Suggested fix:** Open the line at 24 bits when the mixer reports it supported (measured on macOS; check Linux and Windows before relying on it), or add the same triangular dither the Android path uses when packing to 16 bits, skipping it only when the ring gain is exactly 1 so the bit-exact property of #260 stays.

**How to check:** Read `DesktopAudioSink.kt:489-492` next to `AudioTrackDriver.kt:113-123`. Pack a -90 dBFS sine at volume 0.05 through `packBlock` and look at the spectrum of the result: harmonics of the tone instead of flat noise.

### AUD-08. The public AudioPlayback.drain waits for ever when the device stops pulling

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | low | Confirmed | high | none |

**Where:** `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/AudioPlayback.kt:950-965`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:9201-9214`

**What happens:** `drain()` loops until the ring is empty with no bound of its own. The engine wraps it in `withTimeoutOrNull(DRAIN_DEADLINE)` and keeps its own grace period, but the method is public, its KDoc only says it "plays out what is already submitted, then stops", and a standalone caller (the music app the class's KDoc invites) has no such guard.

**Evidence:**

```kotlin
// AudioPlayback.kt:955-965
    public suspend fun drain() {
        val ring = ring ?: return
        ring.markEnding()
        while (ring.bufferedFrames > 0) {
            delay(ring.bufferedUs.microseconds.coerceIn(FULL_RING_WAIT_MIN, fullRingWait))
        }
        sink.drain()
        deviceRunning = false
    }
// PlaybackCore.kt:9209
                val finished = withTimeoutOrNull(DRAIN_DEADLINE) { audio.drain() } != null
```

**Failure scenario:** A music app using `AudioPlayback` directly reaches the end of a track after the Android AudioTrack writer has stopped on a `DeviceLost`, or in a browser whose AudioContext was never resumed by a user gesture. Nothing consumes the ring, `drain()` never returns, and the app's end-of-track logic hangs.

**Suggested fix:** Bound the wait inside `drain()` (ring depth plus the device's buffer plus slack, as the engine computes) and return or throw when it expires, or at least document in the KDoc that a device that stops pulling makes it wait for ever and that callers need a timeout.

**How to check:** Read `AudioPlayback.kt:955-965`. A test with a fake sink that never renders, calling `drain()` under `withTimeout(1.seconds)`, times out.

### AUD-09. The public KDoc of AudioPlayback.volume still says the volume is applied by the pipeline on the next converted buffer

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | docs | low | Confirmed | high | none |

**Where:** `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/AudioPlayback.kt:967-981`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/AudioPlayback.kt:1145-1166`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/AudioPipeline.kt:29`

**What happens:** The volume moved to the ring's read side, and on sinks that take a device gain (Android) the part up to unity moved to the device. `KitePlayer.setVolume` documents that correctly, and the first paragraph of this very KDoc says the ring is "where the gain is applied". The second paragraph of the same public KDoc still describes the old pipeline gain stage and its latency, contradicting both the first paragraph and the code.

**Evidence:**

```kotlin
// AudioPlayback.kt:976-979
     * Real, and applied by the pipeline's gain stage as one multiply per sample with a short ramp, so a
     * change never clicks. It takes effect on the next buffer the feeder converts, which is why setting
     * it is safe from any thread: this stores a value and the feeder reads it, rather than writing into
     * a stage that has one owner.
// AudioPipeline.kt:29
 * The gain is NOT here. Volume and mute moved to the ring's read side on 2026-08-31, ...
```

`pushGain()` (line 1145-1166) sets `ring?.setGain(...)` and `sink.setDeviceGain(...)`; the pipeline is not involved.

**Failure scenario:** A developer using `AudioPlayback` directly reads that a volume change waits for the feeder and is heard only after the ring's depth, and builds a workaround or a test on behaviour that no longer exists (the first volume latency test in this project was wrong in exactly this way). On Android they also miss that the part below unity is the AudioTrack's own volume.

**Suggested fix:** Replace the second paragraph with the wording `KitePlayer.setVolume` uses: applied on the ring's read side within one device period, the part at or below unity handed to the device where the sink takes it, and folded through the saturator above unity.

**How to check:** Read `AudioPlayback.kt:967-981` next to `pushGain()` at line 1145.

### AUD-10. The audio conversion KDoc says integer samples are scaled by their full-scale value while the code divides by a power of two

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | docs | low | Confirmed | high | none |

**Where:** `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/Conversions.kt:500-501`, `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/Conversions.kt:546-550`, `KitePlayer/kiteplayer-output/src@jvm/io/github/yuroyami/kiteplayer/output/DesktopAudioSink.kt:486-487`

**What happens:** The KDoc of `decodeToFloat` says integer formats are divided by their largest value so a full-scale input reaches exactly 1.0, but the code divides by 32768 and 2^31, which is FFmpeg's convention and the one the desktop sink's packing relies on for bit-exact 16-bit output. The comment is the wrong one, not the code.

**Evidence:**

```kotlin
// Conversions.kt:500-501
 * Integer formats are scaled by their full-scale value rather than by the next power of two, so a
 * full-scale input reaches exactly 1.0 and does not clip on the way back out.
// Conversions.kt:548-549
    KiteSampleFormat.S16, KiteSampleFormat.S16p -> leShort(offset) / 32_768f
    KiteSampleFormat.S32, KiteSampleFormat.S32p -> leInt(offset) / 2_147_483_648f
// DesktopAudioSink.kt:486-487
     * The 32768 scale and the rounding are FFmpeg's own, the inverse of how the decode side reads
     * S16, so a 16-bit source at unity gain reaches the line bit for bit (#260).
```

**Failure scenario:** Someone trusting the KDoc changes the desktop packing to 32767 to match it, and 16-bit sources stop reaching the line bit for bit, undoing #260; or a test is written expecting +32767 to read as exactly 1.0 and fails.

**Suggested fix:** Rewrite the paragraph to say integers are divided by 2 to the power of (bits minus 1), as FFmpeg does, so the most negative value reads -1.0 and the most positive just under 1.0, and name the desktop sink as the place that relies on it.

**How to check:** Read `Conversions.kt:500-501` against lines 546-550.

#### Rejected during verification

None. All ten auditor findings survived. Two were reduced to Plausible because they depend on run-time behaviour that static reading cannot settle (AUD-03 on how often a feed-side underrun happens with full packet queues, AUD-04 on how long the HAL takes to report a timestamp and how large the left-out delay is on a given device). Line numbers were corrected for AUD-04 (resume at `AudioTrackSink.kt:346`, timestamp floor check at line 559), and AUD-02 was strengthened: FFmpeg not only accepts height layouts but in 9.0.2 also mixes the top front pair into the front speakers, so the KDoc's "FFmpeg refuses the same masks" is itself untrue. AUD-01's claim about `VideoPlayback` handing renderers target times on the wrong origin was narrowed to "carries the same default", since the renderer side was not traced.


## 03. Video output, rendering, colour, HDR, Compose and views

This lane read the code that turns decoded pictures into what a viewer sees: the Metal renderer and its shader, the Apple colour tags and the sample buffer renderer, the Android surface renderer and the Compose GPU bridge, the threaded and web Compose KiteVideo renderers, the web canvas renderer, the AWT canvas renderer and the desktop view adapter, the iOS view, the software converters and HDR tone map in kiteplayer-ffmpeg, and KiteFFmpeg's web display converter. Every finding below was re-traced by hand against the current tree by a second reader who tried to refute it, and line numbers were corrected where they had drifted. Nothing was built, run or measured, so performance findings are stated as risks with the arithmetic shown, and two behaviour findings that depend on how a platform composites a layer are marked plausible. Not covered: the Android GPU image renderer past its latch and close paths, the Anime4k ports, the AppKit window and controls, the Swing controls, web picture in picture and the visualiser surface.

### VID-01. HDR video bands on every software path because PQ is tone mapped from 8-bit values

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| Both | defect | medium (was high) | Confirmed | high | none |

**Second check:** Downgraded from high to medium and merged in spirit with BRG-05, which is the same code path (`Conversions.kt:251` and `:362`, `HdrToneMap.kt:28-36` and `:61`, `SoftwareConverter.native.kt:93`). It is a visible quality limit in tone mapped HDR gradients, reasoned from the PQ step size and not measured. On Apple and Android the hardware and shader paths normally draw HDR, so there this path is only the fallback, while the desktop AWT view always takes it.

**Same root as:** BRG-05, WEB-16. Post these as one issue.

**Where:**
- `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/Conversions.kt:251`
- `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/Conversions.kt:255-258`
- `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/Conversions.kt:362`
- `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/HdrToneMap.kt:28-36`
- `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/HdrToneMap.kt:58-63`
- `KitePlayer/kiteplayer-ffmpeg/src@native/io/github/yuroyami/kiteplayer/ffmpeg/SoftwareConverter.native.kt:93`
- `KitePlayer/kiteplayer-view-bindings/src@jvm/io/github/yuroyami/kiteplayer/mobile/DesktopPlayerViewRenderer.kt:41`
- `KiteFFmpeg/native/kitecodec-c/src/helpers_display.c:9-13`
- `KiteFFmpeg/native/kitecodec-c/src/helpers_display.c:302`

**What happens:** HDR10 pictures are stored with 10 bits per sample because the PQ curve that encodes their brightness needs that many steps to look smooth. The software converter, which is the only video path on the desktop JVM view and the fallback on iOS, macOS and Android, first throws away the lowest two bits of each sample, converts the picture to 8-bit RGB, and only then tone maps it to standard range. The tone map looks every pixel up in a table with just 256 entries indexed by those 8-bit PQ bytes. One 8-bit PQ step in the mid tones is about four 10-bit steps, a brightness jump of several percent, so smooth gradients turn into visible stairs once the tone map stretches them into the SDR range. The Metal renderer tone maps from the full sample in floating point and does not band. KiteFFmpeg's web display converter copies the same 8-bit law on purpose, through swscale's 8-bit RGB output, so the web canvas has the same weakness. Ten-bit standard range video also loses its low bits by truncation rather than rounding, which darkens every level by about 0.375 of an 8-bit step on average.

**Evidence:**
```kotlin
// Conversions.kt:255-258
private enum class PackedSampleLayout(val bytesPerSample: Int, val dropBits: Int) {
    Eight(1, 0),
    TenLowAligned(2, 2),
    TenHighAligned(2, 8),
}
// Conversions.kt:362
((this[offset + 1].toInt() and 0xFF) shl 8 or (this[offset].toInt() and 0xFF)) shr layout.dropBits
// Conversions.kt:251, after the 8-bit RGBA is written
HdrToneMap.forColorSpaceOrNull(colorSpace, hdrPeakNits)?.mapInPlace(out)
```
```kotlin
// HdrToneMap.kt:28-35
private val eotf = DoubleArray(256) { code ->
    val e = code / 255.0
    ...
        pqDecode(e) * 10000.0
// HdrToneMap.kt:61
var r = eotf[rgba[at].toInt() and 0xFF]
```
```c
/* helpers_display.c:12-13 */
 *   BT.2390 EETF from a 1000 nit peak to 203 nit reference white, then gamma 2.2. The law runs on
 *   the 8-bit RGB bytes, as the Kotlin one does, so the two paths give the same picture. */
```
The desktop adapter converts every frame with `SoftwareConverter.toRgba(frame as KiteFFmpegVideoFrame, buffers)` (DesktopPlayerViewRenderer.kt:41), which lands in `tightlyPackedToRgba` and the code above; the native converter runs the same `mapInPlace` (SoftwareConverter.native.kt:93).

**Failure scenario:** Play an HDR10 film (yuv420p10le, PQ, BT.2020) in the desktop JVM view, in the UIKit or AppKit CPU fallback, in Android's software KiteVideo fallback, or on the web canvas. A clear sky, fog, or a dim wall shows stair-step bands that the same file does not show on the Metal renderer.

**Suggested fix:** Tone map before quantizing. In KitePlayer, read the full 10-bit sample, run the YUV to RGB step in float or 16-bit integers, index the PQ table with at least 10 (ideally 12) bits of input or evaluate it directly, apply the EETF, and only then encode to 8 bits, ideally with a small ordered dither. Round rather than truncate when 10-bit standard range is reduced to 8 bits. In KiteFFmpeg, have the display converter ask swscale for a 16-bit RGB intermediate (rgb48 or rgba64) when the frame's transfer is PQ or HLG, and run its tone map on those values before writing 8-bit RGBA.

**How to check:** Read `Conversions.kt` around lines 255 and 362 and `HdrToneMap.kt` lines 28 to 63. To see it, make a 10-bit PQ gradient with `ffmpeg -f lavfi -i gradients=s=1920x1080:c0=0x101010:c1=0x606060 -pix_fmt yuv420p10le -color_trc smpte2084 -color_primaries bt2020 -colorspace bt2020nc` and play it in the desktop view next to the Metal renderer on a Mac.

### VID-02. A paused web canvas player does not show a new subtitle, scale mode or zoom until playback resumes

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | medium | Confirmed | high | none |

**Where:**
- `KitePlayer/kiteplayer-output/src@wasmJs/io/github/yuroyami/kiteplayer/output/WebCanvasVideoRenderer.kt:361-376`
- `KitePlayer/kiteplayer-output/src@wasmJs/io/github/yuroyami/kiteplayer/output/WebCanvasVideoRenderer.kt:265-280`
- `KitePlayer/kiteplayer-output/src@wasmJs/io/github/yuroyami/kiteplayer/output/WebCanvasVideoRenderer.kt:348-356`
- `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/spi/VideoRenderer.kt:219-230`
- `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:7809-7812`
- `KitePlayer/kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/mobile/WebVideoRenderer.kt:46-54`

**What happens:** The renderer interface lets a renderer say whether it redraws the picture it holds by itself after a change of look. If it says yes, the engine trusts it and does not decode the paused picture again. The web canvas renderer does not override this, so it answers the default yes. But when it is told about a new scale mode or a new zoom and pan, it only stores the value, and a new subtitle overlay is drawn only when the picture has been taken off. The method that would redraw the held picture exists and is called only after a resize. The default KiteFFmpeg web canvas factory wraps this renderer by delegation, so it inherits the same answer.

**Evidence:**
```kotlin
// WebCanvasVideoRenderer.kt:361-376
override fun setScaleMode(mode: VideoScale) {
    scaleMode = mode
}
override fun setTransform(transform: VideoTransform) {
    this.transform = transform
}
override suspend fun setOverlay(overlay: SubtitleOverlay?) {
    this.overlay = overlay
    // Not drawn here while a picture plays: ...
    val s = state ?: return
    if (pictureCleared && !closed) drawBackground(s)
}
```
```kotlin
// PlaybackCore.kt:7809-7812
private fun requestRedraw(forced: Boolean) {
    ...
    if (!forced && pendingRenderer?.redrawsHeldPicture != false) return
```
The interface says the default true means the renderer "draws the picture it holds again by itself after a change of its look: scale mode, adjustments, framing, the view into a 360 degree video, render quality or the subtitle overlay" (VideoRenderer.kt:219-221). `redrawRetained` (line 265) is reached only from `setViewport` (line 355).

**Failure scenario:** On a page using `WebCanvasRendererFactory`, pause a video, then switch on a subtitle track, change the subtitle delay, pick Fill instead of Fit, or zoom and pan. The canvas keeps the old framing and no cue until play is pressed. On the Metal, UIKit, AppKit and Android renderers the change shows at once.

**Suggested fix:** Call `redrawRetained(s)` at the end of `setScaleMode`, `setTransform` and `setOverlay` (whenever a picture is held, not only when it was cleared), and from `setFlashGuard` (see VID-08). The alternative, answering `redrawsHeldPicture = false`, would make the engine seek and decode again, which is heavier than drawing from the stage the renderer already keeps.

**How to check:** Read the four setters at lines 361 to 376 and `requestRedraw` in PlaybackCore. A wasmJs test that presents one frame, calls `setScaleMode(VideoScale.Fill)` and reads back the canvas pixels would show the letterbox bars unchanged.

### VID-03. Rotating an iPhone while the video is paused stretches the Metal picture out of shape

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | medium | Plausible | medium | none |

**Where:**
- `KitePlayer/kiteplayer-view/src@ios/io/github/yuroyami/kiteplayer/view/KitePlayerUIView.kt:368-387`
- `KitePlayer/kiteplayer-output/src@apple/io/github/yuroyami/kiteplayer/output/MetalVideoRenderer.kt:656-659`
- `KitePlayer/kiteplayer-output/src@apple/io/github/yuroyami/kiteplayer/output/MetalVideoRenderer.kt:405-408`
- `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:8607-8613`

**What happens:** When the iOS view changes size, its layout method resizes the Metal layer and its drawable size and does nothing else: it tells neither the renderer nor the player. The Metal renderer redraws its retained picture only when something asks it to, and its `setViewport` (never called by this view) only stores numbers without asking for a redraw either. The layer's `contentsGravity` is never set, so it keeps Core Animation's default, which stretches the last drawable to fill the new bounds without keeping its shape. Nothing redraws until the next decoded frame arrives. There is one rescue: when a subtitle track with cues is loaded, the engine's subtitle pass notices the renderer's output size changed and publishes a new overlay, and `setOverlay` does ask the Metal renderer to redraw. A video without subtitles has no such rescue.

**Evidence:**
```kotlin
// KitePlayerUIView.kt:375-382
metalLayer.frame = bounds
...
metalLayer.contentsScale = scale
bounds.useContents {
    metalLayer.drawableSize = CGSizeMake(size.width * scale, size.height * scale)
}
```
```kotlin
// MetalVideoRenderer.kt:656-659
override fun setViewport(width: Int, height: Int, scale: Float) {
    viewportWidth.value = (width * scale).toInt()
    viewportHeight.value = (height * scale).toInt()
}
```
A search for `contentsGravity` finds it only in `UIKitVideoRenderer.kt`, never on the Metal layer, and a search for `setViewport` or `redrawPicture` finds no caller in `kiteplayer-view/src@ios`.

**Failure scenario:** Play a 16:9 video without subtitles in portrait on an iPhone with the default Metal renderer, pause, and rotate to landscape. The portrait drawable, a small picture with tall black bars above and below, is stretched to the landscape bounds, so faces look squashed and the bars grow, until play is pressed. iPad split view and Stage Manager resizes do the same.

**Suggested fix:** From `layoutSubviews`, when the drawable size actually changed, ask the active renderer to redraw its retained picture, for example by having the view call `setViewport` and making the Metal renderer's `setViewport` call `requestRedraw()` when the size moved. Also set `metalLayer.contentsGravity = kCAGravityResizeAspect` (or `kCAGravityCenter`) so the frame shown during the resize keeps its shape.

**How to check:** On a device or simulator, open a clip with no subtitle track, pause, and rotate. Whether the stretched frame appears depends on Core Animation keeping the old drawable as contents across the size change, which a static read cannot fully prove; the missing redraw path itself can be confirmed by reading the cited lines.

### VID-04. The desktop canvas allocates a new full-size image for every frame and copies, converts and repacks each frame on the scheduler thread

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | risk | medium | Plausible | medium | none |

**Where:**
- `KitePlayer/kiteplayer-output/src@jvm/io/github/yuroyami/kiteplayer/output/AwtCanvasVideoRenderer.kt:390-399`
- `KitePlayer/kiteplayer-output/src@jvm/io/github/yuroyami/kiteplayer/output/AwtCanvasVideoRenderer.kt:89-92`
- `KitePlayer/kiteplayer-view-bindings/src@jvm/io/github/yuroyami/kiteplayer/mobile/DesktopPlayerViewRenderer.kt:33-43`
- `KitePlayer/kiteplayer-view-bindings/src@jvm/io/github/yuroyami/kiteplayer/mobile/DesktopPlayerViewRenderer.kt:69-86`
- `KitePlayer/kiteplayer-ffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteplayer/ffmpeg/SoftwareConverter.jvm.kt:42-49`

**What happens:** For every frame, the desktop canvas renderer creates a brand new `BufferedImage`, which the JVM zero-fills: 8.3 MB at 1080p. The desktop adapter then fills it in three passes: the frame's planes are copied out of KiteFFmpeg into a reused array, converted to an RGBA byte array, and finally repacked one pixel at a time into the image's integers. All of this runs inline on the engine's video scheduler, as the class itself says, so the allocation and the passes delay the schedule. The adapter already reuses its two byte arrays through `SoftwareConverter.Buffers`; only the image is thrown away every frame, and the renderer keeps just one retained image for repaints, so two alternating images would be enough.

**Evidence:**
```kotlin
// AwtCanvasVideoRenderer.kt:390-393
val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
val raster = (image.raster.dataBuffer as java.awt.image.DataBufferInt).data
val painted = try {
    painter.paintArgb(frame, raster, width, height)
```
```kotlin
// DesktopPlayerViewRenderer.kt:41-42
val converted = SoftwareConverter.toRgba(frame as KiteFFmpegVideoFrame, buffers)
return packRgbInto(converted, destination, width, height)
```
```kotlin
// AwtCanvasVideoRenderer.kt:89-92
 * [present] converts and paints on the thread that calls it, which is the engine's video
 * scheduler, ... a slow present slows the schedule that called it.
```

**Failure scenario:** 1080p at 60 frames a second in the desktop view allocates and zeroes about 500 MB of image buffers per second on top of the conversion work, which drives frequent young-generation collections whose pauses land inside the presentation schedule and show as dropped or late frames on a modest machine. This is arithmetic, not a measurement.

**Suggested fix:** Keep two `TYPE_INT_RGB` images, the retained one and the one being filled, and swap them under the existing paint lock, allocating again only when the frame size changes. Longer term, let the converter write packed integers straight into the raster so the RGBA byte array and the Kotlin repack loop go away.

**How to check:** Read `present` at lines 377 to 440. Run the desktop sample with a 1080p60 clip and `-Xlog:gc` and count collections per second, then repeat with a two-image swap.

### VID-05. On Apple, every software-decoded frame is copied into new Kotlin arrays before it is copied again into textures

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | risk | medium | Plausible | medium | none |

**Where:**
- `KitePlayer/kiteplayer-ffmpeg/src@native/io/github/yuroyami/kiteplayer/ffmpeg/MetalFrameAccess.kt:49-74`
- `KitePlayer/kiteplayer-ffmpeg/src@native/io/github/yuroyami/kiteplayer/ffmpeg/MetalFrameAccess.kt:80-84`
- `KitePlayer/kiteplayer-view-bindings/src@ios/io/github/yuroyami/kiteplayer/mobile/MobilePlayerViewRenderer.ios.kt:26-42`
- `KitePlayer/kiteplayer-output/src@apple/io/github/yuroyami/kiteplayer/output/MetalFrameComposer.kt:634`
- `KitePlayer/kiteplayer-output/src@apple/io/github/yuroyami/kiteplayer/output/MetalVideoRenderer.kt:476-481`
- `KitePlayer/kiteplayer-ffmpeg/src@native/io/github/yuroyami/kiteplayer/ffmpeg/SoftwareConverter.native.kt:52-67`

**What happens:** The Metal resolver used by the iOS and macOS views calls `uploadPlanesOrNull`, which allocates a new `ByteArray` of stride times rows for every plane of every frame and copies the decoded picture into it. The composer then uploads those arrays into its pooled textures. The arrays are also what the renderer keeps to redraw a paused picture. The native `SoftwareConverter` used by the UIKit and AppKit CPU fallbacks likewise allocates a new width times height times four array for every frame and, unlike the JVM converter, offers no overload that reuses buffers.

**Evidence:**
```kotlin
// MetalFrameAccess.kt:51-55
val copied = frame.withPlanes { planes, strides, heights ->
    planes.mapIndexed { index, plane ->
        UploadPlanes.UploadPlane(
            bytes = copyOut(plane, strides[index] * heights[index]),
// MetalFrameAccess.kt:81-82
internal fun copyOut(source: CPointer<*>, size: Int): ByteArray {
    val bytes = ByteArray(size)
```
```kotlin
// MetalFrameComposer.kt:634
acquired.textures.forEachIndexed { index, texture -> texture.uploadPlane(picture.planes[index]) }
// SoftwareConverter.native.kt:67
val out = ByteArray(width * height * 4)
```

**Failure scenario:** Software AV1 or VP9 at 1080p60 on a Mac or iPhone without AV1 hardware allocates roughly 190 MB per second of Kotlin/Native arrays and makes a second full copy per frame; 10-bit content doubles that. The Kotlin/Native collector then has to run often, which can show up as missed drawables on the render thread. The UIKit CPU fallback at 1080p30 adds about 250 MB per second of RGBA arrays. These figures are arithmetic, not measurements.

**Suggested fix:** Recycle the plane arrays: keep a small pool keyed by plane size in the resolver (or in `MetalVideoRenderer`, releasing an array back once it is no longer the retained picture), so a steady stream allocates nothing. A bigger change is to let the composer upload straight from the decoded frame while it is still open and keep the retained redraw on a texture set pinned out of the composer's pool. Give the native `SoftwareConverter` a reusing overload like the JVM's `Buffers`.

**How to check:** Read `uploadPlanesOrNull` and `copyOut`. Play a software-decoded 1080p60 clip on macOS with Instruments' Allocations template and watch the Kotlin heap churn per second.

### VID-06. The display refresh interval every renderer reports is stored and never used, yet the interface says it improves smoothness

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | docs | medium | Confirmed | high | KitePlayer#692 |

**Same root as:** ENG-07. Post these as one issue.

**Where:**
- `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/spi/VideoRenderer.kt:22-23`
- `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/VideoPlayback.kt:123-129`
- `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:1588`
- `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:3483`
- `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:3795`
- `KitePlayer/kiteplayer-core/test/io/github/yuroyami/kiteplayer/VsyncAwarenessTest.kt:16-20`

**What happens:** The engine writes the renderer's refresh interval into `VideoPlayback.vsyncIntervalNanos` at attach and on every `VsyncChanged` event, and nothing in the schedule ever reads it. Its only reader is a property marked as read by tests. The interface rule says a null answer "costs smoothness on a high refresh display", the field's own comment says it is "held here because the schedule is the one consumer pacing against a display", and the test's header says renderers used to be "paced no better than a display nobody asked". None of that is true today: no presentation target is snapped to or phased against the display interval, so the renderers that work to supply the number get nothing for it.

**Evidence:**
```kotlin
// VideoPlayback.kt:123-129
 * The display's refresh interval, seeded at renderer attach and moved by
 * [io.github.yuroyami.kiteplayer.spi.RendererEvent.VsyncChanged]. Advisory, exactly as the
 * SPI says: null costs smoothness on a high-refresh display and nothing else. Held here
 * because the schedule is the one consumer pacing against a display.
internal var vsyncIntervalNanos: Long? = null
```
```kotlin
// PlaybackCore.kt:1588
val videoScheduleVsyncNanos: Long? get() = session?.video?.vsyncIntervalNanos
```
A search across `kiteplayer-core/src` finds the field only in assignments (PlaybackCore.kt:3483, 3795, 4360, 5876), the declaration and that test hook. `VsyncAwarenessTest` asserts only that the stored number equals the renderer's.

**Failure scenario:** A reader comparing KitePlayer with mpv's display-synced pacing reads the interface and the test and concludes the engine paces against the display. Removing every renderer's `vsyncIntervalNanos` would change no frame's timing. A 23.976 fps film on a 60 Hz desktop or web canvas gets the same cadence whatever the renderer reports.

**Suggested fix:** Either make the schedule use the interval (round presentation targets to the display's phase and keep a steady pulldown pattern), with a test that asserts a pacing effect, or change rule 4, the field comment and the test header to say the value is recorded for diagnostics only.

**How to check:** `rg -n "vsyncIntervalNanos" kiteplayer-core/src` and read each hit.

### VID-07. Render quality and 360 view changes do not reach a paused picture on the Android Compose GPU path

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | low | Confirmed | medium | none |

**Where:**
- `KitePlayer/kiteplayer-compose-video/src@threaded/io/github/yuroyami/kiteplayer/compose/KiteVideoRenderer.kt:108-142`
- `KitePlayer/kiteplayer-compose-video/src@threaded/io/github/yuroyami/kiteplayer/compose/KiteVideoRenderer.kt:202-231`
- `KitePlayer/kiteplayer-output/src@android/io/github/yuroyami/kiteplayer/output/AndroidGpuImageVideoRenderer.kt:115-125`
- `KitePlayer/kiteplayer-output/src@android/io/github/yuroyami/kiteplayer/output/AndroidGpuImageVideoRenderer.kt:144-145`
- `KitePlayer/kiteplayer-output/src@android/io/github/yuroyami/kiteplayer/output/AndroidGpuImageVideoRenderer.kt:252-257`
- `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:7812`

**What happens:** On Android the Compose KiteVideo renderer can hand codec frames to a GPU tier, `AndroidGpuImageVideoRenderer`, whose blit runs only when a new codec frame latches. That tier correctly answers `redrawsHeldPicture = false`, so the engine would decode the paused picture again after a change. But the engine never talks to that tier; it talks to the wrapper `KiteVideoRenderer`, which does not forward the answer and keeps the default true. The wrapper forwards render quality and the 360 projection to the tier, where they only set values on the bridge for the next latch. So dither, debanding, linear light, the animation upscaler and a switch into the 360 view do not show on a paused hardware-decoded picture.

**Evidence:**
```kotlin
// AndroidGpuImageVideoRenderer.kt:144-145
/** False: the blit runs at each latch only, so a held picture shows a change from a redraw (#463). */
override val redrawsHeldPicture: Boolean get() = false
```
```kotlin
// KiteVideoRenderer.kt:230
hardwareRenderer?.setRenderQuality(quality)
// KiteVideoRenderer.kt:208
if (!closed.value) hardwareRenderer?.setProjection(drawn, view)
```
A search for `redrawsHeldPicture` in `kiteplayer-compose-video` finds no override.

**Failure scenario:** In the Compose sample on an Android phone with hardware decoding through the GPU tier, pause and switch on debanding or the animation upscaler: the paused picture does not change. Turn a 360 video into the projected view while paused: the view is drawn from an image fitted to the old area, so it looks soft until play.

**Suggested fix:** Override `redrawsHeldPicture` in `KiteVideoRenderer` to answer false while the last published frame came from the hardware tier, for example `hardwareRenderer?.redrawsHeldPicture == false && lastPublishedFromHardware`, where the flag is set when a hardware frame is published and cleared when a software one is.

**How to check:** Read the two files at the cited lines; a core test with a fake hardware tier answering false would show the engine never asking for a redraw through the wrapper.

### VID-08. Turning the flash guard off while paused leaves the picture dimmed on the web, desktop and Compose renderers

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | low | Confirmed | high | KitePlayer#500 |

**Where:**
- `KitePlayer/kiteplayer-output/src@wasmJs/io/github/yuroyami/kiteplayer/output/WebCanvasVideoRenderer.kt:234-238`
- `KitePlayer/kiteplayer-output/src@wasmJs/io/github/yuroyami/kiteplayer/output/WebCanvasVideoRenderer.kt:279`
- `KitePlayer/kiteplayer-output/src@jvm/io/github/yuroyami/kiteplayer/output/AwtCanvasVideoRenderer.kt:223-226`
- `KitePlayer/kiteplayer-output/src@jvm/io/github/yuroyami/kiteplayer/output/AwtCanvasVideoRenderer.kt:547`
- `KitePlayer/kiteplayer-compose-video/src@threaded/io/github/yuroyami/kiteplayer/compose/KiteVideoRenderer.kt:157-161`
- `KitePlayer/kiteplayer-compose-video/src@wasmJs/io/github/yuroyami/kiteplayer/compose/WebKiteVideoRenderer.kt:347-351`
- `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:3316-3323`

**What happens:** Each of these four renderers keeps the dimming factor of the held picture beside it (`retainedFlashFactor`, `lastDim`, the published frame's `dim`). Their `setFlashGuard` resets the detector but neither redraws nor resets that factor. All four answer the default `redrawsHeldPicture = true`, and the engine's flash guard command does not ask for a redraw at all, so nothing undims the paused picture. The Metal, UIKit, AppKit and Android surface renderers request a redraw on a mode change, as their comments say ("turning the guard off undims it at once").

**Evidence:**
```kotlin
// WebCanvasVideoRenderer.kt:234-238
override fun setFlashGuard(mode: FlashGuard) {
    if (flashGuard == mode) return
    flashGuard = mode
    guard.reset()
}
// WebCanvasVideoRenderer.kt:279, in redrawRetained
drawStage(s, layout, retainedFlashFactor)
```
```kotlin
// AwtCanvasVideoRenderer.kt:223-226
override fun setFlashGuard(mode: FlashGuard) {
    if (flashGuard.getAndSet(mode) != mode) guardForgets.set(true)
}
// AwtCanvasVideoRenderer.kt:547, in paintHeld
dim = lastDim
```
```kotlin
// MetalVideoRenderer.kt:613-618, for contrast
if (flashGuardMode.getAndSet(mode) != mode) {
    guardForgets.value = true
    requestRedraw()
}
```

**Failure scenario:** With `FlashGuard.On`, pause during a strobe scene so the picture is held at about a third of its brightness, then switch the guard off in the app's settings. On the web canvas, the desktop canvas, and Compose KiteVideo on desktop, Android software frames or the web, the paused picture stays dark until play. On Apple and on the Android surface path it brightens at once.

**Suggested fix:** On a mode change to anything other than On, set the held factor to 1 and redraw the retained picture (`redrawRetained` on the web canvas, `repaintRetained` on AWT). In the two Compose renderers, republish the current frame with `dim = 1`, or read the guard mode in the draw phase so a change invalidates the draw.

**How to check:** Read the four `setFlashGuard` bodies and the redraw paths named above. The existing `WebCanvasFlashGuardTest` could gain a case that strobes, switches the guard off and reads the canvas brightness back.

### VID-09. The web canvas renderer never reports that it tone mapped HDR, so the HDR warning never fires there

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | low | Confirmed | high | none |

**Where:**
- `KitePlayer/kiteplayer-output/src@wasmJs/io/github/yuroyami/kiteplayer/output/WebCanvasVideoRenderer.kt:135-136`
- `KitePlayer/kiteplayer-output/src@wasmJs/io/github/yuroyami/kiteplayer/output/WebCanvasVideoRenderer.kt:42-48`
- `KitePlayer/kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/mobile/WebVideoRenderer.kt:54`
- `KitePlayer/kiteplayer-compose-video/src@wasmJs/io/github/yuroyami/kiteplayer/compose/KiteVideoState.wasmJs.kt:61`
- `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:3759-3764`
- `KiteFFmpeg/kiteffmpeg/src@wasmJs/io/github/yuroyami/kiteffmpeg/WebRgba.kt:31-35`

**What happens:** KiteFFmpeg's web converter tone maps every PQ and HLG frame to standard range. The engine learns that a picture was tone mapped only from a renderer's `ToneMapEngaged` event, which it turns into `PlaybackWarning.HdrToneMapped` and the snapshot's dynamic range. The web canvas renderer declares its event flow and never emits anything into it, and the `WebFramePainter` interface has no way to say a frame was tone mapped. The Compose web renderer does report it, through `toneMaps = { frame -> frame.colorSpace.isHdr }`.

**Evidence:**
```kotlin
// WebCanvasVideoRenderer.kt:135-136
private val _events = MutableSharedFlow<RendererEvent>(extraBufferCapacity = 8)
override val events: Flow<RendererEvent> = _events
```
A search for `_events.` in `kiteplayer-output/src@wasmJs` finds only `WebAudioSink.kt`. WebRgba.kt:31-32 says "A PQ or HLG picture is tone mapped to SDR in C beside the decoder".

**Failure scenario:** Open an HDR10 stream on a page that uses `WebCanvasRendererFactory`. The picture is rolled off to SDR, but the app never receives `PlaybackWarning.HdrToneMapped` and the snapshot does not say the picture was tone mapped. The same file in Compose for web, on the desktop view or on Android does warn.

**Suggested fix:** Add a `toneMapped(frame): Boolean` method with a false default to `WebFramePainter`, as `AwtFramePainter` has, emit `ToneMapEngaged` once per stream when it answers true after a successful paint, and have the KiteFFmpeg web canvas factory answer `frame.colorSpace.isHdr`.

**How to check:** Search the file for `tryEmit`; there is none.

### VID-10. On iPhone the Metal path shows BT.2020 standard range video with the wrong primaries

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | low | Confirmed | medium | none |

**Where:**
- `KitePlayer/kiteplayer-output/src@ios/io/github/yuroyami/kiteplayer/output/DisplayHeadroom.ios.kt:18`
- `KitePlayer/kiteplayer-output/src@apple/io/github/yuroyami/kiteplayer/output/DisplayHeadroom.kt:48-52`
- `KitePlayer/kiteplayer-output/src@apple/io/github/yuroyami/kiteplayer/output/MetalVideoRenderer.kt:235-238`
- `KitePlayer/kiteplayer-output/src@apple/io/github/yuroyami/kiteplayer/output/MetalVideoSupport.kt:652-663`

**What happens:** For standard range pictures the Metal shader writes the picture's own colour values with no change of primaries; the BT.2020 to BT.709 conversion runs only inside the PQ and HLG tone map. On macOS the layer is tagged with the picture's colour space, so Core Animation converts. On iOS the renderer deliberately leaves the layer untagged, and its own comment says iOS then treats the layer as sRGB. BT.709 content is close enough to sRGB, but a BT.2020 standard range stream reaches the screen as if its primaries were BT.709.

**Evidence:**
```kotlin
// DisplayHeadroom.kt:48-52
 * True where a Metal layer with no colour space gets no colour matching at all, so the renderer
 * names the picture's own (#489). That is macOS. iOS treats such a layer as sRGB.
internal expect val tagsStandardRangeLayer: Boolean
// DisplayHeadroom.ios.kt:18
internal actual val tagsStandardRangeLayer: Boolean = false
// MetalVideoRenderer.kt:237
val wanted = if (tagsStandardRangeLayer) tags ?: layerTags else null
```
```kotlin
// MetalVideoSupport.kt:658-661
val mode = when (colorSpace.transfer) {
    ...Pq -> 1
    ...Hlg -> 2
    else -> return DISABLED_TONE_UNIFORMS
```

**Failure scenario:** A UHD broadcast or camera clip tagged with BT.2020 primaries and a standard range transfer plays on an iPhone through the Metal renderer. Greens and reds look oversaturated, while the same file on a Mac looks right.

**Suggested fix:** On iOS, convert BT.2020 standard range primaries to BT.709 in the shader (the matrix already exists for the tone map path), or tag the layer's colour space on iOS too where the system honours it.

**How to check:** Read the cited lines. Play `ffmpeg -f lavfi -i testsrc2 -pix_fmt yuv420p10le -color_primaries bt2020 -color_trc bt2020-10 -colorspace bt2020nc` on an iPhone and on a Mac side by side.

### VID-11. The web canvas tone maps every HDR stream from a fixed 1000 nits, and KiteFFmpeg's documentation wrongly says that matches KitePlayer

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| Both | docs | low | Confirmed | high | none |

**Where:**
- `KiteFFmpeg/native/kitecodec-c/src/helpers_display.c:9-13`
- `KiteFFmpeg/native/kitecodec-c/src/helpers_display.c:103`
- `KiteFFmpeg/kiteffmpeg/src@wasmJs/io/github/yuroyami/kiteffmpeg/WebRgba.kt:31-35`
- `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/HdrToneMap.kt:147-150`

**What happens:** KiteFFmpeg's display converter hard-codes a 1000 nit source peak and its comments say this is "the law KitePlayer's software converters use", so "the canvas shows the same picture they do". KitePlayer's tone map now takes the content's own peak from its HDR metadata and falls back to 1000 only when there is none, so the two paths differ for any master that is not 1000 nits. The KiteFFmpeg prose also describes KitePlayer's internals, which an independent library should not depend on, and it has already drifted.

**Evidence:**
```c
/* helpers_display.c:10-12 */
 *   PQ and HLG, which are tone mapped to SDR with the law KitePlayer's software converters use:
 *   ... BT.2390 EETF from a 1000 nit peak to 203 nit reference white
/* helpers_display.c:103 */
#define KC_SRC_PEAK 1000.0
```
```kotlin
// HdrToneMap.kt:147-149
internal fun forColorSpaceOrNull(colorSpace: ColorSpaceInfo, peakNits: Float? = null): HdrToneMap? {
    if (!colorSpace.isHdr) return null
    val gamut2020 = colorSpace.primaries == ColorPrimaries.Bt2020
    val srcPeak = peakNits?.toDouble() ?: SRC_PEAK
```

**Failure scenario:** A 4000 nit HDR10 master plays in the desktop view and on the web canvas. The desktop rolls highlights off from 4000 nits and keeps detail; the web canvas clamps everything above 1000 nits, so highlights blow out on one platform and not the other, against what the KiteFFmpeg documentation promises.

**Suggested fix:** In KiteFFmpeg, give `ffkmp_frame_convert_display` a source peak, taken from the frame's mastering display or content light side data when present and 1000 nits otherwise, and describe the law on its own terms without naming KitePlayer, in both `helpers_display.c` and `WebRgba.kt`. KiteFFmpeg needs no knowledge of the player for this.

**How to check:** Read the two comments and `forColorSpaceOrNull`.

### VID-12. The sample buffer renderer creates a new IOSurface pixel buffer for every software frame

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | risk | low | Plausible | medium | none |

**Where:**
- `KitePlayer/kiteplayer-output/src@apple/io/github/yuroyami/kiteplayer/output/SampleBufferVideoRenderer.kt:405-410`
- `KitePlayer/kiteplayer-output/src@apple/io/github/yuroyami/kiteplayer/output/SampleBufferVideoRenderer.kt:570-610`

**What happens:** For a software frame, `copyIntoPixelBuffer` calls `CVPixelBufferCreate` with IOSurface properties every time and keeps no `CVPixelBufferPool`, so each frame allocates and maps a fresh IOSurface of about 3 MB at 1080p. The frame's planes already arrive as freshly copied Kotlin arrays (VID-05), so each picture is copied twice, and the 4:2:0 chroma planes are interleaved one byte at a time through a Kotlin/Native pointer.

**Evidence:**
```kotlin
// SampleBufferVideoRenderer.kt:409
is MetalPicture.SoftwarePlanes -> copyIntoPixelBuffer(picture, frame.colorSpace.fullRange, frame.colorSpace)
// SampleBufferVideoRenderer.kt:581-583
CFDictionarySetValue(attributes, kCVPixelBufferIOSurfacePropertiesKey, surfaceProperties)
...
val created = CVPixelBufferCreate(
```
A search for `CVPixelBufferPool` in the file finds nothing.

**Failure scenario:** Picture in picture of a software-decoded 1080p60 stream creates and frees 60 IOSurfaces a second, adding kernel calls and page clearing on top of the copies, which costs heat and battery and can drop frames in the small window on older iPhones.

**Suggested fix:** Keep one `CVPixelBufferPool` per format, size and range and take buffers from it with `CVPixelBufferPoolCreatePixelBuffer`; interleave chroma with `vImageConvert_PlanarToChunky8` or a word-wide loop.

**How to check:** Read `copyIntoPixelBuffer`; profile picture in picture with Instruments' VM Tracker for IOSurface churn.

### VID-13. The desktop canvas renderer's own documentation contradicts itself about which thread paints

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | docs | low | Confirmed | high | none |

**Where:**
- `KitePlayer/kiteplayer-output/src@jvm/io/github/yuroyami/kiteplayer/output/AwtCanvasVideoRenderer.kt:71`
- `KitePlayer/kiteplayer-output/src@jvm/io/github/yuroyami/kiteplayer/output/AwtCanvasVideoRenderer.kt:76`
- `KitePlayer/kiteplayer-output/src@jvm/io/github/yuroyami/kiteplayer/output/AwtCanvasVideoRenderer.kt:84-85`
- `KitePlayer/kiteplayer-output/src@jvm/io/github/yuroyami/kiteplayer/output/AwtCanvasVideoRenderer.kt:89-94`
- `KitePlayer/kiteplayer-output/src@android/io/github/yuroyami/kiteplayer/output/AndroidSurfaceVideoRenderer.kt:69`

**What happens:** The class summary says the renderer draws "on a thread of its own" and paints the canvas "from its own thread", and lists "a frame superseded by a newer one while the painter was busy" among the paths that close and count. The section right below says `present` paints inline on the engine's video scheduler, "exactly as the Android surface renderer does", that there is no painter thread, and that nothing is ever superseded. The Android surface renderer in fact hands frames to a worker and returns at once. A reader deciding whether a slow paint can stall the schedule gets opposite answers. The heading at line 89 is also missing its leading `*`.

**Evidence:**
```kotlin
// AwtCanvasVideoRenderer.kt:71
 * Draws frames onto an AWT canvas, on a thread of its own.
// AwtCanvasVideoRenderer.kt:90-92
 * scheduler, exactly as the Android surface renderer does. ...
 * a slow present slows the schedule that called it. There is no queue and no painter thread here,
```
```kotlin
// AndroidSurfaceVideoRenderer.kt:69
 * [present] hands the frame to a worker and returns at once, which is what the renderer contract
```

**Failure scenario:** An integrator trusts the summary and draws an expensive `AwtCanvasDecoration` on every frame, expecting it to be off the schedule, and video timing degrades.

**Suggested fix:** Rewrite the summary and the ownership paragraph to describe the inline design, drop the superseded path and the comparison with the Android renderer, and restore the `*` at line 89.

**How to check:** Read lines 70 to 100 of the file.

#### Rejected during verification

None. Every finding survived re-tracing. Adjustments made: VID-03 was kept as plausible and narrowed, because when a subtitle track with cues is loaded the engine's subtitle pass notices the new output size and its overlay publish makes the Metal renderer redraw, so the stretch is limited to videos without active subtitles. VID-04's title was corrected to the three real passes (copy, convert, repack), and its 4K figures were dropped because software 4K is a documented non-goal. VID-06 was reclassified from design to documentation untruth, since the concrete defect is the claim, with the missing capability noted. VID-10 was narrowed to BT.2020, because the renderer's own comment confirms iOS reads the untagged layer as sRGB but no P3 path was traced.


## 04. FFmpeg bridge: kiteplayer-ffmpeg and KiteFFmpeg Kotlin surface

This lane covered the seam where KitePlayer hands media to KiteFFmpeg and gets packets, frames and cues back: the source, packet, video and audio decoder wrappers and their filter paths in `KiteFFmpegSource.kt`, item opening, the subtitle decoders, thumbnails, the software pixel converter and its HDR tone map, the platform decoder choice, and on the KiteFFmpeg side the native, JVM and wasm `Packet`, `PacketReader`, `StreamDecoder`, `FilterGraph` and interrupt code with the C helpers behind them. Every finding below was re-read at the cited lines by a second reader who tried to refute it. The lane did not cover the HLS byte serving and variant switching in depth, the rest of the decoder fallback replay machine, the async lanes, or KiteFFmpeg's sink, transcoder and remuxer, which many open issues already cover. FFmpeg's own source is not in the container, so the claims about what libavcodec, the AudioToolbox wrapper, the webvtt demuxer and bwdif do come from knowledge of FFmpeg and are marked as such; each one names a quick way to confirm it against the pinned FFmpeg.

### BRG-01. A video in a pixel format outside the eleven the engine models plays with no picture on every renderer except the web

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | medium (was high) | Confirmed | high | none |

**Second check:** Downgraded from high to medium. The second reader confirmed that formats outside the eleven fall to Opaque and throw in the software converters, but the common consumer formats (4:2:0 at 8 and 10 bits, 4:2:2 including ProRes 422, 8-bit 4:4:4, gbrp, NV12, P010, bgra) are all modelled. The ones that fail are 12-bit, 10-bit 4:4:4, alpha, grey and similar. The cover art example in the first write-up is wrong: attached pictures are kept out of video selection (`KiteFFmpegSource.kt:758`, `PlaybackCore.kt:4241`) and reach the app as cover bytes. On Apple silicon VideoToolbox can still hand some of these formats over as drawable hardware frames. The Metal check is at `MetalFrameComposer.kt:596-598`.

**Where:** `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/Conversions.kt:26-45`, `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/Conversions.kt:243-247`, `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/KiteFFmpegSource.kt:1785`, `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/KiteFFmpegSource.kt:1168-1176`, `KitePlayer/kiteplayer-ffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteplayer/ffmpeg/SoftwareConverter.jvm.kt:56-66`, `KitePlayer/kiteplayer-output/src@android/io/github/yuroyami/kiteplayer/output/AndroidSurfaceVideoRenderer.kt:456-457`, `KitePlayer/kiteplayer-output/src@apple/io/github/yuroyami/kiteplayer/output/MetalVideoRenderer.kt:342-344`, `KitePlayer/kiteplayer-output/src@apple/io/github/yuroyami/kiteplayer/output/MetalFrameComposer.kt:595-597`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/spi/VideoRenderer.kt:180-182`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:4650`

**What happens:** FFmpeg's software decoders hand back pictures in dozens of pixel layouts. The engine names only eleven of them (8-bit 4:2:0, 4:2:2 and 4:4:4 with their full-range twins and planar RGB, 10-bit 4:2:0 and 4:2:2, NV12, P010, RGBA, BGRA and RGB24). Every other software layout is labelled "Opaque", which in the engine's vocabulary means "a hardware picture only its matching renderer can draw". But these frames are not hardware pictures, so no renderer can draw them, and nothing on the decode side converts them to a layout the engine knows first. The only filter the player inserts by itself is the deinterlacer. So every frame is refused and counted as a drop, while the sound plays on.

**Evidence:**

```kotlin
// Conversions.kt:42-44
    // Everything else, including every hardware format, is opaque to the engine. Only a renderer
    // matched to the decoder that produced it can draw it, and the engine does not need to know more.
    else -> PlayerPixelFormat.Opaque
```

```kotlin
// Conversions.kt:243-246, the CPU converter every desktop, Compose and view path uses
        else -> throw IllegalArgumentException(
            "tier 0 cannot convert $pixelFormat. A hardware frame needs its matching renderer, " +
                "and an unusual software format needs a filter graph first.",
        )
```

```kotlin
// AndroidSurfaceVideoRenderer.kt:456-457
    override fun supports(format: PlayerPixelFormat): Boolean =
        format != PlayerPixelFormat.Opaque || codecTarget != null
```

```kotlin
// MetalFrameComposer.kt:595-597 (MetalVideoRenderer.supports answers true for Opaque, then this throws)
        val recipe = requireNotNull(planeRecipeFor(picture.format)) {
            "the Metal renderer cannot draw ${picture.format}"
        }
```

`KiteFFmpegVideoDecoder` (KiteFFmpegSource.kt:1213) declares no `output` shape, so the engine's up-front compatibility check returns early (`val shape = decoder.output ?: return false`, PlaybackCore.kt:4650) and the first frame is the only backstop. `videoFilterChain` (KiteFFmpegSource.kt:1168-1176) adds only `bwdif`. The JVM, Android and iOS Compose converters and the desktop and mobile view renderers all go through `SoftwareConverter.toRgba`, which ends in the throw above. The web is the one exception: `KiteFFmpegWebPainter.paint` calls `WebRgbaConverter.copyInto`, which converts in C with FFmpeg's scaler from the frame's real layout, so the web canvas draws these frames. `scripts/testmedia.sh` makes only `yuv420p`, `yuv420p10le` and one raw `yuv444p` input, so no fixture exercises an unmodelled layout.

**Failure scenario:** Open any of these on Android, Apple or desktop: an H.264 High 4:4:4 or HEVC 4:4:4 10-bit screen recording (`yuv444p10le`), a 12-bit AV1 or HEVC file (`yuv420p12le`), a ProRes 4444 or DNxHR 444 master (`yuva444p10le` or `yuv444p10le`), a monochrome stream (`gray`), a JPEG-based MJPEG at 4:4:0 (`yuvj440p`), or an audio file whose only picture is a paletted or grayscale PNG cover (`pal8`, `gray`). The sound plays, the clock runs and the picture stays black or frozen on the last frame, with every frame counted as dropped. The same file plays in the browser build, and mpv and VLC play all of these.

**Suggested fix:** In `KiteFFmpegVideoDecoder`, when a decoded software frame maps to `Opaque`, run it through the filter path that already exists with a `format=` step to the nearest modelled layout: `yuv420p10le` above 8 bits at 4:2:0, `yuv422p10le` at 4:2:2, `yuv444p` for 8-bit 4:4:4 and `yuv420p10le` or `rgba` for deeper 4:4:4 until a 10-bit 4:4:4 layout is modelled, `rgba` for paletted, grey and RGB layouts. Declare `output` as a memory shape of the converted layout, so the engine's compatibility check works before playback. Add `yuv444p10le`, `gray` and `pal8` clips to `testmedia.sh`.

**How to check:** `ffmpeg -f lavfi -i testsrc2=d=5 -c:v libx264 -pix_fmt yuv444p10le -profile:v high444 /tmp/x.mp4` and play it in the desktop sample: no picture, and the dropped-frame counter climbs. Or read `toPlayerFormat` and grep the module for any `format=` step: there is none.

### BRG-02. Decoders are opened without a packet time base, so the first sound after encoder priming keeps its untrimmed time

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KiteFFmpeg | defect | medium | Plausible | medium | none |

**Where:** `KiteFFmpeg/kiteffmpeg/src@native/io/github/yuroyami/kiteffmpeg/Playback.native.kt:574-595`, `KiteFFmpeg/kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/Playback.jvm.kt:324-326`, `KiteFFmpeg/kiteffmpeg/src@wasmJs/io/github/yuroyami/kiteffmpeg/MediaSource.wasmJs.kt:572-579`, `KiteFFmpeg/native/kitecodec-c/src/helpers_codec.c:17-19`, `KiteFFmpeg/native/kitecodec-c/src/helpers_subtitle.c:37`, `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/KiteFFmpegSource.kt:1646-1650`

**What happens:** Many audio formats begin with a few hundred samples of encoder warm-up that a player must cut: Opus states a pre-skip, and MP3 and AAC files with a gapless header state an encoder delay. libavcodec does the cut itself, and when it cuts part of a frame it moves that frame's timestamp forward by the cut, but only when the caller has told the decoder the stream's time base through `pkt_timebase`. FFmpeg documents that field as "decoding: set by user", and both `ffmpeg` and `ffplay` set it. KiteFFmpeg never sets it for audio or video decoders on any backend, so FFmpeg keeps the trimmed frame at the time of its first untrimmed sample and logs a warning. The subtitle open does set it, which shows the gap is only on this path.

**Evidence:**

```c
// helpers_codec.c:17-19, the whole of what the audio and video open does with the stream
KC_API int   ffkmp_codecctx_from_par(AVCodecContext *c, AVCodecParameters *p) {
    return (c && p) ? avcodec_parameters_to_context(c, p) : AVERROR(EINVAL);
}
```

```c
// helpers_subtitle.c:37, the subtitle open
        c->pkt_timebase = st->time_base;
```

A search of `native/` and every `kiteffmpeg/src*` folder finds no other `pkt_timebase` write outside a patch. The native open (Playback.native.kt:577 `ffkmp_codecctx_from_par(codecCtx, codecpar)`), the JVM open (Playback.jvm.kt:326 `Internals.codecCtxFromPar(context, parameters)`) and the wasm open (MediaSource.wasmJs.kt:577) all go straight from parameters to `avcodec_open2`. KitePlayer then anchors its audio clock on exactly the frame's own time (KiteFFmpegSource.kt:1646 `val mapped = mapper.mapTimestamp(frame.ptsMicros)`, then `anchorMicros = mapped.micros`). The libavcodec behaviour (in `discard_samples` in `decode.c`, the timestamp shift is guarded by `avctx->pkt_timebase.num`, else "Could not update timestamps for skipped samples") is from knowledge of FFmpeg, not read in this container.

**Failure scenario:** An Ogg or WebM Opus file with the usual 312-sample pre-skip: the first decoded frame keeps 648 of its 960 samples but is dated at the start of the 960, so it is dated 6.5 ms early and the next frame starts 6.5 ms after the first one ends, a timestamp gap the engine's playout timeline then has to absorb on every such file. An MP3 with a LAME header (about 1105 samples of delay at 44.1 kHz) gives the same shape at about 25 ms. Each such file also writes FFmpeg's warning into the app's log sink. KiteFFmpeg users with their own decode loops get the same early timestamps.

**Suggested fix:** In KiteFFmpeg, set `pkt_timebase` to the stream's time base between `avcodec_parameters_to_context` and `avcodec_open2` in `StreamDecoder.open` on native, JVM and wasm, and in the batch decoder open, as the subtitle helper already does. Add a test that decodes an Opus file with a pre-skip and checks that the first frame's time equals the stream's start.

**How to check:** Decode any Opus file through `StreamDecoder` with the log sink on and look for "Could not update timestamps for skipped samples". Then compare the first frame's pts with what `ffprobe -show_frames -select_streams a -read_intervals %+#2` prints for the same file.

### BRG-03. A packet sent to a drained decoder is reported as decoded and silently thrown away

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KiteFFmpeg | defect | medium | Confirmed | high | none |

**Where:** `KiteFFmpeg/kiteffmpeg/src@native/io/github/yuroyami/kiteffmpeg/Playback.native.kt:452-454`, `KiteFFmpeg/kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/Playback.jvm.kt:223-224`, `KiteFFmpeg/kiteffmpeg/src@wasmJs/io/github/yuroyami/kiteffmpeg/Playback.wasmJs.kt:299-300`, `KiteFFmpeg/kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/Playback.kt:275-288`

**What happens:** Once a decoder has been told "no more input" (a null packet) and has not been flushed since, FFmpeg refuses every further packet with an end-of-file answer. All three backends translate that answer into `true`, which the public contract defines as "consumed". So a caller that forgets the flush, or does not know it is needed, keeps sending packets that are all thrown away, with no frame, no error and no counter. The KDoc of `send` already tells the story of the same silent loss for a different cause, and that one was fixed by refusing loudly.

**Evidence:**

```kotlin
// Playback.native.kt:452-454
        return when {
            rc == 0 -> true
            rc == FFErrors.EOF -> true
```

```kotlin
// Playback.jvm.kt:223-224
        when (rc) {
            0, Internals.errorEof -> true
```

```kotlin
// Playback.wasmJs.kt:299-300, whose comment also misreads the answer: EOF means drained, not flushed
            // Already flushed. Nothing more to send, so the input is not owed another attempt.
            rc == ffkmp_averror_eof(m) -> true
```

```kotlin
// Playback.kt:275-285
     * A packet belonging to a different stream is REFUSED rather than decoded. Feeding one used to
     * reach FFmpeg, which answered INVALIDDATA, which every backend swallowed as consumed, so the
     * input vanished and nothing said why.
     ...
     * @return true when consumed; false when output must be drained before retrying the same packet
```

KitePlayer's own scripted decoders refuse this case on purpose, and its CLAUDE.md says they do so because FFmpeg answers it with an error, so the test doubles are stricter than the real binding. KitePlayer's current paths flush before feeding again, so this is a contract defect in KiteFFmpeg rather than an observed KitePlayer bug.

**Failure scenario:** A KiteFFmpeg user drains a decoder at the end of a live input, the input resumes, and they keep calling `send` without `flush`. Every call returns `true`, `receive` returns null forever, and nothing says why. A future KitePlayer path that drains a lane and reopens it without the flush would stall the same way while every scripted test stays green.

**Suggested fix:** On a non-null packet, map end-of-file to an `IllegalStateException` or a typed `FFmpegException` that names the missing flush, on all three backends. Keep `true` only for a second null drain packet. Correct the wasm comment and state the rule in the `send` KDoc.

**How to check:** A three-line test: open a decoder, send a null packet, receive until null, then send a real packet and assert it is refused. Today it returns `true`.

### BRG-04. WebVTT captions read through FFmpeg lose their position, line and alignment settings

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| Both | defect | medium | Confirmed | medium | none |

**Where:** `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/KiteFFmpegSubtitleDecoder.kt:41-43`, `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/KiteFFmpegSubtitleDecoder.kt:135-137`, `KitePlayer/kiteplayer-subtitles/src/io/github/yuroyami/kiteplayer/subtitle/WebVttParser.kt:91`, `KitePlayer/kiteplayer-subtitles/src/io/github/yuroyami/kiteplayer/subtitle/WebVttParser.kt:116-150`, `KitePlayer/kiteplayer-adaptive/src/io/github/yuroyami/kiteplayer/network/dash/DashHls.kt:42-45`, `KiteFFmpeg/kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/Playback.kt:16-100`

**What happens:** A WebVTT cue can say where it goes: `align:start`, `position:20%`, `line:10%`, `size:50%`. FFmpeg's webvtt reader, which reads every WebVTT segment of an HLS stream and the WebVTT that KitePlayer's DASH reader serves to FFmpeg, and its Matroska reader both keep those settings beside the packet as side data, not in the text. KiteFFmpeg's `Packet` gives no access to side data, and KitePlayer's packet decoder parses the body only. So every such cue is drawn at the default bottom centre. The same cues keep their placement when the file is loaded as an external `.vtt`, because the file parser reads the settings from the timing line.

**Evidence:**

```kotlin
// KiteFFmpegSubtitleDecoder.kt:135-137
/** A WebVTT packet's cue. Matroska keeps the cue settings outside the body, so only the text is here. */
private fun webVttCue(track: WebVttTrackParser, body: String, startMicros: Long, endMicros: Long): SubtitleCue.Text? =
    track.parseCueBody(body).takeIf { it.isNotEmpty() }?.let { SubtitleCue.Text(startMicros, endMicros, it) }
```

```kotlin
// WebVttParser.kt:91, no settings
    public fun parseCueBody(body: String): List<StyledSpan> = vttSpans(body.trim(), VttStyleSheet.EMPTY, null)
```

The file parser's `parseTiming` (WebVttParser.kt:116-150) reads `ALIGN`, `POSITION`, `LINE` and `SIZE` from the text after the timing. KiteFFmpeg's `Packet` (Playback.kt:16-100) exposes timing, flags, size, position, tag and stream changes, and `copy()`, but no side data. DashHls.kt:42-45 says FFmpeg's HLS reader takes subtitles only as WebVTT text, which is why DASH subtitles take the same road. That FFmpeg stores the settings as `AV_PKT_DATA_WEBVTT_SETTINGS` comes from knowledge of FFmpeg's `webvttdec.c` and `matroskadec.c`.

**Failure scenario:** An HLS or DASH stream whose captions use `line:10%` to stay clear of burnt-in lower thirds, or `align:start` with `position:` to place a speaker's line on their side, as broadcast captioning commonly does. KitePlayer draws every cue at the bottom centre, over the on-screen text the author moved it away from. An MKV with positioned WebVTT does the same. Note that the integer form `line:0` is not read even by the file parser, which says so in a comment, so a test of this should use the percentage form.

**Suggested fix:** KiteFFmpeg: add a packet accessor for side data by type on every backend, at least the WebVTT settings and identifier, phrased as a general packet feature. KitePlayer: pass the settings string into a `WebVttTrackParser` call that reuses the timing-line settings code the file parser already has.

**How to check:** Make a two-cue `.vtt` with `align:start position:10% line:10%` on one cue, play it once as an external file and once wrapped in MKV with `ffmpeg -i x.vtt -c copy x.mkv`. The external file places the cue at the top left and the MKV draws it bottom centre.

### BRG-05. HDR video drawn by the software converter is cut to 8 bits before tone mapping, so dark gradients band

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | medium | Confirmed | medium | none |

**Second check:** Upheld at medium by the second reader. Same defect as VID-01; keep one issue when posting. The table is at `HdrToneMap.kt:28-36` with the lookup at line 61, and the tone map call is at `Conversions.kt:251`.

**Same root as:** VID-01, WEB-16. Post these as one issue.

**Where:** `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/Conversions.kt:249-250`, `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/Conversions.kt:254-258`, `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/Conversions.kt:358-363`, `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/HdrToneMap.kt:27-36`, `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/HdrToneMap.kt:56-61`, `KitePlayer/kiteplayer-ffmpeg/src@native/io/github/yuroyami/kiteplayer/ffmpeg/SoftwareConverter.native.kt:93`, `KitePlayer/kiteplayer-view-bindings/src@jvm/io/github/yuroyami/kiteplayer/mobile/DesktopPlayerViewRenderer.kt:41`

**What happens:** For a 10-bit HDR frame the CPU converter first drops each sample to 8 bits, does the colour matrix on those 8-bit PQ or HLG codes, rounds the result to a byte, and only then undoes the HDR curve through a 256-entry table indexed by that byte. The PQ curve was designed for 10 or 12 bits; at 8 bits each step in the shadows and midtones is several times the size the eye can see, so smooth gradients turn into visible contours after tone mapping. The desktop view renderer and the Compose image paths draw every frame through this converter, so desktop HDR always takes it.

**Evidence:**

```kotlin
// Conversions.kt:254-258 and 358-362
private enum class PackedSampleLayout(val bytesPerSample: Int, val dropBits: Int) {
    Eight(1, 0),
    TenLowAligned(2, 2),
    TenHighAligned(2, 8),
}
...
        ((this[offset + 1].toInt() and 0xFF) shl 8 or (this[offset].toInt() and 0xFF)) shr layout.dropBits
```

```kotlin
// Conversions.kt:249-250, after the 8-bit RGBA is written
    HdrToneMap.forColorSpaceOrNull(colorSpace, hdrPeakNits)?.mapInPlace(out)
```

```kotlin
// HdrToneMap.kt:27-29 and 59
    private val eotf = DoubleArray(256) { code ->
        val e = code / 255.0
...
            var r = eotf[rgba[at].toInt() and 0xFF]
```

The native converter applies the same 8-bit tone map (SoftwareConverter.native.kt:93). The KDoc of `HdrToneMap` calls this tier "the declared last resort", but on the desktop JVM view it is the only path (DesktopPlayerViewRenderer.kt:41). The visibility of the banding is reasoned from PQ's step size, not measured here.

**Failure scenario:** Play an HDR10 film with a dark sky, fog or a smoke gradient in the desktop player. The tone-mapped picture shows contour bands that the Metal and GL shader paths, which work in floating point from 10-bit textures, do not show.

**Suggested fix:** For HDR frames, keep the 10-bit samples through the matrix in floating point and index a 1024-entry or larger curve table, or ask FFmpeg's scaler for a 16-bit or float RGB layout before the tone map, and dither the final 8-bit encode. The SDR 8-bit path can stay bit-exact as its tests require.

**How to check:** Make a 10-bit PQ ramp (`ffmpeg -f lavfi -i "gradients=s=1920x1080:c0=black:c1=0x404040" -vf format=yuv420p10le,setparams=color_trc=smpte2084:color_primaries=bt2020:colorspace=bt2020nc -c:v libx265 ...`), take one frame through `SoftwareConverter.toRgba`, and count the distinct output grey levels across the ramp against the same frame tone mapped from float.

### BRG-06. On Apple devices AAC and Dolby audio decode to 16-bit integers by default, which clips loud peaks

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | design | medium | Plausible | medium | none |

**Where:** `KitePlayer/kiteplayer-ffmpeg/src@apple/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.apple.kt:26-44`, `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/KiteFFmpegSource.kt:653-662`

**What happens:** By default the player on iPhone and Mac decodes AAC, ALAC, AC-3 and E-AC-3 with Apple's AudioToolbox through FFmpeg's wrapper rather than with FFmpeg's own decoders. FFmpeg's wrapper asks AudioToolbox for packed 16-bit integer samples (32-bit only for high-depth ALAC), while FFmpeg's own AAC and Dolby decoders give floating point. Lossy decoding routinely reconstructs peaks slightly above full scale; a float decode keeps them, so a volume below 100 percent or the downmix brings them back into range, while a 16-bit decode has already hard-clipped them. The reason given in the code is battery savings on heavy decodes, but no measurement is cited, unlike the other choices in the same file.

**Evidence:**

```kotlin
// PlatformDecoderSelection.apple.kt:26-27 and 39-44
 * - `aac`, `alac`, `ac3`, `eac3` are IN. These are the formats real files arrive in where the decode
 *   is heavy enough for the offload to show up on a battery, surround Dolby most of all.
...
internal actual fun platformAudioDecoder(codec: String): DecoderId? = when (codec.trim().lowercase()) {
    "aac" -> DecoderId("aac_at")
    "alac" -> DecoderId("alac_at")
    "ac3" -> DecoderId("ac3_at")
    "eac3" -> DecoderId("eac3_at")
```

```kotlin
// KiteFFmpegSource.kt:662
    internal var preferPlatformAudioDecoder: Boolean = true
```

The 16-bit output of `aac_at` and its siblings is from knowledge of FFmpeg's `audiotoolboxdec.c`, not read here. The comment at KiteFFmpegSource.kt:653-661 already notes the two decoders differ in their output, which is why a reference test turns the platform decoder off.

**Failure scenario:** A loudly mastered AAC album or an E-AC-3 5.1 film on iPhone at 70 percent volume: the overs that FFmpeg's float decoder would keep, and that the volume stage would then bring under full scale, arrive already clipped flat at 16-bit full scale. The same file on Android and desktop decodes in float, so the output differs by platform with no warning.

**Suggested fix:** Measure the battery saving on one heavy E-AC-3 file before keeping AudioToolbox on by default. If it stays, say in the KDoc and in the decoder report that it decodes to 16 bits. Otherwise default to FFmpeg's decoders and keep AudioToolbox as an opt-in.

**How to check:** Open an AAC file on macOS and log the first decoded frame's sample format with the platform decoder on and off. Then decode a test tone mastered at +1 dBFS true peak both ways and compare the peak values.

### BRG-07. Interrupting a source while it closes reads a freed FFmpeg context, although a safe interrupt already exists

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| Both | risk | low | Confirmed | medium | none |

**Where:** `KiteFFmpeg/kiteffmpeg/src@native/io/github/yuroyami/kiteffmpeg/MediaSource.native.kt:748-753`, `KiteFFmpeg/native/kitecodec-c/src/helpers_format.c:78-81`, `KiteFFmpeg/kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/MediaSource.jvm.kt:495-501`, `KiteFFmpeg/kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/OpenInterrupt.kt:42-70`, `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/ItemSources.kt:95-96`, `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/KiteFFmpegSource.kt:462-470`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/AudioScanning.kt:36-42`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/AudioScanning.kt:135-137`

**What happens:** `MediaSource.interrupt` is the one call meant to come from another thread, yet it reads the FFmpeg context with no guard, and both backends say in a comment that calling it during or after close is forbidden. KiteFFmpeg's `OpenInterrupt` already solves this properly: its targets run under a lock that the close's unbind takes, so a close waits for an interrupt in flight. KitePlayer creates an `OpenInterrupt` for every open but then calls the unguarded `source.interrupt()` instead. The audio scan links its cancellation to that call from a job completion handler, which runs on the cancelling thread, while the scan thread may at that moment complete the link and close the session; `complete()` does not wait for a handler that is already running.

**Evidence:**

```kotlin
// MediaSource.native.kt:748-753
    public actual fun interrupt() {
        /* Deliberately NOT under stateLock: the whole point is reaching a context another thread
           is blocked on. The contract forbids calling this concurrently with or after close, so
           the pointer is live for the duration of the call. */
        ffkmp_fmt_interrupt(ctx)
    }
```

```c
// helpers_format.c:78-81
KC_API void ffkmp_fmt_interrupt(AVFormatContext *ctx) {
    if (!kc_ctx_has_cell(ctx)) return;
    kc_cell_raise((int *)ctx->interrupt_callback.opaque);
}
```

```kotlin
// AudioScanning.kt:41-42 and 135-137
    val link = currentCoroutineContext()[Job]?.let { Job(it) }
    link?.invokeOnCompletion { cause -> if (cause != null) session.source.interrupt() }
...
    } finally {
        link?.complete()
        session.close()
    }
```

The comment above line 41 says the link "is detached before the session closes, because an interrupt must never run after the close", which `complete()` does not guarantee for a handler already running on another thread. ItemSources.kt:95-96 says the `OpenInterrupt` "stays with the source the open returns, as MediaSource.interrupt() does", but `KiteFFmpegSource.interrupt` (KiteFFmpegSource.kt:462-470) does not use it.

**Failure scenario:** A waveform or loudness scan is cancelled at the instant it finishes. The cancelling thread enters `ffkmp_fmt_interrupt` and reads the context while the scan thread runs `avformat_close_input`. On Apple and native targets that reads, and may write, freed memory. The window is a few instructions wide, so it would show up as a rare, unexplained crash.

**Suggested fix:** KitePlayer: keep the `OpenInterrupt` in the opened item and have `KiteFFmpegSource.interrupt` raise it, plus the bridge, never `MediaSource.interrupt`. KiteFFmpeg: let `interrupt` and `close` share a small lock, so a close waits for an interrupt in progress and a later interrupt does nothing, which turns the documented undefined behaviour into a safe call for every user.

**How to check:** Read the two comments in the native and JVM `interrupt`, then the `finally` block of `AudioScanning`. A stress test that cancels scans of a short file at random moments under AddressSanitizer on Linux native would catch the read.

### BRG-08. A filter graph that fails to build or flush leaks the decoded picture it was handed

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | low | Confirmed | high | none |

**Where:** `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/Thumbnails.kt:74-99`, `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/Thumbnails.kt:110`, `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/KiteFFmpegSource.kt:1317-1319`, `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/KiteFFmpegSource.kt:1436-1445`, `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/KiteFFmpegSource.kt:1693-1697`

**What happens:** In thumbnail taking, the decoded picture is owned by the loop until it is fed into the filter graph. The graph is built in between with no guard that closes the picture, so a build that throws leaks one decoded picture. In both playback decoder wrappers, the old graph's flush on a mid-stream format change runs before the guard that closes the new frame when the build fails, so a flush that throws leaks that frame. KitePlayer's CLAUDE.md puts a leaked 1080p frame at 3.11 MB and a 4K one at 24.9 MB.

**Evidence:**

```kotlin
// Thumbnails.kt:74-75, then 89 with no try around it, and 110 where ownership finally passes
                    val decoded = source.extractFrame(position.inWholeMicroseconds, stream)
                    val software = if (decoded.info.isHardware) decoded.use { it.downloadFromHardware() } else decoded
...
                        graph = FilterGraph.buildVideo(
...
                    current.feedInput(0, software, take)
```

```kotlin
// KiteFFmpegSource.kt:1317-1319, before the try that closes raw on a failed build
            if (filterGraph != null && input != graphInput) {
                retireGraph { out -> filteredPending.addLast(DecodedPicture(out.copy(), scenePeakOf(out))) }
            }
```

The audio wrapper has the same order at KiteFFmpegSource.kt:1693-1697. `retireGraph` (1436-1445) closes the graph in a `finally` but rethrows a flush failure.

**Failure scenario:** A thumbnail strip of a file whose rotate, crop or scale step FFmpeg refuses for its geometry leaks a decoded picture, and the whole call fails on the first one. A mid-stream format change whose old graph fails to flush leaks one frame in the playback decoder.

**Suggested fix:** In `Thumbnails.at`, close `software` when `buildVideo` (or `shownGeometry`) throws. In both decoder wrappers, move `retireGraph` inside a try that closes `raw` on failure.

**How to check:** Read the three spots. A test that passes a deliberately invalid filter through the item's video filter, then counts open frames, shows the leak in the playback path.

### BRG-09. The video filter notes say timestamps keep the stream's time base, but the deinterlacer the player adds changes it

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | docs | low | Confirmed | high | none |

**Where:** `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/KiteFFmpegSource.kt:1288-1299`, `KiteFFmpeg/kiteffmpeg/src@native/io/github/yuroyami/kiteffmpeg/FilterGraph.native.kt:41-45`, `KiteFFmpeg/kiteffmpeg/src@native/io/github/yuroyami/kiteffmpeg/FilterGraph.native.kt:63-69`, `KiteFFmpeg/kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/FilterGraph.jvm.kt:13`

**What happens:** The note on the playback video decoder says timestamps pass through filters in the stream's own time base, so only filters that keep the time base are supported, and rate-changing filters "would silently disagree with the stream's". That is not what the code does. KiteFFmpeg stamps every filter output with the output end's own time base on every backend, so outputs are dated correctly whatever the filter does. The `bwdif` deinterlacer that this very factory inserts already halves the time base, which is a working proof against the note. The note also refers to "the KD roadmap", an internal label a reader cannot decode.

**Evidence:**

```kotlin
// KiteFFmpegSource.kt:1295-1298
     * held (#484), as the `ffmpeg` command line does. Timestamps pass through in the stream's own time base, so
     * the supported chains are the timebase-preserving ones (scale, crop, eq, format and
     * friends); fps-changing chains are the KD roadmap's own next step and refuse nothing today
     * because their output time base would silently disagree with the stream's.
```

```kotlin
// FilterGraph.native.kt:41-45 and 69
    override val outputTimeBase: Rational = memScoped {
        ...
        ffkmp_buffersink_time_base(sink, n.ptr, d.ptr)
...
        val view = FrameOps.wrap(into.nativeFrame, -1, inputType, outputTimeBase)
```

That bwdif halves the time base in every mode is from knowledge of FFmpeg's yadif-family output setup.

**Failure scenario:** A maintainer trusts the note and blocks rate-changing filters such as `fps=` or `framerate=`, which already date correctly, or chases a timing bug as a time base problem that does not exist.

**Suggested fix:** Rewrite the note: outputs carry the graph's own time base and are converted through it, so rate-changing chains are dated correctly. Keep whatever real limit remains, such as matching Dolby Vision scene peaks to frames across a rate change, and drop the internal label.

**How to check:** Read `outputTimeBase` in the three `FilterGraph` backends, then print `FilterGraph.outputTimeBase` for a graph built from `bwdif=mode=send_frame` on a 1/90000 stream.

#### Opportunities

### BRG-10. Taking several thumbnails opens and tears down a new video decoder for every picture

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KiteFFmpeg | opportunity | low | Confirmed | medium | none |

**Where:** `KiteFFmpeg/kiteffmpeg/src@native/io/github/yuroyami/kiteffmpeg/MediaSource.native.kt:356-394`, `KiteFFmpeg/kiteffmpeg/src@native/io/github/yuroyami/kiteffmpeg/MediaSource.native.kt:593-620`, `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/Thumbnails.kt:72-74`

**What happens:** `extractFrame` runs the shared demux pass, which opens a fresh decoder for the stream on every call and frees it at the end, and it decodes every frame from the keyframe to the target, including frames nothing else refers to. KitePlayer's `Thumbnails.at` calls it once per position. A strip of a hundred thumbnails of a 4K HEVC file therefore opens a hundred frame-threaded decoders, each starting its own thread pool, and fully decodes each run up to its target.

**Evidence:**

```kotlin
// MediaSource.native.kt:371 and 391
            decode.forEach { stream -> decoders += DecoderState.open(ctx, stream) }
...
            decoders.forEach { it.free() }
```

```kotlin
// Thumbnails.kt:72-74
                return@withContext positions.map { position ->
                    ensureActive()
                    val decoded = source.extractFrame(position.inWholeMicroseconds, stream)
```

**Failure scenario:** A library grid or seek-bar preview of long 4K files takes noticeably longer and allocates more than a loop that reuses one decoder with a flush between positions, and on a phone it competes with playback for cores.

**Suggested fix:** In KiteFFmpeg, offer a batch frame extraction or a reusable extractor that opens the decoder once, flushes it between seeks, and skips non-reference frames while decoding toward a target, restoring full decoding for the last stretch before it. Then have `Thumbnails.at` use it.

**How to check:** Time `Thumbnails.at` over 50 positions of the `hevc4k10.mp4` fixture against a hand loop over one `PacketReader` and one `StreamDecoder` with a flush between seeks.

#### Rejected during verification

- "On Apple and native targets a packet reader closed during a read frees memory the read still uses, unlike on the JVM": refuted, because the common `PacketReader` KDoc (KiteFFmpeg `kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/Playback.kt:150`) and the native one (`Playback.native.kt:214-215`) both state that reading, seeking and closing must be serialised by the caller, so a close during a read is documented misuse rather than a defect; the JVM's lock is an extra, and KitePlayer joins its demux worker before closing.


## 05. KiteFFmpeg native C, build flags, provenance, licensing

This lane read the C that KiteFFmpeg owns under `native/kitecodec-c` (the custom byte-source bridge, the nested opener and its AES-128 reader, the filter, Dolby Vision and display helpers), the fifteen FFmpeg patches against a checkout of FFmpeg n9.0.2, the configure recipes for every profile including the web one, the JNI link flags, the fuzz targets and workflow, and the licence texts that ship with each artifact and release. Every claim below was re-traced by hand in the tree and, where FFmpeg's behaviour mattered, in FFmpeg's own `hls.c` and `aviobuf.c` at n9.0.2. Nothing was built or run, so the findings are proven by reading only, and the one high finding says what a test would show. The web JavaScript glue, the Kotlin wrappers above the C layer, the KitePlayer real-time C beyond its ring, and the abi-check and dokka plugins were not covered. Two of the auditor's eight findings were rejected: one is already issue #185 in full, and one describes a limit that the public KDoc already documents.

### NAT-01. Encrypted HLS that cuts one file into byte ranges plays corrupted after the first segment when a nested opener serves it

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KiteFFmpeg | defect | medium (was high) | Confirmed | medium | none |

**Second check:** Downgraded from high to medium. The second reader retraced the same path (`helpers_format.c:1242`, `:1249`, `:1267`, `:1289`, `:1140`, and in the vendored hls.c lines 700, 1764 to 1765 and 1810) and confirmed it is a regression against stock FFmpeg's crypto protocol, but whole-segment AES-128 combined with byte ranges in one file is an uncommon packaging, and the result is a stream that will not play rather than corruption. Through KitePlayer the exposure is narrower still, because a multi-variant stream is served segment by segment with its byte range lines rewritten, so the failure mostly reaches KiteFFmpeg consumers who call `MediaSource.open(byteSource, opener)` directly.

**Where:** `KiteFFmpeg/native/kitecodec-c/src/helpers_format.c:1242`, `KiteFFmpeg/native/kitecodec-c/src/helpers_format.c:1249`, `KiteFFmpeg/native/kitecodec-c/src/helpers_format.c:1267`, `KiteFFmpeg/native/kitecodec-c/src/helpers_format.c:1289`, `KiteFFmpeg/native/kitecodec-c/src/helpers_format.c:1140`, `KiteFFmpeg/native/patches/ffmpeg/0002-hls-trust-io-open.patch`, `KiteFFmpeg/kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/MediaByteOpener.kt:47`, `KiteFFmpeg/kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/MediaByteOpener.kt:40`, `KiteFFmpeg/native/kitecodec-c/tests/test_nested_io.c`

**What happens:** An HLS playlist can describe its segments as byte ranges inside one big file (the `EXT-X-BYTERANGE` tag), and it can encrypt each of those segments on its own with AES-128, where each segment restarts the cipher with its own starting value and ends with its own padding. FFmpeg asks for such a segment by handing the opener the file address plus two options that say where the range starts and ends, and expects the opened stream to start at that range. KiteFFmpeg's nested opener reads only the key and the starting value from those options and opens the whole file from its first byte. Because KiteFFmpeg's patch makes FFmpeg skip its protocol check, FFmpeg no longer thinks the address is an http one, so it then tries to seek the opened stream to the start of the range. An encrypted nested stream is never seekable, so FFmpeg reads and decrypts every byte from the start of the file up to the range, and it does so with the wrong chaining: the first block of the segment is decrypted against the last cipher block of the previous segment instead of the segment's own starting value, so its first 16 bytes come out as garbage, and the segment's own padding is never removed because the source does not end there.

**Evidence:**

The nested opener uses only two options and opens the bare address:

```c
// KiteFFmpeg/native/kitecodec-c/src/helpers_format.c:1242
if (kc_hex_block(given, "key", key) < 0 || kc_hex_block(given, "iv", iv) < 0)
// KiteFFmpeg/native/kitecodec-c/src/helpers_format.c:1249
int rc = p->opener.open_fn(p->opener.opaque, inner, &source, &size, &seekable);
// KiteFFmpeg/native/kitecodec-c/src/helpers_format.c:1267
int can_seek = !encrypted && seekable && p->opener.seek_fn;
// KiteFFmpeg/native/kitecodec-c/src/helpers_format.c:1289
n->pb->seekable = can_seek ? AVIO_SEEKABLE_NORMAL : 0;
```

The padding is stripped only when the whole source ends:

```c
// KiteFFmpeg/native/kitecodec-c/src/helpers_format.c:1140
if (n->ended && n->cipher_len == 0) {
    /* The final block: its last byte says how many padding bytes to drop. */
    int pad = n->plain[used - 1];
```

FFmpeg n9.0.2 hands the range as options and, when the address is not http, seeks after the open:

```c
// libavformat/hls.c:1764 (FFmpeg n9.0.2)
av_dict_set_int(&opts, "offset", seg->url_offset, 0);
av_dict_set_int(&opts, "end_offset", end_offset, 0);
// libavformat/hls.c:1810
if (ret == 0 && !is_http && seg->url_offset) {
    int64_t seekret = avio_seek(*in, seg->url_offset, SEEK_SET);
```

Patch 0002 jumps past the place where `is_http` is set, so `is_http` stays 0 for every address:

```c
// libavformat/hls.c:700 after patch 0002
if (c->trust_io_open)
    goto open;
```

A seek on a stream that is not seekable reads forward from where it is (`libavformat/aviobuf.c:281`, `while(s->pos < offset && !s->eof_reached) fill_buffer(s);`), and `hls.c:1477` then limits each read to `seg->size` bytes, which counts the padding. `MediaByteOpener.open(url: String)` at `MediaByteOpener.kt:47` has no range parameter, and `test_nested_io.c` has no byte-range case at all (a search for "BYTERANGE" or "offset" there finds only its own cursor seek).

**Failure scenario:** A single-file HLS stream, one `.ts` or `.mp4` file split into `EXT-X-BYTERANGE` segments with `METHOD=AES-128`, is opened through `MediaSource.open(byteSource, opener)`. The first segment starts at byte 0 and decrypts correctly apart from up to 16 bytes of padding passed on as trailing data. For a later segment at offset N, the bridge downloads and decrypts bytes 0 to N only to throw them away, then hands FFmpeg a first block of garbage and a tail of padding. In fragmented MP4 the first 16 bytes are the `moof` box header, so every segment after the first fails to parse; in MPEG-TS the first packet of every segment is lost. Unencrypted byte ranges from a forward-only opener take the same read from byte 0 on every segment (FFmpeg's connection reuse at `hls.c:1726` needs a seekable stream), so playback cost grows with the square of the file size, and on a browser page each reopen stages the whole child source again, up to 512 MiB (`MediaByteOpener.kt:40`).

**Suggested fix:** In `kc_io_open_nested`, read `offset` and `end_offset` from the options. When an offset is given, seek the caller's source there before wrapping it (or pass the range to the opener through a new entry point), set the nested context's position to that offset so FFmpeg's follow-up seek is a no-op, and treat `end_offset` as the end of the stream, so that an encrypted range starts its cipher at the range with the segment's own starting value and strips its padding at the range's end. This is entirely inside KiteFFmpeg.

**How to check:** Add a case to `test_nested_io.c` that encrypts two WAV payloads separately with different starting values, concatenates them into one resource, and serves a playlist with two `EXT-X-BYTERANGE` segments over that resource; read both and compare with the plain bytes. The second segment's first 16 bytes will differ today. A second case with a forward-only cursor that counts bytes read shows the read from byte 0.

### NAT-02. On the web an HLS key or playlist given inline as a data: address fails to open, though the opener documentation says FFmpeg reads it

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KiteFFmpeg | defect | medium | Confirmed | high | none |

**Where:** `KiteFFmpeg/native/kitecodec-c/src/helpers_format.c:1231`, `KiteFFmpeg/build-plugins/native-build/src/BuildFFmpegWasmTask.kt:120`, `KiteFFmpeg/build-plugins/native-build/src/BuildFFmpegWasmTask.kt:133`, `KiteFFmpeg/build-plugins/native-build/src/BuildFFmpegTask.kt:387`, `KiteFFmpeg/kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/MediaByteOpener.kt:15`, `KiteFFmpeg/native/kitecodec-c/tests/test_nested_io.c:412`

**What happens:** The nested opener never gives a `data:` address to the caller's opener; it sends it to FFmpeg's own default open, trusting FFmpeg's `data` protocol to read the bytes inside the address. The native builds compile that protocol in, but the web build starts from nothing and adds back only the `file` protocol, and the HLS demuxer does not pull `data` in by itself. So on the web the default open has no protocol for the address, and the caller's opener, which could have answered, is never asked.

**Evidence:**

```c
// KiteFFmpeg/native/kitecodec-c/src/helpers_format.c:1230
/* A data: URL carries its bytes inline, and FFmpeg's data protocol reads them. */
if (av_strstart(url, "data:", NULL)) return p->default_io_open(s, pb, url, flags, options);
```

```kotlin
// KiteFFmpeg/build-plugins/native-build/src/BuildFFmpegWasmTask.kt:120
"--disable-everything", "--disable-autodetect", "--disable-network",
// KiteFFmpeg/build-plugins/native-build/src/BuildFFmpegWasmTask.kt:133
"--enable-protocol=file",
// against the native profile, KiteFFmpeg/build-plugins/native-build/src/BuildFFmpegTask.kt:387
"--enable-protocol=file,fd,pipe,data,http,tcp,udp,rtp,rtmp",
```

FFmpeg n9.0.2 `configure:3943` reads `hls_demuxer_select="aac_demuxer ac3_demuxer adts_header ac3_parser eac3_demuxer mov_demuxer mpegts_demuxer"`, with no `data_protocol`. The web demuxer list at `BuildFFmpegWasmTask.kt:258` includes `hls`, and the web backend drives the same C opener (`kiteffmpeg/src@wasmJs/.../WebNestedOpener.kt` and `wasm/OpenerLayout.kt`). The public KDoc promises the opposite at `MediaByteOpener.kt:15`: "A `data:` address never arrives. FFmpeg reads the bytes inside it itself." The native C suite proves the data: key only against a native FFmpeg (`test_nested_io.c:412`, `case_an_aes128_segment_decrypts(1)`), and no web test uses a data: address in a playlist.

**Failure scenario:** An HLS stream with `#EXT-X-KEY:METHOD=AES-128,URI="data:application/octet-stream;base64,..."` plays on Android, the JVM and Apple. Opened through `kiteffmpeg-web` or `kiteffmpeg-web-async`, FFmpeg's key read (`hls.c:1691`) fails with "protocol not found", and no segment can be decrypted. A master playlist that inlines a media playlist as a data: address fails the same way.

**Suggested fix:** Add `data` to the web build's protocol list; it is FFmpeg's own small protocol with no dependency and no network access. If the web build must stay at `file` only, hand a data: address to the caller's opener whenever `avio_find_protocol_name(url)` finds no protocol, and correct the KDoc line to say what each platform does.

**How to check:** Read the two configure lists above, then run the existing data-key case of `test_nested_io` in the web test runner (or add one to the wasmJs tests that opens a one-segment AES-128 playlist with a data: key); it fails on the web and passes natively.

### NAT-03. No fuzz target reaches the AES-128 reader, the nested opener or the Dolby Vision and display converters that KiteFFmpeg wrote itself

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KiteFFmpeg | test | medium | Confirmed | high | KiteFFmpeg#205 |

**Where:** `KiteFFmpeg/native/kitecodec-c/fuzz/kc_fuzz.c:106`, `KiteFFmpeg/native/kitecodec-c/fuzz/fuzz_decode.c:117`, `KiteFFmpeg/native/kitecodec-c/src/helpers_format.c:1117`, `KiteFFmpeg/native/kitecodec-c/src/helpers_format.c:1360`, `KiteFFmpeg/native/kitecodec-c/src/helpers_dovi.c:444`, `KiteFFmpeg/native/kitecodec-c/src/helpers_dovi.c:536`, `KiteFFmpeg/native/kitecodec-c/src/helpers_display.c:298`, `KiteFFmpeg/.github/workflows/fuzz.yml:80`

**What happens:** Issue #205 is about FFmpeg's patched parsers never being fuzzed or run under a sanitizer, and it proposes an HLS fuzz target whose opener refuses every segment. This finding is the part of the same gap that lives in KiteFFmpeg's own C rather than in the patches. The media fuzz targets open their bytes with no nested opener at all, and a target with an opener that refuses every segment would still never reach the code that decrypts segments, parses the hex key and starting value, strips the padding, asks the opener for a redirect location, or frees and unlinks a nested source after a failed open. Likewise, the decode target converts frames only through the plain pixel converter, so the per-pixel Dolby Vision compose and the display converter, which both read values that come straight from the media, are never fuzzed.

**Evidence:**

```c
// KiteFFmpeg/native/kitecodec-c/fuzz/kc_fuzz.c:106
int rc = ffkmp_fmt_open_input_io(out, media, kc_fuzz_media_read,
```

A search of `fuzz/*.c` finds no call to `open_input_io2`, no `kc_io_opener`, and no `dovi` or `convert_display`; `fuzz_decode.c:117` calls only `ffkmp_frame_convert_pixfmt`. The opener path refuses itself on an FFmpeg without the patch:

```c
// KiteFFmpeg/native/kitecodec-c/src/helpers_format.c:1360
if (opener && !ffkmp_fmt_nested_io_available()) return AVERROR(ENOSYS);
```

and `fuzz.yml:80` installs Ubuntu 24.04's FFmpeg 6.1 packages, which do not carry the `trust_io_open` option, so an opener target added as things stand would stop at that line.

**Failure scenario:** A hostile segment or key drives an edge of `kc_io_crypt_read` (a short final read across a block boundary, a source that answers more than it was asked for, a final block whose pad byte is out of range) or of the nested free and unlink order during a failed open, or a hostile Dolby Vision RPU drives an edge of the compose. libFuzzer never generates such inputs, so a memory bug there ships unseen on the exact path that handles untrusted network playlists and HDR streams.

**Suggested fix:** Add a target whose in-memory opener serves a fuzzer-split playlist, segments and key so that AES-128 decryption is reached, built against the patched vendored tree (which is the same build #205 asks for). Add a target, or extend `fuzz_decode`, that passes every decoded frame through `ffkmp_frame_convert_display` and, when it carries Dolby Vision metadata, through `ffkmp_frame_dovi_compose_prepare` and `ffkmp_frame_dovi_compose_rows`.

**How to check:** `grep -l "open_input_io2\|dovi\|convert_display" native/kitecodec-c/fuzz/*.c` returns nothing.

### NAT-04. No published artifact or release zip carries the dav1d copyright notice that its BSD licence asks a binary distributor to reproduce

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KiteFFmpeg | docs | low | Confirmed | high | KiteFFmpeg#185 |

**Same root as:** API-04. Post these as one issue.

**Where:** `KiteFFmpeg/.github/scripts/package-ffmpeg.sh:81`, `KiteFFmpeg/.github/scripts/package-ffmpeg.sh:121`, `KiteFFmpeg/kiteffmpeg/resources@jvm/META-INF/licenses/kiteffmpeg-ffmpeg/THIRD-PARTY.txt`, `KiteFFmpeg/kiteffmpeg/resources@android/META-INF/licenses/kiteffmpeg-ffmpeg/THIRD-PARTY.txt`, `KiteFFmpeg/kiteffmpeg/module.yaml:86`, `KiteFFmpeg/kiteffmpeg-android-natives/module.yaml:26`, `KiteFFmpeg/docs/licensing.md:93`

**What happens:** dav1d is compiled into every release zip, native klib, JVM jar and Android library, and its BSD-2-Clause licence asks anyone who redistributes it in binary form to reproduce its copyright notice, conditions and disclaimer. The project's own licensing page says the same of every permissive component. Yet the release zips carry only FFmpeg's licence files, the jar and the Android library carry FFmpeg's LGPL text and a `THIRD-PARTY.txt` that gives dav1d only as a web address, and the POM points at dav1d's moving `master` branch rather than the 1.5.4 tag that is built. Issue #185 covers the missing libiconv and IJG notices and FFmpeg's `LICENSE.md`; it does not mention dav1d.

**Evidence:**

```bash
# KiteFFmpeg/.github/scripts/package-ffmpeg.sh:81
legal_files=("COPYING.LGPLv2.1" "LICENSE.md")
# KiteFFmpeg/.github/scripts/package-ffmpeg.sh:121, in the same zip
if [ ! -f "${src}/lib/libdav1d.a" ]; then
```

```text
# THIRD-PARTY.txt (JVM copy)
dav1d: https://code.videolan.org/videolan/dav1d (BSD-2-Clause, no source obligation)
```

```yaml
# KiteFFmpeg/kiteffmpeg/module.yaml:86, the same at kiteffmpeg-android-natives/module.yaml:26
url: https://code.videolan.org/videolan/dav1d/-/blob/master/COPYING
```

`docs/licensing.md:93` says: "Permissive components (BSD, zlib) only require you to reproduce their license text and copyright notice." The only licence files in the published resources are `COPYING.LGPLv2.1` and `THIRD-PARTY.txt`, and `BuildDav1dTask.kt` copies no licence file into the tree.

**Failure scenario:** A consumer who builds an app from the release zip or the Maven artifacts and follows the shipped texts has no copy of dav1d's notice to reproduce, so the app's attribution misses a component it ships. If dav1d's `master` ever changes its `COPYING`, the POM link stops describing the version that is embedded.

**Suggested fix:** Copy dav1d's `COPYING` from the pinned checkout into each release zip and into `META-INF/licenses/kiteffmpeg-ffmpeg/` of the jar and the Android library, and pin the POM link to the 1.5.4 tag.

**How to check:** `unzip -l` any release zip or the published jar and look for a dav1d licence file; there is none.

### NAT-05. The libass chain release ships FriBidi under the LGPL with no source attached and no provenance, unlike the FFmpeg release

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KiteFFmpeg | docs | low | Confirmed | medium | KiteFFmpeg#187 |

**Where:** `KiteFFmpeg/.github/workflows/release-ass-chain.yml:137`, `KiteFFmpeg/.github/workflows/release-ass-chain.yml:165`, `KiteFFmpeg/.github/workflows/release-binaries.yml:221`, `KiteFFmpeg/.github/workflows/release-binaries.yml:231`, `KiteFFmpeg/scripts/package-ass-chain.sh:80`, `KiteFFmpeg/docs/licensing.md:34`, `KiteFFmpeg/docs/licensing.md:88`

**What happens:** The chain release distributes static FriBidi (LGPL-2.1-or-later) and a patched libass as object code. Its workflow attaches only the zips and their checksums, with no source tarball and no build provenance attestation, while the FFmpeg release workflow attaches the exact source tarball and attests the binaries. The licensing page argues for attaching FFmpeg's source because the offer then "does not depend on a third-party URL staying alive", and then points the chain's source at the upstream tags each zip's `CHAIN.txt` names. The licensing page also says an upstream tag "works in practice", so this is an inconsistency of care rather than a clear breach, which is why it stays low.

**Evidence:**

```yaml
# KiteFFmpeg/.github/workflows/release-ass-chain.yml:137 onwards, the whole release job
permissions:
  contents: write
...
for asset in assets/*; do
  ...
  gh release upload "$TAG" "$asset" --repo "$REPO"
```

No `attest-build-provenance` step and no source fetch appear anywhere in the file, against `release-binaries.yml:221` ("Fetch FFmpeg source tarball (source offer)") and `release-binaries.yml:231` (`actions/attest-build-provenance@v2`). `docs/licensing.md:88`: "Their source is the tagged upstream checkout that each zip's `CHAIN.txt` names".

**Failure scenario:** A product that embeds the chain's archives must offer FriBidi's source. If the upstream tag is moved or the repository is renamed, the offer that the release relies on points at nothing, and a consumer cannot verify that a chain zip came from this workflow.

**Suggested fix:** Attach `git archive` tarballs of the four pinned checkouts, with the libass patches applied, to the chain release, and add `actions/attest-build-provenance` for the zips, as the FFmpeg release does.

**How to check:** Read the release job of `release-ass-chain.yml` beside the one in `release-binaries.yml`.

### NAT-06. The FFmpeg recipe comments point at desktop GPL and base profiles that no longer exist

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KiteFFmpeg | docs | low | Confirmed | high | KiteFFmpeg#222 |

**Where:** `KiteFFmpeg/build-plugins/native-build/src/BuildFFmpegTask.kt:422`, `KiteFFmpeg/build-plugins/native-build/src/BuildFFmpegTask.kt:423`, `KiteFFmpeg/build-plugins/native-build/src/BuildFFmpegTask.kt:61`, `KiteFFmpeg/build-plugins/native-build/src/BuildFFmpegTask.kt:122`, `KiteFFmpeg/build-plugins/native-build/src/BuildFFmpegTask.kt:236`

**What happens:** The comment above the shared filter list says that `drawtext` lives in `desktopBaseArgs` and that `eq` and `boxblur` live in `desktopGplArgs`. Neither function exists any more, and the GPL flavour is refused for every target, including desktop. The property KDoc at line 61 also still says desktop targets honour the GPL flavour. A reader is sent looking for a place where those filters supposedly ship. The user-facing docs (`docs/filtering.md:40`, `docs/platforms.md:99`) already say correctly that no profile carries them.

**Evidence:**

```kotlin
// KiteFFmpeg/build-plugins/native-build/src/BuildFFmpegTask.kt:422
//   - drawtext needs libfreetype/libharfbuzz → desktopBaseArgs (Android links neither).
// KiteFFmpeg/build-plugins/native-build/src/BuildFFmpegTask.kt:423
//   - eq and boxblur are `deps="gpl"` in FFmpeg's own configure → desktopGplArgs.
// KiteFFmpeg/build-plugins/native-build/src/BuildFFmpegTask.kt:61
/** Licence flavour. Android and iOS are LGPL-only; only desktop targets honour [FFmpegLicense.GPL]. */
// KiteFFmpeg/build-plugins/native-build/src/BuildFFmpegTask.kt:236
require(license != FFmpegLicense.GPL) {
```

A search of `build-plugins` finds no definition of `desktopBaseArgs` or `desktopGplArgs`.

**Failure scenario:** A contributor picking up #222 (drawtext and blur missing from the filter graph) reads this comment, concludes drawtext already ships on desktop through a base profile, and starts from a wrong picture of the build.

**Suggested fix:** Say in the comment that no profile carries `drawtext`, `eq` or `boxblur`, and why (drawtext needs FreeType and HarfBuzz, which no profile links; the other two are GPL-only in FFmpeg and the GPL flavour is refused), point at #222, and correct the KDoc at line 61.

**How to check:** `grep -rn "desktopBaseArgs\|desktopGplArgs" build-plugins` finds only the comment.

#### Rejected during verification

- "The Windows library in the JVM jar bundles zlib, libiconv and winpthreads while the notice says only FFmpeg and dav1d": refuted as a duplicate. Open issue KiteFFmpeg#185 already reports the static libiconv in `kitecodec_jni.dll`, the false "zlib not bundled" claim for Windows, and the missing names in NOTICE, `docs/licensing.md` and `THIRD-PARTY.txt`. The winpthreads part was not proven: the mingw FFmpeg is configured with `--disable-pthreads --enable-w32threads` (`BuildFFmpegTask.kt:637`), so `-lpthread` pulls nothing in unless some archive references it.
- "An audio filter graph longer than about two thousand characters is refused while a video graph of any length works": refuted as a defect. The 2048 byte bound is a stated public contract in `kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/FilterGraph.kt:459` and `:517` ("must fit in 2048 bytes ... refused with [FFmpegError.InvalidArgument]; it is never silently truncated"), so the claim that the caller gets no word about length is false. Lifting the cap with a growing buffer would be a reasonable polish, not a bug.


## 06. Network, adaptive streaming, IO, segment cache

This lane read the DASH manifest parser and its segment planning, the DASH door and its routes, the reader that serves a DASH presentation to FFmpeg as HLS, the live clock, the WebM index reader, the Ktor network reader and its redirect handling, the public `Dash` entry points, the file segment store and its reuse layer, the Web Worker's synchronous HTTP reader, the file URI rule and the playlist address resolver in `kiteplayer-core`. Every finding below was re-traced by hand from the cited lines by a second reader, who tried to refute each one; line numbers and quotes were corrected where they had drifted, and one finding (the WebM index) turned out worse than first reported and was raised. Nothing was built or run, so the timings below are arithmetic from the code, not measurements. Ktor's own redirect code was not available offline, so the redirect header finding rests on Ktor's documented behaviour and is marked plausible. Everything found here concerns KitePlayer alone; nothing in this lane needs a change in KiteFFmpeg.

### NET-01. In a Web Worker a progressive file stops with a stall on any connection slower than about 4.5 Mbit/s

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | high | Confirmed | high | none |

**Second check:** Upheld at high by the second reader. Line corrections: `startReconnect` is at `PlaybackCore.kt:6031`, the KDoc promise at `PlayerConfig.kt:263` and `stallTimeout` at `:268`. The HEAD request at `SyncHttpMediaIo.kt:371` carries the same whole-request timeout. This is the same root cause as WEB-02; post one issue.

**Same root as:** WEB-02. Post these as one issue.

**Where:** `KitePlayer/kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/SyncHttpMediaIo.kt:218`, `:241`, `:249`, `:332`, `KitePlayer/kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/WorkerRuntime.kt:75`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/PlayerConfig.kt:258`

**What happens:** The reader the player uses inside a Web Worker asks for the file in windows that double on every forward read, from 1 MiB up to 16 MiB, and gives each synchronous request the stall timeout (30 seconds by default) as its `xhr.timeout`. A browser's request timeout covers the whole request, body included, so it limits how long one window may take to download, not how long the network may stay silent. Once the window has grown so large that it needs more than 30 seconds at the user's speed, the request is cut off while bytes are still arriving, the reader throws `SourceStalled`, and nothing tries a smaller window. The player's own documentation of the stall timeout promises the opposite.

**Evidence:**
```kotlin
// WorkerRuntime.kt:75
network = defaults.network.copy(ioResolver = SyncHttpResolver(defaults.buffer.stallTimeout)),
// SyncHttpMediaIo.kt:218
nextWindow = if (from == windowEnd && from > 0) (nextWindow * 2).coerceAtMost(MAX_WINDOW_BYTES) else FIRST_WINDOW_BYTES
// SyncHttpMediaIo.kt:249
const val MAX_WINDOW_BYTES = 16L shl 20
// SyncHttpMediaIo.kt:332 (inside the xhrFetch JavaScript)
xhr.timeout = s.timeout;
// SyncHttpMediaIo.kt:241
TIMED_OUT -> throw PlaybackException(PlaybackError.SourceStalled(url, timeout))
```
```kotlin
// PlayerConfig.kt:263, the documentation of BufferPolicy.stallTimeout
// A packet from the source or bytes from the reader start it again, so a slow source that still delivers never stalls.
```
The automatic reconnect in `PlaybackCore.startReconnect` (`kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:6032`) runs only when `config.network.recovery` is set, and its default is null (`PlayerConfig.kt:166`), so in the worker's default configuration the session simply fails. Even with recovery set, the fresh reader grows its window again and meets the same wall.

**Failure scenario:** A page plays a 200 MB MP4 in the worker over a 3 Mbit/s link. The 1, 2, 4 and 8 MiB windows each finish inside 30 seconds; the 16 MiB window needs about 45 seconds, the request is aborted at 30, and playback ends with `SourceStalled` after roughly 15 MiB of the file. At 1.5 Mbit/s it fails at the 8 MiB window, and below about 280 kbit/s the very first 1 MiB window already fails. Each long window also holds the worker's only thread for up to the full 30 seconds.

**Suggested fix:** Size each window from the measured rate (the reader already keeps a `SyncDownloadMeter`) so one request finishes well inside the timeout, for example a few seconds' worth clamped between 256 KiB and 16 MiB; retry a timed-out window once at a quarter of its size before failing; and state in the KDoc that in the worker the timeout bounds one request.

**How to check:** Read `SyncHttpMediaIo.fetch` and the `xhrFetch` script side by side. To see it, serve a large MP4 through a throttled local server (for example Chrome DevTools network throttling at "Fast 3G") to the worker page and watch the request for the 16 MiB window get cancelled at 30 seconds.

### NET-02. A live DASH timeline whose segment lasts under a microsecond hangs the player for ever

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | high | Confirmed | high | none |

**Second check:** Upheld at high by the second reader. The hang needs a live window that starts after zero, which the default two-minute window gives once the presentation is older than two minutes; a window at or before zero hits the 100,000 address limit quickly instead. The walk runs inside the reader's lock, so other requests to that reader block too.

**Where:** `KitePlayer/kiteplayer-adaptive/src/io/github/yuroyami/kiteplayer/network/dash/DashManifest.kt:959`, `:974`, `:982`, `:991`, `:900`, `KitePlayer/kiteplayer-adaptive/src/io/github/yuroyami/kiteplayer/network/dash/DashHlsMediaIo.kt:231`

**What happens:** For a live presentation, the timeline expander walks each `S` entry one segment at a time and jumps over the segments that have already left the live window. The jump is computed in whole microseconds and is skipped when an entry's duration rounds down to zero microseconds. Such an entry still passes the only duration check (`d > 0` in timescale units), so the loop then advances one tenth of a microsecond (or less) per iteration from the start of the timeline toward the live window, which can be about 10^16 iterations away. The template path right above it refuses exactly this case with "degenerate segment duration"; the timeline path does not.

**Evidence:**
```kotlin
// DashManifest.kt:959
fun micros(units: Long) = rescale(units, 1_000_000L, timescale, "a segment time")
// DashManifest.kt:982
val skip = if (behind > 0 && durationMicros > 0) minOf(behind / durationMicros, repeats - repeat) else 0
// DashManifest.kt:900, the template path, which does guard
require(segmentMicros > 0) { "degenerate segment duration" }
```
The only ways out of `while (repeat <= repeats)` (line 974) for a live window are reaching the live edge and the 100,000 URL `require` at line 991, which counts only segments inside the window. For `r="-1"` the repeat count comes from `timescaleUnits(it.edgeMicros)`. `DashHlsMediaIo.playlist` (line 231) runs this through `servedPlan` while it holds the reader's mutex.

**Failure scenario:** A dynamic MPD with `availabilityStartTime="1970-01-01T00:00:00Z"`, a `SegmentTemplate` with `timescale="10000000"` and `<S t="0" d="1" r="-1"/>`. `micros(1)` is 0, the repeat count is about 1.8e16, and the start time grows by one microsecond every ten iterations. The first media playlist FFmpeg asks for never comes back, the demux thread spins on the processor, and every other request to that reader waits on the mutex. Stop cannot interrupt pure computation. With an availability start only an hour ago it is still tens of billions of iterations.

**Suggested fix:** Refuse a timeline entry whose duration is under one microsecond as degenerate, as the template path does (`require(durationMicros > 0)` right after line 972), or compute the skip in timescale units rather than truncated microseconds. A bound on iterations spent outside the window would also cap any other degenerate shape.

**How to check:** Write a unit test that calls `DashManifestParser.timedPlan` on the MPD above with a fixed `nowMicros` and a test timeout; it will not finish.

### NET-03. A WebM DASH file whose header declares one element of about 4 GiB hangs the player for ever

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | high | Confirmed | high | none |

**Second check:** Upheld at high by the second reader, who traced the bytes by hand. Narrowed: the exact endless loop needs the declared size plus the header length to land on a multiple of 2^32. Other sizes past 2 GiB mostly make the reader return null and stop harmlessly, and only a wrapped index that lands inside the buffer re-reads old bytes.

**Where:** `KitePlayer/kiteplayer-adaptive/src/io/github/yuroyami/kiteplayer/network/dash/WebmIndex.kt:51`, `:95`, `:179`, `:201`, `:209`, `KitePlayer/kiteplayer-adaptive/src/io/github/yuroyami/kiteplayer/network/dash/DashHlsMediaIo.kt:711`

**What happens:** The WebM index reader keeps its file position as a 64-bit number but turns `position - base` into a 32-bit index before every read of the buffer. Skipping an element adds its declared size, taken from the file, to the position. When that size is exactly 2^32 minus the element's header length, the truncated index lands back on the same element, so the top-level loop of `layout` reads the same header, skips it, and lands on it again, without end: that loop is `while (true)` with no bound of its own. Other sizes past 2 GiB send the reader back into bytes it already read and make it parse them as new elements. The original report described only the second, milder effect; the first is a hang.

**Evidence:**
```kotlin
// WebmIndex.kt:51 and :95, the top-level loop of layout()
while (true) {
    val child = reader.element() ?: break
    ...
    else -> if (!reader.skip(child)) break
// WebmIndex.kt:179
position += size
// WebmIndex.kt:209 (and :201)
val at = (position - base).toInt()
if (at !in bytes.indices) return null
```
`DashHlsMediaIo.kt:711` calls `WebmIndex.layout(readRange(file, 0L until headEnd, allowShort = true))` on the first bytes a server sends for a `SegmentBase` WebM representation. The inner loops compare 64-bit positions with their element's end and do exit; only the top-level loop has no such bound.

**Failure scenario:** A server answers the head of a WebM representation with a valid EBML header and `Segment`, followed by a `Void` element (ID `0xEC`, one byte) whose size is written as the five-byte number `0x08 FF FF FF FA`, which is 2^32 minus 6. After the skip the position is the element's own start plus 2^32, the truncated index is the element's start, and the loop repeats for ever (the position overflows and wraps silently after about two billion turns and the truncation still lands on the same byte). The playlist request never answers.

**Suggested fix:** In `Reader`, compare the 64-bit difference with `bytes.size` before converting (treat anything at or past the end as the end of the bytes), and in `layout` stop when `reader.position` passes the end of the head or `segmentEnd`.

**How to check:** A unit test that builds the 20-odd bytes above and calls `WebmIndex.layout` with a test timeout; it will not return.

### NET-04. A hostile HLS playlist with one very long segment address ties up the player for minutes to hours while its addresses are resolved

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | medium (was high) | Confirmed | high | none |

**Second check:** Downgraded from high to medium. The quadratic copying in `removeDotSegments` is real on the JVM, Native and Wasm (not on Kotlin/JS), but the cost is bounded, a 100 KB line costs well under a second, and it takes a deliberately built line of several megabytes. The DASH resolver at `DashManifest.kt:1333` already walks an index and is the model for the fix.

**Where:** `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaylistText.kt:317`, `:339`, `:262`, `:208`, `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/HlsTimeOfDay.kt:207`, `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/HlsSwitching.kt:193`, `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/HlsOpening.kt:87`, `KitePlayer/kiteplayer-adaptive/src/io/github/yuroyami/kiteplayer/network/dash/DashManifest.kt:1334`

**What happens:** The shared resolver behind `Playlists.resolve` removes `.` and `..` from a path by repeatedly cutting the front off its input with `substring`, which copies everything that is left. Its cost therefore grows with the square of the path length. Every segment line of every media playlist, and of every live reload, passes through it in `readDatedPlaylist` and in the variant switch's playlist reader, and a playlist may be 16 MiB. The DASH resolver once had the same shape and was rewritten to walk an index for exactly this reason; the core one was not.

**Evidence:**
```kotlin
// PlaylistText.kt:317 onward
private fun removeDotSegments(path: String): String {
    var input = path
    ...
            input.startsWith("../") -> input = input.substring(3)
    ...
                output.append(input, 0, next)
                input = input.substring(next)   // line 339
```
```kotlin
// HlsTimeOfDay.kt:207
segments += DatedSegment(sequence, Playlists.resolve(base, line), length, date)
// HlsSwitching.kt:193
val address = Playlists.resolve(base, line)
// HlsOpening.kt:87
internal const val MAX_PLAYLIST_BYTES: Int = 16 * 1024 * 1024
```
```kotlin
// DashManifest.kt:1331, the DASH resolver's own note
// RFC 3986, section 5.2.4, walked with an index rather than by cutting the front off the input, so the cost follows the length of [path].
```
`playlistAddress` (line 208) hands any `http` or `https` base to `resolveUriReference` (line 262), which calls `removeDotSegments`.

**Failure scenario:** A server answers an HLS media playlist whose one segment line is `a/` repeated two million times (4 MB, well under the cap). Resolving it copies the remaining text once per segment, about 4e12 characters in all, which is minutes of processor time on a fast desktop JVM and longer on a phone or in Kotlin/Native; a line near the 16 MiB cap is about 6e13 characters, an hour or more. The work happens on the thread that reads the playlist, no read is waiting on the network, so the stall timeout never fires and stop cannot interrupt it. (The original report said a 100 KB line costs seconds; by the same arithmetic it is a fraction of a second, so the problem needs a line in the megabytes.)

**Suggested fix:** Port the index-walking `removeDotSegments` from `DashManifestParser` into `PlaylistText.kt`, or make both call one shared implementation in `kiteplayer-core`, and add a test that resolves a path of a few megabytes in bounded time.

**How to check:** Compare `PlaylistText.kt:317-343` with `DashManifest.kt:1334-1366`. A JVM test that calls `Playlists.resolve("https://a.example/x.m3u8", "a/".repeat(200_000))` takes visibly longer than one with 20,000 repeats, by about a hundred times rather than ten.

### NET-05. Dash.mediaItemFor resolves a redirected manifest's relative segment addresses against the address before the redirect

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | medium | Confirmed | high | none |

**Where:** `KitePlayer/kiteplayer-network/src/io/github/yuroyami/kiteplayer/network/dash/Dash.kt:76`, `:168`, `:171`, `:239`, `:241`, `KitePlayer/kiteplayer-adaptive/src/io/github/yuroyami/kiteplayer/network/dash/DashDoor.kt:152`, `:170`

**What happens:** The public `Dash.manifest` and `Dash.mediaItemFor` fetch the manifest with `fetchBounded`, which returns only the body, and then parse it with the address the caller passed as its base. DASH (ISO/IEC 23009-1) and dash.js resolve relative `BaseURL`s and templates against the address the manifest was finally served from after HTTP redirects, and so does the player's own automatic transport, which uses the reader's `location`. So the same address plays through automatic discovery and breaks through the public door whenever the manifest sits behind a redirect.

**Evidence:**
```kotlin
// Dash.kt:168-171
val body = fetchBounded(client, mpdUrl, maxManifestBytes, "the manifest at $shown", readerPolicy, redirects) { status -> ... }
DashDoor.manifest(body, mpdUrl, policy, maxManifestBytes)
// Dash.kt:84, fetchBounded returns only the bytes
): ByteArray = coroutineScope {
```
```kotlin
// DashDoor.kt:170-176, the automatic path
val location = io.location
...
val mpdUrl = requireNotNull(location) { "a reader with no address cannot be a DASH manifest" }
```
`DashDoor.kt:152` documents that the automatic path resolves against "the manifest came from after its redirects". `mediaItemFor` then builds its route from that manifest (line 241), so every segment and the live refetch inherit the wrong base.

**Failure scenario:** `Dash.mediaItemFor("https://short.example/live", client)` where the server answers 302 to `https://origin-3.example/ch1/manifest.mpd` with `media="seg-$Number$.m4s"`. The segments resolve to `https://short.example/seg-1.m4s` and fail with 404, while the same address handed to the player directly plays.

**Suggested fix:** Have `fetchBounded` return the final request address (`response.call.request.url`) beside the body, parse with it in `Dash.manifest`, and use it as the base for the live refetch too.

**How to check:** Read `Dash.kt:155-172`. A server-backed test in `test@serverBacked` that redirects `/m` to `/sub/manifest.mpd` with relative segment names, then compares the segment addresses of `Dash.manifest` with those of the automatic transport.

### NET-06. The public DASH segment plan drops segments of a repeating timeline when the template has a presentation time offset

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | medium | Confirmed | high | none |

**Where:** `KitePlayer/kiteplayer-adaptive/src/io/github/yuroyami/kiteplayer/network/dash/DashManifest.kt:673`, `:727`, `:728`, `:963`, `KitePlayer/kiteplayer-adaptive/src/io/github/yuroyami/kiteplayer/network/dash/DashDoor.kt:274`

**What happens:** For an `S` entry with `r="-1"` and no following entry, `segmentPlan` stops repeating at the Period's duration in timescale units, but compares that with the entry's `t`, which counts from `presentationTimeOffset`. The HLS path's `expandTimeline` adds the offset to the Period end; `segmentPlan` does not. So the public `DashManifestParser.segmentPlan`, and the one-stream route that uses it, list too few segments, or a single one when the offset is larger than the Period.

**Evidence:**
```kotlin
// DashManifest.kt:727-728, segmentPlan
val untilTime = timeline.getOrNull(index + 1)?.t
    ?: durationMicros?.let { rescale(it, template.timescale, 1_000_000L, "the period in timescale units") }
// DashManifest.kt:963, expandTimeline, which gets it right
?: totalMicros?.let { plus(timescaleUnits(it), presentationTimeOffset, "the period end") }
```
`DashDoor.kt:274` builds `SegmentsRoute` from `DashManifestParser.segmentPlan(...)`. That route is reached only for a single-Period on-demand representation the HLS path does not carry, so most users meet this through the public `segmentPlan` call rather than playback.

**Failure scenario:** A static MPD with `timescale="90000"`, `presentationTimeOffset="900000"`, `<S t="900000" d="180000" r="-1"/>` and a 60 second Period. The span is 5,400,000 minus 900,000, so `segmentPlan` returns 25 segments instead of 30 and the last 10 seconds are missing. With a 10 minute offset, common in live-to-on-demand recordings, the span is negative and it returns one segment.

**Suggested fix:** Add `template.presentationTimeOffset` to the Period end as `expandTimeline` does, or build `segmentPlan` on `timedPlan` so the two cannot disagree; add a test with a non-zero offset.

**How to check:** A unit test calling `DashManifestParser.segmentPlan` on the MPD above and counting `mediaUrls`.

### NET-07. A live DASH clock that fails to answer once is never asked again, and the device clock rules the whole session

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | design | medium | Confirmed | high | none |

**Where:** `KitePlayer/kiteplayer-adaptive/src/io/github/yuroyami/kiteplayer/network/dash/DashLiveClock.kt:16`, `:29`, `:33`, `:71`, `KitePlayer/kiteplayer-adaptive/src/io/github/yuroyami/kiteplayer/network/dash/DashHlsMediaIo.kt:136`

**What happens:** The live clock measures how far the device's clock is from the packager's time once, on the first playlist load, and keeps the answer. When every `UTCTiming` source fails at that moment (a timeout, a rate limit, a server error), the measurement returns 0 and that 0 is stored exactly like a real measurement, so the device's clock is trusted for the rest of the session. There is no retry and no later resync, and the clock is built from the `UTCTiming` list of the manifest at the open only, so timing elements in a refreshed manifest are never read. The KDoc states the read-once rule on purpose, which is why this is filed as a design gap rather than a slip, but the case of a failed first read is not argued there.

**Evidence:**
```kotlin
// DashLiveClock.kt:33
val offset = offsetMicros ?: offset().also { offsetMicros = it }
// DashLiveClock.kt:71, after every source failed and was logged
return 0L
// DashHlsMediaIo.kt:136-137
private val clock = DashLiveClock(
    manifest.utcTimings,
```

**Failure scenario:** A television box whose clock is 40 seconds slow plays a live MPD that names an `http-xsdate` time server, and the first request to that server times out. The offset is stored as 0, the live window is computed on the slow clock, and playback sits at least 40 seconds behind the edge for the whole session. On a device 20 seconds fast, the playlists name segments that do not exist yet, which answer 404 and make FFmpeg skip or stall, for the whole session.

**Suggested fix:** Keep `offsetMicros` null after a failed measurement and try again on a later load with a backoff; read the `UTCTiming` of each refreshed manifest; and resync now and then (dash.js resyncs on manifest refresh). The `Date` header of the manifest response is a reasonable last resort before the device clock.

**How to check:** Read `DashLiveClock.nowMicros`. A unit test with a `fetchText` that throws on the first call and answers on the second shows the second `nowMicros()` still on the device clock.

### NET-08. A live DASH stream with a long time-shift window fails to open past the URL budget and rewrites its whole window on every reload

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | design | medium | Confirmed | medium | KitePlayer#677 |

**Where:** `KitePlayer/kiteplayer-adaptive/src/io/github/yuroyami/kiteplayer/network/dash/DashManifest.kt:272`, `:278`, `:829`, `:913`, `:920`, `:991`, `:1054`, `:1060`, `KitePlayer/kiteplayer-adaptive/src/io/github/yuroyami/kiteplayer/network/dash/DashHlsMediaIo.kt:255`

**What happens:** Each live media playlist FFmpeg reloads (about once per target duration, for each track) expands and resolves every segment of the manifest's whole `timeShiftBufferDepth` again and writes all of them as playlist text. A window larger than the plan budgets (100,000 URLs, or 16 Mi characters, where each segment is charged its base URL plus its reference, and the base is the full manifest URL with its query when no `BaseURL` is given) is refused with `IllegalArgumentException` instead of being trimmed toward the live edge.

**Evidence:**
```kotlin
// DashManifest.kt:829
return LiveWindow(edge - (manifest.timeShiftBufferDepthMicros ?: DEFAULT_LIVE_WINDOW_MICROS), edge)
// DashManifest.kt:913 and :920, templatePlan
first = if (window.fromMicros < 0) 0 else window.fromMicros / segmentMicros
budget.reserve((end - first).coerceAtLeast(0))
// DashManifest.kt:1055, UrlBudget.reserve
if (count > maxUrls - urls) throw IllegalArgumentException("$what needs more than $maxUrls URLs")
// DashManifest.kt:1060, each URL charged
charge(base.url.length.toLong() + reference.length)
// DashHlsMediaIo.kt:255, on every load, with no cache of the expanded plan
val plan = servedPlan(track, manifest, now)
```

**Failure scenario:** A broadcaster's MPD has `timeShiftBufferDepth="PT72H"` and 2 second `$Number$` segments: 129,600 segments, so the first playlist load throws "the segment plan needs more than 100000 URLs" and the channel never opens. With a 500 character signed manifest URL the character budget refuses windows above about 17 hours. A 6 hour window does play, but every 2 seconds each of three tracks resolves 10,800 URLs and hands FFmpeg a playlist of about a megabyte to parse again.

**Suggested fix:** Clamp the listed window to what the budgets allow, keeping the segments nearest the live edge (FFmpeg starts near the edge anyway), and cache the expanded plan between reloads, adding only the segments that became available.

**How to check:** A unit test calling `timedPlan` on a dynamic MPD with `timeShiftBufferDepth="PT72H"` and `duration` of 2 seconds at a fixed `nowMicros`.

### NET-09. An item's custom headers follow a redirect to another host, though the docs say they go only to the item's own origin

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | risk | medium | Plausible | medium | none |

**Where:** `KitePlayer/kiteplayer-network/src/io/github/yuroyami/kiteplayer/network/KtorMediaIo.kt:82`, `:571`, `:816`, `:991`, `:1113`, `:1139`, `KitePlayer/kiteplayer-network/src/io/github/yuroyami/kiteplayer/network/KtorMediaIoResolverProvider.kt:27`, `KitePlayer/kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/SyncHttpMediaIo.kt:177`

**What happens:** The reader keeps an item's own headers off related addresses of other origins (`RelatedRequests.headersFor`), and its documentation promises they go only to the item's scheme, host and port. But the headers are set on the request itself, and when that request is redirected, Ktor's redirect handling (the automatic transport passes no redirect rule of its own) builds the next request from the previous one, headers included, and removes only `Authorization` when the host changes. A browser's request in the worker behaves the same way. So an app's token header, an explicit `Cookie` header or a signed `Referer` reaches whatever host the item, or a same-origin segment, redirects to.

**Evidence:**
```kotlin
// KtorMediaIo.kt:82-84
// The item's own headers go only to the scheme, host and port of the item's address, because the addresses come from the media.
// KtorMediaIo.kt:571, the item request
requestHeaders.forEach { (key, value) -> header(key, value) }
// KtorMediaIo.kt:991, the code itself relies on Ktor dropping only the login
// The header is one of the item's own, so it goes only to the item's scheme, host and port, and Ktor drops it on a redirect to another host.
// KtorMediaIoResolverProvider.kt:27
KtorMediaIo.open(uri, null, headers, HttpReaderPolicy(), redirects = null, ...)
```
`KtorMediaIoLoginTest.aRedirectToAnotherHostDoesNotCarryTheLogin` checks `Authorization` only. Ktor's sources were not available offline, so the claim that its `HttpRedirect` plugin copies other headers rests on its documented and long-standing behaviour.

**Failure scenario:** An app plays `MediaItem("https://api.example.com/play/42", headers = mapOf("X-Api-Key" to secret))`. The API answers 302 to `https://cdn.thirdparty.net/v/42.m3u8`. Ktor follows it with `X-Api-Key` attached, so the third-party CDN receives and can log the app's key, contrary to the documented promise.

**Suggested fix:** Either attach a redirect rule to item requests that removes all of the item's own headers (not only `Authorization`) when a hop leaves the item's origin, or correct the KDoc at both places to say redirects carry them. Add a server-backed test with a custom header across a cross-host redirect.

**How to check:** Copy `KtorMediaIoLoginTest.aRedirectToAnotherHostDoesNotCarryTheLogin`, send `X-Test: 1` as an item header instead of a login, and record headers on the second host.

### NET-10. The Web Worker reader can splice the bytes of two versions of a file into one stream

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | risk | medium | Plausible | medium | none |

**Where:** `KitePlayer/kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/SyncHttpMediaIo.kt:158`, `:164`, `:334`, `:352`, `:353`, `:409`

**What happens:** Each window is an independent range request with no `If-Range`, no comparison of the entity tag, and no check that the stated total size stayed the same; a new `Content-Range` total simply replaces the old one. The Ktor reader refuses exactly this case (it sends `If-Range` with the first entity tag and fails the read when the tag or size changes). The worker reader also accepts a 206 whose range starts after the byte it asked for, and then reads the buffer at a negative offset, which JavaScript's `subarray` counts from the end.

**Evidence:**
```javascript
// SyncHttpMediaIo.kt:334, the only header besides the item's
xhr.setRequestHeader('Range', 'bytes=' + from + '-' + (from + count - 1));
// SyncHttpMediaIo.kt:352-353
s.start = first ? Number(first[1]) : from;
if (total) s.total = Number(total[1]);
```
```kotlin
// SyncHttpMediaIo.kt:158-164, no check that position >= windowStart after the fetch
if (position < windowStart || position >= windowEnd) {
    fetch(position)
    if (position >= windowEnd) return -1
}
val bytes = xhrSlice(state, (position - windowStart).toInt(), count)
```
Compare `KtorMediaIo.kt:578` (`IfRange`) and `:666` (refusal of a changed tag).

**Failure scenario:** An on-demand file is replaced at the same address during playback, or two CDN edges hold different encodes. The first windows come from version A and the next from version B, and the demuxer reads B's bytes at A's offsets, giving corrupt pictures or a demux error instead of a clean failure that the engine could answer by reopening.

**Suggested fix:** Keep the first answer's strong entity tag (or `Last-Modified`) and total size, send `If-Range` on later windows, and fail with a typed error when the tag or size changes or when `Content-Range` does not start at the requested byte.

**How to check:** Read `xhrFetch`. A worker browser test whose test server changes the file's bytes and `ETag` between two range requests would show the splice; such a request kind goes into `scripts/web-tests/probe.mjs`.

### NET-11. A stored segment cut short by a power loss turns the segment store off on every later play of that item, and is never removed

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | medium | Plausible | medium | none |

**Where:** `KitePlayer/kiteplayer-network/src/io/github/yuroyami/kiteplayer/network/FileSegmentStore.kt:275`, `:277`, `:282`, `:350`, `KitePlayer/kiteplayer-network/src/io/github/yuroyami/kiteplayer/network/SegmentReuse.kt:47`, `:303`, `:352`, `KitePlayer/kiteplayer-network/src@jvmAndAndroid/io/github/yuroyami/kiteplayer/network/StoreFiles.jvmAndAndroid.kt:38`, `:45`

**What happens:** A stored span is written to a temporary file, closed without a flush to disk, and renamed to a name that carries its start and length. After a power loss or kernel crash the rename can survive while the data does not (on ext4 and F2FS with delayed allocation, a new file renamed into place can come back empty), leaving a short or empty file. Recovery trusts the length in the file name and never compares it with the file's real size. A later read past the real end returns -1, trips `check(read > 0)`, and the reuse layer turns that into "stop using the store for this open" with a `SegmentStoreFailed` warning, without removing the bad entry. Every later open of the item meets the same span again.

**Evidence:**
```kotlin
// FileSegmentStore.kt:276-282, recover()
val start = range.substringBefore('-').toLongOrNull()
val length = range.substringAfter('-', "").toLongOrNull()
...
    resource.spans += Span(start, length, file)
    resource.bytes += length
// FileSegmentStore.kt:350, Handle.read
check(read > 0) { "a stored span ended before its length" }
// SegmentReuse.kt:352-360, StoredSpans.guarded: closes the entry and calls reuse.failed, no store.remove(name)
// StoreFiles.jvmAndAndroid.kt:45, create(): a plain FileOutputStream, closed with no sync
```

**Failure scenario:** A phone dies while the player is filling the store. On the next launch the user replays the film: the first segment whose span file came back empty fails its stored read, the open reports `SegmentStoreFailed` and fetches everything else from the network, and the same happens on every later play of that film. On a device that is offline at that moment, segments the store did hold intact are no longer offered, so playback fails where it could have gone on.

**Suggested fix:** In `recover()`, compare each span file's real size with the length in its name and drop the span when they differ; when a span read fails, remove that entry (`store.remove(name)`) rather than only giving up on the store for the open; optionally sync the span file before the rename.

**How to check:** A unit test that creates a store, writes a span, truncates the span file on disk to zero bytes, opens a new store on the same directory and reads the span.

### NET-12. Dash.mediaItemFor says the HLS path holds no segment whole, but joined Periods hold each segment whole up to 64 MiB

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | docs | low | Confirmed | high | none |

**Where:** `KitePlayer/kiteplayer-network/src/io/github/yuroyami/kiteplayer/network/dash/Dash.kt:212`, `:213`, `KitePlayer/kiteplayer-adaptive/src/io/github/yuroyami/kiteplayer/network/dash/DashHlsMediaIo.kt:388`, `KitePlayer/kiteplayer-adaptive/src/io/github/yuroyami/kiteplayer/network/dash/DashMediaIo.kt:90`

**What happens:** For a manifest of several Periods every media segment is fetched whole, rewritten onto one timeline (MP4 fragments rewritten, WebM retracked, MPEG-TS shifted) into a second copy, and handed to FFmpeg from memory. The ceiling is the fixed 64 MiB constant, not the caller's `maxSegmentBytes`. A single-Period presentation does stream its segments, so the statement holds only for that case.

**Evidence:**
```kotlin
// Dash.kt:212-213
// [maxSegmentBytes] limits the segments of that stream; the HLS path reads segments as streams and holds none whole.
// DashHlsMediaIo.kt:388, serve() for a joined Period's media piece
val bytes = fetch(piece.url, piece.range, MAX_SEGMENT_BYTES)
// DashMediaIo.kt:90
public const val MAX_SEGMENT_BYTES: Long = 64L shl 20
```

**Failure scenario:** An app sizing its memory budget from this KDoc plays an advertisement-stitched multi-Period 4K presentation: each 20 MB segment is held whole and copied once more by the rewrite, for audio and video at once, and a segment of up to 64 MiB is accepted whatever `maxSegmentBytes` the app passed.

**Suggested fix:** State in the KDoc that joined Periods read each segment whole, up to 64 MiB; optionally pass `maxSegmentBytes` through to `DashHlsMediaIo` and use it for joined pieces.

**How to check:** Read `DashHlsMediaIo.serve` against `Dash.kt:208-213`.

### NET-13. A Windows network-share file address written with its server as host opens the wrong path

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | low | Confirmed | medium | none |

**Where:** `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/uri/FileUri.kt:19`, `:33`, `:35`

**What happens:** `FileUri.localPath` turns `file://host/path` into `/path`, dropping the host, on the reasoning that the local host is the only one a player can read. On Windows that is false: a network share is a local path there, and Java's `Path.toUri()` and Windows itself write `\\nas\films\a.mkv` as `file://nas/films/a.mkv`. The share's server is dropped and the share name becomes a root folder, so the file is not found.

**Evidence:**
```kotlin
// FileUri.kt:33-35
if (rest.startsWith("//")) {
    rest = rest.substring(2)
    if (!rest.startsWith("/")) rest = rest.substringAfter('/', "").let { "/$it" }
}
// FileUri.kt:19-20, the KDoc
// A `file://host/path` names `/path`, since the local host is the only one a player can read.
```

**Failure scenario:** A desktop app on Windows plays `MediaItem(Paths.get("\\\\nas\\films\\a.mkv").toUri().toString())`, which is `file://nas/films/a.mkv`; the player opens `/films/a.mkv` and fails with a missing file.

**Suggested fix:** For a host that is neither empty nor `localhost`, return the UNC form `//host/path` (which Windows file calls and FFmpeg's file reader accept on Windows) instead of discarding the host, and adjust the KDoc.

**How to check:** A unit test: `FileUri.localPath("file://nas/films/a.mkv")` returns `/films/a.mkv` today.

#### Opportunities

### NET-14. A DASH presentation with video always streams its highest bitrate audio, whatever the network allows

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | opportunity | low | Confirmed | medium | none |

**Where:** `KitePlayer/kiteplayer-adaptive/src/io/github/yuroyami/kiteplayer/network/dash/DashHls.kt:147`, `:148`, `:159`

**What happens:** The HLS stand-in offers each audio adaptation set as one rendition made from its highest bandwidth representation, so stepping down the video variants never lowers the audio. dash.js and Media3 adapt audio within a set.

**Evidence:**
```kotlin
// DashHls.kt:147-148
audio = audioSets.map { (setIndex, set) ->
    track(DashHlsRole.Audio, setIndex, set, set.representations.indices.maxBy { set.representations[it].bandwidth })
// DashHls.kt:159, the same for the other sets of an audio-only presentation
track(DashHlsRole.Audio, otherIndex, other, other.representations.indices.maxBy { other.representations[it].bandwidth })
```

**Failure scenario:** An audio set offers 64, 128 and 384 kbit/s representations. On a 700 kbit/s link the player drops the video to its lowest variant but keeps pulling the 384 kbit/s audio, so more than half the link goes to sound and the picture stays at the floor.

**Suggested fix:** Write one audio group per bandwidth tier and pair the low video variants with a lower audio representation through their `AUDIO` group, as Apple's authoring guidelines do for multi-bitrate audio.

**How to check:** Read `DashHls.presentation`; the master playlist written for a manifest with three audio representations names only one audio rendition per set.

#### Rejected during verification

None. Two corrections were made instead of rejections: the long-address resolver finding kept its severity but its timings were recomputed (a 100 KB line costs a fraction of a second, a multi-megabyte line costs minutes to hours), and the WebM index finding was raised from a low risk to a high defect because the same truncation makes its top-level loop spin for ever.


## 07. Subtitles, captions, libass, charset and text

This lane covered the external subtitle read path (charset detection, byte-order marks, UTF-8 validation and the East Asian guess), the SubRip, WebVTT, TTML, ASS, LRC, CEA-608, CEA-708 and teletext readers, the cue table, how the overlay is published and withdrawn, the typeset lane, the libass C driver and its JNI, cinterop and wasm bindings, the desktop JNI loader, font loading, and the caption and image subtitle helpers in KiteFFmpeg. Every finding below was re-read at the cited lines by a second reader who tried to refute it, and the HLS and caption claims were checked against the FFmpeg 9.0.2 source the build vendors. Nothing was built or run, so the cost figures are estimates. Not covered: subtitle seeking and delay stepping, where the secondary track is placed, how a gapless join meets the typeset lane, the platform rasterizers in depth, and how libass behaves on a script of 70,000 events.

### SUB-01. Starting libass on desktop Linux can delete another folder's files through a link planted in the shared temp folder

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | high | Confirmed | high | none |

**Second check:** Upheld at high by the second reader, with the lines confirmed (43, 90 to 96, and 94 for the delete). Added: the victim cannot write into the attacker's folder, so the cleanup never removes the trap and it fires again on every later launch.

**Where:** `KitePlayer/kiteplayer-libass/src@jvm/io/github/yuroyami/kiteplayer/libass/LibassJniLoader.kt:43`, `KitePlayer/kiteplayer-libass/src@jvm/io/github/yuroyami/kiteplayer/libass/LibassJniLoader.kt:90-96`

**What happens:** When the desktop player first needs libass and finds no copy on the library path, it unpacks the copy bundled in its jar into the system temp folder. It then tidies up by deleting any other folder in that temp folder whose name starts with `kiteplayer-libass-jni` and that is more than an hour old. It deletes with Kotlin's `File.deleteRecursively`, which follows a symbolic link to a folder and deletes what is inside the folder it points at. On Linux the temp folder is `/tmp`, which every account on the machine can write to, so anyone can create a folder with that name and put a link inside it. KiteFFmpeg already learned this the hard way: its own build code says `File.deleteRecursively` "follows a link to a directory and deletes what it points at, which once emptied a linked FFmpeg clone (#162)".

**Evidence:**

```kotlin
// KitePlayer/kiteplayer-libass/src@jvm/.../LibassJniLoader.kt:43
val directory = extractBundle(File(System.getProperty("java.io.tmpdir")), files)
```

```kotlin
// KitePlayer/kiteplayer-libass/src@jvm/.../LibassJniLoader.kt:90-96
temp.listFiles()?.forEach { other ->
    if (other.isDirectory && other.name.startsWith(EXTRACT_PREFIX) && other != directory &&
        nowMillis - other.lastModified() > STALE_AFTER_MILLIS
    ) {
        runCatching { other.deleteRecursively() }
    }
}
```

```kotlin
// KiteFFmpeg/build-plugins/native-build/src/BuildFFmpegTask.kt:1137-1139
 * Deletes a scratch tree without following a symbolic link anywhere in it, so a link inside
 * takes only itself with it. `File.deleteRecursively` follows a link to a directory and
 * deletes what it points at, which once emptied a linked FFmpeg clone (#162).
```

The path is `loadLibassJni` (line 20), reached when the libass provider creates its first typesetter, then `extractBundle`, then the cleanup loop. Linux's `fs.protected_symlinks` does not help here: it stops a link that sits directly in the sticky `/tmp`, but the planted link sits one level down inside an ordinary folder the other account owns. macOS and Windows give each user a private temp folder, so the hole is a Linux one.

**Failure scenario:** On a shared Linux machine another account runs `mkdir /tmp/kiteplayer-libass-jni-x; ln -s /home/alice /tmp/kiteplayer-libass-jni-x/h; touch -d '2 hours ago' /tmp/kiteplayer-libass-jni-x`. Alice later opens a desktop app built on kiteplayer-libass and plays a file with an ASS track. The loader's cleanup walks into the link and deletes every file in her home folder that she is allowed to delete. The verifier downgraded this from critical to high only because it needs a hostile local account; the damage, when it happens, is the loss of a home folder.

**Suggested fix:** Stop walking the shared temp folder. Extract into a folder only this user can reach, such as a user cache folder created with mode 0700 and checked to be owned by the user, and clean up only inside that root. Delete with `java.nio.file.Path.deleteRecursively` from `kotlin.io.path`, which does not follow links, as KiteFFmpeg's `deleteScratch` already does, and skip any entry that is a link or that another user owns.

**How to check:** On a Linux machine, as a second user, create the folder and link above pointing at a throwaway folder of files owned by the first user, then run any desktop sample that opens an ASS track as the first user, with no `kiteplayer.libass.jni.path` set. The throwaway folder comes back empty.

### SUB-02. On desktop Linux another local user can make the player load their own code in place of the libass adapter

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | high | Plausible | medium | none |

**Second check:** Upheld at high by the second reader. The read and skip is at line 72 and the hash at lines 59 to 65. The window between the check and `System.load` includes the whole `/tmp` cleanup loop, which makes the race wider than first described, and an attacker folder writable by everyone gives a second route. The denial half (libass never starts for that user) is certain, not plausible.

**Where:** `KitePlayer/kiteplayer-libass/src@jvm/io/github/yuroyami/kiteplayer/libass/LibassJniLoader.kt:43-44`, `KitePlayer/kiteplayer-libass/src@jvm/io/github/yuroyami/kiteplayer/libass/LibassJniLoader.kt:65-75`

**What happens:** The bundled adapter is unpacked to `/tmp/kiteplayer-libass-jni-<tag>`, where the tag is a hash of the jar's own library bytes, so anyone who downloads the published jar can work out the folder name in advance. The loader uses the folder if it already exists, without asking who owns it or who may write to it, skips writing when the file inside already has the right bytes, and then loads that file into the process. KiteFFmpeg's own loader does not have this problem, because it unpacks into a fresh random folder from `Files.createTempDirectory` (`KiteFFmpeg/kiteffmpeg/src@jvm/io/github/yuroyami/kiteffmpeg/JniLibrary.jvm.kt:51`).

**Evidence:**

```kotlin
// KitePlayer/kiteplayer-libass/src@jvm/.../LibassJniLoader.kt:65-72
val tag = digest.digest().take(12).joinToString("") { ... }
val directory = File(temp, "$EXTRACT_PREFIX-$tag")
directory.mkdirs()
...
if (target.isFile && target.length() == bytes.size.toLong() && target.readBytes().contentEquals(bytes)) continue
```

```kotlin
// KitePlayer/kiteplayer-libass/src@jvm/.../LibassJniLoader.kt:44
System.load(File(directory, fileName).absolutePath)
```

**Failure scenario:** Another account creates `/tmp/kiteplayer-libass-jni-<tag>` before the victim first runs that build, owns it, and places a byte-identical copy of the real library in it. The victim's launch reads the file, sees matching bytes and skips writing. Because the attacker owns the folder, they can rename a different library into place between that read and `System.load`, for example by watching the file with inotify, and their code then runs inside the victim's player. Even without winning the race, an attacker-owned folder that the victim cannot write to makes `Files.createTempFile` at line 75 throw whenever the bytes differ, so libass never loads for that user. This is marked plausible because the swap is a race that was not attempted.

**Suggested fix:** The same private folder as SUB-01, with its owner and mode checked before anything in it is loaded. If a shared, reusable folder is kept, refuse one the current user does not own or that others can write to.

**How to check:** Read lines 58-89 and note that nothing checks the folder's owner or mode. To see the denial half, as a second user create the tagged folder with mode 0755 and an empty file of the library's name, then open an ASS track as the first user and watch the typesetter fail to start.

### SUB-03. HLS WebVTT subtitles ignore X-TIMESTAMP-MAP, so on most packaged streams every line shows at the wrong time

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| Both | defect | high | Plausible | medium | none |

**Second check:** Upheld at high by the second reader, who also grepped the vendored FFmpeg, KitePlayer and KiteFFmpeg for `TIMESTAMP-MAP` and found nothing. One claim is narrowed: "most packaged streams" is too strong. The error is zero when the map's LOCAL already equals the MPEG-TS time, about 0.1 s with Shaka's default, about 10 s with Apple's tools, and arbitrary on live streams. Upstream FFmpeg and therefore mpv share this gap, while VLC, ExoPlayer, hls.js and Safari apply the map. Only text WebVTT segments are affected; fMP4 wvtt and stpp are not.

**Where:** `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/KiteFFmpegSource.kt:783-786`, `KitePlayer/kiteplayer-adaptive/src/io/github/yuroyami/kiteplayer/network/HlsTtml.kt:129`, FFmpeg 9.0.2 `libavformat/hls.c:2173-2241` and `libavformat/webvttdec.c:95` as vendored by `KiteFFmpeg/build-plugins/native-build/src/BuildFFmpegTask.kt`

**What happens:** The HLS specification (RFC 8216, section 3.5) says each WebVTT subtitle segment carries a header line `X-TIMESTAMP-MAP=MPEGTS:m,LOCAL:l` that ties the cue clock to the video clock: local time `l` in the cues is media time `m / 90000` seconds. Nothing in either repository reads that line, and FFmpeg's HLS reader does not either: it opens each subtitle segment with the plain WebVTT reader, which skips the header block. The player then shifts every stream, cues included, by the one start time of the whole container, which is the video's first timestamp. Cues written on the WebVTT local clock therefore move by the video's start offset.

**Evidence:**

```c
// FFmpeg 9.0.2 libavformat/hls.c:2201-2203 (init_subtitle_context)
in_fmt = av_find_input_format("webvtt");
av_dict_copy(&opts, c->seg_format_opts, 0);
ret = avformat_open_input(&pls->ctx, current_segment(pls)->url, in_fmt, &opts);
```

```c
// FFmpeg 9.0.2 libavformat/webvttdec.c:95
        /* ignore header chunk */
```

```kotlin
// KitePlayer/kiteplayer-ffmpeg/src/.../KiteFFmpegSource.kt:786
fun mapTimestamp(micros: Long?): Pts? = micros?.let { Pts(it - containerStartMicros) }
```

```kotlin
// KitePlayer/kiteplayer-adaptive/src/.../HlsTtml.kt:129
if (looksLikeWebVtt(bytes)) return bytes.decodeToString()
```

A search of both repositories and of the vendored FFmpeg source for `TIMESTAMP-MAP` finds nothing. In `hls.c` the per-playlist `ts_offset` (lines 2989-3012) only steers the catch-up seek of a playlist that joins late; it never shifts a packet's timestamps. KitePlayer's own TTML stand-in passes a segment that is already WebVTT through untouched, and plain WebVTT subtitle playlists go straight to FFmpeg.

**Failure scenario:** A stream packaged the common way has MPEG-TS video starting at PTS 900000 (10 s) and subtitle segments headed `X-TIMESTAMP-MAP=MPEGTS:900000,LOCAL:00:00:00.000`. A cue at local 00:00:12.000 belongs at position 12 s. The container starts at 10 s, so the player shows the cue at 2 s, ten seconds before the speech, and every line of the film is ten seconds early. Live streams, whose MPEG-TS clocks are usually hours in, are off by hours and show nothing. The project's HLS subtitle fixtures are made by FFmpeg, which writes no map and starts near zero, so the existing tests cannot see this. It is marked plausible because no stream with a nonzero map has been played.

**Suggested fix:** Read the map from each WebVTT segment and shift its cues by `m / 90000 - l` seconds, minding the 33-bit wrap of MPEG-TS time. The fix fits best in KiteFFmpeg as an `hls.c` patch beside `0004-hls-keep-the-subtitle-cue-on-screen`, which helps every consumer and needs no knowledge of the player; the alternative is a KitePlayer stand-in for WebVTT subtitle playlists that rewrites segment times, the way `HlsTtmlMediaIo` already serves TTML ones. Either way, add a fixture with a nonzero map and assert cue times against the picture.

**How to check:** Take any FFmpeg-made HLS fixture with WebVTT subtitles, offset the video by 10 s with `-output_ts_offset 10`, add `X-TIMESTAMP-MAP=MPEGTS:900000,LOCAL:00:00:00.000` to each `.vtt` segment, and compare when the first cue appears with the same file played in Safari or hls.js.

### SUB-04. A UTF-8 subtitle file with a cut last character or one stray byte is shown as mojibake on every line

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | medium | Confirmed | high | none |

**Where:** `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/SubtitleTextDecoder.kt:215-217`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/SubtitleTextDecoder.kt:380-405`

**What happens:** A file without a byte-order mark is read as UTF-8 only if every byte of it validates strictly. A file whose last character was cut in half, or that holds a single byte from another encoding, fails as a whole and goes to the legacy guess, which reads the UTF-8 lead and trail bytes of every line as Cyrillic, Latin or East Asian characters. The whole file becomes mojibake because of one byte.

**Evidence:**

```kotlin
// KitePlayer/kiteplayer-core/src/.../SubtitleTextDecoder.kt:215-217
if (isValidUtf8(bytes)) {
    return DecodedSubtitleText(bytes.decodeToString(), "UTF-8", confident = true)
}
```

```kotlin
// KitePlayer/kiteplayer-core/src/.../SubtitleTextDecoder.kt:390-394
// 0xC0/0xC1 are overlong two-byte leads and 0x80..0xBF cannot lead at all.
else -> return false
}
if (i + extra >= bytes.size) return false
```

Everything after line 217 is the single-byte and East Asian guess; nothing weighs "almost all of this is valid UTF-8".

**Failure scenario:** A Russian UTF-8 `.srt` whose download ended inside its last Cyrillic letter, or that has one windows-1252 en dash pasted into a line, opens as text like `РџСЂРёРІРµС‚` on every cue, or as GBK hanzi, with a "charset guessed" warning, although all but one byte of the file is valid UTF-8.

**Suggested fix:** Accept a truncated final sequence as valid. When the invalid sequences are a tiny share of the valid multi-byte ones, decode as UTF-8 with U+FFFD for the bad bytes and report the result as not confident, rather than reading the whole file through a legacy table.

**How to check:** A unit test that feeds `decodeSubtitleBytes` a valid Russian UTF-8 SubRip file with its final byte removed, or with one `0x96` byte inserted, and asserts the charset is UTF-8.

### SUB-05. The built-in ASS styling draws text the script made transparent, and a colour override makes translucent text opaque

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | medium | Confirmed | high | none |

**Where:** `KitePlayer/kiteplayer-subtitles/src/io/github/yuroyami/kiteplayer/subtitle/AssParser.kt:300-305`, `KitePlayer/kiteplayer-subtitles/src/io/github/yuroyami/kiteplayer/subtitle/AssParser.kt:376-389`

**What happens:** In the built-in ASS tier, which every ASS track gets where libass is absent or refused and which the secondary track always gets, a `\c` or `\1c` colour override replaces the whole colour including its transparency: a six-digit `&HBBGGRR&` has no alpha digits, so it comes out fully opaque. libass changes only the colour and keeps the transparency the style set. The alpha tags `\alpha` and `\1a` to `\4a` are not read at all, so text a script hides stays visible.

**Evidence:**

```kotlin
// KitePlayer/kiteplayer-subtitles/src/.../AssParser.kt:300-305
tag.startsWith("1c") || (tag.startsWith("c") && !tag.startsWith("clip")) -> {
    val v = tag.substringAfter("c")
    assColor(v)?.let { newStyle = newStyle.copy(primaryColor = it) }
}
tag.startsWith("3c") -> assColor(tag.drop(2))?.let { newStyle = newStyle.copy(outlineColor = it) }
tag.startsWith("4c") -> assColor(tag.drop(2))?.let { newStyle = newStyle.copy(shadowColor = it) }
```

```kotlin
// KitePlayer/kiteplayer-subtitles/src/.../AssParser.kt:387
val alpha = 255 - (value shr 24 and 0xFF).toInt()
```

No branch of the tag `when` matches a tag beginning `alpha` or `1a` to `4a`, so they fall to "unknown tags are ignored".

**Failure scenario:** A typesetter hides a duplicate layer with `{\1a&HFF&}` to show only its outline, or writes `{\alpha&HFF&\t(0,400,\alpha&H00&)}EXIT` so a sign fades in. The built-in tier skips the transform (by design, since #631) and also ignores the alpha, so the hidden text is drawn fully opaque from the first frame on top of the real line. A style with `PrimaryColour` `&H80FFFFFF` followed by `{\c&H00FFFF&}` turns from half transparent to opaque.

**Suggested fix:** When `\c`, `\1c`, `\3c` or `\4c` sets a colour, keep the alpha the colour already had. Read `\alpha` into all four colours' alpha and `\1a` to `\4a` into the matching one, as libass does.

**How to check:** A test that parses a dialogue line `{\1a&HFF&}hidden` with an opaque style and asserts the span's primary colour alpha is zero; today it is 255.

### SUB-06. WebVTT lines show &lrm;, &rlm; and numeric character references as literal text

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | medium | Confirmed | high | none |

**Where:** `KitePlayer/kiteplayer-subtitles/src/io/github/yuroyami/kiteplayer/subtitle/SubRipParser.kt:115-127`, `KitePlayer/kiteplayer-subtitles/src/io/github/yuroyami/kiteplayer/subtitle/WebVttStyles.kt:439`

**What happens:** The WebVTT specification's cue text parser turns HTML character references, named and numeric, into the characters they stand for. The player's WebVTT reader ends with the same decoding step as SubRip, which knows only `&lt;`, `&gt;`, `&nbsp;` and `&amp;`. Every other reference stays on screen as typed. `&nbsp;` also becomes an ordinary space, so a line can break where the author forbade it.

**Evidence:**

```kotlin
// KitePlayer/kiteplayer-subtitles/src/.../SubRipParser.kt:120-127
internal fun List<StyledSpan>.decodeSpanEntities(): List<StyledSpan> = map { span ->
    val decoded = span.text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
```

```kotlin
// KitePlayer/kiteplayer-subtitles/src/.../WebVttStyles.kt:439
return spans.decodeSpanEntities()
```

`vttSpans` is the only path for WebVTT cue bodies, both whole files (`WebVttParser.kt:74`) and packets (`WebVttParser.kt:92` and `215`).

**Failure scenario:** A Hebrew or Arabic WebVTT file from a streaming service, which marks direction with `&rlm;` or `&lrm;`, has the cue `&rlm;- מה?`. It is drawn with the text `&rlm;` visible at the start of every line. `&#x2019;`, `&#8217;` and `&quot;` show the same way.

**Suggested fix:** In the WebVTT path, decode the named references the specification's table holds (at minimum `lrm`, `rlm`, `nbsp` as U+00A0, `quot` and `apos`) and decimal and hexadecimal numeric references, in the same single pass after tag parsing. SubRip can keep its narrower set.

**How to check:** `WebVttParser.parseCueBody("&rlm;hi &#x2019;")` and look at the span text.

### SUB-07. After a reopen or a new queue item the first subtitle can show the previous item's text on Android software video and on Metal

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | medium | Plausible | medium | none |

**Where:** `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:16094-16099`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:8731`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:4343-4347`, `KitePlayer/kiteplayer-output/src@android/io/github/yuroyami/kiteplayer/output/AndroidSurfaceVideoRenderer.kt:1369-1381`, `KitePlayer/kiteplayer-output/src@apple/io/github/yuroyami/kiteplayer/output/MetalFrameComposer.kt:355-356`, `KitePlayer/kiteplayer-output/src@apple/io/github/yuroyami/kiteplayer/output/MetalFrameComposer.kt:778-796`

**What happens:** Each subtitle overlay carries a number, `contentHash`, that renderers use to decide whether they already uploaded its pictures. The number comes from a counter that lives on each opened session and starts at zero for every one. The application's renderer outlives sessions: each session wraps the same renderer in a new adapter. The Android software canvas and the Metal composer cache the uploaded pictures by that number, and an empty overlay does not clear the cache. A new session's first real overlay can therefore carry the same number as the last real overlay of the session before, and the old pictures are drawn instead of the new ones. The comment on the counter promises it "can never claim two different overlays are the same", which holds only inside one session. The Compose and web renderers clear their key on an empty overlay and are not affected.

**Evidence:**

```kotlin
// KitePlayer/kiteplayer-core/src/.../PlaybackCore.kt:16094-16099 (inside class OpenSession)
 * Monotonic overlay identity. A hash of the cue content is collision-prone; a counter
 * bumped on every real change can never claim two different overlays are the same.
 ...
val overlayGeneration = atomic(0L)
```

```kotlin
// KitePlayer/kiteplayer-core/src/.../PlaybackCore.kt:4343-4344
val renderer = AttachableRenderer().also {
    it.delegate = pendingRenderer
```

```kotlin
// KitePlayer/kiteplayer-output/src@android/.../AndroidSurfaceVideoRenderer.kt:1376-1381
if (contentHash != overlayHash) {
    overlayBitmaps.forEach { it.recycle() }
    overlayBitmaps.clear()
    overlayHash = contentHash
}
overlayBitmaps.getOrNull(imageIndex)?.let { return it }
```

```kotlin
// KitePlayer/kiteplayer-output/src@apple/.../MetalFrameComposer.kt:355 and 780
if (overlay == null || overlay.images.isEmpty()) return
...
if (overlayKey == overlay.contentHash && overlayViewport == viewport) return
```

**Failure scenario:** An item opens and publishes its first line, then the session is rebuilt or the queue moves to the next item before another line changes, so the old session's last real overlay carries a small number such as 1 or 2. The new session's counter starts again at zero and its first real line gets that same small number. On Android software decoding the cached bitmap of the old line is drawn into the new line's rectangle, and on Metal the old textures are drawn at the old positions, until the next cue change. The collision needs the two numbers to line up, which is most likely right after an open, so it is marked plausible rather than reproduced.

**Suggested fix:** Draw the generation from one counter on `PlaybackCore` that every session shares, so the numbers handed to the shared renderer never repeat, and correct the comment.

**How to check:** A JVM harness test with a renderer that records `contentHash` values across two sessions on the same player; the second session's first non-empty overlay repeats a value from the first.

### SUB-08. Every ASS track switch or new item on Android and Linux reads up to 24 MB of system fonts from disk again

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | design | medium | Plausible | medium | none |

**Where:** `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:8845-8861`, `KitePlayer/kiteplayer-libass/src/io/github/yuroyami/kiteplayer/libass/LibassTypesetter.kt:39`, `KitePlayer/kiteplayer-libass/src/io/github/yuroyami/kiteplayer/libass/LibassTypesetter.kt:95-102`, `KitePlayer/kiteplayer-libass/src/io/github/yuroyami/kiteplayer/libass/KiteLibass.kt:31`, `KitePlayer/kiteplayer-libass/src@jvmAndAndroid/io/github/yuroyami/kiteplayer/libass/FontFiles.jvm.kt:6-15`

**What happens:** Whenever the typeset track changes, the engine stops the old typesetter and asks the provider for a brand-new one. That happens on every switch between ASS streams, every external script, and every new session. Each new typesetter loads the system fonts once for itself: on Android and Linux, where this libass build has no font provider, it walks the font folders, reads files up to a 24 MB budget (the Latin face, one East Asian face such as the roughly 19 MB Noto CJK collection, then others), and hands each to libass, which copies it. That reading runs on the raster lane before the new track's first typeset picture, and the same lane draws the built-in tier's cues.

**Evidence:**

```kotlin
// KitePlayer/kiteplayer-core/src/.../PlaybackCore.kt:8846-8861
if (session.typeset?.key == key) return
stopTypesetting(session)
val provider = io.github.yuroyami.kiteplayer.spi.SubtitleTypesetters.select() ?: return
val typesetter = try {
    provider.create()
...
val lane = TypesetLane(key, provider.id, typesetter)
```

```kotlin
// KitePlayer/kiteplayer-libass/src/.../LibassTypesetter.kt:95-101
private fun loadSystemFontsOnce() {
    if (systemFontsLoaded) return
    systemFontsLoaded = true
    if (!KiteLibass.needsSystemFontFiles) return
    readFontFiles(KiteLibass.fontDirectories, KiteLibass.systemFontBudgetBytes).forEach { (name, bytes) ->
        engine.addFont(name, bytes)
    }
}
```

`systemFontsLoaded` is a field of each instance (line 39), and `LibassTypesetterProvider.create()` makes a new instance every call (`KiteLibass.kt:47`).

**Failure scenario:** On a phone a viewer toggles between the "Full" and "Signs and Songs" ASS tracks of an episode, or plays a queue of episodes. Each change re-reads and copies up to 24 MB of font files and churns as much on the Java heap, and the first typeset picture of the new track waits behind that reading. The cost is estimated from the budget and was not measured on a device.

**Suggested fix:** Cache the font bytes `readFontFiles` chose once per process, since they are read-only, or keep one typesetter per player and reopen tracks on it. Time the switch on a floor device before and after.

**How to check:** Log the time spent in `loadSystemFontsOnce` on an Android device and switch ASS tracks a few times; it repeats on every switch.

### SUB-09. A SubRip file without blank lines between cues shows each next index number at the end of the line before

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | low | Confirmed | high | none |

**Where:** `KitePlayer/kiteplayer-subtitles/src/io/github/yuroyami/kiteplayer/subtitle/SubRipParser.kt:62-78`

**What happens:** A cue's text ends at a blank line or at the next timing line. A line of digits is recognised as the next cue's index only when it follows a blank line, so in a file with no blank lines the index is kept as text. FFmpeg's SubRip reader holds back a line of digits and drops it when a timing line follows (`libavformat/srtdec.c:172-191` in the vendored 9.0.2 source).

**Evidence:**

```kotlin
// KitePlayer/kiteplayer-subtitles/src/.../SubRipParser.kt:66-77
if (line.isBlank()) {
    val next = lines.getOrNull(i + 1)
    if (next == null || next.isBlank() || parseTiming(next) != null || looksLikeIndex(next)) {
        i++
        break
    }
}
if (parseTiming(line) != null) break
if (body.isNotEmpty()) body.append('\n')
body.append(line)
```

**Failure scenario:** A converter writes `1\n00:00:01,000 --> 00:00:02,000\nHello\n2\n00:00:03,000 --> 00:00:04,000\nWorld`. The first cue is drawn as `Hello` with a second line reading `2`, and every later cue carries the next number the same way.

**Suggested fix:** When a non-blank line of digits is directly followed by a timing line, treat it as that cue's index and leave it out of the body, as FFmpeg does.

**How to check:** `SubRipParser.parse` on the text above and look at the first cue.

### SUB-10. A UTF-16 subtitle file without a byte-order mark loads as no cues

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | low | Confirmed | high | none |

**Where:** `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/SubtitleTextDecoder.kt:214-217`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/SubtitleTextDecoder.kt:350-361`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:756-764`

**What happens:** UTF-16 is recognised only by its byte-order mark. A UTF-16 file of Latin text without one is made only of bytes below 0x80, the zero halves included, so it passes the UTF-8 test and is decoded with a NUL character between every letter. No reader then finds a timing line.

**Evidence:**

```kotlin
// KitePlayer/kiteplayer-core/src/.../SubtitleTextDecoder.kt:385
b < 0x80 -> { i++; continue }
```

`bom()` (lines 350-361) checks only `FF FE` and `FE FF`. The SubRip reader looks for a literal `-->`, which `-\u0000-\u0000>` never matches, and the external load then fails with "the external subtitle file parsed to no cues" at `PlaybackCore.kt:763`.

**Failure scenario:** An `.srt` written as UTF-16LE without a mark by a script or a tool fails to load. The FFmpeg fallback readers it is handed to also detect UTF-16 only by its mark, so it most likely fails there too (that last step was not traced).

**Suggested fix:** Before the UTF-8 test, look for UTF-16 by where the zero bytes fall: a high share of zeros at odd or at even offsets, paired with printable bytes. Decode it as UTF-16LE or BE and report it as not confident.

**How to check:** A unit test that encodes a short SubRip file as UTF-16LE without a mark and passes it to `decodeSubtitleBytes`; the result says UTF-8 and holds NULs.

#### Opportunities

### SUB-11. Captions on CC2, CC3 and CC4 of a broadcast can never be chosen

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| Both | opportunity | low | Confirmed | medium | none |

**Where:** `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/InVideoCaptions.kt:74-82`, `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/KiteFFmpegCaptionDecoder.kt:14-21`, `KiteFFmpeg/native/kitecodec-c/src/helpers_subtitle.c:103-107`

**What happens:** The engine lists one CEA-608 track, titled CC1, for the captions carried inside a picture. KiteFFmpeg opens FFmpeg's caption decoder on the first field only, so the second field, which carries CC3 and CC4 and in US broadcasts often the Spanish captions, is never decoded. CC2 is not offered either. Media3 lets a viewer pick any of CC1 to CC4. The 708 services are already separate tracks.

**Evidence:**

```kotlin
// KitePlayer/kiteplayer-core/src/.../InVideoCaptions.kt:74-79
internal fun captionStreamOf(video: PlayerStreamInfo): PlayerStreamInfo = PlayerStreamInfo(
    index = captionTrackIndex(video.index),
    ...
    title = "CC1",
```

```c
// KiteFFmpeg/native/kitecodec-c/src/helpers_subtitle.c:107
int rc = av_dict_set(&options, "data_field", "first", 0);
```

The decoder's own comment explains why the field is pinned: left on auto, it locks onto whichever field it sees first. In the vendored FFmpeg 9.0.2, `data_field` can choose the second field (`libavcodec/ccaption_dec.c:948-951`), which would reach CC3. The decoder does not track which channel within a field a command belongs to: it treats CC2's control byte `0x1c` like CC1's `0x14` and has no handling for CC2's row codes (`ccaption_dec.c:773-781`), so CC2 and CC4 need decoder work, not just an option.

**Failure scenario:** A US over-the-air recording carries English on CC1 and Spanish on CC3. A viewer who needs Spanish captions has nothing to select. With 708 present the Spanish may be offered as a service, but many MPEG-2 recordings carry 608 only.

**Suggested fix:** In KiteFFmpeg, let the caption decoder open take a field, so a consumer can decode the second field. In KitePlayer, list CC3 as a track once a picture carries second-field bytes, the way 708 services are adopted on first sight. CC2 and CC4 wait for a decoder that separates channels.

**How to check:** Read the cited lines, and play a recording known to carry CC3 to see only CC1 offered.

#### Rejected during verification

- "Animated typeset subtitles on the web pass their pixels through the Kotlin heap twice for every changed frame": rejected because CLAUDE.md records the one Latin-1 string per image crossing as the chosen, measured form for overlay pixels, and the cost here was extrapolated from a different measurement (building a whole 1080p video image) with no new evidence about typeset overlays, which are usually small glyph regions.


## 08. Audio visualiser module

This lane covered the flash guard and the way the surface feeds it, the tests that qualify the captured output for flashes (the capture survey, the area counter and its calibration fixtures, the Night Drive light test and the guard's own test), reduced motion from the public property down to the reading a drawing sees, the finishing pass as a light source of its own, the analysis worker and the hand-over of song maps, the display loudness reference, the PCM queue and the timeline retention, the multi-view logic of `KiteAudioViz`, the Float clocks left in Night Drive, and the settings panel's accessibility. Every finding below was re-read at the cited lines by the verifier, and the flash guard loophole was also checked by porting the guard's decision logic line for line into a small script and feeding it an energy-shaped signal. Nothing was built, rendered or run on a device, so the per-drawing geometry of the fifteen presets, shader cost, section and key accuracy, and every timing figure that depends on real decode speed or real output latency remain unmeasured, and the findings that rest on them are marked as risks.

### VIZ-01. The flash guard lets fast flashes through whenever the dark state before them has not reached a new low for 0.3 seconds

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | high | Confirmed | high | none |

**Second check:** Upheld at high by the second reader, who ported the guard line for line into a script and fed it the real envelope: an independent counter found 4 flashes in the busiest second at 5, 6.67, 8 and 12 hits a second, and 5 at 10 hits a second, against a policy of 3, while `FlashGuardTest` reports 3 because it counts with the same guard. Treat the count of 5 as one case rather than the typical one. How much of the leak reaches the finished picture was not measured.

**Where:** `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/viz/FlashGuard.kt:113-118`, `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/viz/FlashGuard.kt:120-130`, `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/viz/FlashGuard.kt:135-138`, `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/viz/VisualizerSurface.kt:156`, `KitePlayer/kiteplayer-audioviz/test/io/github/yuroyami/kiteplayer/audioviz/FlashGuardTest.kt:29-37`, `KitePlayer/docs/audioviz-rendering-api.md:523-525`

**What happens:** The guard decides whether a change is a slow fade, which it lets through untouched and uncounted, by looking at how long ago the light last reached its extreme, not at how long the change itself took. The time of the extreme only moves when the light goes further the same way. So if the light sits a little above its last low for 0.3 seconds, as it does whenever the quiet moments between hits are slightly brighter than the darkest one so far, the next hit is judged a fade however sudden it is. It passes at full strength, the pair it starts is never counted, and the guard turns round. A held rise has the same weakness: the held branch never refreshes the time, so any rise the guard has been holding is let go all at once after 0.3 seconds. The rendering doc describes a different rule, a change that takes longer than 0.3 seconds to cross the step.

**Evidence:**

```kotlin
// FlashGuard.kt:113-118: only a move further away refreshes the time of the extreme
val swing = wanted - extreme
val away = if (rising) swing >= 0f else swing <= 0f
if (away) { extreme = wanted; extremeAt = now; return wanted }
// FlashGuard.kt:121: a trough above the stored low changes nothing
if (back < step || minOf(wanted, extreme) >= dark) return wanted
// FlashGuard.kt:124-130: measured from the extreme, not from the crossing
if (now - extremeAt > QUICK) { halfway = false; rising = !rising; extreme = wanted; extremeAt = now; return wanted }
// FlashGuard.kt:135-138: the held branch does not touch extremeAt either
if (!rising && flashes.size >= most) { return extreme + step * HOLD }
```

The surface feeds it `guard.allowance(lightFor(current.energy), deltaSeconds)` (VisualizerSurface.kt:156), where the energy envelope has a 10 ms attack and a 120 ms release (`DisplayScale.kt`, `EnergyEnvelope.advance`), so a snare at eight hits a second makes a real peak and trough every 125 ms. The guard's own test counts what it lets through with `FlashGuard(mostPerSecond = 1_000)` (FlashGuardTest.kt:30), the same class with the same rule, so it cannot see the gap. The doc line reads "A change that takes longer than 0.3 seconds to cross the step is a fade, not a flash".

**Failure scenario:** A build-up with a snare roll at eight hits a second over a slowly rising bed, at 60 Hz with the default limit of three. Once three flashes are counted, rises are held at the low plus 0.08. Because every later trough sits above the stored low, the time of the low stays put, and roughly every third hit, 0.375 seconds after the low, passes as a fade at full light. The verifier ported `Channel.allow` line for line and fed it that envelope: the raw signal has 9 flashes in its busiest second, and what the guard let through still has 5, against a policy of 3. A steady roll whose troughs vary only by the frame grid gave 4. The auditor's further claim that the same pattern passes under reduced motion does not hold: with reduced motion on, `CalmReading` glides the energy over half a second before the guard sees it, so a one-frame snare peak never reaches the guard as a fast swing.

**Suggested fix:** Decide fade or flash by how quickly the light actually crossed the step: keep a short ring of the light values of the last 0.3 seconds and compare with the value from 0.3 seconds ago, as the capture counter already does, or date the crossing from the last frame at which the light was still within half a step of the extreme. When a hold ends, release it as a ramp rather than a jump. Check the guard against an independent counter, for example `FlashCaptureTest.AreaFlashes` on a one-pixel picture, rather than another `FlashGuard`, and add fixtures with rising troughs and with a dwell followed by a jump.

**How to check:** Add a test to `FlashGuardTest` that feeds 600 frames of an exponential envelope (10 ms attack, 120 ms release) hit every 125 ms with a background that rises 0.02 per second, run it through `runAllowance(FlashGuard(), ...)`, and count the result with `AreaFlashes` on a one-element array instead of `busiest`. It reports more than three.

### VIZ-02. The flash capture test measures brightness on gamma-encoded pixels, so it under-reads flashes in bright pictures and skips some outright

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | test | medium | Confirmed | high | none |

**Where:** `KitePlayer/kiteplayer-audioviz/test@jvm/io/github/yuroyami/kiteplayer/audioviz/FlashCaptureTest.kt:195-199`, `KitePlayer/kiteplayer-audioviz/test@jvm/io/github/yuroyami/kiteplayer/audioviz/FlashCaptureTest.kt:106`, `KitePlayer/kiteplayer-audioviz/test@jvm/io/github/yuroyami/kiteplayer/audioviz/NeonLoFiLightTest.kt:39-42`, `KitePlayer/docs/audioviz-standard.md:433`, `KitePlayer/docs/audioviz-rendering-api.md:534-538`

**What happens:** The WCAG step of 0.10 and the 0.80 limit on the darker state are both measured in relative luminance, which is computed after the sRGB values have been turned back into linear light. The capture test applies the Rec. 709 weights straight to the 8-bit encoded values divided by 255. In that space the 0.80 cut-off sits at a linear luminance of about 0.60, so any flash whose darker state lies between about 0.60 and 0.80 linear is skipped completely, and above mid grey a real 0.10 step shows up as only about 0.06 to 0.08, so it is not counted. The Night Drive test decodes each channel to linear before weighting, so two tests in the same folder disagree on what luminance means. In the darkest part of the range the encoded measure reads swings as larger than they are, so on the mostly dark drawings of the catalogue the error partly runs the safe way, which is why this is medium rather than high; for bright or pastel moments it runs the unsafe way.

**Evidence:**

```kotlin
// FlashCaptureTest.kt:195-199
fun luma(pixel: Int): Float =
    0.2126f * (pixel shr 16 and 0xFF) / 255f +
        0.7152f * (pixel shr 8 and 0xFF) / 255f +
        0.0722f * (pixel and 0xFF) / 255f
// FlashCaptureTest.kt:106
if (minOf(was, now) >= DARK) continue
// NeonLoFiLightTest.kt:39-42
val r = NeonLoFiPaint.decode((pixels[i] shr 16 and 255) / 255f)
...
light[i] = 0.2126f * r + 0.7152f * g + 0.0722f * b
```

The standard (audioviz-standard.md:433) says "Assess the actual colour-managed output".

**Failure scenario:** A full-screen alternation between sRGB grey 210 and white reads 0.82 encoded for the darker state, so the test skips it, but its linear luminance is 0.64 with a swing of 0.36, a WCAG flash. A swing between sRGB 178 and 198 reads 0.078 encoded, so it is not counted, but in linear light it runs from 0.445 to 0.565, a swing of 0.12 that is a WCAG flash. A drawing that strobes light pastels or near white on the beat shows no flashes in the survey.

**Suggested fix:** Linearise each channel with the sRGB transfer function before weighting, as `NeonLoFiLightTest` does, and share one luminance function between every flash test. Add a `FlashCounterTest` fixture that alternates between two bright greys whose linear swing qualifies, then re-run the survey and update the published counts.

**How to check:** Read `FlashCaptureTest.luma` next to `NeonLoFiLightTest` lines 39 to 42, then compute the numbers above by hand with the sRGB formula in `NeonLoFiPaint.decode`.

### VIZ-03. The flash counter cannot see a pattern that reverses, because areas brightening and darkening at once cancel out

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | test | medium | Confirmed | high | none |

**Where:** `KitePlayer/kiteplayer-audioviz/test@jvm/io/github/yuroyami/kiteplayer/audioviz/FlashCaptureTest.kt:102-116`, `KitePlayer/kiteplayer-audioviz/test@jvm/io/github/yuroyami/kiteplayer/audioviz/FlashCounterTest.kt:32-95`

**What happens:** The area counter records a leg only when the share of pixels that brightened minus the share that darkened passes a tenth of the picture. A checkerboard or stripes swapping phase, a swap between red and blue regions, or strips slid sideways brighten about as many pixels as they darken, so the net is near zero and nothing is counted, however much of the screen is flashing. WCAG counts each flashing area, and a reversing pattern is one of the classic triggers. The calibration test has no reversing fixture, so this blind spot was never measured. The code comment explains why the net was chosen (a travelling pattern would otherwise count as a flash train), which is a real concern, but the chosen fix throws away a far more dangerous case.

**Evidence:**

```kotlin
// FlashCaptureTest.kt:107, 113-116
if (now - was >= STEP) up++ else if (was - now >= STEP) down++
...
val share = (up - down).toFloat() / light.size
val size = if (share < 0f) -share else share
if (size > widest) widest = size
if (size < AREA) return
```

`FlashCounterTest` feeds only whole-screen and patch alternations, a bright pair, a small step, a four second fade and a still picture.

**Failure scenario:** A drawing whose kick inverts a grid of tiles at six times a second across the whole screen, half going bright while half go dark: `up` and `down` are each about half the pixels, the share is about zero, and the survey reports no flashes.

**Suggested fix:** Count brightening and darkening separately, each against the area threshold, or divide the frame into regions and count per region, and treat a travelling pattern by requiring the same region to reverse rather than by netting the whole frame. Add a reversing checkerboard and a red and blue swap to `FlashCounterTest` as known flashes, and keep a slowly travelling pattern as a known pass.

**How to check:** Add a fixture to `FlashCounterTest` that alternates the two halves of a checkerboard between 0.05 and 0.85 three times a second, and run it through `count`. It returns 0.

### VIZ-04. The rule against saturated red flashes is checked for one drawing out of fifteen, and that check allows three a second

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | test | medium | Confirmed | high | none |

**Where:** `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/viz/FlashGuard.kt:19-20`, `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/viz/FlashGuard.kt:68-72`, `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/viz/VisualizerSurface.kt:156`, `KitePlayer/kiteplayer-audioviz/test@jvm/io/github/yuroyami/kiteplayer/audioviz/NeonLoFiLightTest.kt:43`, `KitePlayer/kiteplayer-audioviz/test@jvm/io/github/yuroyami/kiteplayer/audioviz/NeonLoFiLightTest.kt:54`, `KitePlayer/docs/audioviz-standard.md:419-421`, `KitePlayer/docs/audioviz-rendering-api.md:507-509`

**What happens:** The policy is "no saturated red flashing at all", and the standard asks the guard to constrain red transitions in the final picture or on every layer. The surface only ever calls `allowance`, which passes a red share of zero, so the red half of the guard never runs at run time; the rendering doc admits this. That leaves captured testing as the only check, and the only captured red measurement is in the Night Drive test. That test asserts at most three red flashes rather than none, and it feeds its red signal into the general area counter, which uses the general 0.10 step and the 0.80 darker-state cut-off, while WCAG's red threshold is a change of 20/320, about 0.0625, in R minus G minus B with no darker-state rule. The survey that covers every drawing measures luminance only.

**Evidence:**

```kotlin
// FlashGuard.kt:68-72
public fun allowance(wanted: Float, deltaSeconds: Float): Float {
    val asked = wanted.coerceIn(0f, 1f)
    if (asked <= 0f) return 1f
    return (limit(asked, 0f, deltaSeconds) / asked).coerceIn(0f, 1f)
}
// NeonLoFiLightTest.kt:43, 54
red[i] = if (r / max(0.001f, r + g + b) > 0.8f) max(0f, r - g - b) else 0f
assertTrue(flashes <= 3 && redFlashes <= 3, ...)
```

**Failure scenario:** A palette with saturated red stops (`VizPalette.fromColors` with reds, or a key colour that leans red) on Fracture, whose kick splits the red, green and blue layers for a moment: red alternations four times a second are counted by no test, and even in the Night Drive test a red swing of 0.08 would not count.

**Suggested fix:** Add a red trace to `FlashCaptureTest` for every drawing using the WCAG saturated red definition (R/(R+G+B) of at least 0.8 in linear light, a 0.0625 step in R minus G minus B, no darker-state cut-off), give it its own counter constants rather than reusing the general ones, and assert zero to match the policy. Then either pass an estimated red share from the palette to the guard, or say plainly in the KDoc and the docs that the red rule is enforced only by captured qualification.

**How to check:** Read `allowance` and grep the module for callers of `limit(` with a non-zero red share; there are none outside tests. Then read lines 43 and 54 of `NeonLoFiLightTest`.

### VIZ-05. A new song after one with a song map starts from the old song's loudness reference, so a quiet track can look dim for tens of seconds

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | medium | Confirmed | high | none |

**Where:** `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/AudioVizFeed.kt:195-215`, `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/AudioVizFeed.kt:226`, `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/AudioVizFeed.kt:233-240`, `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/DisplayScale.kt:21-28`, `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/DisplayScale.kt:47-54`, `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/SongScan.kt:206-208`, `KitePlayer/docs/audioviz-feature-api.md:102-109`

**What happens:** The feature doc says the causal reference starts at 0.01 and rises with a one second time constant, that `reset` keeps a fixed song reference for seeks, and that the setter must be called with null before feeding a different song. The feed does these in the wrong order. When a new song's audio arrives (a new epoch or a timestamp jump), it calls `reset()` first, which keeps the old song's fixed reference because one is still set. It then re-applies `appliedMap`, which is still the old song's map, so that call does nothing. Only after that does `applyMap` pick up the scanner's null (the scanner installs null for every new item before it even waits to scan). Setting null then makes the reference `1 / gain`, which after the two second hand-over equals the old song's fixed reference, instead of the fresh 0.01. From there a quieter song's reference can only fall with the 15 second time constant.

**Evidence:**

```kotlin
// AudioVizFeed.kt:198, 211
active = if (active != null && nextFormat == format) active.apply { reset() } else newAnalyzer(...)
active.setSongReferencePower(appliedMap?.map?.referencePower)   // still the old map: no-op
// AudioVizFeed.kt:226, 233-238
applyMap(active)   // only now: pending is null, so setSongReferencePower(null)
// DisplayScale.kt:21-28
if (power == fixedReference) return
...
if (power == null) referencePower = 1.0 / gain
// DisplayScale.kt:47-54
fun reset() { if (fixedReference == null) { referencePower = 0.01; gain = 100.0; ... } }
// SongScan.kt:206-208
// Whatever map was installed described the previous item or track.
destination.install(null)
```

**Failure scenario:** A loud mastered pop track whose map holds a reference near 0.16 is followed by a quiet acoustic track that has no map yet, or that the scan policy refuses (for example a network item under the default policy). The new track's bars and its light start at a fraction of their normal height and take tens of seconds to recover, and a track that never gets a map never gets the documented one second start.

**Suggested fix:** In `process`, call `applyMap(active)` before the reset, or make the epoch branch clear the fixed reference when the pending map is null, so that `reset()` sees no fixed reference and restores 0.01. Separately, make `setReference(null)` after a reset fall back to the initial 0.01 rather than `1 / gain`. Add a feed-level test: install a map, let the hand-over finish, change the epoch, install null, and assert the reference is 0.01 on the first new analysis.

**How to check:** Read `AudioVizFeed.process` from line 195 to 227 next to `DisplayScale.setReference` and `reset`, and follow the order of the three calls for a slot of a new generation.

### VIZ-06. When the first visualiser view of a state leaves while a second remains, the director stops changing drawings and mutations stop reaching the screen

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | medium | Confirmed | high | none |

**Where:** `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/KiteAudioViz.kt:41-54`, `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/KiteAudioViz.kt:93-130`, `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/viz/VisualizerSurface.kt:689`, `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/AudioVizState.kt:160-162`, `KitePlayer/kiteplayer-audioviz/test@jvm/io/github/yuroyami/kiteplayer/audioviz/OneViewPerStateTest.kt:38`

**What happens:** Whether a view is the first one is decided once, inside `remember`, and never looked at again. Only the first view draws through `DirectedVisualizerSurface`, and that is the only place `director.advance` is called. A view that started as a mirror stays a mirror after the first view goes away. With directed play on, nothing advances the director any more, so the remaining view shows the same drawing for ever. The mirror copies the state's parameter values and Night Drive's recipe into its own copy each frame, so parameter sliders still work, but `state.mutate()` and the gene controls change the genes of the state's own instance, which no view draws any more. The count is also raised during composition inside `remember`, so a composition that is abandoned before its effects apply leaves the count raised with no `onDispose` to lower it, and every later view of that state becomes a mirror.

**Evidence:**

```kotlin
// KiteAudioViz.kt:41-54
val first = remember(state) { ShownStates.add(state) }
DisposableEffect(state) { onDispose { ShownStates.remove(state) } }
...
if (!first) { MirroredSurface(state, source, modifier, post, makeCopies); return }
// KiteAudioViz.kt:102, 107-111: the mirror shows director.current and copies only params and the Night Drive recipe
val shown = if (state.directed) state.director.current else state.drawing
// VisualizerSurface.kt:689, the only caller
clock.run(framesPerSecond, { currentFrame() }) { director.advance(it.frame, it.deltaSeconds) }
// AudioVizState.kt:160-162
public fun mutate() { showing.genes?.mutateNow() }
```

**Failure scenario:** An app shows a full-screen player and a mini player, each with a `KiteAudioViz` of the same state. During an animated navigation back, the mini player composes while the full screen is still there, so it becomes a mirror, and then the full screen is disposed. From then on the mini player never changes drawing with directed play on, and "Mutate now" and the gene sliders in `AudioVizSettings` do nothing visible.

**Suggested fix:** Make the role reactive: keep the count in snapshot state, or register in a `RememberObserver` (`onRemembered`, and unregister in both `onForgotten` and `onAbandoned`), and let a mirror promote itself to the first view when the owner leaves. Add a test that disposes the first of two views and checks that the director advances and that a mutation reaches the remaining view.

**How to check:** Extend `OneViewPerStateTest` with two views in a scene whose first view is removed by a state flag after a few frames, then step the scene for longer than the director's change interval and assert `state.director.current` changes.

### VIZ-07. The finishing pass flares on every kick and tears the picture on a drop outside the flash guard, and no flash fixture ever contains a drop

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | design | medium | Plausible | medium | none |

**Where:** `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/viz/PostProcess.kt:51-57`, `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/viz/PostProcess.kt:199-201`, `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/viz/PostProcess.kt:213-242`, `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/viz/presets/Bars.kt:93`, `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/viz/Visualization.kt:382-387`, `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/MoodTracker.kt:96-100`, `KitePlayer/kiteplayer-audioviz/test@jvm/io/github/yuroyami/kiteplayer/audioviz/FlashCaptureTest.kt:40-43`, `KitePlayer/docs/audioviz-standard.md:415-421`

**What happens:** The standard says the common guard must bound every contributing layer, finishing included. The finishing pass reads the analysis frame directly and never sees `lightScale`. Its bloom strength rises by half the bloom amount times the kick strength on the display frame that carries a kick. On a drop, with the default `PostSpec` (where `glitch` is on), it adds red-only and blue-only copies of the scene with additive blending at up to 0.55 alpha, and picks new places for five torn strips on every display frame for about 0.8 seconds while the drop pulse fades. In the built-in catalogue Bars keeps the tear (`PostSpec.Default.copy(bloom = 0.5f)`), and so does any app drawing that keeps the base class's default post for a non-calm drawing. The kick flare is inside what the capture survey renders, because its drum loops have kicks, but a drop needs the music to have gone quiet first (`trend > 1.6f && sinceQuiet < 8f`), which a steady drum loop does not do, so the drop tear has very likely never been in a captured flash measurement. It also changes the picture mostly in antiphase, which the counter cannot see (VIZ-03).

**Evidence:**

```kotlin
// PostProcess.kt:199-201
val flare = if (calm) 0f else current.kick
val strength = (post.bloom * (0.6f + 0.6f * current.energy + 0.5f * flare)).coerceIn(0f, 1f)
// PostProcess.kt:218-233
redSplit.colorFilter = RED_ONLY
redSplit.blendMode = BlendMode.Plus
redSplit.alpha = 0.55f * amount
...
if (current.audible > 0f) { for (strip in 0 until TORN_STRIPS) { tears[strip * 3] = random.next() ... } }
// MoodTracker.kt:96
if (trend > 1.6f && sinceQuiet < 8f && dropPulse <= 0f && onsetStrength > 0.15f) {
```

**Failure scenario:** A song with a breakdown and a drop, shown with Bars: at the drop the picture sticks for two frames, gains a saturated red and blue double image, and five bands covering up to about a fifth of the height jump to new places sixty times a second for most of a second, while the guard's light scale stays at 1 because the energy rises only once. No test has rendered this moment through the flash counter. What would prove it: a survey run of Bars over a fixture with a two second breakdown followed by a loud return.

**Suggested fix:** Put the finishing pass under the guard: scale the kick flare and the tear layers by `state.lightScale`, or by a second allowance computed from the light the pass adds. Pick new tear positions at most every 100 to 150 ms rather than every frame, and drop or limit the red-only layer under the red rule. Add a breakdown-and-drop fixture (and one with palette changes) to the capture survey, as the standard's "Checked by" list asks.

**How to check:** Read `PostProcess.kt` lines 199 to 242 and `MoodTracker.readSections`, then write a fixture of two seconds of near silence followed by the drum loop and log `dropPulse` from `SongPlayer` to see that a drop lands, which the plain drum loop does not produce.

### VIZ-08. Reduced motion promises no flashes at all, but its own test accepts up to three a second

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | docs | medium | Confirmed | high | none |

**Where:** `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/AudioVizState.kt:105-113`, `KitePlayer/docs/audioviz-rendering-api.md:587-589`, `KitePlayer/docs/audioviz-rendering-api.md:558-560`, `KitePlayer/kiteplayer-audioviz/test@jvm/io/github/yuroyami/kiteplayer/audioviz/FlashCaptureTest.kt:46-49`, `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/viz/VisualizerSurface.kt:90`

**What happens:** The public KDoc of `AudioVizState.reducedMotion` and the rendering doc both say "the flash limit drops to none at all". In code that is literally true of the guard's count (`FlashGuard(0)`), but the guard only scales the shared light, the drawings draw their own marks, and the finishing pass is outside it, so the screen does not reach zero flashes. The capture test openly accepts up to three under reduced motion ("it cannot promise none at all"), and the same doc reports Pipe and Honeycomb still flashing once with reduced motion on. An app developer reading the KDoc is told something stronger than the library delivers.

**Evidence:**

```kotlin
// AudioVizState.kt:108-109
// ... every change of drawing becomes a plain fade, and the flash limit drops to none at all.
// FlashCaptureTest.kt:46-49
// Reduced motion has to bring the picture inside the policy and never make it worse.
// It slows the reading a drawing sees rather than stopping the drawing, so it cannot
// promise none at all: a drawing with its own fast actors still moves them.
if (calm > MOST || calm > fast + 1) problems += ...
```

The doc at line 559 to 560: "Pipe and Honeycomb still flash once in their busiest second".

**Failure scenario:** An app offers "Reduce motion (no flashing)" in its accessibility settings on the strength of the KDoc, and a user who relies on it can still meet up to three qualifying flashes a second.

**Suggested fix:** Reword the KDoc and the doc to what is measured: reduced motion sets the guard's allowance to zero, glides the reading and turns off the tear and the colour split, and the captured output stays inside the general policy, but zero flashes is not guaranteed. Or make it true by asserting zero in the survey and fixing the drawings that fail.

**How to check:** Read the KDoc at `AudioVizState.kt:105-113` next to `FlashCaptureTest.kt:46-49`.

### VIZ-09. The published flash results describe a 24-drawing catalogue, while the catalogue now has 15

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | docs | medium | Confirmed | high | none |

**Where:** `KitePlayer/docs/audioviz-rendering-api.md:556-560`, `KitePlayer/docs/audioviz-rendering-api.md:54-57`, `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/viz/VizCatalog.kt:31-48`

**What happens:** The rendering doc says "The capture test ran on all 24 drawings on 2026-09-29, and none of them crosses the policy", and gives counts that add up to 24 (four at three and twenty below; two at one and twenty-two at none under reduced motion). The same file, forty lines earlier in its own numbering, says the catalogue has 15 drawings, and `VizCatalog.create()` lists 15. The result names only Bars, Fracture, Iris, Odyssey, Pipe and Honeycomb, so no reader can tell which recorded result, if any, belongs to Ocean Mist, Twin Bloom, Contour, Alchemy, Thin Ice, Glitch, Night Drive, Silk or Lines as they stand now. The git history cannot settle it either: every drawing file shows the toolchain move of 2026-10-22 as its first commit.

**Evidence:**

```text
docs/audioviz-rendering-api.md:54   The catalogue has 15 drawings on desktop, iOS and Android 13 and newer.
docs/audioviz-rendering-api.md:556  The capture test ran on all 24 drawings on 2026-09-29
```

`VizCatalog.create()` returns Bars, OceanMist, TwinBloom, Contour, Alchemy, Pipe, ThinIce, Glitch, Odyssey, NeonLoFi, Silk, Lines, Honeycomb, Fracture and Iris.

**Failure scenario:** A reviewer reads that every drawing passed the flash survey and ships the default catalogue, when the results for the current drawings are not recorded anywhere in the docs.

**Suggested fix:** After fixing the counter (VIZ-02, VIZ-03, VIZ-04), re-run `scripts/audioviz-tests.sh survey` on the current catalogue and replace the paragraph with a per-drawing table and its date, or have the survey write its table to a file the doc links to, so the numbers cannot drift from the catalogue.

**How to check:** Read lines 54 and 556 of the rendering doc and count the entries in `VizCatalog.create()`.

### VIZ-10. The palette swatches have no name or selected state for a screen reader, and the sliders cannot be reached with a keyboard or remote

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | low | Confirmed | high | KitePlayer#469 |

**Where:** `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/AudioVizSettings.kt:88-91`, `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/AudioVizSettings.kt:240-252`, `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/PanelControls.kt:119-145`

**What happens:** Each palette swatch is a `Canvas` with `.clickable(onClick = onPick)` and no role, label or selected state, so TalkBack and VoiceOver announce an unnamed control and cannot say which palette is in use; the outline is the only cue, and the name appears only in a separate note below the row. The custom slider sets a range and a progress action for screen readers, but it is not focusable and handles no key events, so on desktop and with a television remote it cannot be focused or moved. Its touch area is 22 dp tall, under the usual 48 dp.

**Evidence:**

```kotlin
// AudioVizSettings.kt:243-248
Canvas(Modifier.size(44.dp, 22.dp).clip(RoundedCornerShape(4.dp))
    .border(if (chosen) 2.dp else 0.dp, PanelColors.Bright, RoundedCornerShape(4.dp))
    .clickable(onClick = onPick)) { ... }
// PanelControls.kt:125-141: semantics, tap and drag only; no focusable() or onKeyEvent
```

**Failure scenario:** A blind user moving through the settings hears an unnamed control eight times with no palette names; a television user with a direction pad skips past every gene and parameter slider.

**Suggested fix:** Give `Swatch` `selectable(selected = chosen, role = Role.RadioButton, onClick = onPick)` and `semantics { contentDescription = palette.name }`, as the panel's chip already does. Make `Slider` `focusable()` with left and right key handling in steps of a twentieth of the range, and pad its hit area to 48 dp.

**How to check:** Read `Swatch` and `Slider`, or open the settings panel on Android with TalkBack on.

### VIZ-11. Night Drive still runs two Float clocks that speed up after 18 hours and stop after 36 at 144 Hz

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | low | Confirmed | high | none |

**Where:** `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/viz/shader/NeonLoFiWorld.kt:67`, `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/viz/shader/NeonLoFiWorld.kt:224`, `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/viz/shader/NeonLoFiFlight.kt:74`, `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/viz/shader/NeonLoFiFlight.kt:90-94`, `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/viz/shader/ShaderInputs.kt:38-39`

**What happens:** The surface clock (#349) and the guard's clock (#656) were moved to Double because a Float stops adding a short step correctly after a day or so. `NeonLoFiWorld.time` and `NeonLoFiFlight.clock` are still Floats that add one display step at a time. At 144 Hz a step is about 0.0069 s; past 65,536 s (about 18 hours of heard play) each addition rounds up to 0.0078 s, so these clocks run about 13 percent fast, and past 131,072 s (about 36 hours) each addition rounds to nothing and they stop. The flight changes region only when `clock - lastStart >= 60f`, so once the clock stops the drive never changes region again. The shared `uMusicTime` uniform is also a Float, so shader motion fed from it starts to step coarsely after about a day.

**Evidence:**

```kotlin
// NeonLoFiWorld.kt:67, 224
var time = 0f; private set
time += dt * audible * state.motionScale
// NeonLoFiFlight.kt:74, 90, 94
var clock = 0f; private set
clock += dt * audible
val eligible = manual < 0 && clock - lastStart >= 60f && ...
```

**Failure scenario:** An installation or a radio app leaves Night Drive running for two days on a 144 Hz monitor: the motion driven by these clocks first speeds up and then freezes, and the flight never picks a new region.

**Suggested fix:** Keep both accumulators as Double, or wrap them modulo a period the drawing uses, and pass shader time uniforms already wrapped on the host. Extend the existing long-run clock tests to Night Drive.

**How to check:** Read the two fields, then evaluate `131072f + 0.0069f == 131072f` in a scratch Kotlin file.

### VIZ-12. Two clips of one file with the same length share one song map cache entry

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | low | Confirmed | medium | none |

**Where:** `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/SongScan.kt:150-159`, `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/SongScan.kt:215-219`, `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/SongScan.kt:276-282`, `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/SongScan.kt:97`

**What happens:** The cache key hashes the address, the track, the duration, the sample rate, the channel count and the map version, but not the item's clip. The scan plans its ranges from the clip's start (#672), and the duration it keys on is the player's duration for the item, which counts from the clip's start, so a map belongs to one clip. Two clips of one file with equal lengths, such as fixed-length previews at different offsets or equal-length tracks cut from one file, get the same key in memory and in the on-disk store. The second clip loads the first clip's map until live verification disagrees and withdraws it.

**Evidence:**

```kotlin
// SongScan.kt:157-158
return "${hash.toULong().toString(16)}/${target.track?.value}/${target.durationMicros}/" +
    "${stream?.sampleRate}/${stream?.channels}/${SongMap.VERSION}"
// SongScan.kt:276
val clipStart = target.media.clip?.start?.inWholeMicroseconds?.coerceAtLeast(0L) ?: 0L
```

**Failure scenario:** An app plays 30 second previews of an album file at different offsets. The second preview installs the first one's loudness reference and section and drop events, so structure effects land at the wrong moments and the display gain is wrong until verification rejects the map and deletes the stored copy.

**Suggested fix:** Add the clip's start and end, in microseconds, to the key and raise `SongMap.VERSION` so old entries are not read.

**How to check:** Read `SongMapCache.key` and grep it for `clip`; there is none.

### VIZ-13. A burst of decoded audio longer than a quarter second throws away the analysis of the sound heard first after a seek at double speed

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | risk | low | Plausible | low | none |

**Where:** `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/AudioPcmQueue.kt:10-13`, `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/AudioPcmQueue.kt:62-65`, `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/AudioVizFeed.kt:100-104`, `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/AudioVizFeed.kt:145-155`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/AudioPlayback.kt:80`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:15113-15114`

**What happens:** The tap is handed decoded media before the tempo stage, and after every start or seek the decode loop fills the 200 ms output ring as fast as it can. At double speed, 200 ms of output is about 400 ms of media, more than the queue's 250 ms bound unless the analysis worker keeps pace with decoding. The first refused block calls `resetEpoch`, and the worker then discards every queued slot of the old revision unprocessed, which throws away the continuous audio that is about to be heard first, not just the block that did not fit. The analyser's own timestamp jump check would have marked the hole without losing what was queued.

**Evidence:**

```kotlin
// AudioPcmQueue.kt:13, 64
val maxQueuedNanos: Long = 250_000_000L,
if (queuedNanos + duration > maxOf(maxQueuedNanos, duration)) return Offer.TooMuchTime
// AudioVizFeed.kt:151-154
if (!gapOpen) { resetEpoch(generation); gapOpen = true }
// AudioVizFeed.kt:102: older-revision slots are released without being processed
if (slot.generation == current.generation && slot.revision == current.revision) process(slot, current)
```

**Failure scenario:** Playing at 2x on a slow phone, each seek shows the empty picture for roughly the first quarter to half second, because the audio heard first was never analysed, and `AudioAnalysisStats.drops` grows on every seek. What would prove it: log `drops` and `resets` around seeks at 1x and 2x on the ASUS, whose ring is 240 ms.

**Suggested fix:** On saturation keep the queued slots and drop only the incoming block, letting the existing jump check in `process` reset at the hole; or scale the time bound with the playback rate. Measure before choosing.

**How to check:** Read `onAudio` and the worker loop together, then write a feed test that offers 400 ms of blocks at once without letting the worker run and counts the analyses that come out.

### VIZ-14. The spectrum timeline and event history keep only two seconds, so an output with more delay than that would show an empty picture

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | risk | low | Plausible | low | KitePlayer#495 |

**Where:** `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/SpectrumTimeline.kt:124-127`, `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/SpectrumTimeline.kt:334`, `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/AudioVizFeed.kt:273`, `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/AudioEventHistory.kt:15-20`

**What happens:** Analyses are made when audio is decoded, ahead of the ring, and the picture samples them at the heard position, which since #495 includes the device's reported output latency on Apple platforms. The timeline drops frames older than two seconds behind the newest one, and its 256 entries at the 10 ms hop hold 2.56 seconds at most; the event history also keeps two seconds. If ring depth plus device latency approaches two seconds, the frame for the moment being heard has already been dropped, the lookup returns nothing and the view draws the silent picture.

**Evidence:**

```kotlin
// SpectrumTimeline.kt:124, 334
val earliest = ... frame.ptsMicros - RETENTION_MICROS
private const val RETENTION_MICROS = 2_000_000L
// AudioVizFeed.kt:273
private const val TIMELINE_CAPACITY = 256
// AudioEventHistory.kt:19
private val retentionMicros: Long = 2_000_000L,
```

**Failure scenario:** An iPhone routed to an AirPlay speaker whose session reports an output latency near two seconds: the heard position trails the decoded audio by more than the retention, and the visualiser stays blank while the music plays. What would prove it: read the reported output latency on an AirPlay route and the lead between the newest analysis and the sampled position.

**Suggested fix:** Size retention from the measured lead with a margin, or raise it to four or five seconds, and report the lead in `AudioAnalysisStats` so a device test can check it.

**How to check:** Read the constants, then on an AirPlay route log `AVAudioSession.outputLatency` alongside whether `interpolated()` returns null.

#### Opportunities

### VIZ-15. The visualiser ignores the system's reduce-motion setting and starts with flashing and camera shake on

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | opportunity | medium | Confirmed | high | none |

**Where:** `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/AudioVizState.kt:105-113`, `KitePlayer/kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/AudioVizState.kt:318-342`, `KitePlayer/docs/audioviz-standard.md:404-406`

**What happens:** `reducedMotion` starts false, and nothing in the module reads Android's animation scale or remove-animations setting, iOS's Reduce Motion, or a desktop equivalent; the KDoc leaves it to the host ("Follow the host's own reduced-motion setting where the platform has one"), and `rememberAudioVizState` takes no parameter for it. For a component whose main protection for sensitive viewers is reduced motion, an off default that every app must wire itself means most apps will ship it off.

**Evidence:**

```kotlin
// AudioVizState.kt:113
public var reducedMotion: Boolean by mutableStateOf(false)
```

A search of every source folder of the module for `ANIMATOR_DURATION_SCALE`, `isReduceMotionEnabled` or `prefers-reduced-motion` finds nothing.

**Failure scenario:** A user who turned on Remove animations or Reduce Motion for vestibular or photosensitive reasons opens an app built on `KiteAudioViz` and gets the full camera punch, shake, cuts and flash allowance until the app developer adds their own wiring.

**Suggested fix:** Add a platform reading of the system setting and use it as the initial value, or as the default of a `reducedMotion` parameter on `rememberAudioVizState`, keeping the property overridable. Say so in the rendering doc's reduced-motion section.

**How to check:** Grep `kiteplayer-audioviz` for the platform setting names above.

#### Rejected during verification

- Part of VIZ-01 only: the claim that the guard loophole also passes about 2.7 flashes a second under reduced motion was dropped, because with reduced motion on `CalmReading` glides the energy over half a second before the guard sees it, so a one-frame snare peak never reaches the guard as a fast swing. The rest of VIZ-01 stands.
- Part of VIZ-06 only: the claim that parameter sliders stop reaching the remaining view was dropped, because the mirror copies every parameter value and Night Drive's recipe into its copy each frame (`KiteAudioViz.kt:107-111`). Only the director and gene mutations are lost.


## 09. Web target, web worker, platform parity, tests and CI

This lane covered the web worker player from end to end (the page facade `KitePlayerWorker`, the worker's `WorkerRuntime`, the message protocol, and the synchronous network reader `SyncHttpMediaIo`), the page and worker sound paths (`WebAudioSink`, `WebAudioWorkletDevice`, `WebWorkerAudio`), how libass loads on the web, the web blocking bridge, KiteFFmpeg's display conversion used by the web canvas, both repositories' web test runners and coverage script, and the web, publish and libass chain jobs of both repositories' workflows. Every finding below was re-read at its cited lines by the verifier, and line numbers were corrected where they had drifted. Nothing was built or run, so the two worker network findings and the Safari finding rest on the code plus browser behaviour that was not reproduced here; each says what would prove it. Not covered: the nested opener and the asynchronous codec bridge in C, `MediaSource.wasmJs`, the web canvas draw loop, the web subtitle rasterizer, the Compose WebGL path, and the native and JVM CI jobs.

### WEB-01. Network playback in the worker drops sound and freezes the picture while each read-ahead window downloads

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | high | Plausible | medium | KitePlayer#568 |

**Second check:** Upheld at high by the second reader. Line corrections: the window constants are at `SyncHttpMediaIo.kt:248-249`, `xhr.open('GET', url, false)` is at line 330, `xhrFetch` spans 327 to 364 and `fetch` spans 217 to 245 with the doubling at 218.

**Where:** `KitePlayer/kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/SyncHttpMediaIo.kt:216-218`, `KitePlayer/kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/SyncHttpMediaIo.kt:245-246`, `KitePlayer/kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/SyncHttpMediaIo.kt:315-318`, `KitePlayer/kiteplayer-output/src@wasmJs/io/github/yuroyami/kiteplayer/output/WebAudioSink.kt:224-230`, `KitePlayer/kiteplayer-core/src@wasmJs/io/github/yuroyami/kiteplayer/internal/PlatformDispatchers.wasmJs.kt:11-12`

**What happens:** The worker player reads network media with synchronous requests, which hold the worker's only thread until the whole answer has arrived. Each request asks for a window of the file that starts at 1 MB and doubles to 16 MB while the reads run forward. While a request is in flight nothing else in the worker runs: no demux, no decode, no picture, and no refill of the sound. The only sound that keeps playing is what the page's audio worklet already holds, which the sink keeps at about 85 ms. Any request that takes longer than that leaves the worklet dry, so the listener hears a gap and the picture stalls.

**Evidence:**

```kotlin
// SyncHttpMediaIo.kt:217
nextWindow = if (from == windowEnd && from > 0) (nextWindow * 2).coerceAtMost(MAX_WINDOW_BYTES) else FIRST_WINDOW_BYTES
// SyncHttpMediaIo.kt:245-246
const val FIRST_WINDOW_BYTES = 1L shl 20
const val MAX_WINDOW_BYTES = 16L shl 20
// SyncHttpMediaIo.kt:317 (inside xhrFetch)
xhr.open('GET', url, false);
```

```kotlin
// WebAudioSink.kt:224-230
/**
 * Four blocks, about 85 ms at 48 kHz.
 ...
const val highWaterFrames = 4096
```

```kotlin
// PlatformDispatchers.wasmJs.kt:11-12
internal actual fun platformPlaybackDispatchers(): PlaybackDispatchers =
    PlaybackDispatchers.sharing(Dispatchers.Default)
```

Path: the demux lane calls `BlockingMediaIo.read`, which runs `SyncHttpMediaIo.read` without suspending; a read outside the window calls `fetch`, which calls `xhrFetch` with `async = false`. The sink's feeder (`WebAudioSink.feedLoop`) and `WorkletPortDevice.enqueue` run on the same `Dispatchers.Default` of the same worker thread, so they cannot run until the request returns. The worker browser tests read from a localhost server and assert only the page's frame gaps (`KitePlayerWorkerBrowserTest.kt:113-119`); no test watches the worker's underruns or picture gaps on a slower link. Open issue #568 covers the related case of a live stream that blocks a read for ever; this is the ordinary on-demand case.

**Failure scenario:** A 1080p MP4 at 5 Mbit/s plays from an https server over a 50 Mbit/s connection. Even the first 1 MB window takes about 170 ms plus the round trip, twice the worklet's queue. Once the window reaches 16 MB, a request takes about 2.6 s, so roughly every 25 seconds of media the sound stops for over two seconds, an underrun warning fires, and the picture holds.

**Suggested fix:** Size each request by time rather than bytes: use the existing `SyncDownloadMeter` rate to keep a request well under the worklet queue plus what the engine has buffered, and fetch more often. A longer term fix is to read through the asynchronous codec modules (JSPI or Asyncify) so the thread is free while bytes arrive. Add a browser test whose test server throttles its answers and that asserts `stats.audioUnderruns` stays at zero.

**How to check:** Serve a clip through `scripts/web-tests/probe.mjs` with a delay per 64 KB, play it through `KitePlayerWorker`, and read the underrun count and the warning events. Statically, read `SyncHttpMediaIo.fetch` beside `WebAudioSink.highWaterFrames`.

### WEB-02. A worker player on a slow connection fails a long file with a stall error while bytes are still arriving

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | high | Confirmed | high | none |

**Second check:** Upheld at high by the second reader, who also found the reason no retry happens: `network.recovery` defaults to null (`PlayerConfig.kt:166`) and the worker runs plain `PlayerConfig()` defaults. Line corrections: `xhr.timeout = s.timeout` is at `SyncHttpMediaIo.kt:332` and the timeout branch at line 241. Same root cause as NET-01; post one issue.

**Same root as:** NET-01. Post these as one issue.

**Where:** `KitePlayer/kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/WorkerRuntime.kt:75`, `KitePlayer/kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/SyncHttpMediaIo.kt:99`, `KitePlayer/kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/SyncHttpMediaIo.kt:319`, `KitePlayer/kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/SyncHttpMediaIo.kt:238`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/PlayerConfig.kt:268`

**What happens:** The worker hands the player's stall timeout, 30 seconds by default, to every request as the browser's request timeout. A browser's request timeout limits the whole request, from send to the last byte, not the time spent without receiving anything. The window grows to 16 MB, so on any link slower than about 4.5 Mbit/s a full-size window cannot finish in 30 seconds. The browser aborts it, the reader reports the source as stalled, and playback fails, although the connection was delivering faster than the media plays.

**Evidence:**

```kotlin
// WorkerRuntime.kt:75
network = defaults.network.copy(ioResolver = SyncHttpResolver(defaults.buffer.stallTimeout)),
// SyncHttpMediaIo.kt:99
private val state: JsAny = xhrState(headers.toJsObject(), timeout.inWholeMilliseconds.toDouble())
// SyncHttpMediaIo.kt:319 (inside xhrFetch)
xhr.timeout = s.timeout;
// SyncHttpMediaIo.kt:238
TIMED_OUT -> throw PlaybackException(PlaybackError.SourceStalled(url, timeout))
```

The class KDoc also says a server that ignores the range sends the whole file and every read is answered from it, so such a server puts the whole file under the same 30 second limit.

**Failure scenario:** A viewer on a 3 Mbit/s mobile link plays a 200 MB MP4 encoded at 2 Mbit/s. As reads run forward the window reaches 16 MB, which needs about 43 seconds at 3 Mbit/s. At 30 seconds the request times out and the player fails with a stall error. On any link, a file from a server that ignores `Range` and takes more than 30 seconds to download fails the same way at the open.

**Suggested fix:** Keep each request short by sizing it from the measured rate (the same change as WEB-01), and give the request a timeout derived from its own size and that rate rather than the stall budget. Count a stall only when a request returns no bytes within the stall timeout.

**How to check:** Read `fetch` and `xhrFetch` in `SyncHttpMediaIo.kt`; the XMLHttpRequest standard defines `timeout` as the whole fetch's limit. A throttled test server serving 16 MB at 3 Mbit/s reproduces it.

### WEB-03. A page whose worker binary is a different version hangs for ever on any call the worker cannot read

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | medium | Confirmed | high | none |

**Same root as:** API-02. Post these as one issue.

**Where:** `KitePlayer/kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/WorkerProtocol.kt:1240-1258`, `KitePlayer/kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/WorkerProtocol.kt:1297-1323`, `KitePlayer/kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/WorkerProtocol.kt:1268`, `KitePlayer/kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/WorkerRuntime.kt:44`, `KitePlayer/kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/KitePlayerWorker.kt:504-512`, `KitePlayer/README.md:165-169`

**What happens:** When the worker receives a call whose command it does not know, or whose values fail to decode, it drops the message silently and never replies. The page's call waits for its reply with no time limit, so the caller's coroutine never returns. The page side already turns an unreadable reply into a failure, but the worker side has no matching rule. The worker binary ships in its own zip on the GitHub release, separately from the Maven dependency, and the hello and init messages carry no protocol version, so a page and a worker from different releases are never told apart.

**Evidence:**

```kotlin
// WorkerProtocol.kt:1250-1253
"call" -> PageMessage.Call(
    o.int("id") ?: missing("id"),
    decodeCommand(o.child("command") ?: missing("command")) ?: return null,
)
// ... }.getOrNull()   (line 1258), and decodeCommand ends with `else -> null` (line 1322)
// WorkerRuntime.kt:44
val message = decodePageMessage(data) ?: return
// KitePlayerWorker.kt:508-511
val reply = CompletableDeferred<Answer?>()
pending[id] = reply
post(PageMessage.Call(id, command))
return reply.await()
// WorkerProtocol.kt:1268
"hello" -> WorkerMessage.Hello
```

A control that cannot be decoded is dropped the same way (line 1255), without the `CommandRefused` warning the facade KDoc promises for a refused setter.

**Failure scenario:** An app moves to a KitePlayer release that adds a worker command, while its server or CDN still serves the previous release's `kiteplayer-web-worker.mjs`. Every call to the new member is decoded to null in the worker and ignored, so the call never returns and never throws, and nothing in the console says why.

**Suggested fix:** Put a protocol version in `Hello` and fail `start()` with a clear `PlaybackError` when it differs. In the worker, read the call's id before the command and reply with a failure when the command cannot be decoded, and post `CommandRefused` for a control that cannot be decoded.

**How to check:** In `WorkerProtocolTest`, hand `decodePageMessage` a call with an unknown command type and see it return null; then follow `WorkerRuntime.receive` to confirm nothing is posted.

### WEB-04. The worker player's lip sync uses an output latency read once at start and never again

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | medium | Confirmed | high | none |

**Where:** `KitePlayer/kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/KitePlayerWorker.kt:700-706`, `KitePlayer/kiteplayer-output/src@wasmJs/io/github/yuroyami/kiteplayer/output/WebWorkerAudio.kt:107`, `KitePlayer/kiteplayer-output/src@wasmJs/io/github/yuroyami/kiteplayer/output/WebWorkerAudio.kt:115`, `KitePlayer/kiteplayer-output/src@wasmJs/io/github/yuroyami/kiteplayer/output/WebAudioWorkletDevice.kt:31`, `KitePlayer/kiteplayer-output/src@wasmJs/io/github/yuroyami/kiteplayer/output/WebAudioSink.kt:179-188`

**What happens:** The page reads the audio context's output latency once, inside `start()`, right after creating the context and before the user has started it, and sends that number to the worker in the init message. The worker's device keeps it as a constructor value and reports it for the life of the player. The page's own player reads the same figure live on every call. So the worker player never learns of a later change, such as a switch to Bluetooth headphones, and a figure read from a context that was not yet running may be wrong from the start.

**Evidence:**

```kotlin
// KitePlayerWorker.kt:704
latencySeconds = audio?.outputLatencySeconds,
// WebWorkerAudio.kt:107 and :115
private val latencySeconds: Double?,
override fun outputLatencySeconds(): Double? = latencySeconds
// WebAudioWorkletDevice.kt:31 (the page's device, read live)
override fun outputLatencySeconds(): Double? = webAudioLatencySeconds(state).takeIf { it >= 0.0 }
// WebAudioSink.kt:182 adds it to every block's deadline
return clock.nanos() + aheadNanos + outputLatencyNanos()
```

No message type carries a later latency to the worker.

**Failure scenario:** A page starts `KitePlayerWorker` and the user plays on laptop speakers, then connects Bluetooth headphones whose output latency is around 150 to 250 ms. The worker keeps dating sound with the start-time figure, so for the rest of the session the picture runs that far ahead of what the user hears. The page's own `KitePlayer` in the same situation follows the change.

**Suggested fix:** Have the page post the current `outputLatency` to the worker when it changes (polling once a second is cheap), keep it in `WorkletPortDevice` behind a setter, and report that.

**How to check:** Read the three cited lines; then in a browser log `ctx.outputLatency` before and after `resume()` and after changing the output device.

### WEB-05. A worker player given its own libass address can still fall back to plain subtitle styling because the first ASS track starts a second load from beside the worker

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | medium | Confirmed | medium | none |

**Where:** `KitePlayer/kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/WorkerRuntime.kt:62`, `KitePlayer/kiteplayer-libass/src@wasmJs/io/github/yuroyami/kiteplayer/libass/KiteLibassWeb.kt:74-110`, `KitePlayer/kiteplayer-libass/src@wasmJs/io/github/yuroyami/kiteplayer/libass/LibassEngine.wasmJs.kt:200-207`, `KitePlayer/kiteplayer-libass/src@wasmJs/io/github/yuroyami/kiteplayer/libass/LibassEngine.wasmJs.kt:47-54`

**What happens:** The worker starts loading libass from the address the page gave, through `KiteLibassWeb.load`, which records nothing about a load being in flight. If an ASS track opens before that load lands, the engine starts a second, background load from the default `./kiteass.mjs`, which a worker resolves against its own script. When that default address does not hold the module and fails first, the failure is recorded, and the waiting engine throws on its next call and hands the track to the built-in styling, although the real module is still on its way. The code's own comment anticipates two loads side by side but only guards the case where the explicit one lands first.

**Evidence:**

```kotlin
// WorkerRuntime.kt:62
init.libassUrl?.let { url -> scope.launch { runCatching { KiteLibassWeb.load(url) } } }
// KiteLibassWeb.kt:74-79 (load sets neither `loading` nor `loadStarted`)
public suspend fun load(url: String = DEFAULT_URL) {
    if (module != null) return
    val loaded = importModule(url).await<JsAny>()
// KiteLibassWeb.kt:83 and :103-105
if (module != null || loading || loadFailure != null) return
...
if (module == null) {
    loadFailure = IllegalStateException("kiteass.mjs could not be loaded from $url: ...")
// LibassEngine.wasmJs.kt:202-204
if (module == null) {
    if (KiteLibassWeb.loadFailure != null) return null
    KiteLibassWeb.loadInBackground()
// LibassEngine.wasmJs.kt:51
KiteLibassWeb.pendingFailure()?.let { throw IllegalStateException(it.message ?: "kiteass.mjs failed to load", it) }
```

The browser test of this path (`KitePlayerWorkerBrowserTest.kt:772`) serves libass beside everything on localhost, where the explicit load lands first.

**Failure scenario:** A site serves `kiteass.mjs` from `https://cdn.example/kite/` and passes that as `libassUrl`, while the worker binary sits in `/app/`. The codec module is cached and libass is not, so the open reaches an ASS track before libass has compiled. The import of `/app/kiteass.mjs` answers 404 within milliseconds, the failure is recorded, and the film's styled subtitles draw in the plain built-in style with a typesetter warning. Every later ASS track in that worker also gets no engine, because `open()` returns null while a failure is recorded.

**Suggested fix:** Have `load` mark the load in flight (set `loading` and `loadStarted`) so `loadInBackground` waits for it, or let the worker set the default address that `loadInBackground` uses from `Init.libassUrl`.

**How to check:** In a libass web test, replace `importModule` with one that answers the explicit address after 500 ms and rejects the default address at once, open an engine in between, and watch `pendingFailure()`.

### WEB-06. The browser-only web tests pass as green when their setup is missing, so a broken mount would hide every worker and format test

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | test | medium | Confirmed | high | none |

**Where:** `KitePlayer/kiteplayer-web-worker/test/io/github/yuroyami/kiteplayer/worker/KitePlayerWorkerBrowserTest.kt:75-79`, `KitePlayer/kiteplayer-web-worker/test/io/github/yuroyami/kiteplayer/worker/KitePlayerWorkerBrowserTest.kt:862`, `KitePlayer/kiteplayer-ffmpeg/test@wasmJs/io/github/yuroyami/kiteplayer/ffmpeg/WebFormatMatrixTest.kt:36-40`, `KitePlayer/kiteplayer-output/test@wasmJs/io/github/yuroyami/kiteplayer/output/WebSubtitleRasterizerTest.kt:65`, `KitePlayer/scripts/web-tests/run.py:87`, `KitePlayer/scripts/web-tests/harness.mjs:41-55`, `KitePlayer/.github/workflows/ci.yml:1209-1218`

**What happens:** All 18 tests in `kiteplayer-web-worker`, which are the module's only tests, return early when the config the browser runner fills is missing, and the harness counts a test that returns as passed. So do the browser format matrix and the subtitle rasterizer tests when a canvas is missing. The runner fails a module only on a failure or on zero passes, and CI's conformance step only warns when the matrix printed no table. KiteFFmpeg's own runner guards the same pattern with `--require-modules`; KitePlayer's has no equivalent.

**Evidence:**

```kotlin
// KitePlayerWorkerBrowserTest.kt:75-79
val setup = karmaWorkerConfig()?.split("\n")
if (setup == null) {
    println("skipped: the worker player runs only in the browser half, where karma serves the worker")
    return@runTest
}
// KitePlayerWorkerBrowserTest.kt:862, and the same at 873, 892, 908
val setup = karmaWorkerConfig()?.split("\n") ?: return@runTest
```

```python
# run.py:87
if process.returncode != 0 or not result or int(result.group(2)) > 0 or int(result.group(1)) == 0:
```

```yaml
# ci.yml:1218
echo "::warning::The browser format matrix left no conformance report"
```

**Failure scenario:** A change renames the `kiteWorker` config key in `run.py`, or `browser-runner.mjs` stops setting `__karma__`. All 18 worker tests print "skipped" and pass, the runner sees 18 passed and none failed, and the format matrix passes with a CI warning. A regression that stops the worker playing anything ships with CI green.

**Suggested fix:** Pass `required: true` inside the browser config that `run.py` builds and make each browser-only test fail instead of returning when its setup is missing in that mode, as KiteFFmpeg's `--require-modules` does. Turn the missing conformance table into an error.

**How to check:** Run `python3 scripts/web-tests/run.py --platform wasmJsBrowser --module kiteplayer-web-worker` with the `config` line in `browser_setup` commented out and read the RESULT line.

### WEB-07. The published web codec modules are built with an emscripten no test ever runs

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KiteFFmpeg | test | medium | Confirmed | high | none |

**Where:** `KiteFFmpeg/.github/workflows/ci.yml:285-301`, `KiteFFmpeg/.github/workflows/publish.yml:182-227`, `KiteFFmpeg/.github/workflows/publish.yml:103-108`

**What happens:** The CI job for the web codec module installs whatever emscripten Homebrew offers that day, builds the web FFmpeg and links the modules with it, and runs the web tests against that build. The publish workflow runs the whole of CI first and then builds both web jars again with emsdk pinned at 6.0.11, checks only that the expected files are in the jars, and uploads them. So the bytes that go to Maven Central are never loaded by any test, and the compiler that was tested is not the one that shipped. The CI job also hardcodes the FFmpeg tag `n9.0.2` in its clone and cache key instead of using the workflow's `FFMPEG_VERSION`.

**Evidence:**

```yaml
# ci.yml:285-289
- name: Install emscripten
  id: emscripten
  run: |
    brew install emscripten
# ci.yml:301
git clone --depth 1 --branch n9.0.2 https://github.com/FFmpeg/FFmpeg.git vendor/ffmpeg
# publish.yml:184 and :197-198
EMSCRIPTEN_VERSION: "6.0.11"
./kotlin build -m kiteffmpeg-web -m kiteffmpeg-web-async
./kotlin check webPackage
```

The checks after it are `unzip -l` presence checks (publish.yml:208-227); no step of the publish job runs `scripts/web-tests/run.py`.

**Failure scenario:** Homebrew moves to an emscripten whose JSPI or Asyncify output differs from 6.0.11 in a way CLAUDE.md already warns about, such as how a parked export handles a 64-bit value. CI stays green on the Homebrew build while the 6.0.11 build that ships carries a defect no run executed, or the reverse, CI goes red for a Homebrew-only break while the release compiler is fine.

**Suggested fix:** Install the same pinned emsdk in the CI job from one shared `EMSCRIPTEN_VERSION`, use `${FFMPEG_VERSION}` for the clone and key, and in the publish job run `run.py --platform wasmJs --platform wasmJsBrowser --require-modules` against the jars just built, before the upload.

**How to check:** Read the two cited blocks side by side and search `publish.yml` for `run.py`.

### WEB-08. The shipped libass web module is compiled and linked by three different unpinned emscripten versions

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| Both | risk | medium | Plausible | medium | none |

**Where:** `KiteFFmpeg/.github/workflows/release-ass-chain.yml:87-89`, `KitePlayer/.github/workflows/ci.yml:1172-1175`, `KitePlayer/.github/workflows/publish.yml:129-130`

**What happens:** The wasm32 libass chain archives (libass, HarfBuzz, FreeType, FriBidi) are compiled in KiteFFmpeg's release job with Homebrew's emscripten of that day. KitePlayer's CI links `kiteass.mjs` from those archives with the `latest` emsdk, and KitePlayer's publish links the shipped `kiteass.wasm` with Homebrew's emscripten of the release day. None of the three is pinned or recorded, so the libass module that ships is linked by a compiler CI never used, against objects built by possibly a third. KiteFFmpeg pins emscripten for its own web modules for exactly this reason.

**Evidence:**

```yaml
# KiteFFmpeg release-ass-chain.yml:87-89
- name: Install emscripten
  if: matrix.target == 'wasm32'
  run: brew install emscripten
# KitePlayer ci.yml:1173-1175
- uses: mymindstorm/setup-emsdk@6ab9eb1bda2574c4ddb79809fc9247783eaf9021 # v14
  with:
    version: latest
# KitePlayer publish.yml:129-130
- name: Install emscripten
  run: brew install emscripten
```

KiteFFmpeg's publish says it pins emsdk "so a later publish cannot ship different bytes from the same inputs" (publish.yml:179-182), and the KitePlayer CLAUDE.md records one break already caused by a newer emscripten clang in the HarfBuzz build.

**Failure scenario:** Emscripten changes its object format version or a system library layout between the day the chain was baked and the day KitePlayer publishes. The link either fails at release time only, or succeeds and the module misbehaves at run time on ASS tracks in browsers, with no CI run having used that combination. Two publishes of one KitePlayer commit can also ship different libass bytes.

**Suggested fix:** Pin one emsdk version in all three workflows, record it in the chain's build info and in `kiteplayer-libass/ass-chain.sha256`, and have KitePlayer's link step refuse a chain baked by a different emcc version. The KiteFFmpeg half (pinning and recording the version of the chain it releases) needs no knowledge of KitePlayer.

**How to check:** Read the three cited steps; `emcc --version` is not written anywhere in the chain's release assets.

### WEB-09. The worker player silently lacks playlist, subtitle-line and item-detail calls that its KDoc and the README say it has

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | docs | medium | Confirmed | high | none |

**Where:** `KitePlayer/kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/KitePlayerWorker.kt:40-69`, `KitePlayer/README.md:165-169`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/KitePlayer.kt:1072`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/KitePlayer.kt:1080`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/KitePlayer.kt:1296`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/KitePlayer.kt:1312`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/KitePlayer.kt:448`

**What happens:** The README says the worker "has the same calls and flows as `KitePlayer`, except for a few that its KDoc lists", and the KDoc has a "What does not cross" list. Comparing public members, the worker also lacks `openPlaylist`, `readPlaylist`, `seekToSubtitleLine`, `stepSubtitleDelay`, `setItemDetails`, `setControlsInset`, `redrawPicture`, `losslessEvents` and the `keyframeChoice` getter, and none of these is listed. `openPlaylist` and `readPlaylist` read network playlists, which on the web only the worker could do, so a web app has no documented way to open a network playlist at all.

**Evidence:**

```kotlin
// KitePlayer.kt:1072 and :1080
public suspend fun readPlaylist(uri: String, headers: Map<String, String> = emptyMap()): List<MediaItem> =
public suspend fun openPlaylist(uri: String, startIndex: Int = 0, headers: Map<String, String> = emptyMap()) {
// KitePlayer.kt:1296, :1312, :448
public suspend fun seekToSubtitleLine(offset: Int = 0): Duration {
public suspend fun stepSubtitleDelay(offset: Int): Duration = core.stepSubtitleDe...
public suspend fun setItemDetails(title: String?, artist: String?, album: String?)
```

A search of `KitePlayerWorker.kt` finds none of the nine names. The KDoc list (lines 57-69) names subtitleCues, coverArt, position, audioClock, transportMark, awaitClose, the time-of-day calls, inspect, scanAudio, captureFrame, thumbnailAt, recording, memento, restore, renderer and tap attachment, and setExternalClock. The web media session (`session/WebMediaSession.kt:32`) takes only a `KitePlayer`, which the KDoc acknowledges only indirectly ("a worker has none").

**Failure scenario:** A web radio app calls `openPlaylist("https://example.com/stations.m3u")`. The page's player cannot read the network and the worker player has no such member, and the docs never say so. A language-learning app that uses `seekToSubtitleLine` on every other platform finds it missing on the web only.

**Suggested fix:** Add the members that are one command each (`openPlaylist`, `readPlaylist`, `seekToSubtitleLine`, `stepSubtitleDelay`, `setItemDetails`) to the protocol, and list the rest in the KDoc's "What does not cross", softening the README sentence to match.

**How to check:** Diff the public member names of `KitePlayer.kt` against `KitePlayerWorker.kt`.

### WEB-10. Closing a worker player can leave other calls that were still waiting suspended for ever

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | risk | low | Plausible | low | none |

**Where:** `KitePlayer/kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/KitePlayerWorker.kt:535-540`, `KitePlayer/kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/KitePlayerWorker.kt:606-613`, `KitePlayer/kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/WorkerRuntime.kt:189-202`

**What happens:** When the close reply arrives, the page's `finish()` completes only the close and terminates the worker; any other call still waiting keeps its deferred open. The two other ending paths, a close the worker did not answer and a dead worker, both fail every waiting call. In the worker, close posts its reply, cancels its scope and calls `self.close()`, so a command coroutine that had not yet posted its reply by then never does, and the page has terminated the worker anyway.

**Evidence:**

```kotlin
// KitePlayerWorker.kt:536-539
val reply = pending.remove(message.id) ?: return
...
if (message.id == closeId) finish()
// KitePlayerWorker.kt:606-613, finish() has no loop over `pending`
workerTerminate(worker)
releaseAudio()
closeId?.let { pending.remove(it) }?.complete(null)
// compare endUnanswered, KitePlayerWorker.kt:474-476
val waiting = pending.values.toList()
pending.clear()
waiting.forEach { it.completeExceptionally(PlaybackException(error)) }
```

Downgraded from medium: in the usual order the player's close completes its own pending commands first, their coroutines are dispatched before the close coroutine resumes, and their replies reach the page before the close reply. The hole is a command whose suspension is not ended by the player's close, which could not be shown statically.

**Failure scenario:** A call that is still waiting when the close reply arrives stays suspended for the life of its scope, instead of failing with the closed-player error, breaking the rule that every command reply completes exactly once.

**Suggested fix:** In `finish()`, fail every deferred still in `pending` other than the close, as `endUnanswered` and `died` do. It is three lines and removes the dependence on message order.

**How to check:** Read `finish()` beside `endUnanswered()`; a test with `onWorkerForTest` can post a close reply while another call is pending and check that call completes.

### WEB-11. The worker player's sound still crosses into JavaScript one sample at a time, and the KDoc wrongly says a worker would remove the copy

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | docs | low | Confirmed | high | none |

**Where:** `KitePlayer/kiteplayer-output/src@wasmJs/io/github/yuroyami/kiteplayer/output/WebWorkerAudio.kt:117-122`, `KitePlayer/kiteplayer-output/src@wasmJs/io/github/yuroyami/kiteplayer/output/WebWorkerAudio.kt:212-217`, `KitePlayer/kiteplayer-output/src@wasmJs/io/github/yuroyami/kiteplayer/output/WebAudioWorkletDevice.kt:33-43`, `KitePlayer/kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/KiteWebModules.kt:17-20`

**What happens:** The worker's audio device stages each sample with its own call into JavaScript, about 96,000 calls a second for 48 kHz stereo, then copies the block again with `slice` and once more through `postMessage` without a transfer list. The code says so openly. The page device's KDoc, however, says the fix is "the Worker, where `SharedArrayBuffer` becomes legal and the ring stops being copied at all". That is not true: `KiteWebModules`' own KDoc says a worker is cross-origin isolated exactly when its page is, so a worker gets no `SharedArrayBuffer` the page lacks.

**Evidence:**

```kotlin
// WebWorkerAudio.kt:117-121
/** The same per-sample crossing as the page's sink, now on the worker's thread instead of the page's. */
override fun enqueue(samples: FloatArray, frames: Int) {
    val count = frames * channels
    for (i in 0 until count) webPortStage(port.state, i, samples[i])
// WebWorkerAudio.kt:215
s.port.postMessage({ cmd: 'push', samples: s.staging.slice(0, n) });
// WebAudioWorkletDevice.kt:41-42
 * The fix is not a smarter loop here. It is either a typed-array bridge in the language, or the
 * Worker, where `SharedArrayBuffer` becomes legal and the ring stops being copied at all.
// KiteWebModules.kt:19-20
 * exactly when the page that started it is ...
```

Downgraded from a medium defect: the per-sample cost is real but small (a direct import call each), the code labels it honestly, and the measurable harm on the worker thread was not shown. The untrue KDoc is what remains.

**Failure scenario:** A contributor reads the page device's KDoc, believes the worker path removed the copy, and does not look for the per-sample loop that now runs on the worker's one thread beside demux and decode.

**Suggested fix:** Correct the KDoc. When convenient, cross per block (pack the floats into one string, or stage through the bytes path) and transfer the block's buffer in `postMessage`.

**How to check:** Read the two KDocs side by side.

### WEB-12. The page player starts the browser's audio only after an await, which its own web docs say a browser refuses

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | risk | low | Plausible | low | none |

**Where:** `KitePlayer/kiteplayer-output/src@wasmJs/io/github/yuroyami/kiteplayer/output/WebAudioSink.kt:81-85`, `KitePlayer/kiteplayer-output/src@wasmJs/io/github/yuroyami/kiteplayer/output/WebAudioWorkletDevice.kt:52-54`, `KitePlayer/kiteplayer-output/src@wasmJs/io/github/yuroyami/kiteplayer/output/WebWorkerAudio.kt:37-41`, `KitePlayer/kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/KitePlayerWorker.kt:163-171`, `KitePlayer/scripts/web-tests/run.py:122`

**What happens:** The worker facade resumes the audio context synchronously inside `play()` because, as its KDoc says, "a browser starts audio only inside a user gesture, so call this from the gesture's own handler, not after an await". The page's own player reaches `ctx.resume()` only through the actor and the sink's `start`, so after at least one dispatch and outside the gesture's call stack. Chrome accepts that once the page has had any interaction. Whether WebKit does in every case could not be checked here, and the only browser tests run Chrome with the autoplay policy switched off.

**Evidence:**

```kotlin
// WebWorkerAudio.kt:37-41
 * Starts the device. A browser starts audio only inside a user gesture, so call this from the
 * gesture's own handler, not after an await.
// KitePlayerWorker.kt:168-170
checkOpen()
audio?.resume()
send(Control.Play)
// WebAudioSink.kt:83 and WebAudioWorkletDevice.kt:53, the page path
device.resume()
awaitJs(webAudioResume(state))
```

```python
# run.py:122
flags.append("--autoplay-policy=no-user-gesture-required")
```

**Failure scenario:** On Safari, a user taps play on a page that uses the page's `KitePlayer`. If WebKit refuses the late resume, the audio-mastered clock never moves and the picture sits at position zero although the user did interact.

**Suggested fix:** Give the page player the worker's shape: resume a pre-created context synchronously from `play()` on wasmJs before posting to the actor. Add one browser test without the autoplay flag that clicks through the DevTools protocol and checks that the position moves.

**How to check:** On Safari for macOS and iOS, open a page with the page player, tap play, and read `ctx.state` and the position.

### WEB-13. A relative linked-file or thumbnail address in a worker item is never read as a network address

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | low | Confirmed | high | none |

**Where:** `KitePlayer/kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/KitePlayerWorker.kt:491-499`, `KitePlayer/kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/SyncHttpMediaIo.kt:25-27`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/MediaItem.kt:649-651`

**What happens:** Before an item crosses to the worker, the facade turns its own address, its external subtitles and its external audio into whole addresses against the page, but leaves linked files and the thumbnail file as given. A linked file's address is read "as `MediaItem.uri` is", not against the item's address, so a relative one reaches the worker's resolver with no scheme. The resolver then answers null, the backend tries it as a file path, and the file is not found. (The auditor thought it would be fetched against the worker script; the resolver's scheme check means it is not fetched at all, with the same outcome.)

**Evidence:**

```kotlin
// KitePlayerWorker.kt:494-498
return media.copy(
    uri = pageAddress(media.uri),
    externalSubtitles = media.externalSubtitles.map { it.copy(uri = pageAddress(it.uri)) },
    externalAudio = media.externalAudio.map { it.copy(uri = pageAddress(it.uri)) },
)
// SyncHttpMediaIo.kt:26-27
val scheme = uri.substringBefore(':', missingDelimiterValue = "").lowercase()
if (scheme != "http" && scheme != "https" && scheme != "blob") return null
```

**Failure scenario:** A page opens `MediaItem("film.mkv", linkedFiles = listOf(LinkedFile("opening.mkv")))`. The film plays, but the ordered edition's shared opening is never found, and a relative `ThumbnailSource("thumbs.vtt")` gives no scrub previews, while the same item with absolute addresses works.

**Suggested fix:** Map `linkedFiles` and `thumbnails` through `pageAddress` in `crossing()`, as `externalAudio` already is.

**How to check:** Read `crossing()`; a worker browser test with a relative thumbnail file shows it.

### WEB-14. The common tests of kiteplayer-io never run on wasmJs, and the coverage check cannot see a common test folder at all

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | test | low | Confirmed | high | none |

**Where:** `KitePlayer/scripts/web-tests/run.py:23-43`, `KitePlayer/scripts/check-test-coverage.py:24-33`, `KitePlayer/kiteplayer-io/module.yaml:6-26`, `KitePlayer/kiteplayer-io/test/io/github/yuroyami/kiteplayer/io/ReadSliceTest.kt:10`

**What happens:** `kiteplayer-io` declares js and wasmJs, and its common `test/` folder has one concrete class, `ReadSliceTest`, with two tests (the other eleven are in abstract contract classes for platform readers). The web runner lists the module for JS but not for either Wasm run, so its common code is tested on JS and never on Wasm. The coverage script maps only platform test folders and never asks whether a module's common tests run on each platform it declares, so it cannot report this or any similar gap in another module. The auditor's claim that the worker reads through kiteplayer-io types is not true (the worker reader implements the core's `MediaIo`), so the practical risk is small.

**Evidence:**

```python
# run.py:23-31 has "kiteplayer-io" in JS_MODULES; WASM_NODE_MODULES (32-42) does not
# check-test-coverage.py:24-33
FOLDERS = {
    "test@jvm": "jvm",
    ...
    "test@wasmJs": "wasmJs",
    "test@js": "js",
}
```

**Failure scenario:** A change to `ReadSlice` relies on behaviour that differs under Kotlin/Wasm; JVM, native and JS pass, and the Wasm build is never exercised. More broadly, a new module whose only tests are common ones can be left out of the Wasm lists with the coverage check green.

**Suggested fix:** Add `kiteplayer-io` to `WASM_NODE_MODULES`, and teach the coverage script to treat a common `test/` folder as owed on every platform the module declares that CI runs.

**How to check:** Read the two lists in `run.py` and the `FOLDERS` map.

### WEB-15. CLAUDE.md and the web error messages still describe a karma timeout file and describe the worker as future work

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | docs | low | Confirmed | high | none |

**Where:** `KitePlayer/CLAUDE.md:130-133`, `KitePlayer/scripts/web-tests/harness.mjs:1-3`, `KitePlayer/kiteplayer-ffmpeg/src@wasmJs/io/github/yuroyami/kiteplayer/ffmpeg/BlockingMediaIo.wasmJs.kt:26-27`, `KitePlayer/kiteplayer-ffmpeg/src@wasmJs/io/github/yuroyami/kiteplayer/ffmpeg/BlockingMediaIo.wasmJs.kt:46-52`, `KitePlayer/kiteplayer-ffmpeg/src@wasmJs/io/github/yuroyami/kiteplayer/ffmpeg/BlockingMediaIo.wasmJs.kt:117-122`

**What happens:** CLAUDE.md tells a contributor that every module running browser tests needs a timeout config file and to "copy the one that has it", but no such file exists in the tree, and CI now runs browser tests through `scripts/web-tests`, which has no per-test limit (only a 45 minute limit per module in `browser.mjs`). The error a page user sees when a source suspends still tells them to run the player in a worker, which "is tracked as an issue", and the KDoc calls the worker the future general fix and leaves a known limit "to whoever writes that Worker". `KitePlayerWorker` exists and is the README's supported route for network media.

**Evidence:**

```text
CLAUDE.md:130-132: "A browser test that runs longer than two seconds is killed rather than failed ...
Every module that runs browser tests needs a timeout config file; copy the one that has it."
harness.mjs:3: "runs them one at a time with no time limit of its own."
```

```kotlin
// BlockingMediaIo.wasmJs.kt:50-51
"an ArrayBuffer), or run the player in a Worker where blocking is legal " +
    "and is tracked as an issue.",
```

**Failure scenario:** A contributor hunts for a timeout file that does not exist. An app developer whose page player refuses an https item reads that a worker is only an open issue and does not learn that `KitePlayerWorker.start` already plays it.

**Suggested fix:** Rewrite the CLAUDE.md line to describe `scripts/web-tests` (and keep only what still applies to `./kotlin test -p wasmJs`), and change the error and the KDoc to name `KitePlayerWorker.start`.

**How to check:** `find . -path ./build -prune -o -name 'karma.config.d' -print` finds nothing; read the cited lines.

#### Opportunities

### WEB-16. HDR video on the web canvas is tone mapped after it has been cut to 8 bits, so smooth gradients can band

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KiteFFmpeg | opportunity | low | Confirmed | medium | none |

**Same root as:** VID-01, BRG-05. Post these as one issue.

**Where:** `KiteFFmpeg/native/kitecodec-c/src/helpers_display.c:1-13`, `KiteFFmpeg/native/kitecodec-c/src/helpers_display.c:244-261`, `KiteFFmpeg/native/kitecodec-c/src/helpers_display.c:298-311`, `KiteFFmpeg/kiteffmpeg/src@wasmJs/io/github/yuroyami/kiteffmpeg/WebRgba.kt:84`

**What happens:** The display conversion first turns a 10-bit PQ or HLG frame into 8-bit RGB with swscale's fast path, and then applies the transfer curve, the roll-off and the gamut fold to those 8-bit codes through 256-entry tables. In 8-bit PQ the range up to reference white gets only about 150 codes before the curve, so a slow sky or a fade loses steps the 10-bit source had. The file's header says this is deliberate, so that it matches a Kotlin software converter step for step, which is why this is listed as an opportunity rather than a defect. That header also names KitePlayer, which a KiteFFmpeg source comment should not need to.

**Evidence:**

```c
/* helpers_display.c:12-13 */
 *   BT.2390 EETF from a 1000 nit peak to 203 nit reference white, then gamma 2.2. The law runs on
 *   the 8-bit RGB bytes, as the Kotlin one does, so the two paths give the same picture. */
/* helpers_display.c:302 */
dst = kc_is_ycgco(src) ? kc_convert_ycgco(src, dst_fmt, &lay) : kc_sws_convert(src, dst_fmt, KC_SWS_FAST);
/* helpers_display.c:258-261, the table index is the 8-bit code */
last_r = p[lay->r];
...
r = eotf[last_r];
```

**Failure scenario:** A 10-bit HDR10 clip with a slow dark-to-bright gradient shows contour bands on the web canvas that a GPU renderer working at higher precision does not show.

**Suggested fix:** Convert to a 16-bit RGB intermediate, apply the tone map at that precision with a larger table or direct evaluation, then pack to 8 bits with optional dither, and measure the cost against the per-frame budget. Reword the header so it describes the law on its own terms without naming the consuming player.

**How to check:** Play a 10-bit PQ gradient clip through the web canvas renderer and compare with the same frame tone mapped from a 16-bit intermediate.

#### Rejected during verification

None. Two findings were downgraded (WEB-10 from medium to low, because the usual message order already completes the other calls; WEB-11 from a medium defect to a low documentation finding, because the per-sample cost is labelled in the code and its harm was not shown). Three had their claims corrected: WEB-13 (the relative address is not fetched at all rather than fetched against the worker script), WEB-14 (two concrete common tests, not thirteen, and the worker does not read through kiteplayer-io types), and WEB-16 (moved to opportunities because the 8-bit order is a documented parity choice).


## 10. Public API, distribution, documentation truth, capability matrix

This lane read the public face of both libraries: both READMEs, the KitePlayer docs on the player API, the module contract, the backend cookbook and the web setup, KiteFFmpeg's platform and licensing pages, the web loader and the desktop JNI loader, the web worker protocol, the Compose entry point, the Java wrapper, the network transport's client construction, the publish and web zip scripts, and the licence files the artifacts carry. Every finding below was re-opened and re-traced by the verifier, and line numbers were corrected where they had drifted. Nothing was built, linked or run, so three things stay unproven by a real build: what an Xcode link of a static framework needs, how Kotlin/Native writes `kotlin.time.Duration` into the Swift header, and which Ktor engine actually wins when an app adds a second one. Those three are marked Plausible and say what would settle them. The size and timing figures in the docs were not checked, and the Android AAR layout was not unpacked.

### API-01. A web page that serves the codec module of another KiteFFmpeg release is accepted and then read at the wrong offsets

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KiteFFmpeg | risk | medium | Confirmed | high | none |

**Where:** `KiteFFmpeg/kiteffmpeg/src@wasmJs/io/github/yuroyami/kiteffmpeg/KiteFFmpegWeb.kt:55-78`, `KiteFFmpeg/kiteffmpeg/src@wasmJs/io/github/yuroyami/kiteffmpeg/KiteFFmpegWeb.kt:343-345`, `KiteFFmpeg/kiteffmpeg/src@wasmJs/io/github/yuroyami/kiteffmpeg/KiteFFmpegWeb.kt:186-196`, `KiteFFmpeg/kiteffmpeg/src@wasmJs/io/github/yuroyami/kiteffmpeg/WebIdentity.kt:15-46`, `KiteFFmpeg/kiteffmpeg/src@wasmJs/io/github/yuroyami/kiteffmpeg/wasm/KiteFFmpegWasm.kt:693-694`, `KiteFFmpeg/kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/Internals.jvm.kt:322-328`

**What happens:** On the web, FFmpeg lives in a separate module (`kite.mjs` and `kite.wasm`) that the page serves as plain files, while the Kotlin code that reads it comes from Maven. The two can come from different releases. `KiteFFmpegWeb.attach`, which `load` also goes through, only checks that ten Emscripten runtime names exist on the module. It never asks the module which version of the C layout it was built with, although the binding to ask exists (`kc_abi_version`) and has no caller. Every C struct is then read at the byte offsets of the committed layout file that the Kotlin side was generated with. The desktop JVM path compares the C ABI version before it goes on, and the asynchronous web path refuses a module whose bridge version differs, so the plain web path is the one place with no check at all. Even the identity report is read at those offsets, so `FFmpeg.identity` cannot catch a moved struct either.

**Evidence:**

```kotlin
// KiteFFmpegWeb.kt:73-75
val missing = missingRuntimeMethods(codecModule)
if (missing.isNotEmpty()) throw IncompleteModule(missing)
module = codecModule
```

```kotlin
// KiteFFmpegWeb.kt:344
@JsFun("""(m) => ["ccall","UTF8ToString","stringToUTF8","lengthBytesUTF8","addFunction","removeFunction","HEAP32","HEAPU8","_malloc","_free"].filter(k => m[k] === undefined).join(", ")""")
```

```kotlin
// KiteFFmpegWeb.kt:188-194, the asynchronous path does check
val version = asyncBridgeVersion(codecModule)
if (version != ASYNC_BRIDGE_VERSION) {
    throw FFmpegException(FFmpegError.Unsupported(0, "this codec module holds version $version of the asynchronous bridge, ..."))
```

```kotlin
// KiteFFmpegWasm.kt:693-694, bound but never called from the web loader
@JsFun("(m) => m._kc_abi_version()")
internal external fun kc_abi_version(module: JsAny): Int
```

`WebIdentity.kt` reads `cAbiVersion` itself out of the report at `ReportLayout.abiMajor` and `ReportLayout.abiMinor`, so a report whose struct moved answers with whatever sits at the old offsets. The KiteFFmpeg contributor notes say a wrong offset "reads the neighbouring field and answers something plausible".

**Failure scenario:** An app raises its `io.github.yuroyami:kiteffmpeg` dependency to a release whose C structs changed shape, and keeps the `kite.mjs` and `kite.wasm` it unpacked by hand beside its page last time. `KiteFFmpegWeb.load()` succeeds. Frames then come back with plausible but wrong widths, strides or timestamps, and reads may walk past the intended buffer inside wasm memory, with no error anywhere that names the cause.

**Suggested fix:** In `attach`, call `kc_abi_version(codecModule)` before installing the module, compare its major and minor with the version the generated layout belongs to, and throw `FFmpegException(FFmpegError.Unsupported(...))` naming both versions on a mismatch, the same way `attachAsyncRuntime` already refuses a bridge of another version. This is wholly inside KiteFFmpeg.

**How to check:** `grep -rn "kc_abi_version(" KiteFFmpeg/kiteffmpeg/src@wasmJs` finds only the declaration. Read `attach` at KiteFFmpegWeb.kt:55-78 beside the async check at 188-196.

### API-02. A web page whose worker file comes from another KitePlayer version waits for ever on the first call the worker cannot read

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | risk | medium | Confirmed | high | none |

**Same root as:** WEB-03. Post these as one issue.

**Where:** `KitePlayer/kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/WorkerProtocol.kt:189`, `KitePlayer/kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/WorkerProtocol.kt:1240-1257`, `KitePlayer/kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/WorkerProtocol.kt:1297-1323`, `KitePlayer/kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/WorkerRuntime.kt:30-34`, `KitePlayer/kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/WorkerRuntime.kt:43-45`, `KitePlayer/kiteplayer/src@wasmJs/io/github/yuroyami/kiteplayer/KitePlayerWorker.kt:504-512`, `KitePlayer/scripts/web-zips.sh:8-9`

**What happens:** The page half of the worker player is compiled into the app from Maven, while the worker half (`kiteplayer-web-worker.mjs`) is a file the app author copies by hand from a GitHub release zip. The two halves never compare versions. The worker's greeting is a bare object with no version in it, the worker silently drops any call it cannot decode without sending a reply, and the page waits for every reply with no time limit.

**Evidence:**

```kotlin
// WorkerProtocol.kt:189
data object Hello : WorkerMessage
```

```kotlin
// WorkerProtocol.kt:1240, 1251-1253, 1322
internal fun decodePageMessage(data: JsAny?): PageMessage? = runCatching {
    ...
    "call" -> PageMessage.Call(
        o.int("id") ?: missing("id"),
        decodeCommand(o.child("command") ?: missing("command")) ?: return null,
    ...
}.getOrNull()
// decodeCommand: else -> null
```

```kotlin
// WorkerRuntime.kt:44
val message = decodePageMessage(data) ?: return
```

```kotlin
// KitePlayerWorker.kt:505-511
val id = nextId++
val reply = CompletableDeferred<Answer?>()
pending[id] = reply
post(PageMessage.Call(id, command))
return reply.await()
```

`web-zips.sh` and `publish.yml` both say the worker zip is a release-page asset, not part of the Maven publication, so the mismatch is an ordinary upgrade mistake rather than a contrived one.

**Failure scenario:** An app raises its Gradle dependency to the next KitePlayer and keeps the previous release's `kiteplayer-web-worker.mjs` beside `index.html`. A command added in the new release, or an enum value the old decoder does not know (a new `SeekMode`, say, which makes `o.enum<SeekMode>("mode") ?: missing("mode")` throw inside the `runCatching`), reaches the old worker. The worker drops the call, and `seek()` or the new call on the page never returns, with nothing logged. Snapshot fields the old worker never sends decode as their defaults, so the page shows "nothing" instead of failing.

**Suggested fix:** Put the library version, or a protocol number, in `Hello`, and make `KitePlayerWorker.start` fail with a typed error naming both versions when they differ. Separately, make the worker answer a `call` whose id it could read but whose command it could not with a failed reply, so a mismatch that slips through fails one call instead of hanging it.

**How to check:** Read the three excerpts above. A unit test can feed `decodePageMessage` a call with `"command": {"t": "someNewCall"}` and an id, and see that it returns null.

### API-03. The rememberKitePlayer example crashes the app as soon as the address it is keyed on changes

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | docs | medium | Confirmed | high | none |

**Where:** `KitePlayer/kiteplayer-compose/src/io/github/yuroyami/kiteplayer/compose/RememberKitePlayer.kt:14-21`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:2755-2760`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/KitePlayer.kt:142-147`

**What happens:** The KDoc example of the Compose entry point opens media in `LaunchedEffect(url) { player.open(MediaItem(url)); player.play() }`. `open` is legal only when the player is Idle, Ended or Failed, and throws otherwise. When `url` changes after the first item has opened, Compose cancels the old effect and starts a new one, which calls `open` on a player that is Playing or Paused, and that throws inside the composition's coroutine. The README's own sample keys the effect on `player` instead, so it never meets this, but the KDoc on the entry point people read first does.

**Evidence:**

```kotlin
// RememberKitePlayer.kt:14-20
 * val player = rememberKitePlayer()
 * KitePlayerVideo(player, Modifier.fillMaxSize())
 * LaunchedEffect(url) {
 *     player.open(MediaItem(url))
 *     player.play()
 * }
```

```kotlin
// PlaybackCore.kt:2755-2759
is CoreCommand.Open -> when (status) {
    PlaybackStatus.Idle, PlaybackStatus.Ended, PlaybackStatus.Failed -> null
    else -> IllegalStateException(
        "open is legal from Idle, Ended and Failed; the player is $status, so call stop() first",
    )
```

`KitePlayer.open` KDoc (KitePlayer.kt:144-145): "From anything else it throws: replacing what is playing needs an explicit [stop] first".

**Failure scenario:** An app copies the sample into a screen whose `url` comes from a list. The first video opens and plays. The user picks a second one, the effect relaunches, `open` throws `IllegalStateException`, and on Android the uncaught exception from the effect's scope takes the app down.

**Suggested fix:** Change the example to `LaunchedEffect(url) { player.stop(); player.open(MediaItem(url)); player.play() }` and say in one line why the `stop` is there. Keying an effect on the address is the natural Compose idiom, so a replacing open helper may be worth considering separately.

**How to check:** Read the KDoc and `rejectionFor` above. A Compose test that changes the key after the first open returns shows the exception.

### API-04. No artifact carries dav1d's copyright notice and licence text, which its BSD licence requires in every binary

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KiteFFmpeg | defect | medium | Confirmed | high | KiteFFmpeg#185 |

**Same root as:** NAT-04. Post these as one issue.

**Where:** `KiteFFmpeg/kiteffmpeg/resources@android/META-INF/licenses/kiteffmpeg-ffmpeg/THIRD-PARTY.txt:1-9`, `KiteFFmpeg/kiteffmpeg/resources@jvm/META-INF/licenses/kiteffmpeg-ffmpeg/`, `KiteFFmpeg/docs/licensing.md:77`, `KiteFFmpeg/docs/licensing.md:93-94`, `KiteFFmpeg/NOTICE:97-98`

**What happens:** dav1d, the software AV1 decoder, is statically linked into every native, JVM and Android artifact. Its licence is BSD-2-Clause, whose second condition says a binary redistribution must reproduce the copyright notice, the list of conditions and the disclaimer. The licence folder the artifacts ship holds only FFmpeg's LGPL text and a short THIRD-PARTY.txt that names dav1d with a web address and the words "no source obligation". dav1d's copyright line and licence text appear nowhere in the repository outside the vendored trees, so no artifact carries them, and the Apple and other native klibs carry no licence file at all. KiteFFmpeg#185 is about libiconv and IJG notices, which is a different component, so this is a related but new gap.

**Evidence:**

```text
# THIRD-PARTY.txt (both the android and jvm copies)
This artifact embeds compiled FFmpeg libraries (LGPL-2.1-or-later) and dav1d
(BSD-2-Clause) inside its native library. KiteFFmpeg's own code is Apache-2.0.
...
dav1d: https://code.videolan.org/videolan/dav1d (BSD-2-Clause, no source obligation)
```

```text
# docs/licensing.md:93
Permissive components (BSD, zlib) only require you to reproduce their license text and copyright
notice.
```

`ls kiteffmpeg/resources@android/META-INF/licenses/kiteffmpeg-ffmpeg/` lists `COPYING.LGPLv2.1` and `THIRD-PARTY.txt` only, and the same for `resources@jvm`. A search for "dav1d authors" outside `native-libs` and build output finds nothing.

**Failure scenario:** An Android app builds its open source notices screen from the licence files of its dependencies, or copies `META-INF/licenses` as the docs suggest. It shows FFmpeg's LGPL text but not dav1d's notice, so every app that ships KiteFFmpeg is out of compliance with dav1d's licence without knowing it, and the docs tell it the reproduction is its job while giving it nothing to reproduce.

**Suggested fix:** Copy dav1d's `COPYING` from the pinned dav1d checkout at bake time into `META-INF/licenses/kiteffmpeg-ffmpeg/` of the JVM and Android artifacts, so it follows the version, quote the same text in `NOTICE` for native consumers, and reword THIRD-PARTY.txt to say the notice must be reproduced.

**How to check:** `ls KiteFFmpeg/kiteffmpeg/resources@*/META-INF/licenses/kiteffmpeg-ffmpeg/` and read THIRD-PARTY.txt.

### API-05. The README's track sample does not compile against the release it tells people to install

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | docs | medium | Confirmed | high | none |

**Where:** `KitePlayer/README.md:69-81`, `KitePlayer/README.md:289-291`, `KitePlayer/README.md:343`, `KitePlayer/README.md:526-530`, `KitePlayer/CHANGELOG.md:11`, `KitePlayer/CHANGELOG.md:21`, `KitePlayer/CHANGELOG.md:48`, `KitePlayer/CHANGELOG.md:62-65`, `KitePlayer/CHANGELOG.md:490`

**What happens:** The README's install block pins 0.3.0 and then says the page describes `main`, with exactly three parts marked "not released yet". Other things it describes without any mark are also only on `main`, according to the CHANGELOG's [Unreleased] section, which runs from line 11 to line 489, above the 0.3.0 heading at line 490. They include `TrackInfo.isLanguage`, used in the main control sample, `PlaybackError.DrmUnsupported`, named in the limitations, and the CEA-708 caption services in the subtitle list.

**Evidence:**

```text
# README.md:79-81
This page describes `main`, which is ahead of 0.3.0. Three parts are marked *not released yet*:
Matroska editions with linked files, 360 degree video, and `kiteplayer-compose` on the web. They
come with the next release, and code that uses them does not compile against 0.3.0.
```

```kotlin
// README.md:291
val french = tracks.audio.firstOrNull { it.isLanguage("fr") }
```

```text
# CHANGELOG.md, all under [Unreleased]
21: - The CEA-708 caption services inside broadcast video, read in Kotlin on every platform (#678).
48: - `PlaybackError.DrmUnsupported(uri, system)` refuses media protected by digital rights management
62: - `TrackInfo.isLanguage(tag)` tells whether a track is in a language, in any spelling of it (#633).
65:   sample now uses `isLanguage` and `firstOrNull`.
```

**Failure scenario:** A new user adds `kiteplayer:0.3.0` as the README says and pastes the "Control it and read its state" sample. The build fails with "Unresolved reference: isLanguage" in the second sample they try, with no "not released yet" mark to explain it. Code written to catch `DrmUnsupported` does not compile either.

**Suggested fix:** Mark every README claim that is still in [Unreleased] the way the three are marked, or keep the README at the released surface and gather main-only text in one labelled section. A release-time check that greps the README's code samples for public names missing from the last release's API dump would stop the drift coming back.

**How to check:** Read README.md:79 and :291, then `grep -n "isLanguage\|DrmUnsupported\|CEA-708" CHANGELOG.md` and compare the line numbers with the `## [0.3.0]` heading at line 490.

### API-06. An app that adds another Ktor engine silently changes the HTTP stack the player streams through

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | design | medium | Plausible | medium | none |

**Where:** `KitePlayer/kiteplayer-network/src/io/github/yuroyami/kiteplayer/network/KtorMediaIo.kt:44-47`, `KitePlayer/kiteplayer-network/src/io/github/yuroyami/kiteplayer/network/KtorMediaIo.kt:721`, `KitePlayer/kiteplayer-network/src/io/github/yuroyami/kiteplayer/network/KtorMediaIo.kt:977-979`, `KitePlayer/kiteplayer-network/src/io/github/yuroyami/kiteplayer/network/KtorMediaIo.kt:1172`, `KitePlayer/kiteplayer-network/src/io/github/yuroyami/kiteplayer/network/RedirectRule.kt:25`, `KitePlayer/kiteplayer-network/src@jvmAndAndroid/io/github/yuroyami/kiteplayer/network/RedirectRule.jvmAndAndroid.kt:5-6`, `KitePlayer/kiteplayer-network/module.yaml`, `KitePlayer/README.md:366-367`

**What happens:** The automatic transport builds its HTTP clients without naming an engine, so Ktor uses the first engine it discovers on the classpath (through ServiceLoader on the JVM and Android). `kiteplayer-network` adds OkHttp on the JVM and Android and Darwin on Apple, and the README and KDoc promise exactly those. But an app that also has `ktor-client-cio`, `ktor-client-android` or `ktor-client-java` for its own calls puts a second engine on the classpath, and which one the player gets then depends on classpath and dex order. The redirect guard and the redirect visibility hook are written for OkHttp, and the KDoc at line 721 already concedes "from the platform engine on the classpath".

**Evidence:**

```kotlin
// KtorMediaIo.kt:977-979
internal fun itemClient(): HttpClient = HttpClient {
    install(HttpCookies) { storage = AcceptAllCookiesStorage() }
}
```

```kotlin
// KtorMediaIo.kt:1172
private val shared: HttpClient by lazy { client ?: HttpClient().also { created = it } }
```

```kotlin
// RedirectRule.jvmAndAndroid.kt:5-6
// OkHttp leaves every redirect to Ktor, which raises the event a RedirectRule checks.
internal actual fun HttpRequestBuilder.keepRedirectsVisible() = Unit
```

```text
# README.md:366-367
HTTP, HTTPS, HLS and DASH URLs play directly, because every standard entry point includes
`kiteplayer-network` (OkHttp on Android and the JVM, NSURLSession on Apple).
```

**Failure scenario:** A Kotlin Multiplatform app uses `ktor-client-cio` for its backend on Android or the desktop JVM. If CIO is found first, every HTTPS stream the player opens goes through CIO, which brings its own TLS handling rather than the platform's, has no HTTP/2, and treats proxies differently. A server that played yesterday can fail after an unrelated dependency change, and the README's statement about the stack is no longer true for that app.

**Suggested fix:** Name the engine in a small expect/actual factory (OkHttp on jvmAndAndroid, Darwin on Apple, Js on the web) and use it in `itemClient` and the resolver's shared client.

**How to check:** A JVM test with `ktor-client-cio` added to the test classpath that prints `itemClient().engine::class` settles which engine wins. Reading the two constructor calls above confirms that nothing names one today.

### API-07. A Swift app sees every player time as a raw encoded number and has no simple way to listen to the player

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | design | medium | Plausible | medium | KitePlayer#393 |

**Where:** `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/KitePlayer.kt:208-212`, `KitePlayer/kiteplayer/src@jvmAndAndroid/io/github/yuroyami/kiteplayer/KitePlayerJava.kt:366-369`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/PlayerState.kt:222`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/PlayerState.kt:311`, `KitePlayer/README.md:243-245`

**What happens:** The player's calls take and return `kotlin.time.Duration`, for example `seek(to: Duration)`. Kotlin/Native writes a value class into the Objective-C and Swift header as its underlying type, and for `Duration` that is the internal encoded number (nanoseconds shifted left by one bit, with a unit flag in the low bit), not seconds or milliseconds. Java got a whole layer for this in `KitePlayerJava`, with millisecond calls and listeners. Apple got nothing like it: only a few read-side helpers exist in common code (`durationMillis`, `positionMillis`, `MediaItemBuilder.startPositionMillis`), there is no millisecond seek or delay setter, no listener in place of `StateFlow`, and no `ObjCName` or Swift refinement anywhere in the tree.

**Evidence:**

```kotlin
// KitePlayer.kt:208-209
@Throws(Exception::class)
public suspend fun seek(to: Duration, mode: SeekMode = SeekMode.Precise) {
```

```text
# README.md:243-245
On Android and the desktop JVM, `KitePlayerJava` wraps the player for Java code. Listeners run on
an executor that you choose, every suspending call returns a `CompletableFuture`, and times are in
milliseconds where Kotlin takes a `Duration`:
```

`grep -rn "ObjCName\|HiddenFromObjC\|ShouldRefineInSwift"` over the sources finds nothing. The `@Throws` annotations on the suspend calls show the Swift surface is meant to be used.

**Failure scenario:** An iOS team calls `player.seek(to: 90_000, mode: .precise)` from Swift meaning 90 seconds, and lands at 45 microseconds, because the Int64 is read as an encoded `Duration`. Reading `state` needs a hand-written flow collector. The project goal is a simple API for apps outside Kotlin Multiplatform, and the Swift package of #393 would ship this surface as it is.

**Suggested fix:** Add an Apple counterpart to `KitePlayerJava` in `src@apple`: millisecond or `TimeInterval` versions of the calls that take a `Duration`, a listener with a close handle, and mark the `Duration` overloads with `@ShouldRefineInSwift` so Swift sees the usable ones. Do it before #393 publishes the surface.

**How to check:** Build the iOS framework once and read the generated header for `seekTo:mode:`. If the parameter is `int64_t`, the finding holds as written.

### API-08. The README's linker flags for a static iOS framework leave out libraries that libass and the player's Apple code need

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | docs | medium | Plausible | medium | none |

**Where:** `KitePlayer/README.md:98`, `KitePlayer/README.md:109-114`, `KitePlayer/kiteplayer-libass/native/libass.def:17-21`, `KitePlayer/kiteplayer-rt/native/kitert.def:35`, `KiteFFmpeg/kiteffmpeg/interop/ffmpeg.def:30`

**What happens:** The README says Xcode links a static framework itself and needs a given list of Other Linker Flags. That list is KiteFFmpeg's own `linkerOpts.ios` with `-ObjC` in front. The libass cinterop, which `kiteplayer` exports on iOS, declares `-lz -liconv -lc++ -framework CoreText -framework CoreFoundation -framework CoreGraphics`, and the def's own comment says the C++ runtime is there because HarfBuzz needs it. By the README's own reasoning, that a static framework does not carry the cinterop linker options, `-liconv`, `-lc++`, CoreText and CoreGraphics belong in the list too. The player's Apple code also imports Metal, AVFoundation, AVKit, AVFAudio, MediaPlayer, QuartzCore and Network, which clang module autolinking may or may not cover for Kotlin objects.

**Evidence:**

```text
# README.md:109-114
A dynamic framework needs no flags, because Kotlin links it with the system frameworks. Xcode links
a static framework instead, which needs these Other Linker Flags:

-ObjC -lz -framework CoreFoundation -framework CoreMedia -framework CoreVideo -framework VideoToolbox -framework AudioToolbox
```

```text
# libass.def:17-21
# What the chain needs underneath, per platform. Apple ships its font provider as frameworks and
# its C++ runtime as libc++; ...
linkerOpts.ios = -lz -liconv -lc++ -framework CoreText -framework CoreFoundation -framework CoreGraphics
```

**Failure scenario:** An iOS app with `isStatic = true` adds exactly the README flags. Unless it happens to link these itself, the link stops with undefined symbols such as `_iconv_open`, C++ runtime symbols from HarfBuzz and CoreText's `_CTFont...` functions.

**Suggested fix:** Link a small static-framework app once, by hand or in CI, and publish the list it proves, at least adding `-liconv -lc++ -framework CoreText -framework CoreGraphics`.

**How to check:** One Xcode link of a static framework with the README flags, or `nm -u` on the framework binary filtered for `iconv`, `__cxa` and `CTFont`.

### API-09. Java code cannot catch the typed error that KitePlayerJava.create throws

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | low | Confirmed | high | none |

**Where:** `KitePlayer/kiteplayer/src@jvmAndAndroid/io/github/yuroyami/kiteplayer/KitePlayerJava.kt:366-369`, `KitePlayer/kiteplayer/src/io/github/yuroyami/kiteplayer/KitePlayers.kt:30-37`, `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/PlaybackError.kt:169`

**What happens:** `KitePlayerJava.create` calls `KitePlayer(config)`, which throws `PlaybackException` where there is no default stack, and whose KDoc invites the caller to fall back to another engine in that case. `PlaybackException` extends `Exception`, so Java treats it as checked. The Kotlin factory declares `@Throws(PlaybackException::class)`, but `create` declares nothing, so javac refuses `catch (PlaybackException e)` around it as unreachable.

**Evidence:**

```kotlin
// KitePlayerJava.kt:366-369
/** A player on this platform's default stack, as `KitePlayer()` builds it. */
@JvmStatic
@JvmOverloads
public fun create(config: PlayerConfig = PlayerConfig()): KitePlayerJava = KitePlayerJava(KitePlayer(config))
```

```kotlin
// KitePlayers.kt:36-37
@Throws(PlaybackException::class)
public fun KitePlayer(config: PlayerConfig = PlayerConfig()): KitePlayer {
```

**Failure scenario:** A Java Android app wraps `KitePlayerJava.create()` in `try { ... } catch (PlaybackException e) { useMedia3(); }` to handle an `x86` TV box. The code does not compile ("exception PlaybackException is never thrown in body of corresponding try statement"), so the author has to catch bare `Exception` and lose the typed error. (The README's Java sample does not catch at all, so it is not the sample that breaks, only the natural way to follow the factory's KDoc.)

**Suggested fix:** Add `@Throws(PlaybackException::class)` to `create`, and check the other public JVM entry points that reach throwing Kotlin code.

**How to check:** Read the three lines above, or compile a two-line Java file against the jar.

### API-10. Adding the Compose artifacts moves an app onto a pre-release Compose Multiplatform, and only a README badge says so

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | risk | low | Confirmed | high | none |

**Where:** `KitePlayer/compose.module-template.yaml:5`, `KitePlayer/kiteplayer-compose-ui/module.yaml:14-19`, `KitePlayer/README.md:25`, `KitePlayer/README.md:82-88`

**What happens:** Every published Compose module is built against Compose Multiplatform 1.13.0-alpha02 and exports `compose.runtime`, `compose.ui` and `compose.foundation`. Gradle resolves to the highest version anyone asks for, so an app on a stable Compose line moves onto the alpha line the moment it adds `kiteplayer-compose`. The install section warns about `minSdk` and `compileSdk 37.2`, but not this. The README's header does carry a badge reading "Compose Multiplatform 1.13.0-alpha02", which is why this was downgraded from medium: the version is disclosed, just not its consequence.

**Evidence:**

```yaml
# compose.module-template.yaml:5
    version: 1.13.0-alpha02
```

```yaml
# kiteplayer-compose-ui/module.yaml:15-19
  - $compose.runtime: exported
  - $compose.ui: exported
  - $compose.foundation: exported
```

**Failure scenario:** A production app on stable Compose adds `kiteplayer-compose` for one video screen, and its whole UI then runs on an alpha runtime and foundation, with alpha behaviour changes and possible binary breaks against its other Compose libraries.

**Suggested fix:** Say in the install section, beside the `compileSdk` note, that the Compose modules pull in Compose Multiplatform 1.13 alpha, or build the non-web variants against the newest stable line.

**How to check:** Read the template, then `./gradlew dependencies` in any consumer app on stable Compose after adding the artifact.

### API-11. Three documentation pages send readers to source folders that do not exist in this repository's layout

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | docs | low | Confirmed | high | none |

**Where:** `KitePlayer/docs/spi-cookbook.md:6-7`, `KitePlayer/docs/spi-cookbook.md:136-137`, `KitePlayer/docs/subtitle-placement.md:136-137`, `KitePlayer/docs/audioviz-standard.md:492-494`

**What happens:** The repository uses the Kotlin Toolchain layout (`src/`, `test/`, `src@platform/`), but three pages still give Gradle-layout paths. The backend cookbook, whose main instruction is "read this beside that file", points to `kiteplayer-core/src/commonTest/kotlin/.../ScriptedBackend.kt`, which is really `kiteplayer-core/test/io/github/yuroyami/kiteplayer/ScriptedBackend.kt`. The subtitle placement page says `OverlayPlacementContractTest` lives in `kiteplayer-core/src/commonTest`. The visualiser standard links to `src/commonMain/kotlin/...` files, so those links are dead.

**Evidence:**

```text
# docs/spi-cookbook.md:6-7
backend the engine's own test suite runs thousands of sessions against: `ScriptedBackend` in
`kiteplayer-core/src/commonTest/kotlin/io/github/yuroyami/kiteplayer/ScriptedBackend.kt`. It is
```

`find . -name ScriptedBackend.kt` gives `./kiteplayer-core/test/io/github/yuroyami/kiteplayer/ScriptedBackend.kt`, and `CoreHarness.kt` sits beside it. `grep -rln "src/commonMain\|src/commonTest" docs` lists exactly these three pages.

**Failure scenario:** A developer writing a custom backend follows the cookbook and cannot find the file it is built around. A reader of the visualiser standard clicks a link and gets a 404 on GitHub.

**Suggested fix:** Replace the paths with `kiteplayer-core/test/io/github/yuroyami/kiteplayer/` and `kiteplayer-audioviz/src/io/github/yuroyami/kiteplayer/audioviz/` (and the matching core path for `AudioTap.kt`).

**How to check:** `grep -rn "src/commonMain\|src/commonTest" KitePlayer/docs`.

### API-12. The contributor notes say the libass web module comes inside the Maven publication when it is a release-page download

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | docs | low | Confirmed | high | none |

**Where:** `KitePlayer/CLAUDE.md:653-654`, `KitePlayer/scripts/web-zips.sh:8-9`, `KitePlayer/.github/workflows/publish.yml:24-27`, `KitePlayer/README.md:148-152`

**What happens:** The contributor notes say `kiteass.mjs` is "delivered as the `web` zip on the wasmJs publication". The publish workflow and the zip script both say the opposite, that a Kotlin Toolchain publication carries no extra files and both web zips must be attached to the GitHub release page by hand. The README agrees with the scripts.

**Evidence:**

```text
# publish.yml:24-27
# WEB ZIPS. A Kotlin Toolchain publication carries no extra files, so the two web zips of KitePlayer
# (the worker binary and the libass web module) are not on Maven Central. The run builds them with
# scripts/web-zips.sh and keeps them as the web-zips-<version> artifact. Attach them to the GitHub
# release page, where the README's web setup points.
```

**Failure scenario:** Someone preparing a release trusts the contributor notes, expects the libass zip to reach Central with the publish, and skips attaching it to the release. Web apps on that version have no `kiteass.mjs` to download and fall back to the built-in ASS styling.

**Suggested fix:** Change the sentence to say that the libass and worker zips come from `scripts/web-zips.sh` and are attached to the GitHub release.

**How to check:** Read CLAUDE.md:653 beside publish.yml:24-27.

### API-13. The web reader's refusal message is garbled and does not name the worker player that solves the problem

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | docs | low | Confirmed | high | none |

**Where:** `KitePlayer/kiteplayer-ffmpeg/src@wasmJs/io/github/yuroyami/kiteplayer/ffmpeg/BlockingMediaIo.wasmJs.kt:26-27`, `KitePlayer/kiteplayer-ffmpeg/src@wasmJs/io/github/yuroyami/kiteplayer/ffmpeg/BlockingMediaIo.wasmJs.kt:46-52`

**What happens:** A web app on the page's main thread that opens a source whose reads suspend, such as a network address, gets an error that ends "or run the player in a Worker where blocking is legal and is tracked as an issue." The sentence reads as if the worker route were still an open issue, and it never names `KitePlayerWorker`, which shipped and is the documented answer. The KDoc above the class says the same in future tense.

**Evidence:**

```kotlin
// BlockingMediaIo.wasmJs.kt:47-51
"This media source suspends while reading, and the web backend cannot wait for it: " +
    "Kotlin/Wasm has no runBlocking and a browser forbids blocking the main thread. " +
    "Supply a source whose bytes are already resident (a File, a fetch response or " +
    "an ArrayBuffer), or run the player in a Worker where blocking is legal " +
    "and is tracked as an issue.",
```

**Failure scenario:** A Compose web developer passes an https address to a main-thread player, gets this `UnsupportedOperationException`, and concludes network playback on the web is not done yet, without learning that `KitePlayerWorker.start(canvas)` exists.

**Suggested fix:** End the message with "or play it with KitePlayerWorker.start(canvas), which runs the player in a web worker", and update the KDoc to the present tense.

**How to check:** Read the lines above.

### API-14. On a processor the desktop jar does not carry, it loads the x64 Linux library and fails with a confusing error

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KiteFFmpeg | defect | low | Confirmed | high | none |

**Where:** `KiteFFmpeg/kiteffmpeg/src@jvm/io/github/yuroyami/kiteffmpeg/JniLibrary.jvm.kt:69-79`, `KiteFFmpeg/docs/platforms.md:35`

**What happens:** The lookup of the bundled native library maps every processor that is not `aarch64` or `arm64` to `x64`, and every system that is not a Mac or Windows to `linux`. A JVM on 32-bit ARM, RISC-V, ppc64le, s390x or 32-bit x86 Linux therefore finds the `linux-x64` manifest, extracts that library and fails inside `System.load` with a low-level loader message about a temp file. The clear message the code already has for a platform the jar does not carry, which names the override property and which `docs/platforms.md` promises, is reached only when no manifest is found.

**Evidence:**

```kotlin
// JniLibrary.jvm.kt:72-78
val os = when {
    "mac" in name || "darwin" in name -> "macos"
    "win" in name -> "windows"
    else -> "linux"
}
val cpu = if (arch in setOf("aarch64", "arm64")) "arm64" else "x64"
```

**Failure scenario:** A desktop app runs on a Raspberry Pi with a 32-bit userspace (`os.arch` is `arm`). The first FFmpeg call fails with "wrong ELF class: ELFCLASS64" about a file in the temp directory, which reads like a broken jar instead of "this platform is not supported, supply one with -Dkiteffmpeg.jni.path".

**Suggested fix:** Map only known names (`amd64` and `x86_64` to `x64`, `aarch64` and `arm64` to `arm64`) and throw the existing friendly `UnsatisfiedLinkError` for anything else, naming `os.name` and `os.arch`. Intel Macs and Windows on ARM already get the friendly error, because no `macos-x64` or `windows-arm64` manifest exists.

**How to check:** Read the lines above, or run any JVM test with `-Dos.arch=riscv64`.

#### Opportunities

### API-15. What plays end to end on each platform today, and where the gaps are

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| Both | opportunity | medium | Confirmed | high | none |

**Where:** `KitePlayer/README.md:47-58`, `KitePlayer/README.md:478-494`, `KitePlayer/kiteplayer/module.yaml:5`, `KitePlayer/kiteplayer-audioviz/module.yaml:5`, `KitePlayer/kiteplayer-compose/module.yaml:5`, `KitePlayer/kiteplayer-core/module.yaml:7-28`, `KiteFFmpeg/kiteffmpeg/module.yaml:3-17`, `KiteFFmpeg/build-plugins/native-build/src/BuildFFmpegWasmTask.kt:236-258`

**What happens:** A capability matrix read from the module platform lists, the README and the code. Android has the full stack: FFmpeg with MediaCodec, Compose and View, HDR in the view, HLS and DASH, background, picture-in-picture and the visualiser. iOS has the full stack on `iosArm64` and the simulator. Native macOS has the engine, Metal and AppKit, picture-in-picture and Now Playing, but no Compose UI and no visualiser. The desktop JVM has Compose and AWT, VideoToolbox on Apple silicon only, an untried Direct3D 11 route on Windows (#101), no HDR output, no Intel Mac (KiteFFmpeg#212), software-only video on Linux (KiteFFmpeg#217) and sound never heard on real Linux or Windows (#639). On the web the main-thread player plays only bytes already in memory, network media plays only through the worker player, the codec set is small (H.264, HEVC and VP9 video, no AV1, only mov, matroska, mp3, flac, hls and webvtt demuxers, KiteFFmpeg#211), there is no visualiser, and Compose is unreleased. `js` is a placeholder. Native Linux and Windows carry the engine and FFmpeg only, by decision. tvOS and watchOS build the engine modules only, and KiteFFmpeg has no target there.

**Evidence:** `kiteplayer` platforms are `[ jvm, android, iosArm64, iosSimulatorArm64, macosArm64, linuxX64, linuxArm64, mingwX64, js, wasmJs ]`, `kiteplayer-audioviz` is `[ jvm, android, iosArm64, iosSimulatorArm64 ]`, `kiteplayer-compose` is `[ jvm, android, iosArm64, iosSimulatorArm64, wasmJs ]`, `kiteplayer-core` adds tvOS, watchOS and Android Native, and KiteFFmpeg lists no tvOS or watchOS target.

**Failure scenario:** Not a failure. The gaps an app meets first, roughly by value, are no Apple TV, no network playback inside a Compose web app, no visualiser on the Mac or the web, and no HDR or Intel Mac on the desktop JVM. The next opportunities take up the ones no open issue covers.

**Suggested fix:** Keep a capability table generated from the module platform lists in `docs/`, so the README tables cannot drift from the build.

**How to check:** `grep -n "platforms" */module.yaml` in both repositories.

### API-16. Apple TV cannot play anything, because the media library has no tvOS build

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| Both | opportunity | medium | Confirmed | high | none |

**Where:** `KiteFFmpeg/kiteffmpeg/module.yaml:3-17`, `KitePlayer/kiteplayer-core/module.yaml:14-15`, `KitePlayer/kiteplayer-ffmpeg/module.yaml:4-13`, `KitePlayer/kiteplayer-output/module.yaml:4-13`, `KitePlayer/README.md:57-58`, `KitePlayer/README.md:493-494`

**What happens:** The README says the engine "also builds for Linux and Windows native, tvOS and watchOS, where your app supplies the missing parts". On tvOS the missing part is the whole demuxer and decoder, because KiteFFmpeg has no `tvosArm64` or `tvosSimulatorArm64` target, and `kiteplayer-ffmpeg`, `kiteplayer-output` and `kiteplayer` have no tvOS variant. Under the decision that FFmpeg is the one media truth, an app has no sanctioned backend to supply there. Apple TV is the screen where long-form video is watched most, and the players KitePlayer measures itself against (VLC, Infuse) ship on it.

**Evidence:** KiteFFmpeg's module platform list stops at the iOS, macOS, Linux, Windows, Android Native and web targets; a search for tvOS in KiteFFmpeg finds only a Dokka mapping. The Apple output code uses CoreAudio, AVSampleBufferDisplayLayer, Metal and MediaPlayer, all of which tvOS has.

**Failure scenario:** An app that uses KitePlayer on iPhone for MKV with ASS subtitles cannot offer the same on Apple TV and has to keep a second engine there.

**Suggested fix:** Add tvOS FFmpeg trees to KiteFFmpeg (the iOS profile with the tvOS minimum version and VideoToolbox decode), then add tvOS to `kiteplayer-ffmpeg`, `kiteplayer-output` and `kiteplayer`, with a UIKit view and focus-engine controls. Meanwhile, make the README sentence say plainly that nothing plays on tvOS or watchOS today.

**How to check:** `grep -n tvos KiteFFmpeg/kiteffmpeg/module.yaml` returns nothing.

### API-17. A Compose web app cannot stream a web address, although the media library already has the asynchronous reader that would allow it

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | opportunity | medium | Confirmed | high | KiteFFmpeg#183 |

**Where:** `KitePlayer/kiteplayer-ffmpeg/src@wasmJs/io/github/yuroyami/kiteplayer/ffmpeg/BlockingMediaIo.wasmJs.kt:12-27`, `KitePlayer/README.md:161-163`, `KitePlayer/README.md:486-490`, `KiteFFmpeg/docs/async-byte-io.md:1-25`, `KiteFFmpeg/kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/AsyncMediaRuntime.kt:29`, `KiteFFmpeg/kiteffmpeg/src@wasmJs/io/github/yuroyami/kiteffmpeg/KiteFFmpegWeb.kt:134`

**What happens:** `kiteplayer-compose` on the web runs the player on the page's main thread, where KitePlayer refuses any reader that suspends, so `KitePlayerVideo` with an https or HLS address cannot play. Network media plays only through `KitePlayerWorker`, which draws on a canvas of its own outside Compose. KiteFFmpeg already publishes an asynchronous runtime for exactly this case (`KiteFFmpegWeb.loadAsyncRuntime`, `AsyncMediaRuntime`, `AsyncMediaByteSource`), and nothing in KitePlayer calls it.

**Evidence:** `grep -rli "AsyncMediaRuntime\|loadAsyncRuntime"` over KitePlayer's Kotlin sources finds nothing. README.md:161: "A player on the page's main thread cannot play network media, because it cannot block while it waits for a read."

**Failure scenario:** A Compose Multiplatform team ships one `KitePlayerVideo(player)` screen to Android, iOS and the web, and the web build cannot play the stream the others play. The worker workaround gives up Compose clipping, overlays and controls.

**Suggested fix:** Back the main-thread web backend with KiteFFmpeg's asynchronous runtime when the page serves `kite-jspi` or `kite-asyncify`, bridging `MediaIo` onto `AsyncMediaByteSource`. Seeking through it waits on KiteFFmpeg#183.

**How to check:** The grep above, and KiteFFmpeg's `docs/async-byte-io.md`.

### API-18. Ad and promo breaks that a stream announces in its HLS playlist are ignored

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | opportunity | low | Confirmed | medium | none |

**Where:** `KitePlayer/kiteplayer-ffmpeg/src/io/github/yuroyami/kiteplayer/ffmpeg/HlsPlaylists.kt:90-245`

**What happens:** No source file mentions `EXT-X-DATERANGE` or interstitials. AVPlayer and Media3 play HLS interstitials, which schedule other assets such as ads, bumpers or recaps at points of the main timeline, and expose date-range metadata to the app. KitePlayer plays only the main content and gives the app no event for these points.

**Evidence:** `grep -rli "DATERANGE\|interstitial"` over the Kotlin sources finds nothing; `HlsPlaylists.kt` reads variant, media and I-frame tags.

**Failure scenario:** A broadcaster or free ad-supported channel app whose streams use SCTE-35 date ranges gets no breaks and no cue events on KitePlayer.

**Suggested fix:** First parse `EXT-X-DATERANGE` into a player event and timeline metadata, which is cheap; consider playing `com.apple.hls.interstitial` assets as spliced queue items later.

**How to check:** The grep above.

### API-19. Files on a home network share cannot be played without a web server in front of them

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| Both | opportunity | low | Confirmed | medium | none |

**Where:** `KiteFFmpeg/build-plugins/native-build/src/BuildFFmpegTask.kt:387`, `KiteFFmpeg/docs/platforms.md:95`

**What happens:** FFmpeg's protocol list is `file,fd,pipe,data,http,tcp,udp,rtp,rtmp`. There is no `ftp`, which FFmpeg has built in with no extra library, and KitePlayer has no SMB, NFS or SFTP reader. VLC, Kodi, Infuse and nPlayer all play straight from SMB shares, where most home media libraries sit.

**Evidence:**

```kotlin
// BuildFFmpegTask.kt:387
"--enable-protocol=file,fd,pipe,data,http,tcp,udp,rtp,rtmp",
```

`grep -rli "smb://"` over KitePlayer's Kotlin sources finds nothing.

**Failure scenario:** A user points the app at `smb://nas/Movies/film.mkv` and gets a source-unavailable error; the app author has to write an SMB client and feed it through `MediaIo`.

**Suggested fix:** Enable FFmpeg's `ftp` protocol in KiteFFmpeg (no dependency). In KitePlayer, an optional pure Kotlin SMB2/3 resolver module discovered like the network module would fit the "Kotlin first" decision.

**How to check:** Read the protocol line.

### API-20. The music visualiser is missing on the native Mac and on the web

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | opportunity | low | Confirmed | medium | KitePlayer#682 |

**Where:** `KitePlayer/kiteplayer-audioviz/module.yaml:5`, `KitePlayer/README.md:478-490`

**What happens:** `kiteplayer-audioviz` publishes for the JVM, Android and the two iOS targets only. A native macOS app and a wasmJs page have no visualiser, although the drawings are Compose and Kotlin and the audio clock and tap they read are common API.

**Evidence:** `platforms: [ jvm, android, iosArm64, iosSimulatorArm64 ]`.

**Failure scenario:** A music app shows the visualiser on phones and the desktop JVM and a blank area in its web build.

**Suggested fix:** Add wasmJs first, since Compose on the web is in progress, within a cost budget like the one #682 asks for; a native macOS version needs a non-Compose host and costs more.

**How to check:** Read the module file.

#### Rejected during verification

None. One finding was downgraded (API-10, the Compose alpha, from medium to low, because the README badge does name the version). Corrections made while verifying: API-09's scenario no longer claims the README's Java sample breaks, since that sample has no catch; API-11 was widened from the cookbook to the two other pages with the same stale paths; line numbers for `KitePlayerJava.create` (366-369, not 357-360) and the JNI platform lookup (69-79, not 76-86) were fixed; API-04 now notes its relation to KiteFFmpeg#185; API-15 trimmed the web codec claim to what `BuildFFmpegWasmTask.kt` shows.


## 11. KitePlayer areas the first round could not reach

This lane read the media session layer of the `kiteplayer` module (the background policy, the focus and interruption handling on Android and iOS, and the Apple now playing card) and followed each of its calls into the engine and the Apple audio sink. All five findings were retraced against the code. None repeats a title in sections 1 to 10, and none touches a documented decision. All five survive. The first three are confirmed in the code. The fourth and fifth depend on how iOS behaves at run time, so they are marked Plausible.

### GKP-01. Coming back to a music app cuts the sound for a moment, and leaving or coming back in a track's last seconds breaks the gapless join

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | high | Confirmed | high | none |

**Second check:** Spot checked by the coordinator after the gap lane: `PlaybackCore.kt:3250-3257` queues the precise seek with no check on `videoStream`, and `Background.kt:97-100` and `:110-114` park and resume video from the player-wide switch alone, as described.

**Where:**
- `KitePlayer/kiteplayer/src/io/github/yuroyami/kiteplayer/session/Background.kt:94-114`
- `KitePlayer/kiteplayer/src/io/github/yuroyami/kiteplayer/session/Background.kt:192-195`
- `KitePlayer/kiteplayer/src/io/github/yuroyami/kiteplayer/session/SessionTarget.kt:42`
- `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:3227-3260`
- `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:2915`
- `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:11374`
- `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:11393-11415`
- `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:11430`
- `KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:12060-12068`
- `KitePlayer/kiteplayer/src@android/io/github/yuroyami/kiteplayer/session/AndroidMediaSession.kt:505-508`

**What happens:** `attachMediaSession` uses the `ContinueAudio` background policy by default on Android and on Apple platforms. That policy turns the player's video switch off when the app leaves the screen and on again when it comes back. It reads the player-wide switch, which is on by default, and never asks whether the item has a picture. So a music player with no video at all gets `setVideoEnabled(false)` on every trip away and `setVideoEnabled(true)` on every return.

When video is turned back on, the engine queues a precise seek to the current position whenever the source can seek and the player is playing or buffering. Nothing checks that a video stream is selected. That seek publishes Buffering, stops the audio device and clears the ring, so the listener hears the sound stop and start again.

Both directions also drop a gapless preload, because any change of the video switch counts as a command that changes what follows. The preload opens 5 seconds before the end. If the next item's feeder already writes into the ring, the drop takes the ring back and clears it, which throws away up to one ring depth of the current item's end, and the current item then ends with nothing left to drain.

**Evidence:**

`KitePlayer/kiteplayer/src/io/github/yuroyami/kiteplayer/session/Background.kt:97-100` and `:110-114`, the policy acts on the player switch only:
```kotlin
BackgroundPolicy.ContinueAudio -> {
    videoParkedByPolicy = videoEnabled
    BackgroundDecision(SessionTransport.None, if (videoEnabled) false else null)
}
...
BackgroundPolicy.ContinueAudio -> {
    val resumeVideo = videoParkedByPolicy
    videoParkedByPolicy = false
    BackgroundDecision(SessionTransport.None, if (resumeVideo) true else null)
}
```
`KitePlayer/kiteplayer/src/io/github/yuroyami/kiteplayer/session/SessionTarget.kt:42`:
```kotlin
override val videoEnabled: Boolean get() = player.state.value.videoEnabled
```
`KitePlayer/kiteplayer-core/src/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:3250-3257`, the seek has no check on `videoStream` (the `picture != null` check a few lines above guards only the separate audio rendition case):
```kotlin
if (changed && command.value) {
    session?.videoWaitingForKeyframe?.value = true
    if (session?.source?.seekable == true && status.isActive) {
        queueSeek(
            SeekRequest(SeekTarget.Absolute(currentPosition()), SeekMode.Precise),
            CompletableDeferred(),
        )
    }
}
```
`PlaybackCore.kt:12060` and `:12068`, inside `runSeek`:
```kotlin
if (playRequested && status != PlaybackStatus.Ended) setStatus(PlaybackStatus.Buffering)
...
stopAudioDevice(session)
```
`PlaybackCore.kt:2915` and `:11430`, the preload drop:
```kotlin
if (pendingNext != null && dropsPreload(command)) dropPending(null)
...
is CoreCommand.SetVideoEnabled -> command.value != videoEnabled
```
`PlaybackCore.kt:11374` and `:11397`, once handed off the drop clears the ring:
```kotlin
next.handedOff && active != null -> takeRingBack(active, prepared.session, midItem = next.wrapUs != null)
...
if (parked) audio?.flush(requestedEpoch)
```
Path: activity stop or start, `BackgroundApplier.handle`, `target.setVideoEnabled`, `CoreCommand.SetVideoEnabled`, `execute` (preload drop), then on return `queueSeek` and `runSeek`.

**Failure scenario:** A music app plays a queue of local MP3 files and calls `player.attachMediaSession(context, MediaNotificationOptions(...))` with the defaults. The user presses Home and later opens the app again while a song plays. On return the engine seeks to where it already is: the sound fades out and back in, and the notification and lock screen show Buffering for a moment, every time the app comes back. If the user leaves or returns in the last 5 seconds of a song, the preload is dropped. In the last ring depth (at least 200 ms) the ring is also cleared, so the end of the song is cut and the next song opens cold after a gap, although the queue is gapless.

**Suggested fix:** In the engine, skip the re-seek when the session has no selected video stream (`session?.videoStream != null` added to the seek condition), and make `dropsPreload` answer false for `SetVideoEnabled` when neither the current session nor the preloaded one has a video stream. In `BackgroundMachine`, park video only when the current item has a selected video track, and remember that it did.

**How to check:** Read `PlaybackCore.kt:3250-3257` and confirm no `videoStream` check guards the seek. Then, in the core test harness, open an audio-only seekable item, play it, call `setVideoEnabled(false)` and `setVideoEnabled(true)`, and watch the status pass through Buffering.

### GKP-02. Pressing play during a phone call or another app's short sound on Android plays over it without asking for audio focus again

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | medium | Confirmed | medium | #282, #451 |

**Where:**
- `KitePlayer/kiteplayer/src/io/github/yuroyami/kiteplayer/session/SessionTarget.kt:139-145`
- `KitePlayer/kiteplayer/src/io/github/yuroyami/kiteplayer/session/SessionTarget.kt:172-175`
- `KitePlayer/kiteplayer/src@android/io/github/yuroyami/kiteplayer/session/AndroidInterruptions.kt:67-73`
- `KitePlayer/kiteplayer/src@android/io/github/yuroyami/kiteplayer/session/AndroidInterruptions.kt:113-117`

**What happens:** A short loss of audio focus (a call, or another app asking for focus for a moment) pauses the player, but the focus bookkeeping still says focus is held, because only a permanent loss clears it. If the listener presses play while the other app still has focus, the bookkeeping answers "nothing to do" for Playing, so no new request goes to Android and the player makes sound over the other app. A new request is what Android expects: during a call it is refused or answered "later", and the player already has a path that waits for the gain in that case (#451). That path is never reached here.

**Evidence:**

`KitePlayer/kiteplayer/src@android/io/github/yuroyami/kiteplayer/session/AndroidInterruptions.kt:69-72`, only a permanent loss reaches the lifecycle:
```kotlin
if (change == AudioManager.AUDIOFOCUS_LOSS) synchronized(lifecycle) { lifecycle.lost() }
if (change == AudioManager.AUDIOFOCUS_GAIN) synchronized(lifecycle) { lifecycle.gained() }
interruptionEventFor(change)?.let(::handle)
```
`KitePlayer/kiteplayer/src/io/github/yuroyami/kiteplayer/session/SessionTarget.kt:139-141`:
```kotlin
fun on(status: PlaybackStatus, hasSound: Boolean = true): Boolean? = if (closed) null else when (status) {
    PlaybackStatus.Playing, PlaybackStatus.Buffering ->
        if (hasSound) (if (held) null else true) else giveBack()
```
`AndroidInterruptions.kt:116`, a request is made only on a `true` answer:
```kotlin
when (synchronized(lifecycle) { lifecycle.on(status, hasSound) }) {
    true -> { ... audioManager.requestAudioFocus(request) ... }
```
Path: `AUDIOFOCUS_LOSS_TRANSIENT`, `InterruptionMachine` pauses, `held` stays true; user play, status Playing, `on` returns null, no request.

**Failure scenario:** Music plays. A call comes in, Android sends `AUDIOFOCUS_LOSS_TRANSIENT`, and the policy pauses. During the call the user taps play on the notification. The status goes to Playing, `held` is still true, nothing is requested, and the song plays during the call. A navigation prompt that took `AUDIOFOCUS_GAIN_TRANSIENT` overlaps with the music the same way. When the call ends, the gain does nothing, because the play moved the transport mark.

**Suggested fix:** Give `SessionFocusLifecycle` a "suspended" state set on `AUDIOFOCUS_LOSS_TRANSIENT` (and on `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK` when the policy does not duck) and cleared on `AUDIOFOCUS_GAIN`. Treat Playing or Buffering in that state as a new request, so a Delayed or Failed answer goes through the existing Waiting and Denied paths.

**How to check:** Read `SessionTarget.kt:139-145` with `held = true` and status Playing: it returns null. On a device, start music, place a call to the phone, press play on the notification during the call, and listen.

### GKP-03. On the iPhone lock screen and in CarPlay a music queue shows 15 second skip buttons instead of previous and next track

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | medium | Confirmed | medium | #424 |

**Where:**
- `KitePlayer/kiteplayer/src@apple/io/github/yuroyami/kiteplayer/session/AppleNowPlaying.kt:141-151`
- `KitePlayer/kiteplayer/src@apple/io/github/yuroyami/kiteplayer/session/AppleNowPlaying.kt:167-181`
- `KitePlayer/kiteplayer/src/io/github/yuroyami/kiteplayer/session/MediaSessionState.kt:90`

**What happens:** The now playing card enables the skip forward and skip backward commands whenever the item can seek, and the next and previous track commands whenever there is a neighbour (previous is on for any seekable item). The system draws only one pair beside play. When both pairs are enabled, iOS gives the skip interval commands the two slots, so for any seekable item in a queue the lock screen, Control Centre and CarPlay show 15 second buttons, and the listener cannot change track from there. Nothing in the code chooses between the pairs.

**Evidence:**

`KitePlayer/kiteplayer/src@apple/io/github/yuroyami/kiteplayer/session/AppleNowPlaying.kt:144-149`:
```kotlin
commands.nextTrackCommand.enabled = state.hasNext
// Previous also starts the item again, so it is there whenever the item can seek (#424).
commands.previousTrackCommand.enabled = state.offersPrevious
commands.changePlaybackPositionCommand.enabled = state.canSeek
commands.skipForwardCommand.enabled = state.canSeek
commands.skipBackwardCommand.enabled = state.canSeek
```
`AppleNowPlaying.kt:168-169`, the interval commands are set up with their interval, and handlers are added for all four at `:177-180`.

**Failure scenario:** An iOS app opens a queue of ten local songs and calls `attachMediaSession()`. The user locks the phone. The card shows 15 seconds back and 15 seconds forward around play, not previous and next. A car's steering wheel and CarPlay show the same. The next track that the Android notification and `docs/background-playback.md` offer cannot be reached from the iOS lock screen.

**Suggested fix:** Enable only one pair. The smallest rule: enable the skip interval commands only when the item has no queue neighbour (a single long item or a podcast), and disable them otherwise. An option on `attachMediaSession` could let the app choose track buttons or interval buttons.

**How to check:** On an iPhone, open a queue of two seekable songs with the media session attached, lock the phone, and look at the buttons beside play.

### GKP-04. After iOS resets its media services, the player stays silent with its clock stopped until the item is opened again

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | risk | medium | Plausible | medium | none |

**Where:**
- `KitePlayer/kiteplayer/src@ios/io/github/yuroyami/kiteplayer/session/AppleInterruptions.kt:49-63`
- `KitePlayer/kiteplayer-output/src@apple/io/github/yuroyami/kiteplayer/output/CoreAudioSink.kt:610-614`
- `KitePlayer/kiteplayer-output/src@apple/io/github/yuroyami/kiteplayer/output/CoreAudioSink.kt:636-645`
- `KitePlayer/kiteplayer-output/src@apple/io/github/yuroyami/kiteplayer/output/AppleAudioSessionPolicy.kt:173-184`
- `KitePlayer/kiteplayer-rt/native/src/kite_rt_coreaudio.c:730-752`

**What happens:** Nothing in either repository observes `AVAudioSessionMediaServicesWereResetNotification`. Apple documents that after this notice every audio object the app made, the RemoteIO unit included, is no longer valid and has to be built again, with the session category set again. The iOS interruption handle listens only for interruptions and route changes. The sink's resume reuses the same C unit, and `kprt_sink_start` returns at once because its `running` flag is still set. No watch in the engine notices a device that stopped pulling and reopens the output, so nothing rebuilds it.

**Evidence:**

A search of both trees for `MediaServicesWere` finds nothing.

`KitePlayer/kiteplayer/src@ios/io/github/yuroyami/kiteplayer/session/AppleInterruptions.kt:50` and `:56` are the only two notifications observed:
```kotlin
observe(AVAudioSessionInterruptionNotification) { note -> ... }
observe(AVAudioSessionRouteChangeNotification) { note -> ... }
```
`KitePlayer/kiteplayer-output/src@apple/io/github/yuroyami/kiteplayer/output/CoreAudioSink.kt:639-642`:
```kotlin
if (!paused) sessionLease?.reactivate()
callAndCheck(if (paused) "pausing the audio device" else "resuming the audio device") { status ->
    kprt_sink_set_paused(sink, if (paused) 1 else 0, status)
}
```
`KitePlayer/kiteplayer-rt/native/src/kite_rt_coreaudio.c:737-738`:
```c
if (atomic_load_explicit(&sink->running, memory_order_relaxed))
    return KPRT_SINK_OK;
```
The engine reopens the output only on `FormatChangeRequested` (`PlaybackCore.kt`, in `startAudioEventCollector`), which the Apple sink sends only when a route change gives another channel count.

**Failure scenario:** Audio plays on an iPhone and the media server restarts, for example after `mediaserverd` crashes or after the Settings > Developer "Reset Media Services" switch. The RemoteIO callback stops. The player says Playing and the sink says running, but no sound comes and the audio clock does not move. Pause and play change nothing, because the start call returns early on the stale flag. Only closing and opening the item again brings sound back.

**Suggested fix:** Observe `AVAudioSessionMediaServicesWereResetNotification` in the Apple sink or in the session lease manager. On it, set the category and activation again and send `AudioSinkEvent.FormatChangeRequested`, so the existing output reopen in `handleOutputChange` builds a new unit at the current position.

**How to check:** On an iPhone with developer settings on, play audio, use Settings > Developer > Reset Media Services, return to the app, and press pause and play.

### GKP-05. An iPhone app resumed after the system suspended it can find its player paused by an interruption that never happened

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KitePlayer | defect | low | Plausible | medium | none |

**Where:**
- `KitePlayer/kiteplayer/src@ios/io/github/yuroyami/kiteplayer/session/AppleInterruptions.kt:50-55`
- `KitePlayer/kiteplayer/src@ios/io/github/yuroyami/kiteplayer/session/AppleInterruptions.kt:91-98`
- `KitePlayer/kiteplayer/src/io/github/yuroyami/kiteplayer/session/Interruptions.kt:117-121`

**What happens:** From iOS 10.3 the system can deliver an interruption "began" notice late, when a suspended app resumes, with `AVAudioSessionInterruptionWasSuspendedKey` set (and from iOS 14.5 with the reason "app was suspended"). Apple says such a notice is not a real interruption, and no "ended" notice follows it. The handler turns every "began" into `LostTransient` without reading either key, so the policy pauses a player that asked to play and waits for a gain that never comes. Apple marks that reason as no longer used from iOS 16, so the reach is mainly iOS 10.3 to 15, which this project does not exclude.

**Evidence:**

`KitePlayer/kiteplayer/src@ios/io/github/yuroyami/kiteplayer/session/AppleInterruptions.kt:51-54`, only the type and options keys are read:
```kotlin
interruptionEventFor(
    type = note.number(AVAudioSessionInterruptionTypeKey),
    options = note.number(AVAudioSessionInterruptionOptionKey),
)?.let(applier::handle)
```
`AppleInterruptions.kt:91-92`:
```kotlin
internal fun interruptionEventFor(type: ULong?, options: ULong?): InterruptionEvent? = when (type) {
    AVAudioSessionInterruptionTypeBegan -> InterruptionEvent.LostTransient
```
`Interruptions.kt:117-121`, `LostTransient` pauses whenever `playRequested` is true.

**Failure scenario:** On iOS 15, a video app without the background audio mode plays a film and the user switches away. iOS suspends the app with `playRequested` still true. When the user comes back, the late "began" notice arrives, the policy pauses, and no "ended" with the resume flag follows, so the film stays paused.

**Suggested fix:** Read `AVAudioSessionInterruptionWasSuspendedKey`, and `AVAudioSessionInterruptionReasonKey` where the system has it, in the observer, and have `interruptionEventFor` return null for a "began" that the system marks as caused by suspension.

**How to check:** Read `AppleInterruptions.kt:50-55` and confirm neither key is read. On an iOS 15 device, play, switch away until the app is suspended, return, and watch whether the player pauses on its own.

#### Rejected during verification

None.


## 12. KiteFFmpeg areas the first round could not reach

This pass covered parts of KiteFFmpeg that the first round never opened: the stream copy path that Remuxer, Transcoder and MediaSink share, the trim logic that ends a copy, writing to an application byte sink, the protocols a byte-source input can reach, the multi-threaded Dolby Vision composition, and the JNI log bridge. Each finding was traced through the Kotlin and C sources and into the vendored FFmpeg 9.0.2 sources. Each was checked against the open KiteFFmpeg issues and against the first-round sections 04, 05, 09 and 10. None of the seven repeats an earlier finding or an open issue. All seven survived verification: four are confirmed defects or confirmed reach, and three are plausible risks whose harm depends on how an app calls the library.

### GKF-01. A remuxed iPhone HEVC video is retagged hev1, so the MP4 or MOV it becomes no longer plays in Apple's players

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KiteFFmpeg | defect | high | Confirmed | high | none |

**Second check:** Spot checked by the coordinator after the gap lane: `helpers_codecpar.c:83-89` zeroes `codec_tag` after every copy, and no other Kotlin or C source in KiteFFmpeg sets a tag, as described. The claims about the vendored `mux.c`, `movenc.c` and `ffmpeg_mux_init.c` were not re-read here, because FFmpeg's source is not in the container; check them against the pinned release.

**Where:** `KiteFFmpeg/native/kitecodec-c/src/helpers_codecpar.c:83-89`, `KiteFFmpeg/kiteffmpeg/src@native/io/github/yuroyami/kiteffmpeg/MediaSink.native.kt:309`, `KiteFFmpeg/kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/MediaSink.jvm.kt:248`, `KiteFFmpeg/native/kitecodec-jni/kj_format.c:691-692`

**What happens:** Every copied stream has its codec tag set to zero before it reaches the muxer. The muxer then takes the first tag its table lists for the codec. For HEVC that is `hev1`, in both the mp4 and the mov muxer. So a source tagged `hvc1`, which is every iPhone recording and most HEVC made for Apple devices, comes out tagged `hev1`, and a Dolby Vision `dvh1` track comes out as plain `hev1`. QuickTime, Safari, Photos and AVPlayer will not play an `hev1` track. The code comment says ffmpeg.c does the same thing. It does not: ffmpeg.c keeps the source tag whenever the output muxer maps that tag to the same codec. Nothing else in KiteFFmpeg sets a tag (no Kotlin or C source mentions `codec_tag` or `hvc1` apart from this helper), so a caller cannot work around it.

**Evidence:**

```c
// KiteFFmpeg/native/kitecodec-c/src/helpers_codecpar.c:83-89
/* Stream-copy parameter clone. codec_tag is container-specific (mp4 'avc1' means nothing
   to mkv), and zeroing it lets the destination muxer pick its own tag; ffmpeg.c does the same. */
KC_API int ffkmp_codecpar_copy_for_mux(AVCodecParameters *dst, const AVCodecParameters *src) {
    if (!dst || !src) return AVERROR(EINVAL);
    int rc = avcodec_parameters_copy(dst, src);
    if (rc >= 0) dst->codec_tag = 0;
    return rc;
}
```

Every copy stream goes through it. Native: `MediaSink.native.kt:309` `check0(ffkmp_codecpar_copy_for_mux(outPar, sourcePar), ...)`. JVM and Android: `MediaSink.jvm.kt:248` `Internals.codecParCopy(...)`, which is `kj_codecpar_copy` at `kj_format.c:691-692`, which calls `ffkmp_codecpar_copy_for_mux`.

In the vendored FFmpeg 9.0.2, a zero tag is filled from the format's table:

```c
// libavformat/mux.c:323-324
            } else
                par->codec_tag = av_codec_get_tag(of->p.codec_tag, par->codec_id);
```

and the first HEVC entry in both tables is `hev1`:

```c
// libavformat/movenc.c:9306-9308 (codec_mp4_tags)
    { AV_CODEC_ID_HEVC,            MKTAG('h', 'e', 'v', '1') },
    { AV_CODEC_ID_HEVC,            MKTAG('h', 'v', 'c', '1') },
    { AV_CODEC_ID_HEVC,            MKTAG('d', 'v', 'h', '1') },
// libavformat/isom_tags.c:124 (ff_codec_movvideo_tags)
    { AV_CODEC_ID_HEVC, MKTAG('h', 'e', 'v', '1') },
```

What ffmpeg.c actually does keeps the source tag:

```c
// fftools/ffmpeg_mux_init.c:1031-1036
    if (!codec_tag) {
        const struct AVCodecTag * const *ct = mux->fc->oformat->codec_tag;
        unsigned int codec_tag_tmp;
        if (!ct || av_codec_get_id (ct, par->codec_tag) == par->codec_id ||
            !av_codec_get_tag2(ct, par->codec_id, &codec_tag_tmp))
            codec_tag = par->codec_tag;
    }
```

The muxer also changes how it writes the stream by the tag: `movenc.c:1646`, `1662` and `7137` test `track->tag == MKTAG('h','v','c','1')`, so the hvcC box and the in-band parameter set filtering follow the wrong tag too.

**Failure scenario:** An app calls `Remuxer.remux` to rewrap `IMG_0001.MOV` into `out.mp4`, or runs `Transcoder` with video copy on. The output plays in VLC and Chrome, but QuickTime, Safari and the iOS Photos app report it as unplayable, and a Dolby Vision iPhone clip loses its `dvh1` sample entry as well. The same file run through `ffmpeg -c copy` keeps `hvc1` and plays.

**Suggested fix:** Do what ffmpeg.c does. Give the copy helper the output format (or add a sibling that takes the output `AVFormatContext`), and after `avcodec_parameters_copy` clear the tag only when the output format has a tag table, `av_codec_get_id(table, tag)` is not the stream's codec id, and the table has some other tag for that codec. Add a contract test that remuxes an `hvc1` MP4 into MP4 and MOV and reads the sample entry back.

**How to check:** Remux any `hvc1` HEVC MP4 with `Remuxer.remux` and run `ffprobe -show_streams out.mp4 | grep codec_tag_string`. It prints `hev1`, while `ffmpeg -i in.mp4 -c copy ref.mp4` gives `hvc1`.

### GKF-02. A trimmed remux or copy transcode ends its sound early, because every stream stops at the first picture packet past the end

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KiteFFmpeg | defect | medium | Confirmed | high | none |

**Where:** `KiteFFmpeg/kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/Remuxer.jvm.kt:127-128`, `KiteFFmpeg/kiteffmpeg/src@native/io/github/yuroyami/kiteffmpeg/Remuxer.native.kt:135-136`, `KiteFFmpeg/kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/Transcoder.jvm.kt:346`, `KiteFFmpeg/kiteffmpeg/src@native/io/github/yuroyami/kiteffmpeg/Transcoder.native.kt:376`, `KiteFFmpeg/kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/OutputOrigin.kt:139-142`

**What happens:** The copy loop throws `StopDemux` as soon as the lead stream, which is the video when there is one, gives a packet whose decode time is past `endMicros`. That ends the read for every stream at once. A demuxer does not hand out packets in time order across streams. FFmpeg's MP4 reader takes samples in file order while the streams are within one second of each other, and in file order alone on an input that cannot seek. Sound packets that belong before the end but sit later in the file than the first late picture are never read, so the sound of the cut stops before its picture: by up to about a second on an ordinary MP4, and by more on a badly interleaved file or a byte source that cannot seek. Packets of other streams that are past the end are dropped while the loop waits for the lead, which is right, but the lead's own stop leaves nothing to wait for the others.

**Evidence:**

```kotlin
// KiteFFmpeg/kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/Remuxer.jvm.kt:127-128
                        if (micros != Long.MIN_VALUE && micros > endMicros) {
                            if (info.index == leadIndex) throw StopDemux()
// KiteFFmpeg/kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/Transcoder.jvm.kt:346
                                        pastEnd -> if (info.index == lead.index) throw StopDemux()
// KiteFFmpeg/kiteffmpeg/src@native/io/github/yuroyami/kiteffmpeg/Remuxer.native.kt:135-136
                        if (micros != Long.MIN_VALUE && micros > endMicros) {
                            if (info.index == leadIndex) throw StopDemux()
```

The library already knows about the reordering in another place:

```kotlin
// KiteFFmpeg/kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/OutputOrigin.kt:139-141
         * that has shown nothing. FFmpeg's MP4 reader takes samples in file order while the
         * streams are within a second of each other, so a stream can deliver its first packet up
         * to that much behind another, ...
```

And the vendored reader picks the next sample like this:

```c
// libavformat/mov.c:11616-11630
    int no_interleave = !mov->interleaved_read || !(s->pb->seekable & AVIO_SEEKABLE_NORMAL);
    ...
            if (!sample || (no_interleave && current_sample->pos < sample->pos) ||
                ((s->pb->seekable & AVIO_SEEKABLE_NORMAL) &&
                 ((msc->pb != s->pb && dts < best_dts) || (msc->pb == s->pb && dts != AV_NOPTS_VALUE &&
                 ((dtsdiff <= AV_TIME_BASE && current_sample->pos < sample->pos) ||
```

ffmpeg.c instead finishes each output stream on its own and keeps reading until every stream is finished.

**Failure scenario:** `Remuxer.remux` with `endMicros = 60_000_000` on `talk.mp4`, where the video chunk covering 59.6 s to 60.4 s sits before the audio chunk covering 59.5 s to 60.5 s. The first video packet past 60 s is read, `StopDemux` fires, and the sound from about 59.5 s on is never read. The clip's sound ends half a second before its picture, and a clip joined after it has a gap. `Transcoder` with a copied video stream and an end bound stops the same way.

**Suggested fix:** Stop each stream at its own end instead of stopping the reader at the lead's. Mark a stream finished when its own packet passes `endMicros`, keep reading while any selected audio or video stream is unfinished, and bound the extra read (for example by the one second window FFmpeg's reader uses, plus a byte or packet limit) so a stream that never reaches the end cannot make a short cut read the whole file.

**How to check:** Cut a phone MP4 with `Remuxer.remux(..., endMicros = 60_000_000)` and compare the two durations with `ffprobe -show_entries stream=codec_type,duration out.mp4`. The audio duration falls short of the video one, while `ffmpeg -t 60 -c copy` gives two that match.

### GKF-03. An MP4 written to a byte sink with the faststart option the docs suggest loses its index and cannot be played at all

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KiteFFmpeg | defect | medium | Confirmed | high | none |

**Where:** `KiteFFmpeg/kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/MediaSink.kt:90-91`, `KiteFFmpeg/kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/MediaSink.kt:104`, `KiteFFmpeg/native/kitecodec-c/src/helpers_format.c:1512-1544`

**What happens:** The path overload of `MediaSink.open` gives `"movflags" to "+faststart"` as its example option, and the byte-sink overload says its options are "as for the path variant". Faststart makes FFmpeg's MP4 trailer open the output again, by its address, for reading, so it can move the data along and put the index in front. A byte-sink output has no address (FFmpeg sets it to the empty string) and its I/O context has no read callback, so the reopen fails. The trailer then returns before it writes the `moov` box, and the bytes already written form a file with no index. The header check that refuses a non-seekable sink does not help: this sink is seekable, and the failure only comes at the very end.

**Evidence:**

```kotlin
// KiteFFmpeg/kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/MediaSink.kt:90-91
         * @param options muxer private options applied before the header is written, such as
         *                `"movflags" to "+faststart"` (mp4 web-ready) or `"movie_timescale"`
// KiteFFmpeg/kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/MediaSink.kt:104
         * @param options muxer private options, as for the path variant
```

```c
// KiteFFmpeg/native/kitecodec-c/src/helpers_format.c:1520-1524
    int rc = avformat_alloc_output_context2(&c, NULL, format, NULL);
    ...
        ? avio_alloc_context(buffer, KC_IO_BUFFER_SIZE, 1, writer, NULL, kc_io_write_packet,
                             seek_fn ? kc_io_write_seek : NULL)
```

The vendored FFmpeg then does:

```c
// libavformat/mux.c:210   if (!s->url && !(s->url = av_strdup(""))) {
// libavformat/mux_utils.c:90-94
    ret = s->io_open(s, &read_pb, s->url, AVIO_FLAG_READ, NULL);
    if (ret < 0) {
        av_log(s, AV_LOG_ERROR, "Unable to re-open %s output file for shifting data\n", s->url);
        goto end;
    }
// libavformat/movenc.c:9149-9153
        if (mov->flags & FF_MOV_FLAG_FASTSTART) {
            ...
            res = shift_data(s);
            if (res < 0)
                return res;
```

KiteFFmpeg installs no `io_open` on an output context (the only `io_open` it sets is the nested opener of an input), so the default open of an empty address is what runs.

**Failure scenario:** An Android app records to a seekable `MediaByteSink` over a `FileChannel` from the Storage Access Framework and passes `movflags=+faststart` to get a web-ready file. Encoding runs for the whole recording, `MediaSink.close` throws at the trailer, and the document holds an `mdat` with no `moov`. The recording cannot be played or easily recovered.

**Suggested fix:** Refuse `faststart` (and any other movflag that needs a second pass over the output) on a byte sink when it is opened, with a typed `InvalidArgument` that names the reason, and correct the byte-sink KDoc so it does not inherit the faststart example. Supporting it instead would need a read callback on the output bridge and an `io_open` that serves the output's own bytes back.

**How to check:** Write a short MP4 through `MediaSink.open(sink = seekableByteSink, format = "mp4", options = mapOf("movflags" to "+faststart"))`. Close fails, the FFmpeg log shows "Unable to re-open  output file for shifting data", and `ffprobe` on the bytes reports "moov atom not found".

### GKF-04. Trimming a song or audiobook that carries cover art reads the whole input, because the cover picture becomes the stream that ends the cut

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KiteFFmpeg | defect | low | Confirmed | high | GKF-02 |

**Where:** `KiteFFmpeg/kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/Remuxer.jvm.kt:106`, `KiteFFmpeg/kiteffmpeg/src@native/io/github/yuroyami/kiteffmpeg/Remuxer.native.kt:108`

**What happens:** The remux takes the first selected video stream as the one whose end stops the read. When no stream list is given, every stream that is not of unknown type is selected, cover art included. A cover picture is a video stream with one packet at the start, so it never passes `endMicros`. Every sound packet after the end is then read and dropped until the end of the file. On a byte source backed by the network, that downloads the whole file. `TrackSelector.selectVideo` already skips attached pictures for this reason, but this line does not use it.

**Evidence:**

```kotlin
// KiteFFmpeg/kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/Remuxer.jvm.kt:80
                source.streams.filter { it.type != MediaType.Unknown }
// KiteFFmpeg/kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/Remuxer.jvm.kt:106
            val leadIndex = (selected.firstOrNull { it.type == MediaType.Video } ?: selected.first()).index
// KiteFFmpeg/kiteffmpeg/src@jvmAndAndroid/io/github/yuroyami/kiteffmpeg/Remuxer.jvm.kt:128
                            if (info.index == leadIndex) throw StopDemux()
// KiteFFmpeg/kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/TrackSelector.kt:42
        streams.firstOrNull { it.type == MediaType.Video && !it.disposition.attachedPicture }
```

The native copy at `Remuxer.native.kt:108` is the same line.

**Failure scenario:** `Remuxer.remux` from `book.m4b` to `sample.m4a` with `endMicros = 30_000_000`, on a ten hour audiobook with a cover. The 30 second sample takes as long as reading ten hours of packets, and through a `MediaByteSource` backed by HTTP it downloads the whole book.

**Suggested fix:** Choose the lead the way `TrackSelector` does: the first video stream that is not an attached picture, else the first audio stream, else the first selected stream. With per-stream ends as in GKF-02, the lead is no longer needed at all.

**How to check:** Trim the first 10 seconds of a long MP3 or M4A that has embedded cover art, and log how many packets the loop reads: it reads every packet of the file instead of stopping near 10 seconds.

### GKF-05. A playlist read from a byte source without an opener can make FFmpeg fetch plain http addresses and local media files on its own

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KiteFFmpeg | risk | low | Plausible | medium | none |

**Where:** `KiteFFmpeg/native/kitecodec-c/src/helpers_format.c:1399-1412`, `KiteFFmpeg/kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/MediaSource.kt:417-419`, `KiteFFmpeg/build-plugins/native-build/src/BuildFFmpegTask.kt:383-387`

**What happens:** With no `nestedOpener`, the byte-source open leaves FFmpeg's own `io_open` in place and sets no `protocol_whitelist` on the context. FFmpeg copies a whitelist from the input only when the I/O context carries one, and `avio_alloc_context` gives it none. So the HLS reader opens every segment, key and variant address through any protocol this build has. Plain FFmpeg protects this case: a playlist fetched through its http protocol inherits http's default whitelist, which has no `file`, so a network playlist cannot read local files. A playlist handed over as bytes loses that protection. An app that fetched untrusted bytes with its own HTTPS client gets cleartext requests that skip its proxy, certificate pinning and cleartext policy, to any host the playlist names, and reads of local files whose names have a media extension. The KDoc says FFmpeg opens those addresses with its own protocols, but not that this reaches local files, and the build comment reasons only about inputs opened from a file.

**Evidence:**

```c
// KiteFFmpeg/native/kitecodec-c/src/helpers_format.c:1399-1412
    c->pb = pb;
    c->flags |= AVFMT_FLAG_CUSTOM_IO;
    ...
    if (opener) {
        bridge->default_io_open = c->io_open;
        ...
        c->io_open = kc_io_open_nested;
```

```kotlin
// KiteFFmpeg/build-plugins/native-build/src/BuildFFmpegTask.kt:383-387
        // A wider list does not widen what a playlist reaches: FFmpeg's HLS reader opens only
        // file, http and data, and an input opened from a file may reach only file, crypto and
        // data unless DemuxOptions.protocolWhitelist names more.
        "--enable-network",
        "--enable-protocol=file,fd,pipe,data,http,tcp,udp,rtp,rtmp",
```

In the vendored FFmpeg, `demux.c:270-271` copies the whitelist only from `s->pb->protocol_whitelist`; `hls.c:717-730` accepts `file` (with an extension check) and `http`; `http.c:2250` gives http the default whitelist `"http,https,tls,rtp,tcp,udp,crypto,httpproxy,data"`, which is the protection a byte source does not get. No KiteFFmpeg C source sets `protocol_whitelist`; only `DemuxOptions.protocolWhitelist` passes one, and only when the caller asks.

**Failure scenario:** A consumer downloads an m3u8 over HTTPS from a link a user pasted and opens it with `MediaSource.open(io = bytes, url = link)`. The playlist lists segments at `http://192.168.1.1/admin/backup.ts` and `file:///sdcard/DCIM/Camera/private.mp4`. FFmpeg requests the router address in cleartext from inside the app's network, and plays the local video as part of the stream.

**Suggested fix:** When a byte source is opened without an opener and the caller passed no `DemuxOptions.protocolWhitelist`, set the context's `protocol_whitelist` to `"data"`, so a nested address fails with a typed refusal that says to pass a `nestedOpener`. State the default in the KDoc at `MediaSource.kt:417-419`.

**How to check:** Serve a two line m3u8 whose segment is `file:///tmp/x.ts` (an existing MPEG-TS file) from memory through `MediaSource.open(io = ..., url = "https://example.com/a.m3u8")` and read packets: the local file plays.

### GKF-06. Closing a Dolby Vision composition while another thread still composes a band frees the pictures under it

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KiteFFmpeg | risk | low | Plausible | medium | none |

**Where:** `KiteFFmpeg/kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/DolbyVision.kt:308-309`, `KiteFFmpeg/kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/DolbyVision.kt:353-370`, `KiteFFmpeg/kiteffmpeg/src@native/io/github/yuroyami/kiteffmpeg/Frame.native.kt:539-545`, `KiteFFmpeg/native/kitecodec-jni/kj_frame.c:352-364`

**What happens:** `DolbyVisionComposition` is a public class built so that bands of rows run on several threads, and `composeRows` runs without the frames' locks on purpose. `close` and `finish` free the source and output frames with nothing that waits for bands still running. The KDoc asks the caller to wait, so this is a stated contract, but breaking it gives a use after free inside the C composer instead of a typed error. Everywhere else the library uses its handle table and per-wrapper locks to turn this kind of mistake into an exception. On the JVM the handle lookup happens once at the start of the band, so a close that lands after the lookup is not caught.

**Evidence:**

```kotlin
// KiteFFmpeg/kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/DolbyVision.kt:308-309
 * Threading. [composeRows] calls may run at the same time on different threads, as long as their
 * rows do not overlap. [finish] and [close] must wait until every [composeRows] call has returned.
// KiteFFmpeg/kiteffmpeg/src/io/github/yuroyami/kiteffmpeg/DolbyVision.kt:365-370
    override fun close() {
        output?.close()
        output = null
        source?.close()
        source = null
    }
```

```c
// KiteFFmpeg/native/kitecodec-jni/kj_frame.c:352-364
/* Runs without the Kotlin frame lock, so bands of one composition can run on several threads; the
 * composition owns both frames, and the table lookups are safe from any thread. */
    kc_frame *src = (kc_frame *)kj_handle_get(env, src_token, KJ_KIND_FRAME);
    ...
    return (jint)ffkmp_frame_dovi_compose_rows(src, dst, (int)row_start, (int)row_end);
```

`Frame.native.kt:540-545` passes `source.nativeFrame` and `output.nativeFrame` straight to C. `source` and `output` are plain `var`s with no count of bands in flight.

**Failure scenario:** An app splits a 4K frame into eight bands on a thread pool. One band throws `FFmpegException` for a bad RPU, and the coordinating code closes the composition in a `finally` while the other seven bands are still inside `ffkmp_frame_dovi_compose_rows`. Those bands read and write freed frame buffers, which corrupts the heap or crashes a later decode.

**Suggested fix:** Count bands in flight with an atomic counter. Make `close` and `finish` wait for it to reach zero (or refuse with `IllegalStateException` while it is not zero), and make `composeRows` refuse once closing has begun. The frames' own locks can stay out of the hot path.

**How to check:** Read `DolbyVision.kt:320-370`: nothing between `composeRows` and `close` coordinates the two, and the C side holds raw pointers for the whole band.

### GKF-07. Every FFmpeg log line from a decoder worker thread attaches that thread to the JVM and detaches it again

| Repo | Kind | Severity | Verdict | Confidence | Related |
|---|---|---|---|---|---|
| KiteFFmpeg | risk | low | Plausible | medium | none |

**Where:** `KiteFFmpeg/native/kitecodec-jni/kj_abi.c:41-73`, `KiteFFmpeg/native/kitecodec-c/src/helpers_log.c:83-88`

**What happens:** The JNI log bridge attaches any thread the JVM does not know, calls the Kotlin sink, and detaches it, for every line. FFmpeg's frame-threaded and slice-threaded decoders log damage reports from their own worker threads, often many lines a second on a broken stream (for example HEVC's "Could not find ref with POC" at error level). Each attach creates and tears down a `java.lang.Thread` peer, which on Android is a full ART thread registration. With a sink installed at error or warning level, the log path can cost more than the decoding it reports on and can hold up the decoder's worker threads. No sink is installed by default, so this only bites an app that turns logging on, which is exactly when it is debugging a damaged stream.

**Evidence:**

```c
// KiteFFmpeg/native/kitecodec-jni/kj_abi.c:50-57 and 72
    if ((*kj_log_vm)->GetEnv(kj_log_vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK || env == NULL) {
#ifdef __ANDROID__
        if ((*kj_log_vm)->AttachCurrentThread(kj_log_vm, &env, NULL) != JNI_OK) return;
#else
        if ((*kj_log_vm)->AttachCurrentThread(kj_log_vm, (void **)&env, NULL) != JNI_OK) return;
#endif
        attached = 1;
    }
    ...
    if (attached) (*kj_log_vm)->DetachCurrentThread(kj_log_vm);
```

`ffkmp_log_set_sink` at `helpers_log.c:83-88` installs the forwarder as FFmpeg's log callback for every thread. The library already keeps per-thread state that lives until the thread ends with a pthread key (`kc_convert.h:94-103`, the swscale cache), so the pattern is at hand.

**Failure scenario:** An Android app sets `FFmpegLog` to warnings for diagnostics and plays an HEVC stream with packet loss. Each decoder worker thread logs several lines for every damaged frame, every line attaches and detaches a JVM thread, playback stutters, and the app's thread list churns with short-lived threads.

**Suggested fix:** Attach a native thread once and keep it attached, detaching it from a pthread key destructor when the thread exits. Or queue lines from threads the JVM does not know to one attached forwarding thread.

**How to check:** Install a sink at warning level, decode a damaged HEVC file with frame threading on, and count `AttachCurrentThread` calls (or watch thread creation in Android Studio's profiler): there is one per logged line.

#### Rejected during verification

None.
