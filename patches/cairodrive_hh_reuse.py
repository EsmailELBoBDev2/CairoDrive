#!/usr/bin/env python3
"""
Stops the Highway-Hierarchy point index being reloaded on every single route calculation.

WHY THIS EXISTS
    Measured on the 2026-08-08 drives, with the CD_HHLOAD line that
    patches/cairodrive_native_diag.py exists to produce:

        283 loads across two drives, EVERY ONE of them pts=42580 - the same index
        median 739 ms, commonly 4042 ms, worst 9492 ms
        407.7 SECONDS of one drive spent re-reading data that had not changed

    179 of those loads served 213 calculations, so this is not the abandoned-search
    storm double-counting. It is roughly one full reload per route calculation.

    cairodrive_native_diag.py was written to answer exactly two questions before any
    caching could be justified - "how much of the 4-8 s search is this load" and "how
    large would the cache be". The numbers above answer the first. The second is
    42580 points x ~400 B = ~17 MB, against a measured nativeHeapMb=381 on the POCO
    C85. Both answers point the same way.

WHAT IT CHANGES - AND WHAT IT DELIBERATELY DOES NOT

    It does NOT add a cache. Upstream already has one, and two of them:

      1. initHCtx (hhRoutePlanner.cpp:257) - `if (hctx->initialized) return hctx;`,
         placed AFTER stats/config/startX/endX/clearVisited() have all been reset, so
         every piece of per-query state is cleared before the load is skipped.
      2. filterPointsBasedOnConfiguration (:1448) - compares the live routing parameter
         map against hctx->filterRoutingParameters and returns early when they match,
         skipping the O(N) acceptLine sweep over all 42580 points.

    And selectBestRoutingFiles (:114-129) already decides when reuse is legal: it
    rebuilds the region list from the CURRENT open-files snapshot, compares each one
    against currentCtx->regions by file, fileRegion and routingProfile, and returns
    `currentCtx` unchanged only when all of them match.

    None of that can ever fire across two calculations, for one reason: both call sites
    in routePlannerFrontEnd.cpp (:366 and :615) construct `HHRoutePlanner routePlanner(ctx)`
    as a STACK object per calculation, and its constructor does
    `currentCtx = std::make_shared<HHRoutingContext>()`. Every query therefore starts
    from an empty context, `allMatched` is trivially false against an empty region list,
    and `initialized` is false. The machinery is present and structurally unreachable.

    This patch makes the context outlive the planner. It adds NO new notion of when
    reuse is safe - it lets upstream's own two guards answer that, which is the whole
    reason it is a small change rather than the bespoke cache that was designed and
    rejected on 2026-08-06.

THE THREE THINGS THAT ARE GENUINELY NEW, AND HOW EACH IS CLOSED

    1. A DEAD RoutingContext POINTER. HHRoutingContext::rctx is a raw pointer to the
       per-query RoutingContext, which is destroyed when the calculation ends.
       selectBestRoutingFiles dereferences it on its FIRST line (hctx->rctx->config->router)
       and initHCtx on its second (hctx->rctx->progress). So the adopted context has its
       rctx re-pointed at the live one in the constructor, before anything can read it.

    2. TWO SEARCHES AT ONCE. This fork runs concurrent calculations by design - the
       offline/online race, plus overlapping reroutes. Sharing one context between them
       would corrupt visited sets and queues. A non-blocking try_lock is taken for the
       planner's whole lifetime: the first search in gets the cached context, any search
       that arrives while it is held builds its own exactly as today and never touches
       the cache. Contention degrades to current behaviour rather than to a wrong route,
       and no search ever waits on another.

    3. A CLOSED .obf REOPENED AT THE SAME ADDRESS. selectBestRoutingFiles compares
       region files by POINTER, which is sound within one query but not across a map
       download, delete or index reload. The open-files snapshot is therefore recorded
       when the context is cached and compared on adopt; any difference drops the cache.

    Kept deliberately UNCACHED between calculations: nothing. The whole context is
    either adopted wholesale under those guards or rebuilt from scratch.

WHAT IT COSTS IF IT IS WRONG ANYWAY
    ~17 MB of native heap held for the life of the process, and a route computed
    against a stale point index. The second is what CD_HHREUSE exists to make visible:
    it names the decision taken on every calculation, so a drive log says which
    calculations reused and which did not, rather than leaving it to be inferred from a
    loadMs that got smaller.

    Set CAIRODRIVE_HH_REUSE=false at build time to not apply this patch at all. The
    resulting libosmand.so is byte-for-byte the one built without this file, because
    the flag gates whether the edits are made rather than a branch inside them.

USAGE
    python3 patches/cairodrive_hh_reuse.py <core-legacy-root>

    IMPORTANT - the workflow's native .so cache key MUST name this file AND the flag
    that gates it, for exactly the reason cairodrive_native_diag.py's docstring gives:
    without both, the first build after a change restores a libosmand.so compiled from
    different sources and `Verify restored native libraries` confirms its sha256
    happily, because the digest matches that stale library perfectly.
"""

