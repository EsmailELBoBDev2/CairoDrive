#!/usr/bin/env python3
"""
Catches the ONE class of error that broke this build: a simple type name used without an import.

Not a compiler. It resolves every capitalised name appearing in a type position against
(a) explicit imports, (b) wildcard-imported packages, (c) the file's own package, (d) java.lang,
(e) names declared inside the file itself. Anything left over is reported.

False positives are expected (nested classes reached through an outer name, generics tricks).
The point is a short list to eyeball, not a verdict.
"""
import os
import re
import sys

# Where the generated R class lands: the `package` on OsmAnd/AndroidManifest.xml. Not a guess -
# read it back with `grep package= OsmAnd/AndroidManifest.xml` if this ever stops matching.
R_PACKAGE = "net.osmand.plus"

ROOTS = [
    "OsmAnd/src", "OsmAnd-java/src/main/java", "OsmAnd-shared/src/commonMain/kotlin",
    "OsmAnd-api/src", "OsmAnd/src-google", "OsmAnd/src-nogoogle",
]

JAVA_LANG = set("""
String Object Integer Long Double Float Boolean Byte Short Character Number Math System
Exception RuntimeException Throwable Error IllegalArgumentException IllegalStateException
NullPointerException UnsupportedOperationException IndexOutOfBoundsException
Thread Runnable Comparable Iterable Override Deprecated SuppressWarnings SafeVarargs
StringBuilder StringBuffer CharSequence Class ClassLoader Enum Void Cloneable AutoCloseable
InterruptedException NumberFormatException ArithmeticException ClassCastException
CloneNotSupportedException NoSuchMethodException NoSuchFieldException ReflectiveOperationException
ArrayIndexOutOfBoundsException StackOverflowError OutOfMemoryError SecurityException
FunctionalInterface ThreadLocal Process ProcessBuilder Package Record Runtime Iterable
""".split())

# The JDK is not on disk to index, so wildcard imports of java.* resolve to nothing and every use
# looks unresolved. Listing the names actually wildcard-imported across this tree keeps the output
# down to things worth reading. Anything missing here shows up as noise, never as a missed error.
JDK = set("""
List ArrayList LinkedList Map HashMap LinkedHashMap TreeMap Set HashSet LinkedHashSet TreeSet
Collection Collections Arrays Iterator ListIterator Comparator Queue Deque ArrayDeque Stack
Vector Hashtable EnumMap EnumSet Optional Objects Random UUID Locale Calendar GregorianCalendar
Date TimeZone Currency Scanner StringTokenizer BitSet Properties Timer TimerTask Formatter
NavigableMap SortedMap NavigableSet SortedSet AbstractMap AbstractList Spliterator
File FileInputStream FileOutputStream InputStream OutputStream Reader Writer BufferedReader
BufferedWriter InputStreamReader OutputStreamWriter FileReader FileWriter IOException
ByteArrayInputStream ByteArrayOutputStream DataInputStream DataOutputStream PrintStream
PrintWriter Serializable Closeable Flushable RandomAccessFile FileNotFoundException StringWriter
Charset StandardCharsets ByteBuffer CharBuffer FloatBuffer IntBuffer
BigDecimal BigInteger Pattern Matcher Executor ExecutorService Executors Future Callable
TimeUnit CountDownLatch AtomicInteger AtomicLong AtomicBoolean AtomicReference
ConcurrentHashMap CopyOnWriteArrayList ConcurrentLinkedQueue LinkedBlockingQueue
BlockingQueue ThreadPoolExecutor ScheduledExecutorService RejectedExecutionException
ExecutionException TimeoutException ReentrantLock URL URI URLEncoder URLDecoder
HttpURLConnection URLConnection MalformedURLException UnsupportedEncodingException
Stream Collectors IntStream Function Supplier Consumer Predicate BiFunction
""".split())
JAVA_LANG |= JDK


def index_packages(base):
    """package name -> set of top-level type names declared in it, plus fqn -> file path."""
    pkgs = {}
    FILES.clear()
    for root in ROOTS:
        d = os.path.join(base, root)
        if not os.path.isdir(d):
            continue
        for dirpath, _, files in os.walk(d):
            for fn in files:
                if not (fn.endswith(".java") or fn.endswith(".kt")):
                    continue
                rel = os.path.relpath(dirpath, d).replace(os.sep, ".")
                stem = fn.rsplit(".", 1)[0]
                pkgs.setdefault(rel, set()).add(stem)
                FILES[rel + "." + stem] = os.path.join(dirpath, fn)
    return pkgs


