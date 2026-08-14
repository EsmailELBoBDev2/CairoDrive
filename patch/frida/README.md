# patch/frida — Google Places search hook for Magic Earth

Runtime hook that routes the **existing** Magic Earth phone search through Google
Places API (New), preserving the search UI, the `Landmark` result model, and the
whole destination/routing/navigation pipeline. Search only — nothing touching
Premium/licensing/entitlement.

See `reports/SEARCH-AUDIT.md`, `SEARCH-PATCH-DESIGN.md`, `SEARCH-TEST-RESULTS.md`.

## Files
- `cairodrive-search-hook.js` — the hook. Binds to
  `SearchRepositoryImpl::search` at `libapp.so + 0x926cc4`.

## Dependency: Blutter helpers (not committed — generated, ~700 KB)
The hook uses helper functions (`getArg`, `getTaggedObjectValue`, `getDartString`,
`init`) from Blutter's generated `blutter_frida.js`. Regenerate it against the
target build:

```sh
git clone https://github.com/worawit/blutter
# system deps: cmake ninja g++ libicu-dev libcapstone-dev pkg-config
python3 blutter/blutter.py <dir-with libapp.so + libflutter.so> blutter-out
# -> blutter-out/blutter_frida.js  and the address table in blutter-out/pp.txt
```

## Address binding (build-specific)
The offsets are valid **only** for:
`com.generalmagic.magicearth` 7.1.26.26, Dart 3.12.2, snapshot
`ace654289f5abc240509fc941453ebc5`. For any other build, re-run Blutter and update
the `ADDR` table in the hook.

## Credential
`GOOGLE_PLACES_API_KEY` is resolved at attach time (script param, or a git-ignored
file pushed to the device). Never embedded in this source, never committed.

## Run (device with Frida)
```sh
frida -U -f com.generalmagic.magicearth \
      -l blutter-out/blutter_frida.js \
      -l cairodrive-search-hook.js
```
Requires a rooted device/emulator with `frida-server` (Mode 1, no re-sign), or a
repackaged Frida-gadget build (Mode 2, re-sign the split set). Neither is
runnable in the analysis sandbox — see `SEARCH-TEST-RESULTS.md`.
