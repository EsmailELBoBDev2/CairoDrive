#!/usr/bin/env python3
"""
Reads CairoDrive drive logs and answers the questions the current build is asking.

    adb pull /sdcard/Android/data/com.cairodrive.app/files/cairodrive-logs ./cd-logs
    python3 cd-analyze.py cd-logs/*.log

CLAUDE.md says this script must exist and to recreate it if it does not. It did not - so a
drive log would have been read by hand, which is how a number gets missed. Every section below
maps to a decision that is currently open; if a section prints "no data", that is a finding in
itself and is reported as one rather than skipped silently.
"""

import re
import sys
from collections import defaultdict

BAR = "=" * 78


def kv(line):
    """Parse `key=value` pairs. Values may be numbers, booleans or bare words."""
    return dict(re.findall(r"(\w+)=([-\w.:]+)", line))


def num(d, k, default=None):
    try:
        return float(d[k])
    except (KeyError, ValueError, TypeError):
        return default


def read(paths):
    rows = defaultdict(list)
    for p in paths:
        try:
            with open(p, encoding="utf-8", errors="replace") as fh:
                for line in fh:
                    m = re.search(r"(SESSION|CD_[A-Z_]+)\s+(.*)", line.rstrip("\n"))
                    if m:
                        rows[m.group(1)].append(m.group(2))
        except OSError as exc:
            print("cannot read %s: %s" % (p, exc), file=sys.stderr)
    return rows


def section(title):
    print("\n" + BAR + "\n" + title + "\n" + BAR)


def build_identity(rows):
    section("BUILD - which build produced this log")
    lines = rows.get("SESSION", [])
    if not lines:
        print("  NO SESSION HEADER. Cannot attribute any number below to a build.")
        return
    seen = set()
    for line in lines:
        d = kv(line)
        key = tuple(d.get(k) for k in ("versionCode", "buildType", "flavor", "hwCanvas",
                                       "presentation", "renderScale", "offRouteHysteresis"))
        if key in seen:
            continue
        seen.add(key)
        print("  versionCode=%s buildType=%s flavor=%s" % (
            d.get("versionCode", "?"), d.get("buildType", "?"), d.get("flavor", "?")))
        print("  asked for:  hwCanvas=%s presentation=%s renderScale=%s overscan=%s" % (
            d.get("hwCanvas", "MISSING"), d.get("presentation", "MISSING"),
            d.get("renderScale", "?"), d.get("surfaceOverscan", "?")))
        print("  hysteresis=%s dataSaver=%s fullLogging=%s" % (
            d.get("offRouteHysteresis", "?"), d.get("dataSaver", "?"), d.get("fullLogging", "?")))
    if len(seen) > 1:
        print("  WARNING: more than one build in this folder. Old and new runs are mixed -")
        print("           delete the log dir before a drive, a Play update does not clear it.")


def frames(rows):
    section("FRAME - what the head unit actually cost, vs the 2026-08-04 baseline")
    base = dict(over=25.9, blit=9.2, read=4.5, avg=46.9)
    sums = [kv(l) for l in rows.get("CD_FRAME", []) if l.startswith("summary")]
    if not sums:
        print("  no CD_FRAME summary lines - was the phone connected to the car?")
        return
    modes = defaultdict(list)
    for d in sums:
        modes[d.get("renderMode", "offscreen")].append(d)
    for mode, ds in modes.items():
        total = sum(num(d, "frames", 0) for d in ds)
        def wavg(k):
            n = sum(num(d, k, 0) * num(d, "frames", 0) for d in ds)
            return n / total if total else 0
        print("\n  renderMode=%s   (%d windows, %d frames)" % (mode, len(ds), total))
        print("    avgMs   %6.1f   (was %.1f)   maxMs %s" % (
            wavg("avgMs"), base["avg"], max(num(d, "maxMs", 0) for d in ds)))
        print("    avgOver %6.1f   (was %.1f)  <- the layers, this is the big one" % (
            wavg("avgOver"), base["over"]))
        if mode == "presentation":
            hw = {d.get("hwAccel") for d in ds}
            print("    hwAccel %s" % ", ".join(sorted(str(h) for h in hw)))
            if "false" in hw:
                print("    ^^ FALSE means the window fell back to SOFTWARE. The overlays are still")
                print("       on the CPU and the numbers above do not mean what they look like.")
        else:
            print("    avgBlit %6.1f   (was %.1f)" % (wavg("avgBlit"), base["blit"]))
            print("    avgRead %6.1f   (was %.1f)" % (wavg("avgRead"), base["read"]))
            hw = {d.get("hwCanvas") for d in ds if d.get("hwCanvas")}
            if hw:
                print("    hwCanvas %s" % ", ".join(sorted(hw)))
                if "REFUSED" in hw:
                    print("    ^^ the head unit REFUSED the hardware canvas. Not a code problem.")
        slow = sum(num(d, "slow", 0) for d in ds)
        print("    slow    %6.1f%%  of frames" % (100.0 * slow / total if total else 0))