FILES = {}


def nested_types(fqn):
    """Type names declared inside a class, for `import static Foo.*` which imports them too."""
    path = FILES.get(fqn)
    if not path:
        return set()
    try:
        src = strip(open(path, encoding="utf-8").read())
    except OSError:
        return set()
    return set(re.findall(r"\b(?:class|interface|enum|record|@interface)\s+(\w+)", src))


def strip(src):
    """
    Blank out comments and literals, PRESERVING LINE COUNT.

    Order matters and getting it wrong is not a cosmetic problem. Stripping char literals before
    comments makes an apostrophe in prose - "OsmAnd's", "does not" - open a fake char literal that
    swallows every line until the next apostrophe, silently deleting real code. That is exactly
    how an earlier version of this reported a clean file that had four compile errors in it.

    So: comments first, then literals. And newlines are kept so reported line numbers are real.
    """
    out = []
    i, n = 0, len(src)
    state = None  # None | 'line' | 'block' | 'str' | 'chr'
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if state is None:
            if c == "/" and nxt == "/":
                state = "line"; out.append("  "); i += 2; continue
            if c == "/" and nxt == "*":
                state = "block"; out.append("  "); i += 2; continue
            if c == '"':
                state = "str"; out.append('"'); i += 1; continue
            if c == "'":
                state = "chr"; out.append("'"); i += 1; continue
            out.append(c); i += 1; continue
        if state == "line":
            if c == "\n":
                state = None; out.append("\n")
            else:
                out.append(" ")
            i += 1; continue
        if state == "block":
            if c == "*" and nxt == "/":
                state = None; out.append("  "); i += 2; continue
            out.append("\n" if c == "\n" else " "); i += 1; continue
        # inside a literal
        if c == "\\":
            out.append("  "); i += 2; continue
        if (state == "str" and c == '"') or (state == "chr" and c == "'"):
            state = None; out.append(c); i += 1; continue
        out.append("\n" if c == "\n" else " "); i += 1; continue
    return "".join(out)


def nested_within(src, outer):
    """Types declared inside `class <outer> { ... }`, found by brace matching.

    A regex over the whole file cannot tell a nested class from a sibling, and the difference is
    exactly what Java inheritance does and does not give you.
    """
    m = re.search(r"\b(?:class|interface|enum|record)\s+" + re.escape(outer) + r"\b[^{]*\{", src)
    if not m:
        return set()
    depth, i, n = 0, m.end() - 1, len(src)
    while i < n:
        if src[i] == "{":
            depth += 1
        elif src[i] == "}":
            depth -= 1
            if depth == 0:
                break
        i += 1
    body = src[m.end():i]
    return set(re.findall(r"\b(?:class|interface|enum|record|@interface)\s+(\w+)", body))


# The name after `extends` may be QUALIFIED - `extends SearchCoreFactory.SearchBaseAPI`. Capturing
# only the first segment is not a small inaccuracy: it names the OUTER class, and everything
# downstream then treats the outer class as the superclass. That is what turned this method into a
# false negative (see below), so the dotted tail is part of the match.
EXTENDS_RE = r"\bclass\s+\w+[^{]*?\bextends\s+([A-Z]\w*(?:\s*\.\s*[A-Z]\w*)*)"


def resolve_supertype(name, import_map, wildcards, own_pkg, pkgs):
    """A dotted `extends` name -> (file holding it, simple name of the class extended).

    Two shapes have to land in the same place. `extends SearchCoreFactory.SearchBaseAPI` carries
    its outer class in the text; `extends SearchBaseAPI` under an
    `import net.osmand.search.core.SearchCoreFactory.SearchBaseAPI` carries it in the import. Both
    must resolve to SearchCoreFactory.java with the target `SearchBaseAPI`, not `SearchCoreFactory`.
    """
    parts = [p.strip() for p in name.split(".")]
    head = parts[0]
    full = import_map.get(head)
    if full:
        dotted = ".".join([full] + parts[1:])
    else:
        dotted = None
        for pkg in list(wildcards) + [own_pkg]:
            if head in pkgs.get(pkg, set()):
                dotted = ".".join([pkg] + parts)
                break
        if dotted is None:
            return None
    # Longest prefix that names a FILE. Anything left over is nesting inside it.
    segs = dotted.split(".")
    for i in range(len(segs), 0, -1):
        cand = ".".join(segs[:i])
        if cand in FILES:
            return FILES[cand], segs[-1]
    return None


