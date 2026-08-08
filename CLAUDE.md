# CairoDrive — standing instructions

Fork-specific. `AGENTS.md` is upstream OsmAnd's and is left alone so syncs stay clean.

---

## When a drive log arrives, analyse it WITHOUT being asked

The owner drives this daily in Cairo with Android Auto. A log is the only source of truth this
project has, and it costs him a real trip to produce. **Whenever he shares one, mentions a drive,
or pulls `cairodrive-logs`, run the full analysis unprompted and report all of it.** Do not wait
to be asked which part to look at.

Logs land in `/sdcard/Android/data/com.cairodrive.app/files/cairodrive-logs/cairodrive-*.log`
(app-scoped external storage: no root, no `run-as`). A Play *update* does not clear them, so old
and new runs mix unless they were deleted first.

There is an analyser — recreate it if absent, it is ~180 lines of Python:

```bash
adb pull /sdcard/Android/data/com.cairodrive.app/files/cairodrive-logs ./cd-logs
python3 cd-analyze.py cd-logs/*.log
```

### 1. `CD_FRAME` — the Android Auto frame cost

Summary lines carry six buckets plus the settings that produced them:

```
overscan=  renderScale=  avgLock=  avgRead=  avgBlit=  avgOver=  avgWidget=  avgPost=
```

**The number that matters is `read+blit` as a percentage of the total.** It decides a
multi-day question, so compute it every time:

| Dominant bucket | Meaning | Action |
|---|---|---|
| `read` / `blit` | GPU readback + a **software** blit (a `lockCanvas` canvas is never hardware accelerated) | 1. Hours: rebuild with `CAIRODRIVE_RENDER_SCALE=0.75` → ~44% fewer pixels through both. 2. ~3–5 days: VirtualDisplay + `Presentation` removes both steps outright |
| `lock` / `post` | The head unit is not taking frames back | **Stop.** No app-side change helps — not scaling, not the rewrite |
| `over` | OsmAnd's own Java drawing — `mapView.drawOverMap`, i.e. the ~23 map layers | **This is what the 2026-08-04 drive measured: 25.9 ms, 61% of a 46.9 ms frame.** Read `CD_LAYER`, which names the worst layers per 200-frame window. Do **not** reach for the nine deferred perf findings — they were measured statically at **0.2–0.4% of a frame combined** and are not the explanation. Note also that `over` is drawn onto the same canvas as `blit`, so on the offscreen path both are paid in software |
| `wdgt` | Speedometer/alarm widgets | Already gated to rebuild only on a new fix; if still large the cost is drawing, not computing |

`wdgt` exists because the timing mark used to sit *before* the widget callback, so that work
landed in `post` — the bucket whose meaning is "unfixable, blame the head unit". Never merge it
back.

### 2. `CD_ROUTE_TIMING` — check this first, it is a regression detector

**Route calculation speed is SETTLED — do not re-investigate it.** An 8 km Cairo route takes
4-8 s on the POCO C85 and `fast=SUCCESS`, meaning the Highway-Hierarchy fast path with the
`.obf`'s precomputed shortcuts is working. That is simply the cost on this hardware. Six
hypotheses were measured on-device and all six were wrong: cold start, warming the routing
context, the native memory cap (256 vs 1024 - no difference), this fork's 31 priority rules
(gated by `avoid_narrow_streets`, and disabling them made it *slower*), a stale map (it was
current), and a silent A* fallback (there is none).

**One of those six has a hole, and it is the cheapest open experiment in the project.** The rules
test disabled the RULES in `routing.xml` while the PARAMETER stayed set. Those are different
tests. `filterPointsBasedOnConfiguration` (`hhRoutePlanner.cpp:1431-1512`) runs on EVERY query and
sweeps EVERY point in the region calling `router->acceptLine()` - but only when the parameter map
is non-empty, and `RouteProvider:869-876` puts a boolean in only when it is TRUE. So disabling the
rules kept the sweep AND lost the route quality, which is a plausible mechanism for "slower".
Clearing the PARAMETER is what empties the map and skips the sweep entirely. That has never been
measured. It is a settings toggle, reversible in the UI, and it costs one drive.

So there are **TWO** fixed per-query costs stacked, not one: the HH index read, and this
parameter sweep. `CD_ROUTE_PHASE` bills both to `LOAD_POINTS` - the next phase mark is not until
after the filter - so they cannot be told apart from a single log. Toggling the parameter across
two drives is what separates them.

If the sweep IS the cost, the fix is not to abandon `avoid_narrow_streets`: OsmAnd's
`generate-hh-routing.sh` takes `--routing_params`, so the parameter can be BAKED into a custom
map's precomputed shortcuts, which also clears `unsupportedParams`.

**`routingTime` is NOT a duration and the "15-20% of search" note it produced was a unit error.**
`ctx.routingTime` is the accumulated ROUTING COST - the route's estimated DRIVING time in seconds -
printed raw while `setup/search/pre/find/load` all go through `ms(nanos)`. `RoutingHelper:375`
prints the same value as `+ " sec"`. Seconds-of-travel over milliseconds-of-work lands near 15-20%
on any route, forever, for arithmetic reasons. The field is now named **`routeCostSec=`**. That
old note sent one whole investigation down the wrong path; do not resurrect it.

