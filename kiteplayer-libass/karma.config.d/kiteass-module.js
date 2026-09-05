// The web tests import the real kiteass.mjs. Under karma the bundle is served from a temporary
// directory, so a relative import finds nothing; the two module files are served here from the
// module's own build output and proxied to the root, where the test's fallback URL looks.
// karma.conf.js lives in <root>/build/wasm/packages/<project>-test, four levels under the root.
const path = require("path");
const kiteassDir = path.resolve(__dirname, "../../../../kiteplayer-libass/build/kiteass");
config.files.push({ pattern: path.join(kiteassDir, "*"), included: false, served: true, watched: false });
// Percent-encoded: this repository lives under a directory named "#Kite", and a bare '#' in a URL
// is a fragment, so the proxy target would otherwise stop at the hash.
const served = (name) => "/absolute" + encodeURI(path.join(kiteassDir, name)).replace(/#/g, "%23");
config.proxies = Object.assign({}, config.proxies, {
    "/kiteass.mjs": served("kiteass.mjs"),
    "/kiteass.wasm": served("kiteass.wasm"),
});
// ES modules need the mime type a browser accepts for a script.
config.mime = Object.assign({}, config.mime, { "text/javascript": ["mjs"], "application/wasm": ["wasm"] });
