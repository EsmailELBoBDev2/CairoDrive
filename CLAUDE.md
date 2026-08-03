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
| `over` | OsmAnd's own Java drawing | The nine deferred perf findings become worth doing (per-fix bitmaps in `TripHelper`, boxed ints in `RouteGeometryWay`, per-frame `getModeValue`) |
| `wdgt` | Speedometer/alarm widgets | Already gated to rebuild only on a new fix; if still large the cost is drawing, not computing |

`wdgt` exists because the timing mark used to sit *before* the widget callback, so that work
landed in `post` — the bucket whose meaning is "unfixable, blame the head unit". Never merge it
back.

### 2. `CD_ROUTE_TIMING` — check this first, it is a regression detector

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

## Things that have gone wrong here before

- **Debug-signed vs signed.** The `build` job is debug-signed; Play rejects it with "signed with
  the wrong key". Only the `release` job (tag `v*`, or dispatch with `sign=true`, behind the
  `production` reviewer gate) produces `cairodrive-release-aab-*`. That is the only Play upload.
- **Compile-check before spending an approval.** Let the free unsigned build go green first. A
  missing import once cost a 15-minute gated build.
- **Verify against the tree, never against a commit message.** A re-audit found a "fixed" token
  redaction that closed one of three leak vectors, and three regressions introduced the same day.
- **The asset extractor aborts on the first missing file.** `CheckAssetsTask.unpackBundledAssets`
  has no per-entry catch, so an asset in `bundled_assets.json` but absent from the APK skips every
  remaining asset *and* the `PREVIOUS_INSTALLED_VERSION.set()` after it. Never remove an asset
  without removing its manifest entry in the same change — `patches/cairodrive_trim_assets.py`.
- **`65_NotoSansNastaliqUrdu` is the only Arabic-script font in the manifest.** Base Noto Sans is
  Latin/Greek/Cyrillic. Dropping it makes every Cairo street label an empty box.
- **Never commit into `patches/`** anything but reviewed `.py`. Both CI jobs execute python from
  there, including the one holding the decoded keystore.

## Build flags (`OsmAnd/cairodrive.gradle`), and why the defaults are what they are

| Flag | Default | Note |
|---|---|---|
| `CAIRODRIVE_RENDER_SCALE` | `1.0` | Below 1.0 trades sharpness for frame rate. That is the driver's call, not a build script's |
| `CAIRODRIVE_SURFACE_OVERSCAN` | `0.0` | Upstream renders 50% extra width every frame and crops it |
| `CAIRODRIVE_OFFROUTE_HYSTERESIS` | `false` | Delayed a genuine wrong turn for kilometres on a real drive |
| `CAIRODRIVE_DRIVING_VIEW` | `false` | Unverified camera change at the moment navigation starts |
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
| **Popular times / "best time to visit"** | **NO** | Google does not expose it through any Places API — it exists only in the Maps app. Scraping it breaks the ToS | n/a |

Quotas are deliberately left non-zero on the endpoints above because these features are planned;
the amounts are small enough that the exposure is pennies. **Raising the relevant quota is part of
shipping the feature** — which is convenient, because it makes the console enforce the
one-at-a-time rule rather than relying on anyone remembering it.

Current cap on the only endpoint in use: `SearchTextRequest` **160/day**, against ~12/day actual.

Do not batch them. The reason is not caution for its own sake: with several landing together, a
bill or a latency regression cannot be attributed, and `CD_SEARCH` traces in the drive log are the
only measurement available. One change, one drive, one answer — the same rule the frame work
follows.

## Measure before optimising

Two things were nearly optimised blind and both would have been wrong: the nine performance
findings model out at **~2–3% of a frame combined**, and the "lane-aware" turn-in trigger *moved*
the only close-in prompt instead of adding one, leaving ~40 s of silence before a turn. Quantify
first — statically if the device is not available, from a log if it is.
