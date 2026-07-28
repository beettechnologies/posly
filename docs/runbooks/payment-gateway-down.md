# Payment Gateway Down Runbook

## Scope

Covers the card payment gateway/terminal being unreachable or erroring — `POST /payments`
(create) and `POST /payments/{id}/refund` failing at the gateway layer. Does **not** cover a
receipt/POS terminal being offline (see `docs/runbooks/terminal-offline.md`) or general server
capacity issues (see `docs/runbooks/capacity-scale-incidents.md`).

## What "gateway down" looks like in this codebase

- **Abstraction:** `PaymentGatewayService` talks to an injected `PaymentGateway`
  (`server/src/main/kotlin/com/beettechnologies/posly/payments/PaymentGateway.kt`) — in every
  environment today that's `SimulatorPaymentGateway`, a working in-memory stand-in; a real vendor
  terminal integration is a drop-in replacement once sandbox credentials exist (see the interface's
  doc comment). **This runbook's response steps apply to a real gateway integration; today's
  simulator cannot actually go down** (see Known limitations).
- **Retry:** every gateway call is wrapped in `RetryPolicy.withBackoff` (3 attempts, exponential
  backoff starting at 100ms — `server/src/main/kotlin/com/beettechnologies/posly/gateway/RetryPolicy.kt`).
  Only `GatewayTransientException` is retried; anything else (e.g. a permanent rejection) fails
  immediately, since retrying a declined-card-type failure would never help.
- **No circuit breaker exists.** If the gateway is down, every single request still pays the full
  retry cost (3 attempts × backoff) before failing — there's no trip-open state that would let
  the service fail fast once the gateway is known to be down. See Known limitations.
- **Client-visible failure:** once retries are exhausted, `POST /payments` and
  `POST /payments/{id}/refund` respond `502 Bad Gateway` with an `ErrorResponse` body
  (`CreatePaymentResult.GatewayError` / `RefundPaymentResult.GatewayError` /
  `RefundOrderResult.GatewayError` — `server/src/main/kotlin/com/beettechnologies/posly/payments/PaymentGatewayService.kt`).
  Critically, **the order itself is never touched** on a gateway error at creation time — no
  partial state, safe to retry the whole request.

## Detection

- **Alert:** the existing `PoslyPayments5xxRateHigh` rule (`infra/observability/prometheus/alerts.yml`)
  — `status=~"5.."` on `/payments.*` routes — already captures this, since gateway failures
  surface as 502s. There's no gateway-specific metric (e.g. a dedicated failure counter) beyond
  this route-level 5xx rate; see Known limitations.
- **Contextual data:** every request carries a correlation ID (`X-Correlation-Id`, propagated per
  `docs/runbooks/observability-alerts.md`'s correlation-id flow). Pull the specific failing
  requests:
  ```
  correlationId:<value>  service:posly-server  path:/payments*
  ```
  in your centralized logs, and check `GET /payments/refunds/unresolved` (ADMIN/MANAGER) for any
  refund attempts left in an ambiguous state pending manual resolution
  (`PaymentGatewayService.listUnresolvedRefunds`).

## Response steps

1. Acknowledge the page. Confirm scope: is this affecting **all** payment creation/refunds, or a
   subset (e.g. only refunds, only one store)? Check the 5xx rate split by route
   (`/payments` vs `/payments/{id}/refund`) and by any store-identifying fields in the logs.
2. Check whether the gateway vendor has a public status page / incident notice — a genuine
   third-party outage is the most likely cause once this integration is a real vendor, not the
   simulator.
3. **Cashier-facing guidance during the outage:** checkout itself does not require an immediately
   successful card payment — cash payments are unaffected. For card payments, cashiers should be
   told to hold off on retrying repeatedly (each attempt pays the full 3-retry backoff cost) and
   instead wait for an "all clear," to avoid compounding load on a struggling/recovering gateway.
4. If refunds specifically are affected mid-attempt, check `GET /payments/refunds/unresolved` —
   these are refund attempts where the gateway's outcome is unknown, requiring manual
   verification with the vendor before resolving them one way or the other. Do **not** re-attempt
   a refund whose outcome is unresolved without first confirming with the vendor whether the
   original attempt actually completed (double-refund risk).
5. Once the gateway recovers, confirm the 5xx rate on `/payments.*` returns to baseline and any
   unresolved refunds have been manually reconciled.

## Known limitations (explicitly disclosed, not fabricated)

- **No real payment gateway/terminal vendor is integrated anywhere in this codebase** — every
  environment runs `SimulatorPaymentGateway`, which never actually fails in production traffic (it
  only fails on-demand via a constructor parameter, `transientFailuresBeforeSuccess`, used by
  `PaymentGatewayServiceTest` — not exposed via any runtime API or admin toggle). This runbook
  describes response steps for when a *real* vendor integration lands; it cannot be rehearsed
  end-to-end against a running instance today the way `docs/runbooks/terminal-offline.md`'s
  game-day drill can (see `scripts/gameday/`).
- **No circuit breaker.** Every request during an outage pays the full retry cost before failing,
  rather than failing fast once the gateway is known to be down. Worth adding
  (e.g. via `gateway/RetryPolicy.kt` gaining a sibling `CircuitBreaker`) if a real integration's
  outage history shows this retry cost matters in practice — not built speculatively here.
- **No gateway-specific metric** (e.g. `payment_gateway_failures_total`) exists — detection relies
  entirely on the route-level 5xx rate, which conflates a gateway outage with any other cause of
  `/payments` 5xx responses (a bug, an auth issue, etc.). Worth adding once there's a real
  integration whose specific failure modes are worth distinguishing from generic 5xx.
