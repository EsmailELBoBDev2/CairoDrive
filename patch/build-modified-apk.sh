#!/usr/bin/env bash
#
# Builds a private, personal-use, no-root Frida-gadget build of Magic Earth so
# patch/frida/cairodrive-search-hook.js runs automatically on every launch —
# no rooted device, no frida-server, no USB-tethered `frida` CLI session.
# Search only — nothing about Premium/licensing/entitlement is touched.
#
# RUN THIS ON YOUR OWN MACHINE, NOT IN A SANDBOX. It installs and executes
# third-party APK-patching tools against a real APK; that's ordinary Android
# app-signing tooling, but it's exactly the kind of action a locked-down cloud
# sandbox's safety classifier will refuse to run on your behalf, no matter how
# the request is phrased. This script is meant to be read and run by you.
#
# --------------------------------------------------------------------------
# WHY THIS REPLACES THE OLD apktool/smali VERSION OF THIS SCRIPT
# --------------------------------------------------------------------------
# The previous version of this file hand-rolled everything: apktool decode,
# a manual smali <clinit> edit to call System.loadLibrary("gadget"), a raw
# `zip -r` to drop the gadget .so into the arm64 split, then SEPARATE
# uber-apk-signer calls for base.apk and the arm64 split.
#
# That's fragile in one specific way that matters a lot: Android's split-APK
# install mechanism (`adb install-multiple base.apk split.apk ...`) requires
# every part to carry the SAME signing identity as one signed set. Signing
# base.apk and the arm64 split in two separate uber-apk-signer invocations is
# not guaranteed to produce that — it depends on uber-apk-signer generating
# and reusing the exact same debug keystore both times, which is not a
# documented guarantee. A mismatch shows up as an install failure or, worse,
# a silent partial install.
#
# This version sidesteps that entire bug class: merge every split into ONE
# APK first, then patch and sign that ONE APK ONCE. There is only one
# signing identity, because there is only one file.
#
# It also replaces hand-written smali with `objection patchapk` — SensePost's
# actively maintained (v1.12.4 as of March 2026), widely used, purpose-built
# tool for exactly this: embed a Frida gadget into an APK for a non-rooted
# device. It handles the manifest INTERNET-permission check, gadget
# embedding, and signing itself. See:
#   https://github.com/sensepost/objection/wiki/Patching-Android-Applications
#   https://github.com/sensepost/objection/wiki/Gadget-Configurations
#
# --------------------------------------------------------------------------
# PREREQUISITES (install these yourself; none of this needs dl.google.com)
# --------------------------------------------------------------------------
#   - Java (for APKEditor.jar)
#   - Python 3 + pip: `pip install objection` (installs the `objection` CLI;
#     it fetches the matching frida-gadget build for you at patch time)
#   - APKEditor.jar — https://github.com/REAndroid/APKEditor/releases
#     (merges split APK sets into one installable APK)
#   - adb (only needed to install the result on your device — root not
#     required for any of this)
#
#   ALTERNATIVE to APKEditor for the merge step, if you'd rather do it
#   entirely on your phone with no PC at all: install AntiSplit-X
#   (https://github.com/Hiaashuu/AntiSplit-X) on the device and feed it the
#   .apkm directly — it merges + signs on-device. You would then still need a
#   PC for the `objection patchapk` step, since objection is a desktop tool.
#
# --------------------------------------------------------------------------
# USAGE
# --------------------------------------------------------------------------
#   APKEDITOR=/path/to/APKEditor.jar \
#   SPLIT_DIR=/path/to/extracted/apkm/apks \
#   OUTDIR=/path/to/output \
#   ./patch/build-modified-apk.sh
#
# SPLIT_DIR must contain base.apk + every split you extracted from the
# .apkm (split_config.arm64_v8a.apk at minimum — that's the one carrying
# libapp.so, which is what the hook actually attaches to; the density/
# language splits are only needed if you want resources to render correctly).
set -euo pipefail

: "${APKEDITOR:?set APKEDITOR to the path of APKEditor.jar}"
: "${SPLIT_DIR:?set SPLIT_DIR to the folder containing base.apk + splits}"
: "${OUTDIR:?set OUTDIR for build outputs}"

HOOK_JS="$(dirname "$0")/frida/cairodrive-search-hook.js"
GADGET_CONFIG="$(dirname "$0")/gadget-config.json"

mkdir -p "$OUTDIR"
WORK="$(mktemp -d)"
echo "work dir: $WORK"

# --- 1. Merge every split into one installable APK ------------------------
echo "== merging splits =="
java -jar "$APKEDITOR" m -i "$SPLIT_DIR" -o "$WORK/merged.apk"
echo "merged: $(stat -c%s "$WORK/merged.apk") bytes"

# --- 2. Embed the Frida gadget, configured to auto-run the search hook ----
# `objection patchapk` does the manifest patch (INTERNET permission,
# extractNativeLibs), embeds frida-gadget.so for the target arch, wires it to
# the hook via --gadget-config (script/autoload mode — no USB/frida CLI
# needed at runtime; see gadget-config.json), and signs the result.
#
# --architecture is explicit here rather than auto-detected from a connected
# device, since you may be building before the device is plugged in. The
# value MUST be the real Android ABI string (arm64-v8a), not a shortened
# form — confirmed against objection's own documented examples.
#
# objection does NOT take an --output flag (verified against its docs/usage
# examples — no such flag exists). It always writes its result next to the
# source file, named <source-without-extension>.objection.apk.
echo "== patching with objection =="
objection patchapk \
  --source "$WORK/merged.apk" \
  --architecture arm64-v8a \
  --gadget-config "$GADGET_CONFIG" \
  -l "$HOOK_JS"

PATCHED="$WORK/merged.objection.apk"
if [ ! -f "$PATCHED" ]; then
  echo "ERROR: expected output $PATCHED not found — objection's output naming" >&2
  echo "may have changed; check what it actually wrote in $WORK" >&2
  exit 1
fi
cp "$PATCHED" "$OUTDIR/cairodrive-magicearth-modded.apk"
echo "output: $OUTDIR/cairodrive-magicearth-modded.apk"
echo
echo "Install with: adb install -r '$OUTDIR/cairodrive-magicearth-modded.apk'"
echo "The search hook loads automatically on every launch — no root, no USB"
echo "tethering, no frida CLI session required at runtime."
