#!/usr/bin/env python3
"""
Fails the build if code that runs BEFORE OsmandSettings exists tries to read a preference.

WHY THIS EXISTS
    OsmandApplication.onCreate does its work in a fixed order, and one line in the middle
    of it divides the file into two worlds:

        CairoDriveLogger.getInstance().init(this);      <- ~line 256
        ...
        settings = appCustomization.getOsmandSettings();  <- ~line 290

    Above that line getSettings() returns null and logs "Trying to access settings before
    they were created". A preference read there does not degrade a feature - it throws a
    NullPointerException out of application creation, which Android treats as a process
    that cannot start. The user sees "CairoDrive keeps stopping" and no map, ever.

    This has now happened TWICE, to two different authors of this fork:

      2026-08-07  CairoDriveProviders.install ran up beside the logger init. Every
                  provider's isAvailable() reads a preference, so TomTom traffic, weather
                  hazards and sun glare threw into arbitrate's catch and were recorded
                  unavailable on every cold start this fork had ever had. Silent: three
                  features simply never ran, and the log said "treating as unavailable".

      2026-08-09  CairoDriveLogger.logSessionHeader added
                  `offlinePriorityAtStart=` + settings.OFFLINE_ROUTE_PRIORITY.get()`.
                  Not silent: a crash loop on launch. The signed build was unusable and
                  the drive it was built for did not happen.

    The first cost a feature nobody knew was missing. The second cost a trip. A comment
    saying "do not do this" was already present in the tree when the second one was
    written, which is the whole argument for this file: the rule needs to be executable.

WHAT IT CHECKS
    Classes listed in EARLY_INIT below run before settings exist. None of them may call
    getSettings(), and none may name a known preference field. Both spellings are checked
    because `app.getSettings().FOO.get()` and a passed-in `settings.FOO.get()` fail
    identically at runtime and look different in source.

    Comments and string literals are stripped first, so a line explaining the rule does
    not trip it - the 2026-08-09 fix left several such comments deliberately.

WHAT IT DOES NOT CHECK
    Whether a NEW class was added to onCreate above the settings line. That is not
    statically knowable from this file alone; keep EARLY_INIT current when moving init
    calls around. The failure it does catch is the one that has actually happened twice.

USAGE
    python3 .github/check_early_init.py <repo-root>
"""

import os
import re
import sys

# Files whose code runs before `settings = appCustomization.getOsmandSettings()`.
EARLY_INIT = [
    "OsmAnd/src/net/osmand/plus/cairodrive/CairoDriveLogger.java",
]

# Reading a preference has two shapes and both crash identically before settings exist.
FORBIDDEN = [
    (re.compile(r"\bgetSettings\s*\(\s*\)"), "getSettings()"),
    (re.compile(r"\bsettings\s*\.\s*[A-Z][A-Z0-9_]{2,}\b"), "settings.SOME_PREFERENCE"),
]

# The one method that is allowed to take settings as a parameter, because it is called
# from onCreate AFTER they exist and is the sanctioned place for preference logging.
ALLOWED_METHOD = "logRuntimePreferences"


def strip_noise(src):
    """Remove block comments, line comments and string literals, preserving line count."""
    out = []
    i = 0
    n = len(src)
    while i < n:
        two = src[i:i + 2]
        if two == "/*":
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append("".join(c if c == "\n" else " " for c in src[i:j]))
            i = j
        elif two == "//":
            j = src.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
        elif src[i] == '"':
            j = i + 1
            while j < n and src[j] != '"':
                j += 2 if src[j] == "\\" else 1
            j = min(j + 1, n)
            out.append("".join(c if c == "\n" else " " for c in src[i:j]))
            i = j
        else:
            out.append(src[i])
            i += 1
    return "".join(out)


def method_bounds(src, name):
    """Line range of a method, by brace depth. Crude but adequate for one known method."""
    m = re.search(r"\b" + re.escape(name) + r"\s*\(", src)
    if not m:
        return None
    start = src.rfind("\n", 0, m.start()) + 1
    depth = 0
    i = src.index("{", m.end())
    for j in range(i, len(src)):
        if src[j] == "{":
            depth += 1
        elif src[j] == "}":
            depth -= 1
            if depth == 0:
                return (src[:start].count("\n") + 1, src[:j].count("\n") + 1)
    return None


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    failures = []
    for rel in EARLY_INIT:
        path = os.path.join(root, rel)
        if not os.path.isfile(path):
            print("::error::check_early_init: missing file %s - update EARLY_INIT." % rel)
            return 1
        with open(path, "r", encoding="utf-8") as fh:
            raw = fh.read()
        src = strip_noise(raw)
        allowed = method_bounds(src, ALLOWED_METHOD)
        for lineno, line in enumerate(src.split("\n"), start=1):
            if allowed and allowed[0] <= lineno <= allowed[1]:
                continue
            for pattern, label in FORBIDDEN:
                if pattern.search(line):
                    failures.append((rel, lineno, label, raw.split("\n")[lineno - 1].strip()))

    if failures:
        print("::error::A class that runs before OsmandSettings exists reads a preference.")
        print("::error::This does not degrade a feature - it throws out of onCreate and the")
        print("::error::app crash-loops on launch. It has happened twice; see this script.")
        for rel, lineno, label, text in failures:
            print("::error file=%s,line=%d::%s - %s" % (rel, lineno, label, text[:120]))
        print()
        print("Fix: move the read into CairoDriveLogger.logRuntimePreferences(settings),")
        print("which OsmandApplication.onCreate calls one line after settings are created.")
        return 1

    print("check_early_init: %d early-init file(s) clean - no preference reads before "
          "OsmandSettings exists." % len(EARLY_INIT))
    return 0


if __name__ == "__main__":
    sys.exit(main())
