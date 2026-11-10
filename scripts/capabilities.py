#!/usr/bin/env python3
"""Builds docs/capabilities.md from the code, and fails when the committed copy differs (#926).

The README's platform tables were written by hand and drifted from the module files. This reads
two things that are facts of the code:

  - the platforms each published module's module.yaml declares, and
  - the capability flags each video renderer overrides: appliesAdjustments, showsHdr and projects.

    python3 scripts/capabilities.py           # exits 1, naming the drift, when docs/capabilities.md is stale
    python3 scripts/capabilities.py --write   # writes docs/capabilities.md again
"""
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
DOC = ROOT / "docs/capabilities.md"

# The platform columns, each a name a reader knows and the targets it stands for.
COLUMNS = [
    ("Android", ["android"]),
    ("Desktop JVM", ["jvm"]),
    ("iOS", ["iosArm64", "iosSimulatorArm64"]),
    ("macOS", ["macosArm64"]),
    ("tvOS", ["tvosArm64", "tvosSimulatorArm64"]),
    ("watchOS", ["watchosArm32", "watchosArm64", "watchosDeviceArm64", "watchosSimulatorArm64"]),
    ("Linux", ["linuxX64", "linuxArm64"]),
    ("Windows", ["mingwX64"]),
    ("Android Native", ["androidNativeArm32", "androidNativeArm64", "androidNativeX64", "androidNativeX86"]),
    ("Web (wasmJs)", ["wasmJs"]),
    ("JS", ["js"]),
]

# The renderer folders, and the platform each one draws on.
RENDERER_SOURCES = ["kiteplayer-output", "kiteplayer-compose-video"]

# Each flag, its default in VideoRenderer, and how the table names an override.
FLAGS = [
    ("appliesAdjustments", "picture controls", True),
    ("showsHdr", "HDR on an HDR screen", False),
    ("projects", "360 degree video", False),
]


def published_modules():
    out = subprocess.run([str(ROOT / "scripts/published-modules.sh")], capture_output=True, text=True, check=True)
    return [line for line in out.stdout.split() if line]


def platforms_of(module):
    text = (ROOT / module / "module.yaml").read_text()
    inline = re.search(r"^\s+platforms:\s*\[([^\]]*)\]", text, re.M)
    if inline:
        return {p.strip() for p in inline.group(1).split(",") if p.strip()}
    listed = re.search(r"^\s+platforms:\s*\n((?:\s+-\s+\w+\s*\n)+)", text, re.M)
    return set(re.findall(r"-\s+(\w+)", listed.group(1))) if listed else set()


def cell(declared, targets):
    have = [t for t in targets if t in declared]
    if not have:
        return ""
    if len(have) == len(targets):
        return "✓"
    return ", ".join(have)


def renderers():
    """Every class that is a VideoRenderer, with its folder and flag overrides.

    A class's supertypes follow its constructor, which can span many lines, so each line that
    names `VideoRenderer` in a supertype clause (one that starts with `)` or holds `class`) is
    given to the nearest class above it.
    """
    found = []
    for module in RENDERER_SOURCES:
        for path in sorted((ROOT / module).glob("src*/**/*.kt")):
            text = path.read_text()
            lines = text.splitlines()
            last_class = None
            starts = []
            for number, line in enumerate(lines):
                declared = re.match(r"^(?:public |internal |private )?(?:open |abstract |data )?class (\w+)", line)
                if declared:
                    last_class = (declared.group(1), number)
                clause = line.lstrip().startswith(")") or declared
                if last_class and clause and re.search(r":\s*(?:[\w.<>, ()]*,\s*)?(?:[\w.]+\.)?VideoRenderer\b(?!Factory)", line):
                    if not starts or starts[-1][0] != last_class[0]:
                        starts.append(last_class)
            for index, (name, number) in enumerate(starts):
                # The class body runs to the next top-level class, object or function.
                rest = "\n".join(lines[number + 1:])
                end = re.search(r"^(?:public |internal |private )?(?:open |abstract |data )?(?:class|object|fun|interface) ", rest, re.M)
                body = "\n".join(lines[number:]) if not end else lines[number] + "\n" + rest[: end.start()]
                flags = {}
                for flag, _, default in FLAGS:
                    if flag == "projects":
                        flags[flag] = "yes" if re.search(r"override fun projects\(", body) else "no"
                        continue
                    over = re.search(rf"override val {flag}: Boolean(?:\s*get\(\))?\s*(?:=\s*(true|false)\b)?", body)
                    if not over:
                        flags[flag] = "yes" if default else "no"
                    elif over.group(1):
                        flags[flag] = "yes" if over.group(1) == "true" else "no"
                    else:
                        flags[flag] = "when the screen and the stream allow"
                folder = path.relative_to(ROOT / module).parts[0]
                platform = folder.removeprefix("src@") if folder != "src" else "common"
                found.append((name, module, platform, flags))
    return sorted(found, key=lambda r: (r[1], r[2], r[0]))


def build():
    lines = [
        "# Capabilities",
        "",
        "Built from the code by `scripts/capabilities.py`; do not edit by hand. CI fails when it",
        "differs from the module files and the renderers (#926). The README's platform section says",
        "what each platform means for an app, and links here for the full picture.",
        "",
        "## Modules and platforms",
        "",
        "A tick is every target of the platform; otherwise the cell names the targets the module builds for.",
        "",
        "| Module | " + " | ".join(name for name, _ in COLUMNS) + " |",
        "| --- | " + " | ".join(":---:" for _ in COLUMNS) + " |",
    ]
    for module in published_modules():
        declared = platforms_of(module)
        if not declared:
            continue
        lines.append(f"| `{module}` | " + " | ".join(cell(declared, targets) for _, targets in COLUMNS) + " |")
    lines += [
        "",
        "## Video renderers",
        "",
        "What each renderer says it does, which is what the engine reads to warn an app that a",
        "setting has no effect. `VideoRenderer` applies picture controls unless a renderer says",
        "otherwise, and shows no HDR and no 360 degree view unless it says it does.",
        "",
        "| Renderer | Module | Source folder | " + " | ".join(label[0].upper() + label[1:] for _, label, _ in FLAGS) + " |",
        "| --- | --- | --- | " + " | ".join("---" for _ in FLAGS) + " |",
    ]
    for name, module, platform, flags in renderers():
        lines.append(f"| `{name}` | `{module}` | `{platform}` | " + " | ".join(flags[f] for f, _, _ in FLAGS) + " |")
    lines.append("")
    return "\n".join(lines)


def main() -> int:
    fresh = build()
    if "--write" in sys.argv[1:]:
        DOC.write_text(fresh)
        print(f"wrote {DOC.relative_to(ROOT)}")
        return 0
    current = DOC.read_text() if DOC.is_file() else ""
    if current == fresh:
        print("docs/capabilities.md matches the code")
        return 0
    import difflib
    sys.stdout.writelines(difflib.unified_diff(current.splitlines(True), fresh.splitlines(True), "docs/capabilities.md", "from the code"))
    print("\ndocs/capabilities.md differs from the code. Run python3 scripts/capabilities.py --write (#926).")
    return 1


if __name__ == "__main__":
    sys.exit(main())
