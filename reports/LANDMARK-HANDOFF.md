# LANDMARK-HANDOFF.md

How a Google Places result becomes a real Magic Earth `Landmark` and reaches the
existing navigation path — the "result conversion" invariant. Labels:
**VERIFIED** (shown here), **BLOCKED** (needs a runtime this sandbox lacks),
**NOT TESTED**.

---

## The required conversion

```
Google Place Details → { lat, lng, displayName, formattedAddress, primaryType }
   → Magic Lane SDK Landmark   (a native libGEM object, NOT a JSON look-alike)
   → existing result-selection → existing Destination → existing routing → navigation
```

## What the downstream code actually consumes (VERIFIED — static)

- The result type flowing out of the search data layer is the SDK **`Landmark`**,
  not a custom entity. Blutter shows `SearchRepositoryImpl` / the SDK
  `SearchService::search` producing `List<Landmark>`; routing takes
  `List<Landmark>` directly (`RoutingService.calculateRoute(List<Landmark>, …)`).
- A `Landmark` is a **Dart handle over a native libGEM object** (a `pointerId`),
  created through the SDK — it cannot be faked as a plain map. Confirmed in the
  `magiclane_maps_flutter` source and by the artifact's own JNI usage
  (`com.magiclane.sdk.places.Landmark`).

## The proven construction route (VERIFIED — from the artifact + SDK)

There is an existing, public construction path that turns bare coordinates into a
navigable `Landmark`, and the artifact itself uses it:

- SDK: `Landmark.withLatLng({latitude, longitude})` →
  `Landmark()..coordinates = Coordinates(latitude, longitude)`; `..name = …`.
- Artifact (decoded smali): `AppUtils.deserializeWaypoint(String name, double lat,
  double lng) → Lcom/magiclane/sdk/places/Landmark;` and direct
  `new Landmark(String, Coordinates)` call sites in `SearchScreen.smali`,
  `HistoryScreen.smali`, `FavoritesFolderScreen.smali`,
  `PoiCategorySearchScreen.smali`.

So the Google → Landmark step uses the **same constructor the app already calls**
to build waypoints — not a new/parallel representation. Because the output is an
ordinary `Landmark`, the existing selection/destination/routing code accepts it
unchanged.

## Why the hook is placed to make this work (VERIFIED — static)

The hook sits at `SearchRepositoryImpl::search` (`libapp.so+0x926cc4`), **above**
the FFI boundary, so the in-process SDK Dart API — `Landmark`, `Coordinates`,
`Landmark.withLatLng` — is directly callable from the hooked context. (Hooking
below the FFI boundary, at `native_call`, would instead require replicating
libGEM's native `Landmark` pointer lifecycle by hand — strictly harder; see
SEARCH-AUDIT §4.)

## What is NOT yet proven

- **Invoking `Landmark.withLatLng` from the running hook** and having that real
  `Landmark` reach the original Dart selection handler: **BLOCKED**. This needs
  the app running under Frida on a device. No Android runtime exists in this
  sandbox (RUNTIME-SEARCH-TEST.md), so a real `Landmark` object has **not** been
  observed reaching the original code here. Not claimed.
- The Google side of the conversion (lat/lng actually obtained) **is** verified
  live — see RUNTIME-SEARCH-TEST.md — so the *input* to the conversion is real;
  only the in-app construction step is pending a device.

## Exact on-device check (procedure)

With the modified APK (or external Frida) attached:
1. type a query; hook logs the captured `searchText`.
2. hook runs Google autocomplete + details → `lat/lng` (verified live).
3. hook calls the SDK `Landmark.withLatLng(lat, lng)..name = displayName`.
4. assert the returned object's `runtimeType` is the SDK `Landmark` and its
   `coordinates` match the Google `lat/lng` (log via Blutter's tagged-object
   reader).
5. assert the existing selection handler receives that `Landmark` (breakpoint /
   log at the selection call site) → continues to NAVIGATION-PROOF.md.