What IS established (four agents, 2026-08-06, code-verified against the pinned
`CORE_LEGACY_REF`): the cost is **fixed per query, not per metre**. `initHHPoints` /
`readPointBox` read **every** Highway-Hierarchy point in the selected region on every single
calculation - there is no spatial pruning, the road quad-tree prunes by bbox and the HH point tree
is the one descent that does not - and it is **~9 O(N) passes**, not one: allocate+parse+insert,
region merge, `markSegmentsNotLoaded`, `groupByClusters` x2 with sorts, spatial-index fill,
`rtExclude` reset, `tagValues` reset, and a per-point `router->acceptLine()` rule evaluation.
Nothing caches it between calls. `CD_HHLOAD pts= segs= loadMs=` prices it, and `loadMs` is a LOWER
BOUND - the `acceptLine` pass is billed after the line is printed.

### `engine=` LIES about a missing HH index - read `fast=`

The old note here said `engine=java` means `libosmand.so` did not load (**6.8 s average, 39 s
worst**, measured). That is still true and still worth saying loudly. **But it is not the failure
to look for**, because `engine=` is computed from `router.isHHRoutingConfigured()`
(`RouteProvider:1244`) and stays `hh-cpp` even when the HH index is absent entirely.

A map with no HH section - which is what a self-built OsmAndMapCreator extract usually is - gives
`selectBestRoutingFiles` no group, so routing silently falls through to the native C++ A*. It
reads **`engine=hh-cpp fast=FAILED_NO_HH_ROUTING_DATA`**. `fast=` is the ONLY tell.

C++ HH search times are ~200-400 ms; the seconds are the load, not the search.

### `fast=FAILED_UNSUPPORTED_PARAMETERS` fires on EVERY calculation — read `badParams=`, do not guess

The 2026-08-07 drive got it on all **69** calculations, so the HH fast path is refused every single
time and every route on that drive was produced the slow way. The status names no parameter, and
the guess recorded here was `avoid_narrow_streets`. **A guess is not an answer and it has already
cost this project six wrong hypotheses on this exact router.**

`CD_ROUTE_TIMING` now carries **`badParams=`**, which names it. It mirrors
`HHRoutePlanner.matchGroupRoutingParams` rather than approximating it — a parameter counts only when
it is absent from the map's declared `profileParams` **and** differs from its own declared default,
with `allow_private` exempt exactly as `IGNORE_FAILED_UNSUPPORTED_PARAMETERS` exempts it — and it
reduces per **group**, because each entry of a region's `profileParams` forms its own candidate
group and only the ranked best group's count sets the flag. A union across groups would name
parameters the chosen group supports.

Three readings, three different conclusions:

| `badParams=` | Means | Next move |
|---|---|---|
| a parameter name | That is the culprit, measured | Bake it into a custom map with `generate-hh-routing.sh --routing_params`, or clear the setting |
| `none` | No non-default parameter is unsupported. **The `avoid_narrow_streets` explanation above is wrong** | Do not repeat it. The cause is elsewhere — start from `matchGroupRoutingParams` on the C++ side |
| `noHHIndex` | No HH section for this profile at all | A map problem wearing a parameter problem's status. Same fix as `FAILED_NO_HH_ROUTING_DATA` |

`cd-analyze.py` prints the breakdown and says plainly when the reading contradicts this file.

### `FAILED_UNSUPPORTED_PARAMETERS` is a LABEL, not a cause — and `badParams=short_way`

Measured 2026-08-07 20:31, first drive with `badParams=`: the answer is **`short_way`**, not
`avoid_narrow_streets`. `short_way` enters the parameter map only when `FAST_ROUTE_MODE` is FALSE
(`RouteProvider.collectRoutingParameters`), so the car profile is set to **Shortest route** rather
than Fastest, and no map's HH index is built for that cost model. It is one toggle in the UI, and
in Cairo "shortest" is also the setting most likely to send the router down the alleys this fork
exists to avoid.

**But do not now claim `short_way` caused the 69 failures on the earlier drive.** Read
`FastRoutingState.fail()`: when the HH search fails it picks between `FAILED_UNSUPPORTED_PARAMETERS`
and `FAILED_NEED_MORE_LAND_MAPS` purely on whether ANY non-default parameter is unsupported. The
status is the label on a failure, not its mechanism. The mechanism is at `HHRoutePlanner:233`
("No finalPnt found — points might be filtered by params") or `:261` (too many recalculations).
This drive proves the point: `badParams=short_way` on both calculations and `fast=SUCCESS` anyway.

### The crash, the black map, the freeze and the ANR were ONE bug — 2026-08-08

`SIGSEGV fault addr 0x10` in `cairodrive-route-race`, twice. Around it: **147 online wins and 0
offline wins** across two logs, so essentially every reroute left a native HH search running that
nothing could stop, and Cairo reroutes come seconds apart. Measured in one session: **45 distinct
threads inside native routing**, 89 offline legs completing against 52 races, single searches
stretched to **63 s** by their own contention, and `CD_FRAME avgMs=711–1370 ms, maxMs=22759,
slow=200/200` — roughly **1 fps**, with `avgOver` only 27–203 ms, so it was CPU starvation and not
the layers.

The cause was a note in this file's own history: the abandon callback was removed on 2026-08-07
because setting `isCancelled` discarded the winning route, and the replacement comment claimed
letting the search run was "the cheaper failure by a very wide margin". **It is not, and that
sentence cost a drive.**

