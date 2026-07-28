# Terminal / Printer Offline Runbook

## Scope

Covers a store's POS terminal (device) or receipt printer going offline: how it's detected, what
still works while it's down, and how to bring it back. Does **not** cover the payment gateway
itself being unreachable (see `docs/runbooks/payment-gateway-down.md`) or the server/infra layer
(see `docs/runbooks/capacity-scale-incidents.md`).

## What "offline" means here

Two independent registries, each with its own offline signal:

- **Printers** (`server/src/main/kotlin/com/beettechnologies/posly/printing/PrinterRegistryService.kt`):
  `PrinterStatus` is `ONLINE`/`OFFLINE`, set **explicitly** via `PATCH /printers/{id}/status` — a
  real printer driver/spooler integration would call this when it detects a failure; there's no
  heartbeat here because a printer isn't a security-sensitive credential holder the way a POS
  terminal is.
- **Devices/terminals** (`server/src/main/kotlin/com/beettechnologies/posly/devices/DeviceRegistryService.kt`):
  `DeviceHealthStatus` (`ONLINE`/`OFFLINE`/`NEVER_SEEN`) is **derived** from `lastSeenAt` —
  a device is `OFFLINE` once its heartbeat (`POST /devices/heartbeat`) is more than
  `HEARTBEAT_OFFLINE_THRESHOLD_SECONDS` (5 minutes) stale.

## Detection

- **Alert:** `PoslyTerminalsOffline` (`infra/observability/prometheus/alerts.yml`) fires when
  `posly_printers_offline + posly_devices_offline > 0` for 10+ minutes — two Micrometer gauges
  registered in `Application.kt`, scraped fresh on every `/metrics` poll (not cached), so they
  always reflect current registry state.
- **Contextual data:** the alert alone doesn't say *which* store or device — that's a deliberate
  choice (a single low-cardinality gauge per fleet, not per-device, to keep the metric cheap).
  On page, immediately pull the specifics:
  ```bash
  curl -H "Authorization: Bearer $ADMIN_TOKEN" "$BASE_URL/printers?storeId=<if known>" | jq '.[] | select(.status=="OFFLINE")'
  curl -H "Authorization: Bearer $ADMIN_TOKEN" "$BASE_URL/devices?storeId=<if known>" | jq '.[] | select(.healthStatus=="OFFLINE")'
  ```
  If the store isn't known yet, omit `storeId` to list fleet-wide, or check
  `GET /feature-flags/audit-log`-style correlation via recent `DEVICE_ENROLLMENT_*` events at
  `GET /devices` — device audit trail is in-memory only (`DeviceRegistryService.listAuditTrail()`,
  not yet exposed as an HTTP route — see Known limitations).

## What still works while a terminal/printer is offline

- **A cashier can still check out** — carts/checkout don't depend on printer or device online
  status at all.
- **Print jobs queue instead of failing:** `PrintService.submitPrintJob` checks the printer's
  status *before* attempting a print — an `OFFLINE` printer is never even attempted, the job is
  immediately queued (`PrintJobResult.Queued`), so no cashier-facing error, no lost job. See
  `server/src/main/kotlin/com/beettechnologies/posly/printing/PrintService.kt:65-67`.
  `GET /orders/{id}/print`-adjacent job history (`PrintService.listJobs`) shows queued jobs once
  the printer is confirmed back online — they are **not automatically retried** by a background
  job (see Known limitations); the queued job's existence is the signal to retry the print
  manually via `POST /orders/{id}/print` once the printer is back.
- **A device going offline does not deprovision it** — its `clientId`/`clientSecret` stay valid.
  It resumes normal operation the moment its heartbeat resumes, with no re-pairing needed.

## Response steps

1. Acknowledge the page. Pull the specifics with the `curl` commands above.
2. **For a printer:** this is almost always a real-world physical issue (powered off, out of
   paper, USB/network disconnected) — the app has no way to distinguish these from here. Contact
   the store to check the hardware. Once it's fixed store-side and confirmed printing, flip the
   record back: `PATCH /printers/{id}/status {"status":"ONLINE"}`.
3. **For a device/terminal:** offline means no heartbeat for 5+ minutes — check:
   - Is the store's internet/network down? (Correlate with other stores in the same
     region/provider going offline around the same time.)
   - Is the device physically powered off / app crashed on the device? Contact the store.
   - Once connectivity/app is restored, the device's own heartbeat loop resumes automatically —
     no server-side action needed; `healthStatus` flips back to `ONLINE` on the next heartbeat.
4. If **multiple stores** report offline terminals simultaneously, treat this as a possible
   server-side or networking-layer incident instead of independent hardware failures — cross-check
   `docs/runbooks/capacity-scale-incidents.md`'s alarms (a server outage would look like "every
   terminal offline at once" from this signal, since heartbeats can't reach a down server).
5. Once resolved, confirm the alert clears (`posly_printers_offline + posly_devices_offline` back
   to `0`) and any queued print jobs have been retried.

## Known limitations (explicitly disclosed, not fabricated)

- **The offline gauges are fleet-wide totals, not per-store/per-device.** Good enough to trigger a
  page; the `curl` lookups above are how you find the specific offline terminal(s). A
  higher-cardinality per-device metric was deliberately not added to avoid an unbounded label set.
- **Queued print jobs are not auto-retried** when a printer comes back online — there's no
  background sweep of `PrintJobStatus.QUEUED` jobs in `PrintService`. Retrying is a manual
  `POST /orders/{id}/print` call today.
- **No dedicated audit events exist for "printer went offline" / "device went offline"** — device
  enrollment/deprovisioning is audited (`DeviceAuditEvent`), but health-status transitions are not.
  The gauge/alert is the only offline signal; there's no historical log of *when* a specific
  device flipped offline beyond its own `lastSeenAt` timestamp.