import os
import sys

MARKER = "CD_HH_REUSE"

# (relative path, anchor, replacement). Every anchor must appear EXACTLY once. Anything
# else means upstream drifted from the pinned CORE_LEGACY_REF, and we refuse rather than
# guess at which site was meant. core-legacy is tab-indented; anchors carry their real
# whitespace.
EDITS = [
    # ------------------------------------------------------------------ header: destructor
    (
        "native/src/hhRoutePlanner.h",
        "\tHHRoutePlanner(RoutingContext * ctx);\n",
        "\tHHRoutePlanner(RoutingContext * ctx);\n"
        "\t// " + MARKER + ": releases the cross-calculation context lock and publishes the\n"
        "\t// context for the next calculation. Non-virtual and never deleted through a base\n"
        "\t// pointer - HHRoutePlanner has no base and both call sites are stack objects.\n"
        "\t~HHRoutePlanner();\n",
    ),
    # ------------------------------------------------------------- header: the lock flag
    (
        "native/src/hhRoutePlanner.h",
        "\tSHARED_PTR<HHRoutingContext> currentCtx;\n",
        "\tSHARED_PTR<HHRoutingContext> currentCtx;\n"
        "\t// " + MARKER + ": true when THIS planner owns the shared cached context and must\n"
        "\t// publish it and unlock on destruction. False whenever the try_lock failed, in\n"
        "\t// which case this planner is an ordinary per-query one and the cache is untouched.\n"
        "\tbool cdHoldsCacheLock = false;\n",
    ),
    # ------------------------------------------------- cpp: the constructor and destructor
    (
        "native/src/hhRoutePlanner.cpp",
        "HHRoutePlanner::HHRoutePlanner(RoutingContext * ctx) {\n"
        "\tstd::vector<SHARED_PTR<HHRouteRegionPointsCtx>> regions;\n"
        "\tinitNewContext(ctx, regions);\n"
        "\thhRouteRegionGroup = std::make_shared<HHRouteRegionsGroup>();\n"
        "}\n",

        "// " + MARKER + " -------------------------------------------------------------------\n"
        "// The HH point index is identical between consecutive calculations - measured at\n"
        "// pts=42580 on every one of 283 loads across two Cairo drives, costing a median of\n"
        "// 739 ms and up to 9492 ms each, 407 s in a single drive. Upstream already declines to\n"
        "// reload it (initHCtx: `if (hctx->initialized) return hctx;`) and already decides when\n"
        "// that is legal (selectBestRoutingFiles compares the freshly built region list against\n"
        "// currentCtx->regions and returns currentCtx only when every one matches). Neither can\n"
        "// fire, because the planner is a stack object and its constructor built an empty\n"
        "// context every time. Keeping the context alive past the planner is the entire change;\n"
        "// the decision of whether it may be USED stays upstream's.\n"
        "static std::mutex cdHHCacheMutex;\n"
        "static SHARED_PTR<HHRoutingContext> cdHHCachedCtx;\n"
        "// The open .obf set as it was when cdHHCachedCtx was published. selectBestRoutingFiles\n"
        "// compares region files by POINTER, which is sound inside one query but cannot see a\n"
        "// map closed and reopened at the same address between two of them.\n"
        "static std::vector<BinaryMapFilePtr> cdHHCachedFiles;\n"
        "\n"
        "static bool cdHHSameOpenFiles(const BinaryMapFiles & now) {\n"
        "\tif (now.size() != cdHHCachedFiles.size()) {\n"
        "\t\treturn false;\n"
        "\t}\n"
        "\tfor (size_t i = 0; i < now.size(); i++) {\n"
        "\t\tif (now[i].get() != cdHHCachedFiles[i].get()) {\n"
        "\t\t\treturn false;\n"
        "\t\t}\n"
        "\t}\n"
        "\treturn true;\n"
        "}\n"
        "\n"
        "HHRoutePlanner::HHRoutePlanner(RoutingContext * ctx) {\n"
        "\t// try_lock, never lock. A second concurrent calculation - this fork races an offline\n"
        "\t// and an online search on every route, and reroutes overlap - must not wait on the\n"
        "\t// first and must not share its visited sets. It silently becomes an ordinary\n"
        "\t// per-query planner instead, which is precisely the behaviour before this patch.\n"
        "\tcdHoldsCacheLock = cdHHCacheMutex.try_lock();\n"
        "\tbool reused = false;\n"
        "\tif (cdHoldsCacheLock) {\n"
        "\t\tconst BinaryMapFiles & openFiles = getOpenFilesSnapshot();\n"
        "\t\tif (cdHHCachedCtx != nullptr && cdHHSameOpenFiles(openFiles)) {\n"
        "\t\t\tcurrentCtx = cdHHCachedCtx;\n"
        "\t\t\t// BEFORE anything reads it. selectBestRoutingFiles dereferences rctx on its\n"
        "\t\t\t// first line and initHCtx on its second, and the one this context was cached\n"
        "\t\t\t// with was destroyed when that calculation ended.\n"
        "\t\t\tcurrentCtx->rctx = ctx;\n"
        "\t\t\treused = true;\n"
        "\t\t} else if (cdHHCachedCtx != nullptr) {\n"
        "\t\t\t// The maps changed under it. Drop it rather than trust a pointer comparison\n"
        "\t\t\t// against a file that has been closed.\n"
        "\t\t\tcdHHCachedCtx = nullptr;\n"
        "\t\t}\n"
        "\t}\n"
        "\tif (!reused) {\n"
        "\t\tstd::vector<SHARED_PTR<HHRouteRegionPointsCtx>> regions;\n"
        "\t\tinitNewContext(ctx, regions);\n"
        "\t}\n"
        "\t// Names the decision on EVERY calculation. Without this the only evidence of reuse\n"
        "\t// is a CD_HHLOAD line that did not appear, which is indistinguishable from a search\n"
        "\t// that failed before it got that far.\n"
        '\tOsmAnd::LogPrintf(OsmAnd::LogSeverityLevel::Info, "CD_HHREUSE reused=%d held=%d",\n'
        "\t\t\treused ? 1 : 0, cdHoldsCacheLock ? 1 : 0);\n"
        "\thhRouteRegionGroup = std::make_shared<HHRouteRegionsGroup>();\n"
        "}\n"
        "\n"
        "HHRoutePlanner::~HHRoutePlanner() {\n"
        "\tif (!cdHoldsCacheLock) {\n"
        "\t\treturn;\n"
        "\t}\n"
        "\t// Publish whatever the calculation ended up with. selectBestRoutingFiles REPLACES\n"
        "\t// currentCtx when the region set changed, so this is not necessarily the context\n"
        "\t// adopted above - which is correct: the freshly built one is the one worth keeping.\n"
        "\tcdHHCachedCtx = currentCtx;\n"
        "\tcdHHCachedFiles = getOpenFilesSnapshot();\n"
        "\tcdHHCacheMutex.unlock();\n"
        "}\n",
    ),
]


