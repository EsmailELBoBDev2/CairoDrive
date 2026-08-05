#!/usr/bin/env python3
"""
Is LOCATION_INTERPOLATION_PERCENT=50 the right default for Cairo?

The prediction in RoutingHelperUtils.predictLocations projects the marker forward along the route
by:

    remainingDistance = ((speedPrev + speedNew) / 2) * dt * (pct / 100)

The displayed marker is therefore at (last fix + remainingDistance). The car's TRUE position when
the next fix lands is (last fix + distance actually covered during the next interval).

    error(pct) = pct/100 * avgSpeed*dt  -  actualNextIntervalDistance

pct=0 reproduces upstream: error = -actualDistance, i.e. the arrow is a full interval BEHIND.
Positive error is overshoot - the arrow ahead of the car, which is the failure mode that matters
at a junction. So the two directions are not equally bad and are reported separately.
"""

DT = 1.0  # 1 Hz, what the hardware actually delivers


def cairo_cycle():
    """Speed (m/s) sampled at 1 Hz over a stop-go Cairo minute.

    Built from segments rather than a formula so the shape is inspectable: cruise, hard decel to
    a light, idle, accel away, a crawl in traffic, a second decel.
    """
    v = []
    v += [16.7] * 15                                    # 60 km/h cruise
    v += [16.7 - 2.5 * t for t in range(1, 7)]          # brake 2.5 m/s^2 to ~1.7
    v += [0.0] * 8                                      # stopped at the light
    v += [min(16.7, 1.8 * t) for t in range(1, 10)]     # pull away
    v += [8.3] * 10                                     # 30 km/h in traffic
    v += [8.3 - 2.0 * t for t in range(1, 5)]           # brake again
    v += [0.0] * 5
    return [max(0.0, x) for x in v]


def evaluate(speeds, pct):
    errs = []
    for i in range(1, len(speeds) - 1):
        avg = (speeds[i - 1] + speeds[i]) / 2.0
        predicted = avg * DT * (pct / 100.0)
        # Distance actually covered over the NEXT interval, trapezoid on the sampled speeds.
        actual = (speeds[i] + speeds[i + 1]) / 2.0 * DT
        errs.append(predicted - actual)
    over = [e for e in errs if e > 0]
    behind = [-e for e in errs if e < 0]
    return {
        "mae": sum(abs(e) for e in errs) / len(errs),
        "max_over": max(over) if over else 0.0,
        "max_behind": max(behind) if behind else 0.0,
        "mean_behind": sum(behind) / len(behind) if behind else 0.0,
        "pct_over": 100.0 * len(over) / len(errs),
    }


speeds = cairo_cycle()
print("Cairo stop-go cycle: %d fixes at %.0f Hz, mean %.1f km/h, max %.0f km/h\n"
      % (len(speeds), 1 / DT, sum(speeds) / len(speeds) * 3.6, max(speeds) * 3.6))
print("  pct |   MAE  | worst ahead | worst behind | mean behind | %% of time ahead")
print("  ----+--------+-------------+--------------+-------------+----------------")
rows = {}
for pct in (0, 25, 50, 75, 100):
    r = evaluate(speeds, pct)
    rows[pct] = r
    print("  %3d | %5.2fm |    %5.2fm   |    %5.2fm    |    %5.2fm   |   %5.1f%%"
          % (pct, r["mae"], r["max_over"], r["max_behind"], r["mean_behind"], r["pct_over"]))

base = rows[0]["mae"]
print("\nvs upstream (pct=0):")
for pct in (25, 50, 75, 100):
    print("  %3d: MAE %+.0f%%   worst-ahead %.2fm"
          % (pct, 100 * (rows[pct]["mae"] - base) / base, rows[pct]["max_over"]))

# A steady-cruise sanity check: prediction should be nearly exact when speed is constant.
steady = [16.7] * 40
print("\nsteady 60 km/h cruise (no accel): MAE at pct=100 is %.3fm, at pct=50 is %.2fm"
      % (evaluate(steady, 100)["mae"], evaluate(steady, 50)["mae"]))

# ---------------------------------------------------------------------------------------
# The above assumes the reported speed IS the truth. It is not: GNSS Doppler speed carries
# roughly 0.3-0.7 m/s of noise, and prediction multiplies that noise straight into position -
# at pct=100, one-for-one. That is the real argument against 100, and it is not in the model
# above, so the model above is not yet a basis for choosing.
import random

def evaluate_noisy(true_speeds, pct, sigma, trials=400, seed=12345):
    rnd = random.Random(seed)
    mae, over = 0.0, 0.0
    n = 0
    for _ in range(trials):
        meas = [max(0.0, v + rnd.gauss(0, sigma)) for v in true_speeds]
        for i in range(1, len(true_speeds) - 1):
            avg = (meas[i - 1] + meas[i]) / 2.0          # predicted from MEASURED speed
            predicted = avg * DT * (pct / 100.0)
            actual = (true_speeds[i] + true_speeds[i + 1]) / 2.0 * DT   # against TRUTH
            e = predicted - actual
            mae += abs(e)
            over = max(over, e)
            n += 1
    return mae / n, over

print("\n" + "=" * 74)
print("with GNSS speed noise (prediction amplifies it 1:1 at pct=100)")
for sigma in (0.3, 0.5, 0.7):
    print("\n  sigma=%.1f m/s" % sigma)
    print("    pct |  MAE   | worst ahead")
    best, bestp = None, None
    for pct in (0, 25, 50, 60, 75, 90, 100):
        m, o = evaluate_noisy(cairo_cycle(), pct, sigma)
        flag = ""
        if best is None or m < best:
            best, bestp = m, pct
        print("    %3d | %5.2fm |   %5.2fm%s" % (pct, m, o, flag))
    print("    -> lowest MAE at pct=%d" % bestp)

print("\nsteady cruise WITH noise (sigma=0.5), where most driving happens:")
for pct in (50, 75, 100):
    m, o = evaluate_noisy([16.7] * 40, pct, 0.5)
    print("  pct=%3d  MAE %.2fm  worst ahead %.2fm" % (pct, m, o))
