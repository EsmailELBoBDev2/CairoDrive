# CairoDrive — what is done, what is not, and why

Living status of the performance and reroute work. Kept in the repo on purpose: this used to
exist only inside a chat, which meant the only record of *why* something was not done could be
lost, and a later session would re-raise a settled question or re-derive a falsified one.

Update this file in the same commit that changes an item's state.

Last updated: 2026-08-04, branch `dev`.

---

## Two different problems, and they keep getting conflated

| | State |
|---|---|
| **Reroute happening over and over** (reroute after reroute while turning around) | **Fixed and shipping** |
| **A single reroute taking 4–8 s** | **Not fixed.** The next drive measures it; it does not speed it up |

Everything below is about the second one unless marked otherwise.

---

## Done

### Compiled and green (last successful build: `351d535`)

| Item | What |
|---|---|
| — | Off-route hysteresis: three compounding rules fixed + hard 12 s timeout. Six fix patterns simulated before the default was flipped on |
| — | ETA learns from real driving including stops. Mean absolute error down 21% (32% on median) over 76 samples |
| **P3** | Android Auto first frame fires on `MAPS_INITIALIZED` instead of the whole init chain (`INDEX_REGION_BOUNDARIES` alone measured 1150–1300 ms) |
| **P7** | Hardware canvas behind `CAIRODRIVE_HW_CANVAS`, default on |
| **P8** | Track recording: one connection + WAL + `synchronous=NORMAL` instead of open/close per point |
| **D2** | Mobile-data consent time-boxed to 10 minutes instead of process-lifetime |
| **N1** | GNSS health recorded as `gnss=OK|DEGRADED` (55% of the 2026-08-04 drive's fixes had `satsUsed=0` while claiming 2.1 m accuracy) |
| — | Security: AIDL receiver not exported; `ContextCompat.registerReceiver` closes the API 24–32 hole; notification receivers un-exported with `setPackage()` |
| — | `ArabicNormalizer` `charAt(0)` bug — `٢٦ يوليو` now matches `26 يوليو`, verified by codepoint |
| — | Estedad (Naskh) added to the asset manifest. Nastaliq was the only Arabic-script font and it is the wrong style for Egyptian signage |
| — | `CD_LAYER` per-layer frame timing |
| — | Tile downloads gated on metered connections, with `CD_DATA` so the gate is never silent |

### Written and pushed, but **never compile-checked**

Every CI run from `ad816c0` onward was cancelled by concurrency — commits landed every 5–14
minutes while a build takes ~19. The last conclusive result predates all of this.

**`CairoDriveCarPresentation` calls into `net.osmand.core.android.AtlasMapRendererView`, a
prebuilt binding whose source is not in this repo. CI is the only place its API shape can be
verified. A compile failure there is the expected failure mode.**

| Item | What |
|---|---|
| **B1** | VirtualDisplay + `Presentation`. Same view stack the phone uses, on a display backed by the car surface. Falls back to offscreen-and-blit on any failure, latched, reason in `CD_PRESENT` |
| — | Head unit keeps the manoeuvre card during a reroute instead of blanking to a spinner |
| — | `pre=` — times the pre-search block (missing-maps check, private-access probe, region lookup) |
| — | `repairProbe` — shadow-times a 600 m repair search and discards the result |
| — | Private-access probe skipped on reroutes |
| — | `CD_REROUTE` — dropped requests, dispatch, and total dispatch→result span |
| — | `SESSION` header records `hwCanvas` and `presentation`; `CD_ITINERARY` routed to the file instead of logcat |

---

## Not done — and why

| # | Item | Why not |
|---|---|---|
| 4 | **Partial repair** — route 600 m to a rejoin point on the old route and splice the tail, instead of a full search to the destination | **The big one, if it works.** Blocked on `repairProbe`: the whole technique assumes a short route is proportionally cheaper on this hardware, and it might not be — HH's cost is dominated by loading and searching the network around each endpoint. If that fixed cost dominates, a 600 m repair costs nearly what an 8 km search does and the idea is worthless here. Second reason to be slow: a bad splice produces a route that **looks correct on the map and gives the wrong turn instruction** |
| 5 | Cut the duplicated region queries (~4 per calculation over essentially two points, each a synchronized binary search over `regions.ocbf` plus a polygon ray-cast, because the quad-tree is never built on Android) | Waiting on `pre=` to say whether the cost is actually there. Small either way |
| 6 | Speculative precomputation at junctions — compute the alternative before the driver misses the turn | Depends on 4. A full 8 km search per junction is ~13% duty cycle on one of this phone's two big cores, continuously, on a device already at 46.9 ms/frame. Only affordable if repairs are cheap |
| 7 | Enable the alternatives switch that already exists in the tree but is off (`HHRoutingConfig.CALC_ALTERNATIVES`) | Would be a third render/routing variable in a drive already testing B1 and the card change. One at a time |
| — | Raise the car frame cap 20 → 30 | **Done on the B1 path only.** On the offscreen path a frame costs 46.9 ms, so a 33 ms budget cannot be met — asking for more frames queues work that cannot be delivered. The cap was never the limiter there |

## Dropped

| Item | Why |
|---|---|
| **D1** — resume a download after the app is closed | **Architecturally blocked.** `.obf` maps arrive as zip streams: the bytes downloaded are compressed, the bytes on disk are decompressed, and there is no way to map one to the other, so no byte offset can be requested. `ZipInputStream`'s inflater state cannot be restored mid-stream either. **Resume *within* a download already exists** — HTTP `Range`, 15 retries, 8 s apart |
| **B4** — OBD | Owner's decision. Verified absent from the tree |
| Lowering `recalculateDistance` from 20 km on its own | Only affects the `BinaryRoutePlanner` path, which the HH branch pre-empts. Recorded so nobody rediscovers it |

---

## Settled — do not re-open

- **Route calculation speed itself.** Six hypotheses were measured on-device and all six were
  wrong: cold start, warming the routing context, the native memory cap, this fork's priority
  rules, a stale map, and a silent A* fallback. Do not propose these again.
- **Google Places key**: daily quota cap set; rotation declined; SHA-1 restriction left as is.
- **Online OSM routing**: recommended against — needs network per reroute, and OSRM/GraphHopper
  read the same `maxspeed` tags that make the offline estimate what it is.

---

## Why every deviation is a full search (verified, three independent reasons)

OsmAnd *has* a repair mechanism, `RoutePlannerFrontEnd.getRecalculationEnd`. It is dead here:

1. `RoutePlannerFrontEnd:460` — the HH C++ branch passes a hardcoded `null` and returns before
   the splice code at `:494`/`:733`. Drive logs show `fast=SUCCESS`, so this is the live branch.
2. `RoutingConfiguration:63` — `recalculateDistance = 20000f`. Only route beyond 20 km is reused,
   so an 8 km Cairo route never qualifies even on the fallback path.
3. `RouteProvider:708-714` slices a segment-indexed list with a location index — an upstream
   index-space bug, so the tail it would reuse is wrong anyway. Inert today; fix separately.

Upstream agrees: [osmandapp/OsmAnd#19737](https://github.com/osmandapp/OsmAnd/issues/19737), open,
says smart recalculation "could be near instantaneous" with HH.

HERE ships the technique as `returnToRoute()` (documented as avoiding "a costly route
recalculation", and available on its **offline** engine); TomTom as continuous replanning with a
1 km cutoff that doubles on repeated deviation. Every open-source engine checked — Valhalla,
OSRM, GraphHopper, Mapbox — does a full recompute.

---

## What the next drive decides

Read these together, for the same deviation:

| Line | Field | Decides |
|---|---|---|
| `CD_REROUTE repairProbe` | `repairMs` vs `CD_ROUTE_TIMING search` | Item 4. A 600 m repair that is a small fraction of the full search justifies building it; one that is close to it kills the plan |
| `CD_ROUTE_TIMING` | `pre=` | Item 5, and where the unattributed ~80% of a search actually goes |
| `CD_ROUTE_TIMING` | `find=` | **Must be 0.00** on `engine=hh-cpp fast=SUCCESS`. If not, the reading above is wrong |
| `CD_ROUTE_TIMING` | `skipPriv=1` on reroutes | That the private-access skip actually took effect (it is set on two contexts; the second silently not being set would make it a no-op) |
| `CD_PRESENT` / `CD_FRAME` | `renderMode=`, `hwAccel=`, `avgOver` vs 25.9, `avgMs` vs 46.9 | Whether B1 works |
| `CD_TRIP` | present at all | Whether the head unit accepted the populated card |
| `CD_REROUTE dropped` | `evalWaitMs` | How often a reroute was silently refused. That interval grows ×1.5 up to 120 s and nothing retries |