def fail(msg):
    print("ERROR: " + msg, file=sys.stderr)
    sys.exit(1)


def main():
    if len(sys.argv) != 2:
        fail("usage: cairodrive_hh_reuse.py <core-legacy-root>")
    root = sys.argv[1]
    if not os.path.isdir(root):
        fail("not a directory: " + root)

    # Validate every anchor BEFORE writing anything. A partial apply would leave a header
    # declaring a destructor that the .cpp does not define, which fails in the NDK compile
    # with a message that says nothing about this script.
    #
    # Edits ACCUMULATE per file rather than each starting from what is on disk. Two of
    # them target hhRoutePlanner.h, and reading the original twice means the second write
    # silently discards the first - which is exactly what happened when this was written
    # the obvious way: the destructor declaration vanished, the definition in the .cpp
    # remained, and nothing said so until a full native build failed.
    pending = {}
    planned = []
    for rel, anchor, replacement in EDITS:
        path = os.path.join(root, rel)
        if not os.path.isfile(path):
            fail("missing file (is CORE_LEGACY_REF right?): " + rel)
        if path in pending:
            text = pending[path]
        else:
            with open(path, "r", encoding="utf-8") as fh:
                text = fh.read()
        # Idempotency is tested against THIS edit's own replacement, not against the
        # marker. Two of the three edits keep their anchor inside the replacement (the
        # destructor is declared next to the constructor it belongs with), so a
        # marker-in-file test passes while the anchor still matches - and the edit is
        # applied a second time, declaring the destructor twice. That does not fail here;
        # it fails much later, in the NDK compile, on a line this script did not write.
        if replacement in text:
            print("already patched, skipping: " + rel)
            continue
        count = text.count(anchor)
        if count != 1:
            fail(
                "anchor appears %d times in %s (expected exactly 1). Upstream has drifted "
                "from the pinned CORE_LEGACY_REF - read the diff and update this patch "
                "rather than loosening the check." % (count, rel)
            )
        pending[path] = text.replace(anchor, replacement, 1)
        planned.append(rel)

    if not planned:
        print("nothing to do - all edits already present")
        return

    for path, new_text in pending.items():
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(new_text)
    for rel in planned:
        print("patched: " + rel)

    # <mutex> is not included by hhRoutePlanner.cpp's own include list, and relying on it
    # arriving transitively through CommonCollections.h is the kind of thing that compiles
    # on one NDK and not the next.
    cpp = os.path.join(root, "native/src/hhRoutePlanner.cpp")
    with open(cpp, "r", encoding="utf-8") as fh:
        text = fh.read()
    if "#include <mutex>" not in text:
        anchor = "#include <ctime>\n"
        if text.count(anchor) != 1:
            fail("could not place #include <mutex> in hhRoutePlanner.cpp")
        text = text.replace(anchor, anchor + "#include <mutex>  // " + MARKER + "\n", 1)
        with open(cpp, "w", encoding="utf-8") as fh:
            fh.write(text)
        print("patched: native/src/hhRoutePlanner.cpp (#include <mutex>)")


if __name__ == "__main__":
    main()
