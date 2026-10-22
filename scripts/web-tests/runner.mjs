// Runs one linked Kotlin/JS or Kotlin/Wasm test module in Node and reports every test.
// Usage: node runner.mjs <module>_test.mjs
import { pathToFileURL } from "node:url";
import { collectTests, loadTests, resultLine, runTests } from "./harness.mjs";

const root = collectTests();
await loadTests(pathToFileURL(process.argv[2]).href);
const counts = await runTests(root, (line) => console.log(line));
console.log(resultLine(counts));
process.exitCode = counts.failed > 0 || counts.passed === 0 ? 1 : 0;