The fix is that cancellation and supersession are now separate signals:

| flag | meaning | set by |
|---|---|---|
| `calculationProgress.isCancelled` | stop working — polled by the native search | `stopCalculation()` **and** the race, when online wins |
| `RouteCalculationParams.cairoDriveSuperseded` | your answer is unwanted | `stopCalculation()` only |

`RouteRecalculationTask.run` reads **`cairoDriveSuperseded`**. Anything that wants a result thrown
away must set that; setting `isCancelled` alone stops the search and keeps whatever the race won.

### The map matcher could never see a road, and the reason is the native library

`CD_MATCH raw=0 accepted=0` on 700+ fixes, `matched=0` for two entire drives, parked in mapped
Cairo. `RoutingContext.loadTileData` → `loadSubregionTile` takes the `nativeLib.loadRouteRegion`
branch when a native library is attached, and `setLoadedNative` leaves `routes` null whenever that
call returns a handle rather than materialised objects — after which `loadAllObjects` adds nothing.
The matcher's context is built by `getRoutingEnvironment`, which took the native library like every
other caller. It now passes `javaTilesOnly=true` (`RouteCalculationParams.cairoDriveJavaTilesOnly`).
**Only the matcher may set that** — the router wants the native path, that is the hh-cpp engine.

Second, smaller fault beside it: `ObfRoadSource` cached the EMPTY result and reused it for 80 m,
so 669 of 717 `noCandidate` lines read `cache=1` and never retried. Failures are no longer cached.

### The online route is winning every race, and it is a worse route

**147–0.** The design says offline wins whenever it is ready because it carries this fork's Cairo
tuning; in practice online answers in 150–400 ms and offline in seconds, so online always wins. An
online route has no `RouteSegmentResult`, and three of the owner's 2026-08-08 complaints are that
one fact: the drawn line follows ORS geometry rather than the OSM roads ("الخط في شارعين"), the
prompts lose the street name, and `CD_WRONGROAD` is inert. **This is a product trade-off, not a
bug: fast generic route vs slower correct route.** Do not silently change the balance — ask.

### `turnLead=SKIP reason=missed_window` is why the street name goes missing

20 occurrences across the two logs. Arabic TTS measured at **~97 ms/char**, so a full prompt of
80–104 characters takes **6.7–8.4 seconds** to speak. When the window between "turn in" and "turn
now" is shorter than that, the full prompt is skipped and only the short cue is spoken — which is
the owner's "بتقول على و خلاص". The fix is not more lead time on its own; it is a SHORTER Arabic
prompt when `estFullMs` will not fit.

### The reroute is answered: 279–947 ms, measured

`CD_ROUTE_RACE ONLINE won in 279 ms` / `947 ms`, `CD_REROUTE finished ... calculated=true`,
dispatched=2 finished=2 dropped=0. The offline leg ran on for another ~7 s and was discarded. The
felt reroute is now under a second and the 8.5 s / 7.9 s simulation figures are the OFFLINE-only
floor, not what the driver experiences with a signal.

The gate `params.cairoDriveDispatchedAt > 0` reads as if it excluded the initial route. It does not:
`recalculateRouteInBackground` stamps it for the FIRST calculation as well as for reroutes —
`previousToRecalculate` being null is what makes `reroute=0`, not a different code path. Both
calculations in this log are `reroute=0` and both raced. So the first route the driver waits for is
also sub-second. What the gate actually excludes is the traffic-detour poll and the repair probe,
which is what it was written for.

One consequence to keep:

- **`CD_WRONGROAD` reads `route has no segments - inert for this route` after every online win**, and
  that is structural: an online route is Locations plus directions with no `RouteSegmentResult`, so
  road identity cannot be read. The faster the online side wins, the more often
  `CAIRODRIVE_WRONG_ROAD_ACT` is dead. The two features are in tension; judge neither by the other's
  drive.

### The wrong-direction trigger had no speed floor

`checkWrongMovementDirection` needs only `hasBearing()`, and the trigger that consumes it has **no
hysteresis at all** — one fix reroutes. Parked at 30.08706/31.26917 the log recorded `wrongDir=true`
at **0.9, 2.0 and 4.3 km/h** from fused-provider jitter. Nothing rerouted only because the next route
node happened to be 2–7 m away, and that distance is routinely 40–150 m on sparse OSM geometry.
Stop-and-go Cairo traffic plus sparse geometry is a reroute on a driver who has not moved. Gated at
`WRONG_DIRECTION_MIN_SPEED_MS = 2.5` m/s, the same reasoning as
`CairoDriveMapMatching.HEADING_MIN_SPEED_MS`.

### The four providers were dead on arrival, in every build — fixed 2026-08-07

`CairoDriveProviders.install` ran in `OsmandApplication.onCreate` **before**
`settings = appCustomization.getOsmandSettings()`. Every provider's `isAvailable` reads a runtime
preference (`TOMTOM_TRAFFIC_ON`, `WEATHER_HAZARD_ON`), so all three threw NPE into `arbitrate`'s
catch and were recorded unavailable — on every cold start this fork has ever had:

```
CD_PROVIDERS: flow=none incidents=none hazard=none glare=none
CD_TRAFFIC:   available but serving neither capability - no polls will be made
```

