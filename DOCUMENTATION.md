# Documentation Map & Review Process — posly

## Index of what exists and why

| Doc | Audience | Covers |
|---|---|---|
| [README.md](README.md) | Developers | Module map, how to build/run/test each target |
| [openapi.yaml](openapi.yaml) (served at `GET /docs` / `GET /openapi.yaml`) | API integrators | Every HTTP endpoint's request/response shape, worked examples for key flows |
| [ADMIN_GUIDE.md](ADMIN_GUIDE.md) | Store/fleet admins | Day-to-day store, user, device, tax, flag, key, secret, backup, reporting administration |
| [STORE_ONBOARDING.md](STORE_ONBOARDING.md) | Store/fleet admins | One-time new-store setup sequence |
| [SUPPORT_FAQ.md](SUPPORT_FAQ.md) | Cashiers/store staff | Plain-language answers to common in-store questions |
| [DATA_MIGRATION.md](DATA_MIGRATION.md) | Data migration engineers | Bulk product/historical-sales CSV import, dry-run, reconciliation, rollback |
| [API_KEYS.md](API_KEYS.md) | Integrations admins | API key creation, scopes, rotation, usage logs |
| [ACCESSIBILITY.md](ACCESSIBILITY.md) | Frontend engineers | WCAG 2.1 AA conventions for the Compose UI |
| [PERFORMANCE.md](PERFORMANCE.md) | Frontend engineers | Compose UI performance budgets |
| [E2E_TESTING.md](E2E_TESTING.md) | QA/engineers | End-to-end test coverage, CI gating, synthetic monitoring |
| [SECURITY_COMPLIANCE.md](SECURITY_COMPLIANCE.md) | Security/compliance | PCI DSS boundary, secrets management, pen-test plan |
| [DR_RUNBOOK.md](DR_RUNBOOK.md) | On-call/SRE | Backup/restore disaster-recovery procedure |
| `docs/runbook-deploy.md` | On-call/SRE | Deploy, smoke test, rollback |
| `docs/runbooks/*.md` | On-call/SRE | Per-incident-class response steps (see table below) |
| `docs/oncall/*.md` | On-call/SRE | Escalation policy, contacts, rotation schedule |
| `docs/postmortems/*.md` | On-call/SRE | Postmortem process and template |

### Runbooks specifically

| Runbook | Incident class |
|---|---|
| `docs/runbooks/terminal-offline.md` | POS terminal/printer offline |
| `docs/runbooks/payment-gateway-down.md` | Card payment gateway unreachable/erroring |
| `docs/runbooks/capacity-scale-incidents.md` | Server load, autoscaling, heavy-reporting shedding |
| `docs/runbooks/observability-alerts.md` | Generic auth/payments alert response, tracing |
| `docs/runbooks/auth-sso-incidents.md` | Login/MFA/SSO failure spikes, JWT rotation fallout |
| `docs/runbooks/offline-sync-conflicts.md` | Offline-sale sync conflicts backlog |

## When to update which doc

- **Added or changed an HTTP endpoint** → update `openapi.yaml` (both the path/schema and, if it's
  one of the flows with a worked example, the example payload). CI runs
  `openapi-spec-validator` against it on every PR (`.github/workflows/ci.yml`'s `lint-and-test`
  job) — a structurally invalid spec fails the build, but a spec that's merely *stale* (an endpoint
  changed and the YAML didn't) won't be caught automatically; this is a **reviewer** responsibility
  (see checklist below), not a tooling one.
- **Added a new incident class / alert** → add a runbook under `docs/runbooks/`, matching the
  existing structure (Scope, Detection, Response steps, Known limitations — see any existing
  runbook as a template), and wire its `runbook_url` into the relevant Prometheus alert annotation
  (`infra/observability/prometheus/alerts.yml`) if one exists.
- **Changed store/user/device/tax/flag/key/secret/backup admin behavior** → update
  [ADMIN_GUIDE.md](ADMIN_GUIDE.md).
- **Changed anything a cashier would notice** (checkout flow, refund rules, offline behavior,
  shift variance threshold) → update [SUPPORT_FAQ.md](SUPPORT_FAQ.md).
- **Added a new bulk-import capability** → update [DATA_MIGRATION.md](DATA_MIGRATION.md).

## Review process

1. **Docs live in the same PR as the code change that motivates them.** There's no separate
   documentation-only review cadence — a PR that changes an endpoint's shape, an incident's
   detection signal, or an admin-facing behavior should update the relevant doc in the same PR, and
   reviewers should treat a missing doc update the same as a missing test: request changes.
2. **Runbook accuracy is verified by tabletop exercise, not just by reading.** Periodically (and
   whenever an incident actually invokes one), walk the runbook's exact commands against a real
   running instance and confirm they still work — don't assume prose that reads correctly still
   matches the code. `docs/runbooks/payment-gateway-down.md` was tabletop-validated this way (every
   command/endpoint/role confirmed against the live server) as part of the same effort that added
   the two new runbooks above; do the same for another runbook next time significant auth,
   payments, capacity, or sync code changes.
3. **The OpenAPI spec is CI-enforced for validity, not accuracy.** `openapi-spec-validator` catches
   a malformed spec (bad YAML, wrong OpenAPI structure) but has no way to know if a schema still
   matches the Kotlin DTO it describes. When touching a route's request/response DTO, grep
   `openapi.yaml` for its schema name and update it in the same PR.
4. **No dedicated technical-writer review step exists.** Documentation quality review happens as
   part of normal code review, by whoever reviews the PR — there's no separate sign-off gate.

## Known limitations (explicitly disclosed, not fabricated)

- **No automated staleness detection.** Nothing fails CI if `openapi.yaml` describes a field that
  no longer exists, or a runbook references a route that moved — the only enforcement is
  structural spec validity (task 3 above) and reviewer diligence (task 1). A doc-drift linter that
  cross-checks DTOs against the spec would close this gap but doesn't exist today.
- **This process document itself is not automatically kept in sync** with new docs added after it
  — if you add a new root-level doc or runbook, add it to the index table above in the same PR.
