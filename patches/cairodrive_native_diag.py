#!/usr/bin/env python3
"""
Makes the native router's own diagnostics readable from a drive log.

WHY THIS EXISTS
    The C++ router lives in osmandapp/OsmAnd-core-legacy, not in this repository.
    build-dev.yml checks it out at CORE_LEGACY_REF and compiles it with ndk-build, so
    patching that checkout before the build is the only place a change to it can live
    without forking the upstream repo.

    Two independent audits on 2026-08-06 concluded the 4-8 s offline calculation is
    dominated by FIXED per-query cost rather than route length, and named one suspect:
    initHHPoints / readPointBox read the ENTIRE Highway-Hierarchy point index for the
    region on every single calculation. Caching it was designed and then REJECTED - an
    adversarial review found four ways it produces a silently wrong route, and this fork's
    standard is that a wrong route is far worse than a slow one.

    But the decision was never actually measurable, for two separate reasons that both had
    to be fixed before any number could be read. This script fixes exactly those two and
    changes nothing else. It does NOT cache anything.

WHAT IT CHANGES

    1. THE LOG TAG. targets/android/OsmAndCore/src/Logging.cpp tags every native line
       "net.osmand:native". CairoDriveLogger's logcat pump asks for "net.osmand:V" plus a
       "*:W" floor - and a logcat filterspec is <tag>:<priority>, split on the first colon,
       so "net.osmand:native" can NOT be named as a filter at all. Every native line below
       WARNING therefore falls to the floor and is dropped. Retagging to "net.osmand" puts
       the whole native router inside the filter that already exists.

       This is why adding instrumentation to the native engine WITHOUT this change would
       have printed into a void: the numbers would be produced on every calculation and
       never appear in a drive log.

    2. THE DEAD LOAD STATISTICS. hhRouteDataStructure.h declares
       `static const int STATS_VERBOSE_LEVEL = 0;` - a compile-time constant - so every
       `if (c->STATS_VERBOSE_LEVEL > 0)` block is unreachable code. That includes the one
       line that prints how many HH points were loaded and how long the load took. Making
       that one line unconditional is what turns "nobody knows" into a number in the log.

       The point count is also the decisive input to the memory question: the cached
       structure would be roughly 400 bytes per point, which is ~20 MB at 50k points and
       ~120 MB at 300k - the difference between fine on a POCO C85 and an OOM kill
       mid-drive. That question cannot be answered without this line.

RISK
    Neither edit touches routing logic, data structures, or control flow. One changes a
    string passed to __android_log_vprint; the other removes an `if` around a log call.
    A wrong route is not reachable from either. The realistic failure is verbosity: the
    native router logs one extra INFO line per calculation.

USAGE
    python3 patches/cairodrive_native_diag.py <core-legacy-root>

    IMPORTANT - the workflow's native .so cache key MUST name this file. That key lists
    "every input to the compile, and nothing else"; this script is now such an input.
    Without it in the key, the first run after adding this patch restores a libosmand.so
    built from UNPATCHED sources and the `Verify restored native libraries` step confirms
    its sha256 happily, because the digest matches that stale library perfectly. The patch
    would silently not ship and nothing in the build would say so.
"""

import os
import sys

MARKER = "CD_NATIVE_DIAG"

