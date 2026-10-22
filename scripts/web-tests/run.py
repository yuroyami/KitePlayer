#!/usr/bin/env python3
"""Run the web tests: Kotlin/JS and Kotlin/Wasm in Node, and Kotlin/Wasm in headless Chrome.

`./kotlin test` cannot run JS tests, and it gives the wasmJs tests of a module 30 seconds in all in Chrome.
This script links the tests of each module with the toolchain, then runs them: in Node through runner.mjs,
and in Chrome through browser.mjs, which also serves the clips and test servers that some tests read.
The output of each run is also written to build/web-tests/<module>-<platform>.log.

Usage: python3 scripts/web-tests/run.py [--platform js|wasmJs|wasmJsBrowser]... [--module <name>]...
"""
import argparse
import glob
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
# The modules whose tests run on each platform.
JS_MODULES = (
    "kiteplayer-core",
    "kiteplayer-subtitles",
    "kiteplayer-io",
    "kiteplayer-adaptive",
    "kiteplayer-network",
    "kiteplayer",
    "kiteplayer-libass",
)
WASM_NODE_MODULES = (
    "kiteplayer-core",
    "kiteplayer-subtitles",
    "kiteplayer-output",
    "kiteplayer-ffmpeg",
    "kiteplayer",
    "kiteplayer-libass",
    "kiteplayer-adaptive",
    "kiteplayer-network",
    "kiteplayer-view",
)
# The worker player and the WebGL pass need a page, so these two run only in the browser.
WASM_BROWSER_MODULES = WASM_NODE_MODULES + ("kiteplayer-web-worker", "kiteplayer-compose-video")
ARTIFACTS = ROOT / "build/artifacts/CompiledWebArtifact"
LOGS = ROOT / "build/web-tests"
CACHE = Path.home() / ("Library/Caches/JetBrains/Kotlin" if sys.platform == "darwin" else ".cache/JetBrains/Kotlin")
TESTMEDIA = Path(os.environ.get("KITEPLAYER_TESTMEDIA", ROOT / "testmedia"))


def node():
    """Node from PATH, else the one the Kotlin Toolchain downloaded."""
    found = shutil.which("node")
    if found:
        return found
    downloaded = sorted(glob.glob(str(CACHE / "extract.cache/*node-v*/bin/node")))
    if downloaded:
        return downloaded[-1]
    raise RuntimeError(f"No node executable on PATH or in {CACHE}")


def run(*args):
    print("Running " + " ".join(str(a) for a in args[:3]) + (" ..." if len(args) > 3 else ""), flush=True)
    subprocess.run([str(a) for a in args], cwd=ROOT, check=True)


def kotlin(*args):
    run(ROOT / ("kotlin.bat" if sys.platform == "win32" else "kotlin"), *args)


def run_tests(module, platform, *args):
    """Runs one test command, prints and keeps its output, and fails when it reports a failure or no test."""
    LOGS.mkdir(parents=True, exist_ok=True)
    log = LOGS / f"{module}-{platform}.log"
    print(f"Running the {platform} tests of {module}", flush=True)
    with subprocess.Popen([str(a) for a in args], cwd=ROOT, text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT) as process, log.open("w") as kept:
        result = None
        for line in process.stdout:
            print(line, end="", flush=True)
            kept.write(line)
            found = re.match(r"RESULT (\d+) passed, (\d+) failed, (\d+) ignored", line)
            if found:
                result = found
    print(f"{module} {platform}: " + (result.group(0)[len("RESULT "):] if result else "no result"), flush=True)
    if process.returncode != 0 or not result or int(result.group(2)) > 0 or int(result.group(1)) == 0:
        raise RuntimeError(f"the {platform} tests of {module} failed")


def js_tests(module, executable):
    kotlin("task", f":{module}:linkJsTest")
    entry = ARTIFACTS / f"{module}jsTestrelease/kotlin-output/{module}_test.mjs"
    run_tests(module, "js", executable, HERE / "runner.mjs", entry)


def wasm_entry(module):
    """The test module in the folder that the toolchain fills for its own browser run, beside the test resources."""
    kotlin("task", f":{module}:buildWasmJsAppWasmJsTestDebug")
    return ROOT / f"build/tasks/_{module}_buildWasmJsAppWasmJsTestDebug/{module}_test.mjs"


def wasm_node_tests(module, executable):
    run_tests(module, "wasmJs", executable, HERE / "runner.mjs", wasm_entry(module))


def worker_build(module):
    """The folder of the debug build of the worker program, which the worker tests start."""
    kotlin("task", f":{module}:buildWasmJsAppWasmJsDebug")
    return ROOT / f"build/tasks/_{module}_buildWasmJsAppWasmJsDebug"


def browser_setup(module):
    """The extra files, client config and Chrome flags that the tests of [module] need, as karma gave them."""
    mounts = {}
    # The toolchain serves each file of the module's test resources at the root, and so does this.
    resources = ROOT / module / "testResources@wasmJs"
    if resources.is_dir():
        mounts.update({f"/{file.name}": file for file in resources.iterdir() if file.is_file()})
    config = None
    flags = []
    probe = False
    if module == "kiteplayer-ffmpeg":
        mounts["/testmedia/"] = TESTMEDIA
        config = {"kiteMatrix": {"module": "/kite.mjs", "media": "/testmedia"}}
    elif module == "kiteplayer-web-worker":
        mounts["/worker/"] = worker_build(module)
        mounts["/testmedia/"] = TESTMEDIA
        config = {"kiteWorker": {"worker": f"/worker/{module}.mjs", "codec": "/kite.mjs", "media": "/testmedia",
                                 "libass": "/kiteass.mjs"}}
        flags.append("--autoplay-policy=no-user-gesture-required")
        probe = True
    elif module == "kiteplayer-compose-video":
        # The WebGL that Chrome gives a machine without a GPU.
        flags += ["--use-angle=swiftshader", "--enable-unsafe-swiftshader"]
    return mounts, config, flags, probe


def wasm_browser_tests(module, executable):
    entry = wasm_entry(module)
    mounts, config, flags, probe = browser_setup(module)
    args = [executable, HERE / "browser.mjs", "--entry", entry]
    for prefix, target in mounts.items():
        args += ["--mount", f"{prefix}={target}"]
    if config:
        args += ["--config", json.dumps(config)]
    for flag in flags:
        args += ["--chrome-flag", flag]
    if probe:
        args.append("--probe")
    run_tests(module, "wasmJsBrowser", *args)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--platform", choices=("js", "wasmJs", "wasmJsBrowser"), action="append",
                        help="run one platform; repeat it for more (default: all three)")
    parser.add_argument("--module", action="append", help="run one module; repeat it for more (default: all)")
    args = parser.parse_args()
    platforms = args.platform or ["js", "wasmJs", "wasmJsBrowser"]
    chosen = set(args.module or [])
    executable = node()
    failures = []
    runs = (("js", JS_MODULES, js_tests), ("wasmJs", WASM_NODE_MODULES, wasm_node_tests),
            ("wasmJsBrowser", WASM_BROWSER_MODULES, wasm_browser_tests))
    for platform, modules, test in runs:
        if platform not in platforms:
            continue
        for module in modules:
            if chosen and module not in chosen:
                continue
            try:
                test(module, executable)
            except (subprocess.CalledProcessError, RuntimeError) as error:
                failures.append(f"{module} {platform}: {error}")
    if failures:
        raise RuntimeError("Failed:\n  " + "\n  ".join(failures))


if __name__ == "__main__":
    try:
        main()
    except (subprocess.CalledProcessError, RuntimeError, OSError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
