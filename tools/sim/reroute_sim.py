#!/usr/bin/env python3
"""
Monte Carlo of the WHOLE reroute wait, from wrong turn to new route on screen.

Every constant below is read out of the tree, not invented:

  RoutingHelper:57      POS_TOLERANCE = 60
  RoutingHelper:58      POS_TOLERANCE_DEVIATION_MULTIPLIER = 2
  RoutingHelper:1085    MIN_POS_TOLERANCE_GOOD_FIX = 25
  RoutingHelper:1087    POS_TOLERANCE_GOOD_FIX_FACTOR = 2.5
  RoutingHelper:1097    NEAR_MANOEUVRE_M = 40
  RoutingHelper:1100    NEAR_MANOEUVRE_TOLERANCE_MULT = 0.5
  CairoDriveOffRoute:50 MIN_CONSECUTIVE_FIXES = 3
  CairoDriveOffRoute:54 MAX_CONSECUTIVE_FIXES = 20
  CairoDriveOffRoute:52 ACCURACY_PER_REQUIRED_FIX_M = 4
  CairoDriveOffRoute:60 MAX_SUPPRESSION_MS = 12000
  CairoDriveOffRoute:63 STRONG_RATIO = 2.5
  CairoDriveOffRoute:66 OVERWHELMING_RATIO = 4.0
  CairoDriveEarlyReroute:91  EARLY_START_FRACTION = 0.5

The one thing NOT in the tree is the offline search time. It is measured on the
device at 4-8 s, so it is a parameter here, and the last table sweeps it -
because "what if a Cairo-only .obf halves the HH load" is exactly a change to
that parameter and nothing else.

Degraded/healthy fix mix is 55/45, measured on the POCO C85.
"""

import random
import statistics

random.seed(20260807)

POS_TOLERANCE = 60.0
DEV_MULT = 2.0
MIN_POS_TOL_GOOD = 25.0
GOOD_FIX_FACTOR = 2.5
NEAR_MANOEUVRE_M = 40.0
NEAR_MANOEUVRE_MULT = 0.5
MIN_FIXES = 3
MAX_FIXES = 20
ACC_PER_FIX = 4.0
MAX_SUPPRESSION_S = 12.0
STRONG_RATIO = 2.5
OVERWHELMING_RATIO = 4.0
EARLY_FRACTION = 0.5

FIX_HZ = 1.0
DEGRADED_SHARE = 0.55


def pos_tolerance(acc, degraded):
    """RoutingHelper.getPosTolerance, exactly."""
    if acc > 0:
        legacy = POS_TOLERANCE / 2 + acc
        if not degraded:
            return min(legacy, max(MIN_POS_TOL_GOOD, GOOD_FIX_FACTOR * acc))
        return legacy
    return POS_TOLERANCE


def required_fixes(acc, dev, allow, strong_evidence):
    """CairoDriveOffRoute.requiredFixes. strong_evidence=False reproduces the old rule."""
    base = min(MAX_FIXES, max(MIN_FIXES, int(acc / ACC_PER_FIX))) if acc > 0 else MIN_FIXES
    if not strong_evidence or allow <= 0 or dev <= 0:
        return base
    ratio = dev / allow
    if ratio >= OVERWHELMING_RATIO:
        return 1
    if ratio >= STRONG_RATIO:
        return min(base, 2)
    return base


