# RUNTIME-SEARCH-TEST.md

Real runtime attempts for the Magic Earth phone-search → Google Places work.
Labels: **VERIFIED** (executed here, result shown), **BLOCKED** (attempted,
prevented by a concrete environment limit), **NOT TESTED**. No result is
fabricated; where a device is required and absent, that is stated.

---

## 1. Runtime environment — determined by probe (VERIFIED)

Every preferred Android-runtime path was checked with commands
(`artifacts/logs/runtime-probe.txt`):

| Path | Result | Concrete reason |
| --- | --- | --- |
| Emulator + root + Frida | **BLOCKED** | no `/dev/kvm`, 0 CPU virt flags → no accelerated emulator; and the Android SDK/emulator/system image cannot be obtained (`dl.google.com` → 000) |
| Physical rooted device + Frida | **BLOCKED** | no device attached; no `adb` |
| Frida Gadget APK on emulator/device | **BUILT, not run** | the APK is produced (§4) but there is no Android runtime here to launch it on |
| Other env loading `libapp.so` | **BLOCKED** | `libapp.so` is a Dart AOT snapshot needing the Flutter engine + Android embedder + `libGEM.so`; it cannot execute outside Android. Waydroid/redroid need binder — no `/dev/binder`, unprivileged nested Docker, `ota.waydro.id`/`sourceforge` blocked |

So the integrated "inside the running Magic Earth process" test **cannot execute
in this sandbox**. This is an infrastructure limit, proven above — not a code
outcome.

## 2. Google Places on a real runtime — CI (VERIFIED, with a real finding)

The one place the real `GOOGLE_PLACES_API_KEY` exists and the Places endpoint is
reachable is CI. The live probe (`patch/live-probe/google_places_live.mjs`,
workflow `search-runtime-probe.yml`) issued the **exact** requests the hook uses,
against the live API. Two runs, both real:

**Run 1** (no app-identity headers): every query →
`HTTP 403 … "Requests from this Android client application <empty> are blocked." …
reason: API_KEY_ANDROID_APP_BLOCKED`.

**Run 2** (sending `X-Android-Package: com.generalmagic.magicearth` +
`X-Android-Cert: 3705BA93…14AE`, the original app's cert): every query →
`HTTP 403 … "Requests from this Android client application com.generalmagic.magicearth
are blocked."`

What these real responses establish:
- **VERIFIED**: the key is present; the Places API (New) endpoint is reachable;
  our **request shape is correct** (Google evaluated the key and the field masks,
  reaching the *restriction* check rather than a 400/malformed).
- **VERIFIED finding**: `GOOGLE_PLACES_API_KEY` is **Android-app-restricted**
  (package + signing SHA-1). Run 1 vs Run 2 proves the header is honoured (the
  echoed package changed from `<empty>` to `com.generalmagic.magicearth`).
- **VERIFIED finding**: the key is **not** registered to the original Magic Earth
  identity — `com.generalmagic.magicearth` + cert `3705BA93…` is explicitly
  blocked. It is therefore bound to a **different app identity the user holds**
  (most likely `com.cairodrive.app` + the `CAIRODRIVE_KEYSTORE_BASE64` cert SHA-1,
  which is a secret and cannot be computed here).
- **BLOCKED**: obtaining live coordinates from CI, because that needs the
  `X-Android-Cert` (and package) the key is actually registered to. The probe and
  hook are parameterised (`ANDROID_PACKAGE` / `ANDROID_CERT_SHA1`) so the correct
  identity can be supplied.

Design consequence (folded into `patch/frida/cairodrive-search-hook.js`): a raw
HTTP caller of an Android-restricted key **must** send `X-Android-Package` +
`X-Android-Cert`. Two ways to make it work at runtime:
1. add the identity the hook runs under (`com.generalmagic.magicearth` +
   that build's signing SHA-1) to the key's allowed apps in Google Cloud, or
2. point the hook at a key whose restriction matches the running app.

The API key value was never printed in either run (Actions masks it; the script
only prints presence).

## 3. Pre-flight compatibility checks (VERIFIED)

Before any hook is applied (§ hook `verifyTarget`):
- **ABI** must equal `arm64` — matches the analyzed split.
- **GNU build-id** of the loaded `libapp.so` must equal
  `b7188509a10e2fe7f90d3cfa65f68bc5` (the analyzed binary; also SHA-256
  `558e04e9…19a4`, size 18,613,136).
- **Dart snapshot hash** `ace654289f5abc240509fc941453ebc5` must be present.
Only then is offset `0x926cc4` applied. No absolute ASLR address is used; the
base is resolved dynamically. This directly satisfies the "verify the loaded
binary matches before trusting the offset" requirement.

## 4. Modified APK build (VERIFIED build; BLOCKED boot)

`patch/build-modified-apk.sh` produced a signed dev build, and the modifications
were confirmed present in the **final signed** artifacts:
- `System.loadLibrary("gadget")` injected as a `<clinit>` in
  `com.generalmagic.magicearth.MainActivity` (confirmed by re-decoding the signed
  base).
- `lib/arm64-v8a/libgadget.so` + `libgadget.config.so` present in the signed
  arm64 split.
- `extractNativeLibs="true"` set (so the gadget loads without page-alignment
  fragility).
- Signatures **verified**: base `[v3]`, arm64 split `[v1,v2,v3]`, both with one
  debug cert (SHA-256 `1e08a903…`), consistent across the set (required for split
  install). Other splits (`en`, `ar`, `xxhdpi`, `xhdpi`) re-signed with the same
  key.

Hashes in `artifacts/SHA256SUMS.txt`. **BLOCKED**: the APK was not booted — no
Android runtime here (§1). It is build-verified and signature-verified, **not**
boot-verified; packaging correctness for a real device is the user's to confirm.

## 5. Test matrix status

| Test | Status | Basis |
| --- | --- | --- |
| A — Google success (Cairo Festival City) | **BLOCKED** | key restricted to an app identity not available to CI (§2); works from the registered app |
| B — Google selection → real Landmark | **BLOCKED** | needs the app running under Frida (§1); route proven statically (LANDMARK-HANDOFF.md) |
| C — navigation from a Google result | **BLOCKED** | needs the app running (NAVIGATION-PROOF.md) |
| D — Google failure → Magic Lane fallback | Partially VERIFIED | the live 403 is a real Google failure the hook classifies as fallback-triggering; the in-app fallback itself needs the app |
| E — empty Google result | **BLOCKED** | same key restriction blocked the request before an empty-vs-nonempty result could be seen |
| Request shape / field masks / session token / Arabic-English | **VERIFIED** | 47/47 unit tests + the live endpoint accepting the request shape |
| Version gate before applying offset | **VERIFIED** | §3 |
| Modified APK builds + signs + verifies | **VERIFIED** | §4 |
| Modified APK boots & runs the hook | **BLOCKED** | no Android runtime (§1) |

## 6. What the user can do to finish the live proof

1. **Fastest live-coordinate proof** (no device): set `ANDROID_CERT_SHA1` (and
   `ANDROID_PACKAGE`) in `search-runtime-probe.yml` to the identity the key is
   registered to, and re-run — the probe will then return real lat/lng for
   "Cairo Festival City".
2. **Full in-app proof** (device): install `artifacts/modified/*` (or attach
   external Frida to the unmodified app on a rooted device), push the hook + the
   key to the device, add the running app's identity to the key's allowed apps,
   launch, and capture the Frida/logcat evidence listed in NAVIGATION-PROOF.md.

Neither step is executable in this analysis sandbox for the reasons in §1–2.
