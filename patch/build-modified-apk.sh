#!/usr/bin/env bash
#
# Builds a private development APK set of Magic Earth with a Frida Gadget
# embedded, so the search hook (patch/frida/cairodrive-search-hook.js) can run on
# a device without a separate rooted frida-server. Search only — nothing about
# Premium/licensing/entitlement is touched.
#
# The original artifact is never modified in place. Inputs are copies.
#
# Requires (all obtainable without dl.google.com): apktool.jar, uber-apk-signer.jar,
# frida gadget .so, java, unzip/zip, xz.
#
# Environment variables (paths):
#   APKTOOL, SIGNER            — jars
#   BASE_APK, ARM64_SPLIT      — original split copies
#   GADGET_SO                  — decompressed frida-gadget arm64 .so
#   HOOK_JS                    — the hook script (bundled for reference)
#   OUTDIR                     — where signed APKs are written
#
# NOTE: this produces the artifact; it does NOT boot-verify it. No Android
# runtime exists in the analysis sandbox (see reports/RUNTIME-SEARCH-TEST.md).
set -euo pipefail

WORK="$(mktemp -d)"
mkdir -p "$OUTDIR"
echo "work dir: $WORK"

# --- 1. base.apk: inject System.loadLibrary("gadget") + extractNativeLibs=true --
java -jar "$APKTOOL" d -f -o "$WORK/base" "$BASE_APK" >/dev/null
MANIFEST="$WORK/base/AndroidManifest.xml"
sed -i 's/android:extractNativeLibs="false"/android:extractNativeLibs="true"/' "$MANIFEST"

MAIN="$(find "$WORK/base" -path '*com/generalmagic/magicearth/MainActivity.smali')"
if grep -q '\.method static constructor <clinit>' "$MAIN"; then
  echo "ERROR: MainActivity already has <clinit>; merge required" >&2; exit 1
fi
# Insert a static initializer that loads the gadget at class-load (app launch).
CLINIT='.method static constructor <clinit>()V\n    .locals 1\n    const-string v0, "gadget"\n    invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V\n    return-void\n.end method\n'
awk -v ins="$CLINIT" '
  /^# direct methods/ && !done { print; printf "%s", ins; done=1; next }
  { print }
' "$MAIN" > "$MAIN.tmp" && mv "$MAIN.tmp" "$MAIN"
grep -q 'System;->loadLibrary' "$MAIN" || { echo "ERROR: clinit injection failed" >&2; exit 1; }

java -jar "$APKTOOL" b -o "$WORK/base-mod.apk" "$WORK/base" >/dev/null
echo "base rebuilt: $(stat -c%s "$WORK/base-mod.apk") bytes"

# --- 2. arm64 split: add libgadget.so + gadget config (script mode) -------------
cp "$ARM64_SPLIT" "$WORK/arm64-mod.apk"
mkdir -p "$WORK/g/lib/arm64-v8a"
cp "$GADGET_SO" "$WORK/g/lib/arm64-v8a/libgadget.so"
# Gadget config: load the hook from a device path the user pushes at test time.
# The hook and the API key are NOT baked into the APK.
cat > "$WORK/g/lib/arm64-v8a/libgadget.config.so" <<'JSON'
{
  "interaction": {
    "type": "script",
    "path": "/data/local/tmp/cairodrive-search-hook.js",
    "on_change": "reload"
  }
}
JSON
( cd "$WORK/g" && zip -q -r "$WORK/arm64-mod.apk" lib )
echo "arm64 split patched with gadget + config"

# --- 3. sign the modified set with a generated debug key (personal sideload) ----
# uber-apk-signer generates a debug keystore with --allowResign; one key for all.
java -jar "$SIGNER" --allowResign --overwrite -a "$WORK/base-mod.apk" >/dev/null 2>&1 || \
java -jar "$SIGNER" --allowResign -a "$WORK/base-mod.apk" -o "$WORK" >/dev/null
java -jar "$SIGNER" --allowResign -a "$WORK/arm64-mod.apk" -o "$WORK" >/dev/null 2>&1 || true

# Collect signed outputs
find "$WORK" -name '*-aligned-*Signed*.apk' -o -name '*-mod.apk' | while read -r f; do :; done
cp "$WORK"/*Signed*.apk "$OUTDIR"/ 2>/dev/null || true
cp "$WORK/base-mod.apk" "$OUTDIR/base-mod-unsigned.apk" 2>/dev/null || true
cp "$WORK/arm64-mod.apk" "$OUTDIR/arm64-mod.apk" 2>/dev/null || true

echo "outputs in $OUTDIR:"; ls -la "$OUTDIR"
