# CairoDrive — what is done, what is not, and why

Living status. Kept in the repo on purpose: this used to exist only inside a chat, which meant
the only record of *why* something was not done could be lost, and a later session would re-raise
a settled question or re-derive a falsified one.

Update this file in the same commit that changes an item's state.

Last updated: 2026-08-04, branch `dev`, at `04303c3` (run 125, green).

---

## Everything below is IN the tree, and as of `04303c3` it COMPILES.

Run 125 on `04303c3` is green end to end: release AAB, release APK, `libosmand.so` present at
8,673,520 bytes for `arm64-v8a`, all 19 steps success. That is the first green build since
`351d535` and it covers every commit on this branch.

Getting there took two rounds of finding what the cancelled builds had been hiding.

**Round 1 — found by accident, not by CI.** `@Override` had been left annotating a *field* in
`MapViewTrackingUtilities` when the N3 filter's field was inserted between the annotation and the
method it belonged to. Caught in an agent's report.

**Round 2 — found by reading the last build that actually finished.** Every run after `144867c`
was cancelled by concurrency, so nobody looked at `144867c` itself: it had **failed**, and four
`javac` errors then sat unseen in `RouteProvider.java` for ten commits. Two of them were worse
than a missing import — `PlatformUtil.getOsmandRegions()` returns a *different* `OsmandRegions`
from the one routing queries, so the obvious fix would have compiled and then invalidated the
wrong cache while still logging that it had dropped it.

**The lesson worth keeping is about the checking, not the errors.** The ad-hoc brace-balance
checks used while this work landed stripped char literals *before* comments, so an apostrophe in
prose — `OsmAnd's` — opened a fake char literal that swallowed every line to the next apostrophe,
deleting the broken code before it was ever examined. Those checks reported clean on a file with
four compile errors in it. Any "balanced OK" recorded in this branch's history before `04303c3`
was not evidence.

`tools/cd-typecheck.py` replaces them. It resolves capitalised names in type positions against
imports, wildcards, static wildcards, own package and java.\*, needs no Android SDK, and is
validated both ways: it flags exactly the two missing types on the pre-fix file and reports zero
across every file changed since the last green build. It is **not** a compiler — it cannot see an
out-of-scope *variable*, which is what one of the four errors was. Kept in `tools/`, never
`patches/`, because CI executes python from `patches/`.

---

## Done

### Routing / reroute

| Item | What |
|---|---|
| **4** | **Partial repair, LIVE.** A deviation routes ~600 m to a rejoin point on the previous route and splices the untouched tail, instead of a full search to the destination. Tail deep-copied, `prepareResult` re-run over the whole spliced list so turns are correct *by construction*, joint verified to 1 m. Falls back to a full search on every failure path |
| **5** | Region point queries memoised (~4 per calculation over two points, each a synchronized `regions.ocbf` binary search + polygon ray-cast). Invalidated on every map change. `regionCache=` in the log |
| **6** | **Speculative precompute.** The reroute for a missed turn is computed before the turn is missed. Serialised on the routing worker, dropped if a real calculation queues, 45 s rate limit, expires at 2 min / 120 m |
| **7** | HH alternatives, **alternating per calculation** so one drive carries both arms. `alt=` per line |
| — | Off-route hysteresis: three compounding rules fixed + 12 s hard timeout. Six patterns simulated before flipping the default |
| — | ETA learns from real driving including stops. MAE −21% (−32% median) over 76 samples |
| — | `evalWaitInterval` capped 120 s → 15 s. Two minutes of silently refusing reroutes read as the app giving up |
| — | Private-access probe skipped on reroutes — a JNI round trip resolving both endpoints, feeding a dialog nobody can answer while driving |
| — | Reroute result cache — an oscillating driver re-asks a question answered seconds ago |
| — | **Upstream index-space bug fixed** (`RouteProvider`): a location index was slicing a segment-indexed list |

### Rendering / Android Auto

| Item | What |
|---|---|
| **B1** | VirtualDisplay + `Presentation`. Same view stack the phone uses, on a display backed by the car surface. Latched fallback, reason in `CD_PRESENT` |
| **B2** | Place-detail pane on Android Auto — there was none. Wired into search *and* the POI flow via hand-back (`setResult`/`finish`) so the template quota peak stays at 4 of 5 |
| **B3** | Glance style: car POI suppression uncapped past z17, house numbers off, POI label ink collapsed to one grey pair, and the long tail of shop/amenity/barrier/pole **icons** suppressed at z15+. **OFF by default** — it moves the same `CD_FRAME` numbers B1 is measured on |
| **B5** | Search debounce — every keystroke used to start a full `.obf` index scan |
| **P3** | AA first frame on `MAPS_INITIALIZED` instead of the whole init chain |
| **P7** | Hardware canvas, default on |
| **P9** | Baseline profile (`src/main/baseline-prof.txt` + profileinstaller, plugin-free path) |
| — | Head unit keeps the manoeuvre card during a reroute instead of blanking to a spinner |

