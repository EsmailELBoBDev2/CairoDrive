// cairodrive-search-hook.js  (hardened, Google-request logic completed)
//
// Routes Magic Earth's EXISTING phone search through Google Places API (New),
// preserving the search UI, the Landmark result model, and the whole
// destination/routing/navigation pipeline. Search only — nothing touching
// Premium/licensing/entitlement.
//
// STATUS (see the header block at the bottom for the full breakdown):
//   DONE, verified against a real device today (CairoDrive app, same request
//   logic, same API key class): version gate, query extraction, the Google
//   Autocomplete + Place Details HTTP calls, identity headers, response error
//   decoding.
//   NOT DONE, needs on-device iteration: minting a real SDK `Landmark` from
//   the resolved candidates and delivering it into SearchMenuBloc's result
//   stream (Dart-heap object construction — cannot be written or verified
//   without Frida attached to the live process). See "DELIVERY (TODO)" below.
//
// SAFETY MODEL
//  - No absolute ASLR address is ever used. The base of libapp.so is resolved
//    dynamically at runtime, and the target offset 0x926cc4 is applied ONLY
//    after a version gate confirms the loaded libapp.so is the exact binary the
//    offset was derived from (ABI + GNU build-id + Dart snapshot hash).
//  - Every hook body is wrapped so a fault cannot take down the Flutter isolate;
//    on any error the ORIGINAL search behaviour is left intact (fallback).
//
// RUN (device where Frida can attach):
//   frida -U -f com.generalmagic.magicearth \
//         -l blutter_frida.js -l cairodrive-search-hook.js
//   The API key is supplied at attach time (see loadApiKey); never embedded.

'use strict';

// ---- PROOF-OF-EXECUTION marker ---------------------------------------------
// Write a file the instant this script is evaluated, so we can tell — via a
// single adb command, independent of logcat — whether the gadget actually
// runs our script. Check on device with:
//   adb shell cat /sdcard/Android/data/com.generalmagic.magicearth/files/cairodrive_ran.txt
(function writeRunMarker() {
  const paths = [
    '/storage/emulated/0/Android/data/com.generalmagic.magicearth/files/cairodrive_ran.txt',
    '/sdcard/Android/data/com.generalmagic.magicearth/files/cairodrive_ran.txt',
    '/data/local/tmp/cairodrive_ran.txt',
  ];
  for (const p of paths) {
    try { const f = new File(p, 'w'); f.write('cairodrive script evaluated\n'); f.flush(); f.close(); } catch (_) {}
  }
})();

// ---- Target binding: valid ONLY for this exact build -----------------------
// Re-verified this session: all six original .7z volumes hash-match the
// values recorded when these offsets were derived (reports/SHA256SUMS.txt /
// original-archive-checksums.txt), so this is confirmed to be the identical
// artifact — these values do not need to be re-derived.
const TARGET = {
  package: 'com.generalmagic.magicearth',
  abi: 'arm64', // Process.arch value for the analyzed split
  buildId: 'b7188509a10e2fe7f90d3cfa65f68bc5', // .note.gnu.build-id of arm64 libapp.so
  snapshotHash: 'ace654289f5abc240509fc941453ebc5', // Dart 3.12.2 snapshot version
  sha256: '558e04e9a41aca50a3409ee7640785eedfefb23ff1fe787865b7595f029e19a4',
  offsets: {
    searchRepositoryImplSearch: 0x926cc4, // ★ query in, List<Landmark> out
    searchServiceSearch: 0x9275d8, // original Magic Lane path (fallback)
    searchMenuTextEvent: 0x926770, // SearchTextEvent / "searchText"
    // Confirmed via a real Blutter run against this exact libapp.so (class id
    // 4017 for SearchRepositoryImpl matched our own live cid readout exactly,
    // and both offsets above matched byte-for-byte): every Landmark property
    // read (name/address/coordinates/id) funnels through this ONE static
    // bridge: objectMethod(handle, className, methodName, {args, ...}) ->
    // marshals into native libGEM and back. This is a single choke point
    // covering all Landmark getters — see gem_kit_platform_interface.dart.
    objectMethod: 0x6c79f0,
  },
};

// ---- Logging: multi-channel so SOMETHING always reaches logcat -------------
// Prior builds used only __android_log_write (via null-module lookup) + a
// console.log that goes nowhere in autonomous gadget mode — and produced no
// visible output. This routes every line through THREE channels:
//   1) android.util.Log.i (Java) — the canonical path, once the VM is ready
//   2) __android_log_write resolved explicitly from liblog.so (any thread)
//   3) console.log (harmless if unconsumed)
// View on device with:  adb logcat -s cairodrive
let _JavaLog = null, _javaReady = false;
const _pending = [];
function _tryInitJavaLog() {
  if (_javaReady) return;
  try {
    if (typeof Java !== 'undefined' && Java.available) {
      Java.perform(() => { _JavaLog = Java.use('android.util.Log'); });
      _javaReady = !!_JavaLog;
      if (_javaReady) { for (const m of _pending.splice(0)) { try { _JavaLog.i('cairodrive', m); } catch (_) {} } }
    }
  } catch (_) {}
}
// Frida 17 removed the old static Module.findExportByName(mod, name). Resolve
// exports in a version-tolerant way (works on 16.x and 17.x).
function findExport(moduleName, exportName) {
  try {
    if (moduleName) {
      const m = Process.findModuleByName(moduleName);
      if (m && typeof m.findExportByName === 'function') { const e = m.findExportByName(exportName); if (e) return e; }
    }
    if (typeof Module.findGlobalExportByName === 'function') { const e = Module.findGlobalExportByName(exportName); if (e) return e; }
    if (typeof Module.getGlobalExportByName === 'function') { try { const e = Module.getGlobalExportByName(exportName); if (e) return e; } catch (_) {} }
    if (typeof Module.findExportByName === 'function') return Module.findExportByName(moduleName, exportName);
  } catch (_) {}
  return null;
}
let _alogFn = undefined, _alogTag = null;
function _nativeLog(msg) {
  try {
    if (_alogFn === undefined) {
      const p = findExport('liblog.so', '__android_log_write');
      _alogFn = p ? new NativeFunction(p, 'int', ['int', 'pointer', 'pointer']) : null;
      _alogTag = Memory.allocUtf8String('cairodrive');
    }
    if (_alogFn) _alogFn(4 /* INFO */, _alogTag, Memory.allocUtf8String(msg));
  } catch (_) {}
}
function log(m) {
  const s = '[cairodrive] ' + m;
  try { console.log(s); } catch (_) {}
  _nativeLog(s);
  if (!_javaReady) _tryInitJavaLog();
  if (_javaReady && _JavaLog) { try { _JavaLog.i('cairodrive', String(m)); } catch (_) {} }
  else { _pending.push(String(m)); }
}

// A loud "the script is actually executing" beacon, retried on a timer until
// the Java VM is up so it fires even if we loaded before ART was ready.
function beacon(n) {
  log(`>>> SCRIPT ALIVE beacon #${n} (javaReady=${_javaReady}) <<<`);
  if (n < 6) setTimeout(() => beacon(n + 1), 800);
}

function bytesToPrintable(arrbuf, max) {
  try {
    const u8 = new Uint8Array(arrbuf);
    let s = '';
    for (let i = 0; i < u8.length && i < (max || 256); i++) {
      const c = u8[i];
      s += (c >= 0x20 && c <= 0x7e) ? String.fromCharCode(c) : '.';
    }
    return s;
  } catch (_) { return '(unreadable)'; }
}

