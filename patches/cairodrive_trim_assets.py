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

    NOT removed, deliberately:
        65_NotoSansNastaliqUrdu-Regular.ttf is the ONLY font in the manifest that covers the
        ARABIC SCRIPT - base Noto Sans is Latin/Greek/Cyrillic only. Cutting it because the
        filename says "Urdu" would turn every street label in Cairo into empty boxes. Checked
        before writing this, and it is the single most important line in this file.

        03/04_Estedad are Arabic/Persian and are NOT in the manifest, so they ship without
        ever being extracted - 337 KB of genuine dead weight. Left alone anyway: unverified
        whether anything reads them straight out of assets/, and 337 KB is not worth guessing
        about when the failure mode is unreadable labels.

USAGE
    python3 patches/cairodrive_trim_assets.py <path-to-resources-checkout>
"""

import json
import os
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


def main():
    if len(sys.argv) != 2:
        fail("usage: cairodrive_trim_assets.py <resources-checkout>")
    root = sys.argv[1]
    if not os.path.isdir(root):
        fail("%s is not a directory" % root)

    manifest_path = os.path.join(root, "bundled_assets.json")
    try:
        with open(manifest_path, encoding="utf-8") as handle:
            manifest = json.load(handle)
    except (OSError, ValueError) as exc:
        fail("cannot read %s: %s" % (manifest_path, exc))

    entries = manifest.get("assets")
    if not isinstance(entries, list):
        fail("bundled_assets.json has no 'assets' list - upstream has restructured it")

    present = {entry.get("source") for entry in entries}

    drops_pending = any(source in present for source in DROP_SOURCES)
    adds_pending = any(entry["source"] not in present for entry in ADD_ENTRIES)
    if not drops_pending and not adds_pending:
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

    kept = [entry for entry in entries if entry.get("source") not in DROP_SOURCES]
    removed = len(entries) - len(kept)
    if drops_pending and removed != len(DROP_SOURCES):
        fail("expected to remove %d entries, removed %d" % (len(DROP_SOURCES), removed))

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

    # Validate EVERY file before touching anything. Writing the manifest first and discovering
    # a missing file afterwards would leave exactly the half-applied state this script exists
    # to prevent: a manifest that no longer lists an asset the APK still ships, or worse, the
    # reverse. Check first, then mutate.
    freed = 0
    for relative in DROP_FILES:
        path = os.path.join(root, relative)
        if not os.path.isfile(path):
            fail("%s is missing from the checkout, but its manifest entry was present. "
                 "Refusing to continue with a half-applied trim." % relative)
        freed += os.path.getsize(path)

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

    manifest["assets"] = kept
    try:
        with open(manifest_path, "w", encoding="utf-8") as handle:
            json.dump(manifest, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
    except OSError as exc:
        fail("cannot write %s: %s" % (manifest_path, exc))

    for relative in DROP_FILES:
        os.remove(os.path.join(root, relative))

    print("cairodrive_trim_assets: removed %d assets and %d manifest entries, %.1f MB"
          % (len(DROP_FILES), removed, freed / 1e6))
    if added:
        print("cairodrive_trim_assets: added %d manifest entries (%.0f KB) so the renderer can "
              "actually use them: %s"
              % (added, add_bytes / 1e3, ", ".join(e["source"] for e in ADD_ENTRIES)))
    print("cairodrive_trim_assets: kept %s" % ", ".join(REQUIRED_SOURCES))


if __name__ == "__main__":
    main()
