#!/usr/bin/env python3
"""Fails when a module's tests for a platform are run by no CI job (#637).

CI names every test run by hand, module by module, so a test folder that no job names compiles only
when something else pulls it in and never runs: 83 tests in five folders sat that way, among them
the Android media session's and the iOS GPU path's. This reads each module's test folders for the
platforms CI runs, and the jobs of .github/workflows/ci.yml with the web runner's module lists, and
names every pair no job covers.

A test command in a `run:` block of ci.yml covers a module on a platform when its own `-p` and `-m`
flags name them, with a loop variable read as the list the loop walks; comments count for nothing,
and a test binary run by hand takes its platform from where it was linked (#730). The web runner's
module lists count for a platform only when a block calls the runner with it. A folder for an alias, such
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


def ci_blocks(text=None):
    """The `run:` blocks of ci.yml, each as its text."""
    if text is None:
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


def commands(block):
    """The shell commands of a block: comments dropped, continued and folded lines joined."""
    lines = block.splitlines()
    folded = bool(lines) and lines[0].strip().startswith(">")
    if lines and lines[0].strip() in ("|", ">", "|-", ">-"):
        lines = lines[1:]
    kept = []
    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        # A comment after a command starts at a ` #` outside quotes.
        quote, cut = None, len(stripped)
        for i, ch in enumerate(stripped):
            if quote:
                if ch == quote:
                    quote = None
            elif ch in "'\"":
                quote = ch
            elif ch == "#" and i > 0 and stripped[i - 1] in " \t":
                cut = i
                break
        kept.append(stripped[:cut].rstrip())
    text = " ".join(kept) if folded else "\n".join(kept)
    text = re.sub(r"\\\n", " ", text)
    out = []
    for line in text.splitlines():
        out += [part.strip() for part in re.split(r";|&&|\|\||\|", line) if part.strip()]
    return out


def flags(words, flag, variables):
    """The values each `flag` takes in `words`, with a loop variable expanded to its values."""
    found = []
    for i, word in enumerate(words[:-1]):
        if word == flag:
            found += expand(words[i + 1], variables)
    return found


def expand(word, variables):
    word = word.strip("\"'")
    match = re.fullmatch(r"\$\{?(\w+)\}?", word)
    if match:
        return list(variables.get(match.group(1), []))
    return [word]


def covered_by_ci(text=None, runner=None):
    """Every (module, platform) a test command in ci.yml runs, read command by command (#730)."""
    pairs = set()
    lists = web_runner_lists(runner)
    for block in ci_blocks(text):
        loops = []  # (variable, values) of each `for` the command sits in
        binary_platform = None
        for command in commands(block):
            words = command.split()
            if not words:
                continue
            if words[0] == "for" and len(words) >= 3 and words[2] == "in":
                loops.append((words[1], [w.strip("\"'") for w in words[3:]]))
                continue
            if words[0] == "done":
                if loops:
                    loops.pop()
                continue
            if words[0] == "do":
                words = words[1:]
                if not words:
                    continue
            variables = dict(loops)
            tool = words[0]
            if tool.endswith(("/kotlin", "kotlin.bat")) or tool == "kotlin":
                if len(words) > 1 and words[1] == "test":
                    modules = flags(words, "-m", variables)
                    platforms = flags(words, "-p", variables)
                    pairs |= {(m, p) for m in modules for p in platforms}
            elif tool.endswith("run-native-tests.sh"):
                modules = flags(words, "-m", variables)
                platforms = flags(words, "-p", variables)
                pairs |= {(m, p) for m in modules for p in platforms}
            elif tool.endswith("audioviz-tests.sh") and "gate" in words:
                # The visualiser's JVM tests run through their own gate script, which leaves out the surveys.
                pairs.add(("kiteplayer-audioviz", "jvm"))
            elif tool.endswith("web-tests/run.py") or (tool == "python3" and len(words) > 1
                                                        and words[1].endswith("web-tests/run.py")):
                asked = flags(words, "--platform", variables) or list(WEB_PLATFORMS)
                only = set(flags(words, "--module", variables))
                for platform in asked:
                    for module in lists.get(platform, ()):
                        if not only or module in only:
                            pairs.add((module, WEB_PLATFORMS[platform]))
            # A test binary that a loop finds and then runs: the platform comes from the path the
            # binary is taken from, the modules from the loop.
            assignment = re.match(r"^(?:binary|path)=(.*)$", command)
            if assignment:
                value = assignment.group(1)
                binary_platform = next((p for key, p in BINARY_PLATFORMS.items() if key in value), None)
                continue
            if binary_platform and re.search(r"(?:^|[\s(\"])(?:\"?\$binary\"?|\./test\.kexe)(?:$|[\s)\"])", command) \
                    and words[0] not in ("cp", "chmod", "adb", "find", "[") or \
                    (binary_platform and words[0] == "adb" and "./test.kexe" in command):
                modules = [v for _, values in loops for v in values if v.startswith("kiteplayer")]
                pairs |= {(m, binary_platform) for m in modules}
    return pairs


# The web runner's platforms, and the platform each one tests.
WEB_PLATFORMS = {"js": "js", "wasmJs": "wasmJs", "wasmJsBrowser": "wasmJs"}

# Where a test binary that CI runs by hand comes from, and its platform.
BINARY_PLATFORMS = {"linux-arm64-tests/": "linuxArm64", "linkAndroidNativeX64Test": "androidNativeX64"}


def web_runner_lists(source=None):
    """The module lists of scripts/web-tests/run.py, by the --platform that runs each."""
    if source is None:
        source = (ROOT / "scripts/web-tests/run.py").read_text()

    def names(constant):
        match = re.search(rf"^{constant}\s*=\s*(.*?)\)\n", source, re.S | re.M)
        if not match:
            return []
        found = re.findall(r'"(kiteplayer[a-z0-9-]*)"', match.group(1))
        base = re.match(r"\s*(\w+)\s*\+", match.group(1))
        return (names(base.group(1)) if base else []) + found

    return {"js": names("JS_MODULES"), "wasmJs": names("WASM_NODE_MODULES"),
            "wasmJsBrowser": names("WASM_BROWSER_MODULES")}


def self_test():
    """The reading of ci.yml checked on blocks whose answer is known (#730). Returns the failures."""
    runner = 'JS_MODULES = (\n    "kiteplayer-a",\n)\nWASM_NODE_MODULES = (\n    "kiteplayer-b",\n)\n' \
             'WASM_BROWSER_MODULES = WASM_NODE_MODULES + ("kiteplayer-c",)\n'
    cases = [
        ("a comment naming another module covers nothing",
         "      - run: |\n          # kiteplayer-x is left out here\n          ./kotlin test -p jvm -m kiteplayer-a\n",
         {("kiteplayer-a", "jvm")}),
        ("each command pairs only its own flags",
         "      - run: |\n          ./kotlin test -p jvm -m kiteplayer-a\n          ./kotlin test -p js -m kiteplayer-b\n",
         {("kiteplayer-a", "jvm"), ("kiteplayer-b", "js")}),
        ("a continued command and a loop",
         "      - run: |\n          for module in kiteplayer-a kiteplayer-b; do\n"
         "            ./kotlin test -m \"$module\" \\\n              -p jvm\n          done\n",
         {("kiteplayer-a", "jvm"), ("kiteplayer-b", "jvm")}),
        ("a folded Windows command",
         "      - run: >\n          kotlin.bat test -p mingwX64\n          -m kiteplayer-a\n",
         {("kiteplayer-a", "mingwX64")}),
        ("a link task is not a test run",
         "      - run: |\n          ./kotlin task :kiteplayer-a:linkLinuxArm64TestDebug -p linuxArm64 -m kiteplayer-a\n",
         set()),
        ("the web runner credits only the platforms it is called with",
         "      - run: python3 scripts/web-tests/run.py --platform wasmJsBrowser\n",
         {("kiteplayer-b", "wasmJs"), ("kiteplayer-c", "wasmJs")}),
        ("the web runner is credited only when a block calls it",
         "      - run: |\n          echo the web runner lists kiteplayer-a\n",
         set()),
        ("a binary run from a loop",
         "      - run: |\n          for module in kiteplayer-a; do\n"
         "            binary=\"$GITHUB_WORKSPACE/linux-arm64-tests/$module.kexe\"\n"
         "            (cd \"$module\" && \"$binary\") 2>&1 | tee log\n          done\n",
         {("kiteplayer-a", "linuxArm64")}),
    ]
    failures = []
    for name, text, expected in cases:
        got = covered_by_ci(text, runner)
        if got != expected:
            failures.append(f"self-test: {name}: read {sorted(got)}, expected {sorted(expected)}")
    return failures


def declared_platforms(module_dir):
    """The platforms a module's module.yaml declares under `product.platforms`."""
    text = (module_dir / "module.yaml").read_text()
    match = re.search(r"^\s+platforms:\s*\n((?:\s+-\s+\w+\s*\n)+)", text, re.M)
    return set(re.findall(r"-\s+(\w+)", match.group(1))) if match else set()


def main() -> int:
    failures = self_test()
    for failure in failures:
        print(failure)
    if failures:
        return 1
    covered = covered_by_ci()
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
