#!/usr/bin/env bash
#
# Game-day drill for docs/runbooks/terminal-offline.md: fires a REAL printer-offline incident
# against a running server (via the actual admin API - PATCH /printers/{id}/status - not a mock),
# confirms the offline gauge/alert signal reacts, confirms print jobs queue instead of failing
# while offline (rather than just asserting it - this actually submits print jobs and inspects the
# response), then confirms recovery. Meant to be run by whoever's on call as a rehearsal, walking
# the runbook step by step - not just a correctness test (that's what PrintServiceTest is for).
#
# Requires: curl, jq, a running server (default http://localhost:8080 - see BASE_URL below) with
# the default seeded admin/admin123 user (a fresh dev/local instance - see UserService's init
# block; do not point this at a real staging/prod environment with real admin credentials).
#
# Usage:
#   ./gradlew :server:run &        # in one terminal
#   scripts/gameday/simulate-terminal-offline.sh   # in another
#
# Env vars:
#   BASE_URL  Target server (default http://localhost:8080)
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

step() { echo; echo "--- $1 ---"; }
pass() { echo "[PASS] $1"; PASS=$((PASS + 1)); }
fail() { echo "[FAIL] $1"; FAIL=$((FAIL + 1)); }

step "0. Confirm the server is reachable"
if curl -sf --max-time 3 "$BASE_URL/health" > /dev/null; then
  pass "server is up at $BASE_URL"
else
  echo "Server not reachable at $BASE_URL - start it first (./gradlew :server:run) or set BASE_URL." >&2
  exit 1
fi

step "1. Log in as admin"
ADMIN_TOKEN=$(curl -s -X POST "$BASE_URL/auth/login" -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | jq -r '.accessToken')
[[ "$ADMIN_TOKEN" != "null" && -n "$ADMIN_TOKEN" ]] && pass "admin login" || { fail "admin login"; exit 1; }

step "2. Set up a store, a product, and a printer"
STORE_ID=$(curl -s -X POST "$BASE_URL/stores" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"Game Day Store","address":{"line1":"1 Main St","city":"NY","postalCode":"10001","country":"US"},"timezone":"America/New_York","currency":"USD"}' \
  | jq -r '.id')
PRODUCT_ID=$(curl -s -X POST "$BASE_URL/products" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d "{\"sku\":\"GAMEDAY-$(date +%s)\",\"name\":\"Game Day Widget\",\"price\":5.00}" \
  | jq -r '.id')
PRINTER_ID=$(curl -s -X POST "$BASE_URL/printers" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d "{\"storeId\":\"$STORE_ID\",\"name\":\"Front Counter\",\"connectionType\":\"USB\"}" \
  | jq -r '.id')
[[ -n "$STORE_ID" && -n "$PRODUCT_ID" && -n "$PRINTER_ID" ]] && pass "store/product/printer created" || { fail "setup"; exit 1; }

place_order() {
  local cart_id order_id
  cart_id=$(curl -s -X POST "$BASE_URL/carts" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
    -d "{\"storeId\":\"$STORE_ID\"}" | jq -r '.id')
  curl -s -X POST "$BASE_URL/carts/$cart_id/items" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
    -d "{\"productId\":\"$PRODUCT_ID\",\"quantity\":1}" > /dev/null
  order_id=$(curl -s -X POST "$BASE_URL/carts/$cart_id/checkout" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
    -d "{\"idempotencyKey\":\"gameday-$(date +%s%N)\"}" | jq -r '.id')
  echo "$order_id"
}

