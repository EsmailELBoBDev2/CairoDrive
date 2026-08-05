#!/usr/bin/env python3
"""Catch the two compile errors cd-typecheck.py is structurally blind to.

cd-typecheck resolves capitalised names in type positions. That means it happily accepts:

  * ``R.string.shared_string_call`` - it sees ``R``, which resolves, and stops. The field
    behind it is generated from ``res/`` at build time and does not exist in any source file,
    so nothing in the tree contradicts a name that was simply invented.
  * ``import net.osmand.core.jni.MapRendererView;`` - the import makes the name resolve by
    definition. Whether that package actually contains that class is a question about a jar
    the checker never opens.

Both shipped to CI on 2026-08-05 and both failed the build. This closes them.

Check 1 - R references, exact
    Every ``R.<type>.<name>`` in the sources must exist in some ``res/`` directory. Resource
    names are fully decidable from the tree, so a miss here is a genuine error, not a guess.

Check 2 - imports, by corroboration
    An import cannot be verified against the source tree: MapRendererView really does live in
    an AAR fetched at build time, so "not a file here" proves nothing. What IS evidence is
    disagreement WITHIN the tree - when one file imports ``a.b.jni.Foo`` and thirty others
    import ``a.b.android.Foo``, the one is almost certainly wrong. Reported as a warning, and
    only when the minority package is used by no other file, because a codebase is allowed to
    contain two unrelated classes with the same simple name.

Usage: cd-refcheck.py [file.java ...]     (no arguments: every .java and .kt under the modules)
"""

import os
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

resources_submodule_present = False

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

SOURCE_DIRS = [
    "OsmAnd/src",
    "OsmAnd-java/src/main/java",
    "OsmAnd-api/src",
    "OsmAndCore-android/src",
]

RES_DIRS = [
    "OsmAnd/res",
    "OsmAnd-api/res",
    "plugins/Osmand-SRTMPlugin/res",
    "plugins/Osmand-Skimaps/res",
    "plugins/Osmand-Nautical/res",
]

# res/values tag name -> R type. Anything not listed is skipped rather than guessed at.
VALUE_TAGS = {
    "string": "string",
    "string-array": "array",
    "integer-array": "array",
    "array": "array",
    "plurals": "plurals",
    "color": "color",
    "dimen": "dimen",
    "style": "style",
    "bool": "bool",
    "integer": "integer",
    "fraction": "fraction",
    "attr": "attr",
    "declare-styleable": "styleable",
}

# res/<dir>/ -> R type, for resources that are whole files.
FILE_DIRS = {
    "drawable": "drawable",
    "layout": "layout",
    "menu": "menu",
    "anim": "anim",
    "animator": "animator",
    "raw": "raw",
    "xml": "xml",
    "mipmap": "mipmap",
    "font": "font",
    "transition": "transition",
    "navigation": "navigation",
    "interpolator": "interpolator",
}

# Only these are checked. `id` is deliberately absent: ids are minted by @+id anywhere in any
# layout and by view binding, so the false-positive rate would drown the real findings.
CHECKED_TYPES = {"string", "drawable", "color", "dimen", "layout", "menu", "raw", "plurals",
                 "array", "bool", "integer", "style", "anim", "mipmap"}

# Types the un-checked-out `resources` submodule contributes to. Skipped entirely when it is
# absent - see the comment at the skip site. `string` and `layout` are NOT here: those live in
# OsmAnd/res and are always fully present, which is what makes a miss on them trustworthy.
SUBMODULE_TYPES = {"drawable", "color", "dimen", "style", "integer", "mipmap", "anim"}

# (?<![.\w]) so `com.google.android.material.R.dimen.x` and `android.R.id.y` are NOT matched:
# those resolve against a different R class entirely and are not ours to verify.
R_REF = re.compile(r"(?<![.\w])R\s*\.\s*(\w+)\s*\.\s*(\w+)")
# Non-static only. A static import's last segment is a MEMBER, not a class, so the same-simple-
# name evidence does not apply: `Typeface.DEFAULT` and `CacheSizeViewHolderState.DEFAULT` are
# unrelated constants that happen to share a name, and comparing them produces pure noise.
IMPORT = re.compile(r"^\s*import\s+([\w.]+)\s*;?\s*$", re.M)


