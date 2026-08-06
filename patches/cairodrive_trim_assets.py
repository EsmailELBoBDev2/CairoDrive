#!/usr/bin/env python3
"""
Removes assets this fork cannot use, and the manifest entries that reference them.

WHY BOTH, ALWAYS, IN ONE STEP
    CheckAssetsTask.unpackBundledAssets walks every entry in bundled_assets.json and calls
    ResourceManager.copyAssets, which throws IOException when the asset is not in the APK.
    There is NO per-entry catch: the method declares `throws IOException` and the single
    caller catches it once, outside the loop. So one missing asset skips every REMAINING
    asset - fonts, voice prompts, the world basemap, regions - and it also skips the
    `settings.PREVIOUS_INSTALLED_VERSION.set(fv)` line that sits after the call. The user
    sees one warning string and an app that quietly has no fonts.

    Deleting a file without removing its manifest entry is therefore not a size optimisation,
    it is a way to ship a broken app that looks fine in CI. This script does both or neither.

WHY A BUILD-TIME PATCH
    Same reason as cairodrive_narrow_streets.py: bundled_assets.json and the fonts live in
    the osmandapp/OsmAnd-resources checkout, not in this repository. Gradle copies them from
    there (build-common.gradle: collectFonts, copyProjDb, copyBundledAssets), so patching the
    checkout before Gradle runs is the only place the change survives an upstream sync.

    Nothing in OsmAnd's own build files is edited, so this cannot conflict with a sync.

WHAT IS REMOVED, AND WHY EACH IS SAFE

    proj.db (7.9 MB)
        The PROJ coordinate-reference database. Its only consumer in the entire tree is
        EpsgCatalogRepository.kt, the EPSG projected-CRS picker in coordinate-input settings.
        That file already handles absence explicitly - `if (!projDb.exists())` logs and
        returns null - so the EPSG list is simply empty. Nothing in routing, rendering or
        Android Auto touches it. Its mode is copyOnlyIfDoesNotExist, so it is extracted to
        the data directory as well as shipped, and removing it saves the 7.9 MB twice.

    Eight script fonts (8.8 MB)
        CJK, South Asian, South-East Asian and Tibetan coverage, for scripts that do not
        appear on an Egyptian map.

    Seven unregistered voice scripts (85 KB)
        Shipped by collectVoiceAssets, reachable by nothing. See DROP_UNLISTED_FILES.

    Voice prompt scripts for every language but en and ar (1.28 MB)
        OsmAnd/cairodrive.gradle:773 sets resConfigs "en","ar", so the in-app language selector
        cannot offer a third UI language. A voice script for a language whose strings were
        stripped at build time is not a feature anyone can reach. Derived from the manifest
        rather than listed - see KEEP_VOICE_LANGS.

    NOT removed, deliberately:
        65_NotoSansNastaliqUrdu-Regular.ttf is the ONLY font in the manifest that covers the
        ARABIC SCRIPT - base Noto Sans is Latin/Greek/Cyrillic only. Cutting it because the
        filename says "Urdu" would turn every street label in Cairo into empty boxes. Checked
        before writing this, and it is the single most important line in this file.

WHAT IS ADDED

    03/04_Estedad-Regular/Bold (337 KB)
        Present in the resources checkout but absent from upstream's manifest, so the
        extractor never unpacked them and the renderer - which reads only FONT_INDEX_DIR,
        populated from the manifest - could not see them. They are ADDED here rather than
        left alone: see the ADD_FILES block for why Naskh matters on an Egyptian map.

USAGE
    python3 patches/cairodrive_trim_assets.py <path-to-resources-checkout>
"""

import json
import os
import re
import sys

# Files to delete from the resources checkout, relative to its root.
DROP_FILES = [
    "proj/proj.db",
    "rendering_styles/fonts/DroidSansFallback.ttf",
    "rendering_styles/fonts/35_NotoSansSouthAsian-Regular.ttf",
    "rendering_styles/fonts/40_NotoSansSouthAsian-Bold.ttf",
    "rendering_styles/fonts/45_NotoSansSoutheastAsian-Regular.ttf",
    "rendering_styles/fonts/50_NotoSansSoutheastAsian-Bold.ttf",
    "rendering_styles/fonts/55_NotoSansTibetan-Regular.ttf",
    "rendering_styles/fonts/60_NotoSansTibetan-Bold.ttf",
]