TomTom traffic, OpenWeather hazards and sun glare have never run once. Nothing retried, because
`install`'s javadoc asserted availability was decided by BuildConfig constants and therefore could
not change — true of the keys, false of the preferences gating them. **The lesson generalises past
this one call: a comment asserting an invariant is not the same as the invariant holding, and three
"treating as unavailable" lines are what a silent feature looks like in a log.** Read `CD_PROVIDERS`
before believing anything about traffic, weather or glare.

### The offline search is no longer the reroute bottleneck — simulated, 2026-08-07

`tools/sim/reroute_sim.py` is a 40k-trial Monte Carlo of the WHOLE wait, from the wrong turn to the
new route being installed. Every constant in it is read out of the tree with a `file:line`, and the
one unknown — the offline search time — is swept rather than assumed. Re-run it before proposing
any reroute-latency work.

**The result that changes the project's direction: the search time no longer moves the number.**

| offline search | 8 s | 4 s | 2 s | 1 s |
|---|---|---|---|---|
| median wait | 14.7 s | 14.5 s | 14.5 s | 14.7 s |

Not a modelling artefact — it holds at every separation rate from a slow drift to a motorway exit.
The early start (`EARLY_START_FRACTION = 0.5`) begins the search at half the deviation threshold, so
by the time the hysteresis confirms, the answer is already waiting. **A search that finishes before
it is needed cannot be felt.**

Two things follow, and both are the opposite of what was assumed all session:

- **A Cairo-only `.obf` buys ~0 seconds of felt latency.** It is still worth measuring `CD_HHLOAD`
  for battery, heat and the memory question, but not as a latency fix. Same for the warm routing
  environment: it is worth what its `reuse=` says, which is not seconds.
- **What is left is the safety rule, not the routing.** Median 14.6 s decomposes as ~11 s of
  travelling far enough to cross `allowableDeviation` plus ~6 s of consecutive-fix confirmation.
  Removing the hysteresis takes it to 10.2 s; halving the threshold again takes it to 9.2 s; both
  together with an instant search hit a floor of **5.2 s**.

That 5-second prize is real and so is its price: loosening exactly those two rules is what produced
"reroute after reroute while trying to turn around" and took `CAIRODRIVE_OFFROUTE_HYSTERESIS` off by
default once already.

**It has been taken, on 2026-08-07, at the owner's explicit and repeated instruction** —
`CAIRODRIVE_FAST_REROUTE` (threshold × 0.5 with a hard 30 m floor, hysteresis capped at 3 fixes) and
`CAIRODRIVE_WRONG_ROAD_ACT`, both ON. Simulated at **14.5 s → 8.5 s median, p90 30.1 → 14.3 s**.

**Shipping tier is `aggressive` (0.4 / 20 m floor / 2 fixes) — 7.9 s median, p90 10.8 s, 0.5%
false-positive risk — set by `CAIRODRIVE_FAST_REROUTE_TIER` (`free` | `aggressive` | `max`).**
`max` is reachable and self-defeating: it caps the hysteresis at ONE fix, so a single position past
the threshold reroutes uncorroborated, which trips the flap guard in the first minutes and leaves
the rest of the drive on the conservative rules. You would pay the risk and still measure `free`.

**The "5.2 s floor" quoted above is RETRACTED.** It came from removing the rules without keeping the
floor. Sweeping the knobs the code actually has gives: 0.6/30 m → 8.8 s (p90 16.3, 0.0% false),
0.5/30 m → 8.5 s (p90 14.3, 0.0%), 0.45/25 m → 8.3 s (0.2%), 0.4/20 m → 7.9 s (0.5%),
0.3/15 m → 7.4 s (**3.2%**). 0.5 is the last row that is free. The remaining prize past it is
**1.4 s**, not five, and it is bought with a 3.2% chance of rerouting a driver who never left the
route. Do not re-derive this; re-run the sweep in `tools/sim/reroute_sim.py` instead.

What makes that testable in the ONE drive per build he gets: `CairoDriveFastReroute` carries its own
falsification test. Four reroutes inside 90 s is flapping rather than driving, so the package
disarms itself for the rest of the session and writes `DISARMED` — the remainder of the drive then
runs on the conservative rules and is still valid data. A wrong answer costs a log line, not a trip.

**Read `CD_FAST_REROUTE` before anything else in the next log.** `DISARMED` present ⇒ raise
`MIN_ALLOWABLE_M` or move `TOLERANCE_MULT` toward 1.0, do not simply retry. Survived ⇒ the count is
not the test: read each `tightened` line and confirm the driver really had left the route. One
firing on a road they were correctly following vetoes it however good the timing looks.

The one targeted way to take part of it without loosening anything for anyone is
`CAIRODRIVE_WRONG_ROAD_ACT` — road identity fires at 20 m instead of 50-120 m, but only on a healthy
fix the matcher has settled. On the deviations where it works it saves ~5 s; it works on roughly a
third of them (55% of fixes are degraded on this device), so the MEDIAN moves only ~0.6 s. Judge it
on the mean and on `CD_WRONGROAD` firings, not on the median, and only after a drive shows zero
firings on roads the driver was correctly following.

### The warm routing environment — ON, unverified, and read `reuse=` before defending it

