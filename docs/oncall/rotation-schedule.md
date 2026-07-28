# On-Call Rotation Schedule (template)

**This is a template with placeholder names, not a real live schedule.** There is no scheduling
tool (PagerDuty schedules, OpsGenie, etc.) actually wired up in this environment — see
`infra/observability/alertmanager/alertmanager.yml`'s disclosed limitation. Once a real PagerDuty
account exists, its own schedule UI/API should become the source of truth and this file should
either be deleted or reduced to a pointer at it, rather than kept as a second, driftable copy.

## Rotation structure

- **Cadence:** weekly, Monday 09:00 → next Monday 09:00, in the team's local timezone.
- **Coverage:** one **primary** and one **secondary** on-call engineer at all times — see
  `escalation-policy.md` for when secondary gets paged.
- **Handoff:** primary/secondary from the outgoing week briefs the incoming week on anything still
  open (unresolved alerts, in-progress incidents, recent postmortem action items still pending —
  see `docs/postmortems/README.md`).

## Example schedule (replace with real names before relying on this)

| Week starting | Primary | Secondary |
|---|---|---|
| 2026-08-03 | Alex Chen | Jordan Patel |
| 2026-08-10 | Jordan Patel | Sam Okafor |
| 2026-08-17 | Sam Okafor | Alex Chen |
| 2026-08-24 | Alex Chen | Jordan Patel |

## Swaps

Any swap must update **both** this file (if kept as source of truth) and the real paging tool
(PagerDuty schedule, once one exists) — a swap recorded in only one place is worse than no swap
process at all, since the page still goes to whoever the paging tool thinks is on call.
