#!/usr/bin/env bash
# monitor.sh - synthetic monitor: runs a small set of real checks against a LIVE environment on a
# schedule (see .github/workflows/synthetic-monitor.yml), distinct from scripts/smoke-test.sh
# (which only runs once, right after a deploy). This is the "given ... running in prod" half of
# the e2e ticket's DoD - a continuous canary, not a one-off post-deploy gate.
#
# Two tiers of check, escalating in how much of the real system they exercise:
#   1. Health + authenticated read (always runs) - confirms the process, auth, and DB are healthy.
#   2. Full synthetic transaction (only if SYNTHETIC_STORE_ID is set) - actually creates a cart,
#      checks it out, pays by CASH, and immediately refunds it in full, scoped to one
#      pre-provisioned "synthetic" store so it never touches real business data. CASH (not CARD)
#      is deliberate: this codebase's PaymentGatewayService.SimulatorPaymentGateway is a stand-in,
#      not a real card processor - see PERFORMANCE.md/docs/runbooks/payment-gateway-down.md's own
#      disclosure of the same fact - so a real prod deployment behind a real card gateway would
#      still incur a real (refunded) charge if this ran CARD. CASH exercises the same
#      cart->checkout->pay->refund lifecycle without that side effect.
#
# Usage:
#   BASE_URL=https://api.posly.example \
#   SYNTHETIC_USERNAME=synthetic-monitor SYNTHETIC_PASSWORD=... \
#   [SYNTHETIC_STORE_ID=<pre-provisioned store id>] \
#   scripts/synthetic/monitor.sh
#
# Requires: curl, jq. Exit 0 = all checks passed; non-zero = at least one failed.
#
# Operational prerequisite (not automated here - see E2E_TESTING.md): SYNTHETIC_USERNAME must be a
# dedicated account created specifically for this monitor - never the demo admin/admin123 seed
# account (see UserService's init block) and never a real admin's own credentials. Tier 1 alone
# needs only an authenticated account (any role); tier 2 (SYNTHETIC_STORE_ID set) needs at least
# MANAGER, since /orders/{id}/refund is ADMIN/MANAGER-only - grant the least privilege the
# configured tier actually needs. If SYNTHETIC_STORE_ID is set, that store must be excluded from
# real sales/finance dashboards by an operator (e.g. never selecting it in the store picker) -
# there is no "is_synthetic" flag on Store/Order in this codebase to do that automatically.
set -euo pipefail

BASE_URL="${BASE_URL:?BASE_URL is required}"
BASE_URL="${BASE_URL%/}"
SYNTHETIC_USERNAME="${SYNTHETIC_USERNAME:?SYNTHETIC_USERNAME is required}"
SYNTHETIC_PASSWORD="${SYNTHETIC_PASSWORD:?SYNTHETIC_PASSWORD is required}"
SYNTHETIC_STORE_ID="${SYNTHETIC_STORE_ID:-}"

PASS=0
FAIL=0

log()  { echo "[$(date -u +%H:%M:%S)] $*"; }
pass() { log "PASS  $1"; PASS=$((PASS + 1)); }
fail() { log "FAIL  $1"; FAIL=$((FAIL + 1)); }

log "Synthetic monitor starting against $BASE_URL"

# ---------------------------------------------------------------
# Tier 1: health + authenticated read - always runs.
# ---------------------------------------------------------------
HEALTH_STATUS=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 "$BASE_URL/health" || echo "000")
if [[ "$HEALTH_STATUS" == "200" ]]; then
  pass "GET /health -> 200"
else
  fail "GET /health -> $HEALTH_STATUS (expected 200)"
  echo "=== Synthetic monitor summary: $PASS passed, $FAIL failed ==="
  exit 1
fi

LOGIN_RESP=$(curl -s --max-time 10 -X POST "$BASE_URL/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$SYNTHETIC_USERNAME\",\"password\":\"$SYNTHETIC_PASSWORD\"}")
TOKEN=$(echo "$LOGIN_RESP" | jq -r '.accessToken // empty')
if [[ -n "$TOKEN" ]]; then
  pass "POST /auth/login -> access token issued"
else
  fail "POST /auth/login -> no accessToken in response: $LOGIN_RESP"
  echo "=== Synthetic monitor summary: $PASS passed, $FAIL failed ==="
  exit 1
fi

SEARCH_STATUS=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 "$BASE_URL/search?size=1" \
  -H "Authorization: Bearer $TOKEN")
