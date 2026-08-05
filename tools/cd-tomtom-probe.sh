#!/usr/bin/env bash
# Reproduce the app's TomTom calls EXACTLY, one variant at a time, and print the HTTP code.
#
# WHY: the MyTomTom dashboard showed ~131.2k 4XX out of ~140.9k requests (93.1%). A 4XX is not
# one failure mode - 403 means the key is not entitled to the product, 400 means a parameter was
# rejected - and the app's own logs cannot distinguish "which parameter" because it sends them all
# together. This sends them separately so the first failing variant names the cause.
#
# The key is read from the environment and never echoed. Run it like:
#
#     export TOMTOM_KEY='...'          # note the leading space if your shell is bash+HISTCONTROL
#     bash tools/cd-tomtom-probe.sh
#
# Nothing here writes the key anywhere. Do not paste the key into a file in this repo.

set -u

if [ -z "${TOMTOM_KEY:-}" ]; then
	echo "TOMTOM_KEY is not set. export TOMTOM_KEY='...' first (it is never printed)." >&2
	exit 2
fi

BASE="https://api.tomtom.com"
# A point on Salah Salem, and a bbox over central Cairo. bbox order is minLon,minLat,maxLon,maxLat.
PT="30.04440,31.23570"
BBOX="31.20000,30.00000,31.40000,30.10000"
FIELDS='{incidents{type,geometry{type,coordinates},properties{iconCategory,magnitudeOfDelay,delay,roadNumbers,events{description,code}}}}'
CATS="6,7,8,9,11"

# Prints: label, HTTP code, and the first 300 bytes of the body (TomTom puts the reason there).
probe() {
	local label="$1"; shift
	local out code body
	out=$(curl -sS -G -w '\n__CODE__%{http_code}' -o - "$@" 2>&1)
	code=$(printf '%s' "$out" | sed -n 's/.*__CODE__\([0-9]*\)$/\1/p')
	body=$(printf '%s' "$out" | sed 's/__CODE__[0-9]*$//' | tr -d '\n' | cut -c1-300)
	printf '%-46s %s\n' "$label" "${code:-ERR}"
	if [ "${code:-000}" != "200" ]; then
		printf '    %s\n' "$body"
	fi
}

echo "=== FLOW (flowSegmentData v4) ==="
# 1. The absolute minimum the endpoint accepts. If THIS 4XXs, the problem is the key or the
#    product entitlement, and no parameter change will help.
probe "flow: minimal (key+point only)" \
	"$BASE/traffic/services/4/flowSegmentData/relative0/10/json" \
	--data-urlencode "key=$TOMTOM_KEY" --data-urlencode "point=$PT"

# 2. Exactly what the app sends.
probe "flow: app's exact call (+unit=KMPH)" \
	"$BASE/traffic/services/4/flowSegmentData/relative0/10/json" \
	--data-urlencode "key=$TOMTOM_KEY" --data-urlencode "point=$PT" \
	--data-urlencode "unit=KMPH"

# 3. Orbis. TomTom's newer stack is a DIFFERENT product on the same host and needs apiVersion.
#    A key provisioned for Orbis only will reject the legacy paths above and accept this.
probe "flow: Orbis (apiVersion=1)" \
	"$BASE/traffic/services/4/flowSegmentData/relative0/10/json" \
	--data-urlencode "key=$TOMTOM_KEY" --data-urlencode "point=$PT" \
	--data-urlencode "apiVersion=1"

echo
echo "=== INCIDENTS (incidentDetails v5) ==="
# 4. Minimum: key + bbox. Isolates entitlement from every optional parameter.
probe "incidents: minimal (key+bbox)" \
	"$BASE/traffic/services/5/incidentDetails" \
	--data-urlencode "key=$TOMTOM_KEY" --data-urlencode "bbox=$BBOX"

# 5. Add the fields selector alone. Its brace syntax is the most likely 400 in the whole app.
probe "incidents: +fields selector" \
	"$BASE/traffic/services/5/incidentDetails" \
	--data-urlencode "key=$TOMTOM_KEY" --data-urlencode "bbox=$BBOX" \
	--data-urlencode "fields=$FIELDS"

# 6. Add Arabic. TomTom answers an unsupported language tag with 400 rather than falling back,
#    which is why the app has a languageFallback latch - this says whether it is being used.
probe "incidents: +language=ar-EG" \
	"$BASE/traffic/services/5/incidentDetails" \
	--data-urlencode "key=$TOMTOM_KEY" --data-urlencode "bbox=$BBOX" \
	--data-urlencode "fields=$FIELDS" --data-urlencode "language=ar-EG"

# 7. English control. If 6 fails and this passes, Arabic is the cause outright.
probe "incidents: +language=en-GB (control)" \
	"$BASE/traffic/services/5/incidentDetails" \
	--data-urlencode "key=$TOMTOM_KEY" --data-urlencode "bbox=$BBOX" \
	--data-urlencode "fields=$FIELDS" --data-urlencode "language=en-GB"

# 8. Everything, i.e. byte-for-byte what the app sends. Should match the app's observed code.
probe "incidents: app's exact call" \
	"$BASE/traffic/services/5/incidentDetails" \
	--data-urlencode "key=$TOMTOM_KEY" --data-urlencode "bbox=$BBOX" \
	--data-urlencode "fields=$FIELDS" --data-urlencode "language=ar-EG" \
	--data-urlencode "categoryFilter=$CATS" --data-urlencode "timeValidityFilter=present"

echo
cat <<'NOTE'
=== how to read this ===
  ALL 403, body says
  "over the limit"   -> MOST LIKELY. TomTom answers an exhausted free allowance with 403, not
                        429. The dashboard showed ~140.9k requests against a free tier of a few
                        thousand, so an earlier UNPACED build would have burned the allowance and
                        then 403'd for everything after - which is exactly a ~93% 4XX rate with a
                        small successful head. If this is it, the budget pacing already committed
                        IS the fix, and the 4XX is historical damage rather than a live fault.
  ALL 403, body says
  "missing permission"-> the key is not entitled to the Traffic product, or is restricted by a
                        referrer/IP allowlist. Fix in the TomTom console; no app change helps.
  minimal 200,
  later ones 4XX     -> a PARAMETER is being rejected. The first failing line names it.
  legacy 4XX,
  Orbis 200          -> the key is Orbis-only and every path in the app is the legacy one.
  ALL 200            -> the key and calls are fine, and the 4XX burst in the dashboard is
                        historical - from an older build - rather than current. Check the
                        dashboard's date range before doing anything else.
NOTE
