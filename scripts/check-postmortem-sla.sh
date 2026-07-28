#!/usr/bin/env bash
#
# Checks whether a postmortem draft exists (or is still within its SLA window) for a closed
# incident - see docs/postmortems/README.md. "Exists" is the bar, not "complete": the SLA is
# about capturing the timeline while it's fresh, not finishing root-cause analysis.
#
# Usage:
#   scripts/check-postmortem-sla.sh <incident_closed_at ISO-8601 UTC> <postmortem file path>
#
# Example:
#   scripts/check-postmortem-sla.sh 2026-07-20T15:10:00Z docs/postmortems/2026-07-20-payment-gateway-502s.md
#
# Env vars:
#   POSTMORTEM_SLA_HOURS  Override the default 48-hour SLA window.
#
# Exit codes: 0 = compliant (file exists, or still within the window); 1 = OVERDUE (window passed,
# file missing); 2 = usage error.
set -euo pipefail

SLA_HOURS="${POSTMORTEM_SLA_HOURS:-48}"

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <incident_closed_at ISO-8601 UTC> <postmortem file path>" >&2
  exit 2
fi

CLOSED_AT="$1"
POSTMORTEM_PATH="$2"

if [[ -f "$POSTMORTEM_PATH" ]]; then
  echo "OK: postmortem exists at $POSTMORTEM_PATH"
  exit 0
fi

# Date arithmetic in pure bash/date differs between GNU and BSD date (macOS); python3's stdlib
# datetime is portable across both and already relied on elsewhere in this repo's tooling. This
# script's only job past this point is deciding the exit code, so python does the deciding too.
python3 - "$CLOSED_AT" "$SLA_HOURS" "$POSTMORTEM_PATH" <<'PY'
import sys
from datetime import datetime, timezone

closed_at_raw, sla_hours, postmortem_path = sys.argv[1], float(sys.argv[2]), sys.argv[3]
try:
    closed_at = datetime.fromisoformat(closed_at_raw.replace("Z", "+00:00"))
except ValueError:
    print(f"error: '{closed_at_raw}' is not a valid ISO-8601 timestamp", file=sys.stderr)
    sys.exit(2)
if closed_at.tzinfo is None:
    closed_at = closed_at.replace(tzinfo=timezone.utc)

elapsed_hours = (datetime.now(timezone.utc) - closed_at).total_seconds() / 3600
remaining_hours = sla_hours - elapsed_hours

if remaining_hours < 0:
    print(f"OVERDUE: no postmortem found at {postmortem_path} - incident closed {elapsed_hours:.1f}h ago, "
          f"the {sla_hours:.0f}h SLA has passed")
    sys.exit(1)
else:
    print(f"Within SLA: no postmortem yet at {postmortem_path}, but {remaining_hours:.1f}h remain "
          f"of the {sla_hours:.0f}h window")
    sys.exit(0)
PY
