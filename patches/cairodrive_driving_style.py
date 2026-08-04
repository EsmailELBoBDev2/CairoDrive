#!/usr/bin/env python3
"""
Derives the CairoDrive driving map style from upstream's default.render.xml.

A driving style is not a prettier style. It is a style tuned for a driver GLANCING at a head
unit at speed, where the map has roughly a third of a second to answer one question - "which
way" - and everything that is not that answer is cost. Google reduced its ~700 map colours to
~25 for the navigation view; Waze draws nothing at all where data is thin rather than drawing
clutter. This script applies the same two moves to OsmAnd's default style: stop drawing what a
driver cannot act on, and stop colour-coding what a driver cannot decode at a glance.

WHY THIS IS A BUILD-TIME PATCH AND NOT A FILE IN THE REPO
    Same reason as cairodrive_narrow_streets.py and cairodrive_trim_assets.py: render styles
    are not in this repository. OsmAnd-java/build.gradle pulls rendering_styles/*.xml out of the
    osmandapp/OsmAnd-resources checkout with a Gradle `Sync`, and `Sync` DELETES the destination
    first - so a vendored copy would be wiped on every build. Patching the checkout before
    Gradle runs is the only place the change survives.

    It is also why this is not a new CairoDriveDriving.render.xml with depends="default". A new
    style file has to be registered, selected and kept in step with upstream's schema, and any
    drift between the two files shows up as a map that renders differently from the one that was
    measured. Injecting a small number of gated overrides into the style that is already there
    keeps a single source of truth and makes the whole change reviewable as a diff.

OFF BY DEFAULT, AND WHY THAT IS NOT NEGOTIABLE ON THE NEXT DRIVE
    Every rule this script injects is gated on a new boolean rendering property,
    `cairodriveDriving`, which is FALSE unless the user turns it on. A build that carries this
    patch renders byte-for-byte like stock OsmAnd until the switch is flipped.

    That is deliberate. This build already carries B1 (the VirtualDisplay + Presentation render
    path), which is being measured on the next drive. A render-style change moves map
    rasterisation cost in the same direction B1 does, so a drive with both changed cannot
    attribute either - which is exactly the failure recorded in CLAUDE.md, where a batch of
    features went in together, the app was "buggy as hell", and nothing could be attributed so
    the whole lot came out.

    B1 FIRST, ALONE. Only once B1 has a clean CD_FRAME baseline should this be switched on, and
    then it should be switched on ALONE. Because the gate is a runtime property rather than a
    compile-time constant, that A/B can even be done inside one drive by toggling it in
    Configure map, which is the cheapest possible way to measure it - a drive costs a real trip.

WHAT IS CHANGED, AND WHY EACH

    1. CAR POI SUPPRESSION NO LONGER STOPS AT ZOOM 17  (58 sites today)
       Upstream already decides that a long list of POI classes - benches, drinking water, power
       poles, barriers, shops, offices, sport, craft, tourism, trees, house-numbered building
       labels - are noise for a driver, and hides them with rules of the shape

           <apply_if baseAppMode="car" moreDetailed="false" maxzoom="17" order="-1"/>
           <apply_if baseAppMode="car" moreDetailed="false" maxzoom="17" icon=""/>
           <apply_if baseAppMode="car" moreDetailed="false" maxzoom="17" disable="true"/>

       across the <order>, <point> and <text> sections. The judgement is upstream's and it is
       right. The `maxzoom` cap on it is not a judgement - nothing about zoom 18 makes a bench
       relevant to a driver. It is an artifact, and it fires at the worst possible moment:
       OsmAnd's car auto-zoom pushes IN as you slow down, so z18-z19 is precisely the junction
       approach, in traffic, where the map most needs to be readable and where upstream hands
       the driver back every POI it just spent ten zoom levels hiding.

       This script keeps every one of those rules untouched and adds a SIBLING with the same
       filters, the same effect, the gate, and no maxzoom. Purely additive: below z17 the
       original already fired and the new rule is redundant; above it, the suppression now
       continues. `moreDetailed="false"` is carried over so a driver who deliberately turns
       "More details" on still gets detail - that is upstream's escape hatch and it is kept.

       The same treatment is applied to the maxzoom="16" and maxzoom="18" variants of the idiom
       (public transport platforms, bicycle parking), because they are the same defect with a
       different number in it. Any other attribute on the line - `layer="-1"` on the underground
       platform rules, for instance - is a FILTER, not an effect, and is copied verbatim.

    2. HOUSE NUMBERS OFF IN CAR MODE  (1 site)
       Upstream gates the building *name* label on baseAppMode="car" but not the bare
       addr:housenumber label, which draws from z16 at textSize 13, rising to 15 at z18. In
       Cairo's mapped blocks that is dozens of two- and three-digit numbers per frame. A driver
       cannot read them at speed, and does not need to: navigation announces the destination and
       the route line points at it. This is the single densest block of text on the screen at
       navigation zoom and it is the cheapest thing on this list to remove.

       Note this is NOT the same as upstream's `hideHouseNumbers` property, which the user may
       legitimately want on for walking; this rule is scoped to baseAppMode="car".

    3. POI LABEL INK COLLAPSED TO ONE PAIR AT NAVIGATION ZOOMS  (24 sites today)
       The <text> section carries 213 textColor assignments in 119 distinct colours. A large
       part of that is a category code: shops purple (#680067), food brown (#844f10), sport
       green (#004d33), craft slate (#273249), railway POIs blue (#6666ff), healthcare magenta
       (#da0092), historic and tourism ochre. It is a good scheme for BROWSING a map. It is
       unreadable as information at 60 km/h, on a tilted camera, on a head unit - the driver
       gets the hue long before the word, and the hue means nothing to them.

       So at navigation zooms (minzoom 15, i.e. motorway speed and below - anything above that
       is the driver browsing, and is left alone) in car mode, every POI label is drawn in one
       neutral ink: #3b3b3b by day, #d8d8d8 at night.

       Why a dark grey and not black: upstream sets place names at #000000/#222222 by day and
       #E5E5E5 at night. Keeping POI labels one step lighter preserves the visual HIERARCHY -
       place and street names still outrank POI names - while removing the hue. Collapsing to
       black would flatten that hierarchy and make the screen busier, not calmer.

       The 24 sites are exactly the groups upstream itself marks `hidePOILabels="false"`, i.e.
       the ones that vanish when the user ticks "Hide POI labels". That is upstream's own
       definition of "this is a POI label", so scoping to it is a rule that can be stated in one
       sentence and checked. Nothing else is touched: road shields, street names, place names,
       water names and the route line keep their colours, because those are the labels a driver
       actually uses. Halo colours are left alone too - they are near-white by day and dark by
       night in every one of these groups, and both work with a neutral ink.

WHAT THIS DELIBERATELY DOES NOT DO
    - It does not flatten the polygon palette. That was the first idea and MEASURING IT KILLED
      IT: with `moreDetailed` off, which is the default, landuseResidentialColor already
      resolves to $null and landuseCommercial/Retail/Railway already resolve to $defaultColor.
      The built-up landuse tints a driver sees at navigation zoom are largely already collapsed,
      and a patch to "fix" them would have been an inert diff that looked like work.
    - It does not recolour icons. Map icons are bitmaps from style-icons/; a render style cannot
      tint them. Reducing icon colour means shipping a different icon set, which is its own
      change and its own drive.
    - It does not hide POI classes upstream has NOT already classified as driver-noise. There is
      a long tail of shop and amenity icons with no baseAppMode gate at all, and hiding them is
      a defensible next step - but it is a JUDGEMENT about what a driver wants, not a fix to an
      obvious artifact, so it belongs in its own build measured on its own drive.
    - It does not touch cairodrive.gradle, the CI workflow, or any Java. See the report.

USAGE
    python3 patches/cairodrive_driving_style.py <path-to-default.render.xml>

    Run it only when the driving style is wanted in the build; it is a no-op to run twice.
"""