def inherited_nested(src, wildcards, own_pkg, pkgs, depth=3):
    """Nested type names visible through the `extends` chain, walked up to `depth` levels.

    Java lets a subclass refer to a superclass's nested type by its simple name, unqualified and
    unimported. Without this, `NetworkListener` - declared `protected class` on
    LocationServiceHelper and used bare in AndroidApiLocationServiceHelper - reads as unresolved.

    Only `extends` is followed, not `implements`: an interface's nested types are reachable the
    same way, but interfaces here are overwhelmingly small callback types with nothing nested, and
    each extra edge is more file reading on every checked file for no findings.
    """
    # Imports keyed by simple name, because a nested type is imported by its FULL path and the
    # outer class in that path is the only way to find the file it lives in.
    import_map = {}
    for im in re.finditer(r"^\s*import\s+(?:static\s+)?([\w.]+)\s*;", src, re.M):
        path = im.group(1)
        import_map[path.rsplit(".", 1)[-1]] = path

    found = set()
    seen = set()
    # EVERY extends in the file, not just the first. A nested class can extend something whose
    # nested types it then names unqualified - QuickSearchHelper's inner controller extends
    # TopToolbarController and uses its TopToolbarControllerType that way - and looking only at
    # the outermost class missed exactly that, which failed a CI build on a false positive.
    pending = [m.group(1) for m in re.finditer(EXTENDS_RE, src)]
    hops = 0
    while pending and hops < depth * 4:
        hops += 1
        name = pending.pop(0)
        if name in seen:
            continue
        seen.add(name)
        # Unresolvable means it is outside this tree (a framework class), and its nested types are
        # not knowable from here - which is fine, they are not the case this exists for.
        target = resolve_supertype(name, import_map, wildcards, own_pkg, pkgs)
        if not target:
            continue
        path, simple = target
        try:
            parent = strip(open(path, encoding="utf-8").read())
        except OSError:
            continue
        # ONLY the types nested inside the class actually extended - not every type in that FILE.
        # Harvesting the whole file made a SIBLING of the superclass look importable:
        # QuickSearchHelper extends SearchCoreFactory.SearchAmenityByTypeAPI, and this admitted its
        # sibling SearchAmenityTypesAPI, which Java does not inherit. The result was a false
        # NEGATIVE that let a real "cannot find symbol" reach CI - worse than the false positive
        # this method was widened to remove.
        found |= nested_within(parent, simple)
        # Keep climbing: the nested type may be declared further up the chain.
        pending += [m.group(1) for m in re.finditer(EXTENDS_RE, parent)]
    return found


def bad_char_literals(raw, src):
    """`'x'` holding more than one character - `replace('\\n', ' | ')` - as line -> literal.

    javac rejects it outright, and nothing else here would see it: the name resolution above is
    about types, and `strip` deliberately blanks literal CONTENT, so a broken one reads as a
    perfectly ordinary literal. It cost a build run on 2026-08-05.

    Offsets work because `strip` is length-preserving in every branch - two spaces for `//`, two
    for an escape pair - so a position in the stripped text is the same position in the original.
    That is what makes it safe to find the quotes in the stripped text (where comments and strings
    cannot fake one) and then read the real characters between them.
    """
    found = {}
    i = 0
    while i < len(src):
        if src[i] != "'":
            i += 1
            continue
        j = src.find("'", i + 1)
        if j == -1:
            break
        inner = raw[i + 1:j]
        # An escape - '\n', '\'', 'A' - is one character however long it is written.
        if len(inner) > 1 and not inner.startswith("\\"):
            found[raw[:i].count("\n") + 1] = inner
        i = j + 1
    return found


