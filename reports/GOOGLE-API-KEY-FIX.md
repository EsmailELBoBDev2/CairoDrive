# GOOGLE-API-KEY-FIX.md

**Update:** the CairoDrive app itself had a real bug that made this rejection
happen unconditionally, independent of anything below. `GooglePlacesSearchProvider`
issued raw HTTP requests to the Places API without ever sending
`X-Android-Package` / `X-Android-Cert` — the headers an Android-app-restricted
key checks on every call. The Places SDK adds them automatically; a raw
`package:http` call must add them itself, or the key rejects the request as
unidentified no matter what is allow-listed in Cloud Console.

That's fixed: the app now reads its own true package name + signing-cert SHA-1
from `PackageManager` at runtime (`AppIdentity.kt` / `app_identity.dart` — never
hardcoded, since the cert differs between a local debug build and the CI-signed
release build) and attaches them to every Places request. It also logs the
*actual* Google rejection reason (e.g. `PERMISSION_DENIED — API_KEY_ANDROID_APP_BLOCKED`)
via `print`, visible in `adb logcat`, instead of the generic "rejected the API
key" that used to hide it. On startup it also logs the resolved
`package=... certSha1=...` pair directly, so it can be copy-pasted into Cloud
Console without decompiling anything.

The rest of this document — registering that (package, SHA-1) pair as an
allowed Android app for the key — is still a real, separate step that only you
can do in Cloud Console. The header fix makes the request identify itself
correctly; Cloud Console still has to be told that identity is allowed.

## What the live tests proved (facts)

| Run | Identity asserted | Google response |
| --- | --- | --- |
| CI #1 | none | `403 … "<empty> … are blocked" … API_KEY_ANDROID_APP_BLOCKED` |
| CI #2 | `com.generalmagic.magicearth` + `3705BA93…` (original cert) | `403 … "com.generalmagic.magicearth … are blocked"` |

Interpretation:
- The key is **present** and the request **shape is correct** (Google evaluated
  the key and reached the *restriction* check).
- The key is **Android-application-restricted** (package + signing SHA-1).
- The key is **NOT** registered to the original Magic Earth identity
  (`com.generalmagic.magicearth` + `3705BA93…` is explicitly blocked). It is
  therefore registered to some other app — most plausibly your CairoDrive app.

I cannot read your Cloud project, so I cannot list the exact package/SHA-1 the key
is currently bound to. You can see it in Cloud Console → APIs & Services →
Credentials → (the key) → *Application restrictions* → *Android apps*.

## The test build's true identity (verified from the artifact)

`artifacts/modified/base.apk` (SHA-256 `d5090118…`):

| Field | Value |
| --- | --- |
| Package | `com.generalmagic.magicearth` |
| versionName / Code | `7.1.26.26.21DB1F1B.3C81F7001` / `2026112516` |
| ABI | arm64-v8a |
| Signing (v1/v2/v3) | debug key |
| **Cert SHA-1** | **`5D:08:26:4B:44:E0:E5:3F:BC:CC:70:B4:F0:16:47:4C:C6:C5:AB:5C`** |
| Cert SHA-256 | `1E:08:A9:03:…:59:53` |

Original unmodified app cert (if you test Mode 1 on a rooted device instead):
`37:05:BA:93:D8:6F:95:66:CD:B4:40:97:7E:65:C8:DF:66:05:14:AE`.

## Choose one legitimate fix

### Option A — configure the EXISTING key for the test app (simplest)
In Cloud Console → the `GOOGLE_PLACES_API_KEY` key → Application restrictions →
Android apps → **Add an item**, then add the identity that will actually make the
request:

- **Mode 2 (install the modified dev build):**
  package `com.generalmagic.magicearth`, SHA-1
  `5D:08:26:4B:44:E0:E5:3F:BC:CC:70:B4:F0:16:47:4C:C6:C5:AB:5C`
- **Mode 1 (attach Frida to the original app on a rooted device):**
  package `com.generalmagic.magicearth`, SHA-1
  `37:05:BA:93:D8:6F:95:66:CD:B4:40:97:7E:65:C8:DF:66:05:14:AE`

Also ensure **Places API (New)** is under *API restrictions* for the key. Keep
the restriction — you are *adding* the test identity, not removing restrictions.

Use this only if it is acceptable for the existing key to also serve this Magic
Earth test build.

### Option B — a separate key for this test build (cleaner separation)
If `GOOGLE_PLACES_API_KEY` is intentionally reserved for `com.cairodrive.app`,
don't repurpose it. Create a new key restricted to the identity above, add it as a
**new** GitHub secret with a clear name, e.g. `GOOGLE_PLACES_API_KEY_ME_TEST`, and
point the device test at it. Document that it is the Magic Earth test-build key.
The existing secret keeps its meaning.

## What must NOT be done (and isn't, here)
- **No removing all restrictions** to force a pass.
- **No forged identity headers.** On-device the hook asserts the *true* identity
  of the running app (exactly what the Google Maps SDK does). The CI probe now
  asserts **no** identity and is a reachability/shape check only — it can never be
  the coordinate proof for an Android-restricted key.

## After the fix
- If you added the modified build's identity (Option A/Mode 2): install
  `artifacts/modified/*`, run the hook (RUNTIME-DEVICE-RUNBOOK.md); the hook's
  `X-Android-Package`/`X-Android-Cert` already match this build.
- If you used a different SHA-1 or a new key: update `ANDROID_CERT_SHA1` (and, for
  a new key, the key source) at the top of `patch/frida/cairodrive-search-hook.js`.
