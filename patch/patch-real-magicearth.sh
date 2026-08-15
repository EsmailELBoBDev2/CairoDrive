#!/usr/bin/env bash
#
# ============================================================================
#  patch-real-magicearth.sh
# ============================================================================
#  Patches the REAL Magic Earth app (com.generalmagic.magicearth) so that
#  patch/frida/cairodrive-search-hook.js runs automatically on every launch.
#  NO rooted device, NO frida-server, NO USB-tethered `frida` session, and
#  NO compiling — this only REPACKAGES the existing signed APK you already
#  have. It does not build any new/clone app.
#
#  What it does, end to end:
#    1. Reassembles the real Magic Earth .apkm from CairoDrive.7z.001..006
#       (or uses one you point it at directly).
#    2. Merges the split APK set into ONE installable APK   (APKEditor).
#    3. Embeds the Frida gadget + your search hook, configured to auto-run
#       at launch, and re-signs the result                  (objection).
#    4. Leaves you an installable APK + exact adb commands.
#
#  Search only. Nothing about Premium / licensing / entitlement / billing is
#  touched.
#
#  RUN THIS ON YOUR OWN MACHINE. It runs third-party APK tooling against a
#  real APK — ordinary Android app-signing work, but exactly what a locked
#  cloud sandbox will refuse to execute for you. Read it, then run it.
# ----------------------------------------------------------------------------
#
#  WHY THE CI BUILD KEPT FAILING (fixed here):
#    The GitHub Actions run died with "Command line error: Too short switch:
#    -o" (exit 7). That is a 7-ZIP error, not APKEditor: 7-Zip's -o
#    output-dir switch must be GLUED to the path with NO space
#    (`-o/path`, never `-o /path`). The build never even reached APKEditor.
#    This script uses the glued form and feeds the .apkm straight to
#    APKEditor, skipping the fragile unzip step entirely.
#
# ----------------------------------------------------------------------------
#  PREREQUISITES (install these first; none of this needs dl.google.com):
#    - Java 11+                (runs APKEditor.jar)
#    - Python 3 + pip          -> `pip install objection`
#    - Android SDK build-tools on PATH: apktool, aapt/aapt2, zipalign,
#      apksigner   (objection shells out to these; install via Android
#      Studio SDK Manager or your distro, and `apt/brew install apktool`)
#    - 7z / p7zip              (reassembles the split .7z archive)
#    - adb                     (only to install on your device — no root)
#
#  Quick check that objection's helpers are visible:
#      apktool --version && zipalign 2>&1 | head -1 && apksigner --version
#
# ----------------------------------------------------------------------------
#  USAGE:
#      # from the repo root (auto-finds CairoDrive.7z.001):
#      ./patch/patch-real-magicearth.sh
#
#      # or point it at an .apkm / .apks / .xapk you already have:
#      MAGICEARTH_APKM=/path/to/magicearth.apkm ./patch/patch-real-magicearth.sh
#
#      # optional: choose where outputs land (default: ./out)
#      OUTDIR=/somewhere ./patch/patch-real-magicearth.sh
# ============================================================================
set -euo pipefail

# --- resolve paths ----------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
HOOK_JS="$SCRIPT_DIR/frida/cairodrive-search-hook.js"
GADGET_CONFIG="$SCRIPT_DIR/gadget-config.json"
OUTDIR="${OUTDIR:-$REPO_ROOT/out}"
ARCH="${ARCH:-arm64-v8a}"                 # Magic Earth search lives in the arm64 libapp.so
APKEDITOR_VERSION="${APKEDITOR_VERSION:-1.4.9}"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$OUTDIR"