### Navigation / position

| Item | What |
|---|---|
| **N1** | GNSS health as `gnss=OK\|DEGRADED` |
| **N2** | Arrow no longer un-snaps, and voice no longer goes silent, on a recalculation the driver did not cause |
| **N3** | Stationary hold. Thresholds **simulated, not chosen** — the naive "2 km/h" froze 13.4% of fixes from a car moving at 3 km/h. Applied at the provider *and* the tracking utilities, because they gate different things |
| **N4** | Position prediction at `INTERPOLATION=90`, plus the Android 12 request that forbids batching and blocks a battery-pressure downgrade. Still instrumented by `CD_FIXRATE`, which is what says whether the second part changed anything |
| **N6** | Offline HMM/Viterbi map matching, consumed for display only. Re-projects onto the matched segment; stands down when routing already snapped |
| **N7** | Speech lead — additive only, never moves an existing prompt — **and** turn-now brevity, which shortens the sentence rather than moving it |

### Security

| Item | What |
|---|---|
| **S1** | AIDL receiver un-exported, plus the API 24–32 hole the first fix missed |
| **S2** | OSM password not `.makeShared()` |
| **S3** | Both exported AIDL `<service>` blocks removed |
| — | `osmand.api://` read commands now check the caller. `get_info` was handing GPS position, destination, ETA and routing analytics to any zero-permission app |
| — | POI `SearchResult` NPE fixed at the root — latent, and the place-detail pane would have made it unconditional |

### Correctness / data

`C1` `C2` `C4`–`C10` · `D3` `D6` · `P1` `P2` `P5` `P6` `P8` `P10` · `ArabicNormalizer` `charAt(0)`
· Estedad (Naskh) in the font manifest · `CD_LAYER` per-layer timing · metered tile gate with
`CD_DATA`

**`C3` is now in too** — `SearchHelper.buildSearchRow` was still concatenating `" • "` by hand
instead of using `ltr_or_rtl_combine_via_bold_point`, which exists in both `values/` and
`values-ar/`. It is the shared builder behind *every* car search result, and it was the last
place still assembling a two-part label itself; `PlaceDetailsScreen`, written later, already did
it correctly.

**`D2` is now done too** — a confirmation, not a refusal. D3/D4/D6 gate transfers nobody asked
for and can decline silently; a map download is a button the user pressed, so the fix was to
supply the one missing fact (the size) and let them choose. Fails open in every uncertain case,
including an explicit main-looper check, because a prompt that cannot be shown must never become
a download that cannot start.

---

## Not done, and why

| Item | Reason |
|---|---|
_Empty. Every row that was here has moved into the table below._

### Moved out of this table since it was written

These were listed as not-done and are now in the tree. Kept visible rather than silently deleted,
because the *reasons* they were held are the useful part.

