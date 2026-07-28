---
incident: <short descriptive title, e.g. "Payment gateway 502s for 40 minutes">
severity: page | warning
service: <e.g. posly-payments, posly-terminals, posly-reporting>
incident_started_at: <ISO-8601 UTC, e.g. 2026-07-20T14:32:00Z>
incident_closed_at: <ISO-8601 UTC>
status: draft | complete
author: <who wrote this>
---

# Postmortem: <incident title>

## Summary

*(2-3 sentences: what broke, for how long, and who/what was affected. Written for someone who
wasn't there.)*

## Impact

- **Duration:** *(incident_closed_at - incident_started_at)*
- **Affected:** *(which stores/customers/endpoints - be specific; "some checkouts failed" is
  weaker than "checkout failed for N stores in the EU region for 12 minutes")*
- **Detection:** *(which alert fired, or how it was otherwise noticed - if it wasn't caught by an
  alert, that's itself a finding for the Action Items section below)*

## Timeline

*(UTC timestamps. Include when the alert fired, when it was acknowledged, key diagnostic steps,
mitigation actions, and resolution - not just the happy path; include false starts.)*

| Time (UTC) | Event |
|---|---|
| | Alert fired: `<alert name>` |
| | Acknowledged by |
| | |
| | Resolved |

## Root cause

*(The actual technical cause, not just the symptom. "5xx rate was high" is a symptom; "the
payment gateway's retry policy exhausted all 3 attempts because X" is a root cause. If you don't
know the root cause yet, say so explicitly rather than guessing - a postmortem with an honest
"root cause still under investigation" is more useful than a plausible-sounding wrong one.)*

## What went well

*(Genuinely - don't skip this. E.g. "the kill switch let us shed load without a full outage.")*

## What went poorly / contributing factors

*(Be specific and blameless - about systems and processes, not people. E.g. "no alert existed for
this failure mode" rather than "the on-call engineer should have noticed sooner.")*

## Action items

| Action | Owner | Due date | Status |
|---|---|---|---|
| | | | Not started |

*(Every action item needs an owner and a due date, or it won't happen. Link back to this
postmortem in the tracking issue/ticket for each one.)*

## Runbook updates

*(Did this incident reveal a runbook gap - a step that didn't exist, or that was wrong? Link the
PR that fixes `docs/runbooks/*.md` here, or explain why no runbook update is needed.)*
