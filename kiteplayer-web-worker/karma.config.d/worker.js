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
