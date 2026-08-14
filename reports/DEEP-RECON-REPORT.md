# CairoDrive / MagicEearth — Deep Repository Reconstruction Report

Branch: `MagicEearth`
Date: 2026-08-14
Every claim below is backed by a command that was actually run against the
supplied artifact. Assertions with no demonstration are marked as such.

---

## 0 / 1 — Archive verification and extraction (DEMONSTRATED)

```
$ ls -lh CairoDrive.7z.*
-rw-r--r-- 24M CairoDrive.7z.001 .. 006   (total 150,798,446 bytes)

$ 7z t CairoDrive.7z.001
Type = Split   Volumes = 6   Total Physical Size = 150798446
Everything is Ok                      # integrity: PASS
```

The six volumes are a single split 7z (LZMA2). Extraction produced exactly one
file — an **APKMirror APKM v5 bundle**:

```
com.generalmagic.magicearth_7.1.26.26.21DB1F1B.3C81F7001-2026112516_3arch_7dpi_24lang_...apkmirror.com.apkm
```

Unpacking the bundle (a ZIP) yields **1 base + 34 splits**:

| Kind | Members |
| --- | --- |
| base | `base.apk` (79.3 MB) |
| ABI (3) | `arm64_v8a` 76.8 MB, `armeabi_v7a` 60.7 MB, `x86_64` 130.7 MB |
| density (7) | ldpi, mdpi, tvdpi, hdpi, xhdpi, xxhdpi, xxxhdpi |
| language (24) | ar, de, en, es, et, fi, fr, hi, hu, in, it, ja, ko, ms, nl, pl, pt, ru, sv, th, tr, uk, vi, zh |
| bundle meta | `info.json`, `icon.png`, `APKM_installer.url`, `META-INF/APKMIRRO.*` |

`info.json`: `pname=com.generalmagic.magicearth`, `versioncode=2026112516`,
`capabilities=["auto"]`, `min_api=28`, `arches=[arm64-v8a, armeabi-v7a, x86_64]`.
The `META-INF/APKMIRRO.RSA` signature is **APKMirror's bundle signature**, not the
app's.

**Required split set to install this app** = `base.apk` + one ABI split
(`arm64_v8a` on modern phones) + the matching density split + the device-locale
language split. The 24 language / 7 density splits are per-device selectable;
`com.android.vending.splits.required=true` means base alone will not install.

**Originals: untouched.** SHA-256 of all six volumes recorded in
`reports/original-archive-checksums.txt`; re-verified `OK` after all analysis.
All extraction and rebuild happened out-of-tree in a scratch directory.

## 2 — Exact application structure (DEMONSTRATED via androguard)

| Property | Value |
| --- | --- |
| package / applicationId | `com.generalmagic.magicearth` |
| versionName | `7.1.26.26.21DB1F1B.3C81F7001` |
| versionCode | `2026112516` |
| minSdk / targetSdk / compileSdk | 28 / 36 / 36 |
| AGP / Kotlin / Gradle | 8.9.1 / 2.1.0 (JVM 17) / 8.14.4 |
| Play Billing | 8.0.0 |
| `extractNativeLibs` | false |

**Signing:** v2 + v3 schemes only, **no v1/JAR**. Certificate SHA-256
`fa91bd3a8bfa2b03bda48422b238d584f23921154a5279e721f2f90a74d38806`, self-signed
`CN=ROUTE 66, O=ROUTE 66 Switzerland GmbH, L=Brasov, C=RO`, serial `1310128374`,
SHA1withRSA, valid 2011→2038. Play distribution stamp present
(`com.android.stamp.source = https://play.google.com/store`,
`STAMP_TYPE_DISTRIBUTION_APK`).

**Components** (full manifest saved to `reports/magicearth-base-AndroidManifest.xml`):

- **Activity:** `com.generalmagic.magicearth.MainActivity` (single Flutter activity,
  `launchMode=singleTop`) + billing/GMS/zxing/urllauncher helper activities.