import re
import sys
import xml.etree.ElementTree as ElementTree

# The gate. Every rule injected below carries this, and OsmAnd defaults an undeclared boolean
# rendering property to false - so declaring it is what makes the whole patch inert until the
# user switches it on, with no app-side code needed.
PROPERTY = "cairodriveDriving"

PROPERTY_DECL = (
    '\n\t\t<!-- CairoDrive: the driving style gate. OFF by default, and it must stay off for\n'
    '\t\t     any drive that is measuring B1 (the VirtualDisplay render path) - a style change\n'
    '\t\t     and a render-path change move the same numbers and cannot be told apart in one\n'
    '\t\t     log. Category "hide" puts it next to the other suppression switches in\n'
    '\t\t     Configure map. -->\n'
    '\t\t<renderingProperty attr="%s" name="CairoDrive driving style"\n'
    '\t\t\tdescription="Tune the map for glancing at speed: keep car POI suppression above zoom'
    ' 17, drop house numbers, and draw POI labels in one neutral ink."\n'
    '\t\t\ttype="boolean" possibleValues="" category="hide"/>' % PROPERTY
)

# Anchor for the declaration: upstream's own "Hide POI labels" property. Two lines, hence the
# DOTALL search. Chosen because it is the closest thing upstream has to the same idea, so the
# new switch lands beside it in the same settings group.
PROPERTY_ANCHOR = re.compile(
    r'<renderingProperty attr="hidePOILabels".*?/>', re.DOTALL)

