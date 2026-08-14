# CairoDrive — modified Magic Earth dev build (Google Places search)

Frida-Gadget dev build of the supplied Magic Earth APK. Search-only
modification; nothing about Premium/licensing/entitlement is touched. Package
stays `com.generalmagic.magicearth` (installs over/with the original only if
signatures match — these are re-signed with a debug key, so uninstall the
original first, or install to a separate profile).

**Build-verified and signature-verified; NOT boot-verified** (no Android runtime
was available where it was built — see `reports/RUNTIME-SEARCH-TEST.md`). Test on
your own device/emulator.

## Files (all re-signed with one debug key)
- `base.apk`                     — base, with `System.loadLibrary("gadget")` injected + `extractNativeLibs=true`
- `split_config.arm64_v8a.apk`   — arm64 libs + `libgadget.so` + `libgadget.config.so`
- `split_config.{en,ar}.apk`     — language splits
- `split_config.{xxhdpi,xhdpi}.apk` — density splits (use the one for your device)
- `cairodrive-search-hook.js`    — the hook the gadget loads

Integrity: `../SHA256SUMS.txt`.

## Install (device/emulator, arm64)
```sh
# uninstall the original first (different signature)
adb uninstall com.generalmagic.magicearth
# install the modified split set together
adb install-multiple base.apk split_config.arm64_v8a.apk split_config.en.apk split_config.xxhdpi.apk
# provide the hook + API key at runtime (never baked into the APK)
adb push cairodrive-search-hook.js /data/local/tmp/cairodrive-search-hook.js
printf '%s' "$GOOGLE_PLACES_API_KEY" | adb shell 'cat > /data/local/tmp/gpk'
adb shell am start -n com.generalmagic.magicearth/.MainActivity
adb logcat | grep -i cairodrive     # watch the hook logs
```
The embedded gadget loads `/data/local/tmp/cairodrive-search-hook.js` on launch.

## IMPORTANT — API key restriction (from live testing)
Your `GOOGLE_PLACES_API_KEY` is **Android-app-restricted**, and it is **not**
registered to `com.generalmagic.magicearth`. Live CI calls asserting that
identity were blocked (`API_KEY_ANDROID_APP_BLOCKED`). For the hook's raw HTTP
requests to succeed, either:
- add `com.generalmagic.magicearth` + **this build's** signing SHA-1
  (`5D:08:26:4B:44:E0:E5:3F:BC:CC:70:B4:F0:16:47:4C:C6:C5:AB:5C`) to the key's
  allowed Android apps in Google Cloud, **or**
- set `ANDROID_PACKAGE` / `ANDROID_CERT_SHA1` at the top of the hook to the
  identity your key is already registered to.
Only Places API (New) needs to be enabled.

## No root?
This build embeds the gadget, so root is not required. On a rooted device you can
skip the modified APK and attach externally:
`frida -U -f com.generalmagic.magicearth -l blutter_frida.js -l cairodrive-search-hook.js`.