def trial(search_s, feats):
    """One wrong turn. Returns seconds from the turn to the new route being installed.

    The driver leaves the route at t=0 and separates from it at a rate set by speed
    and divergence angle - a wrong turn at a junction diverges hard, drifting onto a
    parallel service road barely at all.
    """
    speed_kmh = random.triangular(15, 60, 32)          # Cairo arterial traffic
    speed = speed_kmh / 3.6
    angle_deg = random.triangular(15, 120, 60)         # how sharply the wrong road leaves
    sep_rate = speed * abs(random.gauss(1.0, 0.15)) * (angle_deg / 90.0)
    sep_rate = max(sep_rate, 0.7)                      # m/s of orthogonal separation

    degraded = random.random() < DEGRADED_SHARE
    acc = random.triangular(18, 60, 30) if degraded else random.triangular(4, 20, 9)

    tol = pos_tolerance(acc, degraded)
    allow = tol * DEV_MULT

    # Near a manoeuvre the threshold halves (Mapbox's shipped rule). A wrong turn IS
    # at a junction, so this applies to most of them - but not to a missed motorway
    # exit noticed late, or a driver who diverges mid-block.
    near_manoeuvre = random.random() < 0.75
    if feats["near_manoeuvre"] and near_manoeuvre:
        allow *= NEAR_MANOEUVRE_MULT

    t_threshold = allow / sep_rate                     # when upstream calls it off route

    # Confirmation: consecutive off-route fixes at 1 Hz after the threshold.
    # requiredFixes is evaluated on the deviation at the moment it is asked.
    t = t_threshold
    got = 0
    need_at_start = required_fixes(acc, allow * 1.01, allow, feats["strong_evidence"])
    while True:
        t += 1.0 / FIX_HZ
        dev = sep_rate * t
        got += 1
        need = required_fixes(acc, dev, allow, feats["strong_evidence"])
        if got >= need or (t - t_threshold) > MAX_SUPPRESSION_S:
            break
    t_confirm = t

    # The search.
    if feats["early_start"]:
        t_search_begin = (allow * EARLY_FRACTION) / sep_rate
    else:
        t_search_begin = t_confirm
    t_result = t_search_begin + search_s

    # Installed when BOTH the answer exists and the hysteresis has confirmed.
    # That gate is deliberate: starting early must not mean deciding early.
    t_install = max(t_result, t_confirm)
    return t_install, t_threshold, t_confirm, need_at_start