`USE_WARM_ROUTING_ENVIRONMENT` (`RouteProvider:109`) reuses one `RoutePlannerFrontEnd`,
`RoutingConfiguration` and `RoutingContext` across calculations instead of building them per query.
It was **off on purpose** and was turned on at the owner's explicit instruction, after he was told
what it risks. Do not present it as a win until a log says so.

**What it can possibly buy: the setup phase only — 2-12%.** On the C++ HH engine the search runs in
native code the cache does not reach, and `CD_ROUTE_PHASE` has never named setup as dominant. It is
not a second. `search=` and `CD_HHLOAD loadMs=` must come back UNCHANGED; if they move, the
attribution is wrong and something else changed.

**What it risks: a wrong route, silently, that outlives the calculation.** Two such faults were
found by adversarial review after it was flipped on, and both were invisible to the signature:

| Fault | Why the signature could not see it |
|---|---|
| `planRoadDirection` / `heuristicCoefficient` poisoned by the Java HH planner (`HHRoutePlanner:1338`, `:1439` set them; the cleanups at `:1369`/`:1454` restore neither, though `:1009-1021` restores both) | They live on `RoutingConfiguration`, so `resetForNewCalculation` cannot reach them. Worse, this only fires on `engine=java` — where `lib=` is `identityHashCode(null)`, so the signature is constant and the poison **never self-heals** |
| `config.router` replaced by the private-access probe (`RoutePlannerFrontEnd:459` swaps, `:470` restores, with a throwing call between and no `finally`) | The probe router has no impassable set, so `getImpassableRoadIds()` reads `none` — identical to the ordinary case of no closures |

Both are fixed: the entry snapshots all four config fields a search writes and restores them on
reuse, records the router's identity and drops on mismatch, and the upstream restore is now in a
`finally`. **The lesson is the one to keep: a signature over INPUTS does not cover state a search
WRITES.** Anything added to this cache must be checked against that, not against the signature.

The avoided-roads component of the signature is deliberately read back out of `cf.router` AFTER the
build, not from `configBuilder` before it — `Builder.build` re-reads the live set at
`RoutingConfiguration:219`, so labelling from the earlier read could cache a router under a
description of a different one, and that mislabel **latches** because the label is what every later
lookup compares against.

**Expect `reuse=` near zero while the traffic detour polls** — `TrafficDetourHelper:171/:178` adds
and removes jam ids on the shared builder and each change correctly invalidates the entry. That is
the cache refusing to serve a route that ignores a jam. A zero for that reason is not a fault; a
zero with `routing signature changed` on every calculation is. `cd-analyze.py` separates the two.

### 3. `CD_NARROW` — and the rule set was penalising the wrong roads

**The spec, in the owner's words (2026-08-07):** remove the roads only a **tuk-tuk** gets
through. Keep every road a car gets through, **including the tight ones** — he lives on a street
where two cars pass with difficulty and it must stay routable. Separately, avoid streets that are
physically wide but undrivable: souks, and streets the residents have blocked.

The original rules failed the second sentence outright. They penalised `width < 3.5 m` (a road a
car drives fine) and carried `lanes=1 and not oneway → 0.35`, which is *literally* "two cars cannot
pass" — a 2.9× cost penalty on driving home. **Both are gone. Do not restore the `lanes` rule.**

Thresholds now: `<2.0 m → 0.02`, `<2.5 m → 0.05` (tuk-tuk territory), `<3.0 m → 0.35`, nothing
above that.

**The surface tier was doing all the work, and it was the wrong work.** Of 14 actionable ways on
the 2026-08-07 route, **all 14 were tagged by `surface` and none by width or lanes** — so in Cairo
this option was an unpaved-roads penalty wearing a narrow-streets name, and an unpaved road six
metres wide is one he wants driven. Cut to `mud/sand/ground/dirt` at 0.45; `compacted`, `unpaved`,
`cobblestone`, `sett` removed. `smoothness=bad`/`very_bad` removed for the same reason.

**`fires=` is the number that matters now, not `actionable=`.** `actionable` counts ways carrying
any tag the option *could* look at; `fires` counts ways a rule *actually* penalises.
`RouteProvider.wouldBePenalised` mirrors the patch's selects value-for-value and **must be changed
in the same commit as `patches/cairodrive_narrow_streets.py`** — a rule added to one and not the
other silently stops being measured, which is how the previous mismatch survived.

Compare against the city-wide Overpass numbers already measured for Cairo: **tags ~2.5%**,
**alley names (حارة/زقاق/درب/ممر/عطفة) ~16.6%**. If `nameAlley` beats `fires`, it re-confirms that
only a custom Egypt `.obf` can act on the signal — the router cannot read names.

**"Blocked by the residents" has no OSM tag and never will** — the blockage is social and changes
by the hour. Do not invent a proxy for it; that fails the same way an inferred-narrowness rule
fails, confidently and on the roads he actually drives. The two mechanisms that do work are outside
`routing.xml`: OsmAnd's avoid-roads list (`AvoidRoadsHelper` — long-press a road, it is impassable
for every future route) and live closures (`CD_CLOSURE`). `highway=pedestrian` is the one taggable
part of the souk case and is penalised at 0.05.

### Google Places: all five features are ON, and the console can still block them