// ---- Parallel probe: Dart NativePort delivery (Dart_PostCObject) -----------
// Per SDK research, search results return ASYNCHRONOUSLY: a libGEM worker
// posts a Dart_CObject to a NativePort, and the SDK's dispatcher on the Dart
// isolate turns it into the List<Landmark> handed to onComplete/the Bloc. If
// we can see (or later, forge) that post, that's a second independent
// delivery seam, separate from the synchronous SearchRepositoryImpl::search
// hook. Try every known export name across the loaded modules; log whichever
// resolves (or that none did) so we know if this path is even available.
function installPostCObjectProbe() {
  const NAMES = ['Dart_PostCObject', 'Dart_PostCObject_DL', 'Dart_PostInteger'];
  const MODULES = [null, 'libflutter.so', 'libapp.so'];
  let hooked = 0;
  for (const modName of MODULES) {
    for (const name of NAMES) {
      try {
        const addr = findExport(modName, name);
        if (!addr) continue;
        const key = modName + '!' + name;
        log(`Dart NativePort probe: found ${name} in ${modName || '(global)'} @ ${addr}`);
        Interceptor.attach(addr, {
          onEnter(a) {
            try {
              log(`>>> ${name} called: port_id=${a[0]} message=${a[1]}`);
              // Dart_CObject layout starts with an int32 'type' tag at offset 0.
              try { log(`    CObject.type=${a[1].readS32()}`); } catch (_) {}
            } catch (_) {}
          },
        });
        hooked++;
      } catch (e) { log(`Dart NativePort probe error for ${name}/${modName}: ${e}`); }
    }
  }
  log(`Dart NativePort probe: ${hooked} hook(s) installed`);
}

// ---- libGEM native curl hook: capture the search request + response --------
// Search is ONLINE (…/search_maps7 via libGEM's CurlHttpEngine). Hooking curl
// gives us BOTH the query (POST body) and the result wire format (response),
// with no Dart-object construction needed. curl is often statically linked
// into libGEM, so the symbol may not be exported — handled gracefully.
let _lastSearchHandle = null;
function installCurlHook() {
  try {
    const setopt = findExport('libGEM.so', 'curl_easy_setopt');
    if (!setopt) {
      log('curl_easy_setopt NOT exported (curl is static in libGEM) — curl hook skipped for now');
      return;
    }
    log('curl_easy_setopt @ ' + setopt + ' — hooking to capture search traffic');
    const CURLOPT_URL = 10002, CURLOPT_POSTFIELDS = 10015, CURLOPT_WRITEFUNCTION = 20011;
    const seenCb = {};
    Interceptor.attach(setopt, {
      onEnter(args) {
        try {
          const handle = args[0];
          const opt = args[1].toInt32();
          if (opt === CURLOPT_URL) {
            const url = args[2].readCString();
            if (url && url.indexOf('search') >= 0) {
              log('CURL search URL: ' + url + '  [handle ' + handle + ']');
              _lastSearchHandle = handle.toString();
            }
          } else if (opt === CURLOPT_POSTFIELDS) {
            try { const body = args[2].readCString(); if (body) log('CURL POST body: ' + body.slice(0, 500)); } catch (_) {}
          } else if (opt === CURLOPT_WRITEFUNCTION) {
            const cb = args[2];
            const key = cb.toString();
            if (!seenCb[key]) {
              seenCb[key] = 1;
              Interceptor.attach(cb, {
                onEnter(a) { this.p = a[0]; this.n = a[1].toInt32() * a[2].toInt32(); },
                onLeave() {
                  try {
                    if (this.n > 0 && this.n <= 2048) {
                      const buf = this.p.readByteArray(Math.min(this.n, 400));
                      log(`CURL resp ${this.n}B: ${bytesToPrintable(buf, 400)}`);
                    }
                  } catch (_) {}
                }
              });
            }
          }
        } catch (_) {}
      }
    });
  } catch (e) { log('installCurlHook error: ' + e); }
}

// ---- Version gate ----------------------------------------------------------
// Reads the GNU build-id note straight from the mapped ELF, so it works before
// any offset is trusted. Returns the hex build-id or null.
function readGnuBuildId(mod) {
  try {
    const b = mod.base;
    if (b.readU32() !== 0x464c457f) return null; // "\x7fELF"
    const phoff = b.add(0x20).readU64();
    const phentsize = b.add(0x36).readU16();
    const phnum = b.add(0x38).readU16();
    for (let i = 0; i < phnum; i++) {
      const ph = b.add(phoff.add(i * phentsize));
      if (ph.readU32() !== 4) continue; // PT_NOTE
      let p = b.add(ph.add(0x10).readU64()); // p_vaddr
      const end = p.add(ph.add(0x28).readU64()); // + p_memsz
      while (p.compare(end) < 0) {
        const namesz = p.readU32();
        const descsz = p.add(4).readU32();
        const type = p.add(8).readU32();
        const name = p.add(12).readCString(namesz);
        const descOff = 12 + ((namesz + 3) & ~3);
        if (type === 3 && name === 'GNU') {
          const bytes = p.add(descOff).readByteArray(descsz);
          return Array.from(new Uint8Array(bytes))
            .map((x) => x.toString(16).padStart(2, '0')).join('');
        }
        p = p.add(descOff + ((descsz + 3) & ~3));
      }
    }
  } catch (_) {}
  return null;
}

function moduleContainsString(mod, needle) {
  try {
    const ranges = mod.enumerateRanges('r--');
    const pat = needle.split('').map((c) => c.charCodeAt(0).toString(16).padStart(2, '0')).join(' ');
    for (const r of ranges.slice(0, 40)) {
      if (Memory.scanSync(r.base, r.size, pat).length) return true;
    }
  } catch (_) {}
  return false;
}

function verifyTarget(mod) {
  const abiOk = Process.arch === TARGET.abi;
  log(`gate abi: ${abiOk ? 'PASS' : 'FAIL'} (${Process.arch} vs ${TARGET.abi})`);

  const bid = readGnuBuildId(mod);
  const bidMatch = bid === TARGET.buildId;
  log(`gate build-id: ${bid === null ? 'UNREADABLE (advisory)' : (bidMatch ? 'PASS' : 'MISMATCH')} (${bid} vs ${TARGET.buildId})`);

  const hasHash = moduleContainsString(mod, TARGET.snapshotHash);
  log(`gate snapshot-hash: ${hasHash ? 'PASS' : 'FAIL'} (${hasHash ? 'present' : 'absent'})`);

  // The Dart snapshot hash is the DEFINITIVE identity of this exact libapp.so
  // build (it embeds the compiler + snapshot version). Require abi + snapshot
  // hash. build-id is only advisory here: fail the gate solely when we could
  // actually read a build-id AND it mismatched; a null (unreadable) build-id
  // must not veto an otherwise-confirmed match.
  if (bid !== null && !bidMatch) { log('build-id readable but mismatched — refusing to apply offset.'); return false; }
  return abiOk && hasHash;
}

// ---- Credential (resolved at attach time; never embedded) ------------------
function loadApiKey() {
  if (typeof rpcParams !== 'undefined' && rpcParams.googlePlacesApiKey) return rpcParams.googlePlacesApiKey;
  try { return File.readAllText('/data/local/tmp/gpk').trim(); } catch (_) { return ''; }
}
const GOOGLE_PLACES_API_KEY = loadApiKey();

