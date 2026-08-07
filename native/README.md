# Prebuilt native routing library

`prebuilt-<abi>.sha256` pins the `libosmand.so` archive a **signed** build is allowed to
use, as `<sha256>  <release-tag>`.

The release job verifies the download against this digest and refuses anything else. It
also re-derives the tag from the current checkout, so a digest committed against different
inputs is rejected rather than silently used.

Nothing here affects dev builds: they compile the library and cache it as they always have.

## Regenerating - automatic

`native-prebuild.yml` maintains this file itself. It runs daily and on any push that
touches an input which can change the binary, compares the current fingerprint against
what is committed here, and **exits in seconds when they already agree**. When they do
not, it rebuilds, publishes, and commits the new digest.

Inputs that can change the binary:

- `CORE_LEGACY_REF` in `build-dev.yml`
- `patches/cairodrive_native_diag.py`
- `OsmAnd/jni/*.mk` or `OsmAnd/old-ndk-build.sh`
- the runner's NDK revision

The last one is why the schedule exists. The other four arrive with a push and are caught
by the `paths` trigger; the NDK is bumped by GitHub to nobody's plan, and before the
schedule that silently returned every signed build to ~90 minutes until someone noticed.

The digest is committed by a bot rather than a human, deliberately. "A commit is the
review" reads well and does not survive contact with what is being reviewed: a hex string
for a binary nobody can inspect by eye. What protects the Play upload is upstream of the
digest - the library is compiled from a pinned `CORE_LEGACY_REF` plus this repo's patch,
on a clean runner, with no cache - and the hash recorded here is of exactly what that job
built. Changing what a signed build links against still requires landing a commit to the
pinned ref, the patch, or the workflow.

Until a digest is committed the signed build simply builds from source - slower, and
identical.

## Why not a cache

A GitHub cache is writable from any branch of the repository, and this binary ships inside
the reviewer-approved Play upload. A cache is trusted for where it came from; this archive
is trusted for what it is, against a digest that only a commit can change.
