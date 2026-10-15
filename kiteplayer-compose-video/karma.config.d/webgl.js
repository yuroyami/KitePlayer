// The browser test of this module draws through the browser's WebGL2 (#460). Chrome with no GPU,
// which is what a CI runner has, gives WebGL through SwiftShader, and newer versions of Chrome do
// that only when asked to. Asked, it is the same Chrome otherwise.
config.set({
    browsers: ["ChromeHeadlessWebGl"],
    customLaunchers: Object.assign({}, config.customLaunchers, {
        ChromeHeadlessWebGl: {
            base: "ChromeHeadless",
            flags: ["--use-angle=swiftshader", "--enable-unsafe-swiftshader"],
        },
    }),
});
