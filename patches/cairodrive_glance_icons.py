#!/usr/bin/env python3
"""
Produces a desaturated icon set for the CairoDrive glance style.

WHY THIS EXISTS, AND WHY IT WAS PREVIOUSLY CALLED IMPOSSIBLE

    cairodrive_driving_style.py removes most POI icons at navigation zoom, and it collapses POI
    LABEL colour to one grey pair. It could not touch the icons that REMAIN, and the reason was
    checked rather than assumed: a rendering style has no colour output for a point symbol at
    all. The whole set is icon, icon_2..5, icon__1..3, shield, iconOrder, iconVisibleSize,
    intersectionSize*, iconMinDistance, icon_shift_p[xy] - verified in
    RenderingRuleStorageProperties (legacy renderer) and MapStyleBuiltinValueDefinitions_Set.h
    (the OpenGL core). Not one is a colour.

    That conclusion was correct and the inference drawn from it was not. "A style cannot tint a
    bitmap" does not mean the colour cannot be removed; it means it has to be removed from the
    BITMAP, before the style ever sees it. That is what this does.

WHAT IT DOES

    Rewrites the PNGs the glance style still draws, in place in the OsmAnd-resources checkout,
    converting each to greyscale while preserving alpha exactly. The icons keep their SHAPE -
    which is what identifies a petrol station at a glance - and lose the hue, which is what
    competes with the route line and the position arrow for attention.

    Luminance is Rec. 709 (0.2126 R, 0.7152 G, 0.0722 B), not a flat average. A flat average
    turns a saturated red and a saturated blue into the same mid-grey, which would make a hazard
    icon and a water icon identical - the exact failure this is supposed to prevent.

    Output is then lifted toward black by DARKEN so the remaining glyphs sit BELOW the route line
    and the arrow in the visual hierarchy rather than competing with them, matching what change 3
    did for label ink.

WHY IT IS A SEPARATE SCRIPT FROM cairodrive_driving_style.py

    That one edits XML and is gated by a rendering property the user can toggle mid-drive. This
    one edits BITMAPS and cannot be gated at all - once a PNG is grey it is grey for every
    profile and every zoom. So it is opt-in at BUILD time only, it refuses to run twice, and it
    is deliberately not wired into CI by default. Turning the glance style off in Configure map
    does NOT bring the colour back; only a rebuild without this script does.

    That asymmetry is the whole reason for the separation, and it is why the icon list below is
    narrow: only classes whose colour carries no information a driver acts on.

USAGE
    python3 patches/cairodrive_glance_icons.py <path-to-resources-checkout>
"""

import os
import re
import struct
import sys
import zlib

# Rec. 709 luma. See the module comment for why not a flat average.
LUMA_R, LUMA_G, LUMA_B = 0.2126, 0.7152, 0.0722
# Pull the result toward black so glyphs sit below the route line and arrow. 0.0 = untouched,
# 1.0 = black. 0.35 keeps shape legible on both the light and dark map backgrounds.
DARKEN = 0.35

# Marker written into the PNG as a private text chunk. PNG desaturation is not idempotent by
# inspection - a grey image is a perfectly valid input to a desaturator - so re-running would
# darken everything a second time. This is what makes the script safe to run twice.
MARKER_KEY = b"cairodrive"
MARKER_VAL = b"glance-icons-v1"

# Icon families to desaturate. Deliberately narrow: only classes whose COLOUR carries nothing a
# driver acts on. Anything whose colour IS the information stays untouched - see KEEP below.
TARGET_PREFIXES = (
    "mm_shop", "mm_amenity", "mm_leisure", "mm_tourism", "mm_craft", "mm_office",
    "mm_sport", "mm_natural", "mm_historic", "mm_man_made", "mm_landuse",
)

# Never touched, and each for a reason a driver would recognise.
KEEP = (
    "fuel",          # the one POI a driver diverts for
    "charging",      # same
    "hazard",        # colour IS the warning
    "traffic",       # signals, calming - shape alone is ambiguous
    "speed",         # speed cameras and limits
    "railway",       # level crossings
    "highway",       # road furniture the router references
    "barrier",       # gates and bollards block a route
    "emergency",
    "hospital",
    "pharmacy",      # the other thing people divert for at speed
    "police",
    "toll",
)


class Unsupported(ValueError):
    """A PNG this script cannot process, but which is not damaged.

    Kept distinct from a plain ValueError on purpose. A truncated chunk or a bad zlib stream means
    the resources checkout is broken and the build should stop. An interlaced or 16-bit-per-channel
    icon means only that upstream authored one file differently - and killing a whole build, on a
    1000-icon set, over one unusual file would be the wrong trade every single time.

    So: damage fails the build, an unsupported variant is skipped and counted loudly.
    """


