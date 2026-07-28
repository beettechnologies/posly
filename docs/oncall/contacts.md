# Contact List (template)

**Placeholder contact details, not real ones.** Fill in with real names/numbers before relying on
this during an actual incident. Store the real version wherever your org keeps
sensitive-but-operational data (a password manager's shared vault, PagerDuty's own contact
directory, etc.) — committing real phone numbers/personal emails to a git repo is a privacy risk
this template deliberately avoids by using placeholders.

## Engineering on-call

| Role | Name | Phone | Slack |
|---|---|---|---|
| Primary on-call | *(see rotation-schedule.md)* | `REPLACE_WITH_PHONE` | `@REPLACE_WITH_HANDLE` |
| Secondary on-call | *(see rotation-schedule.md)* | `REPLACE_WITH_PHONE` | `@REPLACE_WITH_HANDLE` |
| Engineering manager (tertiary escalation) | `REPLACE_WITH_NAME` | `REPLACE_WITH_PHONE` | `@REPLACE_WITH_HANDLE` |

## Vendor / third-party contacts

| Vendor | Purpose | Support channel |
|---|---|---|
| Payment gateway/terminal vendor | `docs/runbooks/payment-gateway-down.md` — needed once a real vendor integration replaces `SimulatorPaymentGateway` | `REPLACE_WITH_VENDOR_SUPPORT_CONTACT` |
| Cloud provider (AWS) | Infra-level incidents, capacity/scaling — see `docs/runbooks/capacity-scale-incidents.md` | AWS Support Console (per the account's support plan) |
| Database hosting (if managed Postgres) | `DR_RUNBOOK.md` | `REPLACE_WITH_VENDOR_SUPPORT_CONTACT` |

## Business stakeholders (for customer/store-facing incidents)

| Role | Name | Notes |
|---|---|---|
| Support lead | `REPLACE_WITH_NAME` | Notify for incidents visibly affecting stores (checkout down, terminals offline fleet-wide) |
| Communications/status page owner | `REPLACE_WITH_NAME` | Owns any external status-page updates, if one exists |
