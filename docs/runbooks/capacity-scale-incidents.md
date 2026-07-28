# Capacity & Scale Incidents Runbook

## Scope

Covers what to do when the Posly server is under sustained load: reading the capacity alarms,
confirming auto-scaling is keeping up (or isn't), and shedding load from the server's heaviest
on-demand work — full reporting-pipeline runs/backfills and ad-hoc finance report generation —
without taking the whole service down. Does **not** cover application bugs causing elevated error
rates unrelated to load (see `docs/runbooks/observability-alerts.md` for that) or database
failover (see `DR_RUNBOOK.md`).

## What's actually in place

- **Auto-scaling:** `infra/terraform/modules/ecs` scales ECS `DesiredCount` on a target-tracking
  policy at 70% average CPU (`aws_appautoscaling_policy.cpu`), between each environment's
  `min_capacity`/`max_capacity` (dev 1–3, stage 1–4, prod 2–10 — see each
  `environments/*/main.tf`). There is no memory-based or request-count-based scaling policy —
  CPU is the only signal auto-scaling reacts to today.
- **Backpressure:** `POST /reports/pipeline/run`, `POST /reports/pipeline/backfill`,
  `GET /finance/reports/generate`, and `POST /finance/reports/schedules/{id}/run-now` share one
  in-process rate limit — 5 calls/minute, across **all** callers, not per-client (see
  `server/src/main/kotlin/com/beettechnologies/posly/capacity/HeavyAnalyticsGuard.kt`). Once
  exhausted, further calls get `429 Too Many Requests` with a `Retry-After` header instead of
  piling onto the server. Every other endpoint, including the read-only report/finance queries
  (`GET /reports/sales`, `/top-products`, `/finance/reports/schedules`, etc.), is unaffected.
- **Manual kill switch:** the `heavy_analytics_pipeline` feature flag (via the existing
  `FeatureFlagService`) gates the same four endpoints. When it exists and is disabled, they
  return `503 Service Unavailable` with `Retry-After` instead of running — see **Shedding load
  manually** below. If the flag has never been created, the endpoints behave as if it were
  enabled (nothing to provision up front).
- **Alarms:**
  - CloudWatch (`infra/terraform/modules/alerting`): `posly-<env>-ecs-cpu-approaching-ceiling`
    (CPU > 85% for 15m — i.e. hot *despite* auto-scaling already reacting at 70%),
    `posly-<env>-ecs-memory-approaching-ceiling` (memory > 85% for 15m), and
    `posly-<env>-ecs-at-max-capacity` (`RunningTaskCount` at `max_capacity` for 15m — scale-out
    has no headroom left). All three publish to the `posly-<env>-ops-alerts` SNS topic.
  - Prometheus (`infra/observability/prometheus/alerts.yml`): `PoslyHeavyAnalyticsRateLimited`
    (sustained 429s on the four heavy endpoints) and `PoslyHeavyAnalyticsKillSwitchActive`
    (sustained 503s — i.e. the manual kill switch is currently on).
- **Cost alert:** `aws_budgets_budget.monthly_cost` per environment, notifying the same SNS topic
  at 80% actual / 100% forecasted spend. **Not scoped to this project's resources specifically**
  — see Known limitations.

## Alert response steps

1. Acknowledge the page. Identify which alarm fired:
   - `ecs-cpu-approaching-ceiling` / `ecs-memory-approaching-ceiling` → the service is hot but
     auto-scaling is (or should be) actively adding capacity. Confirm in the ECS console /
     `aws ecs describe-services` that `runningCount` is climbing toward `desiredCount`. If it's
     climbing, this may just need time — re-check in 10–15 minutes. If it's stuck, check for a
     capacity-constrained Fargate launch (rare) or a task health-check failure loop.
   - `ecs-at-max-capacity` → auto-scaling has no more headroom. Either (a) this is a genuine,
     larger-than-expected traffic spike — raise `max_capacity` in the relevant
     `environments/<env>/main.tf` and apply, or (b) it's runaway/abusive traffic — investigate
     before just raising the ceiling.
   - `PoslyHeavyAnalyticsRateLimited` → the reporting/finance pipeline is being hammered (a retry
     storm, a misbehaving scheduled job, or genuine demand outgrowing the 5/min cap). Check
     `GET /reports/pipeline/runs` and `/finance/reports/schedules/*/runs` for who's calling it.
     If demand is legitimately outgrowing the cap, raise the `limit`/`refillPeriod` passed to
     `rateLimiter(...)` in `Application.kt`'s `install(RateLimit)` block.
   - `PoslyHeavyAnalyticsKillSwitchActive` → someone (or an automation) disabled
     `heavy_analytics_pipeline`. Confirm whether this is an intentional, ongoing incident
     response; if it was left on by mistake, re-enable it (see below).
2. Check the standard dashboards/logs per `docs/runbooks/observability-alerts.md` for correlated
   error-rate/latency symptoms elsewhere in the system.
3. If the whole service (not just heavy analytics) is degraded and auto-scaling genuinely can't
   keep up in time, shed load from the heaviest endpoints manually (next section) while capacity
   catches up.
4. Once the underlying pressure has passed, confirm alarms clear and re-enable anything you
   disabled manually.

## Shedding load manually (the kill switch)

Create the flag once, the first time it's needed in an environment (admin token required):

```bash
curl -X POST "$BASE_URL/feature-flags" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"key":"heavy_analytics_pipeline","description":"Capacity incident lever","enabled":true}'
```

During an incident, disable it to shed load from the heavy pipeline/report endpoints:

```bash
curl -X PATCH "$BASE_URL/feature-flags/heavy_analytics_pipeline" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"enabled":false}'
```

Every affected endpoint now returns `503` with `Retry-After: 60` immediately, without doing any
work — cashiers checking out and staff viewing already-computed reports are unaffected. Re-enable
the same way (`{"enabled":true}`) once the incident is over. Every flip is audited
(`AuditEvent.FEATURE_FLAG_UPDATED`, see `GET /feature-flags/audit-log`).

## Load testing

`load-tests/` (k6 scripts, see `load-tests/README.md`) validates both halves of this runbook's
acceptance criteria against a running instance:

- `checkout-flow.js` — simulates peak checkout traffic; fails its thresholds if p95 latency
  degrades under load, which is the trigger to check the CPU/memory/at-capacity alarms above.
- `heavy-analytics-degradation.js` — hammers the pipeline endpoint past its rate limit and
  asserts the server sheds load with clean 429s (never a crash or hang) while light reads keep
  working. Useful to re-run after changing the rate limiter's `limit`/`refillPeriod` to confirm
  the new cap behaves as expected.

Run these against a staging environment (never production) before and after any capacity-related
change (raising `max_capacity`, changing the rate limit, etc.) to confirm the change had the
intended effect.

## Known limitations (explicitly disclosed, not fabricated)

- **Not validated against a live AWS deployment.** The Terraform in `infra/terraform/modules/ecs`
  and `infra/terraform/modules/alerting` has been syntax/type-checked with `terraform validate`
  and `terraform fmt` (no `terraform plan`/`apply` — no AWS account is reachable from this
  environment) — see each module's code. The k6 scripts and the rate-limit/kill-switch behavior
  *have* been run end to end against a local `:server:run` instance.
- **CPU/memory-only auto-scaling.** No request-count or queue-depth-based scaling policy exists;
  a CPU-light-but-request-heavy workload (unlikely for this app's profile, but possible) would not
  trigger scale-out.
- **The monthly cost budget is not scoped by resource tags.** `aws_budgets_budget.monthly_cost`
  covers the whole AWS account's spend, not just this project's resources, because tag-based cost
  filtering requires activating cost-allocation tags in the AWS Billing console first — a manual,
  account-wide step outside Terraform's control. Treat it as an account-level backstop, not a
  per-project cost signal.
- **SNS topic has no real on-call integration wired in** — only an optional plain email
  subscription (`var.alert_email`). Wire PagerDuty/Opsgenie/Slack onto
  `module.alerting.ops_alerts_topic_arn` before relying on this in production.
- **The heavy-analytics rate limit is a single global bucket**, not per-store or per-client. A
  single store running frequent report generation can exhaust the shared cap for every other
  store. If that becomes a real problem, the fix is a `requestKey` (e.g. per-store) on the
  `HeavyAnalyticsRateLimit` provider in `Application.kt` — not implemented here since today's
  usage pattern (occasional, largely admin-triggered pipeline runs) doesn't need it yet.