# The matching `source` values in bundled_assets.json. Every one of these MUST be found, or
# the manifest has been restructured and the pairing this script exists to guarantee is no
# longer verifiable - in which case it fails rather than half-applying.
DROP_SOURCES = [
    "proj.db",
    "fonts/DroidSansFallback.ttf",
    "fonts/35_NotoSansSouthAsian-Regular.ttf",
    "fonts/40_NotoSansSouthAsian-Bold.ttf",
    "fonts/45_NotoSansSoutheastAsian-Regular.ttf",
    "fonts/50_NotoSansSoutheastAsian-Bold.ttf",
    "fonts/55_NotoSansTibetan-Regular.ttf",
    "fonts/60_NotoSansTibetan-Bold.ttf",
]

# Files that ship inside the APK but have NO entry in bundled_assets.json, so nothing can ever
# reach them. This is the same class of defect the Estedad fonts below were in - present on disk,
# absent from the manifest - resolved the other way, because unlike Estedad there is no argument
# for wanting them here.
#
# WHY THEY SHIP AT ALL
#     build-common.gradle's collectVoiceAssets copies `**/*.js` out of the checkout wholesale
#     (`from "../../resources/voice"`, `include "**/*.js"`), so every voice script in that repo
#     lands in assets/voice/ whether or not the manifest mentions it. The checkout carries 58;
#     the manifest names 51.
#
# WHY THE OTHER SEVEN ARE UNREACHABLE, not merely unused
#     bundled_assets.json is opened in exactly one place, ResourceManager.java:1074, and every
#     consumer downstream is keyed on the entries it produces:
#       - CheckAssetsTask.unpackBundledAssets iterates AssetsCollection.getEntries(), so an
#         unlisted file is never extracted to the data directory;
#       - DownloadOsmandIndexesHelper.listDefaultTtsVoiceIndexes builds the ENTIRE "Voice prompts
#         (TTS)" download list by walking those same entries, so an unlisted voice never appears
#         in the UI and can never be selected;
#       - AssetsCollection maps destination -> entry, so nothing else can look one up either.
#     Grepped for good measure: no reference to any of the beep providers anywhere in the tree.
#     They are dead weight in the strict sense - removing them cannot change behaviour, because
#     no code path can observe them.
#
# WHY THIS LIST IS SEPARATE FROM DROP_FILES
#     DROP_FILES exists to keep a file and its manifest entry removed together. These have no
#     manifest entry, so that pairing has nothing to say about them - but the INVERSE assertion
#     matters and is made below: if upstream ever registers one of these, deleting it here would
#     create exactly the half-applied state this script exists to prevent, so the run fails
#     instead. Never move a name between the two lists without moving it in the manifest too.
DROP_UNLISTED_FILES = [
    "voice/beep-complex/beep-complex_tts.js",
    "voice/beep-complex-loud/beep-complex-loud_tts.js",
    "voice/beep-minimal/beep-minimal_tts.js",
    "voice/beep-simple/beep-simple_tts.js",
    "voice/beep-simple-loud/beep-simple-loud_tts.js",
    "voice/bs/bs_tts.js",
    "voice/gl/gl_tts.js",
]

