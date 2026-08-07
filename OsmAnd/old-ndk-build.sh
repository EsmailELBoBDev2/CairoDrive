#!/bin/bash

SCRIPT_LOC="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
NAME=$(basename $(dirname "${BASH_SOURCE[0]}") )
if [ -d "$ANDROID_HOME" ]; then
    # for backwards compatbility
    export ANDROID_SDK_ROOT=$ANDROID_HOME
fi
if [ -d "$ANDROID_NDK" ]; then
    export ANDROID_NDK_ROOT=$ANDROID_NDK
fi
if [ ! -d "$ANDROID_SDK_ROOT" ]; then
    echo "ANDROID_SDK is not set"
    exit
fi
if [ ! -d "$ANDROID_NDK_ROOT" ]; then
	echo "ANDROID_NDK is not set"
	exit
fi
# CairoDrive: skip the whole native build when a DIGEST-VERIFIED prebuilt library is
# already in place.
#
# Everything below this point - configure.sh building Qt, boost and protobuf, then
# ndk-build compiling the C++ routing core - is ~40 of the ~50 minutes a signed build
# spends, and it produces a byte-identical result every time until CORE_LEGACY_REF, the
# NDK, jni/*.mk, this script or the diagnostics patch changes. The signed job deliberately
# has no caches (a GitHub cache is writable from any branch, and this .so ships inside the
# Play upload), so it paid that cost on every single release.
#
# The prebuilt is trusted by SHA-256 against a digest committed to this repository, not by
# being in a cache. The verification lives in the workflow; by the time this variable is
# set the file has already been checked. If anything about that fails the workflow simply
# does not set it, and this script builds from source exactly as before - the failure mode
# is slow, never wrong.
if [ "${CAIRODRIVE_PREBUILT_NATIVE:-}" = "1" ]; then
	missing=0
	for abi in ${CAIRODRIVE_PREBUILT_ABIS:-arm64-v8a}; do
		[ -f "$SCRIPT_LOC/libs/$abi/libosmand.so" ] || missing=1
		[ -f "$SCRIPT_LOC/libc++/$abi/libc++_shared.so" ] || missing=1
	done
	if [ "$missing" = "0" ]; then
		echo "CairoDrive: using the digest-verified prebuilt native library; skipping externals and ndk-build."
		exit 0
	fi
	echo "CairoDrive: CAIRODRIVE_PREBUILT_NATIVE is set but a library is missing - building from source."
fi

export BUILD_ONLY_OLD_LIB=1
"$SCRIPT_LOC/../../core-legacy/externals/configure.sh"
# -j2 was hardcoded upstream and is the single biggest lever on this build's wall time:
# ndk-build compiles the C++ routing core once per ABI, four times over for a Play bundle,
# and at -j2 it uses half of a GitHub runner and a fraction of a modern workstation.
# Scaling to the machine is safe in a way that caching the output is not - it changes only
# how many compiler processes run at once, never which sources are compiled or what lands
# in libosmand.so. Falls back to 2 where nproc is unavailable.
NDK_JOBS="${NDK_JOBS:-$(nproc 2>/dev/null || getconf _NPROCESSORS_ONLN 2>/dev/null || echo 2)}"
echo "ndk-build with -j${NDK_JOBS}"
(cd "$SCRIPT_LOC" && "$ANDROID_NDK_ROOT/ndk-build" -j"${NDK_JOBS}")
