# CairoDrive CI/CD

Two workflows drive this fork.

| Workflow | File | Trigger |
| --- | --- | --- |
| Sync with upstream OsmAnd | `.github/workflows/upstream-sync.yml` | daily at 03:17 UTC, or manually. Opens pull requests — never pushes to `master`/`dev`, never starts a build |
| Build CairoDrive | `.github/workflows/build-dev.yml` | `build`: every push to `dev`, or manually — **no signing keys**. `release`: a `v*` tag, or a manual run with `sign=true` — **signed, behind the `production` environment** |

## 0. The trust boundary

Everything below follows from one fact: **Gradle executes arbitrary code out of every
build script it loads.** Any job that runs `./gradlew` hands its entire environment —
every secret, every token — to whatever happens to be in the tree at that moment.

Until July 2026 the pipeline was: sync merges unreviewed `osmandapp/OsmAnd` commits →
pushes them to `dev` → dispatches the build → build runs `./gradlew` with the release
keystore, its three credentials and the Places key in the environment. One line added to
any `*.gradle` file upstream would have run against this fork's production signing
material within 24 hours, unread by anybody. The `upstream_repository` dispatch input
accepted *any* repository on GitHub, so it was not only upstream that could do it.

Three changes close that:

1. **The sync proposes, it does not push.** Upstream commits land on a `sync/*` branch and
   come to you as a pull request. Merging it is a human decision.
2. **The sync starts no build**, so nothing it produces can reach a runner holding secrets
   before that decision is made.
3. **The signing keys are not present in ordinary builds at all.** They live in the
   `production` GitHub Environment, which only the tag-triggered `release` job can read,
   and which holds that job before its first step until a reviewer approves it.

The Google Places key is deliberately *not* behind that gate, and it is worth being
explicit about why. It ships inside every APK — anyone with a copy of the app can extract
it — it is restricted to `com.cairodrive.app` plus the signing certificate SHA-1, and it
can be rotated in a browser in two minutes. The keystore is the opposite on all three
counts: it never leaves the build, a leak lets anyone ship a signed "CairoDrive", and it
cannot be rotated once the app is on Play. So the keystore gets the approval prompt and
the Places key does not. Putting a click in front of every dev build would only train you
to click through it.

## 1. Upstream sync

```
upstream/master ──merge──▶ sync/upstream-master ──PR──▶ master
                                    │
                                    └──merge──▶ sync/upstream-dev ──PR──▶ dev
```

Two pull requests, both opened by the workflow's own `GITHUB_TOKEN`. Each PR body carries
the upstream SHA, the number of new commits and files, the commit list, and — first, under
a warning — **any file in the import that CI executes**: `*.gradle`, `*.properties`, the
Gradle wrapper, `.github/workflows`. That list is the part worth reading closely; the rest
is Java that can at worst break the app.

The sync branches are rebuilt from scratch and force-pushed on each run, so an unmerged
sync PR is refreshed in place rather than a new one being opened every day. It will **not**
force-push over a sync branch whose last commit is not the bot's, so a conflict resolution
you push there is safe.

If a merge conflicts, the workflow runs `git merge --abort`, opens no PR for that branch,
and files (or comments on) an issue labelled `upstream-sync-conflict` listing the
conflicting paths and the commands to resolve it locally. The `dev` side is skipped
entirely when the `master` side conflicted, so a partially merged tree never reaches a
review. The `master` PR is still opened when only `dev` conflicts, and the issue body says
which of the two exists.

The `dev` PR is offered even on a day when upstream brought nothing new, if `dev` is still
behind a `master` import merged earlier — the two PRs are independent and merging one does
not oblige you to merge the other.

Manual runs can pick a different upstream *branch* (a release branch, say). The upstream
*repository* is an allow-list of one, `osmandapp/OsmAnd`, enforced both as a `choice` input
and re-checked at run time; widening it means editing `ALLOWED_UPSTREAM_REPOSITORIES` and
the input's `options` together, and it means accepting that whoever owns that repository
can put code in front of you.

`dev` is no longer created automatically — a PR needs a base branch that already exists. If
it is missing, create it from `master` once and the sync resumes proposing merges into it.

### If a sync is blocked

Resolve on the sync branch, not on `master`/`dev`; pushing it updates the pull request.

