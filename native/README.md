# Prebuilt native routing library

`prebuilt-<abi>.sha256` pins the `libosmand.so` archive a **signed** build is allowed to
use, as `<sha256>  <release-tag>`.

The release job verifies the download against this digest and refuses anything else. It
also re-derives the tag from the current checkout, so a digest committed against different
inputs is rejected rather than silently used.

Nothing here affects dev builds: they compile the library and cache it as they always have.

## Regenerating

Run **Prebuild native routing library** (`native-prebuild.yml`), then commit the line it
prints. Only needed when something that changes the binary changes:

- `CORE_LEGACY_REF` in `build-dev.yml`
- `patches/cairodrive_native_diag.py`
- `OsmAnd/jni/*.mk` or `OsmAnd/old-ndk-build.sh`
- the runner's NDK revision

Until the digest is committed the signed build simply builds from source - slower, and
identical.

## Why not a cache

A GitHub cache is writable from any branch of the repository, and this binary ships inside
the reviewer-approved Play upload. A cache is trusted for where it came from; this archive
is trusted for what it is, against a digest that only a commit can change.