| Item | Was held because | What changed |
|---|---|---|
| **Recoloured map icons** | Called impossible: icons are bitmaps and the point-symbol output set has no colour property. That fact is correct and was verified against both renderers | The inference from it was wrong. A style cannot tint a bitmap, but the colour can be removed *from the bitmap* before the style sees it. `patches/cairodrive_glance_icons.py`, Rec. 709 luma so a hazard and a water icon do not collapse to the same grey. Build-time only and it cannot be toggled back |
| **N8** — deviation threshold | Correctly held on N1's data | Done as a *tightening only where the fix is good*, capped by `Math.min` against the old formula so no case is looser. The first version was looser above ~20 m accuracy; simulation caught it |
| **N6** — local-graph cache, speed term | "Optimisations, not gaps" | Both in. Cache keyed on road identity, hit rate in `CD_MATCH graphCache=`. Speed term applied only *above* the limit — the "fast car ⇒ fast road" form stays rejected, because Cairo flyovers jam |
| **N7** — roundabouts, arrival clause | Roundabouts excluded as "not safe to say twice"; arrival clause "under-fires, which is the safe direction" | Nothing says it twice — the cue is timed, never spoken. Roundabouts are the worst case for late guidance, and the final manoeuvre is the one a driver can least recover from |
| **N7** — the long sentence overrunning the junction | "Structurally forced. Cannot be fixed by saying it earlier — only by saying less, which is a different feature" | The reasoning was right and the conclusion was wrong: saying less is a feature, so it got built. `CairoDriveTurnBrevity` drops whole named parts of the turn-now sentence, least actionable first — the road being left, then the destination clause, then the ref. `toStreetName` is never dropped. Turn-now only; turn-in has the room and keeps the full text |
| **N4** — raising the GNSS fix rate | "Nothing to raise: both helpers already ask for more than the hardware gives" | True of the legacy call, which is what was checked. Android 12 lets an app state two more things: `setMaxUpdateDelayMillis(0)` forbids BATCHING — which preserves the fix rate while destroying latency — and `QUALITY_HIGH_ACCURACY` stops a battery-pressure downgrade. Neither raises the ceiling; both stop it being lowered underneath us. `CD_FIXRATE` says whether it mattered on this device |
| **Popular times** | "Not a Places API field at all" | Still true of Google, and irrelevant: the owner supplied a BestTime.app key. `BestTimeProvider` generates a venue's forecast once with the private key and re-reads it free with the public one. Egypt coverage is unverified, so a venue that resolves to nothing is remembered as absent and the row is omitted |
| **D1** — resume a download after the app closes | Listed under **Dropped** as architecturally blocked, because `.obf` maps arrive as zip streams and no offset maps compressed bytes to decompressed ones | True for `.obf`, and it was over-generalised to every download. `DownloadActivityType.isZipStream()` is FALSE for HILLSHADE, SLOPE, GEOTIFF, SQLITE, WIKIVOYAGE and GPX — the largest raw files there are. Resume is implemented for those; `.obf` still cannot without splitting download from decompression |
| **Google Places photos / autocomplete / nearby** | Shipped behind build flags | The flags were on, the API methods worked, and NOTHING CALLED THEM. Found by grepping for callers instead of for flags. All three are wired now. Nearby also needed a real bug fixed: `location` was in the field mask, and paid for, but never parsed — so a result had a name and no coordinates |
| **D5** — help-article gating | Current state never confirmed | Confirmed and done |
| **D4** — catalogue gating | "Current state never confirmed. Not claimed either way" | Checked, and it was **already done**. `AppInitializer.checkMapUpdates` gates the startup catalogue fetch on `blocksBulkTransfer`, alongside the `* 1000L` fix — without it the two-day throttle was 172800 ms, under three minutes, so the worldwide index could be pulled several times a day on mobile data. The other ten `runReloadIndexFiles()` callers are all screens the user opened, and are deliberately NOT gated: an explicit request is not a bulk transfer |

## Pre-existing, found not caused

- `Landing → Search → PlaceDetails → Settings → MapMagnifier` is **already 5 templates**, at the
  host's per-task ceiling. Exhausting it does not degrade — the host closes the app.
- Four one-shot user-initiated paths read `getLastKnownLocation()` into routing. None is on the
  per-fix path, and N6's route-snapped stand-down covers the case.

## What this app collects, and what leaves the phone

Written down because it is a Play Data safety declaration, and because "does it collect my
GPS" deserves an answer that distinguishes the two very different things going on here.

### Stays on the phone, always

The drive log. `/sdcard/Android/data/com.cairodrive.app/files/cairodrive-logs/`, app-scoped
external storage, written by `CairoDriveLogger`. It contains GPS fixes, speeds, headings, frame
timings, route calculations, device state and provider outcomes. **Nothing uploads it.** It is
pulled over USB by hand, and it is the only reason any of this is diagnosable at all.

Being on-device does NOT make it harmless: it is a minute-by-minute record of where the car went,
readable by anyone holding the unlocked phone. That is a deliberate trade for a single-user fork
and would not be one for a public app. Keys and tokens are redacted from it by `SECRET_NAMES`
before writing - the query a driver TYPES is redacted to a character count for the same reason.

For Play's purposes this is not "collection": it never leaves the device and is not shared. It is
still worth declaring honestly if the listing ever goes wider than internal testing.

### Leaves the phone, to a third party

This is the part that must be declared, and it is not one recipient any more - it is nine.

