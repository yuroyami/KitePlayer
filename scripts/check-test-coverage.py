#!/usr/bin/env python3
"""Fails when a module's tests for a platform are run by no CI job (#637).

CI names every test run by hand, module by module, so a test folder that no job names compiles only
when something else pulls it in and never runs: 83 tests in five folders sat that way, among them
the Android media session's and the iOS GPU path's. This reads each module's test folders for the
platforms CI runs, and the jobs of .github/workflows/ci.yml with the web runner's module lists, and
names every pair no job covers.

A `run:` block of ci.yml covers a module on a platform when it names both: `-p <platform>` or the
platform's task, and the module, as a `-m` flag or in the list a loop walks. The browser and Node
platforms are the lists of scripts/web-tests/run.py, which ci.yml runs. A folder for an alias, such
as test@apple or test@native, is covered through the platforms of its own jobs and is not read here.

A module's common `test/` folder must run on every platform of COMMON_PLATFORMS that its module.yaml
declares, unless EXCUSED names why it cannot (#728).

    python3 scripts/check-test-coverage.py    # prints the gaps and exits 1, or exits 0
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]

# Each test folder, by its platform, and the word a ci.yml block names that platform by.
FOLDERS = {
    "test@jvm": "jvm",
    "test@android": "android",
    "test@ios": "iosSimulatorArm64",
    "test@iosSimulatorArm64": "iosSimulatorArm64",
    "test@macos": "macosArm64",
    "test@macosArm64": "macosArm64",
    "test@wasmJs": "wasmJs",
    "test@js": "js",
}

# The platforms on which CI can run a module's common `test/` folder. A module that declares one of
# these has its common tests run there, or names the reason it cannot in EXCUSED (#728).
COMMON_PLATFORMS = (
    "jvm", "android", "js", "wasmJs", "mingwX64", "linuxX64", "linuxArm64", "macosArm64", "iosSimulatorArm64",
)

# Common tests a declared platform deliberately does not run, each with its reason.
EXCUSED = {
    ("kiteplayer-ffmpeg", "iosSimulatorArm64"):
        "the simulator cannot start the CoreAudio output unit or give a VideoToolbox frame (#155)",
    ("kiteplayer-ffmpeg", "mingwX64"):
        "the Windows job links the FFmpeg backend's tests but has no media to run them on",
}


def ci_blocks():
    """The `run:` blocks of ci.yml, each as its text."""
    text = (ROOT / ".github/workflows/ci.yml").read_text()
    blocks, current, indent = [], None, None
    for line in text.splitlines():
        match = re.match(r"^(\s*)(?:-\s+)?run:\s*(.*)$", line)
        if match:
            if current is not None:
                blocks.append("\n".join(current))
            indent = len(match.group(1))
            current = [match.group(2)]
            continue
        if current is not None:
            if line.strip() and len(line) - len(line.lstrip()) <= indent:
                blocks.append("\n".join(current))
                current = None
            else:
                current.append(line)
    if current is not None:
        blocks.append("\n".join(current))
    return blocks


def covered_by_ci():
    """Every (module, platform) a ci.yml block runs."""
    pairs = set()
    for block in ci_blocks():
        if not any(word in block for word in ("kotlin test", "kotlin.bat test", "run-native-tests.sh", ".kexe", "audioviz-tests.sh")):
            continue
        platforms = set(re.findall(r"-p\s+\"?([A-Za-z0-9]+)", block))
        if "linkLinuxArm64Test" in block or "linux-arm64-tests" in block:
            platforms.add("linuxArm64")
        modules = set(re.findall(r"\b(kiteplayer(?:-[a-z0-9]+)*)\b", block))
        pairs |= {(module, platform) for module in modules for platform in platforms}
        # The visualiser's JVM tests run through their own gate script, which leaves out the surveys.
        if "audioviz-tests.sh gate" in block:
            pairs.add(("kiteplayer-audioviz", "jvm"))
    return pairs


def covered_by_web_runner():
    """The modules scripts/web-tests/run.py runs on js and wasmJs, which ci.yml calls."""
    source = (ROOT / "scripts/web-tests/run.py").read_text()

    def names(constant):
        match = re.search(rf"^{constant}\s*=\s*(.*?)\)\n", source, re.S | re.M)
        return set(re.findall(r'"(kiteplayer[a-z0-9-]*)"', match.group(1))) if match else set()

    wasm = names("WASM_NODE_MODULES") | names("WASM_BROWSER_MODULES")
    return {(m, "js") for m in names("JS_MODULES")} | {(m, "wasmJs") for m in wasm}


def declared_platforms(module_dir):
    """The platforms a module's module.yaml declares under `product.platforms`."""
    text = (module_dir / "module.yaml").read_text()
    match = re.search(r"^\s+platforms:\s*\n((?:\s+-\s+\w+\s*\n)+)", text, re.M)
    return set(re.findall(r"-\s+(\w+)", match.group(1))) if match else set()


def main() -> int:
    covered = covered_by_ci() | covered_by_web_runner()
    gaps = []
    for module_dir in sorted(ROOT.glob("kiteplayer*/")):
        module = module_dir.name
        for folder, platform in FOLDERS.items():
            tests = module_dir / folder
            if tests.is_dir() and any(tests.rglob("*.kt")) and (module, platform) not in covered:
                gaps.append(f"{module}/{folder}: no CI job runs {module}'s tests on {platform}")
        common = module_dir / "test"
        if common.is_dir() and any(common.rglob("*.kt")):
            for platform in COMMON_PLATFORMS:
                if platform in declared_platforms(module_dir) and (module, platform) not in covered \
                        and (module, platform) not in EXCUSED:
                    gaps.append(f"{module}/test: {module} declares {platform}, and no CI job runs its common tests there")
    for gap in gaps:
        print(gap)
    if gaps:
        print(f"{len(gaps)} test folder(s) no job runs. Name each in the job for its platform (#637, #728).")
        return 1
    print("every test folder for a platform CI runs is named by a job")
    return 0


if __name__ == "__main__":
    sys.exit(main())
