#!/usr/bin/env python3
"""Ground-truth a drive against OSM, off-device, using Stadia's Valhalla map matching.

WHAT THIS ANSWERS
-----------------
CD_NARROW fires on a name pattern (حارة / زقاق / درب / ممر / عطفة) because the router cannot read
names and the width tags are largely absent - about 2.5% of Cairo ways carry one against ~16.6%
carrying an alley name. That gap is the whole reason the signal exists, and it means the signal
has never been checked against what the roads actually ARE.

This checks it. Feed it the GPS trace from a drive log; it snaps the trace to the OSM network and
returns, per matched edge: road class, lane count, surface, speed limit, way id and name. Then it
reports how the app's narrow-street calls line up with them.

WHY IT RUNS HERE AND NOT ON THE PHONE
-------------------------------------
Zero runtime cost is the point. No frame budget - `over` is already 61% of a frame - no battery,
no metered bandwidth, no key shipped in an APK. It runs on the laptop beside cd-analyze.py, after
the drive, on a log that already exists.

WHAT IT CANNOT DO
-----------------
It reports what OSM CONTAINS. In Cairo backstreets it will largely confirm the coverage gap rather
than fill it: an alley with no width tag matched to an edge with no width tag tells you the tag is
missing, not how wide the street is. That is still the measurement worth having - CLAUDE.md says
to quantify before optimising, and this quantifies precisely how much of the signal OSM can
corroborate.

USAGE
-----
    export CAIRODRIVE_STADIA_KEY=...          # never written into this repo
    adb pull /sdcard/Android/data/com.cairodrive.app/files/cairodrive-logs ./cd-logs
    python3 tools/cd-stadia-trace.py cd-logs/cairodrive-*.log

Add --dry-run to see how many points would be sent, and what it would cost in requests, without
sending anything.
"""

import argparse
import json
import os
import re
import sys
import urllib.request

TRACE_URL = "https://api.stadiamaps.com/trace_attributes/v1"

# Valhalla caps a single shape at 16k points, but the practical limit is the response size and the
# credit cost. Chunking at 500 keeps each request small and lets a long drive be sampled rather
# than sent whole.
CHUNK = 500

# Road classes Valhalla reports that plausibly correspond to what this app calls a narrow street.
# `service_other` is the catch-all that Cairo alleys usually land in when they are mapped at all.
NARROW_CLASSES = {"residential", "service_other", "unclassified"}

# A CD_ line carrying a position. Matches the logger's `lat=.. lon=..` convention without assuming
# which tag it sits on, so a trace can be reconstructed from whichever lines a given build wrote.
POINT_RE = re.compile(r"\blat=(-?\d+\.\d+)\s+lon=(-?\d+\.\d+)")
NARROW_RE = re.compile(r"CD_NARROW\b(.*)$")


def read_trace(paths):
    """Every distinct fix in the logs, in order, plus the CD_NARROW lines."""
    points = []
    narrow_lines = []
    last = None
    for path in paths:
        with open(path, encoding="utf-8", errors="replace") as handle:
            for line in handle:
                match = POINT_RE.search(line)
                if match:
                    point = (float(match.group(1)), float(match.group(2)))
                    # Consecutive duplicates are a parked car, and sending 400 identical points
                    # buys nothing but credits.
                    if point != last:
                        points.append(point)
                        last = point
                narrow = NARROW_RE.search(line)
                if narrow:
                    narrow_lines.append(narrow.group(1).strip())
    return points, narrow_lines


def chunks(points, size):
    for i in range(0, len(points), size):
        yield points[i:i + size]