# --- Change 1 -----------------------------------------------------------------------------
# A self-closing <apply_if> on its own line that suppresses something for the car profile up to
# some zoom. Matched by SHAPE rather than by literal text so that a reordered or newly added
# attribute upstream is still caught: the line must carry the car filter, a zoom cap, and one of
# the three suppression effects the three sections use.
#
# Counts measured against OsmAnd-resources default.render.xml on 2026-08-04:
#   maxzoom=17 disable="true"  19      (text)
#   maxzoom=17 icon=""         18      (point)
#   maxzoom=17 order="-1"      14      (order)
#   maxzoom=18 order="-1"       6      (public transport platforms, bicycle parking)
#   maxzoom=16 order="-1"       1      (public transport platform, point)
#                              --
#                              58
CAR_SUPPRESSION = re.compile(
    r'(?m)^([ \t]*)(<apply_if\b(?=[^<>]*\bbaseAppMode="car")[^<>]*/>)[ \t]*$')
ZOOM_CAP = re.compile(r'\s*maxzoom="(1[678])"')
EFFECTS = ('order="-1"', 'icon=""', 'disable="true"')

# A restructure that dropped most of these would make the patch a near no-op that still reported
# success, so refuse below a floor. The floor is well under today's 58 so that upstream adding or
# retiring a handful of POI classes does not fail a build, but far enough above zero to catch the
# idiom being replaced wholesale. The exact count is printed on every run - watch it after a sync.
MIN_CAR_SUPPRESSION = 45

# --- Change 2 -----------------------------------------------------------------------------
# The bare house-number label. Deliberately anchored on the variant with NO nameTag2, so the
# "housenumber + housename" case on the line above cannot match it by accident. Must be unique.
HOUSE_NUMBER_CASE = re.compile(
    r'(?m)^([ \t]*)<case hideHouseNumbers="false" tag="building" value=""'
    r' nameTag="addr:housenumber"\s*/>[ \t]*$')

HOUSE_NUMBER_RULE = (
    '\n%s<!-- CairoDrive: house numbers off for the car profile. Upstream gates the building\n'
    '%s     NAME label on baseAppMode="car" but not this one, and in Cairo it is the densest\n'
    '%s     block of text on the screen at navigation zoom. A driver cannot read a house\n'
    '%s     number at speed and does not need to - navigation announces the destination. -->\n'
    '%s<apply_if %s="true" baseAppMode="car" disable="true"/>'
)

# --- Change 3 -----------------------------------------------------------------------------
# Upstream's own marker for "this group is POI labels": these are the groups that disappear when
# the user ticks "Hide POI labels". Scoping the ink collapse to exactly this set is what keeps it
# away from road shields, street names, place names and water labels.
POI_LABEL_GROUP = re.compile(
    r'(?m)^([ \t]*)<switch\b(?=[^<>]*\bhidePOILabels="false")[^<>]*[^/]>[ \t]*$')

MIN_POI_LABEL_GROUPS = 15  # 24 today; same floor reasoning as above.