def fail(msg):
    sys.stderr.write("cairodrive_glance_icons: %s\n" % msg)
    sys.exit(1)


def chunks(data):
    """Yield (type, payload) for every chunk. Raises on a malformed stream."""
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG")
    pos = 8
    while pos < len(data):
        if pos + 8 > len(data):
            raise ValueError("truncated chunk header")
        (length,) = struct.unpack(">I", data[pos:pos + 4])
        ctype = data[pos + 4:pos + 8]
        payload = data[pos + 8:pos + 8 + length]
        if len(payload) != length:
            raise ValueError("truncated chunk payload")
        yield ctype, payload
        pos += 12 + length


def build(chunk_list):
    out = [b"\x89PNG\r\n\x1a\n"]
    for ctype, payload in chunk_list:
        out.append(struct.pack(">I", len(payload)))
        out.append(ctype)
        out.append(payload)
        out.append(struct.pack(">I", zlib.crc32(ctype + payload) & 0xFFFFFFFF))
    return b"".join(out)


def grey(r, g, b):
    y = LUMA_R * r + LUMA_G * g + LUMA_B * b
    y *= (1.0 - DARKEN)
    return max(0, min(255, int(round(y))))


def unfilter(raw, width, height, bpp):
    """Undo PNG scanline filters. Returns a flat bytearray of unfiltered pixel data."""
    stride = width * bpp
    out = bytearray()
    prev = bytearray(stride)
    pos = 0
    for _ in range(height):
        if pos >= len(raw):
            raise ValueError("truncated scanline data")
        ft = raw[pos]
        pos += 1
        line = bytearray(raw[pos:pos + stride])
        if len(line) != stride:
            raise ValueError("truncated scanline")
        pos += stride
        if ft == 1:
            for i in range(bpp, stride):
                line[i] = (line[i] + line[i - bpp]) & 0xFF
        elif ft == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif ft == 3:
            for i in range(stride):
                left = line[i - bpp] if i >= bpp else 0
                line[i] = (line[i] + ((left + prev[i]) >> 1)) & 0xFF
        elif ft == 4:
            for i in range(stride):
                a = line[i - bpp] if i >= bpp else 0
                b = prev[i]
                c = prev[i - bpp] if i >= bpp else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
        elif ft != 0:
            raise ValueError("unknown filter type %d" % ft)
        out.extend(line)
        prev = line
    return out


def desaturate(data):
    """Return the desaturated PNG bytes, or None when the file needs no change."""
    parsed = list(chunks(data))
    for ctype, payload in parsed:
        if ctype == b"tEXt" and payload.startswith(MARKER_KEY + b"\x00"):
            return None  # already done

    ihdr = next((p for t, p in parsed if t == b"IHDR"), None)
    if ihdr is None or len(ihdr) < 13:
        raise ValueError("no IHDR")
    width, height, depth, colour, comp, filt, interlace = struct.unpack(">IIBBBBB", ihdr[:13])
    if depth != 8:
        raise Unsupported("bit depth %d is not supported" % depth)
    if interlace != 0:
        raise Unsupported("interlaced PNG is not supported")

    out = []
    if colour == 3:
        # Palette. The cheapest and most exact case by far: recolour PLTE and every pixel index
        # follows. Nothing is decompressed and no filter is touched, so the image data is
        # byte-identical apart from the palette itself.
        seen_plte = False
        for ctype, payload in parsed:
            if ctype == b"PLTE":
                seen_plte = True
                pal = bytearray(payload)
                for i in range(0, len(pal) - 2, 3):
                    y = grey(pal[i], pal[i + 1], pal[i + 2])
                    pal[i] = pal[i + 1] = pal[i + 2] = y
                out.append((ctype, bytes(pal)))
            else:
                out.append((ctype, payload))
        if not seen_plte:
            raise ValueError("colour type 3 with no PLTE")
    elif colour in (2, 6):
        bpp = 3 if colour == 2 else 4
        idat = b"".join(p for t, p in parsed if t == b"IDAT")
        pixels = unfilter(zlib.decompress(idat), width, height, bpp)
        for i in range(0, len(pixels), bpp):
            y = grey(pixels[i], pixels[i + 1], pixels[i + 2])
            pixels[i] = pixels[i + 1] = pixels[i + 2] = y
            # pixels[i+3] - alpha - is deliberately untouched. An icon that loses its alpha
            # becomes an opaque square on the map, which is worse than any colour problem.
        stride = width * bpp
        rebuilt = bytearray()
        for row in range(height):
            rebuilt.append(0)  # filter type 0; correctness over a byte or two of size
            rebuilt.extend(pixels[row * stride:(row + 1) * stride])
        packed = zlib.compress(bytes(rebuilt), 9)
        wrote = False
        for ctype, payload in parsed:
            if ctype == b"IDAT":
                if not wrote:
                    out.append((b"IDAT", packed))
                    wrote = True
                continue  # drop any further IDATs; the single one above replaces them all
            out.append((ctype, payload))
    else:
        # Colour type 0 or 4 is already greyscale. Mark it so a re-run does not re-darken it.
        out = list(parsed)

    marked = []
    for ctype, payload in out:
        if ctype == b"IEND":
            marked.append((b"tEXt", MARKER_KEY + b"\x00" + MARKER_VAL))
        marked.append((ctype, payload))
    return build(marked)