- **Services:** `…foreground_service.MagicEarthForegroundService`
  (`foregroundServiceType=location|dataSync`),
  `…car_connectivity_plugin.AndroidAutoService` (Car App Library),
  `GeolocatorLocationService`, GMS module/transport services, Health SDK service.
- **Receivers:** `CarAppNotificationBroadcastReceiver`, share receiver,
  `ProfileInstallReceiver`.
- **Providers:** 5 FileProviders + `androidx.startup` +
  `androidx.car.app.connection.provider`.
- **Provider authorities (package-scoped):** `…fileprovider`,
  `…flutter.image_provider`, `…flutter.share_provider`, `…file_provider`,
  `…androidx-startup`.
- **Custom permission:** `com.generalmagic.magicearth.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`.
- **Deep links:** `magicearth://`, `https://magicearth.com` (autoVerify), `geo:`,
  `google.navigation:`, plus KML/KMZ/GPX/ZIP file-type filters.
- **Android Auto:** `com.google.android.gms.car.application` →
  `automotive_app_desc.xml` (`template`, `notification`);
  `androidx.car.app.minCarApiLevel=2`; `car-app-api.level=8`; `AndroidAutoService`
  with `androidx.car.app.category.NAVIGATION` + `NAVIGATE(geo:,geo.offline:)` +
  `DIRECTIONS(geo:)`; permissions `NAVIGATION_TEMPLATES`, `ACCESS_SURFACE`;
  AA action `com.generalmagic.magicearth.ACTION_FROM_AUTO` (in `Config`).

**Native libraries** (from `split_config.arm64_v8a.apk`):

| Library | Size | Identity (demonstrated) |
| --- | --- | --- |
| `libapp.so` | 18.6 MB | **Dart AOT snapshot** — contains `_kDartVmSnapshotInstructions`, `_kDartIsolateSnapshotInstructions`, `_kDartVmSnapshotData`, `_kDartIsolateSnapshotData`. This is the whole phone application. |
| `libGEM.so` | 46.4 MB | Magic Lane / General Magic proprietary map+routing+search engine (stripped C++). Loaded via `System.loadLibrary` in `com.magiclane.sdk.util.GemNativeObj` / `GemSdk`. |
| `libflutter.so` | 11.6 MB | Flutter engine (Impeller on). |
| `libdartjni.so` | 0.12 MB | Dart↔JNI bridge. |