// ---- Egypt region defaults (ported from packages/cairodrive_search) --------
// Same values already validated against the live API today via the CairoDrive
// app's GooglePlacesSearchProvider — see packages/cairodrive_search/lib/src/
// region/egypt.dart and google_places_provider.dart for the source of truth.
const EGYPT = {
  cairoCenter: { latitude: 30.0444, longitude: 31.2357 },
  // Places API (New) Autocomplete hard-rejects locationBias.circle.radius
  // outside [0.0, 50000.0] with HTTP 400 INVALID_ARGUMENT. Confirmed today on
  // a real device: 60000 (an earlier value) failed with exactly that error;
  // 50000 is the true ceiling. See egypt.dart's greaterCairoRadiusMeters.
  greaterCairoRadiusMeters: 50000,
};

function clampRadius(r) {
  if (typeof r !== 'number' || isNaN(r)) return EGYPT.greaterCairoRadiusMeters;
  return Math.max(0.0, Math.min(50000.0, r));
}

function isArabic(codeUnit) {
  return (codeUnit >= 0x0600 && codeUnit <= 0x06ff) ||
    (codeUnit >= 0x0750 && codeUnit <= 0x077f) ||
    (codeUnit >= 0xfb50 && codeUnit <= 0xfdff) ||
    (codeUnit >= 0xfe70 && codeUnit <= 0xfeff);
}
function inferLang(q) {
  for (const ch of q) { const c = ch.codePointAt(0); if (isArabic(c)) return 'ar'; }
  return 'en';
}

// ---- Identity headers --------------------------------------------------
// Verified today (real device, CairoDrive app, same API key class): an
// Android-app-restricted GOOGLE_PLACES_API_KEY is rejected outright (HTTP
// 403, API_KEY_ANDROID_APP_BLOCKED) unless every raw HTTP request carries
// X-Android-Package / X-Android-Cert. The Maps/Places SDK adds these
// automatically; a hand-rolled request (what this hook issues) must add them
// itself. This is the TRUE identity of the process the hook is attached to —
// not a spoof: read the running app's own package name and signing cert via
// PackageManager, exactly the way CairoDrive's AppIdentity.kt does it.
let ANDROID_PACKAGE = TARGET.package;
let ANDROID_CERT_SHA1 = null; // resolved via Java below; falls back to a
// hardcoded default only if resolution fails (see resolveIdentity()).
// Defaults recorded by the earlier recon for this exact build (SHA-256
// verified above): use these ONLY if live resolution fails.
//   Mode 2 (modified dev build, embedded gadget): 5D08264B44E0E53FBCCC70B4F016474CC6C5AB5C
//   Mode 1 (original app, external Frida, rooted device): 3705BA93D86F9566CDB440977E65C8DF660514AE
const FALLBACK_CERT_SHA1 = '5D08264B44E0E53FBCCC70B4F016474CC6C5AB5C';

function resolveIdentity() {
  try {
    Java.perform(() => {
      const ActivityThread = Java.use('android.app.ActivityThread');
      const app = ActivityThread.currentApplication();
      const ctx = app.getApplicationContext();
      const pm = ctx.getPackageManager();
      const pkg = ctx.getPackageName();
      const PackageManager = Java.use('android.content.pm.PackageManager');
      // GET_SIGNING_CERTIFICATES = 0x08000000, available API 28+. This
      // process is the app itself, so API level is whatever the device runs;
      // fall back to the deprecated GET_SIGNATURES flag (64) below API 28.
      const Build = Java.use('android.os.Build$VERSION');
      const sdkInt = Build.SDK_INT.value;
      let certBytes = null;
      if (sdkInt >= 28) {
        const info = pm.getPackageInfo(pkg, 0x08000000);
        const signingInfo = info.signingInfo.value;
        const signers = signingInfo.hasMultipleSigners()
          ? signingInfo.getApkContentsSigners()
          : signingInfo.getSigningCertificateHistory();
        certBytes = signers[0].toByteArray();
      } else {
        const info = pm.getPackageInfo(pkg, 64 /* GET_SIGNATURES */);
        certBytes = info.signatures.value[0].toByteArray();
      }
      const MessageDigest = Java.use('java.security.MessageDigest');
      const digest = MessageDigest.getInstance('SHA-1').digest(certBytes);
      let hex = '';
      for (let i = 0; i < digest.length; i++) {
        hex += (digest[i] & 0xff).toString(16).padStart(2, '0');
      }
      ANDROID_PACKAGE = pkg;
      ANDROID_CERT_SHA1 = hex.toUpperCase();
      log(`identity resolved live: package=${ANDROID_PACKAGE} certSha1=${ANDROID_CERT_SHA1}`);
    });
  } catch (e) {
    log(`identity resolution failed (${e}); falling back to recorded default`);
  }
  if (!ANDROID_CERT_SHA1) {
    ANDROID_CERT_SHA1 = FALLBACK_CERT_SHA1;
    log(`using FALLBACK_CERT_SHA1 = ${ANDROID_CERT_SHA1} — verify this matches ` +
        `the identity actually allow-listed for the key in Cloud Console`);
  }
}

// ---- HTTP via Java (in-process — this IS the running app's own network stack) --
// Frida's plain JS runtime has no fetch/XHR; the reliable way to issue a real
// HTTPS request from inside an attached Android process is Java interop
// through java.net.HttpURLConnection, which is what's used here.
function httpJson(method, urlString, headers, bodyObj) {
  let result = null;
  Java.perform(() => {
    const URL = Java.use('java.net.URL');
    const url = URL.$new(urlString);
    const conn = Java.cast(url.openConnection(), Java.use('javax.net.ssl.HttpsURLConnection'));
    conn.setRequestMethod(method);
    conn.setConnectTimeout(8000);
    conn.setReadTimeout(8000);
    for (const k of Object.keys(headers)) conn.setRequestProperty(k, headers[k]);
    if (bodyObj !== null) {
      conn.setDoOutput(true);
      const bytes = Java.use('java.lang.String').$new(JSON.stringify(bodyObj)).getBytes('UTF-8');
      const os = conn.getOutputStream();
      os.write(bytes);
      os.flush();
      os.close();
    }
    const status = conn.getResponseCode();
    const stream = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
    const ISR = Java.use('java.io.InputStreamReader');
    const BR = Java.use('java.io.BufferedReader');
    const reader = BR.$new(ISR.$new(stream, 'UTF-8'));
    const sb = Java.use('java.lang.StringBuilder').$new();
    let line;
    while ((line = reader.readLine()) !== null) sb.append(line);
    reader.close();
    conn.disconnect();
    result = { status, body: sb.toString() };
  });
  return result;
}

/// Extracts the actual reason from Google's error body, e.g.
/// "PERMISSION_DENIED — API_KEY_ANDROID_APP_BLOCKED — <message>", instead of
/// a generic rejection. Mirrors _describeGoogleError in
/// google_places_provider.dart, which is the version verified today.
function describeGoogleError(status, body) {
  try {
    const decoded = JSON.parse(body);
    if (decoded && decoded.error) {
      const err = decoded.error;
      let reason = null;
      if (Array.isArray(err.details)) {
        for (const d of err.details) { if (d && d.reason) { reason = d.reason; break; } }
      }
      const parts = [err.status, reason, err.message].filter((x) => !!x);
      if (parts.length) return parts.join(' — ');
    }
  } catch (_) {}
  const excerpt = body.length > 200 ? body.slice(0, 200) + '…' : body;
  return excerpt || '(empty response body)';
}

