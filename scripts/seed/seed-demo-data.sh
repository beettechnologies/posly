#!/usr/bin/env bash
#
# seed-demo-data.sh - populate a running posly server with demo catalog products and demo
# historical sales, exercising the ticket-47 migration tooling itself (upload -> dry-run preview
# -> async import -> reconciliation report) rather than writing directly to the database. Useful
# for spinning up a demo/staging environment with realistic-looking data, and doubles as a smoke
# test of the whole import pipeline end to end.
#
# Requires: curl, jq, a running server (default http://localhost:8080) with the default seeded
# admin/admin123 user (a fresh dev/local instance - see UserService's init block; do not point
# this at a real staging/prod environment with real admin credentials).
#
# Usage:
#   ./gradlew :server:run &        # in one terminal
#   scripts/seed/seed-demo-data.sh # in another
#
# Env vars:
#   BASE_URL  Target server (default http://localhost:8080)
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

step() { echo; echo "--- $1 ---"; }
info() { echo "  $1"; }
die()  { echo "ERROR: $1" >&2; exit 1; }

poll_job() {
  local jobs_path="$1" job_id="$2" status job
  for _ in $(seq 1 20); do
    job=$(curl -s "$BASE_URL/$jobs_path/jobs/$job_id" -H "Authorization: Bearer $ADMIN_TOKEN")
    status=$(echo "$job" | jq -r '.status')
    [[ "$status" == "COMPLETED" || "$status" == "FAILED" ]] && { echo "$job"; return 0; }
    sleep 0.3
  done
  echo "$job"
}

step "0. Confirm the server is reachable"
curl -sf --max-time 3 "$BASE_URL/health" > /dev/null || die "server not reachable at $BASE_URL - start it first (./gradlew :server:run) or set BASE_URL"
info "server is up at $BASE_URL"

step "1. Log in as admin"
ADMIN_TOKEN=$(curl -s -X POST "$BASE_URL/auth/login" -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | jq -r '.accessToken')
[[ "$ADMIN_TOKEN" != "null" && -n "$ADMIN_TOKEN" ]] || die "admin login failed"
info "logged in"

step "2. Create a demo store"
STORE_ID=$(curl -s -X POST "$BASE_URL/stores" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"Demo Store","address":{"line1":"1 Demo St","city":"Demo City","postalCode":"00000","country":"US"},"timezone":"America/New_York","currency":"USD"}' \
  | jq -r '.id')
[[ -n "$STORE_ID" && "$STORE_ID" != "null" ]] || die "store creation failed"
info "storeId=$STORE_ID"

# ------------------------------------------------------------------
# Product catalog seed
# ------------------------------------------------------------------
step "3. Import demo products from sample-products.csv"
PRODUCT_UPLOAD_RESP=$(curl -s -X POST "$BASE_URL/products/import/upload" -H "Authorization: Bearer $ADMIN_TOKEN" \
  -F "file=@$SCRIPT_DIR/sample-products.csv;type=text/csv")
PRODUCT_FILE_ID=$(echo "$PRODUCT_UPLOAD_RESP" | jq -r '.fileId')
[[ -n "$PRODUCT_FILE_ID" && "$PRODUCT_FILE_ID" != "null" ]] || die "product CSV upload failed: $PRODUCT_UPLOAD_RESP"
info "uploaded, fileId=$PRODUCT_FILE_ID"

PRODUCT_MAPPING='{"mapping":{"SKU":"sku","NAME":"name","PRICE":"price","DESCRIPTION":"description","TAX_CATEGORY":"taxCategory","BARCODE":"barcode","CATEGORY":"category","IN_STOCK":"inStock"}}'