def match_chunk(key, points):
    body = {
        "shape": [{"lat": lat, "lon": lon} for lat, lon in points],
        "costing": "auto",
        "shape_match": "map_snap",
        "filters": {
            "attributes": [
                "edge.way_id", "edge.names", "edge.road_class", "edge.lane_count",
                "edge.surface", "edge.unpaved", "edge.speed_limit", "edge.length",
            ],
            "action": "include",
        },
    }
    request = urllib.request.Request(
        TRACE_URL + "?api_key=" + key,
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.loads(response.read().decode("utf-8"))


def summarise(edges, narrow_lines):
    by_class = {}
    lane_known = 0
    surface_known = 0
    unpaved = 0
    way_ids = set()
    for edge in edges:
        road_class = edge.get("road_class") or "unknown"
        by_class[road_class] = by_class.get(road_class, 0) + 1
        if edge.get("lane_count") is not None:
            lane_known += 1
        if edge.get("surface"):
            surface_known += 1
        if edge.get("unpaved"):
            unpaved += 1
        if edge.get("way_id") is not None:
            way_ids.add(edge["way_id"])

    total = max(1, len(edges))
    print("\n=== matched edges: %d (%d distinct OSM ways) ===" % (len(edges), len(way_ids)))
    print("\nroad class:")
    for road_class, count in sorted(by_class.items(), key=lambda kv: -kv[1]):
        mark = "  <- narrow-ish" if road_class in NARROW_CLASSES else ""
        print("  %-16s %5d  %5.1f%%%s" % (road_class, count, 100.0 * count / total, mark))

    narrow_edges = sum(c for k, c in by_class.items() if k in NARROW_CLASSES)
    print("\nattribute coverage on the roads actually driven:")
    print("  lane_count present   %5d / %d  (%.1f%%)" % (lane_known, len(edges), 100.0 * lane_known / total))
    print("  surface present      %5d / %d  (%.1f%%)" % (surface_known, len(edges), 100.0 * surface_known / total))
    print("  tagged unpaved       %5d / %d  (%.1f%%)" % (unpaved, len(edges), 100.0 * unpaved / total))
    print("\n  narrow-class edges   %5d / %d  (%.1f%%)" % (narrow_edges, len(edges), 100.0 * narrow_edges / total))
    print("  CD_NARROW log lines  %5d" % len(narrow_lines))
    print("""
Read it like this: `narrow-class edges` is what OSM says about the roads this drive actually
used. If CD_NARROW fired far more often than that, the name heuristic is over-firing on streets
OSM classes as ordinary. If it fired far less, the alley-name patterns are missing streets that
are genuinely minor. And low lane_count/surface coverage is the coverage gap itself, measured on
the roads this driver uses rather than city-wide - which is the number worth tracking per drive.
""")


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("logs", nargs="+", help="cairodrive-*.log files")
    parser.add_argument("--dry-run", action="store_true",
                        help="report what would be sent, send nothing")
    parser.add_argument("--max-points", type=int, default=4000,
                        help="cap on points sent; the trace is decimated to fit (default 4000)")
    args = parser.parse_args()

    points, narrow_lines = read_trace(args.logs)
    if not points:
        raise SystemExit("no lat=/lon= fixes found in those logs")

    # Decimate rather than truncate: half a drive matched is a biased sample of the drive, where an
    # evenly thinned trace still covers the whole route. Valhalla snaps fine at a coarser spacing.
    if len(points) > args.max_points:
        step = (len(points) + args.max_points - 1) // args.max_points
        points = points[::step]
        print("decimated to every %dth fix -> %d points" % (step, len(points)))

    request_count = (len(points) + CHUNK - 1) // CHUNK
    print("%d points, %d CD_NARROW lines, %d request(s) of up to %d points"
          % (len(points), len(narrow_lines), request_count, CHUNK))

    if args.dry_run:
        print("dry run - nothing sent")
        return 0

    key = os.environ.get("CAIRODRIVE_STADIA_KEY", "").strip()
    if not key:
        raise SystemExit("CAIRODRIVE_STADIA_KEY is not set. Export it; do not put it in a file.")

    edges = []
    for i, chunk in enumerate(chunks(points, CHUNK), 1):
        try:
            result = match_chunk(key, chunk)
        except Exception as exc:
            # One bad chunk must not lose the whole drive - a GPS gap can produce a stretch
            # Valhalla refuses to snap, and the rest of the trace is still worth reporting.
            print("  chunk %d/%d failed: %s" % (i, request_count, exc), file=sys.stderr)
            continue
        edges.extend(result.get("edges") or [])
        print("  chunk %d/%d -> %d edges" % (i, request_count, len(result.get("edges") or [])))

    if not edges:
        raise SystemExit("no edges matched - check the key and that the trace is on a road network")
    summarise(edges, narrow_lines)
    return 0


if __name__ == "__main__":
    sys.exit(main())
