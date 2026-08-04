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


def check(path, pkgs):
    raw = open(path, encoding="utf-8").read()
    src = strip(raw)

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

    # Type positions: `Foo bar =`, `new Foo(`, `Foo.class`, `extends Foo`, `implements Foo`,
    # `catch (Foo`, `instanceof Foo`, `(Foo)` casts, `<Foo>`.
    pats = [
        r"\bnew\s+([A-Z]\w*)\s*[(<\[]",
        r"^\s*(?:public|private|protected|static|final|abstract|synchronized|volatile|transient|\s)*\b([A-Z]\w*)(?:<[^;={]*>)?(?:\[\])?\s+\w+\s*[;=)]",
        r"\bextends\s+([A-Z]\w*)",
        r"\bimplements\s+([A-Z]\w*)",
        r"\bcatch\s*\(\s*(?:final\s+)?([A-Z]\w*)",
        r"\binstanceof\s+([A-Z]\w*)",
        r"\b([A-Z]\w*)\.class\b",
        r"\bthrows\s+([A-Z]\w*)",
    ]
    hits = {}
    for i, line in enumerate(src.split("\n"), 1):
        for p in pats:
            for mm in re.finditer(p, line, re.M):
                name = mm.group(1)
                if name in available or name in hits:
                    continue
                hits[name] = i
    return {n: l for n, l in hits.items()}


def main():
    base = sys.argv[1]
    files = sys.argv[2:]
    pkgs = index_packages(base)
    bad = 0
    for f in files:
        p = os.path.join(base, f)
        if not p.endswith(".java") or not os.path.exists(p):
            continue
        hits = check(p, pkgs)
        if hits:
            bad += 1
            print("\n%s" % f)
            for n, l in sorted(hits.items(), key=lambda kv: kv[1]):
                print("    line %-5d unresolved: %s" % (l, n))
    print("\n%d file(s) with unresolved names" % bad)


if __name__ == "__main__":
    main()