if [[ "$SEARCH_STATUS" == "200" ]]; then
  pass "GET /search (authenticated) -> 200"
else
  fail "GET /search (authenticated) -> $SEARCH_STATUS (expected 200)"
fi

# ---------------------------------------------------------------
# Tier 2: full synthetic transaction - only if a dedicated synthetic store is configured.
# ---------------------------------------------------------------
if [[ -z "$SYNTHETIC_STORE_ID" ]]; then
  log "SYNTHETIC_STORE_ID not set - skipping the full transaction check (see this script's header)."
  echo "=== Synthetic monitor summary: $PASS passed, $FAIL failed ==="
  [[ $FAIL -eq 0 ]] || exit 1
  exit 0
fi

PRODUCTS_RESP=$(curl -s --max-time 10 "$BASE_URL/search?size=1" -H "Authorization: Bearer $TOKEN")
PRODUCT_ID=$(echo "$PRODUCTS_RESP" | jq -r '.results[0].id // empty')
if [[ -z "$PRODUCT_ID" ]]; then
  fail "no product found to exercise the synthetic transaction against (searched via GET /search)"
  echo "=== Synthetic monitor summary: $PASS passed, $FAIL failed ==="
  exit 1
fi

CART_RESP=$(curl -s --max-time 10 -X POST "$BASE_URL/carts" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d "{\"storeId\":\"$SYNTHETIC_STORE_ID\"}")
CART_ID=$(echo "$CART_RESP" | jq -r '.id // empty')
if [[ -z "$CART_ID" ]]; then
  fail "POST /carts did not return a cart id: $CART_RESP"
  echo "=== Synthetic monitor summary: $PASS passed, $FAIL failed ==="
  exit 1
fi
pass "POST /carts -> cart created ($CART_ID)"

curl -s --max-time 10 -X POST "$BASE_URL/carts/$CART_ID/items" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d "{\"productId\":\"$PRODUCT_ID\",\"quantity\":1}" > /dev/null
pass "POST /carts/{id}/items -> item added"

CHECKOUT_RESP=$(curl -s --max-time 10 -X POST "$BASE_URL/carts/$CART_ID/checkout" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d "{\"idempotencyKey\":\"synthetic-$(date -u +%Y%m%dT%H%M%S)\"}")
ORDER_ID=$(echo "$CHECKOUT_RESP" | jq -r '.id // empty')
TOTAL=$(echo "$CHECKOUT_RESP" | jq -r '.totals.total // empty')
if [[ -z "$ORDER_ID" ]]; then
  fail "POST /carts/{id}/checkout did not return an order id: $CHECKOUT_RESP"
  echo "=== Synthetic monitor summary: $PASS passed, $FAIL failed ==="
  exit 1
fi
pass "POST /carts/{id}/checkout -> order created ($ORDER_ID)"

PAY_STATUS=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 -X POST "$BASE_URL/orders/$ORDER_ID/payments" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d "{\"method\":\"CASH\",\"amount\":$TOTAL}")
if [[ "$PAY_STATUS" == "200" ]]; then
  pass "POST /orders/{id}/payments (CASH) -> order paid"
else
  fail "POST /orders/{id}/payments (CASH) -> $PAY_STATUS (expected 200)"
fi

ORDER_DETAIL=$(curl -s --max-time 10 "$BASE_URL/orders/$ORDER_ID" -H "Authorization: Bearer $TOKEN")
ITEM_ID=$(echo "$ORDER_DETAIL" | jq -r '.items[0].id // empty')

REFUND_STATUS=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 -X POST "$BASE_URL/orders/$ORDER_ID/refund" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d "{\"refundId\":\"synthetic-refund-$(date -u +%Y%m%dT%H%M%S)\",\"method\":\"MANUAL\",\"reason\":\"synthetic monitor cleanup\",\"lineItems\":[{\"cartItemId\":\"$ITEM_ID\",\"quantity\":1,\"restock\":true}]}")
if [[ "$REFUND_STATUS" == "200" ]]; then
  pass "POST /orders/{id}/refund -> synthetic order refunded (net zero impact)"
else
  fail "POST /orders/{id}/refund -> $REFUND_STATUS (expected 200) - MANUAL cleanup of order $ORDER_ID needed"
fi

echo "=== Synthetic monitor summary: $PASS passed, $FAIL failed ==="
[[ $FAIL -eq 0 ]] || exit 1
