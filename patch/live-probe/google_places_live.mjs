// google_places_live.mjs
//
// Runs the EXACT Google Places API (New) requests that
// patch/frida/cairodrive-search-hook.js issues, against the LIVE API, so the
// "search text -> Google request -> HTTP success -> prediction -> Place Details
// -> lat/lng" half of the flow is demonstrated on a real runtime (CI, where
// GOOGLE_PLACES_API_KEY exists). It does NOT run inside Magic Earth — that needs
// an Android runtime this analysis sandbox does not have (see
// reports/RUNTIME-SEARCH-TEST.md).
//
// The API key is read from the environment and NEVER printed.
//
// Usage:  GOOGLE_PLACES_API_KEY=... node patch/live-probe/google_places_live.mjs

const KEY = (process.env.GOOGLE_PLACES_API_KEY || '').trim();
// An Android-app-restricted key requires the caller to assert the app identity
// it is registered to. The Google Maps Android SDK adds these automatically;
// a raw HTTP caller (this probe, and the Frida hook) must add them explicitly.
// Supply the identity the key is registered to via env; defaults to the
// original Magic Earth app identity (public, derived from the APK cert).
const ANDROID_PACKAGE = (process.env.ANDROID_PACKAGE || '').trim();
const ANDROID_CERT_SHA1 = (process.env.ANDROID_CERT_SHA1 || '').trim();
function androidHeaders() {
  const h = {};
  if (ANDROID_PACKAGE) h['X-Android-Package'] = ANDROID_PACKAGE;
  if (ANDROID_CERT_SHA1) h['X-Android-Cert'] = ANDROID_CERT_SHA1.replace(/:/g, '').toUpperCase();
  return h;
}
const AUTOCOMPLETE = 'https://places.googleapis.com/v1/places:autocomplete';
const DETAILS = 'https://places.googleapis.com/v1/places/';
const AC_MASK =
  'suggestions.placePrediction.placeId,suggestions.placePrediction.text,' +
  'suggestions.placePrediction.structuredFormat,suggestions.placePrediction.types,' +
  'suggestions.placePrediction.distanceMeters';
const DETAILS_MASK = 'id,displayName,formattedAddress,location,primaryType';
const CAIRO = { latitude: 30.0444, longitude: 31.2357 };

function uuid4() {
  const b = [...crypto.getRandomValues(new Uint8Array(16))];
  b[6] = (b[6] & 0x0f) | 0x40; b[8] = (b[8] & 0x3f) | 0x80;
  const h = b.map((x) => x.toString(16).padStart(2, '0'));
  return `${h.slice(0,4).join('')}-${h.slice(4,6).join('')}-${h.slice(6,8).join('')}-${h.slice(8,10).join('')}-${h.slice(10,16).join('')}`;
}
function inferLang(q) {
  for (const ch of q) { const c = ch.codePointAt(0);
    if ((c>=0x0600&&c<=0x06ff)||(c>=0x0750&&c<=0x077f)||(c>=0xfb50&&c<=0xfdff)||(c>=0xfe70&&c<=0xfeff)) return 'ar'; }
  return 'en';
}

async function autocomplete(query, token) {
  const body = {
    input: query, sessionToken: token, languageCode: inferLang(query),
    regionCode: 'EG', locationBias: { circle: { center: CAIRO, radius: 60000 } },
  };
  const r = await fetch(AUTOCOMPLETE, {
    method: 'POST',
    headers: { 'X-Goog-Api-Key': KEY, 'X-Goog-FieldMask': AC_MASK, 'Content-Type': 'application/json', ...androidHeaders() },
    body: JSON.stringify(body),
  });
  const text = await r.text();
  if (!r.ok) throw { status: r.status, body: text.slice(0, 300) };
  const json = JSON.parse(text);
  return (json.suggestions || [])
    .map((s) => s.placePrediction).filter((p) => p && p.placeId)
    .map((p) => ({
      placeId: p.placeId,
      title: p.structuredFormat?.mainText?.text || p.text?.text || p.placeId,
      subtitle: p.structuredFormat?.secondaryText?.text,
    }));
}

