#!/usr/bin/env bash
# smoke-test.sh — run a minimal set of HTTP checks against the deployed application.
# Usage: bash scripts/smoke-test.sh <BASE_URL>
# Exit code 0 = all checks passed; non-zero = one or more checks failed.

set -euo pipefail

BASE_URL="${1:?Usage: $0 <BASE_URL>}"
BASE_URL="${BASE_URL%/}"   # strip trailing slash

MAX_RETRIES=10
RETRY_DELAY=6   # seconds between retries
PASSED=0
FAILED=0

log()  { echo "[$(date -u +%H:%M:%S)] $*"; }
pass() { log "PASS  $1"; PASSED=$((PASSED + 1)); }
fail() { log "FAIL  $1"; FAILED=$((FAILED + 1)); }

# ------------------------------------------------------------------
# Helper: GET <path> and assert HTTP <expected_status>
# ------------------------------------------------------------------
check_status() {
  local path="$1"
  local expected="$2"
  local url="$BASE_URL$path"

  local attempt=0
  local actual

  while [ $attempt -lt $MAX_RETRIES ]; do
    actual=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 "$url" || echo "000")
    if [ "$actual" = "$expected" ]; then
      pass "GET $path → HTTP $actual"
      return 0
    fi
    attempt=$((attempt + 1))
    log "Attempt $attempt/$MAX_RETRIES: GET $path → HTTP $actual (expected $expected). Retrying in ${RETRY_DELAY}s…"
    sleep "$RETRY_DELAY"
  done

  fail "GET $path → HTTP $actual (expected $expected) after $MAX_RETRIES attempts"
}

# ------------------------------------------------------------------
# Helper: GET <path> and assert response body contains <substring>
# ------------------------------------------------------------------
check_body() {
  local path="$1"
  local substring="$2"
  local url="$BASE_URL$path"

  local attempt=0
  local body

  while [ $attempt -lt $MAX_RETRIES ]; do
    body=$(curl -s --max-time 10 "$url" || true)
    if echo "$body" | grep -qF "$substring"; then
      pass "GET $path body contains \"$substring\""
      return 0
    fi
    attempt=$((attempt + 1))
    log "Attempt $attempt/$MAX_RETRIES: body check failed. Retrying in ${RETRY_DELAY}s…"
    sleep "$RETRY_DELAY"
  done

  fail "GET $path body does not contain \"$substring\" after $MAX_RETRIES attempts"
}

# ------------------------------------------------------------------
# Smoke checks
# ------------------------------------------------------------------
log "Starting smoke tests against $BASE_URL"

check_status /health 200
check_status /        200

log "-----------------------------"
log "Results: $PASSED passed, $FAILED failed"
log "-----------------------------"

if [ "$FAILED" -gt 0 ]; then
  exit 1
fi