# Day #3b3b3b / night #d8d8d8: one step lighter than the #000000/#222222 and #E5E5E5 upstream
# uses for place names, so the hierarchy "place and street names outrank POI names" survives the
# loss of hue instead of being flattened. Halo colours are left as upstream set them.
POI_INK_RULE = (
    '%s<!-- CairoDrive: one ink for every POI label at navigation zooms. Upstream colour-codes\n'
    '%s     POI labels by category across 119 distinct colours; at speed the driver reads the\n'
    '%s     hue long before the word, and the hue tells them nothing. minzoom 15 keeps browsing\n'
    '%s     zooms stock. Appended last so it wins over the group\'s own nightMode apply. -->\n'
    '%s<apply_if %s="true" baseAppMode="car" minzoom="15" textColor="#3b3b3b">\n'
    '%s\t<apply_if nightMode="true" textColor="#d8d8d8"/>\n'
    '%s</apply_if>'
)

# Sections that must exist before anything is touched. Their absence means this is not the file
# this script was written against.
REQUIRED_SECTIONS = ("<order>", "</order>", "<text>", "</text>", "<point>", "</point>")

# Tag scanner for finding the element that closes an opening tag. Comments are matched FIRST so
# that a commented-out <case> inside a group cannot unbalance the count - there are several.
TAG_OR_COMMENT = re.compile(r'<!--.*?-->|<\?.*?\?>|<[^>]*>', re.DOTALL)


def fail(msg):
    sys.stderr.write("cairodrive_driving_style: %s\n" % msg)
    sys.exit(1)


def find_close(xml, open_start, open_end, name):
    """Offset of the '<' of the tag closing the element opened at [open_start, open_end).

    Counts nesting depth over real tags, skipping comments and processing instructions, so this
    does not depend on indentation being consistent. Returns None if the element never closes or
    closes with the wrong name - both of which mean the file is not shaped as expected and the
    caller must abort rather than insert into the middle of something.
    """
    depth = 1
    for match in TAG_OR_COMMENT.finditer(xml, open_end):
        tag = match.group(0)
        if tag.startswith("<!--") or tag.startswith("<?"):
            continue
        if tag.startswith("</"):
            depth -= 1
            if depth == 0:
                if not re.match(r'</\s*%s\s*>' % re.escape(name), tag):
                    return None
                return match.start()
        elif not tag.endswith("/>"):
            depth += 1
    return None


