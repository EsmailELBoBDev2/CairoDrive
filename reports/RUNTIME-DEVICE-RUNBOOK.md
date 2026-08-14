# RUNTIME-DEVICE-RUNBOOK.md

The on-device chain cannot be executed from the analysis sandbox (no Android
runtime — see RUNTIME-SEARCH-TEST.md §1; not re-probed). This is the exact,
reproducible procedure to run it on your machine and capture the evidence that
completes the acceptance criteria.

Helper that automates the ADB/Frida mechanics: `patch/device-test.sh`.

## Prerequisites (on your machine, not the sandbox)
- A real Android runtime: a physical arm64 device via `adb`, or a rooted
  emulator on a KVM-capable host.
- Host tools: `adb`, `frida` + `frida-tools`.
- The modified split set: `artifacts/modified/*` (rebuild with
  `patch/build-modified-apk.sh` if absent; hashes in `artifacts/SHA256SUMS.txt`).
- Blutter's `blutter_frida.js` helpers (regenerate per `patch/frida/README.md`)
  for Mode 1.
- The Google key fixed per `reports/GOOGLE-API-KEY-FIX.md` (add the test build's
  identity to the key, or use a matching key).

## Two modes
- **Mode 2 — install the modified dev build (no root).** Uses the embedded Frida
  gadget. Identity = `com.generalmagic.magicearth` + cert SHA-1 `5D:08:…:AB:5C`.
- **Mode 1 — original app + external Frida (rooted device).** Nothing is
  repackaged. Identity = `com.generalmagic.magicearth` + original cert
  `37:05:…:14:AE`. Set `ANDROID_CERT_SHA1` in the hook to that value.

## Steps (both modes; script does 0–6)
```sh
# key in a plain file; never printed, never committed
GPK_FILE=~/gpk.txt ./patch/device-test.sh mode2      # or: mode1
```
0. device present; ABI = arm64-v8a.
1. verify install artifact: `sha256sum` matches `artifacts/SHA256SUMS.txt`;
   package `com.generalmagic.magicearth`; version 2026112516; cert `5D:08:…`.
2. install the complete split set (`adb install-multiple base + arm64 + lang + density`).
3. confirm the RUNNING package is the modified build (not the original) via
   `adb shell dumpsys package` / `pm list packages -f`.
4. push hook + key to `/data/local/tmp/`.
5. start `adb logcat` capture → `artifacts/logs/`.
6. attach: Mode 2 auto-loads the gadget; Mode 1 runs `frida -U -f … -l hook`.

## Gate must pass before anything else
The hook prints, and you must see, before it proceeds:
```
[cairodrive] gate abi: PASS
[cairodrive] gate build-id: PASS (b7188509a10e2fe7f90d3cfa65f68bc5)
[cairodrive] gate snapshot-hash: PASS
[cairodrive] version gate passed; applying offset.
[cairodrive] libapp.so base = 0x... -> SearchRepositoryImpl::search @ 0x...(base+0x926cc4)
```
If the gate FAILS, STOP: the loaded `libapp.so` differs from the analyzed binary
(`558e04e9…`), so `0x926cc4` is invalid. Re-run Blutter on the device's actual
`libapp.so` and update the address table — do not force the hook.

## Acceptance chain to observe (manual UI + logs)
| # | Action | Evidence to capture |
| --- | --- | --- |
| 1 | open existing search UI, type `Cairo Festival City` | `[cairodrive] target reached … query="Cairo Festival City"` |
| 2 | hook issues Google autocomplete | log: HTTP 200 + prediction titles |
| 3 | tap a prediction | log: Place Details HTTP 200 + `lat/lng` |
| 4 | result → Landmark | log: `Landmark.withLatLng` built; runtimeType == SDK `Landmark`; coords == Google lat/lng |
| 5 | Landmark → existing selection handler | log/breakpoint at the selection call site receiving that Landmark |
| 6 | destination + route | logcat: route calculation runs |
| 7 | navigation | logcat + UI: guidance begins to the Google coordinates |
| 8 | fallback | airplane mode / bad key → original Magic Lane search still returns results and navigates |
| 9 | Arabic | repeat 1–7 with `مهرجان القاهرة`, `مول سيتي ستارز` |

Save: `artifacts/logs/device-test-*.log` (logcat), the Frida console output, and
screenshots/screen-recording. Those artifacts are what flip the runtime rows in
the final table from BLOCKED to VERIFIED.

## Note on the result-delivery half
The hook reliably intercepts the query and can run the Google flow and build a
`Landmark` (all above the FFI boundary, using the SDK's own `Landmark.withLatLng`).
Delivering the Google `List<Landmark>` back into the bloc's async result stream is
the one part that needs on-device iteration against the live `SearchMenuBloc`
listener; steps 4–5 are where you confirm/adjust it. Everything needed to do that
(offsets, tagged-object reader, the SDK construction path) is in hand.
