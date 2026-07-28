# Escalation Policy

## Tiers

1. **Primary on-call** (`rotation-schedule.md`) — paged first for every `severity: page` alert
   (see `infra/observability/alertmanager/alertmanager.yml`'s `pagerduty-critical` receiver).
2. **Secondary on-call** — paged if primary hasn't acknowledged within **15 minutes**. (This is a
   PagerDuty escalation-policy setting configured against the real `routing_key` once one exists —
   `alertmanager.yml` itself only fans out to PagerDuty; PagerDuty's own escalation policy owns the
   primary→secondary timing, since Alertmanager has no concept of "acknowledged.")
3. **Engineering manager** — paged if secondary hasn't acknowledged within a further **15
   minutes** (30 minutes total unacknowledged).
4. **Incident commander declared** — for anything still unresolved after **1 hour**, or that's
   visibly affecting multiple stores/customers regardless of elapsed time, whoever is on-call
   (primary, secondary, or manager, whoever is actively engaged) declares an incident and takes the
   incident-commander role: single point of coordination, decides who else to pull in, owns
   status updates to stakeholders (see `contacts.md`), and owns kicking off the postmortem
   (`docs/postmortems/README.md`) once resolved.

## Severity → response expectations

| Alert `severity` label | Examples | Response |
|---|---|---|
| `page` | `PoslyAuth5xxRateHigh`, `PoslyPayments5xxRateHigh`, `PoslyTerminalsOffline`, `posly-<env>-ecs-at-max-capacity` | Ack within 15 min, follow the linked runbook immediately. |
| `warning` | `PoslyAuthP95LatencyHigh`, `PoslyHeavyAnalyticsRateLimited`, `posly-<env>-ecs-cpu-approaching-ceiling` | Investigate within the business day; no page, Slack only (see `alertmanager.yml`'s `slack-warnings` receiver). |

## When to pull in someone outside the rotation

- **The runbook itself says to** (e.g. `payment-gateway-down.md`'s vendor-status-page check might
  surface a need for someone with vendor account access).
- **The incident is taking longer than the runbook anticipates** and the runbook's own "Known
  limitations" section flags the gap (e.g. `payment-gateway-down.md` disclosing there's no circuit
  breaker — if retry-storm behavior itself becomes the problem, that's a signal to pull in whoever
  owns `gateway/RetryPolicy.kt`, not to keep manually retrying the runbook's steps).
- **A postmortem action item from a previous incident said to** — check
  `docs/postmortems/` for anything relevant before assuming this is genuinely novel.

## Relationship to the alerting config

`infra/observability/alertmanager/alertmanager.yml` encodes the mechanical part of this policy
(who/what gets notified, how often, and what's suppressed via `inhibit_rules`) — this document
covers the human part (who escalates to whom, and when to declare an incident). Keep both in sync
when either changes; a policy described here that the Alertmanager config doesn't actually
implement is worse than no documented policy, since responders will trust this doc.
