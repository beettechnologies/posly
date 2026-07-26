# Disaster Recovery Runbook — posly

## Scope

Covers backup and restore of the posly application database (core transactional entities: stores,
tax profiles, products, users, orders, shifts — see `server/src/main/kotlin/com/beettechnologies/posly/db/Tables.kt`).
Derived/regenerable state (reporting aggregates, realtime caches, audit log, finance-report
schedules, offline sync state) is intentionally out of scope for backup/restore — it's either
in-memory-only by design or fully rebuildable from the core tables above.

## RPO / RTO

- **RPO (Recovery Point Objective): 24 hours.** Backups run nightly (`BackupService`'s
  self-scheduling loop, same idiom as `ReportingService`/`FinanceReportService`). Worst case, a
  disaster loses up to one day of writes. Lower it by shortening `backupIntervalMillis`, at the
  cost of more frequent backup I/O.
- **RTO (Recovery Time Objective): 30 minutes.** Target wall-clock time from "declare an incident"
  to "application serving traffic again against the restored database." See **Drill Results**
  below for the actual measured time from this session's test drill.

## Backup

- **Schedule:** nightly, via `BackupService` (`server/src/main/kotlin/com/beettechnologies/posly/backup/BackupService.kt`).
- **Storage:** a local filesystem directory (`backup.directory` in `application.conf`, default
  `build/backups`). **This is not an object-store/S3 integration** — deliberately not simulated,
  since fabricating a fake AWS/GCS integration would misrepresent what this deployment actually
  does (consistent with this codebase's practice of never faking a vendor integration it doesn't
  have). For a real deployment, sync this directory to real object storage (e.g. `aws s3 sync`) as
  a follow-up step outside this application.
- **Encryption:** **not applied at the application layer.** No fake encryption service is wired in.
  For a real deployment, either (a) encrypt in transit/at rest at the storage layer (S3 SSE, disk
  encryption), or (b) pipe the dump through `gpg --symmetric` before writing it to the backup
  directory. This is a deployment-time responsibility, not something `BackupService` fabricates.
- **Mechanism:**
  - **PostgreSQL** (the real production target): shells out to the standard `pg_dump` tool. Requires
    `pg_dump` on the host running backups.
  - **H2** (this environment's dev/test database — see `TestDatabase.kt`'s comment on why real
    Postgres/Testcontainers aren't reachable in this sandbox): uses H2's built-in `SCRIPT TO`
    command, run in-process — no external tool needed.
- **Validation:** after writing the artifact, `BackupService` re-reads it and checks it's non-empty
  and contains recognizable SQL (`CREATE TABLE`/`INSERT INTO`) before recording it as `validated`.
  This is a sanity check, not a full test restore — that's what a DR drill is for (below).
- **Metadata & checksum:** every run records a `BackupMetadata` (id, timestamp, size, SHA-256
  checksum, file path, status, validated) via `GET /ops/backups` (ADMIN-only).

## Manual restore procedure

1. Identify the backup to restore: `GET /ops/backups` (ADMIN token required), pick the most recent
   `validated: true` entry with the desired timestamp.
2. Provision (or reuse) a **separate target database** — never the application's own live database.
   `RestoreService` refuses (`RestoreResult.RefusedProductionTarget`) if the target JDBC URL matches
   the app's own configured `database.jdbcUrl`, precisely to prevent this mistake.
3. Call `POST /ops/backups/{id}/restore` with `{"targetJdbcUrl": "<target connection string>"}`.
   - Against Postgres, this runs `psql <target> -f <dump file>`. Requires `psql` on the host.
   - Against H2, this runs `RUNSCRIPT FROM '<dump file>'` against a fresh connection to the target.
4. Inspect the response `RestoreDrillResultResponse`: `rowCountsMatched` compares the restored
   target's per-table row counts against the live source at restore time. For a Postgres target,
   automated row-count comparison isn't wired up in this environment (no local Postgres to test
   against) — verify manually with `SELECT count(*) FROM <table>` on both sides instead.
5. Point the application's `DATABASE_URL` at the restored database and restart the process (or, for
   a full incident, stand up a fresh app instance against it) to resume serving traffic.

## DR drill (what "tested" means here)

A drill = steps 1–4 above, run against a disposable target (not a real incident), verifying the
restored data matches the source. `RestoreServiceTest`/`BackupServiceTest` automate this exact
sequence in H2. The **Drill Results** section below records the actual measured outcome of a real
run of that sequence in this environment.

## Drill Results

_Filled in from an actual run against a live `:server:run` process backed by a file-based H2
database (`DATABASE_URL=jdbc:h2:file:/tmp/posly-drill-db;MODE=PostgreSQL`), not a hypothetical
estimate. Seeded with 3 demo users (from startup seeding) and 3 stores created via the real
`POST /stores` API, then driven entirely through the `/ops/backups/*` HTTP endpoints as an admin
would in a real incident._

| Run | Backup size | Checksum verified | Restore target | Row counts matched | Duration |
|---|---|---|---|---|---|
| 2026-07-26 live drill | 6,656 bytes | `33fb8c45c8f60464824e07f825ca107682769c3b9637bb900b6cd539f5b6983f` (validated: true) | `jdbc:h2:mem:posly-dr-sandbox` (disposable, separate from the live app's own database) | true — `stores: 3, users: 3, tax_profiles: 0, products: 0, orders: 0, shifts: 0` matched source exactly | 41 ms |

Post-drill, the live application was confirmed to still be serving its own database unaffected
by the restore (existing stores still listed, a new store could still be created) — i.e. the
restore-into-sandbox drill has no side effect on the running production instance, as designed.

This measured 41ms restore time is well within the 30-minute RTO target above; at this data
volume the RTO is dominated by human/process steps (identifying the incident, provisioning a
target, pointing `DATABASE_URL` at it) rather than the mechanical restore itself.

## Known limitations (explicitly disclosed, not fabricated)

- No object-store (S3/GCS) integration — local filesystem only.
- No application-level backup encryption — a deployment-time responsibility (see Backup section).
- Postgres dumps require `pg_dump`/`psql` on the host; this sandbox has neither installed, so only
  the H2 code path has been exercised end-to-end here. The Postgres code path uses the same
  well-established real tools (`pg_dump`/`psql`), just unverified in this specific environment.
- Automated row-count integrity verification after restore is only wired up for H2 targets; a
  Postgres restore's row counts must currently be checked manually (step 4 above).
