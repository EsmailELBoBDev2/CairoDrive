# CairoDrive — Test Results

Status key: **PASS** = executed and passed here. **NOT RUN** = requires a device
or a credential this environment does not have. Nothing below is marked passing
on the basis of reasoning alone.

## Automated suites

| Suite | Command | Result |
| --- | --- | --- |
| Search layer | `dart test` in `packages/cairodrive_search` | **PASS — 47/47** |
| Search layer lint | `dart analyze --fatal-infos` | **PASS — no issues** |
| App widget tests | `flutter test` in `app` | **PASS — 2/2** |
| App lint | `flutter analyze` | **PASS — no issues** |

Both suites also run in CI (`search-layer-tests` needs no secrets at all, so it
gates every push including from forks).

## Requested test matrix

The brief listed 16 scenarios. They split cleanly into what a hermetic suite can
prove and what needs a running app.

### Covered by automated tests

| Scenario | How it is exercised | Result |
| --- | --- | --- |
| Arabic POI name | `مطعم أبو السيد` → asserts `languageCode: ar` | PASS |
| Arabic address | `شارع التحرير، الدقي` → asserts `languageCode: ar` | PASS |
| English address | `90th Street, New Cairo` → asserts `languageCode: en` | PASS |
| Mixed-script query | `كافيه Starbucks` → treated as Arabic | PASS |
| Cairo Festival City | full autocomplete → select → Details → coordinates | PASS |
| Mall of Egypt | request construction, header auth | PASS |
| City Stars | Egypt region + greater-Cairo bias assertions | PASS |
| Current-location search | bias + `origin` set from device fix | PASS |
| Empty query | short-circuits with zero HTTP calls | PASS |
| Rapid typing | 4 keystrokes → exactly 1 dispatched request | PASS |
| No network / airplane mode | `SocketException` → `network` → fallback | PASS |
| Google timeout | slow response → `timeout` → fallback | PASS |
| Google API error | 429→quota, 403→auth, 500→http | PASS |
| No Google result | empty result still tries the engine | PASS |
| Magic Lane fallback | serves results, flagged `usedFallback` | PASS |
| Selecting a result | resolve → `Destination` with coordinates | PASS |
| Both providers down | surfaces `SearchError`, never a stuck spinner | PASS |
| Stale response | older in-flight response cannot overwrite a newer one | PASS |
| Ranking | nearby lifted; a far top hit keeps its lead | PASS |

### NOT RUN — needs a device or emulator

| Scenario | Why not run |
| --- | --- |
| Route calculation | needs the engine initialised with a real Magic Lane token |
| Navigation start | same |
| Rerouting | needs a moving position source |
| Android Auto / DHU | needs a head unit or the Desktop Head Unit |
| Fresh install / reinstall | needs a device |
| Install alongside another nav app | needs a device; `com.cairodrive.app` is a distinct id, but that is an assertion about the manifest, not an observed install |
| Live Cairo queries against real Google | needs `GOOGLE_PLACES_API_KEY` at runtime on a device |

The automated Google tests use `MockClient` with payloads shaped like real
Places API (New) responses. They prove **our** request construction, session
handling, parsing and fallback logic. They do **not** prove Google returns a
particular place for a particular query — that is a live-network assertion and
is listed above as NOT RUN.

## Bugs found by these tests

1. **Proximity ranking cancelled itself.** With the original hyperbolic decay a
   result one position down with a much closer distance scored an exact tie
   (`0 − 0.0714` vs `1 − 1.0714`), so the nudge did nothing. Replaced with an
   exponential decay capped below 2 positions.
2. **`TaskHandler` cancellation.** `flutter analyze` against the real SDK showed
   `SearchService.cancelSearch` / `RoutingService.cancelRoute` require the
   originating handle. The first adapter called them with no argument, so
   cancellation would silently have been a no-op.
3. **Unawaited future in the destination handoff.** `return startNavigation()`
   inside a `try` let failures escape the `catch`.
4. **Layout overflow.** The engine-error panel overflowed by 48 px on a short
   viewport; caught by a widget test, fixed by making it scrollable.
5. **Incomplete Android project.** Pre-build review found no
   `settings.gradle.kts`, root `build.gradle.kts`, `gradle.properties`, wrapper
   or launcher icons. CI run #1 confirmed this independently, failing with
   *"Build failed due to use of deleted Android v1 embedding."*
6. **`compileSdk` too low.** The app declared 35; `magiclane_maps_flutter`
   3.1.11 declares 36, which AGP requires the app to meet.
