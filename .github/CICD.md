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

Each branch is committed and pushed as soon as its own merge is clean. If a merge
conflicts, the workflow runs `git merge --abort`, leaves that branch untouched, and files
(or comments on) an issue labelled `upstream-sync-conflict` listing the conflicting paths
and the commands to resolve it locally. The `dev` merge is skipped entirely when `master`
conflicted, so a partially merged tree can never reach the development branch — but note
that a clean `master` is pushed even when `dev` then conflicts, and the issue body says
which branches actually moved.

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
| `GOOGLE_PLACES_API_KEY` | key for Google Places search (see below) |

## 3a. Google Places search

Place search is served by the **Google Places API (New)** Text Search endpoint instead of
the offline OSM index, because Google's POI coverage is far denser. Routing, rendering and
offline maps are untouched — this only changes where search results come from.

Add one repository secret:

| Secret | Value |
| --- | --- |
| `GOOGLE_PLACES_API_KEY` | your Places API (New) key |

The workflow passes it to Gradle, which compiles it into `BuildConfig.GOOGLE_PLACES_API_KEY`
(`OsmAnd/cairodrive.gradle`). With the secret unset the build still succeeds and search
falls back to stock offline OSM behaviour.

**Restrict the key.** It ships inside the APK and can be extracted from any copy of the app.
In the Google Cloud console set an *Android apps* restriction on it — package name
`com.cairodrive.app` plus the SHA-1 of the signing certificate (add both the release
certificate and the debug one, `keytool -list -v -keystore keystores/debug.keystore
-storepass android`) — and an API restriction limiting it to the Places API (New). An
unrestricted key on a public APK is billable by anyone who finds it.

What is registered, in `QuickSearchHelper.initSearchUICore()`:

| State | Typed search results |
| --- | --- |
| Key set **and** online | Google Places only — no OSM text, address, favourite, history, track or coordinate providers |
| Offline, or no key | Stock OsmAnd: the full offline OSM provider set |

The choice is re-evaluated in `getCore()` whenever reachability flips, so losing signal
mid-drive hands search back to the offline index rather than returning nothing.

Category browse (the Categories tab: Fuel, Restaurants, …) stays OSM-backed in both states.
It is a different feature — "every fuel station near me" is an offline index query that Text
Search does not replace — and the tab drives it through
`shallowSearch(SearchAmenityTypesAPI.class)`, so dropping that provider would leave the tab
empty. Remove the two `SearchAmenityTypesAPI` / `SearchAmenityByTypeAPI` registrations in the
Google branch to make search Google-only in the most literal sense.

Text Search is billed per request and OsmAnd searches on every keystroke, so
`GooglePlacesSearchApi` waits 400 ms for typing to settle, ignores queries under 3
characters, and caches responses for 5 minutes keyed on the query and a ~1 km rounded map
centre. A typed query costs one or two billed requests rather than one per character. The
requested field mask is also the cheapest set that still supports a map pin and a context
menu — adding fields to `FIELD_MASK` can move the request to a more expensive SKU.

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

Rotation is owned by the app, not by logcat (`CairoDriveLogWriter`):

| Rule | Value | Effect |
| --- | --- | --- |
| `MAX_FILE_BYTES` | 8 MB | start a new file once the current one passes this size |
| `MAX_FILE_AGE_MS` | 1 day | ...or once it spans this much time, whichever comes first |
| `MAX_FILE_RETENTION_MS` | **4 days** | delete a file once its first entry is older than this |
| `MAX_FILES` | 40 | hard cap on file count, oldest deleted first |
| `MAX_TOTAL_BYTES` | 320 MB | hard cap on total size, oldest deleted first |

**Log data is cleared on a 4-day cycle — nothing older than 4 days survives.** Deletion
works on whole files, so files are cut at one day (or 8 MB) to make the sweep accurate to
the day.

Four days is a **ceiling, not a floor**. Two things shorten the window:

* the 40-file / 320 MB caps are enforced with no age floor, and under heavy logcat traffic
  8 MB fills in hours — so the retained history can be well under two days;
* logging only accrues while the app runs, so an intermittently-used device holds a few
  hours of logs spread across a four-day window, not four days of logs.

The sweep runs when a file rotates *and* once an hour on its own, so the clearing keeps to
its schedule on a device that logs too lightly to rotate — but only **while the app is
running**. Nothing sweeps in the background: files left by an app that has not been
launched stay on disk until its next start, when the first write clears them.

File names and line timestamps are both UTC, so a trace stays monotonic across a timezone
change and a name can be read directly against the entries under it; the device's local
zone is recorded once on the `SESSION` line. The flush, sweep, back-off and rotation timers
run off the monotonic clock, so a clock correction cannot stall the sweep or strand files
past the window.

Retention itself is necessarily keyed on wall time. A device whose clock was wrong when a
file was written — a head unit booting with a dead RTC, before it picks up network time —
dates that file by the wrong clock, and no on-disk signal can recover its true age. So the
4-day guarantee holds for a device whose clock was right at the time of writing, and
degrades in both directions otherwise:

* a clock that was **behind** makes the file look older once corrected, and it is retired
  early — at worst immediately, losing the drive just recorded;
* a clock that was **ahead** makes it look younger, extending its life by roughly the size
  of the error, to at most just under 8 days. Beyond that the name is still in the future
  at sweep time, which is treated as expired and cleared at once.

The newest file is reopened and appended to on process start rather than superseded, so
restarting the app does not fragment the day's log into a file per launch.

Writes are queued and drained on a background thread, so logging never blocks the UI; if a
burst overflows the queue the dropped count is recorded in the next file header rather than
being silently lost. A failed open or write backs off for 10 s, so a full disk cannot spawn
one file per line.

Release builds have the logger compiled in but disabled. Build with
`CAIRODRIVE_FULL_LOGGING=true` to enable it there too — note that a build which
continuously records GPS positions to storage needs a matching privacy disclosure before
it goes on Google Play.
