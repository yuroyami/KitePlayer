#!/usr/bin/env python3
"""Generates Anime4kNetworks.kt from Anime4K's mpv shaders.

The animation upscaler (#67) ports two networks of Anime4K v3.2, Upscale CNN x2 S and M, by bloc97,
under the MIT licence. This reads the two mpv shader files of a checkout of
https://github.com/bloc97/Anime4K at the tag named below and writes their weights as Kotlin data.
The weights keep the decimal text of the original, so a shader generated from them carries exactly
the numbers the original does.

The parser is strict on purpose: every pass must have the shape the port understands, a 3x3 or 1x1
convolution saved under its own name and one depth-to-space pass at the end. Anything else stops the
script rather than producing a network that differs from the original.

    git clone --branch v4.0.1 --depth 1 https://github.com/bloc97/Anime4K /tmp/anime4k
    scripts/generate-anime4k-networks.py /tmp/anime4k
"""

import os
import re
import sys

TAG = "v4.0.1"
COMMIT = "4029bf701ecaa15f163cdc49cffe5501c1acf410"
NETWORKS = [
    ("small", "Anime4K_Upscale_CNN_x2_S.glsl", "S"),
    ("medium", "Anime4K_Upscale_CNN_x2_M.glsl", "M"),
]
OUTPUT = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..",
    "kiteplayer-output/src/androidAndAppleMain/kotlin/io/github/yuroyami/kiteplayer/output/Anime4kNetworks.kt",
)
WHEN = "//!WHEN OUTPUT.w MAIN.w / 1.200 > OUTPUT.h MAIN.h / 1.200 > *"
DEPTH_TO_SPACE_BODY = """vec4 hook() {
    vec2 f0 = fract(conv2d_last_tf_pos * conv2d_last_tf_size);
    ivec2 i0 = ivec2(f0 * vec2(2.0));
    float c0 = conv2d_last_tf_tex((vec2(0.5) - f0) * conv2d_last_tf_pt + conv2d_last_tf_pos)[i0.y * 2 + i0.x];
    float c1 = c0;
    float c2 = c1;
    float c3 = c2;
    return vec4(c0, c1, c2, c3) + MAIN_tex(MAIN_pos);
}"""
NUMBER = r"-?[0-9]+(?:\.[0-9]*)?(?:e[-+]?[0-9]+)?"


def fail(message):
    sys.exit(f"generate-anime4k-networks: {message}")


def numbers(text, count, where):
    values = [v.strip() for v in text.split(",")]
    if len(values) != count or not all(re.fullmatch(NUMBER, v) for v in values):
        fail(f"{where}: expected {count} numbers, got {text!r}")
    return values


def parse_network(path):
    text = open(path, encoding="utf-8").read()
    passes = text.split("//!DESC ")[1:]
    if not passes:
        fail(f"{path}: no passes")
    saved = {}
    layers = []
    for index, body in enumerate(passes):
        head, _, code = body.partition("\n")
        directives = re.findall(r"^//!(\w+) ?(.*)$", code, re.M)
        names = dict()
        binds = []
        for key, value in directives:
            if key == "BIND":
                binds.append(value)
            else:
                names[key] = value
        if names.get("HOOK") != "MAIN":
            fail(f"{head}: hooks {names.get('HOOK')}, not MAIN")
        if f"\n{WHEN}\n" not in f"\n{code}":
            fail(f"{head}: its WHEN condition is not the 1.2x rule")
        last = index == len(passes) - 1
        if last:
            if not head.endswith("Depth-to-Space") or binds != ["MAIN", "conv2d_last_tf"]:
                fail(f"{head}: the last pass is not the depth-to-space the port implements")
            if names.get("SAVE") != "MAIN" or DEPTH_TO_SPACE_BODY not in code:
                fail(f"{head}: the depth-to-space pass differs from the one the port implements")
            if saved.get("conv2d_last_tf") != len(layers) - 1:
                fail(f"{head}: conv2d_last_tf is not the last convolution")
            break
        if names.get("COMPONENTS") != "4":
            fail(f"{head}: a pass that does not write four components")
        source_size = "MAIN" if binds == ["MAIN"] else binds[0]
        if names.get("WIDTH") != f"{source_size}.w" or names.get("HEIGHT") != f"{source_size}.h":
            fail(f"{head}: a convolution that does not keep the source's size")
        groups = {}
        for match in re.finditer(r"^#define (go?_\d+)(\(x_off, y_off\))? \((.*)\)$", code, re.M):
            name, offset, expression = match.groups()
            plain = re.fullmatch(r"MAIN_texOff\(vec2\(x_off, y_off\)\)", expression)
            if plain:
                groups[name] = ("source", "Raw", True)
                continue
            relu = re.fullmatch(r"max\((-?)\((\w+)_tex(Off\(vec2\(x_off, y_off\)\)|\(\2_pos\))\), 0\.0\)", expression)
            if not relu:
                fail(f"{head}: an input it cannot read: {expression}")
            sign, texture, sampled = relu.groups()
            if texture not in saved or texture not in binds:
                fail(f"{head}: reads {texture}, which no earlier pass saved or this pass binds")
            if (offset is not None) != sampled.startswith("Off"):
                fail(f"{head}: an input whose offset does not match its macro: {expression}")
            groups[name] = (saved[texture], "Negative" if sign else "Positive", offset is not None)
        terms = []
        bias = None
        statements = re.findall(r"^    (.*);$", code, re.M)
        if not statements or statements[-1] != "return result":
            fail(f"{head}: does not end by returning its sum")
        for statement in statements[:-1]:
            term = re.fullmatch(
                r"(vec4 result =|result \+=) mat4\((.*)\) \* (go?_\d+)(?:\((" + NUMBER + r"), (" + NUMBER + r")\))?",
                statement,
            )
            if term:
                first, matrix, group, dx, dy = term.groups()
                if (first == "vec4 result =") != (not terms):
                    fail(f"{head}: the sum does not start at its first term")
                if group not in groups:
                    fail(f"{head}: an undefined input {group}")
                source, part, has_offset = groups[group]
                if has_offset != (dx is not None):
                    fail(f"{head}: {group} used with the wrong arguments")
                dx = 0 if dx is None else int(float(dx))
                dy = 0 if dy is None else int(float(dy))
                if abs(dx) > 1 or abs(dy) > 1:
                    fail(f"{head}: a tap outside 3x3")
                terms.append((source, part, dx, dy, numbers(matrix, 16, head)))
                continue
            offset = re.fullmatch(r"result \+= vec4\((.*)\)", statement)
            if offset and bias is None and terms:
                bias = numbers(offset.group(1), 4, head)
                continue
            fail(f"{head}: a statement the port does not know: {statement}")
        if bias is None:
            fail(f"{head}: no bias")
        if statements[-2].startswith("result += mat4"):
            fail(f"{head}: the bias is not the last term of the sum")
        layers.append((head, terms, bias))
        saved[names["SAVE"]] = len(layers) - 1
    return layers