def same_shape(a, b):
    """True when two packages differ in exactly ONE segment and have the same depth.

    That is the shape of the mistake worth reporting - `net.osmand.core.jni` typed where
    `net.osmand.core.android` was meant. Two packages that share no structure, like
    `org.mozilla.javascript` and `android.content`, are two different Context classes and the
    file that imports the rarer one is not thereby wrong.
    """
    left = a.split(".")
    right = b.split(".")
    if len(left) != len(right):
        return False
    return sum(1 for x, y in zip(left, right) if x != y) == 1


def collect_resources():
    """Every resource name defined anywhere in the tree, as {type: {names}}."""
    found = defaultdict(set)
    for res in RES_DIRS:
        base = os.path.join(ROOT, res)
        if not os.path.isdir(base):
            continue
        for entry in sorted(os.listdir(base)):
            path = os.path.join(base, entry)
            if not os.path.isdir(path):
                continue
            kind = entry.split("-", 1)[0]
            if kind == "values":
                for name in sorted(os.listdir(path)):
                    if name.endswith(".xml"):
                        _collect_values(os.path.join(path, name), found)
            elif kind in FILE_DIRS:
                rtype = FILE_DIRS[kind]
                for name in sorted(os.listdir(path)):
                    found[rtype].add(name.split(".")[0])
    _collect_res_values(found)
    return found


# `resValue "string", "app_edition", ...` in a build script mints a real resource that exists in
# no res/ directory at all. Without this, three legitimate upstream call sites read as errors.
RES_VALUE = re.compile(r"""resValue\s+["'](\w+)["']\s*,\s*["']([\w.]+)["']""")


def _collect_res_values(found):
    for name in ("OsmAnd/build.gradle", "OsmAnd/cairodrive.gradle", "build.gradle"):
        path = os.path.join(ROOT, name)
        if not os.path.isfile(path):
            continue
        with open(path, encoding="utf-8", errors="replace") as handle:
            for rtype, rname in RES_VALUE.findall(handle.read()):
                found[rtype].add(rname)
                found[rtype].add(rname.replace(".", "_"))


def _collect_values(path, found):
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as exc:
        print("  ! %s is not well-formed XML: %s" % (os.path.relpath(path, ROOT), exc))
        return
    for child in root:
        name = child.get("name")
        if not name:
            continue
        tag = child.tag
        if tag == "item":
            # <item type="id" name="foo"/> - the type is an attribute, not the tag.
            rtype = child.get("type")
            if rtype:
                found[rtype].add(name)
            continue
        rtype = VALUE_TAGS.get(tag)
        if rtype:
            # aapt turns every '.' in a resource name into '_' in R, so the style declared as
            # `Animations.PopUpMenu.Fade` is referenced as R.style.Animations_PopUpMenu_Fade.
            # Recording the raw name only would report every dotted style as missing.
            found[rtype].add(name)
            found[rtype].add(name.replace(".", "_"))
            if rtype == "string":
                # An android:name'd string is still reachable as a plain string; nothing else
                # to record, but keep the branch so the intent is explicit rather than implied.
                pass


def source_files(args):
    if args:
        return [os.path.abspath(a) for a in args]
    files = []
    for src in SOURCE_DIRS:
        base = os.path.join(ROOT, src)
        for dirpath, _dirnames, filenames in os.walk(base):
            for name in filenames:
                if name.endswith(".java") or name.endswith(".kt"):
                    files.append(os.path.join(dirpath, name))
    return sorted(files)