def wanted(name):
    if not name.endswith(".png"):
        return False
    low = name.lower()
    if any(k in low for k in KEEP):
        return False
    return any(low.startswith(p) for p in TARGET_PREFIXES)


def main():
    if len(sys.argv) != 2:
        fail("usage: cairodrive_glance_icons.py <resources-checkout>")
    root = sys.argv[1]
    if not os.path.isdir(root):
        fail("%s is not a directory" % root)

    icon_dir = None
    for candidate in ("rendering_styles/style-icons/map-icons-png",
                      "rendering_styles/style-icons",
                      "style-icons/map-icons-png",
                      "style-icons"):
        path = os.path.join(root, candidate)
        if os.path.isdir(path):
            icon_dir = path
            break
    if icon_dir is None:
        fail("no style-icons directory under %s. Upstream may have moved the icon set - re-check "
             "this script against the checkout rather than silently doing nothing." % root)

    targets = sorted(n for n in os.listdir(icon_dir) if wanted(n))
    if not targets:
        fail("matched 0 icons in %s. The naming convention has changed; re-check TARGET_PREFIXES "
             "rather than shipping a build that reports success and changes nothing." % icon_dir)

    # Convert everything in memory FIRST. A half-converted icon set is a map with two visual
    # languages on it, and unlike the XML patches there is no re-checkout-free way back.
    pending = {}
    skipped = 0
    unsupported = []
    for name in targets:
        path = os.path.join(icon_dir, name)
        try:
            with open(path, "rb") as fh:
                data = fh.read()
            result = desaturate(data)
        except Unsupported as exc:
            # Not damage - see the Unsupported docstring. Left in colour and reported.
            unsupported.append("%s (%s)" % (name, exc))
            continue
        except (OSError, ValueError, zlib.error) as exc:
            fail("%s could not be converted (%s). Refusing to write ANY icon, so the set stays "
                 "consistent - fix or re-checkout the resources tree." % (name, exc))
        if result is None:
            skipped += 1
        else:
            pending[path] = result

    if unsupported:
        # Printed in full rather than counted. A handful is upstream authoring quirk; a sudden
        # large number means the icon set changed format and this script needs revisiting, and
        # that is only visible if the names are in the log.
        print("cairodrive_glance_icons: %d icon(s) left in COLOUR - unsupported PNG variant:"
              % len(unsupported))
        for u in unsupported:
            print("cairodrive_glance_icons:   %s" % u)

    if not pending:
        if skipped:
            print("cairodrive_glance_icons: already applied to all %d icons, nothing to do"
                  % skipped)
            return
        # Nothing converted AND nothing was already done means every match failed the variant
        # check - that is a format change, not a no-op, and saying "nothing to do" would report
        # success for a build whose icons are entirely untouched.
        fail("matched %d icons and converted none of them - every one hit an unsupported PNG "
             "variant. The icon set's format has changed; re-check this script rather than "
             "shipping a build whose icons are all still in colour." % len(targets))

    for path, blob in pending.items():
        with open(path, "wb") as fh:
            fh.write(blob)

    print("cairodrive_glance_icons: desaturated %d icons (%d already done) in %s"
          % (len(pending), skipped, icon_dir))
    print("cairodrive_glance_icons: this edits BITMAPS and cannot be toggled at runtime - "
          "turning the glance style off in Configure map does NOT bring the colour back")
    print("cairodrive_glance_icons: kept in colour: %s" % ", ".join(KEEP))


if __name__ == "__main__":
    main()
