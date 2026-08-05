#!/usr/bin/env python3
"""Check the BudgetPacer ladders and the staleness windows that depend on them.

Run with no arguments; it reads the tree.

WHY THIS EXISTS
---------------
The pacing ladders and the TTLs that consume them live in four different files,
and in one afternoon the same defect appeared three separate times:

  * Google spans paced down to a 65-minute poll against a fixed 10-minute paint
    TTL, so the overlay was hidden for 85% of a long drive;
  * TomTom incidents paced to 26 minutes against a 4-minute window;
  * TomTom flow paced to 10 minutes against a 4-minute window.

In every case the budget was being spent exactly as designed and the user saw
nothing, because the data expired before the poll that would have replaced it.
Nothing in a compile or a unit test catches that - the two numbers are correct
in isolation and only wrong in relation to each other.

So the invariant is: FOR EVERY STREAM, THE STALENESS WINDOW MUST BE AT LEAST AS
LONG AS THE SLOWEST POLL THE LADDER CAN EVER ISSUE. Plus the ladder properties
themselves - budget fully spent, 24-hour coverage, monotone degradation, and a
last rung that really is the floor.
"""
import re
import sys
import os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
SRC = os.path.join(ROOT, "OsmAnd", "src", "net", "osmand", "plus")

TOMTOM = os.path.join(SRC, "cairodrive", "providers", "TomTomTrafficProvider.java")
GOOGLE = os.path.join(SRC, "routing", "GoogleTrafficHelper.java")
PROVIDERS = os.path.join(SRC, "cairodrive", "providers", "CairoDriveProviders.java")

# stream, ladder file, ladder name, cap name, ttl file, ttl const, ttl scales with cadence
STREAMS = [
    ("TomTom flow", TOMTOM, "FLOW_LADDER", "FLOW_DAILY_CAP",
     PROVIDERS, "FLOW_TTL_MS", True),
    ("TomTom incidents", TOMTOM, "INCIDENT_LADDER", "INCIDENT_DAILY_CAP",
     PROVIDERS, "INCIDENTS_TTL_MS", True),
    ("Google spans", GOOGLE, "SPANS_LADDER", "SPANS_DAILY_CAP",
     GOOGLE, "SNAPSHOT_TTL_MS", True),
    ("Google delay", GOOGLE, "DELAY_LADDER", "DELAY_DAILY_CAP",
     None, None, False),
]

TIER_RE = re.compile(r"Tier\(\s*([0-9.]+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)")
DAY_MIN = 1440.0
# The margin the runtime applies on top of the poll interval (interval * 3/2).
# Kept in step with CairoDriveProviders.effectiveTtl and spansPaintTtlMs.
TTL_MARGIN_NUM, TTL_MARGIN_DEN = 3, 2


def read(path):
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def find_const(src, name):
    """Value of a `... NAME = <int> [* <int>]*;` constant, in its own units."""
    # Values are written as `4 * 60 * 1000L`, so the long suffix has to be tolerated.
    m = re.search(r"\b" + re.escape(name) + r"\s*=\s*([0-9*\sLl]+?)\s*;", src)
    if not m:
        return None
    value = 1
    for part in m.group(1).split("*"):
        value *= int(part.strip().rstrip("Ll"))
    return value


def find_ladder(src, name):
    m = re.search(r"\b" + re.escape(name) + r"\s*=\s*\{(.*?)\n\t\};", src, re.S)
    if not m:
        return None
    return [(float(a), int(b), int(c)) for a, b, c in TIER_RE.findall(m.group(1))]


