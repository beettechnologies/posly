# Data Migration & Seed Content Tools — posly

## Scope

Tools to map and import legacy data with a dry-run preview and a post-import reconciliation
report. Two importers exist, sharing the same shape (upload → map columns → dry-run → async job →
rollback) but each with its own hardcoded column-mapping enum rather than a shared generic
mapping-config abstraction — deliberately, since two concrete mappings are simpler to reason about
than one generic layer built for a third importer that doesn't exist yet:

- **Product catalog import** (`/products/import`, pre-existing) — `ProductImportField`,
  `catalog/ProductImportService.kt`.
- **Historical sales import** (`/sales-import`, new in this round) — `SalesImportField`,
  `migration/SalesImportService.kt`. This is the ticket's main deliverable; the rest of this doc
  focuses on it.

Plus `scripts/seed/` — demo/seed-content scripts that drive both importers' real APIs (see
`scripts/seed/README.md`), and this doc's Known limitations section, which doubles as rollback
guidance for both importers.

## How historical sales import works

### CSV shape and column mapping

One row per **line item** (matching a typical legacy POS export) — rows sharing an
`ORDER_REFERENCE` value are grouped into a single historical order. `STORE_ID`, `SOLD_AT`,
`PAYMENT_METHOD`, and the totals fields are read only from each group's **first** row and assumed
consistent across the group; a continuation row only needs `ORDER_REFERENCE`, `SKU`, `QUANTITY`,
and `UNIT_PRICE` populated (see `scripts/seed/seed-demo-data.sh`'s `DEMO-ORD-1` for a worked
two-line-item example).

| Field | Required | Notes |
|---|---|---|
| `ORDER_REFERENCE` | yes | Groups rows into one order. |
| `STORE_ID` | yes | Must resolve via `GET /stores/{id}`. |
| `SKU` | yes | Must resolve via the current catalog (`ProductService.getProductBySku`). |
| `QUANTITY`, `UNIT_PRICE` | yes | Per line item. |
| `SOLD_AT` | yes | ISO-8601 instant; becomes the order's `checkedOutAt`. |
| `PAYMENT_METHOD` | yes | Recorded verbatim on the order's payment record. |
| `TOTAL_AMOUNT` | yes | Trusted as-is — see Known limitations. |
| `SUBTOTAL`, `TAX_AMOUNT` | no | Default to a computed sum / `0.0`. |
| `PAYMENT_REFERENCE`, `SOLD_BY` | no | Passed through to the payment record / order's `createdBy`. |

### Dry run

