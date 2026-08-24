// Mocha's per-test default is 2000 ms, and Kotlin does not raise it for browser tests.
//
// THIS IS NOT A HYPOTHETICAL LIMIT. SimulationCampaignTest runs a hundred seeded sessions and asks
// the Kotlin side for `runTest(timeout = 20.minutes)`, which mocha overrules from underneath: under
// wasm in a headless browser the campaign lands either side of two seconds depending on how loaded
// the machine is. CI proved it by passing the suite on one run and failing it on the next with
// identical code and the message "Error: Timeout of 2000ms exceeded".
//
// Raising the mocha ceiling weakens no assertion. The real bound stays the Kotlin timeout on the
// test itself; this only stops the harness killing a test that is still working. The browser and
// disconnect timeouts move with it so a genuinely hung run still ends rather than hanging the job.
config.set({
    client: Object.assign({}, config.client, {
        mocha: Object.assign({}, (config.client || {}).mocha, { timeout: 600000 }),
    }),
    browserNoActivityTimeout: 600000,
    browserDisconnectTimeout: 60000,
});