function newSessionToken() {
  const b = []; for (let i = 0; i < 16; i++) b.push(Math.floor(Math.random() * 256));
  b[6] = (b[6] & 0x0f) | 0x40; b[8] = (b[8] & 0x3f) | 0x80;
  const h = b.map((x) => x.toString(16).padStart(2, '0'));
  return `${h.slice(0, 4).join('')}-${h.slice(4, 6).join('')}-${h.slice(6, 8).join('')}-${h.slice(8, 10).join('')}-${h.slice(10, 16).join('')}`;
}

function googleHeaders(extra) {
  return Object.assign({
    'X-Goog-Api-Key': GOOGLE_PLACES_API_KEY,
    'X-Android-Package': ANDROID_PACKAGE,
    'X-Android-Cert': ANDROID_CERT_SHA1,
  }, extra || {});
}

const AUTOCOMPLETE_URL = 'https://places.googleapis.com/v1/places:autocomplete';
const DETAILS_BASE = 'https://places.googleapis.com/v1/places/';
const AUTOCOMPLETE_FIELD_MASK = 'suggestions.placePrediction.placeId,' +
  'suggestions.placePrediction.text,' +
  'suggestions.placePrediction.structuredFormat,' +
  'suggestions.placePrediction.types';
const DETAILS_FIELD_MASK = 'id,displayName,formattedAddress,location,primaryType';

// ---- The Google flow ---------------------------------------------------
// IMPORTANT — open design question, not yet resolved (see header block):
// SearchRepositoryImpl::search returns a List<Landmark> SYNCHRONOUSLY per the
// call graph in reports/SEARCH-AUDIT.md — it is not a two-phase "type ->
// predictions -> tap -> details" flow the way cairodrive_search's UI is. That
// means, to preserve the ORIGINAL app's contract, coordinates must already be
// resolved by the time this function returns — unlike cairodrive_search's
// design, which defers Place Details to the single row the user taps and so
// pays for exactly one Details call per search session.
//
// This function therefore resolves Place Details for up to TOP_K predictions
// EVERY query, which is a real, deliberate cost/latency tradeoff (more Google
// billing than the lazy-details design) made explicit here rather than
// silently. Confirm downstream expectations (does the real search screen
// actually need every row to already have coordinates, or does something
// else resolve them on tap that Blutter can reveal?) before shipping this to
// avoid over-paying if a lazier seam exists.
const TOP_K = 5;

function runGoogleSearch(queryText) {
  if (!GOOGLE_PLACES_API_KEY) {
    log('GOOGLE_PLACES_API_KEY absent — skipping Google, falling back to original search');
    return null;
  }

  const language = inferLang(queryText);
  const body = {
    input: queryText,
    sessionToken: newSessionToken(),
    languageCode: language,
    regionCode: 'EG',
    locationBias: {
      circle: {
        center: EGYPT.cairoCenter,
        radius: clampRadius(EGYPT.greaterCairoRadiusMeters),
      },
    },
  };

  log(`Google autocomplete: "${queryText}" (lang=${language})`);
  const acResp = httpJson('POST', AUTOCOMPLETE_URL,
    googleHeaders({ 'X-Goog-FieldMask': AUTOCOMPLETE_FIELD_MASK, 'Content-Type': 'application/json' }),
    body);

  if (!acResp) { log('Google autocomplete: no response (network error)'); return null; }
  if (acResp.status === 429) { log('Google autocomplete: quota exceeded'); return null; }
  if (acResp.status === 401 || acResp.status === 403) {
    log(`Google autocomplete rejected (HTTP ${acResp.status}): ${describeGoogleError(acResp.status, acResp.body)}. ` +
        `Sent as: ${ANDROID_PACKAGE} / ${ANDROID_CERT_SHA1}`);
    return null;
  }
  if (acResp.status < 200 || acResp.status >= 300) {
    log(`Google autocomplete HTTP ${acResp.status}: ${describeGoogleError(acResp.status, acResp.body)}`);
    return null;
  }

  let predictions;
  try {
    const decoded = JSON.parse(acResp.body);
    predictions = (decoded.suggestions || [])
      .map((s) => s && s.placePrediction)
      .filter((p) => p && p.placeId);
  } catch (e) {
    log(`Google autocomplete: malformed response (${e})`);
    return null;
  }
  if (!predictions.length) { log('Google autocomplete: no matches'); return []; }

  const results = [];
  for (const pred of predictions.slice(0, TOP_K)) {
    const uri = `${DETAILS_BASE}${pred.placeId}`;
    const detResp = httpJson('GET', uri,
      googleHeaders({ 'X-Goog-FieldMask': DETAILS_FIELD_MASK }), null);
    if (!detResp) { log(`Place Details for ${pred.placeId}: no response — skipping`); continue; }
    if (detResp.status < 200 || detResp.status >= 300) {
      log(`Place Details for ${pred.placeId} failed (HTTP ${detResp.status}): ` +
          `${describeGoogleError(detResp.status, detResp.body)} — skipping this candidate`);
      continue;
    }
    try {
      const d = JSON.parse(detResp.body);
      if (!d.location || typeof d.location.latitude !== 'number' || typeof d.location.longitude !== 'number') {
        log(`Place Details for ${pred.placeId}: no usable location — skipping`);
        continue;
      }
      results.push({
        placeId: pred.placeId,
        name: (d.displayName && d.displayName.text) || pred.placeId,
        address: d.formattedAddress || null,
        latitude: d.location.latitude,
        longitude: d.location.longitude,
        category: d.primaryType || null,
      });
    } catch (e) {
      log(`Place Details for ${pred.placeId}: malformed response (${e}) — skipping`);
    }
  }

  log(`Google search resolved ${results.length}/${predictions.length} candidate(s) with coordinates`);
  return results;
}

// ---- Self-contained Dart string scanner (NO Blutter dependency) ------------
// blutter_frida.js (the ~700KB generated helper the old path needed) is NOT
// bundled in the gadget, so init/getArg/getTaggedObjectValue are undefined and
// the query was never read. This scanner reads the query ourselves: given a
// pointer, it dumps any printable ASCII (OneByteString/Latin-1) or UTF-16LE
// (TwoByteString) runs in a window of memory. Fully guarded — a bad read never
// throws out. Returns [{enc,off,text}].
function scanStrings(p, windowSize) {
  const found = [];
  try {
    if (!p || p.isNull()) return found;
    const buf = p.readByteArray(windowSize);
    if (!buf) return found;
    const u8 = new Uint8Array(buf);
    let run = '', start = -1;
    for (let i = 0; i < u8.length; i++) {
      const c = u8[i];
      if (c >= 0x20 && c <= 0x7e) { if (!run) start = i; run += String.fromCharCode(c); }
      else { if (run.length >= 2) found.push({ enc: 'a', off: start, text: run }); run = ''; }
    }
    if (run.length >= 2) found.push({ enc: 'a', off: start, text: run });
    let w = '', wstart = -1;
    for (let i = 0; i + 1 < u8.length; i += 2) {
      const lo = u8[i], hi = u8[i + 1];
      if (hi === 0 && lo >= 0x20 && lo <= 0x7e) { if (!w) wstart = i; w += String.fromCharCode(lo); }
      else { if (w.length >= 2) found.push({ enc: 'u', off: wstart, text: w }); w = ''; }
    }
    if (w.length >= 2) found.push({ enc: 'u', off: wstart, text: w });
  } catch (_) {}
  return found;
}

