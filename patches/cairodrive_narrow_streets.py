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

WHAT THIS OPTION IS FOR, STATED AS THE OWNER STATED IT
    Remove the roads only a TUK-TUK gets through. Keep every road a car gets through, including
    the tight ones - he lives on a street where two cars pass with difficulty and that street
    must stay routable. And avoid the streets that are physically wide but impossible to drive:
    souks, and streets the residents have blocked off.

    Those three sentences are the specification, and the first version of this file failed the
    second one. It penalised width < 3.5 m and, worse, carried a 0.35 penalty on
    `lanes=1 and not oneway` - which is literally "two cars cannot pass", i.e. a 2.9x cost
    penalty on driving home. Both are gone.

WHAT THE MAP CAN AND CANNOT SEE - read this before adding a rule
    Measured on the 2026-08-07 drive by CD_NARROW: of 14 actionable ways on the route, ALL 14
    were tagged by SURFACE and NONE by width, maxwidth or lanes. City-wide Overpass agrees -
    width appears on 14 ways out of 71922. So the surface tier was doing essentially all of the
    work, and an unpaved road six metres wide is one the owner wants driven. That tier is now
    cut to the four surfaces that can strand a car rather than merely shake it.

    The signal that WOULD identify a tuk-tuk alley in Cairo is the NAME - عطفة, حارة, زقاق, درب,
    ممر are alleys by definition of what they are called, and CLAUDE.md records them on ~16.6%
    of the network against ~2.5% for all routing tags combined. The router cannot read names:
    only tags the map builder encoded into the .obf via rendering_types.xml reach it. Acting on
    that signal needs a custom Egypt .obf that bakes the name convention into a tag. Nothing in
    this file can substitute for it, and no rule here should pretend to.

    A street blocked by the people who live on it, or by a market that sets up in it, carries
    NO OSM tag whatsoever. It is not under-mapped, it is unmappable - the blockage is social and
    changes by the hour. There is no accurate algorithm for it and this file does not attempt
    one. The two mechanisms that DO work are outside routing.xml: OsmAnd's own avoid-roads list
    (AvoidRoadsHelper, long-press a road and it becomes impassable for every future route), and
    live closures from TomTom/HERE (CD_CLOSURE). A guess here would fail exactly the way an
    inferred-narrowness rule fails - confidently, and on the roads he actually drives.

WHY PRIORITY PENALTIES AND NOT A BLOCK
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
    that is what stops this option from penalising most of Cairo.

    Because these rules sit at the TOP of the priority block and the first match wins, every
    value must be BELOW the upstream default it shadows, or the rule silently makes that road
    class more attractive instead of less. Upstream rates track, service and living_street at
    0.5, so 0.45 is the highest value any rule here may take.

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
import xml.etree.ElementTree as ET

PARAM_ID = "avoid_narrow_streets"

# default="true" is read by RoutingConfiguration.parseRoutingParameter into
# RoutingParameter.defaultBoolean, which is what OsmandSettings.getCustomRoutingBooleanProperty
# is handed as the preference default. So this is what makes the option ON out of the box without
# any app-side code: the user can still turn it off in Route parameters.
PARAM_DECL = (
    '\t\t<parameter id="%s" name="Avoid roads a car cannot use" default="true"'
    ' description="Avoids alleys, tracks and pedestrianised streets. Streets where two cars'
    ' pass with difficulty are NOT avoided, and any road is still used when it is the only'
    ' way to the destination." type="boolean"/>\n' % PARAM_ID
)