# Voice prompt languages that survive. Everything else in the manifest's voice/ section goes,
# file and entry together through the normal DROP path.
#
# WHY THIS IS A KEEP-LIST AND NOT 49 PATHS
#     The drops are DERIVED from bundled_assets.json by plan_voice_drops below, so the next bump
#     of the pinned OsmAnd-resources SHA (.github/workflows/build-dev.yml:176) handles a 50th
#     language without anyone editing this file. A hand-written drop list would silently start
#     shipping whatever upstream added.
#
# WHY DROPPING THEM IS REACHABLE-FEATURE-NEUTRAL
#     OsmAnd/cairodrive.gradle:773 sets resConfigs "en","ar", so res/values-*/ carries those two
#     languages and no other, and the in-app language selector can only ever offer what is
#     present. A German voice script on a build with no German strings is not a feature that was
#     taken away; it is one nobody could select. Same trade, matching asset.
#
# WHY en AND ar SPECIFICALLY - both are load-bearing, not just preferred
#     ar: OsmandSettings.java:3252 sets PREFERRED_VOICE_LANGUAGE = "ar" and VOICE_PROVIDER's
#         getProfileDefaultValue tries it FIRST, ahead of the phone's own language. Remove the
#         entry and getSupportedTtsByLanguages - which is built from these manifest entries,
#         DownloadOsmandIndexesHelper.java:132-140 - no longer has "ar", so a fresh install on an
#         English phone with no other match falls through to VOICE_PROVIDER_NOT_USE
#         (OsmandSettings.java:3269) and OsmandApplication.java:829 then sets VOICE_MUTE. Silent
#         navigation on first run.
#     en: the ONLY voice entry in the whole manifest with mode alwaysOverwriteOrCopy is
#         voice/en/en_tts.js -> voice/en-tts/en_tts.js, i.e. the one voice that is force-copied
#         on every start rather than only refreshed if already present, and
#         TtsVoiceExportType.java:49 hardcodes "en-tts" as the bundled default that backup skips.
#         It is the floor the rest of the voice system assumes exists.
#
# WHY A STALE VOICE_PROVIDER SETTING IS NOT A BLOCKER - checked before writing this
#     A user who previously selected, say, de-tts keeps working: nothing deletes from the data
#     directory, and ResourceManager.indexVoiceFiles:428-447 enumerates that directory rather
#     than the manifest, so the already-extracted voice/de-tts/de_tts.js stays selected and
#     stays listed. Only a value that arrives WITHOUT its files - a restored settings backup, a
#     new install from a synced profile - can name an absent voice, and that path is already
#     handled: CommandPlayer.createCommandPlayer:66-70 throws CommandPlayerException when the
#     provider directory is missing, AppInitializer.initVoiceDataInDifferentThread:519-521
#     catches it, toasts "voice data unavailable" and leaves app.player null. Prompts are silent
#     until the user picks a voice again. No crash, and it is the same behaviour upstream has
#     when a user deletes a voice package by hand.
#
# WHY THE .ogg RECORDED VOICES ARE NOT PART OF THIS
#     They never shipped. build-common.gradle:235-239 collectVoiceAssets copies the voice tree
#     with `include "**/*.js"`, so recorded audio could not reach the APK even if it were in the
#     checkout - and at the pinned SHA the checkout contains zero .ogg files. All 58 files under
#     voice/ are scripts.
KEEP_VOICE_LANGS = ("en", "ar")

# The one shape a voice entry is allowed to have: voice/<lang>/<lang>_tts.js, backreferenced so
# the directory and the filename must agree. Anything else and plan_voice_drops stops the build
# rather than guessing - see the fail() message there for why guessing is not on offer.
VOICE_SOURCE_RE = re.compile(r"^voice/([a-z0-9]+(?:-[a-z0-9]+)*)/\1_tts\.js$")

# Fonts that ship in the resources checkout but are NOT in upstream's manifest, so the
# extractor never unpacks them and the renderer - which only ever reads FONT_INDEX_DIR,
# populated from the manifest - cannot see them. Estedad is an Arabic/Latin screen sans; its
# 03/04 filenames put it ahead of Noto Sans in the fallback order the renderer walks.
#
# Without this, the ONLY Arabic-script font the renderer has is 65_NotoSansNastaliqUrdu, and
# Nastaliq is the Perso-Urdu calligraphic style: steep diagonal baselines, deep vertical
# stacking, long descenders. Egyptian road signage is Naskh. Every street label in Cairo was
# being set in the wrong script style, on a tilted map, at glance speed.
#
# Nastaliq deliberately stays in REQUIRED_SOURCES below: it is 100% of the Arabic coverage
# today, and if Estedad turns out to be missing a glyph that appears in an Egyptian name:ar
# value, the fallback chain needs somewhere to land other than an empty box.
ADD_FILES = [
    "rendering_styles/fonts/03_Estedad-Regular.ttf",
    "rendering_styles/fonts/04_Estedad-Bold.ttf",
]