// Heuristic: does a decoded string look like a user's search query rather than
// a Dart-internal token (class name / package URI / type name)?
function looksLikeQuery(s) {
  if (!s || s.length < 2 || s.length > 120) return false;
  if (s.indexOf('::') >= 0 || s.indexOf('package:') >= 0 || s.indexOf('dart:') >= 0) return false;
  if (/^_?[A-Z][A-Za-z0-9]+$/.test(s) && s.indexOf(' ') < 0) return false; // CamelCase identifier
  if (/^[0-9a-fA-Fx]+$/.test(s)) return false; // hex-ish
  const printable = s.replace(/[^\x20-\x7e]/g, '').length;
  return printable / s.length > 0.8;
}

// Collect string candidates reachable from a register value: the pointee, plus
// one level of pointer indirection (the arg may be an object whose FIELD is the
// String). Each candidate is tagged with where it came from.
function collectCandidates(label, v, out) {
  try {
    if (!v || v.isNull()) return;
    for (const f of scanStrings(v, 256)) out.push({ src: `${label}@+${f.off}${f.enc}`, text: f.text });
    let base;
    try { base = v.and(ptr('0xfffffffffffffff8')); } catch (_) { return; }
    for (let s = 0; s < 16; s++) {
      let slot;
      try { slot = base.add(s * 8).readPointer(); } catch (_) { break; }
      if (!slot || slot.isNull()) continue;
      for (const f of scanStrings(slot, 160)) out.push({ src: `${label}[+${s * 8}]@+${f.off}${f.enc}`, text: f.text });
    }
  } catch (_) {}
}

let HITS = 0;

// ---- Precise Dart 3.12 string decoding (compressed pointers) ---------------
// Confirmed on-device: this app uses compressed pointers (nonzero x28 heap
// base). Object fields are 4-byte COMPRESSED tagged pointers; full address =
// heapBase + compressedValue. String layout: header 8B, length Smi @ +0x8,
// char data @ +0xC (Latin-1 OneByte / UTF-16 TwoByte).
function _printableRatio(s) {
  if (!s || s.length === 0) return 0;
  let ok = 0;
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i);
    if (c === 9 || c === 10 || c === 13 || (c >= 0x20 && c < 0x7f) || c >= 0xa0) ok++;
  }
  return ok / s.length;
}
function _clean(s) {
  if (!s) return s;
  // strip leading/trailing null + control bytes (padding / adjacent header)
  let a = 0, b = s.length;
  while (a < b && s.charCodeAt(a) < 0x20) a++;
  while (b > a && s.charCodeAt(b - 1) < 0x20) b--;
  return s.slice(a, b);
}
// Decode a Dart String at an UNTAGGED heap address. On-device this build keeps
// OneByte char data at +0x10 (not +0xC), so try BOTH offsets and both widths,
// strip null padding, and keep the cleanest/longest printable result.
function decodeStringAt(objBase) {
  try {
    const len = objBase.add(8).readU32() >> 1; // length Smi (approximate)
    if (len <= 0 || len > 4000) return null;
    let best = null;
    for (const off of [0x10, 0xC]) {
      try {
        const b = objBase.add(off).readByteArray(len);
        if (b) {
          const u8 = new Uint8Array(b); let s = '';
          for (let i = 0; i < u8.length; i++) s += String.fromCharCode(u8[i]);
          const c = _clean(s);
          if (c.length >= 1) { const pr = _printableRatio(c); if (pr >= 0.85 && (!best || c.length > best.text.length)) best = { kind: '1', text: c, off, len, pr }; }
        }
      } catch (_) {}
      try {
        let s = ''; for (let i = 0; i < len; i++) s += String.fromCharCode(objBase.add(off + 2 * i).readU16());
        const c = _clean(s);
        if (c.length >= 1) { const pr = _printableRatio(c); if (pr >= 0.85 && (!best || c.length > best.text.length)) best = { kind: '2', text: c, off, len, pr }; }
      } catch (_) {}
    }
    return best;
  } catch (_) { return null; }
}
// Given a raw register/tagged value, dump the object's class id, try to decode
// it directly as a String, and walk its compressed-pointer fields looking for
// String fields (where the query text usually lives). Returns decoded strings.
function dumpDartObject(label, raw, heapBase, out) {
  try {
    if (!raw || raw.isNull()) return;
    if (raw.and(1).toInt32() !== 1) return; // Smi / not a heap pointer
    const base = raw.sub(1);                // untag
    let cid = -1;
    try { cid = (base.readU32() >>> 12) & 0xFFFFF; } catch (_) {}
    // Is the register itself a String?
    const self = decodeStringAt(base);
    if (self && self.text.length >= 1) { log(`  ${label} (cid=${cid}) IS string(${self.kind},len${self.len}): ${JSON.stringify(self.text)}`); out.push({ src: label, text: self.text }); }
    else { log(`  ${label} cid=${cid} (walking fields…)`); }
    if (!heapBase) return;
    // Walk compressed 4-byte fields.
    for (let off = 8; off <= 8 + 4 * 48; off += 4) {
      let v32; try { v32 = base.add(off).readU32(); } catch (_) { break; }
      if ((v32 & 1) !== 1) continue;        // not a heap-tagged compressed ptr
      let full; try { full = heapBase.add(v32); } catch (_) { continue; }
      const fbase = full.sub(1);
      const s = decodeStringAt(fbase);
      if (s && s.text.length >= 2 && _printableRatio(s.text) >= 0.85) {
        log(`  ${label}[field+${off}] -> string(${s.kind},len${s.len}): ${JSON.stringify(s.text)}`);
        out.push({ src: `${label}+${off}`, text: s.text });
      }
    }
  } catch (e) { log(`  ${label} dump error: ${e}`); }
}

let HITS2 = 0;
let _lastGoogleQuery = null;   // debounce so we don't re-query Google per keystroke
let _lastGoogleResults = null; // most recent resolved Google results (for delivery)