def strip_comments_and_strings(text):
    """Blank out comments and literals, PRESERVING line count and length.

    The same discipline cd-typecheck learned the hard way: strip in one pass with a state
    machine, comments FIRST. Handling literals first makes the apostrophe in a comment like
    "OsmAnd's" open a char literal that swallows the code after it.
    """
    out = []
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                out.append(" ")
                i += 1
        elif c == "/" and i + 1 < n and text[i + 1] == "*":
            while i < n and not (text[i] == "*" and i + 1 < n and text[i + 1] == "/"):
                out.append("\n" if text[i] == "\n" else " ")
                i += 1
            for _ in range(min(2, n - i)):
                out.append(" ")
                i += 1
        elif c in "\"'":
            quote = c
            out.append(" ")
            i += 1
            while i < n and text[i] != quote:
                if text[i] == "\\":
                    out.append(" ")
                    i += 1
                    if i < n:
                        out.append("\n" if text[i] == "\n" else " ")
                        i += 1
                    continue
                out.append("\n" if text[i] == "\n" else " ")
                i += 1
            if i < n:
                out.append(" ")
                i += 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("-")]
    files = source_files(args)
    resources = collect_resources()

    global resources_submodule_present
    resources_submodule_present = os.path.isdir(os.path.join(ROOT, "resources", "rendering_styles"))
    if not resources_submodule_present:
        print("note: the `resources` submodule is not checked out - %s references are not "
              "verifiable here and are skipped\n" % "/".join(sorted(SUBMODULE_TYPES)))

    # Import corroboration needs the WHOLE tree even when only a few files are being checked -
    # the evidence is what every other file does.
    import_pkgs = defaultdict(lambda: defaultdict(list))
    for path in source_files([]):
        try:
            with open(path, encoding="utf-8", errors="replace") as handle:
                text = handle.read()
        except OSError:
            continue
        for full in IMPORT.findall(text):
            if "." not in full:
                continue
            pkg, _, simple = full.rpartition(".")
            if simple and simple[0].isupper():
                import_pkgs[simple][pkg].append(path)

    bad_refs = 0
    suspect_imports = 0
    for path in files:
        try:
            with open(path, encoding="utf-8", errors="replace") as handle:
                raw = handle.read()
        except OSError as exc:
            print("  ! cannot read %s: %s" % (path, exc))
            continue
        rel = os.path.relpath(path, ROOT)

        # A control byte in a source file makes every text tool treat it as binary and SKIP it -
        # grep, and therefore the build-flag audit, which then reported three flags as "never
        # read" when their only reader was the unreadable file. That is a silent hole in the
        # tooling, not a style problem, so it is an error rather than a warning.
        control = [i for i, ch in enumerate(raw)
                   if ord(ch) < 9 or 13 < ord(ch) < 32]
        if control:
            line = raw.count("\n", 0, control[0]) + 1
            print("%s:%d: control byte 0x%02X in source - makes the file binary to grep"
                  % (rel, line, ord(raw[control[0]])))
            bad_refs += 1

        text = strip_comments_and_strings(raw)

        for match in R_REF.finditer(text):
            rtype, name = match.group(1), match.group(2)
            if rtype not in CHECKED_TYPES:
                continue
            # `R.string.class` is the class literal used for reflection over the R fields, not a
            # resource. WikipediaPlugin and AndroidUtils both do this legitimately.
            if name == "class":
                continue
            if rtype in SUBMODULE_TYPES and not resources_submodule_present:
                # The `resources` submodule holds the map icons (mx_*, mm_*) and a chunk of the
                # colours. It is not checked out here, so "not found" would be a statement about
                # this working copy rather than about the code. CI has it; a local run does not.
                continue
            if name in resources.get(rtype, ()):
                continue
            line = text.count("\n", 0, match.start()) + 1
            print("%s:%d: R.%s.%s does not exist in any res/ directory" % (rel, line, rtype, name))
            bad_refs += 1

        for full in IMPORT.findall(raw):
            pkg, _, simple = full.rpartition(".")
            if not simple or not simple[0].isupper():
                continue
            packages = import_pkgs.get(simple, {})
            if len(packages) < 2:
                continue
            mine = packages.get(pkg, [])
            if len(mine) > 1:
                continue
            # This file is the ONLY one importing that simple name from that package, while
            # other files agree on a different one. That is the MapRendererView shape exactly.
            rivals = [p for p in packages if p != pkg and same_shape(p, pkg)]
            if not rivals:
                continue
            rival = max(rivals, key=lambda p: len(packages[p]))
            if len(packages[rival]) < 3:
                continue
            print("%s: import %s - but %d other file(s) import %s.%s"
                  % (rel, full, len(packages[rival]), rival, simple))
            suspect_imports += 1

    print("\n%d missing R reference(s), %d suspect import(s)" % (bad_refs, suspect_imports))
    return 1 if bad_refs else 0


if __name__ == "__main__":
    sys.exit(main())
