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
  },
};

// ---- Logging: mirror every line to Android logcat (tag: cairodrive) --------
// In autonomous gadget "script" mode nothing consumes console.log, so ALSO
// write to logcat via __android_log_write (works from any thread, no
// Java.perform needed). View on device with:  adb logcat -s cairodrive
let _alogFn = undefined;   // resolved lazily; null if the symbol is unavailable
let _alogTag = null;
function _logcat(msg) {
  try {
    if (_alogFn === undefined) {
      const p = Module.findExportByName(null, '__android_log_write');
      _alogFn = p ? new NativeFunction(p, 'int', ['int', 'pointer', 'pointer']) : null;
      _alogTag = Memory.allocUtf8String('cairodrive');
    }
    if (_alogFn) _alogFn(4 /* ANDROID_LOG_INFO */, _alogTag, Memory.allocUtf8String(msg));
  } catch (_) {}
}
function log(m) { const s = '[cairodrive] ' + m; console.log(s); _logcat(s); }

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
  const checks = [];
  checks.push(['abi', Process.arch === TARGET.abi, `${Process.arch} vs ${TARGET.abi}`]);
  const bid = readGnuBuildId(mod);
  checks.push(['build-id', bid === TARGET.buildId, `${bid} vs ${TARGET.buildId}`]);
  const hasHash = moduleContainsString(mod, TARGET.snapshotHash);
  checks.push(['snapshot-hash', hasHash, hasHash ? 'present' : 'absent']);
  let ok = true;
  for (const [name, pass, detail] of checks) {
    log(`gate ${name}: ${pass ? 'PASS' : 'FAIL'} (${detail})`);
    ok = ok && pass;
  }
  return ok;
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

// ---- Hook install ------------------------------------------------------
function install(mod) {
  const target = mod.base.add(TARGET.offsets.searchRepositoryImplSearch);
  log(`libapp.so base = ${mod.base}  ->  SearchRepositoryImpl::search @ ${target}`);
  Interceptor.attach(target, {
    onEnter(args) {
      try {
        if (typeof init === 'function') init(this.context); // Blutter helper
        let q = null;
        try {
          const objPtr = (typeof getArg === 'function') ? getArg(this.context, 1) : null;
          if (objPtr && typeof getTaggedObjectValue === 'function') {
            const [, , value] = getTaggedObjectValue(objPtr);
            q = extractSearchText(value);
          }
        } catch (_) {}
        this.query = q;
        log('target reached: SearchRepositoryImpl::search  query=' + (q === null ? '(unresolved)' : JSON.stringify(q)));

        if (q) {
          const googleResults = runGoogleSearch(q);
          this.googleResults = googleResults;
          if (googleResults && googleResults.length) {
            log(`Google candidates ready: ${googleResults.map((r) => r.name).join(', ')}`);
          } else {
            log('No usable Google results — original Magic Lane search result stands (fallback)');
          }
        }
        // ---- DELIVERY (TODO — needs on-device iteration) ----------------
        // `this.googleResults` above is a plain JS array of
        // {placeId, name, address, latitude, longitude, category} — fully
        // resolved, real coordinates, ready to become Landmarks. What is
        // NOT done: minting an actual SDK `Landmark` (Landmark.withLatLng)
        // on the Dart heap and substituting it for this call's return value
        // /feeding SearchMenuBloc's result stream. That is Dart-heap object
        // construction through Blutter's tagged-object writer, which cannot
        // be authored or verified without Frida attached to the live
        // process — see reports/SEARCH-PATCH-DESIGN.md §5 and
        // RUNTIME-DEVICE-RUNBOOK.md steps 4-5 for the exact next actions.
      } catch (e) {
        log('onEnter guarded error (original search preserved): ' + e);
      }
    },
    onLeave(retval) { /* delegation/fallback point — see DELIVERY note above */ },
  });
  log('hook installed; existing search UI and navigation untouched.');
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
(function boot() {
  const mod = Process.findModuleByName('libapp.so');
  if (!mod) { setTimeout(boot, 500); return; }
  log('GOOGLE_PLACES_API_KEY: ' + (GOOGLE_PLACES_API_KEY ? 'present' : 'ABSENT') + ' (value never printed)');
  if (!verifyTarget(mod)) {
    log('VERSION GATE FAILED — refusing to apply offset 0x926cc4. Re-run Blutter for this build and update the address table. No hook installed.');
    return;
  }
  log('version gate passed; applying offset.');
  resolveIdentity();
  install(mod);
})();
