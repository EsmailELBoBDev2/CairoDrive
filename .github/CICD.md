# CairoDrive CI/CD

Two workflows drive this fork.

| Workflow | File | Trigger |
| --- | --- | --- |
| Sync with upstream OsmAnd | `.github/workflows/upstream-sync.yml` | daily at 03:17 UTC, or manually |
| Build CairoDrive (dev) | `.github/workflows/build-dev.yml` | every push to `dev`, after a successful sync, or manually |

## 1. Upstream sync

```
osmandapp/OsmAnd@master  ──merge──▶  master  ──merge──▶  dev
```

Both merges are committed and pushed automatically. If either one conflicts, the
workflow runs `git merge --abort`, pushes nothing, and files (or comments on) an issue
labelled `upstream-sync-conflict` listing the conflicting paths and the commands to
resolve it locally. The `dev` merge is skipped entirely when `master` conflicted, so a
partially merged tree can never reach the development branch.

`dev` is created from `master` on the first run if it does not exist yet.

Manual runs accept a different upstream repository and branch, which is useful for a
one-off sync against a fork or a release branch.

### If a sync is blocked

```bash
git remote add upstream https://github.com/osmandapp/OsmAnd.git
git fetch upstream master
git checkout master && git merge upstream/master   # resolve, commit
git push origin master
git checkout dev && git merge master               # resolve, commit
git push origin dev
```

Then close the tracking issue and re-run the workflow.

### Requirements

* Branch protection on `master` or `dev` must allow pushes from `GITHUB_TOKEN`, otherwise
  the push step fails. Either exempt GitHub Actions or drop protection on these branches.
* Pushes made with `GITHUB_TOKEN` intentionally do not fire `on: push`, so the sync
  dispatches the build workflow explicitly once `dev` moves.

## 2. Dev build

Builds the `cairodrive` product flavor (`applicationId com.cairodrive.app`) and uploads
three artifacts, kept for 90 days:

| Artifact | Contents | Use |
| --- | --- | --- |
| `cairodrive-aab-<run>-<sha>` | `.aab` | Google Play upload |
| `cairodrive-apk-<run>-<sha>` | debug + release `.apk` | sideload testing |
| `cairodrive-mapping-<run>-<sha>` | `mapping.txt` | de-obfuscating release stack traces |

Default variant is `CairodriveOpenglArm64` — the fastest combination the project offers:
the OpenGL core comes from a prebuilt AAR (no NDK compilation at all) and only one ABI is
packaged. Manual runs can pick a different ABI (`arm64`, `armonly`, `armv7`, `x86`, `fat`)
or the `legacy` core; selecting `legacy` additionally checks out `OsmAnd-core-legacy` and
compiles the native core with the NDK, which is considerably slower.

The debug APK is versioned `X.Y.Z#<run>-<sha>` so it is recognisable as a test build; the
release artifacts keep the clean version from `OsmAnd/build.gradle`. Pass `version_code` on
a manual run to override `versionCode` for a Play upload.

### Signing

Add these repository secrets to produce artifacts Google Play will accept:

| Secret | Value |
| --- | --- |
| `CAIRODRIVE_KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `CAIRODRIVE_KEYSTORE_PASSWORD` | keystore password |
| `CAIRODRIVE_KEY_ALIAS` | key alias |
| `CAIRODRIVE_KEY_PASSWORD` | key password |

Without them the build still succeeds, falling back to the checked-in debug keystore. Those
artifacts install fine for testing but Google Play rejects them, and the build summary says
so on every run.

Upstream points its `publishing` signing config at `/var/lib/jenkins/osmand_key`, which only
exists on OsmAnd's own build server; `OsmAnd/cairodrive.gradle` repoints it at whichever of
the two keystores above applies.

## 3. Fork-specific build configuration

Everything specific to CairoDrive lives in `OsmAnd/cairodrive.gradle` and
`OsmAnd/AndroidManifest-cairodrive.xml`, with a single `apply from` line added to
`OsmAnd/build.gradle`. Keeping it out of the upstream build files is what lets the daily
sync merge cleanly instead of conflicting on every OsmAnd release.

Build-time environment variables:

| Variable | Effect |
| --- | --- |
| `CAIRODRIVE_KEYSTORE_FILE` | path to the release keystore |
| `CAIRODRIVE_KEYSTORE_PASSWORD` / `CAIRODRIVE_KEY_ALIAS` / `CAIRODRIVE_KEY_PASSWORD` | its credentials |
| `CAIRODRIVE_FULL_LOGGING` | `true`/`false`, forces the diagnostic logger on or off for all build types |

## 4. Diagnostic logging in test builds

Debug builds ship with `BuildConfig.CAIRODRIVE_FULL_LOGGING = true`, which starts
`net.osmand.plus.cairodrive.CairoDriveLogger` from `OsmandApplication.onCreate`. It writes to:

```
Android/data/com.cairodrive.app/files/cairodrive-logs/cairodrive-<timestamp>.log
```

Pull them off the device with no root and no runtime permission:

```bash
adb pull /sdcard/Android/data/com.cairodrive.app/files/cairodrive-logs ./logs
```

Four streams land in the same rotating file set:

* **logcat** — the process' own output at verbose level, drained continuously so entries
  reach disk before the kernel ring buffer recycles them. Lines are prefixed `LOGCAT|`.
  `PlatformUtil.setVerboseLoggingForced(true)` additionally lifts OsmAnd's own
  trace/debug calls above the `android.util.Log` INFO floor, which otherwise cannot be
  raised without `adb setprop`.
* **location** — every fix from `OsmAndLocationProvider` (`FIX`), plus a 1 s sample
  (`SAMPLE`) that records position, speed, bearing, accuracy, altitude, satellite counts
  and fix state **even when nothing moved**; the `state=` field reads `MOVED` or `STILL`
  and `movedM=` carries the delta, so a stationary stretch is as visible as a drive.
* **lifecycle** — activity transitions, screen on/off, power and airplane-mode changes,
  compass headings, and a 5 s `SYSTEM` snapshot of heap, available memory, battery level
  and temperature, and free storage.
* **crashes** — uncaught exceptions on any thread, with the last known position, flushed
  to disk before the process dies.

Rotation is owned by the app, not by logcat: 8 MB per file, 40 files, 320 MB total
(`CairoDriveLogWriter`). Writes are queued and drained on a background thread, so logging
never blocks the UI; if a burst overflows the queue the dropped count is recorded in the
next file header rather than being silently lost.

Release builds have the logger compiled in but disabled. Build with
`CAIRODRIVE_FULL_LOGGING=true` to enable it there too — note that a build which
continuously records GPS positions to storage needs a matching privacy disclosure before
it goes on Google Play.
