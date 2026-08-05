#!/usr/bin/env python3
"""End-to-end check of cairodrive_glance_icons.desaturate on synthetic PNGs.

This is about to gate every CI build, and it had never been run on real input. What matters is
not that it produces a nice picture but that it (a) does not crash, (b) preserves alpha exactly,
(c) is idempotent, and (d) actually removes colour.
"""
import importlib.util
import struct
import sys
import zlib

spec = importlib.util.spec_from_file_location(
    "gi", "/home/user/CairoDrive/patches/cairodrive_glance_icons.py")
gi = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gi)


def chunk(t, p):
    return struct.pack(">I", len(p)) + t + p + struct.pack(">I", zlib.crc32(t + p) & 0xFFFFFFFF)


def make_rgba(w, h, pixels, filt=0):
    """pixels: list of (r,g,b,a). filt: PNG filter type to use on every scanline."""
    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)
    raw = bytearray()
    prev = bytearray(w * 4)
    for y in range(h):
        line = bytearray()
        for x in range(w):
            line.extend(pixels[y * w + x])
        raw.append(filt)
        if filt == 0:
            raw.extend(line)
        elif filt == 2:  # Up
            raw.extend(bytes((line[i] - prev[i]) & 0xFF for i in range(len(line))))
        elif filt == 1:  # Sub
            enc = bytearray()
            for i in range(len(line)):
                left = line[i - 4] if i >= 4 else 0
                enc.append((line[i] - left) & 0xFF)
            raw.extend(enc)
        else:
            raise ValueError("test only builds filters 0/1/2")
        prev = line
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + \
        chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b"")


def make_palette(w, h, palette, indices):
    ihdr = struct.pack(">IIBBBBB", w, h, 8, 3, 0, 0, 0)
    plte = b"".join(bytes(c) for c in palette)
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        raw.extend(indices[y * w:(y + 1) * w])
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"PLTE", plte) + \
        chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b"")


def read_rgba(data, w, h):
    parsed = list(gi.chunks(data))
    idat = b"".join(p for t, p in parsed if t == b"IDAT")
    px = gi.unfilter(zlib.decompress(idat), w, h, 4)
    return [tuple(px[i:i + 4]) for i in range(0, len(px), 4)]


fails = []


def check(name, cond, detail=""):
    print(("  PASS  " if cond else "  FAIL  ") + name + (("  -- " + detail) if detail and not cond else ""))
    if not cond:
        fails.append(name)


print("RGBA, filter 0")
src = [(255, 0, 0, 255), (0, 255, 0, 128), (0, 0, 255, 0), (200, 200, 200, 77)]
out = gi.desaturate(make_rgba(2, 2, src))
check("returns bytes", out is not None)
px = read_rgba(out, 2, 2)
check("alpha preserved exactly", [p[3] for p in px] == [255, 128, 0, 77], str([p[3] for p in px]))
check("all channels equal (greyscale)", all(p[0] == p[1] == p[2] for p in px), str(px))
# Rec.709: red 0.2126, green 0.7152 -> green must be brighter than red, and blue darkest.
check("Rec.709 ordering red<green", px[0][0] < px[1][0], "%s vs %s" % (px[0][0], px[1][0]))
check("Rec.709 blue darkest", px[2][0] < px[0][0], "%s vs %s" % (px[2][0], px[0][0]))
check("DARKEN applied", px[3][0] < 200, "grey 200 -> %s" % px[3][0])

print("idempotence")
again = gi.desaturate(out)
check("second run is a no-op", again is None, "returned %s" % type(again))

print("RGBA, filter 1 (Sub) and 2 (Up) round-trip")
for f in (1, 2):
    o = gi.desaturate(make_rgba(2, 2, src, filt=f))
    p = read_rgba(o, 2, 2)
    check("filter %d alpha preserved" % f, [q[3] for q in p] == [255, 128, 0, 77], str(p))
    check("filter %d greyscale" % f, all(q[0] == q[1] == q[2] for q in p), str(p))

print("palette (colour type 3)")
pal = [(255, 0, 0), (0, 255, 0), (0, 0, 255)]
po = gi.desaturate(make_palette(2, 2, pal, [0, 1, 2, 0]))
check("palette returns bytes", po is not None)
newpal = next(p for t, p in gi.chunks(po) if t == b"PLTE")
triples = [tuple(newpal[i:i + 3]) for i in range(0, len(newpal), 3)]
check("palette entries greyscale", all(t[0] == t[1] == t[2] for t in triples), str(triples))
check("palette idempotent", gi.desaturate(po) is None)

print("rejects what it should")
try:
    gi.desaturate(b"not a png at all")
    check("non-PNG raises", False)
except ValueError:
    check("non-PNG raises", True)

ihdr16 = struct.pack(">IIBBBBB", 1, 1, 16, 6, 0, 0, 0)
bad = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr16) + chunk(b"IEND", b"")
try:
    gi.desaturate(bad)
    check("16-bit depth raises", False)
except ValueError:
    check("16-bit depth raises", True)

print("\nKEEP list is honoured by wanted()")
check("mm_amenity_fuel.png skipped", not gi.wanted("mm_amenity_fuel.png"))
check("mm_amenity_pharmacy.png skipped", not gi.wanted("mm_amenity_pharmacy.png"))
check("mm_shop_bakery.png targeted", gi.wanted("mm_shop_bakery.png"))
check("non-png ignored", not gi.wanted("mm_shop_bakery.svg"))
check("unrelated prefix ignored", not gi.wanted("h_red_shield.png"))

print("\n%d failure(s)" % len(fails))
sys.exit(1 if fails else 0)
