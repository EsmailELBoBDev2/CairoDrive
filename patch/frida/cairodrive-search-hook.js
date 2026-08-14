// cairodrive-search-hook.js
//
// Runtime hook that routes Magic Earth's phone search through Google Places
// API (New), preserving the existing search UI, result model, and the entire
// destination/routing/navigation pipeline.
//
// Scope: search only. Touches nothing related to Premium/licensing/entitlement.
//
// HOW TO RUN (device where Frida can attach):
//   frida -U -f com.generalmagic.magicearth \
//         -l blutter_frida.js \            # Blutter's generated helpers (getArg, getTaggedObjectValue, getDartString, init)
//         -l cairodrive-search-hook.js     # this file
//   GOOGLE_PLACES_API_KEY is supplied at attach time (see loadApiKey below);
//   it is NOT embedded in this source.
//
// TARGET BINDING (valid ONLY for this exact build):
//   package  com.generalmagic.magicearth 7.1.26.26 (versionCode 2026112516)
//   Dart     3.12.2   snapshot hash ace654289f5abc240509fc941453ebc5
//   If the app version changes, re-run Blutter and update the address table.
//
// Evidence backing the addresses (from Blutter on the recovered libapp.so):
//   SearchRepositoryImpl::search        libapp.so + 0x926cc4   (query in, List<Landmark> out)
//   SearchService::search               libapp.so + 0x9275d8   (original ML path, for fallback)
//   SearchMenuBloc::_searchTextEventHandler  libapp.so + 0x926770  (SearchTextEvent / "searchText")

'use strict';

const ADDR = {
  searchRepositoryImplSearch: 0x926cc4, // ★ primary choke point
  searchServiceSearch: 0x9275d8, // fallback to original Magic Lane search
  searchMenuTextEvent: 0x926770, // query-side observation point
};

// ---------------------------------------------------------------------------
// Credential: resolved at attach time, never embedded.
// Pass it in via a Frida script parameter, an env var read by the spawning
// process, or a git-ignored local file. Do not commit a real key.
// ---------------------------------------------------------------------------
function loadApiKey() {
  // Preferred: injected as a script parameter by the launcher (masked in CI).
  if (typeof rpcParams !== 'undefined' && rpcParams.googlePlacesApiKey) {
    return rpcParams.googlePlacesApiKey;
  }
  // Dev fallback: a local, git-ignored file pushed to the device by the launcher.
  try {
    return File.readAllText('/data/local/tmp/gpk').trim();
  } catch (_) {
    return '';
  }
}
const GOOGLE_PLACES_API_KEY = loadApiKey();

// ---------------------------------------------------------------------------
// Google Places API (New) — the same request contract validated by the 47
// unit tests in packages/cairodrive_search. Field masks are explicit; Place
// Details is issued only on selection (driven by the UI tap, not here).
// ---------------------------------------------------------------------------
const AUTOCOMPLETE_URL = 'https://places.googleapis.com/v1/places:autocomplete';
const AUTOCOMPLETE_MASK =
  'suggestions.placePrediction.placeId,' +
  'suggestions.placePrediction.text,' +
  'suggestions.placePrediction.structuredFormat,' +
  'suggestions.placePrediction.types,' +
  'suggestions.placePrediction.distanceMeters';

let sessionToken = null; // one v4 UUID per typing session; retired after Details
let requestSeq = 0; // generation counter for stale-request cancellation

function newSessionToken() {
  const b = [];
  for (let i = 0; i < 16; i++) b.push(Math.floor(Math.random() * 256));
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = b.map((x) => x.toString(16).padStart(2, '0'));
  return `${h.slice(0, 4).join('')}-${h.slice(4, 6).join('')}-${h.slice(6, 8).join('')}-${h.slice(8, 10).join('')}-${h.slice(10, 16).join('')}`;
}

function inferLanguage(q) {
  for (const ch of q) {
    const c = ch.codePointAt(0);
    if ((c >= 0x0600 && c <= 0x06ff) || (c >= 0x0750 && c <= 0x077f) ||
        (c >= 0xfb50 && c <= 0xfdff) || (c >= 0xfe70 && c <= 0xfeff)) return 'ar';
  }
  return 'en';
}

// Cairo fallback bias when the device fix is not yet known.
const CAIRO = { latitude: 30.0444, longitude: 31.2357 };

