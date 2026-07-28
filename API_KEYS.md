# API Keys & 3rd-Party Integration Management — posly

## Scope

Covers creating, scoping, rotating, and revoking API keys for 3rd-party integrations (accounting,
analytics, etc.), and the small set of read endpoints those keys can call. This is a wholly new
feature — no server-to-server credential concept existed in this codebase before it (device
pairing credentials and webhook signing secrets solve different problems; see Known limitations).

## How it works

### Creating a key

`POST /api-keys` (ADMIN only), via the admin UI's **API Keys → + New Key** screen or directly:

```bash
curl -X POST "$BASE_URL/api-keys" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"Accounting integration","scopes":["ORDERS_READ"]}'
```

The response's `rawKey` field (shape `posly_<key-id>-<secret>`) is shown **exactly once** — the
server stores only a BCrypt hash of the secret (`ApiKeyService.createKey`, mirroring
`UserService`'s password hashing), never the raw value. If it's lost, the only recovery is
rotating the key for a new one (see below) — there is no "reveal" endpoint, by design.

### Scopes

| Scope | Gates |
|---|---|
| `ORDERS_READ` | `GET /orders`, `GET /orders/{id}` |
| `PRODUCTS_READ` | `GET /search` |
| `REPORTS_READ` | `GET /reports/sales`, `/sales/realtime`, `/inventory`, `/top-products`, `/cash-on-hand`, `/staff` |

Deliberately narrow — these are the read endpoints a typical external integration needs, not the
full API surface. Notably, `/reports/pipeline/run` and `/reports/pipeline/backfill` (and every
write endpoint anywhere else) are **JWT-only, regardless of scope** — an API key can never trigger
a write or a heavy operation, only read the specific resources above. Adding a new scope means
adding both the enum value (`apikeys/ApiKeyModels.kt`) and wiring the target route through
`withRoleOrScope` (see `cart/OrderRoutes.kt`/`products/search/SearchRoutes.kt`/`reporting/ReportingRoutes.kt`
for the pattern) — done one real integration need at a time, not speculatively.

### Using a key

Send it as a bearer token, exactly like a user's JWT access token — both are checked on any route
that `authenticate("jwt-auth", "api-key-auth")` (Ktor's `FirstSuccessful` multi-provider strategy:
succeeds if *either* authenticates, mutually exclusive per request):

```bash
curl "$BASE_URL/orders?storeId=$STORE_ID&from=2026-01-01T00:00:00Z&to=2026-01-02T00:00:00Z" \
  -H "Authorization: Bearer posly_<key-id>-<secret>"
```

### Revoking

`POST /api-keys/{id}/revoke` (ADMIN only) — immediate, no grace period. The very next request with
that key's raw secret gets `401 Unauthorized` (verified end to end in
`ApiKeyRoutesTest.kt`'s `an API key with ORDERS_READ can call GET orders, and stops working once revoked`).

### Rotating

`POST /api-keys/{id}/rotate` (ADMIN only) — generates a brand-new secret for the *same* key id and
scopes, shown once in the response, exactly like creation. **Unlike**
`SecretsManager`'s JWT-signing-key rotation (which keeps the previous version valid for a grace
period, since already-issued JWTs must keep verifying), an API key's old secret stops working
**immediately** — there's no in-flight-artifact problem to protect against, so immediate
invalidation is simpler and safer. Operationally: deploy the integration's new key *before or
atomically with* invalidating the old one, since there's no overlap window.

### Usage logs

Every API-key-authenticated request is recorded (`ApiKeyUsageTable` — method, path, status code,
timestamp) via an `onCallRespond` hook (`apikeys/ApiKeyUsageLogging.kt`) that fires after the
response status is known — so a `403` from a missing scope is logged accurately, not just
successful calls. Query via `GET /api-keys/{id}/usage` (ADMIN only) or the admin UI's **View
usage** toggle per key row.

## Admin UI

**API Keys** (from the dashboard) → list screen (name, masked prefix `posly_<8 chars>...`, scopes,
status, last-used, per-key Rotate/Revoke/View usage) → **+ New Key** → name + scope checkboxes →
create → the raw secret is displayed once with an explicit "copy it now" warning, matching the
same show-once treatment used after a rotate.

## Testing

- `ApiKeyServiceTest.kt` (16 tests) — hashing, raw-key round-trip, revoke/rotate lifecycle,
  malformed/wrong/unknown-key rejection, usage recording.
- `ApiKeyRoutesTest.kt` (11 tests) — the full acceptance criteria: secret shown once and never
  again from list, non-admin can't create, scope enforcement (right scope → 200, wrong scope →
  403), **revoked key → 401**, rotated key's old secret → 401 / new secret → 200, pipeline
  endpoints stay JWT-only even with a matching-named scope, usage logging, JWT auth unaffected.
- `ApiKeyListScreenTest.kt` / `ApiKeyFormScreenTest.kt` (8 tests) — create/revoke/rotate/usage-toggle
  UI flows against a fake `ApiKeyApi`.

One real bug this work surfaced and fixed along the way: `FakeApiKeyApi`'s revoke/rotate mutated
its own backing list *in place*, and since `listKeys()` originally handed out that same mutable
list (no defensive copy), the mutation could retroactively make a ViewModel's "before" and "after"
`StateFlow` values structurally equal — `MutableStateFlow` skips notifying collectors when the new
value equals the old one, so the Compose UI silently never recomposed even though the ViewModel's
own state was correct. Fixed by having the fake return `keys.toList()` (see the fake's own comment
for the same lesson, in case another fake API test double hits the same pattern).

## Known limitations (explicitly disclosed, not fabricated)

- **No per-store scoping.** A key's scopes are global (e.g. `ORDERS_READ` reads orders across every
  store, not just one) — there's no `storeId` restriction on a key itself. Worth adding if a real
  integration ever needs to be scoped to a single store; not built speculatively here.
- **The usage log has no retention/archival policy**, unlike the audit log
  (`AuditRetentionService`). A high-traffic integration will grow `api_key_usage` indefinitely.
  Follow-up work, not handled in this round.
- **No rate limiting specific to API-key traffic.** An API key rides the same request path as a
  user JWT and gets whatever rate limits already apply there (e.g. the heavy-analytics limiter,
  which API keys can't reach anyway since pipeline endpoints are JWT-only) — there's no separate
  "N requests/minute per API key" throttle.
- **No IP allowlisting.** A stolen raw key works from anywhere; revocation is the only mitigation.
- **This doesn't overlap with device pairing credentials or webhook signing secrets** — those solve
  different problems (a specific paired POS terminal's own identity; an outbound webhook
  delivery's payload signature) and were deliberately left alone rather than unified with this
  feature.
