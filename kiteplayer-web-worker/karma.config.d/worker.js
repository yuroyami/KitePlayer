// KitePlayerWorkerBrowserTest starts a real worker player (#100). The page gets three things from
// disk, proxied to fixed paths:
// - the worker binary this module builds, its development executable, under /worker/, or the
//   directory KITEPLAYER_WORKER_DIR names, such as the unpacked web zip a page actually serves
//   (#554). The variable is not a task input, so pass --rerun with it;
// - the codec module from the kiteffmpeg web zip, which unpackKiteFFmpegWebModule unpacks, at
//   /kite.mjs and /kite.wasm;
// - the clips of the repository's testmedia directory, under /testmedia/;
// - the libass module that :kiteplayer-libass links with emscripten, at /kiteass.mjs and
//   /kiteass.wasm. Without emcc there is none, and the libass test fails rather than skips, as
//   that module's own web test does.
// karma.conf.js lives in <root>/build/wasm/packages/<project>-test, four levels under the root.
const path = require("path");
const root = path.resolve(__dirname, "../../../..");
const workerDir = process.env.KITEPLAYER_WORKER_DIR ||
    path.join(root, "kiteplayer-web-worker/build/compileSync/wasmJs/main/developmentExecutable/kotlin");
const moduleDir = path.join(root, "kiteplayer-web-worker/build/kiteffmpeg-web");
const mediaDir = process.env.KITEPLAYER_TESTMEDIA || path.join(root, "testmedia");
const libassDir = path.join(root, "kiteplayer-libass/build/kiteass");
// nocache stops karma from reading every clip into memory at start.
config.files.push(
    { pattern: path.join(workerDir, "*"), included: false, served: true, watched: false },
    { pattern: path.join(moduleDir, "*"), included: false, served: true, watched: false },
    { pattern: path.join(mediaDir, "*"), included: false, served: true, watched: false, nocache: true },
    { pattern: path.join(mediaDir, "hls", "*"), included: false, served: true, watched: false, nocache: true },
    { pattern: path.join(mediaDir, "dash", "*"), included: false, served: true, watched: false, nocache: true },
    { pattern: path.join(libassDir, "*"), included: false, served: true, watched: false },
);
// Percent-encoded: a bare '#' in a checkout's path is a fragment, so the proxy target would
// otherwise stop at the hash.
const served = (file) => "/absolute" + encodeURI(file).replace(/#/g, "%23");
config.proxies = Object.assign({}, config.proxies, {
    "/worker/": served(workerDir + "/"),
    "/kite.mjs": served(path.join(moduleDir, "kite.mjs")),
    "/kite.wasm": served(path.join(moduleDir, "kite.wasm")),
    "/testmedia/": served(mediaDir + "/"),
    "/kiteass.mjs": served(path.join(libassDir, "kiteass.mjs")),
    "/kiteass.wasm": served(path.join(libassDir, "kiteass.wasm")),
});
// A small server beside the clips, for what a folder of files cannot show (#546). It keeps the
// address, the range, the X-Kite-Test header and the time since the reset of every request for a
// clip, and under /kite-probe/ it serves:
// - asked: those requests, as JSON, and the times live.mpd was served;
// - reset: forgets them and starts the live clock;
// - live.m3u8: hls/ts-0 as a live playlist, two segments at the reset and one more every two
//   seconds, ended after the sixth;
// - live.mpd: dash/separate as a live manifest, whose third segment is the newest at the reset.
//   With ?origin=, the segments are named on that origin instead of the manifest's own. With
//   ?ended=1 it is the whole presentation, ended;
// - moved/<clip>: a redirect to that clip, kept in the list under this name;
// - periods.mpd: the three Periods of dash/period-a, -b and -c as one manifest of 60 seconds,
//   whose second Period has a larger picture;
// - live-periods.mpd: the first of those Periods as a live manifest 16 seconds in, which names
//   the second Period only from its second fetch on.
// Every answer allows another origin to read it, so one test can ask 127.0.0.1 from localhost.
const probe = { asked: [], manifests: 0, periodFetches: 0, epoch: Date.now() };
const liveHls = () => {
    const count = Math.min(6, 2 + Math.floor((Date.now() - probe.epoch) / 2000));
    const lines = ["#EXTM3U", "#EXT-X-VERSION:3", "#EXT-X-TARGETDURATION:2", "#EXT-X-MEDIA-SEQUENCE:0"];
    for (let i = 0; i < count; i++) lines.push("#EXTINF:2.000000,", "/testmedia/hls/ts-0-" + i + ".ts");
    if (count === 6) lines.push("#EXT-X-ENDLIST");
    return lines.join("\n") + "\n";
};
const liveDash = (origin, ended) => {
    const set = (type, id, attributes) =>
        '<AdaptationSet contentType="' + type + '"><Representation id="' + id + '" ' + attributes + '>' +
        '<SegmentTemplate timescale="1" duration="2" startNumber="1" initialization="' + origin + '/testmedia/dash/separate-' + id +
        '-init.m4s" media="' + origin + '/testmedia/dash/separate-' + id + '-$Number%05d$.m4s"/></Representation></AdaptationSet>';
    // Segment n is whole 2n seconds after the start, so the third is the newest six seconds in.
    const head = ended
        ? 'type="static" mediaPresentationDuration="PT70S" minBufferTime="PT2S"'
        : 'type="dynamic" availabilityStartTime="' + new Date(probe.epoch - 6000).toISOString() + '" publishTime="' +
            new Date().toISOString() + '" minimumUpdatePeriod="PT2S" timeShiftBufferDepth="PT30S" minBufferTime="PT2S"';
    return '<?xml version="1.0" encoding="utf-8"?>\n<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" ' + head + '>' +
        '<Period id="0" start="PT0S">' +
        set("video", "0", 'mimeType="video/mp4" codecs="avc1.42c00d" bandwidth="300000" width="320" height="180"') +
        set("audio", "2", 'mimeType="audio/mp4" codecs="mp4a.40.2" bandwidth="96000" audioSamplingRate="48000"') +
        '</Period></MPD>\n';
};
const periods = (names, live) => {
    const set = (name, type, id, attributes) =>
        '<AdaptationSet id="' + id + '" contentType="' + type + '"' + (type === "audio" ? ' lang="en"' : '') + '>' +
        '<Representation id="' + id + '" ' + attributes + '>' +
        '<SegmentTemplate timescale="1000000" duration="2000000" startNumber="1" media="/testmedia/dash/period-' + name +
        '-$RepresentationID$-$Number$.m4s" initialization="/testmedia/dash/period-' + name + '-$RepresentationID$-init.m4s"/>' +
        '</Representation></AdaptationSet>';
    const head = live
        ? 'type="dynamic" availabilityStartTime="' + new Date(probe.epoch - 16000).toISOString() +
            '" minimumUpdatePeriod="PT2S" timeShiftBufferDepth="PT20S"'
        : 'type="static" mediaPresentationDuration="PT' + (names.length * 20) + 'S"';
    return '<?xml version="1.0" encoding="utf-8"?>\n<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" ' + head + '>' +
        names.map((name, index) => {
            const size = name === "b" ? 'width="640" height="360"' : 'width="320" height="180"';
            return '<Period id="' + name + '" start="PT' + (index * 20) + 'S" duration="PT20S">' +
                set(name, "video", "0", 'mimeType="video/mp4" codecs="avc1.42c01e" bandwidth="300000" ' + size) +
                set(name, "audio", "1", 'mimeType="audio/mp4" codecs="mp4a.40.2" bandwidth="96000"') + '</Period>';
        }).join("") + '</MPD>\n';
};
const kiteProbe = function () {
    return function (request, response, next) {
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Allow-Headers", "*");
        response.setHeader("Access-Control-Expose-Headers", "Content-Range, Content-Length, Date");
        if (request.method === "OPTIONS") {
            response.writeHead(204);
            return response.end();
        }
        const url = request.url;
        const send = (type, body) => {
            response.writeHead(200, { "Content-Type": type, "Cache-Control": "no-store" });
            response.end(body);
        };
        if (url.startsWith("/testmedia/")) {
            probe.asked.push({
                clip: url.slice("/testmedia/".length), host: request.headers.host,
                range: request.headers.range || null, marked: request.headers["x-kite-test"] || null,
                at: Date.now() - probe.epoch,
            });
        }
        if (!url.startsWith("/kite-probe/")) return next();
        const parsed = new URL(url, "http://probe");
        const name = parsed.pathname.slice("/kite-probe/".length);
        if (name === "reset") {
            probe.asked = [];
            probe.manifests = 0;
            probe.periodFetches = 0;
            probe.epoch = Date.now();
            return send("text/plain", "ok");
        }
        if (name === "asked") return send("application/json", JSON.stringify({ asked: probe.asked, manifests: probe.manifests }));
        if (name === "live.m3u8") return send("application/vnd.apple.mpegurl", liveHls());
        if (name === "live.mpd") {
            probe.manifests++;
            probe.asked.push({
                clip: "live.mpd", host: request.headers.host, range: null, marked: request.headers["x-kite-test"] || null,
                at: Date.now() - probe.epoch,
            });
            return send("application/dash+xml", liveDash(parsed.searchParams.get("origin") || "", parsed.searchParams.get("ended") === "1"));
        }
        const kept = () => probe.asked.push({ clip: name, host: request.headers.host, range: null, marked: null, at: Date.now() - probe.epoch });
        if (name === "periods.mpd") return send("application/dash+xml", periods(["a", "b", "c"], false));
        if (name === "live-periods.mpd") {
            kept();
            return send("application/dash+xml", periods(++probe.periodFetches > 1 ? ["a", "b"] : ["a"], true));
        }
        if (name.startsWith("moved/")) {
            probe.asked.push({ clip: name, host: request.headers.host, range: null, marked: null, at: Date.now() - probe.epoch });
            response.writeHead(302, { Location: "/testmedia/" + name.slice("moved/".length) });
            return response.end();
        }
        response.writeHead(404);
        response.end();
    };
};
config.plugins = (config.plugins || []).concat([{ "middleware:kiteProbe": ["factory", kiteProbe] }]);
config.beforeMiddleware = (config.beforeMiddleware || []).concat(["kiteProbe"]);
// ES modules need the mime type a browser accepts for a script.
config.mime = Object.assign({}, config.mime, { "text/javascript": ["mjs"], "application/wasm": ["wasm"] });
// The test reads this as __karma__.config.kiteWorker. Node has no such object, which is how the
// node half of the task knows to skip the test.
config.set({
    client: Object.assign({}, config.client, {
        kiteWorker: {
            worker: "/worker/kiteplayer-web-worker.mjs",
            codec: "/kite.mjs",
            media: "/testmedia",
            libass: "/kiteass.mjs",
        },
    }),
});
// A page's audio device starts only in a user gesture, and a test has none, so the sound would
// never play and the audio clock would sit at zero. This browser starts it without one.
config.set({
    browsers: ["ChromeHeadlessAutoplay"],
    customLaunchers: Object.assign({}, config.customLaunchers, {
        ChromeHeadlessAutoplay: { base: "ChromeHeadless", flags: ["--autoplay-policy=no-user-gesture-required"] },
    }),
});