`CAIRODRIVE_PLACES_DETAILS`, `_PHOTOS`, `_REVIEWS`, `_AUTOCOMPLETE`, `_NEARBY` all default true.
The one-at-a-time rule above was the owner's earlier instruction and has been superseded by his
explicit request to enable all of them; the code was already there for each.

**The build cannot switch on the thing that decides whether they work.** The per-endpoint daily
quotas live in the Google Cloud console, and CLAUDE.md's own table records **Nearby Search at 0/day
— blocked**. So `CAIRODRIVE_PLACES_NEARBY=true` fails every request until that quota is raised, and
fails as a quota error that names nothing. Photos are 50/day and Details/Autocomplete 32/day, which
autocomplete alone can spend typing one name because it bills per keystroke session. Raise them
before the drive or the drive tests nothing. Read `CD_SEARCH` for request count, latency and whether
the prefix cache collapsed.

### 4. `LANG_` and the `SESSION` header

`LANG_MISSING_DATA` / `LANG_NOT_SUPPORTED` ⇒ Arabic TTS voice data is not installed and the lane
prompts are silent — the feature looks broken but is not. Always read the `SESSION` header and
state which build produced the log (`buildType`, `versionCode`, `flavor`); a stale sideload
masquerading as a Play build has wasted a drive before.

### 5. `CD_MATCH` — and why a slow fix is not one thing

The HMM matcher watchdog latches the whole feature off for the rest of the process, so a
`disabled reason=` line means the rest of that drive says nothing about map matching. **Read it
before concluding the feature did nothing.**

The 2026-08-07 drive measured a worst case of **2162 ms** against an ordinary case in the low tens.
Those are two different events and the watchdog used to conflate them: a fix that crosses into
unloaded `.obf` tiles pays for a disk read, and five tile-crossing fixes in a row is what driving in
a straight line looks like — not evidence the algorithm is too expensive. So the streak
(`MAX_CONSECUTIVE_SLOW` × `SLOW_MATCH_MS`) now **excuses any fix that loaded tiles**; such a fix
neither increments the streak nor clears it, because clearing would let alternating load/no-load
fixes hide a genuinely slow matcher forever.

Underneath it sits a second latch with no exemption — `MAX_CONSECUTIVE_STALLS` × `STALL_MATCH_MS`
(3 × 1500 ms) — closing the hole where every fix loads tiles and takes two seconds. 1500 ms is past
any plausible tile read on this device and past the 1 Hz fix interval, so three in a row means
matching is running behind the GPS and a matched position arrives after the fix that replaces it.

`msMax` in the summary still reports the excused cost, so nothing is hidden by the exemption.

---

## The routing test suite — settled, do not re-derive

`RouteTestingTest` carries `@Test(timeout = 1500)`, a WALL-CLOCK gate on a shared CI runner. It
failed the build once at 1.534 s (102.3% of budget) and the cause was not the routing.

The root `build.gradle:33` sets `isAndroidBuild=true` for **every** test task in this repo.
`RouterUtilTest.getNativeLibPath():49` returns null whenever that property is set, and
`RouteTestingTest:78` gates `useNative` on it — so `RouteTestingNativeTest`,
`ApproximationNativeTest` and `RouteResultPreparationNativeTest` re-ran their parents **byte for
byte**. Measured: **40.2 s of a 62.1 s suite**. Worse, the duplicate is where the flake landed —
case 69 ran 0.563 s in `RouteTestingTest` and 1.534 s in the copy, identical inputs and code
path, because by then the JVM had ~300 route calculations of garbage behind it.

Excluded in `OsmAnd-java/build.gradle`, not deleted: they become real tests again under
upstream's own conditions (`core-legacy/binaries` present, `isAndroidBuild` unset). A `doFirst`
names them in the build log every run, so the exclusion is never silent.

**Two decisions follow, both closed:**

| | Decision | Why |
|---|---|---|
| Move the suite to a SEPARATE CI job | **No** | ~22 s after the exclusion. Starting a parallel job costs a minute of checkout and Gradle configuration, so that shape is a net loss |
| Get it off the critical path anyway | **Done, for free** | `androidJar` depended on `build` (= `assemble` + `check`), so every Android compile, R8 run and bundle waited behind the suite. Now `assemble`, and Gradle's cross-project parallelism runs the tests BESIDE that work. **Both CI jobs must keep naming `:OsmAnd-java:test :OsmAnd-java:routingTest` on the gradlew line** — nothing pulls them in implicitly any more, and dropping the names would silently stop testing a signed build |
| Raise the 1500 ms timeout | **Not needed** | The surviving copy sits at 37.5% of budget. The ceiling was never the problem |

The build now prints `SUITE COST` with a per-class breakdown and a `HEADROOM:` line on every
run. If that headroom goes negative again, read those two lines before theorising.

---

## Things that have gone wrong here before

- **Debug-signed vs signed.** The `build` job is debug-signed; Play rejects it with "signed with
  the wrong key". Only the `release` job (tag `v*`, or dispatch with `sign=true`, behind the
  `production` reviewer gate) produces `cairodrive-release-aab-*`. That is the only Play upload.
- **Compile-check before spending an approval.** Pushes to `dev` build unsigned; that artifact
  is useless (Play rejects debug-signed) but the compile check is not. Let it go green before
  dispatching a signed run. A missing `net.osmand.Location` import once surfaced inside the
  gated build instead and cost 15 minutes.