say()  { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
die()  { printf '\n\033[1;31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

[ -f "$HOOK_JS" ]       || die "hook not found: $HOOK_JS"
[ -f "$GADGET_CONFIG" ] || die "gadget config not found: $GADGET_CONFIG"

# --- 0. prerequisite checks -------------------------------------------------
say "Checking prerequisites"
need() { command -v "$1" >/dev/null 2>&1 || die "missing '$1' — see PREREQUISITES at the top of this script"; }
need java
need objection
need apktool          # objection shells out to these four:
need zipalign
need apksigner
command -v aapt >/dev/null 2>&1 || command -v aapt2 >/dev/null 2>&1 || \
  die "missing aapt/aapt2 — install Android SDK build-tools"
echo "  java:      $(java -version 2>&1 | head -1)"
echo "  objection: $(objection version 2>/dev/null | head -1 || echo present)"
echo "  apktool:   $(apktool --version 2>/dev/null | head -1)"

# --- 1. locate / reassemble the real Magic Earth bundle ---------------------
say "Locating the real Magic Earth bundle"
if [ -n "${MAGICEARTH_APKM:-}" ]; then
  [ -f "$MAGICEARTH_APKM" ] || die "MAGICEARTH_APKM points to a missing file: $MAGICEARTH_APKM"
  APKM="$MAGICEARTH_APKM"
  echo "  using: $APKM"
elif [ -f "$REPO_ROOT/CairoDrive.7z.001" ]; then
  echo "  reassembling CairoDrive.7z.001..NNN (the real magicearth .apkm)"
  # 7-Zip auto-collects .002..NNN. NOTE the GLUED -o: '-o<dir>' with NO space
  # (a space is the exact "Too short switch: -o" bug that killed the CI build).
  7z x -y "-o$WORK/extracted" "$REPO_ROOT/CairoDrive.7z.001" >/dev/null
  APKM="$(find "$WORK/extracted" -maxdepth 2 -iname '*.apkm' -o -iname '*.apks' -o -iname '*.xapk' | head -1)"
  [ -n "$APKM" ] || die "no .apkm/.apks/.xapk found inside the reassembled archive"
  echo "  found: $(basename "$APKM")"
else
  die "no source found. Run from the repo root (needs CairoDrive.7z.001) or set MAGICEARTH_APKM=/path/to/magicearth.apkm"
fi

# sanity: confirm this really is Magic Earth, not something else
case "$(basename "$APKM")" in
  *magicearth*|*generalmagic*) echo "  identity OK: looks like com.generalmagic.magicearth" ;;
  *) echo "  WARNING: filename doesn't mention magicearth — continuing, but double-check this is the right bundle" ;;
esac

# --- 2. fetch APKEditor (pinned) --------------------------------------------
say "Fetching APKEditor $APKEDITOR_VERSION"
APKEDITOR_JAR="$WORK/APKEditor.jar"
if [ -n "${APKEDITOR_PATH:-}" ] && [ -f "${APKEDITOR_PATH:-}" ]; then
  cp "$APKEDITOR_PATH" "$APKEDITOR_JAR"
  echo "  using local: $APKEDITOR_PATH"
else
  curl -fSL --retry 4 -o "$APKEDITOR_JAR" \
    "https://github.com/REAndroid/APKEditor/releases/download/V${APKEDITOR_VERSION}/APKEditor-${APKEDITOR_VERSION}.jar"
  # verify it really is APKEditor (a redirect saved as .jar prints no help banner)
  java -jar "$APKEDITOR_JAR" -h >/dev/null 2>&1 || die "downloaded APKEditor.jar is not runnable — download may have failed"
  echo "  ok"
fi

# --- 3. merge the split set into ONE installable APK ------------------------
# APKEditor's 'm' takes the .apkm/.apks/.xapk file DIRECTLY (auto-detected) —
# no manual unzip needed. -i input, -o output, -f force-overwrite. It does NOT
# sign; objection re-signs in the next step, so that's fine.
say "Merging splits into one APK (APKEditor)"
MERGED="$WORK/merged.apk"
java -jar "$APKEDITOR_JAR" m -i "$APKM" -o "$MERGED" -f
[ -f "$MERGED" ] || die "APKEditor produced no output"
echo "  merged: $(du -h "$MERGED" | cut -f1)"

