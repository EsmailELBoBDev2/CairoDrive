#!/usr/bin/env python3
"""
Adds a "deprioritise narrow streets" option to the car routing profile.

WHY THIS IS A BUILD-TIME PATCH AND NOT A FILE IN THE REPO
    routing.xml is not part of this repository. OsmAnd-java/build.gradle pulls it from the
    osmandapp/OsmAnd-resources checkout with a Gradle `Sync` task, and `Sync` deletes
    anything already in the destination - so a vendored copy would be silently wiped on
    every build. Patching the checkout before Gradle runs is the only place the change
    survives. This script is deliberately structural rather than a line-based .patch: it
    locates the car profile by name and inserts by XML shape, so an upstream edit a few
    lines away does not break it, and it fails loudly rather than silently doing nothing.

WHY PRIORITY PENALTIES AND NOT A BLOCK
    OsmAnd already has a car "width" parameter, but it is a HARD BLOCK: it emits
    <select value="-1"> which marks the road impassable. That is the wrong tool here. A
    blocked road is removed from the routing graph, and blocking a lot of them can strip
    out the Highway Hierarchies boundary points the fast C++ router depends on, dropping
    routing back to the slow A* path. It also fails outright when a destination genuinely
    is down a narrow street.

    Priority multiplies effective speed, so priority 0.15 makes a road cost ~6.7x its
    length. It loses every shortcut race it should lose, while remaining usable when it is
    the only way home. That is "deprioritise, do not block".

WHY THESE RULES AND NOT MORE AGGRESSIVE ONES
    Every rule below fires only on a tag that is POSITIVELY PRESENT. None of them infer
    narrowness from a missing tag, and that restraint is the whole design. In an
    under-mapped city, absence of `width` or `lanes` is the normal state of a perfectly
    good through-road - a rule like "residential and no lanes tag means narrow" would
    penalise most of Cairo and make routing worse, not better.

    Tiers are ordered by precision, and the routing engine takes the FIRST match, so the
    surveyed-width rules must come before the inferred ones.

    Also worth recording: `narrow=yes` - the tag that sounds perfect for this - is NOT
    usable. The router only sees tags the map builder encoded into the .obf via
    rendering_types.xml, and narrow is not among them. Upstream's own routing.xml carries
    maxwidth:physical rules that can never fire for the same reason.

USAGE
    python3 patches/cairodrive_narrow_streets.py <path-to-routing.xml>
"""

import re
import sys

PARAM_ID = "avoid_narrow_streets"

PARAM_DECL = (
    '\t\t<parameter id="%s" name="Deprioritise narrow streets"'
    ' description="Strongly prefer wider roads. Narrow streets are still used when they'
    ' are the only way to the destination." type="boolean"/>\n' % PARAM_ID
)

