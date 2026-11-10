// Runs the tests of one linked Kotlin/JS or Kotlin/Wasm test module, in Node or in a browser.
// kotlin-test reports its tests to Mocha-style describe/it functions when they exist. This file defines
// them, collects the tests, then runs them one at a time, each under a time limit (#726).

/** Defines describe, it, xdescribe and xit, and returns the root of the test tree that they fill. */
export function collectTests() {
  const root = { name: "", children: [], skipped: false };
  let current = root;

  function suite(name, body, skipped) {
    const node = { name, children: [], skipped: skipped || current.skipped };
    current.children.push(node);
    const parent = current;
    current = node;
    try {
      body();
    } finally {
      current = parent;
    }
  }

  globalThis.describe = (name, body) => suite(name, body, false);
  globalThis.xdescribe = (name, body) => suite(name, body, true);
  globalThis.it = (name, body) => current.children.push({ name, body, skipped: current.skipped });
  globalThis.xit = (name, body) => current.children.push({ name, body, skipped: true });
  // A Kotlin/Wasm test module reads this object at its start. The Kotlin Gradle plugin's runner defined it.
  globalThis.kotlinTest ??= { adapterTransformer: (adapter) => adapter };
  return root;
}

/** Imports the linked test module. A Kotlin/Wasm module declares its tests only when startUnitTests runs. */
export async function loadTests(entry) {
  const module = await import(entry);
  if (typeof module.startUnitTests === "function") module.startUnitTests();
}

/**
 * How long one test may take before it fails as timed out: above every test's own runTest limit in
 * the tree, the longest of which is 20 minutes, so a slow test still fails by its own words. This is
 * the backstop for a test that never settles at all, which used to stall its module with no name
 * until the whole run's limit or the CI job's. A module's config can set `testTimeoutMs`.
 */
export const DEFAULT_TEST_TIMEOUT_MS = 25 * 60 * 1000;

/** The limit for this run: the page config's `testTimeoutMs`, else KITE_TEST_TIMEOUT_MS in Node, else the default. */
function testTimeoutMs() {
  const configured = globalThis.__karma__?.config?.testTimeoutMs
    ?? (typeof process !== "undefined" ? process.env?.KITE_TEST_TIMEOUT_MS : undefined);
  const value = Number(configured);
  return Number.isFinite(value) && value > 0 ? value : DEFAULT_TEST_TIMEOUT_MS;
}

/**
 * [body]'s result, or a rejection naming the limit once [limitMs] pass. A body that never settles
 * keeps running, because nothing can stop it, but the run goes on and names it.
 */
export function withTimeLimit(body, limitMs) {
  let timer;
  const limit = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error(`timed out after ${limitMs} ms`)), limitMs);
  });
  return Promise.race([Promise.resolve().then(body), limit]).finally(() => clearTimeout(timer));
}

/** Runs every test under [root] and returns the counts. [log] receives one line for each failed test. */
export async function runTests(root, log, limitMs = testTimeoutMs()) {
  const counts = { passed: 0, failed: 0, ignored: 0 };

  async function run(node, path) {
    for (const child of node.children) {
      const name = path ? `${path}.${child.name}` : child.name;
      if (child.children) {
        await run(child, name);
      } else if (child.skipped) {
        counts.ignored++;
      } else {
        try {
          // An asynchronous test, such as one that uses runTest, returns a promise.
          await withTimeLimit(child.body, limitMs);
          counts.passed++;
        } catch (error) {
          counts.failed++;
          log(`Failed ${name}: ${error?.stack ?? error}`);
        }
      }
    }
  }

  await run(root, "");
  return counts;
}

/** The last line of a run, which run.py reads. */
export function resultLine(counts) {
  return `RESULT ${counts.passed} passed, ${counts.failed} failed, ${counts.ignored} ignored`;
}
