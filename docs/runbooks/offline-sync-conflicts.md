# Offline Sync Conflicts Runbook

## Scope

Covers a backlog of unresolved conflicts from `POST /sync/offline-sales` — sales rung up on a POS
device while it was offline, that didn't cleanly map onto the current catalog when the device
reconnected. Does **not** cover a device/terminal being currently offline (see
`docs/runbooks/terminal-offline.md`, which covers detection of the offline state itself, not what
happens once it reconnects) or payment gateway failures (see
`docs/runbooks/payment-gateway-down.md`).

## What a sync conflict is

`OfflineSyncService.ingestBatch` (`server/src/main/kotlin/com/beettechnologies/posly/sync/OfflineSyncService.kt`)
re-validates every offline-rung sale against the *current* catalog when a device reconnects and
replays its batch. A conflict is recorded (`AuditEvent.OFFLINE_SALE_CONFLICT`) whenever a sale's
captured item data no longer matches:

- `PRODUCT_NOT_FOUND` — the SKU sold offline no longer resolves to any product (deleted, or a
  fresh device that never synced the current catalog). Always rejected regardless of policy —
  there's nothing to re-price against.
- `PRICE_CHANGED` — the price at time of sale differs from the product's current price.
- `TAX_CATEGORY_CHANGED` — the tax category at time of sale differs from the product's current one.
- `PAYMENT_MISMATCH` — the offline-captured tenders don't cover the (possibly re-priced) total.

The batch's `conflictPolicy` decides the outcome for `PRICE_CHANGED`/`TAX_CATEGORY_CHANGED`
conflicts: `REJECT` (nothing persisted, conflict recorded for review — the default), `MAP`
(persisted, re-priced using today's catalog), or `CONVERT` (persisted using exactly what the
customer was actually charged offline). A rejected sale produces **no order at all** — the
customer was charged on the device (a receipt may have printed), but the server has no record of
it until someone resolves the conflict.

## Detection

```bash
# List every offline-sale conflict awaiting review
curl -H "Authorization: Bearer $ADMIN_TOKEN" "$BASE_URL/sync/conflicts" | jq '.'

# Cross-check against the audit log for a specific device or time window
curl -H "Authorization: Bearer $ADMIN_TOKEN" "$BASE_URL/ops/audit-log?event=OFFLINE_SALE_CONFLICT"
```

There is no alert wired to conflict volume (see Known limitations) — a growing `/sync/conflicts`
list is typically noticed during routine admin review, or reported by a store manager whose
cashier mentioned "the app said it synced but the sale isn't showing up in reports."

## Response steps

1. Pull `GET /sync/conflicts` and group by `reason`. A cluster of `PRODUCT_NOT_FOUND` or
   `PRICE_CHANGED` entries all referencing the same SKU(s), all from the same device/store, and all
   with `processedAt` timestamps clustered right after a catalog change (a price update, a product
   deletion, a bulk `/products/import` run) is the common case: the device was offline *during*
   that catalog change and is only now reconciling against it.
2. **`PRODUCT_NOT_FOUND`** — confirm via `GET /products?sku=...`-style lookup (or `GET /search`)
   whether the SKU still exists under a different id, was genuinely deleted, or was never synced
   to this device in the first place (a brand-new device paired just before going offline). There
   is no automated re-import path for a rejected sale — see Known limitations; the sale must be
   manually reconstructed (e.g. a manager rings it up again as a same-day correction) if the
   customer needs a receipt/record and the original charge is confirmed to have happened.
3. **`PRICE_CHANGED` / `TAX_CATEGORY_CHANGED`** — decide whether to accept the customer's original
   offline price (re-ingest the same batch with `conflictPolicy: CONVERT`) or the store's current
   price (`conflictPolicy: MAP`). This is a business decision, not a technical one — check with the
   store manager which price the customer actually agreed to pay. Re-submitting the exact same
   `idempotencyKey` values with a different `conflictPolicy` is safe: a **rejected** sale was never
   persisted, so it isn't itself idempotency-locked yet; re-ingesting it under `MAP`/`CONVERT` will
   process it as new, not as a replay.
4. **`PAYMENT_MISMATCH`** — the recomputed total exceeds what was actually tendered offline (most
   often caused by `MAP`-repricing pushing the total up past the captured payment amount). Confirm
   with the store whether the customer was undercharged (in which case this is a genuine shortfall
   to reconcile manually — there's no automatic partial-payment fallback here) versus a data-entry
   mistake in the original offline batch.
5. Once resolved (or explicitly written off as unrecoverable), there's no "dismiss" action on a
   conflict record — it remains in `GET /sync/conflicts` permanently (see Known limitations); track
   resolution status outside the system (e.g. in the incident/ticket notes) rather than expecting
   the list to shrink.
6. If conflicts are arriving in volume across **many** devices/stores simultaneously rather than
   one device catching up after being offline, suspect a recent catalog-wide change (a bulk price
   update, a `/products/import` run) rather than device-specific connectivity — check
   `GET /products/import/jobs/{jobId}` / `GET /ops/audit-log?event=PRODUCT_IMPORT_COMPLETED` for a
   recent bulk import around the same time.

## Known limitations (explicitly disclosed, not fabricated)

- **A rejected sale has no persisted record at all**, beyond the conflict entry itself — there's no
  "quarantine" table holding the original cart/order shape for one-click replay. Recovering it
  means either re-submitting the original batch with a different `conflictPolicy` (if you still
  have it) or manually reconstructing the sale.
- **The conflict ledger (`OfflineSyncService`'s in-memory `ledger`) never shrinks.** There's no
  resolve/dismiss/acknowledge action — `GET /sync/conflicts` returns every conflict ever recorded
  for the life of the process, growing unbounded. It's also in-memory only: a server restart clears
  it entirely, along with the idempotency ledger backing replay detection (see next point).
- **Idempotency is also in-memory**, so a server restart between a device's original offline batch
  submission and its resolution means the same `idempotencyKey` is no longer recognized as already
  processed — a device that retries its sync after a restart could double-submit. No cross-restart
  persistence exists for this ledger today.
- **No alert on conflict volume or backlog age.** Unlike terminal-offline or capacity incidents,
  nothing pages when `/sync/conflicts` grows — detection is manual/reactive today.
- **No bulk-resolve tooling.** Each conflict is reviewed and (if needed) re-ingested one batch at a
  time; there's no admin UI or endpoint to re-submit many conflicting sales under a single chosen
  policy at once.
