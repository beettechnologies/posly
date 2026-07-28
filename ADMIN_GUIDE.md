# Admin Guide — posly

## Who this is for

Day-to-day operational tasks for an ADMIN or MANAGER managing a live posly deployment: stores,
users, devices/printers, tax, feature flags, API keys, secrets, backups, and reporting. For setting
up a **brand-new store from scratch**, start with [STORE_ONBOARDING.md](STORE_ONBOARDING.md)
instead — this guide assumes stores already exist and focuses on ongoing administration. For the
full endpoint reference (every route, request/response shape), see the published API docs
(`GET /docs`, or the raw spec at `GET /openapi.yaml` / [openapi.yaml](openapi.yaml)).

All examples below assume `$BASE_URL` (e.g. `http://localhost:8080`) and `$ADMIN_TOKEN` (from
`POST /auth/login`) are set.

## Getting started

```bash
ADMIN_TOKEN=$(curl -s -X POST "$BASE_URL/auth/login" -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | jq -r '.accessToken')
```

**`admin`/`admin123`, `manager`/`manager123`, `cashier`/`cashier123` are seeded demo accounts**
(`UserService`'s init block, only on a genuinely empty database) — see Known limitations before
relying on them anywhere beyond local development.

## Store management

```bash
# Create a store
curl -X POST "$BASE_URL/stores" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"Downtown","address":{"line1":"1 Main St","city":"New York","postalCode":"10001","country":"US"},"timezone":"America/New_York","currency":"USD","taxProfileId":"<tax-profile-id>"}'

# Upload a branding logo (shown on receipts)
curl -X POST "$BASE_URL/stores/$STORE_ID/logo" -H "Authorization: Bearer $ADMIN_TOKEN" -F "file=@logo.png"

# List / update / delete
curl -H "Authorization: Bearer $ADMIN_TOKEN" "$BASE_URL/stores"
curl -X PUT "$BASE_URL/stores/$STORE_ID" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" -d '{"currency":"EUR"}'
curl -X DELETE "$BASE_URL/stores/$STORE_ID" -H "Authorization: Bearer $ADMIN_TOKEN"
```

All store routes are ADMIN-only. See [DATA_MIGRATION.md](DATA_MIGRATION.md) for bulk-importing
products and historical sales into a store once it exists.

## User management

```bash
# Invite a new user (passwordless until they accept)
curl -X POST "$BASE_URL/users/invite" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"username":"jsmith","email":"jsmith@example.com","roles":["CASHIER"],"storeIds":["'"$STORE_ID"'"]}'
# -> response includes inviteToken (and emailDelivered, if the email gateway succeeded) - share
#    the accept-invite link with the user if email delivery isn't wired up in this environment.

# Change roles / store access / status
curl -X PATCH "$BASE_URL/users/$USER_ID/roles" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" -d '{"roles":["MANAGER"]}'
curl -X PATCH "$BASE_URL/users/$USER_ID/store-access" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" -d '{"storeIds":["'"$STORE_ID"'"]}'
curl -X PATCH "$BASE_URL/users/$USER_ID/status" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" -d '{"status":"DISABLED"}'
```

Roles are `ADMIN`, `MANAGER`, `CASHIER`, `MERCHANDISER` — see each route group's role requirements
in the API docs; most write endpoints restrict to `ADMIN`/`MANAGER`, product-catalog writes also
allow `MERCHANDISER`.

### SSO

```bash
curl -X POST "$BASE_URL/users/sso/configure" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"providerName":"Okta","roleMappings":[{"externalGroup":"pos-managers","role":"MANAGER"}],"defaultRoles":["CASHIER"],"enabled":true}'
```

`defaultRoles` is the fallback when an assertion's groups match nothing in `roleMappings` — leaving
it empty means an unmapped group fails login entirely (`SsoLoginResult.NoRoleMapped`). See
[docs/runbooks/auth-sso-incidents.md](docs/runbooks/auth-sso-incidents.md) if SSO logins start
failing after a config change.

## Devices & printers

```bash
# Generate a pairing code for a new POS terminal, then have the device redeem it
curl -X POST "$BASE_URL/devices/create-pair-code" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" -d '{"storeId":"'"$STORE_ID"'"}'
curl -X POST "$BASE_URL/devices/enroll" -H "Content-Type: application/json" -d '{"code":"<pair-code>","name":"Front Counter"}'
# -> enroll response includes clientId/clientSecret - the device's own long-lived credentials,
#    shown once; store them on the device, not in your shell history.

curl -H "Authorization: Bearer $ADMIN_TOKEN" "$BASE_URL/devices?storeId=$STORE_ID"
curl -X POST "$BASE_URL/devices/$DEVICE_ID/deprovision" -H "Authorization: Bearer $ADMIN_TOKEN"

# Register a printer and flip it online/offline
curl -X POST "$BASE_URL/printers" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"storeId":"'"$STORE_ID"'","name":"Front Counter","connectionType":"USB"}'
curl -X PATCH "$BASE_URL/printers/$PRINTER_ID/status" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" -d '{"status":"OFFLINE"}'
```

See [docs/runbooks/terminal-offline.md](docs/runbooks/terminal-offline.md) for what to do when a
device/printer actually goes offline, and
[docs/runbooks/offline-sync-conflicts.md](docs/runbooks/offline-sync-conflicts.md) for reviewing
`GET /sync/conflicts` once a device reconnects.

## Tax profiles

```bash
curl -X POST "$BASE_URL/tax-profiles" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"NY Sales Tax","rates":[{"name":"State","ratePercent":4.0},{"name":"City","ratePercent":4.5}],"pricingMode":"EXCLUSIVE"}'
```

Attach a profile's id to a store via `taxProfileId` on `POST /stores` or `PUT /stores/{id}`. Use
`POST /tax-profiles/{id}/calculate` to preview tax on a single amount before assigning it anywhere.

## Feature flags

```bash
curl -X POST "$BASE_URL/feature-flags" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"key":"new_checkout_flow","description":"Gradual rollout of the redesigned checkout screen","rolloutPercentage":10}'
curl -X PATCH "$BASE_URL/feature-flags/new_checkout_flow" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" -d '{"rolloutPercentage":50}'
```

`heavy_analytics_pipeline` is the operational kill switch used during capacity incidents — see
[docs/runbooks/capacity-scale-incidents.md](docs/runbooks/capacity-scale-incidents.md) before
touching it.

## API keys (3rd-party integrations)

See [API_KEYS.md](API_KEYS.md) for the full guide — creation, scopes, rotation, revocation, usage
logs.

## Secrets rotation

```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" "$BASE_URL/ops/secrets"
curl -X POST "$BASE_URL/ops/secrets/jwt-signing-key/rotate" -H "Authorization: Bearer $ADMIN_TOKEN"
```

Rotating the JWT signing key keeps the previous version valid for its grace period so in-flight
tokens don't immediately break — see
[docs/runbooks/auth-sso-incidents.md](docs/runbooks/auth-sso-incidents.md) if users report mass
logouts shortly after a rotation.

## Audit log

```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" "$BASE_URL/ops/audit-log?event=LOGIN_FAILURE"
curl -X POST "$BASE_URL/ops/audit/retention/run-now" -H "Authorization: Bearer $ADMIN_TOKEN"
```

`event` is any `AuditEvent` enum value (`server/src/main/kotlin/com/beettechnologies/posly/audit/AuditService.kt`).

## Backups & disaster recovery

See [DR_RUNBOOK.md](DR_RUNBOOK.md) for the full backup/restore-drill procedure
(`POST /ops/backups/run-now`, `GET /ops/backups`, `POST /ops/backups/{id}/restore`).

## Reporting

```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" "$BASE_URL/reports/sales?storeId=$STORE_ID&period=DAILY&periodStart=2026-01-01T00:00:00Z"
curl -H "Authorization: Bearer $ADMIN_TOKEN" "$BASE_URL/reports/top-products?storeId=$STORE_ID&limit=10"
curl -H "Authorization: Bearer $ADMIN_TOKEN" "$BASE_URL/reports/cash-on-hand?storeId=$STORE_ID"
```

These same reads are also available to an API key with the `REPORTS_READ` scope (see
[API_KEYS.md](API_KEYS.md)) — useful for wiring up an external BI tool without sharing an admin's
own JWT. Scheduled/emailed finance reports are configured via `POST /finance/reports/schedules`.

## Known limitations (explicitly disclosed, not fabricated)

- **`admin`/`admin123`, `manager`/`manager123`, `cashier`/`cashier123` are seeded automatically on
  any genuinely empty database** (`UserService`'s init block) — including, without any further
  action, a fresh production database on first boot. There is no forced-password-change-on-first-
  login flow. Anyone deploying this beyond local development should immediately disable or
  re-password these three accounts (`PATCH /users/{id}/status` or `.../roles`) before exposing the
  server publicly.
- **No account lockout / login rate limiting** — see
  [docs/runbooks/auth-sso-incidents.md](docs/runbooks/auth-sso-incidents.md)'s Known limitations.
- **This guide covers HTTP API usage, not the admin UI.** The Compose Multiplatform app's admin
  screens (Stores, Users, Devices, Feature Flags, API Keys, Import wizards) call the same
  endpoints documented here; there is no separate web admin console.
