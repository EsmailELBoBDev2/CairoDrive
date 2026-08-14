#!/usr/bin/env bash
#
# Reproducible on-device test for the Magic Earth Google-Places search hook.
# Run this on a machine with a real Android runtime attached (physical device via
# adb, or a rooted emulator on a KVM-capable host). It installs the exact
# modified build, verifies identity, attaches the hook, and captures evidence.
#
# It does NOT print or commit the API key. Provide the key path as $GPK_FILE.
#
# Prereqs on the runner: adb, frida + frida-tools (host), the modified APK set in
# artifacts/modified/, and either a device where the modified APK installs
# (Mode 2) or a rooted device with frida-server for the original app (Mode 1).
#
# Usage:
#   GPK_FILE=/path/to/plain-key.txt ./patch/device-test.sh mode2   # install modified build
#   GPK_FILE=/path/to/plain-key.txt ./patch/device-test.sh mode1   # rooted, original app + external frida
set -euo pipefail

MODE="${1:-mode2}"
PKG="com.generalmagic.magicearth"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MOD="$REPO/artifacts/modified"
HOOK="$REPO/patch/frida/cairodrive-search-hook.js"
BLUTTER_FRIDA="${BLUTTER_FRIDA:-$REPO/patch/frida/blutter_frida.js}" # regenerate via Blutter; see patch/frida/README.md
GPK_FILE="${GPK_FILE:?set GPK_FILE to a file containing ONLY the API key}"
LOG="$REPO/artifacts/logs/device-test-$(date -u +%Y%m%dT%H%M%SZ 2>/dev/null || echo run).log"
mkdir -p "$REPO/artifacts/logs"

say() { echo "== $* =="; }

say "0. device present"
adb get-state
adb shell getprop ro.product.cpu.abi   # must be arm64-v8a for these splits

say "1. verify the artifact we are about to install (hash + identity)"
sha256sum "$MOD/base.apk" "$MOD/split_config.arm64_v8a.apk"
grep -E 'modified/base.apk|modified/split_config.arm64' "$REPO/artifacts/SHA256SUMS.txt" || true
# expected cert SHA-1 5D:08:26:4B:...:AB:5C  (see reports/GOOGLE-API-KEY-FIX.md)

if [ "$MODE" = "mode2" ]; then
  say "2. (mode2) uninstall any existing package, then install the modified split set"
  adb uninstall "$PKG" || true
  adb install-multiple \
    "$MOD/base.apk" \
    "$MOD/split_config.arm64_v8a.apk" \
    "$MOD/split_config.en.apk" \
    "$MOD/split_config.xxhdpi.apk"
  say "3. confirm the INSTALLED package identity is the modified build"
  adb shell dumpsys package "$PKG" | grep -E 'versionName|versionCode' | head -2
  adb shell pm list packages -f "$PKG"
  # confirm signer on device matches the modified cert:
  adb shell dumpsys package "$PKG" | grep -iE 'signatures|signing' | head
fi

say "4. push the hook + API key to the device (key never printed / committed)"
adb push "$HOOK" /data/local/tmp/cairodrive-search-hook.js
adb push "$GPK_FILE" /data/local/tmp/gpk
adb shell chmod 600 /data/local/tmp/gpk

say "5. start capturing logcat"
adb logcat -c
( adb logcat | grep -iE 'cairodrive|Flutter|magicearth' > "$LOG" & echo $! > /tmp/lc.pid ) || true

say "6. attach the hook"
if [ "$MODE" = "mode2" ]; then
  # The embedded gadget auto-loads the config's script; verify the gate output:
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null || true
  echo "watch $LOG for: 'version gate passed' and 'SearchRepositoryImpl::search @ ...'"
else
  # Mode 1: external frida against the original app on a rooted device.
  frida -U -f "$PKG" -l "$BLUTTER_FRIDA" -l "$HOOK" --no-pause 2>&1 | tee -a "$LOG"
fi

cat <<'NEXT'

== 7. MANUAL steps to complete the acceptance chain (capture from $LOG) ==
  a. open the existing Magic Earth search screen, type: Cairo Festival City
     EXPECT log: [cairodrive] version gate passed
                 [cairodrive] ... SearchRepositoryImpl::search @ 0x...
                 [cairodrive] target reached: ... query="Cairo Festival City"
  b. hook issues Google autocomplete -> predictions (with the matching key config)
  c. tap a prediction -> Place Details -> lat/lng in the log
  d. confirm a real Landmark is built (Landmark.withLatLng) and reaches selection
  e. start navigation -> route calculation + guidance begin
  f. fallback: force airplane mode / bad key -> original Magic Lane search still works
  g. repeat (a)-(e) with Arabic: مهرجان القاهرة , مول سيتي ستارز
  Save logcat ($LOG), Frida output, and screenshots/video as evidence.
NEXT
