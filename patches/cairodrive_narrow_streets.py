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

    THE BALANCE, stated explicitly, because it is easy to get backwards:
    upstream's own car defaults are residential 0.7 and unclassified 0.7 - and its comment
    calls unclassified "usually 90% of urban roads". Those two are NEVER touched here, and
    that is what stops this option from penalising most of Cairo. Everything penalised
    below is either measured (width), definitional (alley, driveway, parking_aisle, track)
    or positively surveyed as poor (surface, tracktype).

    Because these rules sit at the TOP of the priority block and the first match wins, every
    value must be BELOW the upstream default it shadows, or the rule silently makes that road
    class more attractive instead of less. Upstream rates track, service and living_street at
    0.5, so anything here keyed on those must be under 0.5.

    Also worth recording: `narrow=yes` - the tag that sounds perfect for this - is NOT
    usable. The router only sees tags the map builder encoded into the .obf via
    rendering_types.xml, and narrow is not among them. Upstream's own routing.xml carries
    maxwidth:physical rules that can never fire for the same reason.

VERIFIED AGAINST rendering_types.xml
    Every tag used below was checked to be genuinely visible to the router, because a rule
    on an invisible tag is silent dead weight that looks like it works:
        width, maxwidth  - <routing_type ... base="true" type="length"/>, so the numeric
                           comparisons below parse units correctly
        lanes, service, surface, smoothness, tracktype, highway - <routing_type ... amend/>
    Deliberately absent: est_width (not a routing_type at all) and surface=earth / soil,
    which rendering_types.xml rewrites to surface=ground before the map is built - a rule
    keyed on them would never fire even though the tag is common in OSM.

USAGE
    python3 patches/cairodrive_narrow_streets.py <path-to-routing.xml>
"""

import re
import sys

PARAM_ID = "avoid_narrow_streets"

# default="true" is read by RoutingConfiguration.parseRoutingParameter into
# RoutingParameter.defaultBoolean, which is what OsmandSettings.getCustomRoutingBooleanProperty
# is handed as the preference default. So this is what makes the option ON out of the box without
# any app-side code: the user can still turn it off in Route parameters.
PARAM_DECL = (
    '\t\t<parameter id="%s" name="Deprioritise narrow streets" default="true"'
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

				<!-- Tier 2: definitional. These tags do not correlate with "not a through
				     road" - they mean it. Highest-precision signal available without a survey.
				     Tracktype comes first so a graded track keeps its specific value instead of
				     being swallowed by the generic highway=track rule below it. -->
				<select value="0.25" t="tracktype" v="grade5"/>
				<select value="0.30" t="tracktype" v="grade4"/>
				<select value="0.45" t="tracktype" v="grade3"/>
				<select value="0.15" t="service" v="alley"/>
				<select value="0.20" t="highway" v="track"/>
				<!-- A driveway or a parking aisle is somebody's access, not a route. Upstream
				     already rates highway=service 0.5; these subtypes deserve worse because
				     they are never a legitimate through-road. -->
				<select value="0.20" t="service" v="driveway"/>
				<select value="0.20" t="service" v="parking_aisle"/>

				<!-- Tier 3: surface. Strong correlate in Cairo's informal areas, where the
				     unpaved lanes and the narrow lanes are largely the same streets. Fires
				     only where the surface was positively surveyed.
				     NOTE: surface=earth and surface=soil are NOT listed here on purpose -
				     rendering_types.xml rewrites both to surface=ground before the map is
				     built, so a rule keyed on them could never fire. ground covers them. -->
				<select value="0.25" t="surface" v="ground"/>
				<select value="0.25" t="surface" v="dirt"/>
				<select value="0.25" t="surface" v="mud"/>
				<select value="0.25" t="surface" v="sand"/>
				<select value="0.30" t="surface" v="grass"/>
				<select value="0.30" t="surface" v="gravel"/>
				<select value="0.30" t="surface" v="fine_gravel"/>
				<select value="0.30" t="surface" v="pebblestone"/>
				<select value="0.40" t="surface" v="unpaved"/>
				<select value="0.45" t="surface" v="compacted"/>
				<!-- Cobbles and setts are paved and perfectly drivable, so the penalty is mild.
				     They are here because in Cairo they overwhelmingly mark the old quarters,
				     where the streets they surface are narrow by construction. -->
				<select value="0.35" t="surface" v="cobblestone"/>
				<select value="0.35" t="surface" v="sett"/>

				<!-- Tier 3b: surveyed ride quality. Same standing as surface - positively
				     surveyed, never inferred - and the same caveat: it measures how rough a road
				     is, not how wide. It earns its place because in Cairo's informal areas the
				     rough streets and the narrow streets are largely the same streets, and
				     because smoothness is one of the few tags the router can actually see.
				     Kept mild at the common end: "bad" only means a car needs decent wheels, and
				     it is tagged on plenty of ordinary through-roads. -->
				<select value="0.10" t="smoothness" v="impassable"/>
				<select value="0.15" t="smoothness" v="very_horrible"/>
				<select value="0.20" t="smoothness" v="horrible"/>
				<select value="0.30" t="smoothness" v="very_bad"/>
				<select value="0.45" t="smoothness" v="bad"/>

				<!-- Tier 4: one lane shared in both directions - two cars cannot pass. Weaker
				     than the above because lanes=1 is sometimes tagged on wide one-way roads,
				     hence the explicit oneway exclusions. -->
				<select value="0.35" t="lanes" v="1">
					<ifnot t="oneway" v="yes"/>
					<ifnot t="oneway" v="-1"/>
				</select>

				<!-- Tier 5: living streets are shared pedestrian/vehicle space and usually
				     narrow, but they are also legitimate residential roads, so this is the
				     mildest penalty in the set.
				     0.35 and not 0.60: upstream already rates living_street 0.5 by default, and
				     because these rules sit at the TOP of the block and first match wins, any
				     value above 0.5 would OVERRIDE upstream and make living streets MORE
				     attractive with the option switched on - the exact opposite of the intent.
				     Every value here must stay below the upstream default it shadows. -->
				<select value="0.35" t="highway" v="living_street"/>
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