`POST /sales-import/{fileId}/dry-run` classifies every row `MATCHED` or `UNMATCHED` (this exact
wording, matching the ticket's acceptance criteria) and every order-reference group as
`importable` or not — a group is only importable if **every** row in it matched; one bad line item
skips the whole order rather than importing it partially. Common `UNMATCHED` reasons: unknown SKU,
unknown store, unparseable quantity/price/date.

### Running the import

`POST /sales-import/{fileId}/start` runs asynchronously (same pattern as product import — the
response returns immediately, poll `GET /sales-import/jobs/{jobId}`). For each importable group,
it builds a `Cart` with the historical `checkedOutAt`, constructs `CartTotals` directly from the
CSV's own subtotal/tax/total fields, then calls the same `OrderService.createOrder` +
`confirmPayment` used by checkout and offline sync — so a migrated order is indistinguishable from
a normal one in reporting, refunds, receipts, etc.

Before creating an order, the service checks `OrderService.getOrderByIdempotencyKey` using a key
derived from `storeId` + `orderReference` — re-running the same file (or the same order reference
across two different files) is detected and the group is marked `SKIPPED_ALREADY_IMPORTED`,
referencing the existing order, rather than creating a duplicate.

### Reconciliation report

`GET /sales-import/jobs/{jobId}/reconciliation` (only once the job is `COMPLETED`) — a distinct
artifact from the job summary, since the ticket calls this out as its own deliverable: counts
(imported / skipped-unmatched / skipped-already-imported) plus a sample of up to 20
`orderReference -> orderId` mappings, for manually spot-checking that specific legacy records
landed where expected.

### Rollback guidance

`POST /sales-import/jobs/{jobId}/rollback` deletes every order the job created
(`OrderService.deleteOrder` per `importedOrders` entry) and marks the job `rolledBack`. Same
constraints as product import's rollback:

- **Only the single most-recently-completed job** can be rolled back — if you've started a second
  import since, roll that one back first (or accept it) before you can roll back an earlier one.
- **Already-rolled-back jobs can't be rolled back again** (`AlreadyRolledBack`).
- **Orders that were `SKIPPED_ALREADY_IMPORTED`** (i.e. pre-existed from an earlier run) are *not*
  deleted by rollback — only orders this specific job actually created. Rolling back a job that
  mostly skipped-as-duplicate will have little visible effect; that's intentional, since those
  orders belong to whichever earlier job actually created them.
- **Rollback is a hard delete** (`OrderEventsTable` + `OrdersTable` rows removed), not a soft
  cancel/void — appropriate for undoing a bad migration run, not for reversing a real completed
  sale (use the existing refund flow for that).
- If a migration goes wrong **after** the safe rollback window (a second import already ran, or
  refunds/reports have since referenced the migrated orders), there is no automated remediation —
  identify the affected order ids from the reconciliation report and handle them manually (delete
  via `OrderService.deleteOrder` in a maintenance script, or leave them and correct downstream
  reports).

## Admin UI

**Import Historical Sales** (from the dashboard, ADMIN/MANAGER) → same 5-step wizard shape as
**Import Products** (`PICK_FILE → MAP_COLUMNS → DRY_RUN → RUNNING → SUMMARY`): pick a CSV, confirm
the auto-guessed column mapping, preview matched/unmatched order groups, run the import, then view
the summary with an optional **View Reconciliation Report** button and **Undo this import**.

## Seed / demo data

`scripts/seed/seed-demo-data.sh` — creates a demo store, imports `scripts/seed/sample-products.csv`
via the product importer, generates a small historical-sales CSV (including one deliberately
unmatched row) and imports it via the sales importer, then prints the reconciliation report. See
`scripts/seed/README.md`.

## Testing

- `SalesImportServiceTest.kt` (21 tests) — upload/dry-run validation, row grouping, matched/
  unmatched classification, full import creating real paid orders from the CSV's own totals,
  zero-total orders (imported without a payment confirmation), idempotent re-import detection,
  reconciliation report contents, rollback (including the most-recent-job and already-rolled-back
  constraints).
- `SalesImportRoutesTest.kt` (9 tests) — HTTP wiring: upload, dry-run, role enforcement
  (ADMIN/MANAGER only, cashier forbidden), full start → poll → reconciliation → rollback flow.
- `SalesImportWizardViewModelTest.kt` / `SalesImportWizardScreenTest.kt` (9 + 3 tests) — wizard
  state machine and UI against a fake `SalesImportApi`.

## Known limitations (explicitly disclosed, not fabricated)

- **Historical totals are trusted verbatim, not recomputed.** Unlike `OfflineSyncService` (which
  recomputes tax from the *current* catalog/tax profile, reasonable for an offline gap of hours),
  this importer builds `CartTotals` directly from the CSV's own subtotal/tax/total columns.
  Recomputing with today's tax rates would misrepresent what a customer was actually charged years
  ago — trusting the legacy system's own numbers is the more honest choice for a migration tool,
  at the cost of not validating that a row's total is internally consistent (e.g. it won't catch a
  CSV where subtotal + tax ≠ total; garbage in, garbage out for that one field).
- **A group is all-or-nothing.** One unmatched line item skips the entire order rather than
  importing the resolvable items — simpler to reason about than partial-order import, but means a
  single typo'd SKU on an otherwise-fine 10-line order drops all 10 lines from that run (they'll
  show up as `UNMATCHED` rows in the reconciliation-adjacent dry-run report, so they're visible,
  just not imported).
- **No per-store scoping on `ORDER_REFERENCE` uniqueness beyond the idempotency key.** The
  dedup/idempotency key is `storeId + orderReference`; the same reference reused for two genuinely
  different historical orders *within the same store* will silently collapse into one (the second
  run is treated as a re-import of the first, not an error).
- **Zero-total orders are recorded but left `PENDING`.** There's no payment to confirm for a fully
  comped/free historical sale, so `confirmPayment` is skipped entirely rather than forcing a
  zero-amount payment record; the order exists with `remainingBalance = 0` but never transitions to
  `PAID`. Acceptable for reconciliation purposes but worth knowing if a downstream report filters
  strictly on `status = PAID`.
- **Two hardcoded per-domain mapping enums, not a generic mapping-config system.** Adding a third
  importer means writing a third `*ImportField` enum and service, not configuring an existing
  generic one — a deliberate simplicity trade-off for two known importers rather than an
  abstraction built for a hypothetical third.
- **Whole-file-in-memory, same as product import.** No streaming/chunked parsing — fine for
  migration-sized batches, not for CSVs large enough to matter for JVM heap.
- **No historical accounts-receivable/partial-payment modeling.** Each imported order gets at most
  one payment record covering the full `TOTAL_AMOUNT`; a legacy system with actual split/partial
  tenders per historical sale would need the CSV pre-aggregated to a single total, since there's no
  `PAYMENT_AMOUNT` column separate from `TOTAL_AMOUNT`.