- **Verify against the tree, never against a commit message.** A re-audit found a "fixed" token
  redaction that closed one of three leak vectors, and three regressions introduced the same day.
- **Never remove an asset without removing its manifest entry in the same change** —
  `patches/cairodrive_trim_assets.py` does both atomically, and refuses to half-apply.
  The rule stands; the reason has moved twice, so check the tree rather than this line.
  `CheckAssetsTask.unpackBundledAssets` HAS a per-entry catch now (`:217`), and so does
  `copyMissingJSAssets` (`:117`) — its catch used to sit outside the loop, so one missing voice
  script cost the user every voice, every 3D model and every asset after it in manifest order.
  Both were the same fault: an entry naming a file that is not in the APK aborted the whole pass,
  skipping the `PREVIOUS_INSTALLED_VERSION.set()` after it, so `versionChanged` stayed true and
  the copy re-ran on every cold start for the life of the install.
- **`65_NotoSansNastaliqUrdu` is the only Arabic-script font in the manifest.** Base Noto Sans is
  Latin/Greek/Cyrillic. Dropping it makes every Cairo street label an empty box.
- **Never commit into `patches/`** anything but reviewed `.py`. Both CI jobs execute python from
  there, including the one holding the decoded keystore.

## Build flags (`OsmAnd/cairodrive.gradle`), and why the defaults are what they are

| Flag | Default | Note |
|---|---|---|
| `CAIRODRIVE_RENDER_SCALE` | `1.0` | Full sharpness. Below 1.0 buys frame rate by pushing fewer pixels through the GPU readback and the software blit, at the cost of label legibility - `0.75` = 44% fewer, `0.85` = 28%. Kept at native because the right fix is removing those two copies, not shrinking them |
| `CAIRODRIVE_SURFACE_OVERSCAN` | `0.0` | Upstream renders 50% extra width every frame and crops it |
| `CAIRODRIVE_OFFROUTE_HYSTERESIS` | `true` | Was `false` because it delayed a genuine wrong turn for kilometres. Back on 2026-08-04 after fixing all three compounding rules (evidence cleared only by two *consecutive* on-route fixes, debounce checked after the evidence test, hard 12 s timeout). Six fix patterns simulated before the flip |
| `CAIRODRIVE_HW_CANVAS` | `true` | Draws the car frame with `lockHardwareCanvas()`. Targets `blit` **and** `over` — both are paid on the same software canvas, together 76% of a frame. Latches back to software if the head unit refuses; `CD_FRAME hwCanvas=` says which actually ran |
| `CAIRODRIVE_PRESENTATION` | `true` | B1. VirtualDisplay + `Presentation` instead of offscreen-render-and-copy. Removes `read`, `blit` and the software canvas outright. Falls back to the offscreen path on any failure, latched, with the reason in `CD_PRESENT`. Makes `RENDER_SCALE` and `SURFACE_OVERSCAN` inert |
| `CAIRODRIVE_DRIVING_VIEW` | `true` | Starts navigation tilted. Upstream only applies `AUTO_ZOOM_3D_ANGLE` when the map is ALREADY tilted, so a user who never tilted by hand never got the 3D view at all. **Watch `avgMs`, not `over`** — a tilted camera sees further, so more is drawn per frame, but on the OpenGL build the map tiles are drawn by the GL core and never appear in `over`. `over` is the Java overlay layers only |
| `CAIRODRIVE_R8_SHRINK` | `true` | **Was `false`; flipped once `.github/check_r8_jni.py` existed, and only because of it.** `-dontobfuscate` means removal is R8's only possible failure mode, `-printusage` lists exactly what it removed, and that script fails the build if any of the 35 classes core-legacy resolves by name (generated from `native/src` at the pinned `CORE_LEGACY_REF` into `.github/r8-jni-classes.txt`) appears in the list. The mid-drive crash became a red build. Guard runs in BOTH jobs and whatever the flag says. **What it does not cover:** the JNI surface is enumerable; Java-side reflection is not. Measured baseline with the flag off is `net.osmand share: 2 of 75277 removed items (0.0%)` — the blanket keep really did make R8 a no-op on this fork's code. Lets R8 shrink this fork's own bytecode. Off because `proguard-rules.pro` carried `-keep class net.osmand.** { *; }`, which made every class a GC root — R8 ran and shrank only third-party code. It is now 30 narrow rules, each with `file:line` evidence, and when the flag is off `proguard-rules-keep-all.pro` adds the blanket keep back. Every narrow rule names only `net.osmand`, so the union is exactly the old line and the dex is unchanged — that is set inclusion, not an assertion. **A keep rule one class short does not fail the build, the install or a smoke test.** It throws at the moment the path is first taken, which for most of this app is on a Cairo road. Flip it, read the `R8 usage summary` the dev workflow prints, and DRIVE it before any signed run |
| `CAIRODRIVE_FULL_LOGGING` | `true` | Both build types — a Play build must log or a drive produces nothing |
| ABI | `arm64` | The only test device is a POCO C85 (`arm64-v8a`). Switch to `fat` before any wide rollout, or Play rejects it for stranding existing installs |

## API keys — never write a value into this repo

**No key, token or fingerprint value goes in a file, a commit message, or a comment.** A repo is
forever and this one is on GitHub. Record the *name* and the *action*, never the secret. If a key
value appears in chat, treat it as burned and say so — it is in a transcript.