def check(path, pkgs):
    raw = open(path, encoding="utf-8").read()
    src = strip(raw)
    if len(src) != len(raw):
        # Cannot happen unless strip stops preserving length, and silently mis-reporting line
        # numbers everywhere would be worse than saying so.
        raise AssertionError("strip() changed length for %s" % path)

    m = re.search(r"^\s*package\s+([\w.]+)\s*;", src, re.M)
    own_pkg = m.group(1) if m else ""

    imported, wildcards, static_wildcards = set(), set(), set()
    for im in re.finditer(r"^\s*import\s+(static\s+)?([\w.]+)(\.\*)?\s*;", src, re.M):
        if im.group(3):
            # `import static Foo.*` imports Foo's NESTED TYPES as well as its static members, so
            # it is not a package wildcard. Treating it as one is how this reported UpdateFrequency
            # and TimeOfDay as unresolved when they are nested enums of LiveUpdatesHelper.
            (static_wildcards if im.group(1) else wildcards).add(im.group(2))
        else:
            imported.add(im.group(2).rsplit(".", 1)[-1])

    # Types declared in this file, including nested and enums.
    local = set(re.findall(r"\b(?:class|interface|enum|record|@interface)\s+(\w+)", src))
    # Type parameters: <T>, <T extends X>, <K, V>
    for tp in re.findall(r"<([A-Z]\w*(?:\s*,\s*[A-Z]\w*)*)>", src):
        for t in tp.split(","):
            t = t.strip()
            if len(t) <= 2:
                local.add(t)

    available = set(imported) | local | JAVA_LANG
    for w in wildcards | {own_pkg}:
        available |= pkgs.get(w, set())
    for sw in static_wildcards:
        available |= nested_types(sw)
        available.add(sw.rsplit(".", 1)[-1])
    # Same-directory siblings for the file's own package, keyed by path too.
    available |= pkgs.get(own_pkg, set())

    # `R` is GENERATED into the manifest's package at build time, so there is no R.java on disk for
    # index_packages to find and a file in that package using it bare reads as unresolved. Eight
    # files in net.osmand.plus do exactly that, and every one of them compiles.
    #
    # Scoped to that one package rather than allowed everywhere, because a file in a DIFFERENT
    # package using bare `R` with no import genuinely is a compile error and is worth keeping
    # catchable. Whether the R members themselves exist is cd-refcheck's job, not this one's.
    if own_pkg == R_PACKAGE:
        available.add("R")

    # Nested types INHERITED from superclasses. A subclass may name its parent's `protected class
    # NetworkListener` with no import and no qualifier, which is real Java and looked like an
    # unresolved name here. That matters more since these findings gate CI: a false positive that
    # fails a build is not noise, it is a broken gate.
    available |= inherited_nested(src, wildcards, own_pkg, pkgs)

    # Type positions: `Foo bar =`, `new Foo(`, `Foo.class`, `extends Foo`, `implements Foo`,
    # `catch (Foo`, `instanceof Foo`, `(Foo)` casts, `<Foo>`.
    #
    # The last pattern is a STATIC RECEIVER - `Foo.bar()`, `Foo.CONSTANT`. It is not a type position
    # in the usual sense and was missing, which is why a file using CairoDriveLogger only through
    # `CairoDriveLogger.isEnabled()` and `CairoDriveLogger.getInstance()`, with no import at all,
    # passed this checker clean and then failed the build with four `cannot find symbol` errors.
    # A class used purely for its statics never appears in a declaration, so nothing else here
    # would ever see it.
    #
    # The leading `(?<![.\w$])` is what keeps it honest: it refuses to match the tail of a
    # qualified name, so `net.osmand.plus.Version.getFullVersion()` and `a.b.C.d()` do not report
    # `Version` or `C`. The receiver must genuinely start the expression.
    pats = [
        r"\bnew\s+([A-Z]\w*)\s*[(<\[]",
        r"^\s*(?:public|private|protected|static|final|abstract|synchronized|volatile|transient|\s)*\b([A-Z]\w*)(?:<[^;={]*>)?(?:\[\])?\s+\w+\s*[;=)]",
        r"\bextends\s+([A-Z]\w*)",
        r"\bimplements\s+([A-Z]\w*)",
        r"\bcatch\s*\(\s*(?:final\s+)?([A-Z]\w*)",
        r"\binstanceof\s+([A-Z]\w*)",
        r"\b([A-Z]\w*)\.class\b",
        r"\bthrows\s+([A-Z]\w*)",
        r"(?<![.\w$])([A-Z]\w*)\s*\.\s*[a-zA-Z_]\w*\s*[(.,;)=]",
    ]
    # CONSTANTS are receivers too - `LOG.info(...)`, `CACHE.get(...)` - and they are fields, not
    # types, so the static-receiver pattern must not report them. Two filters, because either alone
    # leaks: the declaration sweep misses constants inherited from a superclass, and the naming
    # convention misses a lowercase-named field. A single capital is left checkable on purpose so
    # `R.string.x` is still verified; type PARAMETERS of that shape are already in `local`.
    declared_fields = set(re.findall(
        r"(?:static|final|private|public|protected|volatile|transient)\s+"
        r"[\w.<>\[\], ?]+?\s+([A-Za-z_]\w*)\s*(?:=|;)", src))

    def is_constant(name):
        return len(name) > 1 and name.upper() == name

    # A wildcard import of a package that is NOT in this source tree - android.widget.*,
    # com.google.gson.*, java.security.* - cannot be indexed, so every type it supplies reads as
    # unresolved. Reporting those is not a finding, it is the checker admitting it cannot see the
    # imports, and on 2026-08-05 it failed a CI build with nine such names in upstream files.
    #
    # So: a file with an unindexable wildcard is UNVERIFIABLE and reports nothing. The alternative
    # - listing every Android widget by hand - just moves the same blindness somewhere less
    # obvious. Fork files essentially never use wildcard imports, so this costs nothing where the
    # checker earns its keep, and main() prints the count so the skip is visible rather than silent.
    # Reported even for an unverifiable file: it does not depend on imports resolving.
    chars = bad_char_literals(raw, src)

    blind = [w for w in wildcards if not pkgs.get(w)]
    if blind:
        return {}, blind, chars

    hits = {}
    for i, line in enumerate(src.split("\n"), 1):
        for p in pats:
            for mm in re.finditer(p, line, re.M):
                name = mm.group(1)
                if name in available or name in hits:
                    continue
                if name in declared_fields or is_constant(name):
                    continue
                hits[name] = i
    return {n: l for n, l in hits.items()}, [], chars