ADD_ENTRIES = [
    {
        "source": "fonts/03_Estedad-Regular.ttf",
        "destination": "fonts/03_Estedad-Regular.ttf",
        "mode": "alwaysOverwriteOrCopy",
    },
    {
        "source": "fonts/04_Estedad-Bold.ttf",
        "destination": "fonts/04_Estedad-Bold.ttf",
        "mode": "alwaysOverwriteOrCopy",
    },
]

# Must survive. Asserted after the edit, not assumed - see the note above about Arabic.
REQUIRED_SOURCES = [
    "fonts/65_NotoSansNastaliqUrdu-Regular.ttf",
    "fonts/05_NotoSans-Regular.ttf",
    "fonts/10_NotoSans-Bold.ttf",
    "fonts/03_Estedad-Regular.ttf",
    "fonts/04_Estedad-Bold.ttf",
]


def fail(msg):
    sys.stderr.write("cairodrive_trim_assets: %s\n" % msg)
    sys.exit(1)


def sniff_format(raw):
    """Return (indent, newline_at_eof) matching how the manifest is already written.

    indent is what json.dump takes: an int for spaces, "\t" for tabs, or None for a single
    compact line. Falls back to 2 only when the file gives us nothing to go on.
    """
    newline_at_eof = raw.endswith("\n")
    for line in raw.splitlines()[1:]:
        if not line.strip():
            continue
        prefix = line[:len(line) - len(line.lstrip())]
        if prefix.startswith("\t"):
            return "\t", newline_at_eof
        if prefix:
            return len(prefix), newline_at_eof
        # First non-blank line after the opening brace carries no indent at all: the whole
        # document is on one line, or upstream writes it unindented. Either way, compact.
        return None, newline_at_eof
    return 2, newline_at_eof


def plan_voice_drops(entries):
    """Return the voice `source` values to drop, derived from the manifest.

    Fails rather than returning a partial answer. Two entries share one source per language -
    destination voice/<lang>-tts/<lang>_tts.js, the TTS provider directory that
    DownloadOsmandIndexesHelper.listDefaultTtsVoiceIndexes:190-209 keys the whole download list
    off (its isTTS test requires VOICE_PROVIDER_SUFFIX in the DESTINATION), and destination
    voice/<lang>/<lang>_tts.js, the recorded-voice directory that
    CheckAssetsTask.copyMissingJSAssets:131-137 populates only when that directory already
    exists. Both belong to the same language and both go, so this works in sources and lets the
    caller remove every entry naming one.
    """
    by_lang = {}
    malformed = []
    for entry in entries:
        source = entry.get("source") or ""
        destination = entry.get("destination") or ""
        # Matched on EITHER side. An entry whose destination lands under voice/ from some other
        # source would be invisible to a source-only scan, and it is the destination that
        # listDefaultTtsVoiceIndexes reads.
        if not source.startswith("voice/") and not destination.startswith("voice/"):
            continue
        match = VOICE_SOURCE_RE.match(source)
        if not match:
            malformed.append("%s -> %s" % (source, destination))
            continue
        lang = match.group(1)
        expected = ("voice/%s-tts/%s_tts.js" % (lang, lang), "voice/%s/%s_tts.js" % (lang, lang))
        if destination not in expected:
            malformed.append("%s -> %s" % (source, destination))
            continue
        by_lang.setdefault(lang, set()).add(source)

    if malformed:
        # Deliberately fatal rather than "skip the ones I don't recognise". The predicate is the
        # only thing standing between "drop 49 languages" and "drop something the app needs",
        # and an entry that no longer fits it means the manifest's voice section has been
        # restructured - at which point every conclusion in KEEP_VOICE_LANGS above, all of which
        # was read off the current shape, is unverified.
        fail("these voice entries do not match voice/<lang>/<lang>_tts.js -> "
             "voice/<lang>[-tts]/<lang>_tts.js: %s. Upstream has restructured the voice section "
             "- re-derive KEEP_VOICE_LANGS against it rather than dropping languages on a "
             "predicate that no longer describes the manifest." % ", ".join(sorted(malformed)))

    absent = [lang for lang in KEEP_VOICE_LANGS if lang not in by_lang]
    if absent:
        # Asserted here, before anything is removed, and NOT only as a post-condition: if the
        # language we are keeping is not in the manifest to begin with, dropping the other 49
        # produces a build with no voice at all.
        fail("no voice entry for %s in bundled_assets.json. ar is this fork's default voice "
             "(OsmandSettings.java:3252) and en-tts is the bundled default the rest of the voice "
             "system assumes exists (TtsVoiceExportType.java:49); refusing to drop the other "
             "languages and leave the app with no voice it can fall back to."
             % ", ".join(absent))

    return sorted(
        source
        for lang, sources in by_lang.items()
        if lang not in KEEP_VOICE_LANGS
        for source in sources
    )