PRODUCT_DRY_RUN=$(curl -s -X POST "$BASE_URL/products/import/$PRODUCT_FILE_ID/dry-run" -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" -d "$PRODUCT_MAPPING")
info "dry-run preview: $(echo "$PRODUCT_DRY_RUN" | jq -c '[.outcomes[] | {rowNumber, action}]')"

PRODUCT_JOB_ID=$(curl -s -X POST "$BASE_URL/products/import/$PRODUCT_FILE_ID/start" -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" -d "$PRODUCT_MAPPING" | jq -r '.id')
[[ -n "$PRODUCT_JOB_ID" && "$PRODUCT_JOB_ID" != "null" ]] || die "product import start failed"

PRODUCT_JOB=$(poll_job "products/import" "$PRODUCT_JOB_ID")
[[ "$(echo "$PRODUCT_JOB" | jq -r '.status')" == "COMPLETED" ]] || die "product import job did not complete: $PRODUCT_JOB"
info "product import completed: created=$(echo "$PRODUCT_JOB" | jq -r '.createdCount') updated=$(echo "$PRODUCT_JOB" | jq -r '.updatedCount') errored=$(echo "$PRODUCT_JOB" | jq -r '.erroredCount')"

# ------------------------------------------------------------------
# Historical sales seed - generated inline since it needs the store id created above.
# DEMO-ORD-1 has two line items (rows share an orderRef, demonstrating grouping); its second row
# leaves storeId/soldAt/paymentMethod/total blank since only the group's first row supplies them
# (see DATA_MIGRATION.md). DEMO-ORD-4 references an unknown SKU on purpose, so the dry-run preview
# below demonstrates the UNMATCHED path, not just the happy path.
# ------------------------------------------------------------------
step "4. Generate demo historical sales CSV for storeId=$STORE_ID"
SALES_CSV=$(mktemp)
trap 'rm -f "$SALES_CSV"' EXIT
cat > "$SALES_CSV" <<CSV
orderRef,storeId,sku,qty,unitPrice,soldAt,paymentMethod,total,subtotal,tax,paymentRef,soldBy
DEMO-ORD-1,$STORE_ID,DEMO-WIDGET-001,2,9.99,2025-11-01T10:00:00Z,CASH,37.08,34.48,2.60,,demo-cashier
DEMO-ORD-1,,DEMO-GADGET-002,1,14.50,,,,,,,
DEMO-ORD-2,$STORE_ID,DEMO-GIZMO-003,3,4.25,2025-11-15T14:30:00Z,CARD,13.77,12.75,1.02,txn-demo-1,demo-cashier
DEMO-ORD-3,$STORE_ID,DEMO-WIDGET-001,1,9.99,2025-12-05T09:15:00Z,CASH,10.79,9.99,0.80,,demo-cashier
DEMO-ORD-4,$STORE_ID,SKU-DOES-NOT-EXIST,1,5.00,2025-12-10T09:15:00Z,CASH,5.00,5.00,0.00,,demo-cashier
CSV
info "wrote $(($(wc -l < "$SALES_CSV") - 1)) sample rows to a temp CSV"

step "5. Import demo historical sales"
SALES_UPLOAD_RESP=$(curl -s -X POST "$BASE_URL/sales-import/upload" -H "Authorization: Bearer $ADMIN_TOKEN" \
  -F "file=@$SALES_CSV;type=text/csv")
SALES_FILE_ID=$(echo "$SALES_UPLOAD_RESP" | jq -r '.fileId')
[[ -n "$SALES_FILE_ID" && "$SALES_FILE_ID" != "null" ]] || die "sales CSV upload failed: $SALES_UPLOAD_RESP"
info "uploaded, fileId=$SALES_FILE_ID"

SALES_MAPPING='{"mapping":{"ORDER_REFERENCE":"orderRef","STORE_ID":"storeId","SKU":"sku","QUANTITY":"qty","UNIT_PRICE":"unitPrice","SOLD_AT":"soldAt","PAYMENT_METHOD":"paymentMethod","TOTAL_AMOUNT":"total","SUBTOTAL":"subtotal","TAX_AMOUNT":"tax","PAYMENT_REFERENCE":"paymentRef","SOLD_BY":"soldBy"}}'

SALES_DRY_RUN=$(curl -s -X POST "$BASE_URL/sales-import/$SALES_FILE_ID/dry-run" -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" -d "$SALES_MAPPING")
info "dry-run groups: $(echo "$SALES_DRY_RUN" | jq -c '[.groups[] | {orderReference, importable, itemCount}]')"

SALES_JOB_ID=$(curl -s -X POST "$BASE_URL/sales-import/$SALES_FILE_ID/start" -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" -d "$SALES_MAPPING" | jq -r '.id')
[[ -n "$SALES_JOB_ID" && "$SALES_JOB_ID" != "null" ]] || die "sales import start failed"

SALES_JOB=$(poll_job "sales-import" "$SALES_JOB_ID")
[[ "$(echo "$SALES_JOB" | jq -r '.status')" == "COMPLETED" ]] || die "sales import job did not complete: $SALES_JOB"
info "sales import completed: imported=$(echo "$SALES_JOB" | jq -r '.importedCount') skippedUnmatched=$(echo "$SALES_JOB" | jq -r '.skippedUnmatchedCount')"

step "6. Fetch the reconciliation report"
RECON=$(curl -s "$BASE_URL/sales-import/jobs/$SALES_JOB_ID/reconciliation" -H "Authorization: Bearer $ADMIN_TOKEN")
echo "$RECON" | jq '.'

step "Done"
echo "Demo store:   $STORE_ID"
echo "Product job:  $PRODUCT_JOB_ID"
echo "Sales job:    $SALES_JOB_ID"
