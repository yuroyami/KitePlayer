// Runs one linked Kotlin/Wasm test module in headless Chrome, with no time limit but its own.
// `./kotlin test -p wasmJs` gives a module 30 seconds in all, and it serves only the module's test resources.
// This script serves the repository, the folders in --mount and the test server of --probe, opens
// browser-runner.html, and prints what the page logs. Chrome is driven over the DevTools protocol.
//
// Usage: node browser.mjs --entry <file>_test.mjs [--mount /prefix=<path>]... [--config <json>]
//                         [--chrome-flag <flag>]... [--probe] [--timeout-minutes <n>]
import { spawn } from "node:child_process";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { probeHandler } from "./probe.mjs";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");

function parseArgs(argv) {
  const args = { mounts: [], flags: [], config: null, probe: false, entry: null, timeoutMinutes: 45 };
  for (let i = 0; i < argv.length; i++) {
    const value = () => argv[++i];
    switch (argv[i]) {
      case "--entry": args.entry = path.resolve(value()); break;
      case "--mount": {
        const [prefix, target] = value().split(/=(.*)/s);
        args.mounts.push({ prefix, target: path.resolve(target) });
        break;
      }
      case "--config": args.config = value(); break;
      case "--chrome-flag": args.flags.push(value()); break;
      case "--probe": args.probe = true; break;
      case "--timeout-minutes": args.timeoutMinutes = Number(value()); break;
      default: throw new Error(`Unknown argument ${argv[i]}`);
    }
  }
  if (!args.entry) throw new Error("Pass --entry <file>_test.mjs");
  // The longest prefix wins.
  args.mounts.sort((a, b) => b.prefix.length - a.prefix.length);
  return args;
}

const TYPES = {
  ".mjs": "text/javascript", ".js": "text/javascript", ".wasm": "application/wasm", ".html": "text/html",
  ".json": "application/json", ".css": "text/css", ".mp4": "video/mp4", ".m4s": "video/iso.segment",
  ".m4a": "audio/mp4", ".webm": "video/webm", ".mkv": "video/x-matroska", ".ts": "video/mp2t",
  ".m3u8": "application/vnd.apple.mpegurl", ".mpd": "application/dash+xml", ".vtt": "text/vtt",
  ".srt": "text/plain", ".ass": "text/plain", ".ttf": "font/ttf", ".otf": "font/otf", ".png": "image/png",
};

/** The file that [urlPath] names: under the longest matching mount, else under the repository. */
function resolveFile(urlPath, mounts) {
  for (const { prefix, target } of mounts) {
    if (urlPath === prefix) return target;
    if (prefix.endsWith("/") && urlPath.startsWith(prefix)) return within(target, urlPath.slice(prefix.length));
  }
  return within(ROOT, urlPath.slice(1));
}

function within(base, relative) {
  const file = path.resolve(base, relative);
  return file === base || file.startsWith(base + path.sep) ? file : null;
}

/** Serves one file, with single byte ranges, as karma did. */
function serveFile(request, response, file) {
  let stat;
  try {
    stat = fs.statSync(file);
  } catch {
    stat = null;
  }
  if (!stat || !stat.isFile()) {
    response.writeHead(404);
    return response.end();
  }
  const headers = {
    "Content-Type": TYPES[path.extname(file).toLowerCase()] || "application/octet-stream",
    "Accept-Ranges": "bytes",
    "Cache-Control": "no-store",
  };
  let start = 0;
  let end = stat.size - 1;
  let status = 200;
  const range = /^bytes=(\d*)-(\d*)$/.exec(request.headers.range || "");
  if (range && (range[1] || range[2])) {
    if (range[1]) {
      start = Number(range[1]);
      if (range[2]) end = Math.min(Number(range[2]), stat.size - 1);
    } else {
      start = Math.max(0, stat.size - Number(range[2]));
    }
    if (start > end || start >= stat.size) {
      response.writeHead(416, { "Content-Range": `bytes */${stat.size}` });
      return response.end();
    }
    status = 206;
    headers["Content-Range"] = `bytes ${start}-${end}/${stat.size}`;
  }
  headers["Content-Length"] = String(end - start + 1);
  response.writeHead(status, headers);
  if (request.method === "HEAD" || stat.size === 0) return response.end();
  fs.createReadStream(file, { start, end }).pipe(response);
}

/** Starts the server on 127.0.0.1 and, on the same port, on ::1, so that both loopback names answer. */
async function startServer(args) {
  const probe = args.probe ? probeHandler() : null;
  const handle = (request, response) => {
    const urlPath = decodeURIComponent(new URL(request.url, "http://local").pathname);
    if (probe && probe(request, response)) return;
    const file = resolveFile(urlPath, args.mounts);
    if (!file) {
      response.writeHead(403);
      return response.end();
    }
    serveFile(request, response, file);
  };
  const servers = [http.createServer(handle)];
  await new Promise((resolve, reject) => servers[0].once("error", reject).listen(0, "127.0.0.1", resolve));
  const port = servers[0].address().port;
  const second = http.createServer(handle);
  await new Promise((resolve) => second.once("error", resolve).listen(port, "::1", () => {
    servers.push(second);
    resolve();
  }));
  return {
    port,
    close: () => servers.forEach((server) => {
      server.close();
      server.closeAllConnections();
    }),
  };
}