def main():
    if len(sys.argv) != 2:
        fail("usage: cairodrive_driving_style.py <default.render.xml>")
    path = sys.argv[1]

    try:
        with open(path, encoding="utf-8") as handle:
            xml = handle.read()
    except OSError as exc:
        fail("cannot read %s: %s" % (path, exc))

    # Parse the input BEFORE touching it. A render style that does not parse is a style OsmAnd
    # will refuse at runtime, and every offset computed below would be meaningless. This is also
    # what makes "never leave the checkout in a broken state" checkable rather than aspirational:
    # if the file was already broken, this script is not the thing that broke it, and it says so.
    try:
        root = ElementTree.fromstring(xml)
    except ElementTree.ParseError as exc:
        fail("%s is not well-formed XML (%s). Refusing to touch it - fix or re-checkout the "
             "resources tree first." % (path, exc))

    if root.tag != "renderingStyle":
        fail("%s has root <%s>, not <renderingStyle> - this is not a render style."
             % (path, root.tag))
    if root.get("name") != "default":
        fail('%s is the "%s" style, not "default". This patch is written against upstream\'s '
             "default.render.xml and its rule shapes; applying it elsewhere would inject rules "
             "that silently never fire." % (path, root.get("name")))

    if PROPERTY in xml:
        print("cairodrive_driving_style: already applied, nothing to do")
        return

    for section in REQUIRED_SECTIONS:
        if section not in xml:
            fail("%s has no %s section - upstream has restructured the style, re-check this "
                 "script against it rather than shipping a half-applied patch." % (path, section))

    # Everything below only PLANS edits, as (start, end, text) splices - end == start for a pure
    # insertion. Nothing is written until all three changes have been located and the result has
    # been re-parsed, so a failure at any point leaves the checkout exactly as it was found.
    edits = []

    # 1. Declare the gate.
    anchor = PROPERTY_ANCHOR.search(xml)
    if not anchor:
        fail("could not find the hidePOILabels renderingProperty to anchor the new one against "
             "- upstream has renamed or removed it.")
    edits.append((anchor.end(), anchor.end(), PROPERTY_DECL))

    # 2. Uncap the car POI suppression.
    uncapped = 0
    for match in CAR_SUPPRESSION.finditer(xml):
        indent, tag = match.group(1), match.group(2)
        if not ZOOM_CAP.search(tag):
            continue
        if not any(effect in tag for effect in EFFECTS):
            continue
        # Same filters, same effect, no zoom cap, gated. Every attribute other than maxzoom is
        # either a filter that must be preserved (layer, moreDetailed, tag/value) or the effect
        # itself, so removing exactly maxzoom and adding the gate is the whole transformation.
        rule = ZOOM_CAP.sub("", tag)
        rule = rule.replace("<apply_if ", '<apply_if %s="true" ' % PROPERTY, 1)
        edits.append((match.end(), match.end(), "\n" + indent + rule))
        uncapped += 1

    if uncapped < MIN_CAR_SUPPRESSION:
        fail("found only %d car POI suppression rules to uncap, expected at least %d (58 when "
             "this script was written). Upstream has restructured the baseAppMode car rules; "
             "re-check this script against default.render.xml rather than shipping a patch that "
             "silently does almost nothing." % (uncapped, MIN_CAR_SUPPRESSION))

    # 3. House numbers.
    house = HOUSE_NUMBER_CASE.findall(xml)
    if len(house) != 1:
        fail("expected exactly 1 bare addr:housenumber building label case, found %d. Upstream "
             "has changed the house number rules - re-check this script against them." % len(house))
    match = HOUSE_NUMBER_CASE.search(xml)
    indent = match.group(1)
    edits.append((match.end(), match.end(), HOUSE_NUMBER_RULE % (
        indent, indent, indent, indent, indent, PROPERTY)))

    # 4. One ink for POI labels. The rule is appended as the LAST child of each group so it runs
    #    after the group's own nightMode apply and wins; that means finding the real closing tag.
    groups = 0
    for match in POI_LABEL_GROUP.finditer(xml):
        indent = match.group(1)
        close = find_close(xml, match.start(), match.end(), "switch")
        if close is None:
            fail("a hidePOILabels group opened at offset %d never closes with </switch>. The "
                 "style is not nested the way this script expects; refusing to insert into it."
                 % match.start())
        # The rule replaces the run of whitespace that already indents </switch>, and re-emits
        # that indent itself. Inserting instead of replacing would either strand the old indent
        # on a line of its own or double it - both pure noise in the resources checkout's
        # `git diff` on the next upstream sync.
        line_start = close
        while line_start > 0 and xml[line_start - 1] in " \t":
            line_start -= 1
        inner = indent + "\t"
        edits.append((line_start, close, POI_INK_RULE % (
            inner, inner, inner, inner, inner, PROPERTY, inner, inner) + "\n" + indent))
        groups += 1

    if groups < MIN_POI_LABEL_GROUPS:
        fail("found only %d hidePOILabels groups, expected at least %d (24 when this script was "
             "written). Upstream has restructured the text section; re-check this script against "
             "it." % (groups, MIN_POI_LABEL_GROUPS))

    # Apply from the end backwards so earlier offsets stay valid.
    patched = xml
    for start, end, text in sorted(edits, key=lambda item: item[0], reverse=True):
        patched = patched[:start] + text + patched[end:]

    # Re-parse before writing. This is the guarantee that a bug in the offset arithmetic above
    # cannot reach the build: a style that does not parse is caught here, with the original file
    # still untouched on disk, instead of at app start on the head unit.
    try:
        ElementTree.fromstring(patched)
    except ElementTree.ParseError as exc:
        fail("the patched style is not well-formed XML (%s). NOTHING WAS WRITTEN - %s is "
             "unchanged. This is a bug in this script, not in the checkout." % (exc, path))

    try:
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(patched)
    except OSError as exc:
        fail("cannot write %s: %s" % (path, exc))

    print("cairodrive_driving_style: declared '%s' (OFF by default)" % PROPERTY)
    print("cairodrive_driving_style: uncapped %d car POI suppression rules, disabled house "
          "numbers, collapsed POI label ink across %d groups" % (uncapped, groups))
    print("cairodrive_driving_style: the style is INERT until the switch is turned on in "
          "Configure map - do not turn it on during a drive that is measuring B1")


if __name__ == "__main__":
    main()
