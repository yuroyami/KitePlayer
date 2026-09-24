// Same 2000 ms mocha default as :kiteplayer-core, and the same reason: Kotlin does not raise it, so
// any browser test that runs longer than two seconds is killed while it is still working. See
// kiteplayer-core/karma.config.d/timeouts.js for the run that proved it.
config.set({
    client: Object.assign({}, config.client, {
        mocha: Object.assign({}, (config.client || {}).mocha, { timeout: 600000 }),
    }),
    browserNoActivityTimeout: 600000,
    browserDisconnectTimeout: 60000,
});
