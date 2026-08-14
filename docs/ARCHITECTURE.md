# CairoDrive Architecture

## Why this shape

`reports/DEEP-RECON-REPORT.md` established that the supplied artifact could not
host a new search provider: its phone UI is a stripped Dart AOT snapshot with no
injection seam. The same analysis identified the engine underneath it as the
Magic Lane Maps SDK, which is published. CairoDrive therefore rebuilds the
capabilities we wanted — map, routing, navigation, Android Auto, search — as its
own application on that SDK, and owns the search architecture outright.

## Layers

```
┌──────────────────────────────────────────────────────────────┐
│ UI            SearchScreen, HomeScreen        (Flutter)      │
│               sees SearchResult only                         │
├──────────────────────────────────────────────────────────────┤
│ Handoff       DestinationController                          │
│               SearchResult → Destination → route → guidance  │
├──────────────────────────────────────────────────────────────┤
│ Search        SearchCoordinator            (pure Dart pkg)   │
│               ├── GooglePlacesSearchProvider                 │
│               └── MagicLaneSearchProvider ──┐                │
├─────────────────────────────────────────────┼────────────────┤
│ Engine ports  MapEngine RoutingEngine       │ SearchEngine   │
│               NavigationEngine              │                │
├─────────────────────────────────────────────┴────────────────┤
│ Adapters      magiclane_adapters.dart  ← ONLY SDK import     │
├──────────────────────────────────────────────────────────────┤
│ SDK           magiclane_maps_flutter 3.1.11                  │
└──────────────────────────────────────────────────────────────┘
```

Two boundaries carry the design:

1. **`packages/cairodrive_search` is pure Dart** — no Flutter, no map SDK. It
   physically cannot reach the engine except through the `MapEngineSearchPort`
   interface it declares. That is what makes the whole search layer unit-testable
   without a device, and what stops Google-specific behaviour leaking outward.
2. **`magiclane_adapters.dart` is the only file importing the SDK.** Swapping or
   upgrading the engine touches one file. Everything above depends on
   `engine_ports.dart`.

## Search flow

```
keystroke
  → SearchCoordinator.onQueryChanged        debounce 300 ms, <2 chars ignored
  → generation++ , cancel in-flight         stale responses can never land
  → GooglePlacesSearchProvider.autocomplete
        POST places.googleapis.com/v1/places:autocomplete
        X-Goog-Api-Key   (header, never a query param)
        X-Goog-FieldMask placeId,text,structuredFormat,types,distanceMeters
        sessionToken     one per typing session
        regionCode EG · languageCode inferred · locationBias circle · origin
  → results?  ──yes→ rank → SearchSuccess
     │
     └─ error or empty → MagicLaneSearchProvider.autocomplete  (offline-capable)
                          → SearchSuccess(usedFallback: true)
                          → or SearchError if that fails too
```

Selection is the only thing that triggers a Place Details call:

```
tap
  → SearchCoordinator.select(result)
  → GET places.googleapis.com/v1/places/{placeId}?sessionToken=…
        X-Goog-FieldMask id,displayName,formattedAddress,location,primaryType
  → session token retired          (billing session closed exactly once)
  → Destination.fromSearchResult   (throws if coordinates still missing)
  → RoutingEngine.calculateRoute
  → NavigationEngine.startNavigation
```

Engine results already carry coordinates, so `resolve` is a no-op for them —
selecting a fallback result costs no extra request.

## Design decisions worth stating

**Google is primary, the engine is fallback.** Google is materially better at
business and brand discovery, which is what people type. The engine is better at
map-native targets and keeps working offline — the right properties for a
fallback. Empty Google results also fall through to the engine, because it may
know a street or village Google does not.

**Autocomplete returns no coordinates.** Places API (New) genuinely does not
return a location from Autocomplete, so results carry
`needsDetailsLookup: true` and a null location until selection. `Destination`
refuses to be constructed from an unresolved result, which turns "forgot to
resolve" into a loud failure instead of navigation to `null`.

**Ranking nudges, it does not reorder.** Google's relevance order is the base
score. Proximity applies an exponential-decay lift capped below 2.0 positions,
so a nearby branch floats above a distant one but a clearly better match several
places above cannot be buried. A unit test pins both halves of that behaviour —
and caught the first implementation, where the lift exactly cancelled a
one-position gap and produced a tie.

**Cancellation is generation-based, not flag-based.** Each dispatch takes a
generation number; a response whose generation is stale is discarded rather than
published. This is what makes "type fast, then stop" deterministic.

**One error taxonomy.** `SearchFailureKind` distinguishes network, timeout,
quota, auth, http, malformed and cancelled. The UI renders a different state per
kind, and `cancelled` is specifically excluded from triggering a fallback — a
superseded request is not a failure.

## Android Auto

`CairoDriveCarAppService` (AndroidX Car App Library) under
`com.cairodrive.app`, navigation category, `geo:` NAVIGATE filter,
`automotive_app_desc.xml` declaring `template` + `notification`. The car screen
calls the Dart `SearchCoordinator` over a method channel rather than
reimplementing provider policy, so debounce, session tokens and fallback behave
identically on phone and head unit. No entitlement check gates it.

## Testing strategy

The search layer is where the logic and the risk live, so it is tested
exhaustively and hermetically (`MockClient`, no network, no device, no keys):
request construction and field masks, Arabic/English inference, the full session
token lifecycle, deferred Place Details, the seven failure kinds, response
tolerance, debounce and stale-response suppression, every fallback path,
ranking, and the destination handoff.

The app layer is verified by `flutter analyze` against the real SDK — which is
how the `TaskHandler`-based cancellation API and an unawaited-future bug in the
destination handoff were both caught.

What is **not** verified here: no APK was produced, because `dl.google.com` is
blocked by this environment's egress policy, so the Android SDK cannot be
installed. The CI workflow performs that build, signing and verification.