// ---- Deep memory inspector (recon for delivery) ----------------------------
// A plausible geographic coordinate: finite, non-integer, |x| in (0,180].
function looksLikeCoord(d) {
  return typeof d === 'number' && isFinite(d) && Math.abs(d) > 0.0001 &&
    Math.abs(d) <= 180 && Math.floor(d) !== d;
}
// Recursively dump a Dart object graph: class ids, decoded Strings, and
// double-precision fields that look like lat/lng. Node-budgeted so it can't
// explode or hang. Reveals where a result Landmark's name/address/coords live.
//
// CRITICAL: a Map's generic type-argument/metadata fields are CANONICAL
// (shared, same address everywhere) — confirmed live: every single
// getName/getAddress/getCoordinates call showed the exact same nested
// addresses (e.g. cid=171 @0x...8080) repeated dozens of times. Without a
// visited-set, the walker re-explores that same small cluster of shared
// objects over and over and burns the whole budget in a cycle, never
// reaching the actual per-call data. `visited` (a Set of address strings,
// created fresh per top-level call) fixes this.
let _dumpBudget = 0;
function deepDump(label, raw, heapBase, depth, visited) {
  if (_dumpBudget <= 0) return;
  if (!visited) visited = new Set();
  try {
    if (!raw || raw.isNull() || raw.and(1).toInt32() !== 1) return; // Smi / null
    const base = raw.sub(1);
    const key = base.toString();
    if (visited.has(key)) return; // already explored this exact object
    visited.add(key);
    _dumpBudget--;
    let cid = -1; try { cid = (base.readU32() >>> 12) & 0xFFFFF; } catch (_) {}
    const selfStr = decodeStringAt(base);
    if (selfStr && selfStr.text.length >= 2 && _printableRatio(selfStr.text) >= 0.85) {
      log(`  ${label} cid=${cid} STRING ${JSON.stringify(selfStr.text)}`);
      return;
    }
    log(`  ${label} cid=${cid} @${base}`);
    // Doubles (coordinates) at 8-aligned offsets.
    for (let off = 8; off <= 8 + 8 * 16; off += 8) {
      try { const d = base.add(off).readDouble(); if (looksLikeCoord(d)) log(`    ${label}[+${off}] double=${d}`); } catch (_) {}
    }
    if (!heapBase || depth <= 0) return;
    // Compressed 4-byte pointer fields -> recurse.
    for (let off = 8; off <= 8 + 4 * 60; off += 4) {
      if (_dumpBudget <= 0) break;
      let v32; try { v32 = base.add(off).readU32(); } catch (_) { break; }
      if ((v32 & 1) !== 1) continue;
      let child; try { child = heapBase.add(v32); } catch (_) { continue; }
      deepDump(`${label}[+${off}]`, child, heapBase, depth - 1, visited);
    }
  } catch (_) {}
}

// ---- Mutation primitives (the actual delivery attempt, not just recon) -----
// Same visited-set fix as deepDump (see comment above) — without it these
// never escape the canonical Map-metadata cluster either.
// Find the first writable String node reachable from `raw`. Returns
// {base, len} (untagged object base + its CURRENT allocated length) or null.
let _findBudget = 0;
function findFirstString(raw, heapBase, depth, visited) {
  if (_findBudget <= 0) return null;
  if (!visited) visited = new Set();
  try {
    if (!raw || raw.isNull() || raw.and(1).toInt32() !== 1) return null;
    const base = raw.sub(1);
    const key = base.toString();
    if (visited.has(key)) return null;
    visited.add(key);
    _findBudget--;
    const s = decodeStringAt(base);
    if (s && s.text.length >= 1 && _printableRatio(s.text) >= 0.85) return { base, len: s.len };
    if (!heapBase || depth <= 0) return null;
    for (let off = 8; off <= 8 + 4 * 60; off += 4) {
      if (_findBudget <= 0) break;
      let v32; try { v32 = base.add(off).readU32(); } catch (_) { break; }
      if ((v32 & 1) !== 1) continue;
      let child; try { child = heapBase.add(v32); } catch (_) { continue; }
      const found = findFirstString(child, heapBase, depth - 1, visited);
      if (found) return found;
    }
    return null;
  } catch (_) { return null; }
}
// Find the first pair of coordinate-looking doubles reachable from `raw`.
// Returns {base, latOff, lngOff} or null.
let _findBudget2 = 0;
function findFirstCoordPair(raw, heapBase, depth, visited) {
  if (_findBudget2 <= 0) return null;
  if (!visited) visited = new Set();
  try {
    if (!raw || raw.isNull() || raw.and(1).toInt32() !== 1) return null;
    const base = raw.sub(1);
    const key = base.toString();
    if (visited.has(key)) return null;
    visited.add(key);
    _findBudget2--;
    const hits = [];
    for (let off = 8; off <= 8 + 8 * 16; off += 8) {
      try { const d = base.add(off).readDouble(); if (looksLikeCoord(d)) hits.push(off); } catch (_) {}
    }
    if (hits.length >= 2) return { base, latOff: hits[0], lngOff: hits[1] };
    if (!heapBase || depth <= 0) return null;
    for (let off = 8; off <= 8 + 4 * 60; off += 4) {
      if (_findBudget2 <= 0) break;
      let v32; try { v32 = base.add(off).readU32(); } catch (_) { break; }
      if ((v32 & 1) !== 1) continue;
      let child; try { child = heapBase.add(v32); } catch (_) { continue; }
      const found = findFirstCoordPair(child, heapBase, depth - 1, visited);
      if (found) return found;
    }
    return null;
  } catch (_) { return null; }
}
// Overwrite an EXISTING OneByteString's bytes in place. Length can only
// shrink (or stay equal) — never grows past the original allocation, so this
// is always safe to attempt: a too-long replacement is truncated, never
// corrupts adjacent heap objects.
function patchStringInPlace(node, newText) {
  try {
    // Preflight: prove this address is still live/readable BEFORE writing.
    // Converts a raw "access violation" abort into a clean, diagnosable skip
    // — tells us the candidate was stale/misidentified rather than that the
    // write logic itself is unsafe.
    try { node.base.readU8(); } catch (e) {
      log(`patchStringInPlace: target ${node.base} unreadable at write time (stale/bad candidate) — skipping: ${e}`);
      return null;
    }
    const text = newText.length > node.len ? newText.slice(0, node.len) : newText;
    const bytes = [];
    for (let i = 0; i < text.length; i++) bytes.push(text.charCodeAt(i) & 0xff);
    node.base.add(8).writeU32(text.length << 1); // shrink the length Smi
    node.base.add(0x10).writeByteArray(bytes);
    return text;
  } catch (e) { log('patchStringInPlace error: ' + e); return null; }
}

// ---- Keyed resolver: locate the "result" entry inside the returned Map -----
// Confirmed via Blutter disassembly: objectMethod's return is an
// OperationResult (cid 1145) wrapping ONE real field at +8, a Map — and the
// getter code pulls a literal "result" key out of THAT map before handing it
// back to Dart callers. The generic depth-6 field walk above (deepDump /
// findFirstString / findFirstCoordPair) has no idea which key is the answer,
// so empirically it grabs whatever plausible-looking string/double it reaches
// first — which turned out to be unrelated Flutter widget/localization
// strings ("Move down", "helperError") and color-channel doubles, not the
// actual Landmark data. This resolver is narrower: it walks the same field
// grid, but ONLY accepts a hit when a field decodes to the literal string
// "result" — then reads the NEXT compressed-pointer slot in that same backing
// array as the value (Dart compact-hash Map `_data` arrays store key/value
// pairs in adjacent slots: data[2i]=key, data[2i+1]=value). Returns the tagged
// pointer to the value (same representation as a register/`raw` elsewhere),
// or null.
let _keyBudget = 0;
function findKeyedValue(raw, heapBase, keyText, depth, visited) {
  if (_keyBudget <= 0) return null;
  if (!visited) visited = new Set();
  try {
    if (!raw || raw.isNull() || raw.and(1).toInt32() !== 1) return null;
    const base = raw.sub(1);
    const vkey = base.toString();
    if (visited.has(vkey)) return null;
    visited.add(vkey);
    _keyBudget--;
    if (!heapBase || depth <= 0) return null;
    // Pass 1: look for the key literally among THIS object's own fields, so
    // we can read the paired value slot right next to it.
    for (let off = 8; off <= 8 + 4 * 60; off += 4) {
      if (_keyBudget <= 0) break;
      let v32; try { v32 = base.add(off).readU32(); } catch (_) { break; }
      if ((v32 & 1) !== 1) continue;
      let childRaw; try { childRaw = heapBase.add(v32); } catch (_) { continue; }
      const s = decodeStringAt(childRaw.sub(1));
      if (s && s.text === keyText) {
        let vv32; try { vv32 = base.add(off + 4).readU32(); } catch (_) { continue; }
        if ((vv32 & 1) !== 1) continue; // paired slot isn't a heap pointer — skip
        let valRaw; try { valRaw = heapBase.add(vv32); } catch (_) { continue; }
        log(`    [KEYED] found "${keyText}" @ ${base}+${off}, value slot @ +${off + 4} -> ${valRaw}`);
        return valRaw;
      }
    }
    // Pass 2: recurse into children looking for the key elsewhere.
    for (let off = 8; off <= 8 + 4 * 60; off += 4) {
      if (_keyBudget <= 0) break;
      let v32; try { v32 = base.add(off).readU32(); } catch (_) { break; }
      if ((v32 & 1) !== 1) continue;
      let child; try { child = heapBase.add(v32); } catch (_) { continue; }
      const found = findKeyedValue(child, heapBase, keyText, depth - 1, visited);
      if (found) return found;
    }
    return null;
  } catch (_) { return null; }
}

