# CairoDrive

Navigation for Cairo — Google Places search over the Magic Lane map, routing and
navigation engine.

**Application ID:** `com.cairodrive.app` (debug builds: `com.cairodrive.app.debug`)

This is a clean-source application. It is not a modified copy of any other app.
The engine choice comes from `reports/DEEP-RECON-REPORT.md`, which analysed the
supplied Magic Earth artifact and identified the Magic Lane Maps SDK underneath
it; CairoDrive uses that SDK directly, through its published package.

---

## Layout

```
packages/cairodrive_search/   pure-Dart search layer  (47 unit tests)
app/                          Flutter application
  lib/src/engine/             MapEngine / RoutingEngine / NavigationEngine / SearchEngine
  lib/src/engine/magiclane/   the only code that imports the Magic Lane SDK
  lib/src/navigation/         search result → destination → routing → guidance
  lib/src/ui/search/          search screen
  android/                    manifest, Gradle, Android Auto (Kotlin)
tool/flutter-build.sh         build wrapper that injects keys as --dart-define
.github/workflows/            CI: test → analyze → build → sign → verify → upload
reports/                      artifact analysis that motivated this design
```

## Credentials

Two keys. Neither is ever committed; both are resolved at build time from the
environment (CI) or `app/android/local.properties` (local, git-ignored).

| Key | Required for | Source |
| --- | --- | --- |
| `GOOGLE_PLACES_API_KEY` | Places Autocomplete + Details | existing repo secret |
| `MAGICLANE_API_KEY` | map, routing, navigation | **needs to be added** |

`MAGICLANE_API_KEY` is a Magic Lane project token. Create one at
<https://developer.magiclane.com> (free evaluation tier, no card). The SDK is
`LicenseRef-MagicLane-Proprietary`: evaluation use is free, and a commercial
licence is a conversation with Magic Lane — see
<https://www.magiclane.com/web/terms-and-conditions>. Without the token the SDK
initialises with a watermark and reduced functionality.

Degradation is deliberate and testable:

- no `GOOGLE_PLACES_API_KEY` → the app runs on engine search alone, i.e. it
  exercises the fallback path;
- no `MAGICLANE_API_KEY` → the map does not initialise and the UI says so.

### Local setup

```bash
# app/android/local.properties   (git-ignored)
GOOGLE_PLACES_API_KEY=...
MAGICLANE_API_KEY=...
```

Restrict the Google key in Cloud Console to **Android apps**, package
`com.cairodrive.app` (and `com.cairodrive.app.debug`), with the SHA-1 of the
signing certificate — the release SHA-1 from `CAIRODRIVE_KEYSTORE_BASE64`, and
your local debug SHA-1 for development. Enable only **Places API (New)**.

## Building

```bash
flutter pub get                          # in app/
tool/flutter-build.sh apk --release      # keys injected automatically
tool/flutter-build.sh apk --debug
```

The script prints only whether each key was found, never its value.

## Testing

```bash
cd packages/cairodrive_search && dart test   # 47 tests, no keys or device needed
cd app && flutter analyze
```

## Architecture

See `docs/ARCHITECTURE.md`. In short:

```
        SearchCoordinator            ← debounce, cancellation, fallback, ranking
        ├── GooglePlacesSearchProvider   (primary: POI, business, address)
        └── MagicLaneSearchProvider      (fallback: offline, map-native)
                     ↓
              SearchResult              ← one model; the UI cannot tell them apart
                     ↓
              Destination               ← CairoDrive's own type
                     ↓
        RoutingEngine → NavigationEngine   ← Magic Lane, behind our interfaces
```

Google Places specifics: Autocomplete (New) with a session token minted per
typing session and reused into the single terminating Place Details (New) call;
explicit field masks (never `*`); `regionCode=EG`; language inferred from the
query's script so Arabic input gets Arabic results; location bias from the
device fix, falling back to greater Cairo.

## Android Auto

Declared against the public AndroidX Car App Library
(`CairoDriveCarAppService`, navigation category, `geo:` NAVIGATE filter) under
CairoDrive's own package. The car screen is a thin client of the same Dart
`SearchCoordinator`, so provider policy is not duplicated in Kotlin. It has **no
dependency on any third-party entitlement or subscription check** — debug builds
allow the Desktop Head Unit so the flow can be exercised without a car.

## What is deliberately absent

No entitlement/licence system was ported from the analysed artifact. The
capabilities that mattered — Android Auto, navigation, routing, search — are
implemented directly here and gated by nothing. Where a capability depends on a
Magic Lane commercial entitlement, that is documented above rather than worked
around.
