# Game-day drills

Scripted incident rehearsals: fire a **real** incident against a running server (via the actual
admin API, not a mock), then walk the linked runbook's detection/response/recovery steps,
asserting each one actually behaves as documented — not just re-reading the runbook and imagining
it works.

## `simulate-terminal-offline.sh`

Drills `docs/runbooks/terminal-offline.md`: registers a printer, prints a baseline receipt,
flips the printer OFFLINE via `PATCH /printers/{id}/status` (the same call a real
driver/spooler-failure integration would make), confirms the `posly_printers_offline` gauge
reacts, confirms a print job queues instead of failing while offline, then recovers and confirms
printing resumes.

```bash
./gradlew :server:run &     # start a local server
scripts/gameday/simulate-terminal-offline.sh
```

Run this against a **fresh** server instance (or at least one with no other offline printers
already registered) — `PrinterRegistryService` is in-memory, so leftover state from a previous
manual test or a previous drill run will throw off the "gauge returns to exactly 0" check at the
end. Restart the server between runs if in doubt.

## Why there's no `simulate-payment-gateway-down.sh` or a device/heartbeat drill

- **Payment gateway:** every environment runs `SimulatorPaymentGateway`, which only fails
  on-demand via a constructor parameter (`transientFailuresBeforeSuccess`) wired up in
  `Application.kt` at process startup — there's no runtime API to toggle it, so a live drill
  against a running server can't trigger a gateway failure the way this script triggers a printer
  failure. See `docs/runbooks/payment-gateway-down.md`'s Known Limitations.
- **Device/terminal heartbeat offline:** `DeviceHealthStatus` is *derived* from heartbeat
  staleness (`HEARTBEAT_OFFLINE_THRESHOLD_SECONDS = 300L`, not configurable), not directly
  settable like `PrinterStatus` — a live drill would need to enroll a device (a multi-step
  pairing-code flow) and then genuinely wait 5+ minutes with no heartbeat for it to flip
  `OFFLINE`. Feasible as a slower, separate drill if the 5-minute wait is acceptable during a
  scheduled game day; not scripted here to keep this drill fast enough to run routinely.

Both are documented in their respective runbooks' Known Limitations rather than faked with a
script that doesn't actually exercise real failure behavior.
