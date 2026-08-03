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