# Inserted at the TOP of the car profile's priority block: the engine takes the first
# matching select, so these must be evaluated before upstream's own highway-class rules.
#
# Threshold reasoning: a car is ~1.8 m wide, ~2.0 m over the mirrors. Below 2.2 m is not
# passable in practice; 2.2-2.8 m is passable but with no margin; 2.8-3.5 m is single-file.
# Two cars cannot pass each other below ~4.5 m.
#
# Operator semantics, from routing.xml's own documentation: ':' prefixes a PARAMETER you
# defined, '$' prefixes a TAG loaded from the obf. So "$width" is the road's surveyed
# width, and <lt value1="$width" value2="2.2"> reads "the road's width tag is under 2.2 m".
RULES = """
			<!-- CairoDrive: deprioritise narrow streets. Penalties only, never value="-1",
			     so a narrow street stays usable when it is the only way to the destination.
			     Ordered by precision - first match wins, so surveyed width outranks inference. -->
			<if param="%s">
				<!-- Tier 1: surveyed width. Exact, no false positives, but rare in Cairo. -->
				<select value="0.05" t="width">
					<lt value1="$width" value2="2.2" type="length"/>
				</select>
				<select value="0.15" t="width">
					<lt value1="$width" value2="2.8" type="length"/>
				</select>
				<select value="0.40" t="width">
					<lt value1="$width" value2="3.5" type="length"/>
				</select>
				<!-- maxwidth is a legal limit rather than a measurement, but a road signed
				     under 2.8 m is narrow by anyone's definition. -->
				<select value="0.05" t="maxwidth">
					<lt value1="$maxwidth" value2="2.2" type="length"/>
				</select>
				<select value="0.15" t="maxwidth">
					<lt value1="$maxwidth" value2="2.8" type="length"/>
				</select>

				<!-- Tier 2: definitional. An alley IS a narrow back passage; that is what the
				     tag means. Highest-precision signal available without a width survey. -->
				<select value="0.15" t="service" v="alley"/>
				<select value="0.20" t="highway" v="track"/>

				<!-- Tier 3: surface. Strong correlate in Cairo's informal areas, where the
				     unpaved lanes and the narrow lanes are largely the same streets. Fires
				     only where the surface was positively surveyed. -->
				<select value="0.25" t="surface" v="ground"/>
				<select value="0.25" t="surface" v="dirt"/>
				<select value="0.25" t="surface" v="earth"/>
				<select value="0.25" t="surface" v="mud"/>
				<select value="0.25" t="surface" v="sand"/>
				<select value="0.40" t="surface" v="unpaved"/>

				<!-- Tier 4: one lane shared in both directions - two cars cannot pass. Weaker
				     than the above because lanes=1 is sometimes tagged on wide one-way roads,
				     hence the explicit oneway exclusions. -->
				<select value="0.35" t="lanes" v="1">
					<ifnot t="oneway" v="yes"/>
					<ifnot t="oneway" v="-1"/>
				</select>
			</if>
""" % PARAM_ID


def fail(msg):
    sys.stderr.write("cairodrive_narrow_streets: %s\n" % msg)
    sys.exit(1)


def main():
    if len(sys.argv) != 2:
        fail("usage: cairodrive_narrow_streets.py <routing.xml>")
    path = sys.argv[1]

    try:
        with open(path, encoding="utf-8") as handle:
            xml = handle.read()
    except OSError as exc:
        fail("cannot read %s: %s" % (path, exc))

    if PARAM_ID in xml:
        print("cairodrive_narrow_streets: already applied, nothing to do")
        return

    # Locate the car profile by name, then bound it at the next routingProfile so nothing
    # can leak into bicycle/pedestrian.
    car = re.search(r'<routingProfile\s+name="car"\s+baseProfile="car"', xml)
    if not car:
        fail("could not find the car routingProfile - upstream routing.xml has changed shape")
    nxt = re.search(r"<routingProfile\s", xml[car.end():])
    car_end = car.end() + (nxt.start() if nxt else len(xml) - car.end())

    # 1. Declare the parameter, next to the other avoid_* switches so it lands in the same
    #    settings group in the UI.
    anchor = re.search(r'[ \t]*<parameter id="avoid_unpaved"[^\n]*\n', xml[car.start():car_end])
    if not anchor:
        fail("could not find the avoid_unpaved parameter to anchor the new one against")
    at = car.start() + anchor.end()
    xml = xml[:at] + PARAM_DECL + xml[at:]
    car_end += len(PARAM_DECL)

    # 2. Insert the rules at the very top of the car profile's priority block. First match
    #    wins in this engine, so position is load-bearing, not cosmetic.
    prio = re.search(r'<way attribute="priority">\n', xml[car.start():car_end])
    if not prio:
        fail("could not find the car priority block")
    at = car.start() + prio.end()
    xml = xml[:at] + RULES + xml[at:]

    try:
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(xml)
    except OSError as exc:
        fail("cannot write %s: %s" % (path, exc))

    print("cairodrive_narrow_streets: added '%s' to the car profile" % PARAM_ID)


if __name__ == "__main__":
    main()
