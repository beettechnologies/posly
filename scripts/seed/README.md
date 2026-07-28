# Seed content / demo data

Populates a running server with realistic-looking demo data by driving the **real** import APIs
(`/products/import`, `/sales-import`) rather than writing directly to the database — so running
this script is itself a smoke test of the migration tooling from ticket 47, not just a fixture
loader.

## `seed-demo-data.sh`

```bash
./gradlew :server:run &        # start a local server
scripts/seed/seed-demo-data.sh
```

What it does, in order:

1. Logs in as the default seeded `admin` user and creates a "Demo Store".
2. Imports `sample-products.csv` (3 demo SKUs) via the product-import pipeline: upload → dry-run
   preview → async import → poll to completion.
3. Generates a demo historical-sales CSV in a temp file, using the store id from step 1 (it can't
   be a static fixture since the store id is created at runtime). Four order references: one
   two-line-item order (demonstrating row grouping — see `DATA_MIGRATION.md`), two normal
   single-line orders, and one deliberately referencing a SKU that doesn't exist, so the dry-run
   preview below exercises the UNMATCHED path too, not just the happy path.
4. Imports that CSV via `/sales-import`: upload → dry-run preview → async import → poll to
   completion → fetches and prints the reconciliation report.

Exit non-zero (via `die`) if the server isn't reachable, login fails, or either import job doesn't
reach `COMPLETED`.

## `sample-products.csv`

A static fixture — 3 rows, all designed to be `CREATED` (not `UPDATED`) on a fresh database. Also
useful on its own for manually exercising the product-import wizard's dry-run/upload UI without
writing a CSV by hand.

## Why there's no static `sample-sales.csv`

The sales-import CSV requires a real `storeId` that resolves via `GET /stores/{id}` (see
`SalesImportService.resolveGroupHeader`); a store created by a previous script run or by manual
testing won't exist in a fresh database, so a checked-in static CSV would either need a
hardcoded id that breaks on every fresh install, or force the script to `sed`-patch a checked-in
file at runtime anyway. Generating it inline after creating the demo store sidesteps that
entirely, at the cost of the CSV shape only being visible by reading the script rather than a
`.csv` file directly — worth it here since the store-id dependency is otherwise unavoidable.
