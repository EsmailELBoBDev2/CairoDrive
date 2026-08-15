# SEARCH-PATCH-DESIGN.md — Google Places into Magic Earth phone search

## Update — request logic completed and verified against a real device

Two real bugs were found and fixed by building the request logic once, testing
it end-to-end in a throwaway app (CairoDrive) on a real phone, then porting the
verified fix back into `patch/frida/cairodrive-search-hook.js`:

1. **Missing identity headers.** A raw HTTP call to Places API (New) needs
   `X-Android-Package` / `X-Android-Cert` — the Maps/Places SDK adds these
   automatically, a hand-rolled request does not, and an Android-app-restricted
   key rejects the call outright (403, `API_KEY_ANDROID_APP_BLOCKED`) without
   them, regardless of Cloud Console config. The hook now resolves the true
   running identity via `PackageManager` (Java interop, `resolveIdentity()`),
   the same way as `app/android/.../AppIdentity.kt`.
2. **`locationBias.circle.radius` over the API's 50000.0 ceiling.** A prior
   default of 60000 caused every no-GPS-fix query to fail with HTTP 400
   `INVALID_ARGUMENT`. Fixed at 50000 in `EGYPT.greaterCairoRadiusMeters`.

**Open design question surfaced, not yet resolved:** `SearchRepositoryImpl::
search` returns `List<Landmark>` synchronously (§1 call graph) — this is not a
two-phase autocomplete-then-details UI. To preserve that contract, the hook
now resolves Place Details for up to `TOP_K = 5` predictions per query (not
lazily on tap), which costs more Google billing than a lazy-details design.
Confirm on-device whether the real search screen needs every row pre-resolved,
or whether a lazier seam exists, before treating `TOP_K` as final.

**Still not done — genuinely needs a live device, not just more coding:**
minting an SDK `Landmark` (Dart-heap object) and delivering it into
`SearchMenuBloc`'s result stream in place of the original. Request/response
handling is complete and independently testable via `adb logcat` alone, up to
a plain JS array of resolved candidates; only that last delivery step needs
Frida attached to the running app. See "DELIVERY (TODO)" in the hook source.


Design for the integration point selected in `SEARCH-AUDIT.md`:
a runtime hook on `SearchRepositoryImpl::search` at `libapp.so+0x926cc4`, keeping
the existing search UI, destination model, routing and navigation untouched.

Scope: search only. No Premium / licensing / entitlement / billing changes.

---

## 1. Modified components

| Component | Change | Where |
| --- | --- | --- |
| `SearchRepositoryImpl::search` (Dart AOT) | **hooked at runtime** (not byte-patched) | `libapp.so+0x926cc4` |
| Google Places client | new logic run inside the hook | injected Frida module |
| `Landmark` result construction | reuse SDK `Landmark.withLatLng` | via hooked Dart context |
| Fallback | call original `SearchService::search` | `libapp.so+0x9275d8` |
| Search UI, NavigationBloc, routing, nav | **unchanged** | — |

Nothing in `libGEM.so`, `libflutter.so`, or the Dart snapshot bytes is modified.
The AOT snapshot is *hooked*, not rewritten (SEARCH-AUDIT §4D).

## 2. Google Places flow (reused, already unit-tested)

The provider logic is the one validated by 47 passing unit tests in
`packages/cairodrive_search` (that package is Flutter-free pure Dart — reused
here as the injected search logic, not as a separate app). It implements exactly
what this task requires:

- **Autocomplete (New)** — `POST https://places.googleapis.com/v1/places:autocomplete`
- **Session tokens** — one v4 UUID per typing session, reused across keystrokes,
  sent once more on Place Details, then retired
- **Debounce** (300 ms) and **stale-request cancellation** (generation counter)
- **Explicit field masks** — `X-Goog-FieldMask` header, never `*`:
  - autocomplete: `suggestions.placePrediction.{placeId,text,structuredFormat,types,distanceMeters}`
  - details: `id,displayName,formattedAddress,location,primaryType`
- **Place Details only on selection** — never per prediction
- **Egypt handling** — `regionCode=EG`, location bias to the device fix or greater
  Cairo, `languageCode` inferred from query script (Arabic input → `ar`)
- **Key** from `GOOGLE_PLACES_API_KEY` (never hardcoded; injection in §6)

## 3. Result conversion — Google → the existing Landmark model

This is the crux, and it is feasible because the SDK exposes public constructors
the artifact itself already uses:

```
Google prediction (placeId, text)                 [Autocomplete, no coordinates]
  → user selects
  → Place Details (New): location.lat/lng, displayName, formattedAddress, primaryType
  → Landmark.withLatLng(latitude: lat, longitude: lng)   [SDK: mints a native libGEM Landmark]
        ..name = displayName
        ..address = formattedAddress            (optional; via AddressInfo)
  → return List<Landmark> from the hooked SearchRepositoryImpl::search
```

Evidence this conversion is valid and already exercised by the app:
- SDK: `Landmark.withLatLng({latitude, longitude}) => Landmark()..coordinates = Coordinates(...)`
- Artifact: `AppUtils.deserializeWaypoint(String name, double lat, double lng) → Landmark`
  and `new Landmark(String, Coordinates)` call sites in `SearchScreen.smali`,
  `HistoryScreen.smali`, `FavoritesFolderScreen.smali`.

