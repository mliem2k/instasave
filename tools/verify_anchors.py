#!/usr/bin/env python3
"""Check every fingerprint anchor in this bundle against a real Instagram APK.

Why this exists
---------------
Patches in this bundle never name an obfuscated class, method or field. They locate their
target by string literals, unobfuscated type names, and hardcoded MobileConfig parameter ids,
because those are the parts an Android obfuscator does not rewrite. That is what makes a new
Instagram release normally a rebuild rather than an edit.

"Normally" is doing work in that sentence. When Instagram restructures a screen, an anchor does
move, and the failure surfaces as a fingerprint that does not resolve. This script answers the
question that follows, which is not "did it break" but "what is it now": for every anchor it
reports whether it is present, and for each hit it prints the enclosing method signature, which
is exactly the information needed to repair a Fingerprint declaration.

Run it before every build against a new Instagram version. That is the weekly rebase loop.

Anchors are read straight out of Fingerprints.kt, plus the MobileConfig parameter ids in
MobileConfigOverrides.java, rather than duplicated here, so this script cannot drift away from
the patches it is checking. Those ids are the one genuinely version specific part of the bundle
and both of them moved between Instagram 436 and 440, which is precisely the drift this catches.

Usage
-----
    tools/verify_anchors.py path/to/instagram.apk
    tools/verify_anchors.py --smali path/to/already/decompiled
    tools/verify_anchors.py instagram.apk --json

Requires apktool on PATH when given an APK. Decompiled output is cached under tools/.work/
and is gitignored, since neither an Instagram APK nor its decompiled form is redistributable.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
FINGERPRINTS_KT = REPO_ROOT / "patches/src/main/kotlin/app/mliem/patches/instagram/Fingerprints.kt"
# The MobileConfig parameter ids are the one part of this bundle that is genuinely version
# specific, so they are checked as anchors too even though they live in extension code rather
# than in a fingerprint. Instagram moved both between 436 and 440; this is what catches that.
MOBILE_CONFIG_JAVA = (
    REPO_ROOT
    / "extensions/instasave/src/main/java/app/mliem/extension/instasave/MobileConfigOverrides.java"
)
WORK_DIR = REPO_ROOT / "tools/.work"

OK = "OK"
MISSING = "MISSING"
AMBIGUOUS = "AMBIGUOUS"


@dataclass
class Anchor:
    kind: str  # "string", "literal", or "type"
    value: str
    status: str = MISSING
    hits: list[str] = field(default_factory=list)


@dataclass
class FingerprintSpec:
    name: str
    anchors: list[Anchor] = field(default_factory=list)

    @property
    def status(self) -> str:
        if not self.anchors:
            return OK
        if any(a.status == MISSING for a in self.anchors):
            return MISSING
        if any(a.status == AMBIGUOUS for a in self.anchors):
            return AMBIGUOUS
        return OK


# region parsing Fingerprints.kt

def _split_objects(source: str) -> list[tuple[str, str]]:
    """Yields (objectName, bodyText) for each `object X : Fingerprint(...)` declaration."""
    objects = []
    for match in re.finditer(r"object\s+(\w+)\s*:\s*Fingerprint\s*\(", source):
        name = match.group(1)
        start = match.end()  # just past the opening paren
        depth = 1
        index = start
        while index < len(source) and depth > 0:
            char = source[index]
            if char == '"':  # skip string literals so parens inside them do not count
                index += 1
                while index < len(source) and source[index] != '"':
                    index += 2 if source[index] == "\\" else 1
            elif char == "(":
                depth += 1
            elif char == ")":
                depth -= 1
            index += 1
        objects.append((name, source[start:index - 1]))
    return objects


def _resolve_constants(source: str) -> dict[str, str]:
    """Kotlin `const val NAME = "value"` declarations, so anchors can reference them."""
    return {
        m.group(1): m.group(2).replace("\\$", "$")
        for m in re.finditer(r'const\s+val\s+(\w+)\s*=\s*"((?:[^"\\]|\\.)*)"', source)
    }


def parse_fingerprints(path: Path) -> list[FingerprintSpec]:
    source = path.read_text(encoding="utf-8")
    constants = _resolve_constants(source)
    specs: list[FingerprintSpec] = []

    for name, body in _split_objects(source):
        spec = FingerprintSpec(name=name)
        seen: set[tuple[str, str]] = set()

        def add(kind: str, value: str) -> None:
            if value and (kind, value) not in seen:
                seen.add((kind, value))
                spec.anchors.append(Anchor(kind=kind, value=value))

        # strings = listOf("a", "b")
        for block in re.finditer(r"strings\s*=\s*listOf\s*\(([^)]*)\)", body, re.S):
            for literal in re.finditer(r'"((?:[^"\\]|\\.)*)"', block.group(1)):
                add("string", literal.group(1).replace('\\"', '"'))

        # literal(1234L)
        for match in re.finditer(r"literal\s*\(\s*(-?\d+)L?\s*\)", body):
            add("literal", match.group(1))

        # definingClass = "/Foo;" and parameters = listOf("/Bar;", "Z")
        for match in re.finditer(r'definingClass\s*=\s*"([^"]+)"', body):
            add("type", match.group(1).replace("\\$", "$"))
        for block in re.finditer(r"parameters\s*=\s*listOf\s*\(([^)]*)\)", body, re.S):
            for literal in re.finditer(r'"([^"]*)"', block.group(1)):
                value = literal.group(1).replace("\\$", "$")
                # "L" and "Z" are wildcards and primitives, not anchors.
                if value.endswith(";") and len(value) > 2:
                    add("type", value)

        # Bare identifiers used as a type, e.g. parameters = listOf(MEDIA_OPTION_ENUM)
        for match in re.finditer(r"parameters\s*=\s*listOf\s*\(\s*(\w+)\s*\)", body):
            if match.group(1) in constants:
                add("type", constants[match.group(1)])

        # Enum constants referenced from a custom block, e.g. readsOption("REPORT").
        # These are static field names, which appear in smali as `->REPORT:`, not as
        # string literals, so they need their own needle shape.
        for match in re.finditer(r'readsOption\s*\(\s*"(\w+)"\s*\)', body):
            add("field", match.group(1))

        specs.append(spec)

    specs.extend(parse_mobile_config_ids())
    return specs


def parse_mobile_config_ids() -> list[FingerprintSpec]:
    """Reads the hardcoded MobileConfig parameter ids out of the extension.

    These are not fingerprints, but they are anchors in every sense that matters: they are
    matched against the app, they are the part most likely to move between releases, and a
    silently wrong one produces a patch that applies cleanly and does nothing.
    """
    if not MOBILE_CONFIG_JAVA.exists():
        return []

    spec = FingerprintSpec(name=f"{MOBILE_CONFIG_JAVA.stem} (extension constants)")
    for match in re.finditer(r"long\s+(\w+)\s*=\s*(0[xX][0-9a-fA-F]+)L?\s*;", MOBILE_CONFIG_JAVA.read_text()):
        spec.anchors.append(Anchor(kind="literal", value=str(int(match.group(2), 16))))
    return [spec] if spec.anchors else []

# endregion


# region decompiling

def decompile(apk: Path, force: bool) -> Path:
    out = WORK_DIR / apk.stem
    smali_dirs = sorted(out.glob("smali*")) if out.exists() else []

    if smali_dirs and not force:
        print(f"reusing cached decompile at {out} (pass --force to redo)", file=sys.stderr)
        return out

    if shutil.which("apktool") is None:
        sys.exit("apktool is not on PATH. Install it, or pass --smali with a decompiled tree.")

    if out.exists():
        shutil.rmtree(out)
    WORK_DIR.mkdir(parents=True, exist_ok=True)

    print(f"decompiling {apk.name}, this takes a few minutes", file=sys.stderr)
    result = subprocess.run(
        ["apktool", "d", "-f", "--no-res", "-o", str(out), str(apk)],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    if result.returncode != 0:
        sys.exit(f"apktool failed:\n{result.stdout}")
    return out

# endregion


# region searching

def smali_files(root: Path) -> list[Path]:
    files: list[Path] = []
    for directory in sorted(root.glob("smali*")):
        files.extend(directory.rglob("*.smali"))
    if not files:
        files = list(root.rglob("*.smali"))
    return files


def enclosing_method(lines: list[str], index: int) -> str:
    for cursor in range(index, -1, -1):
        line = lines[cursor].strip()
        if line.startswith(".method"):
            return line
    return "<class initializer or field>"


def class_of(lines: list[str]) -> str:
    for line in lines[:10]:
        if line.startswith(".class"):
            return line.strip().split()[-1]
    return "<unknown>"


def search(root: Path, specs: list[FingerprintSpec]) -> None:
    # Build the set of needles once, then make a single pass over the smali tree. Instagram
    # decompiles to well over a hundred thousand files, so one pass matters.
    string_needles: dict[str, list[Anchor]] = {}
    literal_needles: dict[str, list[Anchor]] = {}
    type_needles: dict[str, list[Anchor]] = {}

    for spec in specs:
        for anchor in spec.anchors:
            if anchor.kind == "string":
                string_needles.setdefault(f'"{anchor.value}"', []).append(anchor)
            elif anchor.kind == "field":
                # A static field read renders as `Lsome/Class;->NAME:Lsome/Type;`
                string_needles.setdefault(f"->{anchor.value}:", []).append(anchor)
            elif anchor.kind == "literal":
                # smali renders wide constants in hex, e.g. const-wide v0, 0x81035f00020d71L
                literal_needles.setdefault(f"0x{int(anchor.value):x}", []).append(anchor)
            else:
                type_needles.setdefault(anchor.value, []).append(anchor)

    files = smali_files(root)
    if not files:
        sys.exit(f"no .smali files found under {root}")
    print(f"scanning {len(files)} smali files", file=sys.stderr)

    for path in files:
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue

        interesting = [n for n in string_needles if n in text]
        interesting += [n for n in literal_needles if n in text]
        matched_types = [t for t in type_needles if t in text]

        if not interesting and not matched_types:
            continue

        lines = text.splitlines()
        class_name = class_of(lines)

        for needle in interesting:
            anchors = string_needles.get(needle) or literal_needles.get(needle) or []
            for line_index, line in enumerate(lines):
                if needle in line:
                    location = f"{class_name}->{enclosing_method(lines, line_index)}"
                    for anchor in anchors:
                        if location not in anchor.hits:
                            anchor.hits.append(location)
                    break

        for type_name in matched_types:
            # A type anchor only needs to exist somewhere in the app.
            for anchor in type_needles[type_name]:
                if not anchor.hits:
                    anchor.hits.append(f"referenced from {class_name}")

    for spec in specs:
        for anchor in spec.anchors:
            if not anchor.hits:
                anchor.status = MISSING
            elif anchor.kind == "string" and len(anchor.hits) > 6:
                anchor.status = AMBIGUOUS
            else:
                anchor.status = OK

# endregion


def report(specs: list[FingerprintSpec], as_json: bool) -> int:
    if as_json:
        print(json.dumps(
            [
                {
                    "fingerprint": spec.name,
                    "status": spec.status,
                    "anchors": [
                        {"kind": a.kind, "value": a.value, "status": a.status, "hits": a.hits}
                        for a in spec.anchors
                    ],
                }
                for spec in specs
            ],
            indent=2,
        ))
        return 0 if all(s.status != MISSING for s in specs) else 1

    failed = 0
    for spec in specs:
        marker = {OK: "ok", AMBIGUOUS: "??", MISSING: "XX"}[spec.status]
        print(f"\n[{marker}] {spec.name}")
        if not spec.anchors:
            print("       no textual anchors; matched by shape alone, verify by building")
        for anchor in spec.anchors:
            print(f"       {anchor.status:<9} {anchor.kind:<7} {anchor.value}")
            for hit in anchor.hits[:4]:
                print(f"                            {hit}")
            if len(anchor.hits) > 4:
                print(f"                            ... and {len(anchor.hits) - 4} more")
        if spec.status == MISSING:
            failed += 1

    total = len(specs)
    print(f"\n{total - failed}/{total} fingerprints have all anchors present.")
    if failed:
        print(
            "Anchors reported MISSING moved in this build. The printed method signatures for the\n"
            "surviving anchors of the same fingerprint are the starting point for repairing it."
        )
    return 1 if failed else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("apk", nargs="?", help="Instagram APK to check against")
    parser.add_argument("--smali", help="use an already decompiled tree instead of running apktool")
    parser.add_argument("--force", action="store_true", help="re-decompile even if a cache exists")
    parser.add_argument("--json", action="store_true", help="emit machine readable output")
    args = parser.parse_args()

    if not FINGERPRINTS_KT.exists():
        sys.exit(f"cannot find {FINGERPRINTS_KT}")

    specs = parse_fingerprints(FINGERPRINTS_KT)
    if not specs:
        sys.exit("no fingerprints parsed; has Fingerprints.kt moved or changed shape?")

    if args.smali:
        root = Path(args.smali).expanduser().resolve()
        if not root.is_dir():
            sys.exit(f"{root} is not a directory")
    elif args.apk:
        apk = Path(args.apk).expanduser().resolve()
        if not apk.is_file():
            sys.exit(f"{apk} is not a file")
        root = decompile(apk, args.force)
    else:
        parser.error("pass an APK path, or --smali with a decompiled tree")
        return 2

    search(root, specs)
    return report(specs, args.json)


if __name__ == "__main__":
    sys.exit(main())
