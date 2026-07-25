# Observability Alerts Runbook

## Scope

This runbook covers auth/payments API alerts, trace export issues, and correlation-id based debugging.

## Alert response steps

1. Acknowledge the page in the on-call system.
2. Open Grafana dashboards:
   - `infra/observability/grafana/dashboards/auth-dashboard.json`
   - `infra/observability/grafana/dashboards/payments-dashboard.json`
3. Confirm impacted endpoint(s), error rate, and latency trend.
4. Check logs in centralized logging (CloudWatch/Datadog/ELK) using:
   - `service:posly-server`
   - `correlationId:<value>`
5. Check traces in Jaeger/Datadog APM filtered by `service.name=posly-server` and `correlation.id=<value>`.
6. If issue persists, roll back the latest deployment using `/docs/runbook-deploy.md` rollback section.

## Correlation-id debugging flow

1. Obtain `X-Correlation-Id` from API client, gateway, or response header.
2. Search logs by `correlationId`.
3. Open corresponding trace and inspect spans tagged with `correlation.id`.
4. Record root cause and impacted endpoints in incident notes.

## Synthetic verification checklist

1. Send a request with `X-Correlation-Id: synthetic-obs-test`.
2. Verify `/metrics` emits `ktor_http_server_requests_seconds_count` for the endpoint.
3. Verify centralized logs contain `correlationId=synthetic-obs-test`.
4. Verify trace backend receives span with `correlation.id=synthetic-obs-test`.
5. Force repeated 5xx responses and confirm alert rule fires.

## Environment variables required for tracing export

- `OTEL_SERVICE_NAME` (optional; defaults to `posly-server`)
- `OTEL_EXPORTER_OTLP_ENDPOINT`
- `OTEL_EXPORTER_OTLP_HEADERS` (for auth credentials)
- `OTEL_TRACES_EXPORTER` (e.g. `otlp`)