# (relative path, anchor, replacement). Every anchor must appear EXACTLY once. Anything else
# means upstream drifted from the pinned CORE_LEGACY_REF and we refuse rather than guess at
# which site was meant. core-legacy is tab-indented; anchors carry their real whitespace.
EDITS = [
    (
        "targets/android/OsmAndCore/src/Logging.cpp",
        '    __android_log_vprint(androidLevel, "net.osmand:native", format, args);',
        '    // ' + MARKER + ': was "net.osmand:native". A logcat filterspec is <tag>:<priority>\n'
        '    // split on the first colon, so a tag containing one cannot be named as a filter -\n'
        '    // CairoDriveLogger asks for "net.osmand:V" and everything native fell to its "*:W"\n'
        '    // floor instead, dropping every INFO line the router writes. Same tag as the Java\n'
        '    // side now, so one filter captures the whole app including this engine.\n'
        '    __android_log_vprint(androidLevel, "net.osmand", format, args);',
    ),
    (
        "native/src/hhRoutePlanner.cpp",
        "\thctx->initialized = true;\n"
        "\thctx->stats.loadPointsTime += timer.GetElapsedMs();\n"
        "\tif (c->STATS_VERBOSE_LEVEL > 0) {\n"
        '\t\tOsmAnd::LogPrintf(OsmAnd::LogSeverityLevel::Info, " %zu - %.2f ms\\n", hctx->pointsById.size(), hctx->stats.loadPointsTime);\n'
        "\t}",
        "\thctx->initialized = true;\n"
        "\thctx->stats.loadPointsTime += timer.GetElapsedMs();\n"
        "\t// " + MARKER + ": unconditional. HHRoutingConfig::STATS_VERBOSE_LEVEL is a\n"
        "\t// compile-time 0 (hhRouteDataStructure.h:18), so the line below it has never been\n"
        "\t// reachable and the HH point count for a given .obf has never been knowable. Two\n"
        "\t// questions hang on it: how much of the 4-8 s search is this load (i.e. is a native\n"
        "\t// cache worth its risk at all), and how large that cache would be (~400 B/point).\n"
        '\tOsmAnd::LogPrintf(OsmAnd::LogSeverityLevel::Info, "CD_HHLOAD pts=%zu segs=%zu loadMs=%.0f",\n'
        "\t\t\thctx->pointsById.size(), hctx->cacheAllNetworkDBSegment.size(), hctx->stats.loadPointsTime);\n"
        "\tif (c->STATS_VERBOSE_LEVEL > 0) {\n"
        '\t\tOsmAnd::LogPrintf(OsmAnd::LogSeverityLevel::Info, " %zu - %.2f ms\\n", hctx->pointsById.size(), hctx->stats.loadPointsTime);\n'
        "\t}",
    ),
]


def fail(msg):
    sys.stderr.write("cairodrive_native_diag: %s\n" % msg)
    sys.exit(1)


def main():
    if len(sys.argv) != 2:
        fail("usage: cairodrive_native_diag.py <core-legacy-root>")
    root = sys.argv[1]

    # Read everything first. Nothing is written until every anchor has been verified, so a
    # drifted upstream leaves the checkout untouched rather than half-patched.
    paths = sorted({rel for rel, _, _ in EDITS})
    src = {}
    for rel in paths:
        p = os.path.join(root, rel)
        try:
            with open(p, encoding="utf-8") as h:
                src[rel] = h.read()
        except OSError as exc:
            fail("cannot read %s: %s\n"
                 "  Is '%s' an OsmAnd-core-legacy checkout?" % (p, exc, root))

    # Idempotence. CI steps get re-run; already-applied is success, not failure. A PARTIAL
    # application is not - that means a previous run died between writes.
    applied = [rel for rel in paths if MARKER in src[rel]]
    if applied:
        if len(applied) != len(paths):
            fail("PARTIALLY applied - %s carry the marker, %s do not.\n"
                 "  Refusing to touch a half-patched tree. Restore the checkout and rerun."
                 % (applied, [r for r in paths if r not in applied]))
        print("cairodrive_native_diag: already applied, nothing to do")
        return

    for rel, anchor, _ in EDITS:
        n = src[rel].count(anchor)
        if n != 1:
            fail("anchor not found exactly once in %s (found %d).\n"
                 "  OsmAnd-core-legacy has drifted from the CORE_LEGACY_REF this patch was\n"
                 "  written against. Read the upstream diff and update this script - do NOT\n"
                 "  relax the check.\n"
                 "  Anchor was:\n    %s" % (rel, n, anchor.replace("\n", "\n    ")))

    for rel, anchor, repl in EDITS:
        src[rel] = src[rel].replace(anchor, repl, 1)

    for rel in paths:
        p = os.path.join(root, rel)
        try:
            with open(p, "w", encoding="utf-8") as h:
                h.write(src[rel])
        except OSError as exc:
            fail("cannot write %s: %s\n"
                 "  The tree may now be half-patched - restore the checkout." % (p, exc))

    print("cairodrive_native_diag: applied")
    print("  native log tag  -> net.osmand   (so drive logs capture the router at INFO)")
    print("  CD_HHLOAD line  -> unconditional (pts=, segs=, loadMs= per calculation)")


if __name__ == "__main__":
    main()