def main():
    """Args are FILES, resolved against the repo root. There is no positional `base`.

    It used to be `cd-typecheck.py <base> <file>...`, which was a trap. Called the obvious way -
    `cd-typecheck.py A.java B.java` - it took A.java as the base directory, walked it (a file, so
    no packages), joined every remaining path onto it, found none of them, and printed a
    confident "0 file(s) with unresolved names" having checked NOTHING. That happened repeatedly
    on 2026-08-05 while two real compile errors sat untouched in the tree.

    Hence the two changes that matter here: the count of files actually checked is printed, and a
    path that does not exist is an error rather than a skip. A checker that cannot distinguish
    "clean" from "did not run" is worse than no checker, because it is trusted.
    """
    base = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    args = [a for a in sys.argv[1:] if not a.startswith("-")]
    pkgs = index_packages(base)

    if args:
        files = [os.path.abspath(a) for a in args]
    else:
        files = []
        for root in ROOTS:
            for dirpath, _dirnames, filenames in os.walk(os.path.join(base, root)):
                files.extend(os.path.join(dirpath, n) for n in filenames if n.endswith(".java"))
        files.sort()

    checked = 0
    bad = 0
    unverifiable = 0
    for p in files:
        if not p.endswith(".java"):
            continue
        if not os.path.exists(p):
            print("  ! no such file: %s" % p)
            bad += 1
            continue
        checked += 1
        hits, blind, chars = check(p, pkgs)
        if blind:
            unverifiable += 1
        if hits or chars:
            bad += 1
            print("\n%s" % os.path.relpath(p, base))
            for l, lit in sorted(chars.items()):
                print("    line %-5d not a char literal - javac rejects it: '%s'" % (l, lit))
            for n, l in sorted(hits.items(), key=lambda kv: kv[1]):
                print("    line %-5d unresolved: %s" % (l, n))
    print("\n%d file(s) checked, %d with unresolved names, %d unverifiable "
          "(wildcard import of a package outside this tree)" % (checked, bad, unverifiable))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