async function details(placeId, token) {
  const url = `${DETAILS}${encodeURIComponent(placeId)}?sessionToken=${encodeURIComponent(token)}`;
  const r = await fetch(url, { headers: { 'X-Goog-Api-Key': KEY, 'X-Goog-FieldMask': DETAILS_MASK, ...androidHeaders() } });
  const text = await r.text();
  if (!r.ok) throw { status: r.status, body: text.slice(0, 300) };
  const j = JSON.parse(text);
  return {
    name: j.displayName?.text, address: j.formattedAddress,
    lat: j.location?.latitude, lng: j.location?.longitude, type: j.primaryType,
  };
}

async function runCase(label, query, { expectEmpty = false } = {}) {
  const token = uuid4(); // fresh session per typing session
  process.stdout.write(`\n== ${label}: "${query}" ==\n`);
  try {
    const preds = await autocomplete(query, token);
    console.log(`  autocomplete: HTTP 200, ${preds.length} prediction(s)`);
    preds.slice(0, 5).forEach((p, i) => console.log(`    [${i}] ${p.title}${p.subtitle ? ' — ' + p.subtitle : ''}`));
    if (preds.length === 0) {
      console.log(expectEmpty ? '  RESULT: empty (as expected) -> app would keep Magic Lane search usable' : '  RESULT: empty');
      return { label, ok: true, empty: true };
    }
    const d = await details(preds[0].placeId, token); // same session token -> closes session
    console.log(`  details: HTTP 200  ${d.name} [${d.type || 'n/a'}]`);
    console.log(`  COORDINATES: lat=${d.lat}  lng=${d.lng}`);
    console.log(`  address: ${d.address}`);
    return { label, ok: true, name: d.name, lat: d.lat, lng: d.lng };
  } catch (e) {
    console.log(`  ERROR: HTTP ${e.status ?? '(network)'} ${e.body ? '— ' + e.body.replace(/\s+/g, ' ') : ''}`);
    console.log('  -> a real Google failure; in the app this triggers the Magic Lane fallback');
    return { label, ok: false, status: e.status };
  }
}

async function main() {
  console.log(`GOOGLE_PLACES_API_KEY: ${KEY ? 'present' : 'ABSENT'} (value never printed)`);
  console.log(`endpoint: ${AUTOCOMPLETE}`);
  console.log(`android identity: ${ANDROID_PACKAGE || '(none)'} / cert ${ANDROID_CERT_SHA1 ? ANDROID_CERT_SHA1.slice(0,8)+'…' : '(none)'}`);
  if (!KEY) { console.log('No key in env — cannot issue a live request. See RUNTIME-SEARCH-TEST.md.'); process.exit(2); }

  const results = [];
  results.push(await runCase('Test A (success)', 'Cairo Festival City'));
  results.push(await runCase('Mall of Egypt', 'Mall of Egypt'));
  results.push(await runCase('City Stars', 'City Stars'));
  results.push(await runCase('Arabic business', 'مطعم أبو السيد'));
  results.push(await runCase('English address', '90th Street, New Cairo'));
  results.push(await runCase('Test E (empty)', 'zzzxqjqweqweqzzz nonexistent poi 99999', { expectEmpty: true }));

  console.log('\n== SUMMARY ==');
  for (const r of results) {
    console.log(`  ${r.label}: ${r.ok ? (r.empty ? 'empty' : `OK ${r.name} (${r.lat}, ${r.lng})`) : 'FAILED http ' + r.status}`);
  }
  const gotCoords = results.some((r) => r.ok && !r.empty && typeof r.lat === 'number');
  console.log(`\nLIVE COORDINATE PROOF: ${gotCoords ? 'YES — real lat/lng obtained from Google' : 'NO'}`);
  process.exit(gotCoords ? 0 : 1);
}
main().catch((e) => { console.error('fatal', e); process.exit(3); });