```bash
git fetch origin
git remote add upstream https://github.com/osmandapp/OsmAnd.git
git fetch upstream master
git checkout -B sync/upstream-master origin/master
git merge --no-ff upstream/master          # resolve, commit
git push -u origin sync/upstream-master
gh pr create --base master --head sync/upstream-master --fill

git checkout -B sync/upstream-dev origin/dev
git merge --no-ff origin/sync/upstream-master   # resolve, commit
git push -u origin sync/upstream-dev
gh pr create --base dev --head sync/upstream-dev --fill
```

Then close the tracking issue.

### Things that will surprise you

* **Pull requests opened by `GITHUB_TOKEN` do not trigger other workflows.** Dependency
  Review and Gradle Wrapper Validation therefore do not run on a sync PR. Close and reopen
  the PR by hand to make them run, and *do* check the wrapper by hand whenever the PR body
  flags `gradle/wrapper/` — a swapped `gradle-wrapper.jar` is the classic version of this
  attack.
* Creating those PRs requires **Allow GitHub Actions to create and approve pull requests**
  to be on; see the checklist in §2a.
* Closing a sync PR without merging does not stop the next run from reopening one, because
  the import is still missing from `master`. Merge it, or pin the situation with a comment.

## 2. Builds

Both jobs live in `build-dev.yml` and build the `cairodrive` product flavor
(`applicationId com.cairodrive.app`). Default variant is `CairodriveOpenglFat` — the OpenGL
core comes from a prebuilt AAR (no NDK compilation at all) and `fat` covers all four ABIs,
which is the only thing Play will roll out to an existing user base. Manual runs can pick a
narrower ABI (`arm64`, `armonly`, `armv7`, `x86`) or the `legacy` core; `legacy`
additionally checks out `OsmAnd-core-legacy` and compiles the native core with the NDK,
which is considerably slower.

The debug APK is versioned `X.Y.Z#<run>-<sha>` so it is recognisable as a test build; the
release artifacts keep the clean version from `OsmAnd/build.gradle`. `versionCode` defaults
to minutes elapsed since 2026-01-01, which is monotonic and above the `5399` pinned in
`build.gradle`; pass `version_code` on a manual run to override it.

### 2.1 Dev build — every push to `dev`

No approval, no waiting, no keys. Artifacts, kept for 90 days:

| Artifact | Contents | Use |
| --- | --- | --- |
| `cairodrive-apk-<run>-<sha>` | debug + release `.apk`, **debug-signed** | sideload testing |
| `cairodrive-aab-<run>-<sha>` | `.aab`, **debug-signed** | smoke test of the bundle/R8 path — *not* uploadable to Play |
| `cairodrive-mapping-<run>-<sha>` | `mapping.txt` | de-obfuscating release stack traces |

`OsmAnd/cairodrive.gradle` sees no keystore in the environment and repoints the `publishing`
signing config at the checked-in `keystores/debug.keystore`. The artifacts install and run
normally — this is the build you flash to the head unit. Google Play rejects them, which is
the intended difference from a release build, and the job summary says so on every run.

The Places key *is* present here, so search behaves exactly as it will in the shipped app.

### 2.2 Release build — a `v*` tag

```bash
git tag -a v4.9.10-cd1 -m "CairoDrive release 1"
git push origin v4.9.10-cd1
```

The `release` job starts, and stops immediately: `environment: production` holds it, and
GitHub emails the required reviewers. **Nothing from the tag executes until it is
approved** — approval comes before the checkout, not before the signing step. Review what
is in the tag, approve, and roughly twenty minutes later:

| Artifact | Contents |
| --- | --- |
| `cairodrive-release-aab-<run>-<sha>` | `.aab`, release-signed — upload this to Play |
| `cairodrive-release-apk-<run>-<sha>` | `.apk`, release-signed — sideload the exact shipping build |
| `cairodrive-release-mapping-<run>-<sha>` | `mapping.txt` — **keep it**, release crash reports are unreadable without it |

A manual run with `sign=true` does the same thing from any ref, for the occasional
off-tag build. It goes through the same approval.

Unlike the dev build, an incomplete secret set here is a hard failure before compiling
rather than a silent fallback to the debug keystore: a "release" that is debug-signed is
worse than no release at all.

### 2.3 Signing secrets

These four belong to the **`production` environment**, not to the repository:

