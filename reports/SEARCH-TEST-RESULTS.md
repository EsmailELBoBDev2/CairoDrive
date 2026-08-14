# SEARCH-TEST-RESULTS.md

Status key: **VERIFIED** = executed here with the shown result. **BLOCKED** =
requires a resource this sandbox does not have; not executed, not simulated,
not claimed. No result below is fabricated.

---

## What was verified here

### Static analysis / injection point (VERIFIED)
- Blutter built a version-matched Dart 3.12.2 VM and ran to completion on the
  recovered `libapp.so`, producing `pp.txt` (4.5 MB), `objs.txt`, per-library
  disassembly, IDA scripts, and `blutter_frida.js`.
- Recovered, with exact `libapp.so`-relative offsets:
  - `SearchRepositoryImpl::search` @ **0x926cc4** — the selected hook (query in,
    `List<Landmark>` out)
  - its call `0x9274b8: bl #0x9275d8 → SearchService::search`, and the log string
    `"[SEARCH]: Starting SearchService.search"`
  - `SearchMenuBloc::_searchTextEventHandler` @ **0x926770** (SearchTextEvent /
    `"searchText"`)
  - siblings `searchAddress 0x921178`, `searchAroundPosition 0x91d528`,
    `SearchService::searchAroundPosition 0x91df30`, etc.
- Snapshot confirmed **non-obfuscated** (Blutter flags carry no `obfuscate`),
  which is what makes the address recovery reliable.
- Dispatch confirmed **FFI, not MethodChannel** (`gem_kit_native.dart`
  `native_call` + `DynamicLibrary.open('libGEM.so')`; Kotlin plugin handles only
  init/settings/connectivity/exceptions), rejecting injection options A and B.
- Native search origin confirmed in `libGEM.so`: `SearchServiceImpl.cpp`,
  `api.yelp.com/v3/businesses/search`, Wikipedia, backend
  `m71os.services.magicearthsdk.com`, rejecting the transparent-proxy option C.

### Google Places request logic (VERIFIED — mock HTTP)
The search logic that the hook injects is the pure-Dart provider in
`packages/cairodrive_search`, exercised by **47/47 passing unit tests**
(`dart test`, no network/device/key). Coverage includes: explicit field masks
(never `*`), `X-Goog-Api-Key` header auth, `regionCode=EG` + greater-Cairo/device
bias, Arabic/English/mixed-script language inference, one session token per typing
session reused into Place Details and then retired, Place Details only on
selection, debounce, stale-request cancellation, the full failure taxonomy
(network/timeout/quota/auth/http/malformed/cancelled), and the fallback decision.
- **This proves the request contract, not a live Google response.** It uses
  `MockClient`; it does not assert that Google returns a particular place.

### Result-compatibility path (VERIFIED — from artifact + SDK)
- Downstream consumes the SDK `Landmark` type directly (no custom entity).
- A Google lat/lng becomes a navigable `Landmark` via the SDK's public
  `Landmark.withLatLng(...)`, the same construction the artifact itself uses
  (`AppUtils.deserializeWaypoint(name, lat, lng) → Landmark`;
  `new Landmark(String, Coordinates)` in `SearchScreen.smali` et al.).

### Repackaging primitives (VERIFIED earlier)
- `apktool d base.apk` and `apktool b` both exit 0. The ABI split carrying
  `libapp.so`/`libGEM.so` is separate from `base.apk` and must be preserved and
  re-signed as a set (design §7) — not collapsed into a base with zero native
  libs.

### Artifacts produced (VERIFIED present)
- `patch/frida/cairodrive-search-hook.js` — the hook, bound to 0x926cc4, with the
  tested Google request shape and the documented delivery/fallback strategy.
- Blutter output (address table + `blutter_frida.js` helpers) in the scratch
  workspace.

---

## What is BLOCKED in this environment

The integrated milestone — **Google query → one result → correct lat/lng, running
inside the modified Magic Earth app** — was **not executed**, because it requires,
and this sandbox lacks:

1. **A device where Frida can attach** — a rooted Android device/emulator with
   `frida-server`, or a repackaged Frida-gadget build installed on a device. There
   is no Android device or emulator here, and the Android SDK/emulator cannot be
   installed (`dl.google.com` is blocked by the egress policy — the same block
   that stopped the earlier APK build locally).
2. **A runtime `GOOGLE_PLACES_API_KEY`** on that device (the key is a GitHub
   secret, correctly not present locally).
3. **On-device iteration of the result-delivery half** of the hook — feeding
   Google-derived `Landmark`s into the async bloc result stream over the
   NativePort contract. The query-interception half is deterministic and coded;
   the delivery half needs a live app to finalize and cannot be honestly checked
   without one.

I will not print a fabricated "returned Cairo Festival City at 30.028, 31.409 in
the app" line. That result does not exist until run on a device.

---

## Exact procedure to complete milestone-1 where a device exists

```
# 1. rooted device / emulator with frida-server running
adb push gpk /data/local/tmp/gpk            # the key, pushed out-of-band (git-ignored)
# 2. attach the hook to the ORIGINAL, unmodified signed APK set (Mode 1: no re-sign)
frida -U -f com.generalmagic.magicearth \
      -l blutter_out/blutter_frida.js \
      -l patch/frida/cairodrive-search-hook.js
# 3. open the existing Magic Earth search screen, type "Cairo Festival City"
#    EXPECT (milestone-1): console prints  [cairodrive] search query = Cairo Festival City
#    then the hook's googleAutocomplete() returns predictions; selecting one and
#    issuing Place Details yields lat/lng, converted to Landmark.withLatLng.
# 4. milestone-2: tap the result → existing NavigationBloc routes to it unchanged.
```

CI can validate the parts that do not need a device (the 47 provider tests and
`flutter analyze`) on every push; the on-device milestone needs a runner with an
emulator and Frida, which is a separate infrastructure step.

---

## Blockers summary

| Item | State | Blocker |
| --- | --- | --- |
| Injection point identified (0x926cc4) | DONE | — |
| Hook script bound to the address | DONE | — |
| Google request logic | VERIFIED (47/47 mock tests) | — |
| Result→Landmark→navigation path | VERIFIED (static) | — |
| Google query **inside the running app** | BLOCKED | no rooted device/emulator; `dl.google.com` blocked |
| Result delivery into the bloc stream | DESIGNED, not finalized | needs on-device iteration |
| Repackaged (no-root) build + re-sign | NOT DONE | needs Android build tools + signing key + device to verify |
