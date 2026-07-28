# Postmortems

## SLA

**A postmortem draft (copy `TEMPLATE.md`, fill in at minimum the Summary/Impact/Timeline
sections) must exist within 48 hours of an incident's `incident_closed_at`.** "Draft" is
deliberately the bar for the SLA, not "complete" — root-cause analysis and action items can take
longer, but capturing the timeline while it's fresh cannot wait. This satisfies the ticket's
acceptance criterion ("given post-incident, when incident closed, then postmortem template
created within SLA") without requiring the postmortem to be fully polished within 48 hours.

Required for every `severity: page` incident (see `docs/oncall/escalation-policy.md`). Optional
but encouraged for `severity: warning` incidents that took real investigation time.

## Process

1. Copy `TEMPLATE.md` to `docs/postmortems/<YYYY-MM-DD>-<short-slug>.md` (the date is
   `incident_closed_at`'s date), fill in the front matter and at least Summary/Impact/Timeline.
   Commit it with `status: draft`.
2. Run the SLA checker to confirm it's tracked correctly (see below).
3. Circulate for review - anyone impacted or who responded should read it before it's marked
   `status: complete`.
4. File the Action Items as real tracked issues/tickets, not just rows in this table - a
   postmortem action item that only exists in a markdown file tends to get forgotten.
5. Once root cause and action items are filled in and reviewed, update the front matter to
   `status: complete`.

## Checking the SLA

```bash
scripts/check-postmortem-sla.sh 2026-07-20T15:10:00Z docs/postmortems/2026-07-20-payment-gateway-502s.md
```

Pass the incident's `incident_closed_at` and the expected postmortem file path. Exits `0` (and
prints how much time is left) if the file already exists or if the 48-hour window hasn't elapsed
yet; exits `1` (OVERDUE) if the window has passed and the file still doesn't exist. See
`scripts/check-postmortem-sla.sh`'s own header comment for exact usage and how to override the
default 48-hour window.

This is a manual-invocation check, not an automated cron/CI job — there's no incident-tracking
system in this repo to hook a scheduled check into (see disclosed limitations in
`infra/observability/alertmanager/alertmanager.yml`). Run it yourself during the on-call handoff
(`docs/oncall/rotation-schedule.md`) for any incident closed in the outgoing week without a
postmortem file yet.