**Dynamic code loading / reflection / obfuscation:**
- No `DexClassLoader`/`PathClassLoader`/`InMemoryDexClassLoader` in first-party
  code (only GMS Dynamite, which is Google's).
- No `java.lang.reflect` use in `com.generalmagic.*`.
- **R8/obfuscated**: 675 first-party smali files carry `$r8$lambda$…` synthetics;
  `resources.arsc` + dex are the release-shrunk output.
- **Networking:** first-party dex has no OkHttp/Retrofit/Volley; only
  `com.google.firebase` (datatransport). *All app networking is in the Dart layer*
  (`package:dio`, `package:http`, `package:gotrue`) and in `libGEM.so`.

## 3 — Reproducible working copy (DEMONSTRATED)

```
recon/
  original/   base.apk + splits, chmod 444 (read-only, never modified)
  working/    the only mutable copy
  output/     rebuilt artifacts
  reports/    analysis
```

The recovered artifact is never edited in place.

## 4 — Reconstruction method: what is and isn't possible (DEMONSTRATED)

| Step | Command | Result |
| --- | --- | --- |
| decode | `apktool d base.apk` | **exit 0** — baksmali of 6 dex + resource decode succeed |
| rebuild | `apktool b base_decoded` | **exit 0** — `base_rebuilt.apk` (79.4 MB) produced |
| sign | — | rebuilt APK is **UNSIGNED** (no `META-INF/*.RSA`, no signing block) |

So the **Kotlin/dex + resources round-trip works**. But four hard facts follow,
each demonstrated:

1. **The rebuild does not include the app logic.** `base_rebuilt.apk` contains
   **0** `lib/` entries — the native libraries (including `libapp.so`, the Dart
   app) live in the ABI split, and nothing in the round-trip decodes,
   understands, or can modify a Dart AOT snapshot. There is no tool that turns
   `libapp.so` back into editable Dart. The phone app is therefore
   **read-only machine code**.
2. **Signing cannot reproduce the original.** The rebuilt APK must be re-signed
   with a *different* key (the ROUTE 66 private key is not, and must never be,
   available). Any install is a differently-signed app.
3. **Install requires the whole split set, consistently signed.**
   `splits.required=true` + the Play distribution stamp mean base + the needed
   ABI/density/language splits must all be re-signed with one key and installed
   together (e.g. via a session install). A lone rebuilt base does not install.
4. **`com.generalmagic.magicearth` is baked into the Dart snapshot.** The
   package string is compiled into `libapp.so` for FileProvider authorities,
   Play Billing identity, the Supabase account backend, and the AA action — see
   §12. A manifest-level rename does not change the compiled app, so billing and
   account sign-in break.

No build system was invented. The demonstrated capability is exactly:
*decode → edit Kotlin/resources → rebuild → re-sign*. That capability cannot
reach the phone search UI.

## 5 — The search subsystem: the real execution paths (DEMONSTRATED)

There are **two** search stacks. Only the first is modifiable.

### 5a. Android Auto search — Kotlin/dex, modifiable

```
SearchScreen (androidx.car.app SearchTemplate, onSearchTextChanged)
  → SearchViewModel.didChangeSearchFilter(q) → doSearchByFilter(q)
  → SearchRepository.searchByFilter(q, Coordinates)
  → com.magiclane.sdk.places.SearchService.searchByFilter$default(...)      [JNI → libGEM.so]
      preferences: SearchPreferences, cancel via SearchService.cancelSearch()
  → onCompleted → results: GemList<Landmark>
  → SearchUiState.Results → SearchScreen.getSearchResultsRows()
  → Row.onClick → AppStrings.getDriveTo / getAddAsWaypoint / getNewRoute
      → AppUtils.insertWaypoint(route, Landmark)
      → AppUtils.pushRouteOverviewScreenIfPossible(ctx, screenMgr, factory, wpts, RoutePreferences)
  → RouteOverviewRepository → RoutingService.calculateRoute$default(...)
  → NavigationRepository.startNavigation(Route, …)                          [JNI → libGEM.so]
```

Category/along-route variants: `SearchRepository.searchByCategory`,
`searchAlongRouteByCategory`, `browseImportedStoreLandmarks` over
`LandmarkStore` / `LandmarkBrowseSession`. Result carrier =
`com.magiclane.sdk.places.Landmark` (has `getCoordinates`, name, distance).

### 5b. Phone search — Dart AOT inside `libapp.so`, NOT modifiable

Recovered from the retained Dart library/type identifiers in `libapp.so`
(clean-architecture BLoC). This is the flow the brief's diagram refers to:

```
SearchPage / SearchViewPage / SearchAddressViewPage / WaypointPage   (package:magic_earth, Flutter UI)
  → SearchBloc  ← SearchTextEvent / SearchCategoryEvent / SearchNearbyPlacesEvent   (debounced input)
  → SearchUseCase
  → SearchRepositoryImpl        (SearchPreferencesEntity wraps SDK SearchPreferences)
  → package:magiclane_maps_flutter  →  com.magiclane.sdk.places.SearchService / GuidedAddressSearchService   [JNI → libGEM.so]
  → results List<Landmark> → SearchResultsUpdatedEvent → SearchResultsListState
  → user taps → SearchResultHighlightEvent → Landmark (Coordinates)
  → WaypointPage / destination → NavigationBloc → NavigationUseCase
  → RoutingService.calculateRoute → RouteBottomPanelStartNavigationButton
  → NavigationService.startNavigation
```

Supporting Dart types actually present (evidence): `SearchBloc`, `SearchEvent`,
`SearchUseCase`, `SearchRepository`, `SearchRepositoryImpl`, `SearchAddressBloc`,
`SearchCoordinatesBloc`, `SearchMenuBloc`, `SearchPreferencesEntity`,
`SearchResultsUpdatedEvent`, `SearchResultHighlightEvent`, `WaypointPage`,
`WaypointSearchBarState`, `NavigationBloc`, `NavigationUseCase`,
`NavigationRepository`, `NavigationService`,
`RouteBottomPanelStartNavigationButtonState`.

### 5c. There is no existing OSM/Nominatim/Photon/Overpass search

Demonstrated by absence: scanning `libapp.so` for
`nominatim|photon|overpass|pelias|geoapify|locationiq|/search?|/geocod`
returns **nothing**. `package:dio` / `package:http` exist but serve the Magic
Lane / Supabase backend, not a geocoder. Phone search is **100% the Magic Lane
SDK** (`SearchService`, `GuidedAddressSearchService`, `LandmarkStore`) over
`libGEM.so`. The lone `openstreetmap.org` string is a data-attribution URL.

**Consequence for §6–11 (Google Places).** The brief wants a `SearchCoordinator`
sitting in front of `SearchRepositoryImpl` (5b) with Google primary + OSM
fallback. That class is compiled Dart in `libapp.so`. **There is no injection
seam** — no Java/Kotlin interface on the phone side, no Dart plug-in point, no
editable call site. The only place a `GooglePlacesSearchProvider` could be wired
into *this artifact* is the AA `SearchRepository` (5a), which is the car UI, not
the phone search the brief describes. Implementing the requested architecture in
this binary is therefore not possible without rewriting General Magic's compiled
Dart application.

## 12 — Package migration audit (DEMONSTRATED tokens; migration not performed)

Everything a `com.cairodrive.app` rename must touch, and why it can't be
completed on this artifact:

| Surface | Where | Migratable by editing the APK? |
| --- | --- | --- |
| manifest `package`, authorities, permission, AA action | `AndroidManifest.xml` (base + splits) | Yes (apktool) |
| FileProvider authorities in code | compiled into `libapp.so` | **No** |
| Play Billing app identity | Play + `libapp.so` (`in_app_purchase_android`) | **No** — billing keyed to `com.generalmagic.magicearth` |
| Supabase account backend | `libapp.so` (`gotrue` → `sb.services.magiclane.net`) | **No** |
| signing across base+34 splits | v2/v3, `splits.required=true` | Re-sign with a *new* key only |
| AA certificate assumptions | head-unit trusts signing cert | Changes with the new key |

So a partial (manifest-only) rename yields an app whose compiled internals still
say `com.generalmagic.magicearth`: billing and sign-in fail, and Premium (§18)
never activates. This is why a genuine `com.cairodrive.app` has to be **built
from source**, not carved out of this binary.

## 13 — Android Auto after migration (analysis)

AA is fully declared and first-party (§2). It would survive a *source* rebuild
under a new package as long as: the `CarAppService` + `automotive_app_desc` +
`minCarApiLevel` + nav intent filters are carried over, the AA action constant is
renamed with the package, and the debug build is signed with a key registered for
Android Auto developer mode (or tested via DHU, which trusts the debug cert).
On *this artifact* none of that can be exercised, because the app can't be
rebuilt from source and can't be re-signed as itself.

## 18 — Entitlement architecture (DEMONSTRATED)

**Origin of entitlement state (server-side):**
- Play Billing 8.0.0 (`package:in_app_purchase_android`, manifest
  `com.android.vending.BILLING`, `ProductID{ CORE="core-yearly",
  TRIAL="core-trial" }`).
- Magic Lane account backend `https://sb.services.magiclane.net` (Supabase:
  `package:gotrue`, `/auth/v1`, `/functions/v1/{generate-challenge,
  register-passkey,verify-passkey}`), passkey/WebAuthn (`package:webauthn`,
  `package:pointycastle`).
- Native `com.magiclane.sdk.activation.ActivationService` (`activate`,
  `isActive`, `getActivationsForProduct`, `generateLicenseKey`,
  `completeActivation`) — thin JNI shims into `libGEM.so`;
  `EActivationStatus{NA,PendingActivation,Activated,…,Revoked}`.

**Local representation of feature availability:** the Dart layer caches the
resolved subscription state into `FlutterSharedPreferences` under **obfuscated
keys**. Demonstrated because the AA Kotlin plugin reads that cache:

```
AppUtils.checkSubscriptionStatus(carContext):
    prefs = getSharedPreferences("FlutterSharedPreferences")
    v = prefs.getString("flutter.K3mW8rP5nQ7xF2jL", null)
    if v != null: return v == "H9gT4nR7wK2pY5xB"
    return prefs.getString("flutter.X7pQ9kLmR2sT4vZa", null) == "A1b2C3d4E5f6G7h8"
```

The same obfuscated key/value strings are present in `libapp.so`, i.e. the Dart
side writes them after validating with the server. The AA plugin trusts the
cache.

**Consumers:** AA gates navigation on `checkSubscriptionStatus`; the phone app
gates Premium features (offline maps, elevation, traffic, recording, Android Auto
— per the bundled `premium_features/*` assets) through the same Dart state.

**Is there an authorized developer/debug entitlement mechanism? No (DEMONSTRATED
absence).** `BuildConfig.DEBUG=false`, `BUILD_TYPE="release"`, no debug build
flag, no test-backend switch, no sideloadable development licence, no
source-level premium toggle. The only "local switch" is the cached
SharedPreferences flag above — and **writing that magic value to unlock Premium
is client-side license forgery**, exactly the piracy/bypass mechanism the project
brief prohibits ("do not implement a … license generator … isolate the
entitlement dependency behind a clear interface and report exactly what is
missing"). It is therefore reported here as evidence and **deliberately not
weaponized**. Legitimate Premium in a development build requires a developer
licence / SDK entitlement issued by Magic Lane, which the artifact does not
contain.

---

## 19 — Final report

### Repository
- **Files discovered:** six 7z volumes only (`CairoDrive.7z.001–006`). Repo
  history is an unmodified OsmAnd fork (deleted at `7875ef6`); no prior
  CairoDrive/Places/Gradle/CI/signing/AA work exists.
- **Archive integrity:** `7z t` → *Everything is Ok*; SHA-256 of all volumes
  recorded and re-verified unchanged.
- **Extraction:** APKM v5 → `base.apk` + 34 splits, out-of-tree, originals
  read-only.

### Application
- **Package/version:** `com.generalmagic.magicearth` 7.1.26.26 (vc 2026112516),
  min28/target36, AGP 8.9.1, Kotlin 2.1.0.
- **Architecture:** Flutter (Dart AOT `libapp.so`) over the proprietary Magic
  Lane engine `libGEM.so`; Kotlin/dex is Play Services + Android Auto
  (`car_connectivity_plugin`, 772 classes) + Flutter plugins. R8-obfuscated,
  v2/v3-signed, Play split distribution.
- **Search classes:** phone = `SearchBloc/SearchUseCase/SearchRepositoryImpl` →
  SDK `SearchService`/`GuidedAddressSearchService` (in `libapp.so`);
  AA = `SearchScreen/SearchViewModel/SearchRepository` → SDK
  `SearchService.searchByFilter` (in dex).
- **Destination/navigation:** phone `NavigationBloc/UseCase/Service`; AA
  `AppUtils.pushRouteOverviewScreenIfPossible` → `RoutingService.calculateRoute`
  → `NavigationRepository.startNavigation`. Carrier type
  `com.magiclane.sdk.places.Landmark` (+ `Coordinates`).
- **Android Auto:** `AndroidAutoService` (Car App Library, nav category),
  `automotive_app_desc.xml`, `minCarApiLevel=2`, `car-app-api.level=8`.
- **Entitlement:** Play Billing + Supabase account + `sdk.activation` (native),
  cached to obfuscated `FlutterSharedPreferences` flags; no dev/debug mechanism.

### Changes
- **Google Places integration point:** none applied to the artifact — no seam
  exists (§5c/§12). The correct integration point in a *source* build is
  `SearchRepositoryImpl` behind a new `SearchCoordinator`.
- **OSM fallback point:** N/A — no OSM search exists to fall back to; it would
  have to be added.
- **Result→navigation adapter:** identified (`Landmark`+`Coordinates` →
  `RoutingService`/`NavigationService`); not implementable in the binary.
- **Package migration / AA changes / CI / signing:** not performed — blocked
  below.

### Build (DEMONSTRATED)
- **Command run:** `apktool d base.apk` (exit 0) → `apktool b base_decoded`
  (exit 0).
- **Generated APK:** `recon/output/base_rebuilt.apk`, 79.4 MB, **unsigned**,
  0 native libs.
- **Package ID:** unchanged `com.generalmagic.magicearth`.
- **Signature verification:** N/A — the rebuild is unsigned; it cannot be signed
  as the original (ROUTE 66 key absent) and would not install without the full,
  consistently re-signed split set.

### Tests
Not run. Installing/launching/searching/testing AA all require a
buildable+signable+installable app, which §4 demonstrates this artifact is not.
The 16-point matrix (`Cairo Festival City`, `Mall of Egypt`, `City Stars`,
Arabic/English queries, timeouts, OSM fallback, AA, install-alongside, …) belongs
to the source build and is carried forward to that plan.

### Blockers (demonstrated by the build/analysis process)
1. **No editable app.** The phone search/destination/navigation is a Dart AOT
   snapshot (`libapp.so`); `apktool` round-trips only dex+resources and produces
   an APK with 0 native libs. No decompiler/recompiler exists for the Dart app.
   → Google Places cannot be inserted into this binary.
2. **No injection seam.** `SearchCoordinator`/`GooglePlacesSearchProvider` per
   the brief must sit in front of `SearchRepositoryImpl`, which is compiled Dart.
   The only Kotlin search path is Android Auto's.
3. **Signing/identity.** Rebuild is unsigned; can't re-sign as ROUTE 66;
   `splits.required=true` + Play stamp force whole-set re-signing; and
   `com.generalmagic.magicearth` is compiled into `libapp.so` (Billing, Supabase,
   FileProviders, AA action), so a rename breaks billing and account sign-in.
4. **Entitlement is server-side with no dev mechanism.** Premium resolves via
   Play Billing + magiclane.net; the only local flag is an obfuscated
   SharedPreferences cache whose forgery is out of scope by the brief's own
   anti-piracy rule.

### The route that satisfies the brief
The engine here is the **Magic Lane Maps SDK**, published by the vendor as
`magiclane_maps_flutter` (pub.dev) with a free developer API key. A real
`com.cairodrive.app` built from source on that SDK gives the *same* renderer and
routing engine as the artifact, its own package/signing (installs alongside
Magic Earth), Android Auto via `androidx.car.app`, and a clean home for the
requested `SearchCoordinator → GooglePlacesSearchProvider + OsmSearchProvider`
architecture with the CI signing/secret-injection workflow. Additional
credential required: a Magic Lane SDK key (e.g. secret `MAGICLANE_API_KEY`),
which is not in the current secret list.

*Reproduce this analysis:* `7z x CairoDrive.7z.001` → `.apkm`; unzip →
`base.apk` + splits; `apktool d/b`; `androguard` for manifest/certs/dex;
`unzip split_config.arm64_v8a.apk 'lib/*'` + `strings` for the native libs.
Tools: p7zip 23.01, Apktool 2.12.0, androguard, `file`, `strings`, `unzip`.
