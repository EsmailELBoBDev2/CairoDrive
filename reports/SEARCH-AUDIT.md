# SEARCH-AUDIT.md — Magic Earth phone-search call graph & injection points

Target: the supplied artifact **`com.generalmagic.magicearth` 7.1.26.26**
(versionCode 2026112516), unmodified. Scope: phone search only. No Premium /
licensing / entitlement work.

All findings are backed by commands run against the recovered artifact or against
the published `magiclane_maps_flutter 3.1.11` source that this APK links (plugin
class present in the dex). Tooling: `apktool` decode, androguard, `strings`,
`readelf`, and **Blutter** (Dart AOT snapshot parser) built for Dart 3.12.2 and
run to completion on the recovered `libapp.so`.

Untouched copies preserved: `reports/original-archive-checksums.txt` (the six
`.7z` volumes, re-verified `OK`); working copies live out-of-tree under a scratch
dir, `original/` held read-only.

---

## 1. Independently re-verified facts

| Fact | Evidence |
| --- | --- |
| Flutter app, phone logic in `libapp.so` (arm64 split) | exported `_kDart*Snapshot*` symbols |
| Dart **3.12.2**, snapshot hash `ace654289f5abc240509fc941453ebc5` | `blutter/extract_dart_info.py` |
| **NOT obfuscated** | snapshot flags contain no `obfuscate`; 2,894 `package:…/*.dart` URIs + class names retained |
| Search UI is `package:magic_earth/features/search/*` | Blutter asm dirs |
| Search data layer is `package:coresdk_data` / `coresdk_domain` (clean arch) | Blutter asm |
| Search engine is Magic Lane, **not OSM** | `SearchService`/`GuidedAddressSearchService`→libGEM; only `openstreetmap.org` string is attribution |

## 2. The concrete call graph (with offsets in `libapp.so`)

Addresses are `libapp.so`-relative code entry points recovered by Blutter.

```
[typed query] "searchText"
  │
  ▼  package:magic_earth/features/search/search_menu/search_menu_bloc.dart
SearchMenuBloc::_searchTextEventHandler        (0x926770)   ← handles SearchTextEvent
  │            (debounce/plumbing in the bloc layer)
  ▼  package:coresdk_domain/use_cases/search_use_case.dart
SearchUseCase::search
  │
  ▼  package:coresdk_data/repositories_impl/search_repository_impl.dart
SearchRepositoryImpl::search                   (0x926cc4)   ← ★ query in, List<Landmark> out
  │   recovered log string: "[SEARCH]: Starting SearchService.search"
  │   0x9274b8: bl #0x9275d8
  ▼  package:magiclane_maps_flutter/src/search/search_service.dart
SearchService::search                          (0x9275d8)
  │
  ▼  DART↔NATIVE BOUNDARY  (dart:ffi — NOT a MethodChannel)
  │   gem_kit_native.dart: gemWebRTCNative!.native_call(jsonPtr, len)
  │   DynamicLibrary.open('libGEM.so')
  ▼  libGEM.so
SearchServiceImpl.cpp / MapPoiLandmarkStoreImpl.cpp   ← native search
  │   online POI: https://api.yelp.com/v3/businesses/search  ("<Yelp> Http request completed…")
  │   landmarks:  Wikipedia ("<Wiki> Http request completed search…")
  │   backend:    m71os.services.magicearthsdk.com   (offline index also used → may emit no traffic)
  │
  ▼  results posted back via NativePort  (gem_kit_native.dart: set_dart_port(pub.sendPort.nativePort))
  ▼  registerEventHandler callback → List<Landmark>   (Dart handles wrapping native libGEM objects)
  ▼  SearchMenuBloc emits results state → search UI list
  │
[user taps a result]  → Landmark
  ▼  destination/waypoint path (Landmark carries native pointerId + coordinates)
NavigationBloc → NavigationService::startNavigation → libGEM.so (same FFI path)
```

Sibling search entries recovered (same file, same boundary):
`SearchRepositoryImpl::searchAddress (0x921178)`,
`searchAroundPosition (0x91d528)`, `searchWithCategory (0x924918)`,
`searchAlongRouteWithCategory (0x922f7c)`; SDK side
`SearchService::searchAroundPosition (0x91df30)`,
`SearchService::searchAlongRoute (0x923238)`,
`GuidedAddressSearchService::search (0x921514)`.

**Result model consumed downstream = `Landmark`** (the Magic Lane SDK type, a
Dart handle over a native libGEM object). There is no custom "search result
entity" between the SDK and navigation — routing takes `List<Landmark>` directly.

## 3. AOT / native boundaries

1. **Dart AOT (`libapp.so`)** — machine code. Non-obfuscated, so Blutter recovers
   class/function names and code addresses (§2) and emits a Frida harness
   (`blutter_frida.js`). No source/Kernel recovery; no static append of new code.
2. **Dart→native = `dart:ffi`** — `native_call` into `libGEM.so`. This is the
   critical boundary: **search never crosses a Flutter MethodChannel**, so the
   Kotlin/Java host cannot see it. Verified in `gem_kit_native.dart` and by the
   Kotlin plugin handling only init / `sdkSettingsEvent` / connectivity /
   exceptions — no per-call search handler.