| Secret | Value |
| --- | --- |
| `CAIRODRIVE_KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `CAIRODRIVE_KEYSTORE_PASSWORD` | keystore password |
| `CAIRODRIVE_KEY_ALIAS` | key alias |
| `CAIRODRIVE_KEY_PASSWORD` | key password |

Each is also accepted under its `ANDROID_*` spelling. `GOOGLE_PLACES_API_KEY` stays a
**repository** secret, because both jobs need it — see §0 for why that asymmetry is
deliberate.

Upstream points its `publishing` signing config at `/var/lib/jenkins/osmand_key`, which only
exists on OsmAnd's own build server; `OsmAnd/cairodrive.gradle` repoints it at the keystore
supplied through the environment, or at the debug keystore when none is.

## 2a. Repository settings you must apply by hand

The workflow files can only do half of this. The rest is clicking, and until it is done the
signing keys are exactly as exposed as they were before. In order:

> **VERIFIED NOT DONE, 2026-08-06.** Step 1 below has not been applied. Run 234 was
> dispatched from branch `dev` with `sign=true`; the signed job started **five seconds**
> later, asked nobody, and produced a release-signed AAB. Step 2 *has* been applied — the
> keystore secrets are on the environment and the dev job cannot read them — so the half
> that is done is the half that keeps keys out of dev builds, and the half that is missing
> is the human in the loop. This repository is **public**, so the paid-plan caveat at the
> end of step 1 does not apply: required reviewers are available and simply are not on.
>
> Of the two controls in step 1, **the deployment branch/tag rule is the more important
> one and the one that cannot be argued with** — it is enforced by GitHub outside the
> repository, so unlike anything written in a workflow file it cannot be edited away by
> whoever is doing the dispatching.

**1. Create the `production` environment.**
Settings → Environments → *New environment* → name it `production` → Configure.

* Tick **Required reviewers** and add yourself. Save protection rules.
* Leave **Prevent self-review** *off*. As the only maintainer you would otherwise be unable
  to approve your own release and no release could ever be built. Turn it on only once
  somebody else can approve.
* Under **Deployment branches and tags** choose *Selected branches and tags*, and add a
  rule of type **Tag** with the pattern `v*`. This stops the environment — and its
  secrets — from being reachable from an arbitrary branch even by a `workflow_dispatch`
  with `sign=true`. If you want the off-tag escape hatch of §2.2 to keep working, add a
  second rule of type **Branch** for `dev` instead of omitting the restriction entirely.
* *If required reviewers are greyed out:* environment protection rules need a public
  repository or a paid plan on a private one. Environment **secrets** still work on any
  plan, so do steps 2 and 3 regardless — that alone keeps the keys out of every dev build.
  Without the reviewer gate, the tag itself is the only control, so do not push a `v*` tag
  without reading the diff first.

**2. Move the four signing secrets into that environment.**
Settings → Environments → production → *Environment secrets* → add
`CAIRODRIVE_KEYSTORE_BASE64`, `CAIRODRIVE_KEYSTORE_PASSWORD`, `CAIRODRIVE_KEY_ALIAS`,
`CAIRODRIVE_KEY_PASSWORD`.

**3. Delete the repository-level copies of those four secrets.**
Settings → Secrets and variables → Actions → *Repository secrets*. This step is the whole
control: a repository secret is visible to **every** job, so leaving the old copies in
place means the dev job — the one that runs unattended on every push — can still read the
keystore, and steps 1 and 2 will have bought you nothing. Delete the `ANDROID_*` spellings
too if they exist. Leave `GOOGLE_PLACES_API_KEY` where it is.

**4. Allow Actions to open pull requests.**
Settings → Actions → General → Workflow permissions → tick **Allow GitHub Actions to create
and approve pull requests**. Without it `gh pr create` fails with `GraphQL: GitHub Actions
is not permitted to create or approve pull requests`, and the sync will have pushed a
branch with no PR in front of it. Leave the default token permission on *Read repository
contents and packages permissions*; both workflows request what they need explicitly.

**5. Protect `master` and `dev`.**
Settings → Branches → *Add branch ruleset* (or a classic protection rule) for each of
`master` and `dev`:

* **Require a pull request before merging**, with **0 required approvals**. Zero, not one:
  GitHub does not let you approve your own pull request, so with one required approval a
  solo maintainer can never merge a sync PR. Zero still forces every upstream import
  through a PR you have to look at and press Merge on, which is the control that matters.
* **Block force pushes** and **Restrict deletions**.
* Do **not** add an exemption for GitHub Actions. The old docs told you to allow
  `GITHUB_TOKEN` to push to these branches; that requirement is gone and the exemption is
  now precisely the hole this work closed.
* The `sync/*` branches must stay unprotected — the workflow force-pushes them by design.

**6. Rotate what the old pipeline exposed.**
Every unattended sync-and-build cycle that ran under the previous design executed
unreviewed upstream code with the keystore, its passwords and the Places key on the runner.
Nothing suggests that happened, but the cheap half of the cleanup is worth doing:

* Regenerate `GOOGLE_PLACES_API_KEY` in the Google Cloud console and confirm its Android
  app restriction (`com.cairodrive.app` + both certificate SHA-1s) and its API restriction.
* Change the keystore and key passwords (`keytool -storepasswd`, `keytool -keypasswd`) and
  re-upload the secrets. The key material itself cannot be rotated once an app is published
  under it unless you are on Play App Signing with key upgrade enabled — which is the
  strongest argument for never letting it near an unattended build again.

**7. Optional, cheap.**
Settings → General → **Automatically delete head branches**, so merged `sync/*` branches do
not accumulate. And Settings → Actions → General → **Fork pull request workflows**: require
approval for all outside collaborators.

### What this still does not protect against

* A malicious commit in an upstream import that you merge without reading. The gate is a
  human, and a 400-commit sync PR is a lot to read — which is why the PR body lifts the
  build scripts to the top. Read those, at minimum, every time.
* Anything already merged into `dev`. The dev build has no keystore, but it does run
  arbitrary Gradle code with the Places key and a `contents: read` token.
* A compromised *first-party* action (`actions/checkout`, `actions/setup-java`,
  `actions/upload-artifact`), which are pinned to major tags rather than commit SHAs. That
  is a deliberate trade: they are GitHub-owned, and Dependabot keeps them current. Any
  third-party action added later should be pinned to a full commit SHA — neither workflow
  uses one today.
* The `contents: write` on the sync job, which GitHub cannot scope to `sync/*` alone. Step
  5 above is what confines it.

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

Add one **repository** secret — repository, not environment, so that dev builds search the
same way the shipped app does (§0 explains why this one is not behind the release gate):

| Secret | Value |
| --- | --- |
| `GOOGLE_PLACES_API_KEY` | your Places API (New) key |

Both build jobs pass it to Gradle, which compiles it into
`BuildConfig.GOOGLE_PLACES_API_KEY` (`OsmAnd/cairodrive.gradle`). With the secret unset the
build still succeeds and search falls back to stock offline OSM behaviour.

Because it is readable by any job — including one running upstream code merged into `dev` —
treat it as rotatable and keep it restricted, below.

**Restrict the key.** It ships inside the APK and can be extracted from any copy of the app.
In the Google Cloud console set an *Android apps* restriction on it — package name
`com.cairodrive.app` plus the SHA-1 of the signing certificate (add both the release
certificate and the debug one, `keytool -list -v -keystore keystores/debug.keystore
-storepass android`) — and an API restriction limiting it to the Places API (New). An
unrestricted key on a public APK is billable by anyone who finds it.

**Google answers, or nobody does.** OSM is a fallback, never a supplement — the two never
mix in one result list:

| Google's outcome | Typed search results |
| --- | --- |
| At least one result | Google only. No OSM text, address, category, favourite, history, track or coordinate rows. |
| Zero results | OSM, the full stock provider set |
| Request failed, or non-200 | OSM |
| No connection, or query under 3 characters | OSM |
| No key compiled in | OSM (stock OsmAnd, nothing is wrapped at all) |

How it works, in `QuickSearchHelper.applyGooglePlacesSearch()`: the stock providers are
registered as usual, then each is wrapped in a `GatedSearchApi` and `GooglePlacesSearchApi`
is put in front. `SearchUICore` re-checks `getSearchPriority()` for each provider at its turn
in the run loop rather than once up front, so Google takes its turn first, records the
outcome in a shared `SearchProviderGate`, and every wrapped provider then reports "do not
run" for exactly the phrase Google answered. The gate is keyed by phrase, so a verdict from
the previous keystroke can never suppress the fallback for the next one.

Wrapping whatever was registered — instead of hand-picking providers — means a provider
added by a future upstream sync is gated automatically rather than silently leaking OSM
results back into the list.

Two details worth knowing:

* `SearchAmenityTypesAPI` is *subclassed* (`GatedAmenityTypesAPI`) rather than wrapped,
  because the Categories tab and the Android Auto category screen look it up by type with
  `shallowSearch(SearchAmenityTypesAPI.class, …)` and custom POI filters find it with
  `instanceof`. A delegating wrapper would hide the type from both. `shallowSearch` calls
  `search()` directly without consulting `getSearchPriority()`, so those screens keep
  working while Google owns the typed result list.
* `SearchAmenityByTypeAPI` is rebuilt against the gated types provider rather than wrapped
  as-is, because it reads custom POI filters out of the instance it was constructed with.

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
/data/data/com.cairodrive.app/files/cairodrive-logs/cairodrive-<timestamp>.log
```

That is `Context.getFilesDir()` - the app's **private internal** storage, not
`Android/data` on the shared volume. Deliberately: minSdk is 24, and on API 24-29 there is
no scoped storage, so anything under `Android/data/<package>` is readable by any installed
app holding `READ_EXTERNAL_STORAGE` and by any USB host. A four-day record of everywhere
the user has driven does not belong there.

The cost is that `adb pull` cannot reach it. Extract with `run-as`, which works because
debug builds are debuggable - and these files only exist on a debug build anyway:

```bash
adb exec-out run-as com.cairodrive.app tar c files/cairodrive-logs > cd-logs.tar
tar xf cd-logs.tar          # unpacks to ./files/cairodrive-logs/
```

A release build has `CAIRODRIVE_FULL_LOGGING = false`, so the directory does not exist at
all and `run-as` is refused regardless. If a release build needs to be traced, build it
with `CAIRODRIVE_FULL_LOGGING=true` - and read the privacy note at the end of this section
first.

Four streams land in the same rotating file set:

* **logcat** — the process' own output, drained continuously so entries reach disk before
  the kernel ring buffer recycles them. **Not guaranteed to arrive**: some vendor ROMs
  (MIUI is confirmed) drop third-party tags out of the buffer entirely, so on those devices
  this stream contains only the framework classes the ROM injects into the app's process and
  nothing the app itself wrote. Anything that has to survive a drive - `CD_SEARCH`,
  `CD_ROUTE_TIMING` - is therefore written *directly* to the file as well, not only logged. Lines are prefixed `LOGCAT|`. The filterspec is
  `net.osmand:V` (every OsmAnd log statement carries that one tag) plus `NavigationSession`
  and `SurfaceRenderer` at verbose for Android Auto, `AndroidRuntime:E`, `System.err:W` and
  a `*:W` floor for everything else — **not** `*:V`, which buried the app's own trace under
  framework chatter and rotated it out of the retention window.
  `PlatformUtil.setVerboseLoggingForced(true)` additionally lifts OsmAnd's own
  trace/debug calls above the `android.util.Log` INFO floor, which otherwise cannot be
  raised without `adb setprop`.
* **location** — every fix from `OsmAndLocationProvider` (`FIX`), plus a 5 s sample
  (`SAMPLE`) that records position, speed, bearing, accuracy, altitude, satellite counts
  and fix state **even when nothing moved**; the `state=` field reads `MOVED` or `STILL`
  and `movedM=` carries the delta, so a stationary stretch is as visible as a drive. The
  sample interval only bounds how quickly a *stall* is visible — while fixes are arriving
  the resolution is the provider's, normally 1 Hz. Latitude and longitude are written to
  five decimals, about a metre, which is finer than any consumer GNSS fix and stops a
  four-day movement history being stored at sub-centimetre precision.
* **lifecycle** — activity transitions, screen on/off, power and airplane-mode changes,
  compass headings, `PROVIDERS` lines when location services are toggled, and a 5 s
  `SYSTEM` snapshot of heap and battery. System-wide free memory and free storage are on
  that line too but refreshed only every 30 s: those two cost a binder call and a
  `statvfs` respectively and do not move meaningfully inside five seconds. The crash
  handler forces a full snapshot, because "the disk was full" is a live hypothesis there.
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

Writes are queued and drained on a background thread, so logging never blocks the UI. If a
burst overflows the queue, or a write fails and the 10 s storage back-off drops lines, the
hole is stated in the file itself as soon as writing resumes:

```
=== CairoDrive log gap: 41 line(s) dropped from 2026-07-26 11:03:12.880Z to 2026-07-26 11:03:19.104Z (queue saturation or storage back-off), 41 total ===
```

A running total also appears on the `SYSTEM` line and in the next file header. The marker
is what matters when reading a trace: a counter says lines were lost, not *where*, and on a
fixed-interval log "the logger paused here" and "nothing happened here" look identical
without it. The 10 s back-off on a failed open or write is what stops a full disk spawning
one file per line.

Release builds have the logger compiled in but disabled. Build with
`CAIRODRIVE_FULL_LOGGING=true` to enable it there too — note that a build which
continuously records GPS positions to storage needs a matching privacy disclosure before
it goes on Google Play.
