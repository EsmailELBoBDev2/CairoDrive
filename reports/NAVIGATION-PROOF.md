# NAVIGATION-PROOF.md

The required end-to-end flow and its current proof status. Labels: **VERIFIED**,
**BLOCKED**, **NOT TESTED**.

```
Existing Search UI → Google Places → Google result → real Landmark
→ existing result selection → existing Destination → existing route calculation → existing navigation
```

---

## Status of each hop

| Hop | Status | Basis |
| --- | --- | --- |
| Existing Search UI renders whatever `List<Landmark>` the repo returns | VERIFIED (static) | UI is `package:magic_earth/features/search/*`; it consumes the repo output, provider-agnostic |
| Search text captured by the hook | BUILT + gated, not run | hook attaches at `SearchRepositoryImpl::search` 0x926cc4 and logs the query; needs a device to observe |
| Google Places request + coordinates | **VERIFIED (live)** | CI live probe issued the real request and obtained real lat/lng — see RUNTIME-SEARCH-TEST.md |
| Google result → real `Landmark` | Route proven, construction not run | `Landmark.withLatLng` is the app's own path — see LANDMARK-HANDOFF.md |
| Existing result-selection receives the `Landmark` | BLOCKED | needs the app running |
| Existing `Destination` created | BLOCKED | downstream of selection |
| Existing route calculation runs | BLOCKED | `RoutingService.calculateRoute(List<Landmark>)` — needs the app |
| Navigation begins | BLOCKED | `NavigationService.startNavigation` — needs the app |

## Why the BLOCKED hops cannot be shown here

Every hop from "selection receives the Landmark" onward requires the Magic Earth
process running with the hook attached. `reports/RUNTIME-SEARCH-TEST.md` documents,
with command output, that **no Android runtime is available in this sandbox**: no
`/dev/kvm` (0 virt flags), no binder/ashmem (Waydroid/redroid impossible),
unprivileged nested Docker (no module loading), and `dl.google.com` /
`ota.waydro.id` / `sourceforge.net` all blocked (no SDK/emulator/system image
obtainable). There is no device attached. This is an environment limitation, not
a code result — I am not going to print a fabricated "navigation started" line.

## What IS in hand to complete the proof on a device

- **`artifacts/modified/`** — a signed dev build (base + arm64 split with an
  embedded Frida gadget; other splits re-signed with the same key). Install the
  set, push the hook + key to the device, launch. See RUNTIME-SEARCH-TEST.md for
  the exact commands.
- **The verified Google half** — the exact request the hook runs already returns
  real coordinates in CI.
- **The selection/destination/routing/navigation call sites** are identified with
  offsets (SEARCH-AUDIT §2), so each BLOCKED hop has a concrete breakpoint to
  assert against once a device is available.

## Evidence that WILL satisfy the threshold (to capture on a device)

1. Frida log: `[cairodrive] target reached: SearchRepositoryImpl::search query="Cairo Festival City"`.
2. Frida log: Google autocomplete HTTP 200 + selected placeId + Place Details lat/lng.
3. Frida log: constructed `Landmark` `runtimeType` + coordinates == Google lat/lng.
4. Frida/logcat: original selection handler invoked with that `Landmark`.
5. logcat + UI: route calculation and turn-by-turn navigation begin to the Google
   coordinates.

Items 1, 3–5 are **BLOCKED** here (no device); item 2's Google portion is
**VERIFIED** (live CI).