def kotlin(networks):
    licence = open(os.path.join(sys.argv[1], "LICENSE"), encoding="utf-8").read().strip()
    out = []
    out.append("// Generated by scripts/generate-anime4k-networks.py. Do not edit by hand.")
    out.append("//")
    out.append(f"// From Anime4K {TAG} ({COMMIT}), https://github.com/bloc97/Anime4K,")
    out.append("// glsl/Upscale/Anime4K_Upscale_CNN_x2_S.glsl and Anime4K_Upscale_CNN_x2_M.glsl,")
    out.append("// whose headers read Copyright (c) 2019-2021 bloc97. Its licence:")
    out.append("//")
    for line in licence.splitlines():
        out.append(("// " + line).rstrip())
    out.append("")
    out.append("package io.github.yuroyami.kiteplayer.output")
    out.append("")
    out.append("import io.github.yuroyami.kiteplayer.output.Anime4kPart.Negative")
    out.append("import io.github.yuroyami.kiteplayer.output.Anime4kPart.Positive")
    out.append("import io.github.yuroyami.kiteplayer.output.Anime4kPart.Raw")
    out.append("")
    out.append("/** The weights of the two curated Anime4K v3.2 networks, as the original writes them. */")
    out.append("internal object Anime4kNetworks {")
    for index, (name, filename, tier) in enumerate(networks):
        layers = parse_network(os.path.join(sys.argv[1], "glsl/Upscale", filename))
        if index:
            out.append("")
        out.append(f"    /** Anime4K v3.2 Upscale CNN x2 ({tier}). */")
        out.append(f"    val {name}: Anime4kNetwork by lazy {{")
        out.append(f"        Anime4kNetwork(")
        out.append(f"            \"Anime4K-v3.2-Upscale-CNN-x2-({tier})\",")
        out.append(f"            listOf(")
        for number, (head, _, _) in enumerate(layers):
            out.append(f"                {name}Layer{number}(),")
        out.append(f"            ),")
        out.append(f"        )")
        out.append(f"    }}")
        for number, (head, terms, bias) in enumerate(layers):
            out.append("")
            out.append(f"    /** {head} */")
            out.append(f"    private fun {name}Layer{number}() = Anime4kLayer(")
            out.append("        listOf(")
            for source, part, dx, dy, matrix in terms:
                source = "Anime4kTerm.SOURCE" if source == "source" else str(source)
                out.append("            Anime4kTerm(")
                out.append(f"                {source}, {part}, {dx}, {dy},")
                # One line per column of the mat4, which is how GLSL reads the sixteen values.
                columns = [", ".join(matrix[n:n + 4]) for n in range(0, 16, 4)]
                out.append(f"                \"{columns[0]}, \" +")
                out.append(f"                    \"{columns[1]}, \" +")
                out.append(f"                    \"{columns[2]}, \" +")
                out.append(f"                    \"{columns[3]}\",")
                out.append("            ),")
            out.append("        ),")
            out.append(f"        \"{', '.join(bias)}\",")
            out.append("    )")
    out.append("}")
    return "\n".join(out) + "\n"


def main():
    if len(sys.argv) != 2:
        fail("usage: generate-anime4k-networks.py <Anime4K checkout at " + TAG + ">")
    text = kotlin(NETWORKS)
    with open(OUTPUT, "w", encoding="utf-8") as handle:
        handle.write(text)
    print(f"wrote {os.path.normpath(OUTPUT)}")


if __name__ == "__main__":
    main()