step "3. Baseline: print a receipt while the printer is ONLINE"
ORDER_ID=$(place_order)
BASELINE_RESP=$(curl -s -o /tmp/gameday-baseline.json -w '%{http_code}' -X POST "$BASE_URL/orders/$ORDER_ID/print" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" -d "{\"printerId\":\"$PRINTER_ID\"}")
BASELINE_STATUS=$(jq -r '.status' /tmp/gameday-baseline.json)
if [[ "$BASELINE_RESP" == "200" && "$BASELINE_STATUS" == "PRINTED" ]]; then
  pass "baseline print succeeded while ONLINE (HTTP $BASELINE_RESP, status=$BASELINE_STATUS)"
else
  fail "expected HTTP 200 / status=PRINTED while ONLINE, got HTTP $BASELINE_RESP / status=$BASELINE_STATUS"
fi

step "4. FIRE THE INCIDENT: flip the printer OFFLINE (the same action a real driver/spooler failure would trigger)"
curl -s -X PATCH "$BASE_URL/printers/$PRINTER_ID/status" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"status":"OFFLINE"}' > /dev/null

step "5. Confirm the detection signal: posly_printers_offline on /metrics (backs the PoslyTerminalsOffline alert)"
OFFLINE_GAUGE=$(curl -s --max-time 3 "$BASE_URL/metrics" | grep '^posly_printers_offline' | awk '{print $2}')
if (( $(echo "$OFFLINE_GAUGE >= 1" | bc -l) )); then
  pass "posly_printers_offline = $OFFLINE_GAUGE (alert would fire after 10m sustained, per alerts.yml)"
else
  fail "expected posly_printers_offline >= 1, got $OFFLINE_GAUGE"
fi

step "6. Per the runbook: confirm a print job queues instead of failing while offline"
ORDER_ID_2=$(place_order)
INCIDENT_RESP=$(curl -s -o /tmp/gameday-incident.json -w '%{http_code}' -X POST "$BASE_URL/orders/$ORDER_ID_2/print" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" -d "{\"printerId\":\"$PRINTER_ID\"}")
INCIDENT_STATUS=$(jq -r '.status' /tmp/gameday-incident.json)
if [[ "$INCIDENT_RESP" == "202" && "$INCIDENT_STATUS" == "QUEUED" ]]; then
  pass "print job queued instead of failing (HTTP $INCIDENT_RESP, status=$INCIDENT_STATUS) - cashier checkout was never blocked"
else
  fail "expected HTTP 202 / status=QUEUED while OFFLINE, got HTTP $INCIDENT_RESP / status=$INCIDENT_STATUS"
fi

step "7. RECOVERY: flip the printer back ONLINE (the runbook's resolution step)"
curl -s -X PATCH "$BASE_URL/printers/$PRINTER_ID/status" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"status":"ONLINE"}' > /dev/null

step "8. Confirm the signal clears"
RECOVERED_GAUGE=$(curl -s --max-time 3 "$BASE_URL/metrics" | grep '^posly_printers_offline' | awk '{print $2}')
if (( $(echo "$RECOVERED_GAUGE == 0" | bc -l) )); then
  pass "posly_printers_offline back to 0"
else
  fail "expected posly_printers_offline back to 0, got $RECOVERED_GAUGE"
fi

step "9. Confirm printing works again post-recovery (a fresh print request, per the runbook's disclosed limitation that queued jobs aren't auto-retried)"
ORDER_ID_3=$(place_order)
RECOVERY_RESP=$(curl -s -o /tmp/gameday-recovery.json -w '%{http_code}' -X POST "$BASE_URL/orders/$ORDER_ID_3/print" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" -d "{\"printerId\":\"$PRINTER_ID\"}")
RECOVERY_STATUS=$(jq -r '.status' /tmp/gameday-recovery.json)
if [[ "$RECOVERY_RESP" == "200" && "$RECOVERY_STATUS" == "PRINTED" ]]; then
  pass "printing recovered (HTTP $RECOVERY_RESP, status=$RECOVERY_STATUS)"
else
  fail "expected HTTP 200 / status=PRINTED after recovery, got HTTP $RECOVERY_RESP / status=$RECOVERY_STATUS"
fi

rm -f /tmp/gameday-baseline.json /tmp/gameday-incident.json /tmp/gameday-recovery.json

echo
echo "=== Game day summary: $PASS passed, $FAIL failed ==="
[[ $FAIL -eq 0 ]] || exit 1