| Goes out | To | Triggered by | Play category |
|---|---|---|---|
| Typed search query + coarse location | Google Places | A search | App activity - search history; Location - precise |
| Current point / bbox around the car | TomTom (flow, incidents) | Driving, paced by `BudgetPacer` | Location - precise |
| Route polyline | Google Routes | Live traffic, opt-in, default OFF | Location - precise |
| Point | OpenWeather | Dust / air-quality poll | Location - precise |
| Point | Geoapify | Reverse geocode - ONLY when the offline `.obf` has no name; autocomplete; nearby | Location - precise |
| Point | LocationIQ | Same, as fallback when Geoapify has no key or no budget | Location - precise |
| Sampled points along the route | Azure Maps | Weather along route, severe alerts | Location - precise |
| Point | Tomorrow.io | Visibility second opinion | Location - precise |
| bbox | HERE | Closures, if the key is set | Location - precise |

Two things follow that are easy to miss:

1. **The category has not changed since the Places-only declaration - the RECIPIENT LIST has.**
   "Location - precise, shared with third parties" was already true. What is new is that it is
   now true continuously while driving rather than only when someone searches, and that eight
   more companies receive it. A declaration naming only Google is no longer accurate.
2. **Every one of these is gated on a key being present in the build.** A build with no secrets
   set sends nothing anywhere, degrades to the offline `.obf`, and the API status screen says so
   per provider. That is the honest baseline, and it is what the app does today for any key the
   owner has not registered.

### Not collected at all

No account, no analytics SDK, no crash reporter, no advertising ID, no contacts, no
device identifiers sent anywhere. `GOOGLE_PLACES_API_KEY` and friends ship inside the APK, which
is a key-exposure question (settled - see below), not a data-collection one.

---

## Needs a human, not a build

**CLOSED 2026-08-06 — the owner confirmed `الاتجاهات` reads correctly.** He is a native
Arabic speaker who drives this daily in Cairo, which is the exact bar this item was waiting
for, so it is settled rather than merely unchanged. Do not re-raise it.

The original note is kept below because the *reasoning* still applies to the next string
somebody wants to change.

---

The Arabic strings in B2 are an agent's, not a translator's — `الاتجاهات` for Navigate.

Checked before leaving it: `context_menu_item_directions_to` ("الاتجاهات إلى") is dead — no Java
references it — and `shared_string_navigation` ("الملاحة") reads as a menu heading rather than a
button. `الاتجاهات` is also what Google Maps Arabic puts on this exact control, and Google Maps is
the bar this fork is measured against. So it stays until a native speaker says otherwise in the
car; the alternative was churn, not an improvement.

---

## Dropped

| Item | Why |
|---|---|
| **B4** — OBD | Owner's decision |
| Lowering `recalculateDistance` from 20 km alone | Only affects the `BinaryRoutePlanner` path, which HH pre-empts |

## Settled — do not re-open

- **Route calculation speed itself.** Six hypotheses measured on-device, all six wrong: cold
  start, warming the routing context, the native memory cap, this fork's priority rules, a stale
  map, a silent A* fallback.
- **Google Places key**: quota cap set; rotation declined; SHA-1 restriction left as is.
- **Online OSM routing**: recommended against — network per reroute, same `maxspeed` tags.

---

## What the next drive decides

| Line | Field | Decides |
|---|---|---|
| `CD_REROUTE` | `repair USED ms=` vs `CD_ROUTE_TIMING search=` | Whether partial repair actually saved seconds |
| `CD_SPECULATE` | `HIT` | Whether a reroute was served with no search at all |
| `CD_ROUTE_TIMING` | `pre=`, `regionCache=` | Where the unattributed ~80% of a search goes |
| `CD_ROUTE_TIMING` | `find=` | **Must be 0.00** on `hh-cpp`. If not, the whole static reading is wrong |
| `CD_ROUTE_TIMING` | `alt=0` vs `alt=1` | What alternatives cost |
| `CD_FRAME` | `renderMode`, `hwAccel`, `avgOver` vs 25.9, `avgMs` vs 46.9 | Whether B1 worked |
| `CD_LAYER` | worst layers | What is actually eating `over` |
| `CD_MATCH` | `disagree=`, `applied=` | Whether map matching would have moved the car, and was right |
| `CD_MATCH` | `graphCache=` | Whether the local-graph cache hits. If it collapses in junctions it is paying its key cost for nothing |
| `CD_STATIONARY` | `frozen=`, `frozenWhileDegraded=` | Whether N3's simulation held on real GPS |
| `CD_FIXRATE` | `hz=` | Whether N4 is worth doing |
| `CD_TRIP`, `CD_AUTO`, `CD_SEC` | present at all | Card kept, pane opened, API refusals |