def presentation(rows):
    section("B1 - did the VirtualDisplay path attach?")
    lines = rows.get("CD_PRESENT", [])
    if not lines:
        print("  no CD_PRESENT lines. Either this build predates B1, or the car never connected.")
        return
    for line in dict.fromkeys(lines):
        print("  " + line)


def layers(rows):
    section("CD_LAYER - which layers are eating `over`")
    lines = rows.get("CD_LAYER", [])
    if not lines:
        print("  no CD_LAYER lines.")
        return
    worst = defaultdict(list)
    for line in lines:
        for name, us in re.findall(r"([A-Za-z]+Layer)=(\d+)", line):
            worst[name].append(int(us))
    if not worst:
        print("  lines present but no layer=us pairs parsed. Sample: %s" % lines[0][:100])
        return
    ranked = sorted(worst.items(), key=lambda kvp: -sum(kvp[1]) / len(kvp[1]))
    for name, vals in ranked[:10]:
        print("  %-34s avg %7.0f us   (%5.2f ms)  seen %d" % (
            name, sum(vals) / len(vals), sum(vals) / len(vals) / 1000.0, len(vals)))


def routing(rows):
    section("ROUTE TIMING - where a 4-8 s reroute actually goes")
    lines = rows.get("CD_ROUTE_TIMING", [])
    ds = [kv(l) for l in lines if "search=" in l]
    if not ds:
        print("  no CD_ROUTE_TIMING lines. A route must be ACTIVE - free driving logs nothing here.")
        return
    engines = defaultdict(int)
    for d in ds:
        engines[d.get("engine", "?")] += 1
    print("  engines: %s" % dict(engines))
    if any("java" in e for e in engines):
        print("  *** engine contains `java`: libosmand.so did NOT load. Every reroute is on the")
        print("      Java router - 6.8 s average, 39 s worst, measured. Say so loudly.")
    finds = [num(d, "find", 0) for d in ds if d.get("engine") == "hh-cpp"]
    if finds and max(finds) > 0:
        print("  *** PREDICTION FALSIFIED: find>0 on an hh-cpp line (max %.2f). The static reading" % max(finds))
        print("      that the C++ HH branch never resolves start segments in Java is WRONG.")
    elif finds:
        print("  find=0 on every hh-cpp line, as predicted.")
    print("\n  %-9s %-8s %-8s %-8s %-8s %-8s %s" % (
        "straightM", "search", "pre", "calc", "load", "hdrs", "flags"))
    for d in ds[-25:]:
        print("  %-9s %-8s %-8s %-8s %-8s %-8s reroute=%s skipPriv=%s fast=%s recalcEnd=%s" % (
            d.get("straightM", "?"), d.get("search", "?"), d.get("pre", "-"),
            d.get("calc", "?"), d.get("load", "?"), d.get("headers", "?"),
            d.get("reroute", "?"), d.get("skipPriv", "-"), d.get("fast", "?"),
            d.get("recalcEnd", "-")))
    pres = [num(d, "pre") for d in ds if num(d, "pre") is not None]
    searches = [num(d, "search") for d in ds if num(d, "search") is not None]
    if pres and searches:
        mp, ms = sum(pres) / len(pres), sum(searches) / len(searches)
        print("\n  mean pre=%.0f ms of mean search=%.0f ms  (%.0f%%)" % (mp, ms, 100 * mp / ms if ms else 0))
        print("  -> that %.0f%% is the part reachable WITHOUT touching libosmand.so." % (100 * mp / ms if ms else 0))
    if not pres:
        print("\n  NO pre= field. This build predates the pre-search instrumentation.")