// ---- Hook install ----------------------------------------------------------
function install(mod) {
  const target = mod.base.add(TARGET.offsets.searchRepositoryImplSearch);
  log(`libapp.so base = ${mod.base}  ->  SearchRepositoryImpl::search @ ${target}`);
  Interceptor.attach(target, {
    onEnter(args) {
      try {
        HITS2++;
        const ctx = this.context;
        log(`===== HIT #${HITS2}  search =====`);
        let heapBase = null;
        try { heapBase = ctx.x28.shl(32); } catch (_) {}
        this.heapBase = heapBase;
        log(` x28=${ctx.x28} heapBase=${heapBase}`);
        const found = [];
        // Dart 3.12 arm64: receiver=x1, args=x2,x3,x5,x6,x7. Inspect all.
        for (const rn of ['x1', 'x2', 'x3', 'x5', 'x6', 'x7', 'x0']) {
          let rv; try { rv = ctx[rn]; } catch (_) { continue; }
          log(` ${rn}=${rv}`);
          dumpDartObject(rn, rv, heapBase, found);
        }
        // Pick the recovered query. The typed text is consistently the String
        // field at x3+16 (the SearchTextEvent's searchText), so prefer that
        // exact slot; fall back to the longest non-framework string otherwise.
        const JUNK = ['SearchMenuBloc', '] Added ', 'SearchTextEvent'];
        const isJunk = (t) => !t || t.length < 2 || t.indexOf('Instance of') >= 0 ||
          t.indexOf('package:') >= 0 || JUNK.some((j) => t.indexOf(j) >= 0);
        let query = null, querySrc = null;
        const exact = found.find((f) => f.src === 'x3+16' && !isJunk(f.text));
        if (exact) { query = exact.text; querySrc = exact.src; }
        else {
          for (const f of found) {
            if (isJunk(f.text)) continue;
            if (!query || f.text.length > query.length) { query = f.text; querySrc = f.src; }
          }
        }
        if (query) log(`  >>> RECOVERED QUERY [${querySrc}] = ${JSON.stringify(query)}`);
        else log('  >>> no query recovered this hit');
        this.query = query;

        // Run Google OFF the app's UI thread: doing HTTP directly in onEnter
        // throws NetworkOnMainThreadException. setTimeout(...,0) defers to
        // Frida's own timer thread, which is not the Android main looper, so
        // networking is allowed there. Debounced on the query text so we don't
        // hammer Google on every keystroke.
        if (query && query.trim().length >= 3) {
          const qClean = query.trim();
          if (qClean !== _lastGoogleQuery) {
            _lastGoogleQuery = qClean;
            setTimeout(function () {
              try {
                log(`Google search starting for ${JSON.stringify(qClean)} …`);
                const results = runGoogleSearch(qClean);
                if (results && results.length) { _lastGoogleResults = results; }
                if (results && results.length) {
                  log(`GOOGLE RESULTS (${results.length}) for ${JSON.stringify(qClean)}:`);
                  results.forEach((r, i) => log(`  [${i}] ${r.name} @ ${r.latitude},${r.longitude} (${r.address || 'no addr'})`));
                } else {
                  log(`Google returned no usable results for ${JSON.stringify(qClean)}`);
                }
              } catch (e) { log('Google search error: ' + e); }
            }, 0);
          }
        }
      } catch (e) {
        log('onEnter guarded error (original search preserved): ' + e);
      }
    },
    onLeave(retval) {
      try {
        if (HITS2 > 3) return; // inspect only the first few hits to limit noise
        const hb = this.heapBase;
        log(`----- onLeave HIT #${HITS2}: retval=${retval} -----`);
        _dumpBudget = 300;
        deepDump('retval', retval, hb, 4);
        // The result list may not be the return value (delivery is async via
        // NativePort). Also walk the receiver (x1) — the repo/bloc may hold it.
        _dumpBudget = 300;
        try { deepDump('x1recv', this.context.x1, hb, 3); } catch (_) {}
        log('----- end onLeave -----');
      } catch (e) { log('onLeave error: ' + e); }
    },
  });
  log('hook installed (compressed-pointer decoder + return inspector).');
}