function findChrome() {
  const home = os.homedir();
  const candidates = [process.env.CHROME];
  for (const cache of [path.join(home, "Library/Caches/ms-playwright"), path.join(home, ".cache/ms-playwright")]) {
    const builds = fs.existsSync(cache) ? fs.readdirSync(cache).filter((name) => /^chromium-\d+$/.test(name)) : [];
    builds.sort((a, b) => Number(b.split("-")[1]) - Number(a.split("-")[1]));
    for (const build of builds) {
      candidates.push(
        path.join(cache, build, "chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing"),
        path.join(cache, build, "chrome-mac/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing"),
        path.join(cache, build, "chrome-linux64/chrome"),
        path.join(cache, build, "chrome-linux/chrome"),
      );
    }
  }
  for (const name of ["google-chrome", "google-chrome-stable", "chromium", "chromium-browser"]) {
    for (const dir of (process.env.PATH || "").split(path.delimiter)) candidates.push(path.join(dir, name));
  }
  candidates.push("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
  const found = candidates.find((candidate) => candidate && fs.existsSync(candidate));
  if (!found) throw new Error("No Chrome found. Set CHROME to a Chrome or Chromium executable.");
  return found;
}

/** Starts headless Chrome and returns it with the address of its DevTools endpoint. */
async function startChrome(flags) {
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), "kiteplayer-web-tests-"));
  const chromeArgs = ["--headless=new", "--remote-debugging-port=0", `--user-data-dir=${profile}`, "--no-first-run",
    "--no-default-browser-check", "--disable-background-timer-throttling", "--disable-renderer-backgrounding", ...flags];
  if (process.env.CI && process.platform === "linux") chromeArgs.push("--no-sandbox");
  const chrome = spawn(findChrome(), [...chromeArgs, "about:blank"], { stdio: ["ignore", "ignore", "pipe"] });
  const endpoint = await new Promise((resolve, reject) => {
    let seen = "";
    chrome.stderr.on("data", (chunk) => {
      seen += chunk;
      const found = /DevTools listening on (ws:\/\/\S+)/.exec(seen);
      if (found) resolve(found[1]);
    });
    chrome.once("exit", (code) => reject(new Error(`Chrome exited with ${code} before it listened:\n${seen}`)));
  });
  const stop = () => {
    chrome.kill();
    try {
      fs.rmSync(profile, { recursive: true, force: true });
    } catch {
      // Chrome may still hold a file for a moment. The folder is in the temporary directory.
    }
  };
  return { endpoint, stop };
}

/** A DevTools protocol connection to the first page of the browser. */
async function connectToPage(endpoint) {
  const base = new URL(endpoint);
  const targets = await (await fetch(`http://${base.host}/json/list`)).json();
  const page = targets.find((target) => target.type === "page");
  if (!page) throw new Error("Chrome has no page to drive");
  const socket = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => {
    socket.addEventListener("open", resolve, { once: true });
    socket.addEventListener("error", reject, { once: true });
  });
  let nextId = 1;
  const pending = new Map();
  const listeners = [];
  socket.addEventListener("message", (event) => {
    const message = JSON.parse(event.data);
    if (message.id && pending.has(message.id)) {
      pending.get(message.id)(message);
      pending.delete(message.id);
    } else if (message.method) {
      listeners.forEach((listener) => listener(message));
    }
  });
  return {
    send: (method, params = {}) => new Promise((resolve) => {
      const id = nextId++;
      pending.set(id, resolve);
      socket.send(JSON.stringify({ id, method, params }));
    }),
    onEvent: (listener) => listeners.push(listener),
    close: () => socket.close(),
  };
}

function consoleText(argument) {
  if ("value" in argument) return typeof argument.value === "string" ? argument.value : JSON.stringify(argument.value);
  return argument.description ?? argument.type;
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const server = await startServer(args);
  const chrome = await startChrome(args.flags);
  let page;
  try {
    page = await connectToPage(chrome.endpoint);
    const finished = new Promise((resolve) => {
      page.onEvent(({ method, params }) => {
        if (method === "Runtime.consoleAPICalled") {
          const line = params.args.map(consoleText).join(" ");
          console.log(line);
          const result = /^RESULT (\d+) passed, (\d+) failed, (\d+) ignored/.exec(line);
          if (result) resolve({ passed: Number(result[1]), failed: Number(result[2]) });
        } else if (method === "Runtime.exceptionThrown") {
          console.log(`Uncaught: ${params.exceptionDetails.exception?.description ?? params.exceptionDetails.text}`);
        } else if (method === "Inspector.targetCrashed") {
          console.log("The page crashed");
          resolve({ passed: 0, failed: 1 });
        }
      });
    });
    await page.send("Runtime.enable");
    await page.send("Inspector.enable");
    const relative = path.relative(ROOT, args.entry).split(path.sep).join("/");
    const query = new URLSearchParams({ entry: `/${relative}` });
    if (args.config) query.set("config", args.config);
    await page.send("Page.navigate", { url: `http://localhost:${server.port}/scripts/web-tests/browser-runner.html?${query}` });
    let timer;
    const timeout = new Promise((resolve) => {
      timer = setTimeout(() => {
        console.log(`The tests did not finish in ${args.timeoutMinutes} minutes`);
        resolve({ passed: 0, failed: 1 });
      }, args.timeoutMinutes * 60_000);
    });
    const counts = await Promise.race([finished, timeout]);
    clearTimeout(timer);
    process.exitCode = counts.failed > 0 || counts.passed === 0 ? 1 : 0;
  } finally {
    page?.close();
    chrome.stop();
    server.close();
  }
}

main().catch((error) => {
  console.error(error?.stack ?? error);
  process.exit(1);
});