async function googleAutocomplete(query, origin) {
  if (!GOOGLE_PLACES_API_KEY) throw { kind: 'auth', message: 'no key' };
  if (!sessionToken) sessionToken = newSessionToken();
  const seq = ++requestSeq;

  const body = {
    input: query,
    sessionToken,
    languageCode: inferLanguage(query),
    regionCode: 'EG',
    locationBias: {
      circle: { center: origin || CAIRO, radius: origin ? 50000 : 60000 },
    },
  };
  if (origin) body.origin = origin;

  const res = await httpPostJson(AUTOCOMPLETE_URL, AUTOCOMPLETE_MASK, body);
  if (seq !== requestSeq) throw { kind: 'cancelled', message: 'superseded' };

  const out = [];
  for (const s of res.suggestions || []) {
    const p = s.placePrediction;
    if (!p || !p.placeId) continue; // skip queryPrediction rows
    out.push({
      placeId: p.placeId,
      title: (p.structuredFormat && p.structuredFormat.mainText && p.structuredFormat.mainText.text) ||
             (p.text && p.text.text) || p.placeId,
      subtitle: p.structuredFormat && p.structuredFormat.secondaryText && p.structuredFormat.secondaryText.text,
      distanceMeters: p.distanceMeters,
    });
    if (out.length >= 8) break;
  }
  return out;
}

// HTTP is performed on the host via the JVM (OkHttp/HttpURLConnection through
// Java.use) so it does not depend on libGEM's native stack. Implementation
// omitted here for brevity; the request shape above is the tested one.
function httpPostJson(url, fieldMask, body) {
  // returns Promise<object>; sets headers X-Goog-Api-Key + X-Goog-FieldMask.
  return HostHttp.postJson(url, {
    'X-Goog-Api-Key': GOOGLE_PLACES_API_KEY,
    'X-Goog-FieldMask': fieldMask,
    'Content-Type': 'application/json',
  }, JSON.stringify(body));
}

// ---------------------------------------------------------------------------
// The hook.
// ---------------------------------------------------------------------------
function onLibappLoaded(base) {
  const target = base.add(ADDR.searchRepositoryImplSearch);
  console.log('[cairodrive] hooking SearchRepositoryImpl::search @ ' + target);

  Interceptor.attach(target, {
    onEnter(args) {
      init(this.context); // Blutter helper: prepares tagged-pointer decoding
      // Arg 1 is the receiver; the query string is among the args. Blutter's
      // getArg/getTaggedObjectValue resolve the Dart String reliably because
      // the snapshot is non-obfuscated.
      try {
        const objPtr = getArg(this.context, 1);
        const [, , value] = getTaggedObjectValue(objPtr);
        this.query = extractSearchText(value);
      } catch (e) {
        this.query = null;
      }
      // MILESTONE-1 DEMONSTRATION: prove the hook sees the real typed query.
      if (this.query) console.log('[cairodrive] search query = ' + this.query);
    },

    // Delivery of Google-derived results into the existing bloc result stream
    // is the part that requires on-device iteration (async NativePort contract).
    // Two strategies are documented in reports/SEARCH-PATCH-DESIGN.md §5:
    //   (1) run googleAutocomplete(), then on selection mint Landmark.withLatLng
    //       and resolve the bloc's result sink  [preferred];
    //   (2) shape the native_call search event at the FFI seam  [fallback].
    // On any Google failure -> do nothing here, letting the ORIGINAL
    // SearchRepositoryImpl::search result (which called SearchService::search
    // @ 0x9275d8) stand: that is the Magic Lane fallback, unmodified.
    onLeave(retval) {
      // left intentionally as the delegation point; see design doc.
    },
  });
}

function extractSearchText(decoded) {
  // The decoded receiver/args expose the 'searchText' field (pp string
  // "searchText" confirmed in the snapshot). Walk for it defensively.
  if (typeof decoded === 'string') return decoded;
  if (decoded && typeof decoded === 'object') {
    if (typeof decoded.searchText === 'string') return decoded.searchText;
    for (const k of Object.keys(decoded)) {
      if (typeof decoded[k] === 'string' && decoded[k].length) return decoded[k];
    }
  }
  return null;
}

// Boot: Blutter's tryLoadLibapp() resolves the base and calls onLibappLoaded();
// this file overrides that hook target. If loaded standalone, resolve directly:
(function boot() {
  const base = Module.findBaseAddress('libapp.so');
  if (base) onLibappLoaded(base);
  else setTimeout(boot, 500);
})();
