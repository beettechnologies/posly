# Authentication & SSO Incidents Runbook

## Scope

Covers login failure spikes, MFA lockout complaints, SSO misconfiguration, and mass-401s caused by
JWT signing-key rotation. Does **not** cover generic API alerting/tracing (see
`docs/runbooks/observability-alerts.md`), payment gateway failures (see
`docs/runbooks/payment-gateway-down.md`), or device/terminal offline detection (see
`docs/runbooks/terminal-offline.md`) — those have their own distinct signals.

## Incident classes and what they look like in this codebase

- **Login failure spike** — `AuthService.login` records `AuditEvent.LOGIN_FAILURE` on bad
  credentials or a `DISABLED` account (`server/src/main/kotlin/com/beettechnologies/posly/auth/AuthService.kt:59-76`).
  A burst of these for one username is a plausible credential-stuffing/brute-force attempt; a
  burst across many usernames right after a deploy is more likely a client-side bug (e.g. a bad
  password-hashing change) than an attack.
- **MFA lockout** — `AuditEvent.MFA_FAILURE` on a wrong code or an invalid/expired `mfaToken`
  (`AuthService.verifyMfa`, lines 108-127). A user reporting they "can't get past MFA" usually
  means either a clock-drift TOTP mismatch (the code doesn't match server time) or the `mfaToken`
  from `/auth/login` expired before they entered a code.
- **SSO misconfiguration** — `AuditEvent.SSO_LOGIN_FAILURE` covers four distinct reasons, all
  recorded with a `detail` string identifying which (`AuthService.ssoLogin`, lines 191-225):
  `"SSO not configured or disabled"`, `"no role mapping matched"`, `"account disabled"`, and
  `"username conflict during provisioning"`. The first two are the common ones after an admin
  changes the SSO role-mapping config (`POST /users/sso/configure`) and gets it wrong.
- **Mass 401s after a JWT signing-key rotation** — `SecretsManager.rotate(SecretName.JWT_SIGNING_KEY, ...)`
  (`POST /ops/secrets/jwt-signing-key/rotate`) keeps the previous signing key valid only for its
  grace period (`SecretVersionStatus.IN_GRACE_PERIOD`); once that grace period elapses, any
  still-cached access token signed with the old key starts failing verification. This looks like a
  sudden, correlated 401 spike across many otherwise-unrelated users shortly after (or exactly at)
  a previous rotation's grace-period expiry — not a credential problem at all.

## Detection

```bash
# Recent login/MFA/SSO failures, fleet-wide or filtered to one user
curl -H "Authorization: Bearer $ADMIN_TOKEN" "$BASE_URL/ops/audit-log?event=LOGIN_FAILURE"
curl -H "Authorization: Bearer $ADMIN_TOKEN" "$BASE_URL/ops/audit-log?event=MFA_FAILURE"
curl -H "Authorization: Bearer $ADMIN_TOKEN" "$BASE_URL/ops/audit-log?event=SSO_LOGIN_FAILURE"
curl -H "Authorization: Bearer $ADMIN_TOKEN" "$BASE_URL/ops/audit-log?username=<username>"

# Is a mass-401 wave explained by a recent JWT signing-key rotation?
curl -H "Authorization: Bearer $ADMIN_TOKEN" "$BASE_URL/ops/secrets" | jq '.[] | select(.name=="jwt-signing-key")'
```

There is no dedicated alert rule for any of these (see Known limitations) — this runbook is
triggered by a support ticket, a manual audit-log review, or a spike noticed via
`docs/runbooks/observability-alerts.md`'s generic auth-dashboard error-rate panel, not by a
purpose-built page.

## Response steps

1. **Login failure spike.** Pull `GET /ops/audit-log?event=LOGIN_FAILURE`. One username, many
   attempts, many source IPs (`remoteIp` field) → likely credential stuffing; there is no
   account-lockout mechanism to fall back on (see Known limitations), so the only mitigation today
   is disabling the account: `PATCH /users/{id}/status {"status":"DISABLED"}`. Many usernames, all
   failing right after a deploy → check whether the deploy touched `UserService`/password hashing;
   roll back per `docs/runbook-deploy.md` if so.
2. **MFA lockout.** Confirm the user's `mfaToken` came from a *recent* `/auth/login` call — it's
   short-lived (see `jwt.mfaTokenExpirationMs` config) and a stale one is expected to fail
   verification, not a bug. If the code itself is consistently rejected, suspect clock drift on
   the user's authenticator app relative to server time — not something this runbook can fix
   remotely.
3. **SSO misconfiguration.** `GET /users/sso/configuration` shows the current role-mapping rules.
   `"no role mapping matched"` means the identity provider is asserting a group not present in any
   `roleMappings` entry and there's no `defaultRoles` fallback configured — fix via
   `POST /users/sso/configure` with either a matching mapping or a non-empty `defaultRoles`.
   `"SSO not configured or disabled"` means either no configuration was ever saved, or an admin
   toggled `enabled: false` — re-enable via the same endpoint.
4. **Mass 401s after a rotation.** Confirm via `GET /ops/secrets` that a `jwt-signing-key` rotation
   happened and whether its `previousVersionGraceExpiresAt` has passed. If it has, this is expected
   behavior working as designed, not a bug — affected users simply need to log in again to get a
   token signed with the current key (a client-side "your session expired, please log in" message
   should handle this transparently in normal operation). If the spike happened **before** the
   grace period should have expired, that's a real defect in `InMemorySecretsManager`'s grace-period
   accounting and should be escalated as a bug, not worked around here.
5. Record the incident class, root cause, and affected user count in the postmortem template
   (`docs/postmortems/TEMPLATE.md`) if it met the `severity: page` threshold
   (`docs/postmortems/README.md`).

## Known limitations (explicitly disclosed, not fabricated)

- **No account lockout after repeated failed logins.** `AuthService.login` has no failed-attempt
  counter or temporary lockout — every `LOGIN_FAILURE` is recorded but never itself blocks further
  attempts. The only manual mitigation is disabling the account outright
  (`PATCH /users/{id}/status`), which also blocks the legitimate user, not just an attacker.
- **No login-specific rate limiting.** The `RateLimit` plugin installed in `Application.kt` only
  registers a limiter for heavy analytics routes (`HeavyAnalyticsRateLimit`) — `/auth/login` and
  `/auth/mfa/verify` are unthrottled, so a credential-stuffing attempt isn't slowed down by the
  server itself.
- **No dedicated alert rule for any of these incident classes.** Unlike terminal-offline or
  capacity incidents, there's no Prometheus alert wired to `LOGIN_FAILURE`/`MFA_FAILURE`/
  `SSO_LOGIN_FAILURE` volume — detection today is reactive (a support ticket or manual audit-log
  review), not proactive paging. Worth adding if these incidents recur often enough to justify it.
- **`activeRefreshTokens` is in-memory only** (`AuthService.kt:57`) — a server restart invalidates
  every outstanding refresh token fleet-wide, which would itself look exactly like a mass-401
  incident (everyone needs to log in again) with a completely different root cause than a signing-
  key rotation. Check recent deploy/restart history before assuming a rotation is to blame.
- **SSO is simulated, not a real SAML/OIDC integration.** `POST /auth/sso/callback` accepts an
  `SsoCallbackRequest` directly rather than validating a real identity-provider assertion/signature
  — there's no "the IdP itself is down or misconfigured" failure mode to drill here, only this
  codebase's own role-mapping logic.
