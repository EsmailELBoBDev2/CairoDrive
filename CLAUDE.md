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
current), and a silent A* fallback (there is none). `routingTime` is only ~15-20% of `search`;
the rest is inside the native engine and is not attributable from the app side.


If `engine=` contains **`java`**, `libosmand.so` did not load and every reroute is back to the
Java router: **6.8 s average, 39 s worst**, measured. Say so immediately and loudly. C++ search
times are ~200–400 ms.

### 3. `CD_NARROW` — narrow-street data coverage

Compare against the city-wide Overpass numbers already measured for Cairo: **tags ~2.5%**,
**alley names (حارة/زقاق/درب/ممر/عطفة) ~16.6%**. If `nameAlley` again beats `actionable`, it
re-confirms that only a custom Egypt `.obf` can act on the signal — the router cannot read names.

### 4. `LANG_` and the `SESSION` header

`LANG_MISSING_DATA` / `LANG_NOT_SUPPORTED` ⇒ Arabic TTS voice data is not installed and the lane
prompts are silent — the feature looks broken but is not. Always read the `SESSION` header and
state which build produced the log (`buildType`, `versionCode`, `flavor`); a stale sideload
masquerading as a Play build has wasted a drive before.

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
| `CAIRODRIVE_R8_SHRINK` | `false` | Lets R8 shrink this fork's own bytecode. Off because `proguard-rules.pro` carried `-keep class net.osmand.** { *; }`, which made every class a GC root — R8 ran and shrank only third-party code. It is now 30 narrow rules, each with `file:line` evidence, and when the flag is off `proguard-rules-keep-all.pro` adds the blanket keep back. Every narrow rule names only `net.osmand`, so the union is exactly the old line and the dex is unchanged — that is set inclusion, not an assertion. **A keep rule one class short does not fail the build, the install or a smoke test.** It throws at the moment the path is first taken, which for most of this app is on a Cairo road. Flip it, read the `R8 usage summary` the dev workflow prints, and DRIVE it before any signed run |
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
