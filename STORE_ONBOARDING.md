# Store Onboarding — posly

A step-by-step walkthrough for taking a new store from zero to ready-to-sell. For ongoing
administration of a store that's already live, see [ADMIN_GUIDE.md](ADMIN_GUIDE.md) instead — this
doc is the one-time setup sequence.

Each step includes the exact API call; `scripts/seed/seed-demo-data.sh` runs an abbreviated,
non-interactive version of steps 2–7 end to end if you just want a demo store to poke at rather
than a real one.

## 0. Prerequisites

- A running server and an ADMIN account (`POST /auth/login`).
- Decide the store's tax rules up front — you'll need them for step 1.

```bash
ADMIN_TOKEN=$(curl -s -X POST "$BASE_URL/auth/login" -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | jq -r '.accessToken')
```

## 1. Create a tax profile

Every store needs a tax profile before (or at) creation — decide the rate lines, pricing mode
(`EXCLUSIVE` or `INCLUSIVE`), and rounding mode up front.

```bash
TAX_PROFILE_ID=$(curl -s -X POST "$BASE_URL/tax-profiles" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"NY Sales Tax","rates":[{"name":"State","ratePercent":4.0},{"name":"City","ratePercent":4.5}],"pricingMode":"EXCLUSIVE","roundingMode":"HALF_UP"}' \
  | jq -r '.id')
```

If two stores genuinely share the same tax rules (common for stores in the same jurisdiction),
reuse the same `taxProfileId` rather than creating a duplicate profile per store.

## 2. Create the store

```bash
STORE_ID=$(curl -s -X POST "$BASE_URL/stores" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{
    "name": "Downtown",
    "address": {"line1": "1 Main St", "city": "New York", "postalCode": "10001", "country": "US"},
    "timezone": "America/New_York",
    "currency": "USD",
    "taxProfileId": "'"$TAX_PROFILE_ID"'",
    "locale": "en-US"
  }' | jq -r '.id')
```

Optionally upload a branding logo now (it appears on printed/emailed receipts):

```bash
curl -X POST "$BASE_URL/stores/$STORE_ID/logo" -H "Authorization: Bearer $ADMIN_TOKEN" -F "file=@logo.png"
```

## 3. Invite the store's staff

```bash
curl -X POST "$BASE_URL/users/invite" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"username":"store-manager","email":"manager@example.com","roles":["MANAGER"],"storeIds":["'"$STORE_ID"'"]}'
```

Repeat per cashier with `"roles":["CASHIER"]`. The invite response's `inviteToken` (or the emailed
link, if an email gateway is configured) lets each person set their own password via
`POST /users/accept-invite` — nobody needs to share the admin account.

## 4. Pair the store's POS terminal(s)

```bash
CODE=$(curl -s -X POST "$BASE_URL/devices/create-pair-code" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"storeId":"'"$STORE_ID"'"}' | jq -r '.code')
```

Enter `$CODE` into the terminal app itself (or, for a manual/dev enroll):

```bash
curl -X POST "$BASE_URL/devices/enroll" -H "Content-Type: application/json" \
  -d '{"code":"'"$CODE"'","name":"Front Counter"}'
```

The response's `clientId`/`clientSecret` are the device's own long-lived credentials (shown once) —
they're what let the terminal sync offline sales later
(`docs/runbooks/offline-sync-conflicts.md`) even without a cashier logged in.

## 5. Register printers

```bash
curl -X POST "$BASE_URL/printers" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"storeId":"'"$STORE_ID"'","name":"Front Counter","connectionType":"USB"}'
```

## 6. Load the initial product catalog

For a handful of products, use `POST /products` directly (see [ADMIN_GUIDE.md](ADMIN_GUIDE.md)).
For a real catalog migration (dozens to thousands of SKUs) or bringing over historical sales for
continuity, use the bulk import tooling instead — see [DATA_MIGRATION.md](DATA_MIGRATION.md) for
the full CSV-mapping/dry-run/reconciliation workflow (`/products/import`, `/sales-import`).

## 7. Smoke-test before going live

Ring up one real test sale end to end, exactly as a cashier would, before handing the store over:

```bash
CART_ID=$(curl -s -X POST "$BASE_URL/carts" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"storeId":"'"$STORE_ID"'"}' | jq -r '.id')
curl -X POST "$BASE_URL/carts/$CART_ID/items" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"productId":"'"$PRODUCT_ID"'","quantity":1}'
ORDER_ID=$(curl -s -X POST "$BASE_URL/carts/$CART_ID/checkout" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"idempotencyKey":"onboarding-smoke-test"}' | jq -r '.id')
curl -X POST "$BASE_URL/orders/$ORDER_ID/payments" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"method":"CASH","amount":<order total>}'
curl -X POST "$BASE_URL/orders/$ORDER_ID/print" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"printerId":"'"$PRINTER_ID"'"}'
```

Confirm: the printer actually produced a receipt (or the job queued cleanly if you deliberately
left the printer `OFFLINE` for the test), and `GET /reports/sales?storeId=...&period=DAILY` shows
the test sale.

## Go-live checklist

- [ ] Tax profile created and attached to the store
- [ ] Store created with correct timezone/currency/locale
- [ ] Branding logo uploaded (if applicable)
- [ ] Manager + cashier accounts invited and accepted
- [ ] At least one POS terminal paired and enrolled
- [ ] At least one printer registered
- [ ] Initial catalog loaded (spot-check a handful of prices/tax categories)
- [ ] One real test sale completed, paid, and printed successfully
- [ ] Test sale doesn't linger in real reporting - refund it or note it was a test if it needs to
      stay for demonstration purposes

## Known limitations (explicitly disclosed, not fabricated)

- **No "store setup wizard" exists in the admin UI** — every step above is a discrete admin-UI
  screen (Manage Stores, Manage Users, Pair a Device, etc.) or a direct API call; there's no single
  guided flow that walks an admin through all of them in sequence.
- **The test sale in step 7 is not automatically cleaned up.** If you don't want it skewing
  real reporting, refund it (`POST /orders/{id}/refund`) once you've confirmed it worked.