def run(label, search_s, feats, n=40000):
    xs, thr, conf = [], [], []
    for _ in range(n):
        a, b, c, _ = trial(search_s, feats)
        xs.append(a)
        thr.append(b)
        conf.append(c)
    xs.sort()
    return {
        "label": label,
        "p50": xs[len(xs) // 2],
        "p90": xs[int(len(xs) * 0.9)],
        "mean": statistics.mean(xs),
        "threshold": statistics.mean(thr),
        "confirm": statistics.mean(conf),
    }


OLD = {"early_start": False, "strong_evidence": False, "near_manoeuvre": False}
NEW = {"early_start": True, "strong_evidence": True, "near_manoeuvre": True}

print("=" * 74)
print("WRONG TURN -> NEW ROUTE INSTALLED   (40k trials, Cairo speeds, 55/45 fix mix)")
print("=" * 74)
print()
print("A. What today's work did, at the offline search time we actually measure")
print("   %-34s %7s %7s %7s" % ("", "median", "p90", "mean"))
for s in (4.0, 6.0, 8.0):
    o = run("old", s, OLD)
    n = run("new", s, NEW)
    print("   search=%.0fs  before today            %7.1f %7.1f %7.1f"
          % (s, o["p50"], o["p90"], o["mean"]))
    print("   search=%.0fs  after today             %7.1f %7.1f %7.1f   (-%.0f%% median)"
          % (s, n["p50"], n["p90"], n["mean"], 100 * (1 - n["p50"] / o["p50"])))
print()

print("B. Where the time goes now (means, seconds from the wrong turn)")
n = run("new", 6.0, NEW)
o = run("old", 6.0, OLD)
print("   travelling far enough to be noticed at all : %.1f s  (was %.1f)"
      % (n["threshold"], o["threshold"]))
print("   ...until the deviation is CONFIRMED        : %.1f s  (was %.1f)"
      % (n["confirm"], o["confirm"]))
print("   ...until the route is installed            : %.1f s  (was %.1f)"
      % (n["mean"], o["mean"]))
print("   => the search now runs INSIDE the first two, not after them.")
print()

print("C. What a faster offline search would still buy, on top of all of the above")
print("   (this is the Cairo-only .obf question: it changes search= and nothing else)")
print("   %-22s %8s %8s %8s" % ("offline search", "median", "p90", "vs 6s"))
base = run("new", 6.0, NEW)["p50"]
for s in (8.0, 6.0, 4.0, 3.0, 2.0, 1.5, 1.0, 0.5):
    r = run("new", s, NEW)
    print("   %5.1f s              %8.1f %8.1f %8s"
          % (s, r["p50"], r["p90"], "%+.1f s" % (r["p50"] - base)))
print()
print("   Note the floor. Below ~2 s of search the median stops moving, because the")
print("   answer is then ready BEFORE the hysteresis confirms - and the install waits")
print("   for the confirmation on purpose. Past that point the search is free and the")
print("   only thing left to attack is the confirmation itself.")
print()

print("D. The online race, same model (network answers in 1-2 s)")
for p_win in (0.3, 0.5, 0.7):
    xs = []
    for _ in range(20000):
        s = random.uniform(1.0, 2.0) if random.random() < p_win else random.uniform(4.0, 8.0)
        xs.append(trial(s, NEW)[0])
    xs.sort()
    print("   network wins %2.0f%% of reroutes -> median %.1f s, p90 %.1f s"
          % (100 * p_win, xs[len(xs) // 2], xs[int(len(xs) * 0.9)]))

print()
print("E. SENSITIVITY: is the 'search no longer matters' result an artefact of my")
print("   separation-rate model? Re-run with the driver leaving the route at a range")
print("   of rates, and ask only ONE question: does search= still move the median?")
print("   %-16s %10s %10s %10s" % ("separation", "search 8s", "search 4s", "search 1s"))
for mult, name in ((0.5, "slow drift"), (1.0, "as modelled"), (2.0, "hard turn"), (3.0, "motorway exit")):
    row = []
    for s in (8.0, 4.0, 1.0):
        xs = []
        for _ in range(20000):
            sp = random.triangular(15, 60, 32) / 3.6
            ang = random.triangular(15, 120, 60)
            sep = max(sp * abs(random.gauss(1.0, 0.15)) * (ang / 90.0) * mult, 0.7)
            degraded = random.random() < DEGRADED_SHARE
            acc = random.triangular(18, 60, 30) if degraded else random.triangular(4, 20, 9)
            allow = pos_tolerance(acc, degraded) * DEV_MULT
            if random.random() < 0.75:
                allow *= NEAR_MANOEUVRE_MULT
            t_thr = allow / sep
            t, got = t_thr, 0
            while True:
                t += 1.0
                got += 1
                if got >= required_fixes(acc, sep * t, allow, True) or (t - t_thr) > MAX_SUPPRESSION_S:
                    break
            xs.append(max((allow * EARLY_FRACTION) / sep + s, t))
        xs.sort()
        row.append(xs[len(xs) // 2])
    print("   %-16s %10.1f %10.1f %10.1f" % (name, row[0], row[1], row[2]))
print("   If the three columns are the same on every row, the search is fully hidden")
print("   behind the confirmation and its speed cannot be felt.")

print()
print("F. THE ONE THING LEFT: let the road-identity detector ACT (wrongRoadAct=true).")
print("   It fires at CairoDriveWrongRoad.MIN_DEV_M=20 m with REQUIRED_RUN=2, but only")
print("   on a HEALTHY fix the map matcher has settled (settledDepth==0). That is why")
print("   coverage, not speed, is the whole question - it is silent on >half of fixes.")
print("   %-22s %9s %9s %9s" % ("matcher coverage", "median", "p90", "vs off"))
off_med = None
for cov in (0.0, 0.2, 0.35, 0.5, 0.7):
    xs = []
    for _ in range(30000):
        sp = random.triangular(15, 60, 32) / 3.6
        ang = random.triangular(15, 120, 60)
        sep = max(sp * abs(random.gauss(1.0, 0.15)) * (ang / 90.0), 0.7)
        degraded = random.random() < DEGRADED_SHARE
        acc = random.triangular(18, 60, 30) if degraded else random.triangular(4, 20, 9)
        allow = pos_tolerance(acc, degraded) * DEV_MULT
        if random.random() < 0.75:
            allow *= NEAR_MANOEUVRE_MULT
        t_thr = allow / sep
        t, got = t_thr, 0
        while True:
            t += 1.0
            got += 1
            if got >= required_fixes(acc, sep * t, allow, True) or (t - t_thr) > MAX_SUPPRESSION_S:
                break
        t_conf = t
        # The detector only helps where the matcher settles AND the fix is healthy.
        if not degraded and random.random() < cov:
            t_conf = min(t_conf, 20.0 / sep + 2.0)   # 20 m of separation + 2 matches
        xs.append(max((allow * EARLY_FRACTION) / sep + 6.0, t_conf))
    xs.sort()
    med = xs[len(xs) // 2]
    if off_med is None:
        off_med = med
    print("   %-22s %9.1f %9.1f %9s"
          % ("%.0f%% of healthy fixes" % (100 * cov), med, xs[int(len(xs) * 0.9)],
             "%+.1f s" % (med - off_med)))