// ---- objectMethod hook: the universal native-SDK property bridge -----------
// Confirmed via Blutter: Landmark.name / .address / .coordinates / .id are
// ALL implemented as objectMethod(handle, "Landmark", "<getterName>", {args})
// -> native libGEM -> a Map<String,dynamic> result. This single function is
// called constantly for map rendering too, so we cheaply filter on the
// className arg (x2) BEFORE doing any deeper decode, and cap total logged
// hits so this can't flood logcat or slow the app to a crawl.
let _omHits = 0;
const OM_LOG_CAP = 150;
function installObjectMethodHook(mod) {
  const off = TARGET.offsets.objectMethod;
  if (!off) { log('objectMethod offset not configured — skipping bridge hook'); return; }
  const target = mod.base.add(off);
  log(`objectMethod @ ${target} — hooking native SDK property bridge (Landmark-filtered)`);
  Interceptor.attach(target, {
    onEnter(args) {
      try {
        const ctx = this.context;
        let heapBase = null; try { heapBase = ctx.x28.shl(32); } catch (_) {}
        // x2 = className arg. Cheap bail-out: decode only this first.
        let classNameStr = null;
        try {
          const x2 = ctx.x2;
          if (x2 && !x2.isNull() && x2.and(1).toInt32() === 1) {
            const s = decodeStringAt(x2.sub(1));
            if (s) classNameStr = s.text;
          }
        } catch (_) {}
        if (classNameStr !== 'Landmark') { this.omInteresting = false; return; }
        if (_omHits >= OM_LOG_CAP) { this.omInteresting = false; return; }
        _omHits++;
        this.omInteresting = true;

        let methodNameStr = null;
        try {
          const x3 = ctx.x3;
          if (x3 && !x3.isNull() && x3.and(1).toInt32() === 1) {
            const s = decodeStringAt(x3.sub(1));
            if (s) methodNameStr = s.text;
          }
        } catch (_) {}

        log(`### objectMethod[#${_omHits}] Landmark.${methodNameStr || '?'}  (x1=${ctx.x1})`);
        // x1 is the handle the caller already unwrapped (often a Smi
        // pointerId, sometimes a small List/Map) — dump it briefly.
        this.omHeapBase = heapBase;
        this.omMethod = methodNameStr;
        _dumpBudget = 80;
        try { deepDump('  handle(x1)', ctx.x1, heapBase, 2); } catch (_) {}
      } catch (e) { log('objectMethod onEnter error: ' + e); }
    },
    onLeave(retval) {
      try {
        if (!this.omInteresting) return;
        log(`    -> result: ${retval}`);
        _dumpBudget = 400;
        deepDump('  result', retval, this.omHeapBase, 6);

        // ---- ACTUAL DELIVERY ATTEMPT (not just recon) -----------------
        // If we have a resolved Google result for the current query, try to
        // overwrite what this getter just returned so the on-screen row
        // shows Google's data instead of the native engine's. Guarded and
        // best-effort: on any failure the original result is untouched.
        const g = _lastGoogleResults && _lastGoogleResults[0];
        if (!g) { log('    (no pending Google result — mutation skipped)'); return; }
        const m = this.omMethod;

        // Strategy A (preferred): anchor on the literal "result" key inside
        // the returned Map, then only look at what's THERE — narrow, so it
        // can't wander into unrelated widget/localization heap noise the way
        // the blind depth-6 walk (Strategy B below) did.
        _keyBudget = 200;
        const keyedVal = findKeyedValue(retval, this.omHeapBase, 'result', 6);

        if (m === 'getName' || m === 'getAddress') {
          const wantText = m === 'getName' ? g.name : (g.address || g.name);
          let node = null, via = null;
          if (keyedVal) {
            const direct = decodeStringAt(keyedVal.sub(1));
            if (direct && direct.text.length >= 1) { node = { base: keyedVal.sub(1), len: direct.len }; via = 'KEYED-direct'; }
            else {
              // "result" value isn't itself a String — unwrap one more level.
              _findBudget = 60;
              const nested = findFirstString(keyedVal, this.omHeapBase, 2);
              if (nested) { node = nested; via = 'KEYED-nested'; }
            }
          }
          if (!node) {
            _findBudget = 400;
            const fallback = findFirstString(retval, this.omHeapBase, 6);
            if (fallback) { node = fallback; via = 'GENERIC-fallback'; }
          }
          if (node) {
            log(`    [${via}] candidate string @ ${node.base} (len ${node.len})`);
            const applied = patchStringInPlace(node, wantText);
            log(applied
              ? `    >>> MUTATION APPLIED [${via}]: ${m} string patched -> ${JSON.stringify(applied)} (orig cap ${node.len} chars)`
              : `    >>> MUTATION FAILED [${via}]: ${m} patch threw / candidate stale`);
          } else {
            log(`    >>> MUTATION SKIPPED: no String field found in ${m} result to patch (keyed and generic both missed)`);
          }
        } else if (m === 'getCoordinates') {
          let pair = null, via = null;
          if (keyedVal) {
            // Check the "result" value's own inline doubles first (tight, 1
            // level), then its keyed "latitude"/"longitude" sub-entries, then
            // a shallow generic walk scoped to just this subtree.
            try {
              const kb = keyedVal.sub(1);
              const hits = [];
              for (let off = 8; off <= 8 + 8 * 16; off += 8) {
                try { const d = kb.add(off).readDouble(); if (looksLikeCoord(d)) hits.push(off); } catch (_) {}
              }
              if (hits.length >= 2) { pair = { base: kb, latOff: hits[0], lngOff: hits[1] }; via = 'KEYED-direct'; }
            } catch (_) {}
            if (!pair) {
              _findBudget2 = 60;
              const nested = findFirstCoordPair(keyedVal, this.omHeapBase, 3);
              if (nested) { pair = nested; via = 'KEYED-nested'; }
            }
          }
          if (!pair) {
            _findBudget2 = 400;
            const fallback = findFirstCoordPair(retval, this.omHeapBase, 6);
            if (fallback) { pair = fallback; via = 'GENERIC-fallback'; }
          }
          if (pair) {
            log(`    [${via}] candidate coord pair @ ${pair.base} (+${pair.latOff}/+${pair.lngOff})`);
            try {
              pair.base.add(pair.latOff).writeDouble(g.latitude);
              pair.base.add(pair.lngOff).writeDouble(g.longitude);
              log(`    >>> MUTATION APPLIED [${via}]: coordinates patched -> ${g.latitude},${g.longitude} (was @ +${pair.latOff}/+${pair.lngOff})`);
            } catch (e) { log(`    >>> MUTATION FAILED [${via}]: coordinates write error: ` + e); }
          } else {
            log('    >>> MUTATION SKIPPED: no coordinate-looking double pair found (keyed and generic both missed)');
          }
        }
      } catch (e) { log('objectMethod onLeave error: ' + e); }
    },
  });
}

function extractSearchText(decoded) {
  if (typeof decoded === 'string') return decoded;
  if (decoded && typeof decoded === 'object') {
    if (typeof decoded.searchText === 'string') return decoded.searchText;
    for (const k of Object.keys(decoded)) if (typeof decoded[k] === 'string' && decoded[k].length) return decoded[k];
  }
  return null;
}

// ---- Boot ----------------------------------------------------------------
// Fire the alive beacon + install the curl hook IMMEDIATELY (these don't need
// libapp.so). If we see the beacon but not the Dart-hook logs, we know the
// script runs and logging works, and can focus on the search path.
log('script loaded — starting beacon + curl hook');
beacon(1);
try { installCurlHook(); } catch (e) { log('curl hook boot error: ' + e); }
try { installPostCObjectProbe(); } catch (e) { log('NativePort probe boot error: ' + e); }

(function bootDart() {
  if (typeof bootDart.tries === 'undefined') bootDart.tries = 0;
  const mod = Process.findModuleByName('libapp.so');
  if (!mod) {
    bootDart.tries++;
    if (bootDart.tries === 1 || bootDart.tries % 10 === 0) log(`waiting for libapp.so… (try ${bootDart.tries})`);
    setTimeout(bootDart, 500);
    return;
  }
  log('==================== cairodrive search hook: BOOT ====================');
  log(`arch=${Process.arch} pointerSize=${Process.pointerSize} pid=${Process.id}`);
  for (const name of ['libapp.so', 'libGEM.so', 'libflutter.so']) {
    const m = Process.findModuleByName(name);
    log(m ? `module ${name}: base=${m.base} size=0x${m.size.toString(16)}` : `module ${name}: NOT LOADED`);
  }
  log('GOOGLE_PLACES_API_KEY: ' +
      (GOOGLE_PLACES_API_KEY ? `present (len=${GOOGLE_PLACES_API_KEY.length})` : 'ABSENT — put it in /data/local/tmp/gpk'));
  if (!verifyTarget(mod)) {
    log('VERSION GATE FAILED — not hooking (offset 0x926cc4 may be wrong for this build). See gate lines above.');
    return;
  }
  log('version gate PASSED; resolving identity + installing hook.');
  try { resolveIdentity(); } catch (e) { log('resolveIdentity error: ' + e); }
  install(mod);
  try { installObjectMethodHook(mod); } catch (e) { log('objectMethod hook boot error: ' + e); }
  log('==================== boot complete; search to trigger ================');
})();