def reroute(rows):
    section("REROUTE - the decision that is actually open")
    lines = rows.get("CD_REROUTE", [])
    if not lines:
        print("  no CD_REROUTE lines. A route must be ACTIVE and you must go off it at least once.")
        return
    dropped = [kv(l) for l in lines if l.startswith("dropped")]
    finished = [kv(l) for l in lines if l.startswith("finished")]
    probes = [kv(l) for l in lines if l.startswith("repairProbe") and "repairMs" in l]

    print("  dispatched=%d  finished=%d  dropped=%d" % (
        sum(1 for l in lines if l.startswith("dispatched")), len(finished), len(dropped)))
    if dropped:
        waits = [num(d, "evalWaitMs", 0) for d in dropped]
        busy = sum(1 for d in dropped if d.get("busy") == "1")
        print("  DROPPED reroutes: %d busy, %d waiting. max evalWaitMs=%.0f" % (
            busy, len(dropped) - busy, max(waits)))
        if max(waits) > 30000:
            print("  *** evalWaitInterval reached %.0f s. Over that window a deviation is refused" % (max(waits) / 1000))
            print("      silently and NOTHING retries. This is a real defect, only measured so far.")
    if finished:
        tot = [num(d, "totalMs", 0) for d in finished]
        print("  dispatch->result: mean %.0f ms, worst %.0f ms" % (sum(tot) / len(tot), max(tot)))

    print("\n  --- THE REPAIR DECISION ---")
    if not probes:
        print("  NO repairProbe lines. Either no deviation happened, or <600 m of route remained,")
        print("  or the 90 s rate limit swallowed them. The repair decision CANNOT be made.")
        return
    searches = [num(kv(l), "search") for l in rows.get("CD_ROUTE_TIMING", []) if "search=" in l]
    searches = [s for s in searches if s]
    full = sum(searches) / len(searches) if searches else None
    for d in probes:
        print("  repairMs=%-8s straightM=%-7s alongRouteM=%-7s ok=%s" % (
            d.get("repairMs"), d.get("straightM"), d.get("alongRouteM", "?"), d.get("ok")))
    reps = [num(d, "repairMs", 0) for d in probes]
    mean_rep = sum(reps) / len(reps)
    print("\n  mean repair = %.0f ms" % mean_rep)
    if full:
        ratio = mean_rep / full
        print("  mean full   = %.0f ms   ->  repair is %.0f%% of a full search" % (full, 100 * ratio))
        if ratio < 0.35:
            print("  ==> BUILD THE REPAIR. A short route IS proportionally cheaper here.")
            print("      Expected saving per reroute: ~%.1f s" % ((full - mean_rep) / 1000))
        elif ratio > 0.7:
            print("  ==> REPAIR IS DEAD. The fixed per-endpoint cost dominates; a 600 m repair costs")
            print("      nearly what an 8 km search does. Drop items 4 and 6. Say so plainly.")
        else:
            print("  ==> INCONCLUSIVE. Needs more samples before committing a week to it.")


def eta(rows):
    section("ETA")
    ds = [kv(l) for l in rows.get("CD_ETA", [])]
    if not ds:
        print("  no CD_ETA lines - a route must be active.")
        return
    ratios = [num(d, "ratio") for d in ds if num(d, "ratio") is not None]
    if ratios:
        print("  ratio: mean %.2f  min %.2f  max %.2f  over %d samples" % (
            sum(ratios) / len(ratios), min(ratios), max(ratios), len(ratios)))
        print("  (<1 means the router's model is optimistic and the correction stretches the ETA)")
    print("  last: %s" % ds[-1])


def misc(rows):
    section("EVERYTHING ELSE")
    for tag, hint in (("CD_TRIP", "head unit accepted the manoeuvre card during reroute?"),
                      ("CD_DATA", "tile downloads blocked on metered?"),
                      ("CD_ITINERARY", "itinerary.gpx parse failures"),
                      ("CD_NARROW", "narrow-street coverage"),
                      ("CD_SEARCH", "Places API"),
                      ("CD_VOICE", "TTS")):
        lines = rows.get(tag, [])
        print("\n  %s  (%s)" % (tag, hint))
        if not lines:
            print("    none")
            continue
        for line in list(dict.fromkeys(lines))[:4]:
            print("    " + line[:150])
    nav = rows.get("CD_NAV", [])
    if nav:
        degraded = sum(1 for l in nav if "gnss=DEGRADED" in l)
        print("\n  CD_NAV: %d fixes, %d DEGRADED (%.0f%%)" % (
            len(nav), degraded, 100.0 * degraded / len(nav)))


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    rows = read(sys.argv[1:])
    if not rows:
        print("no CairoDrive lines found in %d file(s)." % (len(sys.argv) - 1))
        sys.exit(1)
    build_identity(rows)
    frames(rows)
    presentation(rows)
    layers(rows)
    routing(rows)
    reroute(rows)
    eta(rows)
    misc(rows)
    print("\n" + BAR)


if __name__ == "__main__":
    main()