Because the returned objects are ordinary `Landmark`s, **everything downstream —
the result row UI, selection, destination creation, routing, navigation — works
unchanged.** The invariant the task demands holds:

```
Google coordinates → Landmark (existing type) → existing route calc → existing navigation
```

Autocomplete has no coordinates, so predictions are shown as rows; the single
Place Details call happens only when the user taps one, which is also where the
`Landmark` is minted — so we never build a `Landmark` we don't need.

## 4. Fallback flow

Inside the hook:

```
try Google autocomplete/details
  ├─ usable results → return the Google-derived List<Landmark>
  └─ failure / timeout / quota / no useful result
        → invoke the ORIGINAL SearchRepositoryImpl::search behaviour
          (call through to SearchService::search @ 0x9275d8)
        → return its List<Landmark>  (untouched Magic Lane search)
```

The original implementation is never removed — the hook simply chooses whether to
delegate to it. Failure taxonomy (network/timeout/quota/http/malformed/cancelled)
is the tested one; only `cancelled` (a superseded keystroke) does **not** fall
back.

## 5. Hook implementation (concrete artifact)

`patch/frida/cairodrive-search-hook.js` (in this repo) is generated from
`blutter_frida.js` and targets the recovered address. Skeleton:

```js
// Attach at SearchRepositoryImpl::search (libapp.so + 0x926cc4).
const SEARCH = 0x926cc4;             // Dart 3.12.2 / snapshot ace65428… ONLY
const base = Module.findBaseAddress('libapp.so');
Interceptor.attach(base.add(SEARCH), {
  onEnter(args) {
    // Decode the Dart 'searchText' arg using Blutter's tagged-object reader.
    this.query = readDartString(this.context /* x1/x2 per calling convention */);
  },
  onLeave(retval) {
    // Kick the async Google flow; on success, resolve the bloc's result sink
    // with Google-derived Landmarks; on failure, leave the original result.
    // (Delivery uses the SDK's own result/NativePort path — see §6 notes.)
  }
});
```

Two delivery strategies, both documented in the script; the first is preferred:

1. **Query redirect + native Landmark synthesis (preferred).** Let the hook run
   the Google flow, build `Landmark.withLatLng(...)` via the in-process SDK, and
   feed the bloc's result stream. Keeps the async contract intact.
2. **FFI-seam shaping.** Intercept `native_call` for the search JSON and shape
   the returned event — harder (must replicate libGEM's Landmark pointer
   lifecycle), kept as a fallback design only.

The script is parameterised by the Blutter-recovered address table so it can be
re-pointed when the app version changes.

## 6. Credential injection (`GOOGLE_PLACES_API_KEY`)

The key is **never** compiled into the artifact or the hook source. Delivery:

- **Local/dev:** the Frida script reads the key from an environment-supplied
  value at attach time (`frida -l hook.js --runtime=v8` with the key passed via a
  script parameter / a local file that is git-ignored).
- **CI:** the workflow injects `secrets.GOOGLE_PLACES_API_KEY` into the hook
  bundle at package time as a build input (masked in logs, never echoed, never
  committed) — same masked-secret pattern already used elsewhere in this repo.

No `*` field masks; key restricted in Google Cloud to the app's Android
package + signing SHA-1 and to **Places API (New)** only.

## 7. Rebuild / repackaging requirements

Per `SEARCH-AUDIT`, search lives in the **arm64 ABI split** (`libapp.so`), not in
`base.apk`. Two deployment modes:

### Mode 1 — external Frida (no repackaging)
Rooted device / emulator with `frida-server`. Install the **original, unmodified**
signed APK set; attach the hook at runtime. **No split is modified, nothing is
re-signed.** Best for the milestone proof.

### Mode 2 — repackaged Frida gadget (no root)
Embed `frida-gadget.so` + the hook into the app:
- **`base.apk`** — add the gadget `.so` load + hook asset; `apktool d`/`b` verified
  to round-trip (both exit 0).
- **The installed ABI split** (`split_config.arm64_v8a.apk`) — carries
  `libapp.so` + `libGEM.so` + `libflutter.so`; the gadget `.so` can live here or
  in base. **`libapp.so` itself is not modified.**
- **Re-sign the installed set together** — base + arm64 ABI split + active
  density + active language split — with **one key** (your own key for personal
  sideload), because `com.android.vending.splits.required=true` and v2/v3 signing
  require a consistent signer across the installed set. A production-only pitfall
  to avoid (called out in the task): never ship a base with **zero** native libs
  and call it built — the ABI split must be preserved and re-signed alongside.
- **Package name stays `com.generalmagic.magicearth`** for this PoC (task §9);
  identity migration is deferred.

No modification to routing/navigation/traffic/camera/map code or splits.

## 8. What this design deliberately does NOT touch

Premium, licensing, entitlement, subscription, billing, account auth — none are
read, written, or bypassed. The hook operates strictly on the search query and
the search result list.
