#!/usr/bin/env python3
"""Build the bundled Egypt EV-charging dataset from Open Charge Map's git export.

WHY THIS IS A BUNDLE AND NOT AN API CALL
----------------------------------------
Egypt's entire OCM dataset is 492 records. That is small enough to ship inside the APK, which
means: no API key, no quota, no rate limit, no per-tap billing, and full function with the data
saver on and no signal - in the Mokattam tunnels, on the Ring Road underpasses, anywhere the
mobile data drops. PROVIDER_FEATURES.md could not verify OCM's live rate limit at all
(openchargemap.org is blocked from that environment), and this sidesteps the question entirely
rather than shipping against an unknown.

It also fits how the rest of this app works. Everything else that matters here is offline-first;
an EV charger list that needs a network is the one that fails exactly when a driver is somewhere
unfamiliar.

WHAT IS KEPT
------------
Only what a driver decides on: where it is, what it is called, who runs it, what connectors and
how much power, whether it is currently believed operational, and - importantly for Egypt -
whether access is restricted. 66% of Egyptian stations are 'Public - Membership Required', so a
driver cannot assume walk-up access and the UI must not imply it.

Dropped: submission metadata, data-provider bookkeeping, per-connection IDs, comments, media, and
every date except the last verification. They are real fields; none of them changes where the
driver goes.

USAGE
-----
    git clone --depth 1 https://github.com/openchargemap/ocm-export /tmp/ocm
    python3 tools/cd-build-ocm-bundle.py /tmp/ocm

Writes OsmAnd/assets/cairodrive_ev_eg.json. Licence: OCM data is CC BY 4.0 - the attribution
string is written into the bundle itself so it cannot be separated from the data.
"""

import json
import os
import sys
import glob

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "OsmAnd", "assets", "cairodrive_ev_eg.json")

ATTRIBUTION = "Data (c) Open Charge Map contributors, CC BY 4.0 - openchargemap.org"

# StatusTypeIDs that mean "do not send a driver here". From OCM's referencedata: 100 Not
# Operational, 150 Planned For Future Date, 200 Removed (Decommissioned), 210 Removed (Duplicate).
DEAD_STATUS = {100, 150, 200, 210}


def load_reference(root):
    """OperatorID/ConnectionTypeID/UsageTypeID/StatusTypeID -> title."""
    path = os.path.join(root, "data", "referencedata.json")
    with open(path, encoding="utf-8") as handle:
        ref = json.load(handle)

    def index(key, id_field="ID", title_field="Title"):
        out = {}
        for row in ref.get(key, []):
            if row.get(id_field) is not None:
                out[row[id_field]] = row.get(title_field)
        return out

    return {
        "operators": index("Operators"),
        "connections": index("ConnectionTypes"),
        "usage": index("UsageTypes"),
        "status": index("StatusTypes"),
    }


def build(root):
    ref = load_reference(root)
    files = sorted(glob.glob(os.path.join(root, "data", "EG", "*.json")))
    if not files:
        raise SystemExit("no EG records found under %s - is this the ocm-export repo?" % root)

    stations = []
    skipped_dead = 0
    skipped_nolocation = 0
    for path in files:
        with open(path, encoding="utf-8") as handle:
            row = json.load(handle)

        status_id = row.get("StatusTypeID")
        if status_id in DEAD_STATUS:
            skipped_dead += 1
            continue

        address = row.get("AddressInfo") or {}
        lat = address.get("Latitude")
        lon = address.get("Longitude")
        # A charger with no coordinate cannot be shown or routed to. Guarded on None rather than
        # on falsiness: 0.0 is a legal coordinate, and `not lat` would drop it.
        if lat is None or lon is None:
            skipped_nolocation += 1
            continue

        connections = []
        for conn in row.get("Connections") or []:
            title = ref["connections"].get(conn.get("ConnectionTypeID"))
            connections.append({
                "t": title,
                "kw": conn.get("PowerKW"),
                "n": conn.get("Quantity"),
            })

        usage = ref["usage"].get(row.get("UsageTypeID")) or ""
        stations.append({
            "id": row.get("ID"),
            "lat": round(float(lat), 6),
            "lon": round(float(lon), 6),
            "name": address.get("Title"),
            "addr": address.get("AddressLine1"),
            "town": address.get("Town"),
            "op": ref["operators"].get(row.get("OperatorID")),
            "usage": usage,
            # Precomputed rather than left to the app to infer from the usage string at runtime:
            # this is the field the UI must not get wrong, and deciding it once here means one
            # place to correct if OCM's vocabulary shifts.
            "member": 1 if "membership required" in usage.lower() else 0,
            "pts": row.get("NumberOfPoints"),
            "conn": connections,
            "seen": (row.get("DateLastVerified") or "")[:10],
        })

    stations.sort(key=lambda s: (s["lat"], s["lon"]))
    bundle = {
        "attribution": ATTRIBUTION,
        "source": "openchargemap/ocm-export",
        "country": "EG",
        "count": len(stations),
        "stations": stations,
    }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as handle:
        # separators to drop the spaces json.dump would otherwise put after every ',' and ':' -
        # about 8% of the file for zero information. ensure_ascii=False keeps the Arabic street
        # names as UTF-8 rather than \uXXXX escapes, which is both smaller and readable in a diff.
        json.dump(bundle, handle, ensure_ascii=False, separators=(",", ":"))
        handle.write("\n")

    size = os.path.getsize(OUT)
    print("%d stations -> %s (%.1f KB)" % (len(stations), os.path.relpath(OUT, ROOT), size / 1024.0))
    print("  skipped: %d not operational/removed, %d without a coordinate"
          % (skipped_dead, skipped_nolocation))
    membership = sum(s["member"] for s in stations)
    print("  %d of %d (%.0f%%) require membership"
          % (membership, len(stations), 100.0 * membership / max(1, len(stations))))


if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    build(sys.argv[1])
