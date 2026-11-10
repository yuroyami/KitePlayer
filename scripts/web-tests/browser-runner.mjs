// The page half of browser.mjs. It reports through the console, which browser.mjs reads.
import { collectTests, loadTests, resultLine, runTests } from "./harness.mjs";

const query = new URLSearchParams(location.search);
// Some tests read their server paths from the client config that karma gave the page. browser.mjs passes the same.
const config = query.get("config");
if (config) globalThis.__karma__ = { config: JSON.parse(config) };
// Says that this page is the one run.py serves, with every mount a test may need, so a test can tell
// a broken mount from the toolchain's own browser runner, which never has them (#727).
globalThis.__kiteWebTests = true;

try {
  const root = collectTests();
  await loadTests(query.get("entry"));
  const counts = await runTests(root, (line) => console.log(line));
  console.log(resultLine(counts));
} catch (error) {
  console.log(`Failed to load the tests: ${error?.stack ?? error}`);
  console.log(resultLine({ passed: 0, failed: 1, ignored: 0 }));
}