# --- 4. embed the Frida gadget + hook, configured to auto-run --------------
# objection copies hook.js into the APK as lib/<arch>/libfrida-gadget.script.so
# and gadget-config.json as libfrida-gadget.config.so; the gadget auto-loads
# both at launch (no USB / no frida-server). gadget-config.json's
# interaction.path MUST be "libfrida-gadget.script.so" (it is — verified).
#
# --skip-resources: Magic Earth is a ~150MB commercial Flutter app; apktool's
# full resource recompile routinely FAILS on apps this size. Skipping it still
# patches AndroidManifest (INTERNET perm, extractNativeLibs, loadLibrary), so
# the gadget still loads. objection zipaligns + apksigner-signs the output.
say "Embedding Frida gadget + search hook (objection)"
cp "$MERGED" "$WORK/stage.apk"          # objection writes <name>.objection.apk beside the source
OBJ_ARGS=(patchapk
  --source "$WORK/stage.apk"
  --architecture "$ARCH"
  --gadget-config "$GADGET_CONFIG"
  -l "$HOOK_JS"
  --skip-resources)

if ! objection "${OBJ_ARGS[@]}"; then
  echo "  first attempt failed — retrying with aapt2 + main-classes-only + single-threaded (large-app fallback)"
  objection "${OBJ_ARGS[@]}" --use-aapt2 --only-main-classes --fix-concurrency-to 1
fi

PATCHED="$WORK/stage.objection.apk"
[ -f "$PATCHED" ] || die "objection produced no .objection.apk — check its output above"

# --- 5. deliver -------------------------------------------------------------
FINAL="$OUTDIR/magicearth-search-hook.apk"
cp "$PATCHED" "$FINAL"
say "Done"
echo "  patched real Magic Earth: $FINAL"
echo "  size: $(du -h "$FINAL" | cut -f1)"
cat <<EOF

----------------------------------------------------------------------------
INSTALL & RUN (non-rooted device, USB debugging on):

  1. Uninstall any existing Magic Earth first (different signer -> can't update):
       adb uninstall com.generalmagic.magicearth   # ok if it says 'not installed'

  2. Install the patched app:
       adb install -r "$FINAL"

  3. Give the hook your Google Places key WITHOUT baking it into the APK.
     The hook reads it from /data/local/tmp/gpk (world-readable, no root):
       adb shell "echo -n 'YOUR_GOOGLE_PLACES_API_KEY' > /data/local/tmp/gpk"

  4. Launch Magic Earth. The gadget auto-loads the search hook at startup —
     no root, no USB tethering, no 'frida' session needed.

  Before step 2, confirm your device is arm64 (this build embeds an arm64-v8a
  gadget; a mismatch loads nothing, silently):
       adb shell getprop ro.product.cpu.abi      # expect: arm64-v8a

VERIFY the hook is live (it logs to logcat):
       adb logcat | grep -i cairodrive

  You should see the hook announce itself and, on a search, whether the key is
  present and whether Google Places is being queried.

----------------------------------------------------------------------------
TWO HONEST CAVEATS (neither is a script bug — they're real-world unknowns):

  * ANTI-TAMPER: Magic Earth is a real commercial app and MAY ship integrity /
    anti-Frida checks. The classic ones scan /proc/self/maps for the string
    "frida-gadget" and kill the app on launch. objection names the embedded lib
    "libfrida-gadget.so", which is exactly that giveaway. If the patched app
    crashes or exits immediately on launch, this is the likely cause — check
    `adb logcat` around startup. Mitigation is a separate step (rename the
    gadget lib / neutralize the detector); we cross that bridge only if it
    actually triggers.

  * DELIVERY: getting Google results to actually REPLACE Magic Earth's search
    results in the UI still needs live on-device Frida iteration — see the
    "DELIVERY (TODO)" section in
      $HOOK_JS
    This script gets the hook RUNNING inside the REAL app (query captured,
    Google queried, results logged). Wiring those results back into the app's
    result stream is the final step, and it can only be done with the app
    running on your device.
----------------------------------------------------------------------------
EOF
