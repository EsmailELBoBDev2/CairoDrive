#!/usr/bin/env python3
"""Fail the build if R8 removed anything the native library reaches by name.

WHY THIS EXISTS, and why it is a build step rather than a code review.

`-dontobfuscate` is set for this app, so R8 cannot rename anything and the only
failure mode available to it is REMOVAL. That is what makes this checkable at all:
"did R8 delete something JNI needs" has a deterministic answer, because -printusage
writes out exactly what was removed.

Without this step the failure mode is the one CLAUDE.md describes: a keep rule one
class short does not fail the build, does not fail the install and does not fail a
smoke test. It throws NoSuchMethodError or ClassNotFoundException the first time the
code path is taken, which for most of this app is mid-drive on a head unit with no
debugger attached. This turns that into a red build about four minutes after a push.

The check is deliberately ALL-OR-NOTHING per class: any appearance of a manifest class
in usage.txt fails, whether a whole class or a single member. That is stricter than
"the native side only reads three of its fields", and it is the right strictness,
because the alternative is parsing every GetFieldID/GetMethodID out of java_wrap.cpp
and keeping that mapping in sync forever. If the keep rules are right this file prints
nothing and costs a second; if they are wrong it names the class.
"""
import re
import sys

usage_path, manifest_path = sys.argv[1], sys.argv[2]

manifest = set()
for line in open(manifest_path, encoding="utf-8"):
    line = line.strip()
    if line and not line.startswith("#"):
        manifest.add(line)

if not manifest:
    print("::error::%s contained no class names - the JNI guard would pass "
          "vacuously, which is worse than not having it" % manifest_path)
    sys.exit(1)

# -printusage shape: an unindented line is a class. With a trailing ':' the class
# SURVIVED and the indented lines under it are the members that did not; without one,
# the whole class went.
removed = {}          # class -> list of removed members, [] meaning the class itself
current = None
for raw in open(usage_path, encoding="utf-8", errors="replace"):
    line = raw.rstrip("\n")
    if not line.strip():
        continue
    if line[0] not in " \t":
        name = line.rstrip(":")
        current = name
        if not line.endswith(":") and name in manifest:
            removed.setdefault(name, [])
    elif current in manifest:
        removed.setdefault(current, []).append(line.strip())

if not removed:
    print("R8 JNI guard: all %d native-facing classes intact" % len(manifest))
    sys.exit(0)

print("::error::R8 removed code the native library resolves by name. "
      "This build would crash the first time the affected path ran, "
      "which on this app is usually mid-drive.")
print("")
for cls in sorted(removed):
    members = removed[cls]
    if not members:
        print("  %s  <- ENTIRE CLASS REMOVED" % cls)
    else:
        print("  %s" % cls)
        for m in members[:8]:
            print("      %s" % m)
        if len(members) > 8:
            print("      ... and %d more" % (len(members) - 8))
print("")
print("Add a keep rule naming each of the above to OsmAnd/proguard-rules.pro, or set")
print("CAIRODRIVE_R8_SHRINK=false to restore the blanket keep while it is investigated.")
sys.exit(1)