def main():
    if len(sys.argv) != 2:
        fail("usage: cairodrive_trim_assets.py <resources-checkout>")
    root = sys.argv[1]
    if not os.path.isdir(root):
        fail("%s is not a directory" % root)

    manifest_path = os.path.join(root, "bundled_assets.json")
    try:
        with open(manifest_path, encoding="utf-8") as handle:
            raw = handle.read()
        manifest = json.loads(raw)
    except (OSError, ValueError) as exc:
        fail("cannot read %s: %s" % (manifest_path, exc))

    # Rewrite with the indentation upstream already used, rather than forcing indent=2. This
    # script edits a checkout of OsmAnd-resources, and a hardcoded indent reformats every line
    # of a file we are only meant to touch a handful of entries in - which turns `git diff` in
    # that checkout from "10 entries changed" into "the whole manifest changed", and hides a
    # genuine upstream change behind whitespace on the next sync.
    indent, newline_at_eof = sniff_format(raw)

    entries = manifest.get("assets")
    if not isinstance(entries, list):
        fail("bundled_assets.json has no 'assets' list - upstream has restructured it")

    present = {entry.get("source") for entry in entries}

    # Asserted before anything is decided, not after. If upstream has since registered one of
    # these, the premise the whole DROP_UNLISTED_FILES block rests on - "no manifest entry, so
    # nothing can reach it" - is false, and deleting the file would leave a manifest entry
    # pointing at an asset the APK does not carry.
    wrongly_listed = [
        relative for relative in DROP_UNLISTED_FILES if relative in present
    ]
    if wrongly_listed:
        fail("these are in bundled_assets.json but this script treats them as unregistered: %s. "
             "Upstream has registered them - move them to DROP_FILES/DROP_SOURCES so the file "
             "and its entry go together, rather than deleting a file the manifest promises."
             % ", ".join(wrongly_listed))

    # Derived AFTER the check above, and that order is load-bearing: if upstream ever registers
    # bs, gl or a beep provider, the DROP_UNLISTED assertion stops the run before this function
    # can also claim the same file and try to delete it twice.
    #
    # Called BEFORE the early return below, not inside the pending branch, so its assertions run
    # on every invocation. On the second run the drops are gone but en and ar are still there,
    # and "the keep languages are in the manifest" is exactly the property that must not be
    # allowed to rot silently between an upstream bump and the next build.
    voice_drop_sources = plan_voice_drops(entries)
    voice_drop_set = set(voice_drop_sources)

    drops_pending = any(source in present for source in DROP_SOURCES)
    voice_pending = bool(voice_drop_sources)
    adds_pending = any(entry["source"] not in present for entry in ADD_ENTRIES)
    unlisted_pending = any(
        os.path.isfile(os.path.join(root, relative)) for relative in DROP_UNLISTED_FILES
    )
    if not drops_pending and not voice_pending and not adds_pending and not unlisted_pending:
        print("cairodrive_trim_assets: already applied, nothing to do")
        return

    missing = [source for source in DROP_SOURCES if source not in present] if drops_pending else []
    if missing:
        # Partially applied, or upstream renamed something. Either way the file/manifest
        # pairing can no longer be guaranteed, and guessing is how the app ends up with no
        # fonts. Stop.
        fail("these sources are not in bundled_assets.json: %s. Upstream has changed the "
             "manifest - re-check this script against it rather than shipping a partial trim."
             % ", ".join(missing))

    kept = [
        entry for entry in entries
        if entry.get("source") not in DROP_SOURCES
        and entry.get("source") not in voice_drop_set
    ]
    # Counted per list, never as one total. A font source is registered once and a voice source
    # twice - the -tts provider directory and the recorded-voice directory, see plan_voice_drops
    # - so a single expected number would let an under-removal in one list hide behind an
    # over-removal in the other, which is precisely the half-applied state this file exists to
    # make impossible.
    font_removed = sum(1 for entry in entries if entry.get("source") in DROP_SOURCES)
    voice_removed = sum(1 for entry in entries if entry.get("source") in voice_drop_set)
    removed = font_removed + voice_removed
    if drops_pending and font_removed != len(DROP_SOURCES):
        fail("expected to remove %d entries, removed %d" % (len(DROP_SOURCES), font_removed))
    if voice_removed < len(voice_drop_sources):
        # Every source was read out of `entries`, so each must match at least the entry it came
        # from. Fewer means the filter and the derivation disagree about what a source is.
        fail("derived %d voice sources to drop but only %d manifest entries name them"
             % (len(voice_drop_sources), voice_removed))

    # Add before the required-sources assert, so the assert covers the additions too.
    added = 0
    for entry in ADD_ENTRIES:
        if entry["source"] not in {kept_entry.get("source") for kept_entry in kept}:
            kept.append(dict(entry))
            added += 1

    kept_sources = {entry.get("source") for entry in kept}
    for source in REQUIRED_SOURCES:
        if source not in kept_sources:
            fail("%s is no longer in the manifest. Cairo street labels depend on these fonts; "
                 "refusing to produce a build without it." % source)

    # The same assertion for voice, restated on the result rather than on the input. The input
    # check inside plan_voice_drops proves the keep languages were there; this one proves the
    # filter did not take them out anyway - a one-character slip in KEEP_VOICE_LANGS or in the
    # regex would otherwise ship an app whose default voice provider names a voice the APK does
    # not carry, which OsmandApplication.java:829 turns into VOICE_MUTE and a silent drive.
    surviving_voice_langs = {
        VOICE_SOURCE_RE.match(source).group(1)
        for source in kept_sources
        if source and VOICE_SOURCE_RE.match(source)
    }
    for lang in KEEP_VOICE_LANGS:
        if lang not in surviving_voice_langs:
            fail("the %s voice is no longer in the manifest after the trim. Refusing to produce "
                 "a build whose only voices are ones it does not ship." % lang)
    stowaways = sorted(surviving_voice_langs - set(KEEP_VOICE_LANGS))
    if stowaways:
        fail("these voice languages survived the trim but are not in KEEP_VOICE_LANGS: %s"
             % ", ".join(stowaways))

    # Validate EVERY file before touching anything. Writing the manifest first and discovering
    # a missing file afterwards would leave exactly the half-applied state this script exists
    # to prevent: a manifest that no longer lists an asset the APK still ships, or worse, the
    # reverse. Check first, then mutate.
    # Only when there is actually a drop to make. When the drops already applied on a previous
    # run and only the Estedad additions are pending, the files below are legitimately gone -
    # demanding them here would abort a run whose remaining work is entirely additive, and the
    # script would never become idempotent.
    freed = 0
    if drops_pending:
        for relative in DROP_FILES:
            path = os.path.join(root, relative)
            if not os.path.isfile(path):
                fail("%s is missing from the checkout, but its manifest entry was present. "
                     "Refusing to continue with a half-applied trim." % relative)
            freed += os.path.getsize(path)

    # Same rule as DROP_FILES above, and the same reason to be strict: the entry is going out of
    # the manifest in this run, so if the file is not there to go with it the APK would keep
    # shipping a voice script nothing can reach - the DROP_UNLISTED_FILES defect, recreated by
    # the code written to avoid it.
    #
    # A voice `source` doubles as its path in the checkout, unlike a font (source
    # "fonts/x.ttf", path "rendering_styles/fonts/x.ttf"), which is why there is no second list
    # here. The isfile check below is what enforces that rather than assuming it.
    voice_freed = 0
    if voice_pending:
        for relative in voice_drop_sources:
            path = os.path.join(root, relative)
            if not os.path.isfile(path):
                fail("%s is missing from the checkout, but bundled_assets.json still lists it. "
                     "Refusing to continue with a half-applied trim." % relative)
            voice_freed += os.path.getsize(path)

    # Same discipline in the other direction: never add a manifest entry for a file that is not
    # there. CheckAssetsTask now catches per entry rather than aborting the whole pass, but a
    # manifest that promises a font the APK does not carry is still a build that renders boxes.
    add_bytes = 0
    for relative in ADD_FILES:
        path = os.path.join(root, relative)
        if not os.path.isfile(path):
            fail("%s is not in the checkout, so it cannot be added to the manifest. Upstream may "
                 "have renamed or dropped the Estedad fonts - re-check this script against "
                 "rendering_styles/fonts/ rather than shipping a manifest that promises a missing "
                 "font." % relative)
        add_bytes += os.path.getsize(path)

    # Size them before deleting, and tolerate absence: a partially-applied unlisted trim is not
    # the hazard DROP_FILES guards against, because there is no manifest entry left behind to
    # dangle. Whatever is still there goes.
    unlisted_freed = 0
    unlisted_removed = []
    for relative in DROP_UNLISTED_FILES:
        path = os.path.join(root, relative)
        if os.path.isfile(path):
            unlisted_freed += os.path.getsize(path)
            unlisted_removed.append(relative)

    # Only rewrite the manifest when the manifest actually changed. An unlisted-only run touches
    # no entry, and re-serialising the file would show up as a whole-file diff in the resources
    # checkout for no change at all - the thing sniff_format() above exists to avoid.
    if drops_pending or voice_pending or added:
        manifest["assets"] = kept
        try:
            with open(manifest_path, "w", encoding="utf-8") as handle:
                json.dump(manifest, handle, ensure_ascii=False, indent=indent)
                if newline_at_eof:
                    handle.write("\n")
        except OSError as exc:
            fail("cannot write %s: %s" % (manifest_path, exc))

    if drops_pending:
        for relative in DROP_FILES:
            os.remove(os.path.join(root, relative))
        print("cairodrive_trim_assets: removed %d assets and %d manifest entries, %.1f MB"
              % (len(DROP_FILES), font_removed, freed / 1e6))
    if voice_pending:
        for relative in voice_drop_sources:
            os.remove(os.path.join(root, relative))
        print("cairodrive_trim_assets: removed %d voice prompt scripts and %d manifest entries, "
              "%d bytes - languages the build has no UI strings for (resConfigs en, ar): %s"
              % (len(voice_drop_sources), voice_removed, voice_freed,
                 ", ".join(sorted(
                     VOICE_SOURCE_RE.match(source).group(1) for source in voice_drop_sources))))
    for relative in unlisted_removed:
        os.remove(os.path.join(root, relative))
    if unlisted_removed:
        print("cairodrive_trim_assets: removed %d unregistered voice scripts, %.0f KB - shipped "
              "by collectVoiceAssets, named by no manifest entry, so unreachable: %s"
              % (len(unlisted_removed), unlisted_freed / 1e3, ", ".join(unlisted_removed)))
    if added:
        print("cairodrive_trim_assets: added %d manifest entries (%.0f KB) so the renderer can "
              "actually use them: %s"
              % (added, add_bytes / 1e3, ", ".join(e["source"] for e in ADD_ENTRIES)))
    print("cairodrive_trim_assets: kept %s, and the %s voices"
          % (", ".join(REQUIRED_SOURCES), "/".join(KEEP_VOICE_LANGS)))


if __name__ == "__main__":
    main()