# Inserted at the TOP of the car profile's priority block: the engine takes the first
# matching select, so these must be evaluated before upstream's own highway-class rules.
#
# THE THRESHOLD, and the correction that produced it.
#
# The owner's requirement, in his words: remove the roads that only a TUK-TUK gets through.
# Keep everything a car gets through, INCLUDING the tight ones - he lives on a street where
# "two cars pass with difficulty", and that street must stay routable.
#
# A tuk-tuk is ~1.3 m wide. A car is ~1.8 m of body and ~2.0 m over the mirrors. So the line
# he is drawing sits at roughly 2.5 m, and everything above it is a road he wants USED, not
# avoided. The previous version of this file drew the line at 3.5 m and penalised a road a car
# drives down perfectly well, which is the opposite of what was asked for.
#
# Operator semantics, from routing.xml's own documentation: ':' prefixes a PARAMETER you
# defined, '$' prefixes a TAG loaded from the obf. So "$width" is the road's surveyed
# width, and <lt value1="$width" value2="2.5"> reads "the road's width tag is under 2.5 m".
#
# THE CEILING THAT BOUNDS EVERY VALUE HERE: these rules sit at the TOP of the priority block
# and the first match wins, so a value must be BELOW the upstream default it shadows or the
# rule makes that road MORE attractive with the option on. Upstream rates track, service and
# living_street at 0.5, so 0.45 is the highest value any rule here may take.
RULES = """
			<!-- CairoDrive: deprioritise streets a car cannot use. Penalties only, never
			     value="-1", so a road stays usable when it is the only way to the destination.
			     Ordered by precision - first match wins, so surveyed width outranks inference. -->
			<if param="%s">
				<!-- Tier 1: surveyed width. Exact, and the only tier that measures the thing the
				     option is named after. Rare in Cairo - an Overpass count found width on 14 ways
				     out of 71922 - so it almost never fires, which is precisely why the tiers below
				     must not overreach on its behalf. -->
				<select value="0.02" t="width">
					<lt value1="$width" value2="2.0" type="length"/>
				</select>
				<select value="0.05" t="width">
					<lt value1="$width" value2="2.5" type="length"/>
				</select>
				<!-- 2.5-3.0 m: a car fits with no margin. A deterrent, not an exclusion, and
				     deliberately the last width tier. Anything wider is a street the owner drives
				     daily and wants chosen. -->
				<select value="0.35" t="width">
					<lt value1="$width" value2="3.0" type="length"/>
				</select>
				<!-- maxwidth is a legal limit rather than a measurement, but a road signed under
				     2.5 m is not one a car is meant to be on. -->
				<select value="0.02" t="maxwidth">
					<lt value1="$maxwidth" value2="2.0" type="length"/>
				</select>
				<select value="0.05" t="maxwidth">
					<lt value1="$maxwidth" value2="2.5" type="length"/>
				</select>

				<!-- Tier 2: definitional. These tags do not CORRELATE with "only a tuk-tuk gets
				     through" - they mean it. This is the highest-precision signal available without
				     a survey, and with width almost never tagged it is what the option actually
				     runs on. -->
				<select value="0.15" t="service" v="alley"/>
				<select value="0.20" t="highway" v="track"/>
				<!-- A driveway or a parking aisle is somebody's access, not a route. Upstream
				     already rates highway=service 0.5; these subtypes deserve worse because
				     they are never a legitimate through-road. -->
				<select value="0.20" t="service" v="driveway"/>
				<select value="0.20" t="service" v="parking_aisle"/>

				<!-- Tier 2b: WIDE BUT NOT FOR CARS. The owner asked for this case by name - a souk
				     street that is physically wide and impossible to drive. Only the taggable half of
				     it is reachable: highway=pedestrian is a street given over to people, which is
				     what a mapped Cairo market street usually carries.
				     motor_vehicle=no and vehicle=no are deliberately NOT here - upstream's access
				     section already refuses those outright, so a priority rule would be dead weight
				     that looks like it works.
				     A street blocked by the people who live on it carries NO tag at all and cannot be
				     inferred from any map. See the note in the module docstring. -->
				<select value="0.05" t="highway" v="pedestrian"/>

				<!-- Tier 3: surface, and this tier has been CUT HARD on purpose.
				     It used to run from 0.25 to 0.45 across ten values, and CD_NARROW on the
				     2026-08-07 drive showed why that mattered: of 14 actionable ways on the route,
				     all 14 were tagged by SURFACE and none by width or lanes. So in Cairo this option
				     was, in practice, an unpaved-roads penalty wearing a narrow-streets name - and an
				     unpaved road that is six metres wide is one the owner wants driven.
				     What survives is the surfaces that can strand a car rather than merely shake it,
				     and even those are mild. compacted, unpaved, cobblestone and sett are GONE: all
				     four are drivable, and cobbles were only ever a proxy for "old quarter". -->
				<select value="0.45" t="surface" v="mud"/>
				<select value="0.45" t="surface" v="sand"/>
				<select value="0.45" t="surface" v="ground"/>
				<select value="0.45" t="surface" v="dirt"/>

				<!-- Tier 3b: surveyed ride quality, cut for the same reason and to the same shape.
				     `impassable` is a statement that a car does not get through, so it keeps the
				     strongest penalty in the file. `bad` and `very_bad` are GONE - they are tagged on
				     ordinary Cairo through-roads and say nothing about width. -->
				<select value="0.05" t="smoothness" v="impassable"/>
				<select value="0.15" t="smoothness" v="very_horrible"/>
				<select value="0.30" t="smoothness" v="horrible"/>

				<!-- Tier 3c: track grade. Roughness again, not width, so only the two grades that
				     mean "barely a road" survive. grade3 is GONE at 0.45 it was nearly a no-op. -->
				<select value="0.30" t="tracktype" v="grade5"/>
				<select value="0.40" t="tracktype" v="grade4"/>

				<!-- REMOVED, and this is the single most important line in the file:
				       <select value="0.35" t="lanes" v="1"> ... not oneway ...
				     "one lane shared in both directions" is the definition of a street where two cars
				     cannot pass - which is the owner's OWN STREET, and the exact class of road he
				     asked to keep. It was a 2.9x cost penalty on driving home. Do not restore it. -->

				<!-- Tier 4: living streets are shared pedestrian/vehicle space and often tight, but
				     they are legitimate residential roads, so this is the mildest penalty in the set
				     and sits just under upstream's own 0.5. -->
				<select value="0.45" t="highway" v="living_street"/>
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

    # Recorded before any edit so verify() can tell "this script broke it" from "upstream shipped
    # something this parser cannot read", which are not the same failure and must not be treated
    # the same way.
    try:
        ET.fromstring(xml)
        parsed_before = True
    except ET.ParseError:
        parsed_before = False

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

    verify(xml, parsed_before)

    try:
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(xml)
    except OSError as exc:
        fail("cannot write %s: %s" % (path, exc))

    print("cairodrive_narrow_streets: added '%s' to the car profile" % PARAM_ID)


# The highest value any rule here may take. These selects sit at the TOP of the priority block
# and the first match wins, so a value at or above the default it shadows makes that road class
# MORE attractive with the option on - the exact opposite of the intent. Upstream rates track,
# service and living_street at 0.5.
CEILING = 0.5


def verify(xml, parsed_before):
    """Refuse to write a routing.xml this script has broken.

    Two failures are worth a build-time check rather than a comment. Neither shows up until the
    app is on a Cairo road: routing.xml is parsed at RUNTIME, so a malformed insert means the car
    profile fails to load, and a value over the ceiling means the option silently makes bad roads
    more attractive - which looks like working software.

    `parsed_before` is why this cannot simply parse and fail: if upstream ships a routing.xml this
    parser cannot read, that is not this script's doing and failing the build for it would be
    wrong. The check only fires when the file parsed BEFORE the edit and does not parse after.
    """
    try:
        root = ET.fromstring(xml)
    except ET.ParseError as exc:
        if not parsed_before:
            print("cairodrive_narrow_streets: routing.xml did not parse before the edit either,"
                  " so the insert is not being blamed for it (%s)" % exc)
            return
        fail("the patched routing.xml no longer parses, so this script broke it: %s" % exc)

    blocks = [node for node in root.iter("if") if node.get("param") == PARAM_ID]
    if len(blocks) != 1:
        fail("expected exactly one <if param=\"%s\"> block after patching, found %d"
             % (PARAM_ID, len(blocks)))

    over = []
    lanes = []
    for select in blocks[0]:
        value = select.get("value")
        if value is not None:
            try:
                if float(value) >= CEILING:
                    over.append((select.get("t"), select.get("v"), value))
            except ValueError:
                fail("non-numeric priority value %r in the inserted rules" % value)
        if select.get("t") == "lanes":
            lanes.append(select.get("v"))
    if over:
        fail("these rules are at or above the %.2f ceiling and would make those roads MORE"
             " attractive: %s" % (CEILING, over))
    if lanes:
        # Deliberately checked by name. `lanes=1 and not oneway` is "two cars cannot pass", which
        # is the owner's own street and the exact class of road he asked to keep. It was removed
        # on 2026-08-07 and is the single most likely rule to be helpfully restored.
        fail("a `lanes` rule is back (%s). That penalises streets where two cars cannot pass,"
             " which is the road class this option must NOT avoid - see the module docstring."
             % lanes)
    print("cairodrive_narrow_streets: verified - %d rules, none at or above %.2f, no lanes rule"
          % (len(blocks[0]), CEILING))


if __name__ == "__main__":
    main()