3. **Native search (`libGEM.so`)** — C++ `SearchServiceImpl`, its own HTTP
   (Yelp/Wiki via Magic Lane backend), plus offline index. Proprietary wire
   format. Results returned to Dart via **NativePort**, also host-invisible.

## 4. Injection points evaluated (A→D, as instructed)

### A. Existing Flutter/plugin bridge — **REJECTED**
There is no MethodChannel carrying the search query. The `magiclane_maps_flutter`
plugin registers `MagiclaneMapsFlutter` and `plugins.flutter.dev/gem_engine`, but
those carry init, `sdkSettingsEvent`, connectivity and exception callbacks only.
`SearchService.search` dispatches via FFI `native_call`, not a channel. The
app-level `native_app_message_channel` (`FlutterMessageHandler`) carries
`addWaypoint`, `routePath`, `androidAuto*`, `onNavigation*`,
`onSubscriptionStatusUpdated`, `requestLocationPermission`, `showDisclaimer` —
**no search query or search result**. Evidence: `gem_kit_native.dart`
`callObjectMethod → native_call`; Kotlin `MagiclaneMapsFlutterPlugin.kt`;
`FlutterMessageHandler.smali` const-string dump.

### B. Native Android host interception — **REJECTED**
Follows from A: since search is FFI (Dart→libGEM) and results return via
NativePort, the host process never observes the search request or response.
Providing an "alternate provider" from Kotlin is impossible because the Dart code
calls a fixed FFI symbol, not a swappable channel. (The host *can* push a
destination via `addWaypoint`, but that bypasses the existing search UI, so it
does not satisfy "existing search UI shows Google results".)

### C. Existing network abstraction / proxy — **REJECTED**
The SDK's `NetworkProvider` only answers connectivity queries
(`isConnected`/`isWifiConnected`) over the channel — it does **not** carry search
payloads. The actual search HTTP is issued **inside `libGEM.so` natively**
(`api.yelp.com/v3/businesses/search`, backend `m71os.services.magicearthsdk.com`).
A local proxy would have to (a) defeat any native TLS pinning, (b) reverse a
proprietary/undocumented wire protocol, (c) translate Google Places into it — and
results still return as native `Landmark` handles, not JSON. Offline search emits
no traffic at all. Not a drop-in.

### D. AOT-level modification — **SELECTED, via runtime hook (not static byte-patch)**
- **Static byte-patch to *add* Google Places: not viable.** Dart AOT has no
  linker/relocation/spare code pages and a fixed object pool; you cannot
  hand-author HTTPS+TLS+JSON+Dart-object construction and wire it in. (Trivial
  edits — flip a constant, redirect an existing call — are possible but cannot
  add a feature.)
- **Runtime instrumentation (Frida) is viable and is the least-invasive option
  that actually works**, *because the snapshot is non-obfuscated*: Blutter gives
  the exact entry address to hook, and the SDK's own public API lets a Google
  lat/lng become a real navigable `Landmark` (see SEARCH-PATCH-DESIGN.md).

## 5. Selected integration point

**Hook `SearchRepositoryImpl::search` at `libapp.so+0x926cc4` (Frida).**

Why this exact function:
- It is the **single choke point** that sees the raw query on the way in and
  controls the `List<Landmark>` on the way out — one hook covers both halves of
  the flow.
- It sits **above** the SDK/FFI boundary, so from inside the hook the SDK's public
  Dart API is reachable (`SearchService`, `Landmark`, `Coordinates`), including
  `Landmark.withLatLng` to mint navigable results from Google coordinates.
- It sits **below** the UI/bloc, so the **existing search screen is unchanged** —
  it renders whatever `List<Landmark>` the repository returns, Google-derived or
  Magic-Lane-derived, identically.
- Falling back is trivial: on any Google failure, call the original
  `SearchService::search (0x9275d8)` and return its result — the untouched Magic
  Lane path.

Query-side alternative if finer control is wanted: the query string is also
observable earlier at `SearchMenuBloc::_searchTextEventHandler (0x926770)`
(handles `SearchTextEvent`, field `"searchText"`), but that node does not own the
result list, so `0x926cc4` remains the better single hook.

## 6. Hard constraints (intrinsic, not assumptions)

- Hook addresses are valid **only for this exact snapshot** (Dart 3.12.2, hash
  `ace65428…`); a new app version needs them re-derived by re-running Blutter.
- The result contract is **async via NativePort** — an injected result set must
  be delivered the way the bloc's listener expects, not merely returned.
- `Landmark` is a **native handle**, not plain data — it must be created through
  the SDK (`Landmark.withLatLng`), which is why hooking *above* the FFI boundary
  (in Dart) matters.
- Execution requires a device where Frida can attach (rooted, or a repackaged
  Frida-gadget build); this cannot be exercised in the current sandbox (no
  Android device/emulator, `dl.google.com` blocked). See SEARCH-TEST-RESULTS.md.
