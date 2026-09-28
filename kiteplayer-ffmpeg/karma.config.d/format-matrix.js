// The format matrix runs in the browser half of wasmJsTest (WebFormatMatrixTest, #59). The page
// gets two things from disk, proxied to fixed paths:
// - the codec module from the kiteffmpeg web zip, which :kiteplayer-ffmpeg:unpackKiteFFmpegWebModule
//   unpacks, at /kite.mjs and /kite.wasm;
// - the clips of the repository's testmedia directory, at /testmedia/<clip>.
// karma.conf.js lives in <root>/build/wasm/packages/<project>-test, four levels under the root.
const path = require("path");
const root = path.resolve(__dirname, "../../../..");
const moduleDir = path.join(root, "kiteplayer-ffmpeg/build/kiteffmpeg-web");
const mediaDir = process.env.KITEPLAYER_TESTMEDIA || path.join(root, "testmedia");
// nocache stops karma from reading every clip into memory at start. The directory holds a 423 MB
// soak clip that this test never opens.
config.files.push(
    { pattern: path.join(moduleDir, "*"), included: false, served: true, watched: false },
    { pattern: path.join(mediaDir, "*"), included: false, served: true, watched: false, nocache: true },
);
// Percent-encoded: this repository lives under a directory named "#Kite", and a bare '#' in a URL
// is a fragment, so the proxy target would otherwise stop at the hash.
const served = (file) => "/absolute" + encodeURI(file).replace(/#/g, "%23");
config.proxies = Object.assign({}, config.proxies, {
    "/kite.mjs": served(path.join(moduleDir, "kite.mjs")),
    "/kite.wasm": served(path.join(moduleDir, "kite.wasm")),
    "/testmedia/": served(mediaDir + "/"),
});
// ES modules need the mime type a browser accepts for a script.
config.mime = Object.assign({}, config.mime, { "text/javascript": ["mjs"], "application/wasm": ["wasm"] });
// The test reads this as __karma__.config.kiteMatrix. Node has no such object, which is how the
// node half of the task knows to skip the matrix.
config.set({
    client: Object.assign({}, config.client, {
        kiteMatrix: { module: "/kite.mjs", media: "/testmedia" },
    }),
});
