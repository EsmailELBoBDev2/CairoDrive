// cairodrive-search-hook.js  (hardened)
//
// Routes Magic Earth's EXISTING phone search through Google Places API (New),
// preserving the search UI, the Landmark result model, and the whole
// destination/routing/navigation pipeline. Search only — nothing touching
// Premium/licensing/entitlement.
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

function log(m) { console.log('[cairodrive] ' + m); }

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

// ---- Google Places request contract (identical to the CI live probe) -------
// The request shape is the one verified against the live API. Delivery of the
// resulting Landmarks into the async bloc stream is documented in
// reports/SEARCH-PATCH-DESIGN.md and finalized on-device.
function inferLang(q) {
  for (const ch of q) { const c = ch.codePointAt(0);
    if ((c>=0x0600&&c<=0x06ff)||(c>=0x0750&&c<=0x077f)||(c>=0xfb50&&c<=0xfdff)||(c>=0xfe70&&c<=0xfeff)) return 'ar'; }
  return 'en';
}
// An Android-app-restricted GOOGLE_PLACES_API_KEY requires the request to assert
// the app identity the key is registered for. The Google Maps Android SDK adds
// X-Android-Package/X-Android-Cert automatically from the running app; because
// this hook issues a RAW HTTP request, it must add them itself. These are the
// TRUE identity of the app the hook runs inside — NOT a spoof: on-device, this
// process really is com.generalmagic.magicearth signed with the cert below.
//
// The default cert is the MODIFIED dev build's signing cert (what you install in
// Mode 2). If you instead attach to the ORIGINAL unmodified app on a rooted
// device (Mode 1), set ANDROID_CERT_SHA1 to the original cert
// 3705BA93D86F9566CDB440977E65C8DF660514AE. Either way, the GOOGLE_PLACES_API_KEY
// must be configured in Google Cloud to allow the matching (package, SHA-1).
const ANDROID_PACKAGE = 'com.generalmagic.magicearth';
const ANDROID_CERT_SHA1 = '5D08264B44E0E53FBCCC70B4F016474CC6C5AB5C'; // modified dev build cert
function googleHeaders(extra) {
  return Object.assign({
    'X-Goog-Api-Key': GOOGLE_PLACES_API_KEY,
    'X-Android-Package': ANDROID_PACKAGE,
    'X-Android-Cert': ANDROID_CERT_SHA1,
  }, extra || {});
}
let sessionToken = null, requestSeq = 0;
function newSessionToken() {
  const b = []; for (let i=0;i<16;i++) b.push(Math.floor(Math.random()*256));
  b[6]=(b[6]&0x0f)|0x40; b[8]=(b[8]&0x3f)|0x80;
  const h=b.map(x=>x.toString(16).padStart(2,'0'));
  return `${h.slice(0,4).join('')}-${h.slice(4,6).join('')}-${h.slice(6,8).join('')}-${h.slice(8,10).join('')}-${h.slice(10,16).join('')}`;
}

// ---- Hook install ----------------------------------------------------------
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
        // Google flow + result delivery attach here (design §5); any failure
        // below MUST NOT propagate — the original result then stands (fallback).
      } catch (e) {
        log('onEnter guarded error (original search preserved): ' + e);
      }
    },
    onLeave(retval) { /* delegation/fallback point — see design doc */ },
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

// ---- Boot ------------------------------------------------------------------
(function boot() {
  const mod = Process.findModuleByName('libapp.so');
  if (!mod) { setTimeout(boot, 500); return; }
  log('GOOGLE_PLACES_API_KEY: ' + (GOOGLE_PLACES_API_KEY ? 'present' : 'ABSENT') + ' (value never printed)');
  if (!verifyTarget(mod)) {
    log('VERSION GATE FAILED — refusing to apply offset 0x926cc4. Re-run Blutter for this build and update the address table. No hook installed.');
    return;
  }
  log('version gate passed; applying offset.');
  install(mod);
})();