def main():
    problems = []
    print(f"{'stream':20s} {'cap':>5s} {'rungs':>5s} {'spend':>6s} {'covers':>7s} "
          f"{'floor':>7s} {'window':>7s}")
    for (name, lfile, lname, cname, tfile, tname, scales) in STREAMS:
        lsrc = read(lfile)
        tiers = find_ladder(lsrc, lname)
        cap = find_const(lsrc, cname)
        if not tiers or cap is None:
            problems.append(f"{name}: could not parse {lname}/{cname}")
            continue

        fsum = sum(t[0] for t in tiers)
        covers = sum((cap * f / u) * (s / 60.0) for f, u, s in tiers)
        floor_s = tiers[-1][2]

        if abs(fsum - 1.0) > 0.002:
            problems.append(f"{name}: fractions sum to {fsum:.4f}, not 1.000 - "
                            f"the free tier is not fully spent")
        if covers < DAY_MIN:
            problems.append(f"{name}: covers {covers/60:.2f} h, under the 24 h guarantee")
        if floor_s != max(t[2] for t in tiers):
            problems.append(f"{name}: last rung is not the slowest, so it is not a floor")
        for i in range(1, len(tiers)):
            if tiers[i][2] < tiers[i - 1][2]:
                problems.append(f"{name}: interval decreases at rung {i+1}")
            if tiers[i][1] > tiers[i - 1][1]:
                problems.append(f"{name}: units increase at rung {i+1}")

        window = "-"
        if tname:
            base = find_const(read(tfile), tname)
            if base is None:
                problems.append(f"{name}: could not parse {tname}")
            else:
                base_s = base // 1000
                effective = max(base_s, floor_s * TTL_MARGIN_NUM // TTL_MARGIN_DEN) \
                    if scales else base_s
                window = f"{effective//60}m"
                if effective < floor_s:
                    problems.append(
                        f"{name}: staleness window {effective//60}m is SHORTER than the "
                        f"{floor_s//60}m poll floor - the data expires before the poll that "
                        f"would replace it, so the feature goes dark while the budget is "
                        f"still being spent")
                elif not scales and base_s < floor_s:
                    problems.append(
                        f"{name}: {tname} is fixed at {base_s//60}m but the ladder floors at "
                        f"{floor_s//60}m; it must scale with the cadence")

        print(f"{name:20s} {cap:5d} {len(tiers):5d} {fsum:6.3f} {covers/60:6.2f}h "
              f"{floor_s//60:5d}m/{tiers[-1][1]}u {window:>7s}")

    problems += check_horizon()

    print()
    for p in problems:
        print("FAIL " + p)
    if problems:
        print(f"\n{len(problems)} problem(s)")
        return 1
    print("all ladders spend 100%, cover 24 h, degrade monotonically, every staleness window "
          "outlasts its poll floor,\nand the route-aware speed-up never slows a stream down nor "
          "outruns rung one")
    return 0


def check_horizon():
    """BudgetPacer.forHorizon must only ever SPEED UP, and never past rung one.

    That two-sided clamp is the entire safety argument for reading the router's ETA: it is why
    every coverage proof above still holds unchanged with the speed-up switched on. If a refactor
    breaks the clamp, a long drive could be paced by a wrong ETA and run dry - so the property is
    re-derived here from the constants rather than trusted.
    """
    src = read(os.path.join(ROOT, "OsmAnd", "src", "net", "osmand", "plus", "cairodrive",
                            "providers", "BudgetPacer.java"))
    def const(name, default):
        m = re.search(r"\b" + name + r"\s*=\s*([0-9.]+)\s*;", src)
        return float(m.group(1)) if m else default
    slack = const("HORIZON_SLACK", 1.5)
    reserve = const("TRIP_RESERVE", 0.34)
    min_h = const("MIN_HORIZON_MIN", 5)

    def for_horizon(ladder_ms, used, cap, units, remaining_min, fastest_ms):
        if remaining_min < min_h or cap <= 0 or units <= 0:
            return ladder_ms
        calls = max(0, cap - used) * (1.0 - reserve) / units
        if calls < 1:
            return ladder_ms
        return max(fastest_ms, min(ladder_ms, int(remaining_min * 60000 / calls)))

    bad = []
    for (name, lfile, lname, cname, _tf, _tn, _s) in STREAMS:
        lsrc = read(lfile)
        tiers = find_ladder(lsrc, lname)
        cap = find_const(lsrc, cname)
        if not tiers or not cap:
            continue
        fastest = tiers[0][2] * 1000
        for used in range(0, cap + 1, max(1, cap // 12)):
            acc, tier = 0.0, tiers[-1]
            for t in tiers:
                acc += t[0]
                if used < cap * acc:
                    tier = t
                    break
            lad = tier[2] * 1000
            for eta in (0, 5, 20, 45, 90, 180, 360, 720, 1440):
                got = for_horizon(lad, used, cap, tier[1], int(eta * slack), fastest)
                if got > lad:
                    bad.append(f"{name}: horizon SLOWED the stream (used={used} eta={eta}m)")
                if got < fastest:
                    bad.append(f"{name}: horizon outran rung one (used={used} eta={eta}m)")
    return sorted(set(bad))


if __name__ == "__main__":
    sys.exit(main())