| Key | Where it lives | State |
|---|---|---|
| `GOOGLE_PLACES_API_KEY` | GitHub repository secret → `BuildConfig` → ships inside every APK | Restricted by package + SHA-1, daily quota capped. Rotation declined — see below |
| Release keystore (`CAIRODRIVE_KEYSTORE_*` / `ANDROID_*`) | `production` GitHub Environment, reviewer-gated | Not rotatable — it is the app's identity on Play. A leak ends updates to the listing |

### Decided — do not re-raise these

The owner has settled all three. They are recorded so a later session does not reopen them.

| | Decision | Why it holds |
|---|---|---|
| Daily quota cap | **Set** | This is the control that actually bounds the bill, and the only one an attacker cannot forge around |
| Rotate the key | **No** | Owner's call, made with the exposure understood |
| SHA-1 restriction | **Leave as is** | Package + SHA-1 restriction is registered and search works. Changing it would break Places on the builds he actually installs |

Context, not an argument to re-litigate: an Android app restriction is a deterrent, not a
boundary. `GooglePlacesSearchApi:450` sends `X-Android-Package` and `X-Android-Cert` as plain
headers with no cryptographic proof, and both values are public — the package name from the Play
listing, the signing SHA-1 from any downloaded APK. The quota cap is therefore doing the real
work here, which is why the decision above is sound rather than merely accepted.

One live fact worth keeping: `keystores/debug.keystore` is committed in this repo with its
password in plaintext (inherited from upstream, so it exists in thousands of clones). Its SHA-1
is `E6:FA:...:CD` — do not treat a build signed with it as trusted, and never register it against
anything new.

Absence is handled gracefully: no key ⇒ offline OSM search (`GooglePlacesSearchApi` falls back,
CI warns rather than failing).

**Play Data safety:** required on every track including internal testing — not for the on-device
logger, which never leaves the phone, but because Places search sends the user's typed query and
their location to a third party. That is "Location — precise" + "App activity — search history".

## Adding Places API features: one at a time, each one measured

Today the integration is deliberately minimal: a single `places:searchText` call with the
cheapest field mask that still supports a map pin and a context menu —
`places.id,displayName,formattedAddress,location,types,primaryType`.

**Google bills by the fields requested and by the endpoint.** Adding a field or a call can move
the request to a different SKU, so a change that looks like one line is a change to the bill.
Deferred, and to be added **one per build, each verified on a real drive before the next**:

The owner's stated goal is "the info Google Maps shows for a business". Most of that is
reachable; one headline part of it is not.

| Feature | Available? | Cost shape | Daily quota today |
|---|---|---|---|
| Photos | Yes | `GetPhotoMedia` — new endpoint + bandwidth | 50 |
| Place Details (hours, phone, rating, review count, price level, editorial summary) | Yes | `GetPlace` — new endpoint per tapped result | 32 |
| Reviews | Yes | Field on Place Details | — |
| Autocomplete as you type | Yes | `AutocompletePlaces` — billed **per keystroke session**, by far the most expensive here | 32 |
| Nearby Search ("petrol near me") | Yes | `SearchNearby` — new endpoint | **0 (blocked)** |
| Extra fields on the existing `searchText` | Yes | Same call, higher SKU tier | n/a |
| **Popular times / "best time to visit"** | **Not from Google** | Not a Places API field — it exists only in the Maps app. Third parties (Outscraper, ScrapingBee, Apify, BestTime.app) sell it by SCRAPING Maps: separate provider, separate key, separate bill, and it breaks whenever Google changes its markup. Consider the Play-policy angle too, since it is scraped Google data | n/a |

Quotas are deliberately left non-zero on the endpoints above because these features are planned;
the amounts are small enough that the exposure is pennies. **Raising the relevant quota is part of
shipping the feature** — which is convenient, because it makes the console enforce the
one-at-a-time rule rather than relying on anyone remembering it.

Current cap on the only endpoint in use: `SearchTextRequest` **160/day**, against ~12/day actual.

Do not batch them, and the primary reason is PERFORMANCE, not billing.

This has been tried once already: all the features went in together and the app was, in the
owner's words, "buggy as hell" — with no way to tell which addition caused it, so the whole lot
had to come out. That is the failure being avoided. Billing attribution is a secondary benefit.

So each feature ships alone AND is judged on the drive log, not on whether it looks right in the
UI. What to check after adding one:

- `CD_SEARCH` — request count, latency, and whether the cache hit rate collapsed. A feature that
  quietly defeats the prefix cache multiplies every later search.
- `CD_FRAME` — a details or photo fetch that touches the main thread shows up in `over`, and a
  bitmap-heavy one shows up as GC pauses in `maxMs` even when the average looks fine.
- Anything that runs per keystroke (autocomplete above all) must be judged while TYPING, not
  after. That is where the previous attempt went wrong.

If a feature makes it worse, it comes out on its own rather than as part of a mass revert — which
is only possible because it went in on its own.

## Measure before optimising

Two things were nearly optimised blind and both would have been wrong: the nine performance
findings model out at **~2–3% of a frame combined**, and the "lane-aware" turn-in trigger *moved*
the only close-in prompt instead of adding one, leaving ~40 s of silence before a turn. Quantify
first — statically if the device is not available, from a log if it is.
