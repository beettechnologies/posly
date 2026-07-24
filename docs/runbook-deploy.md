# Posly Deployment Runbook

## Overview

This runbook covers provisioning infrastructure, deploying the Posly application,
performing smoke tests, and rolling back a failed deployment.

**Stack:** Kotlin Multiplatform / Ktor server · AWS ECS Fargate · Terraform · GitHub Actions

---

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Bootstrap: Terraform State Backend](#bootstrap-terraform-state-backend)
3. [Provision an Environment](#provision-an-environment)
4. [Configure Secrets](#configure-secrets)
5. [CI Pipeline (Pull Requests)](#ci-pipeline-pull-requests)
6. [Deploy to dev / stage](#deploy-to-dev--stage)
7. [Deploy to prod](#deploy-to-prod)
8. [Smoke Tests](#smoke-tests)
9. [Rollback](#rollback)
10. [Monitoring & Logs](#monitoring--logs)
11. [Troubleshooting](#troubleshooting)

---

## Prerequisites

| Tool | Version |
|------|---------|
| Terraform | ≥ 1.5 |
| AWS CLI | ≥ 2.x |
| Docker | ≥ 24 |
| JDK | 21 |
| jq | any recent |

AWS credentials must be configured for each environment account.
Minimum IAM permissions: create VPC, ECS, IAM, Secrets Manager, ECR, CloudWatch Logs resources.

---

## Bootstrap: Terraform State Backend

Run this **once per AWS account** before applying any environment.

```bash
# Create the S3 bucket for Terraform state
aws s3api create-bucket \
  --bucket posly-terraform-state \
  --region us-east-1 \
  --create-bucket-configuration LocationConstraint=us-east-1

# Enable versioning
aws s3api put-bucket-versioning \
  --bucket posly-terraform-state \
  --versioning-configuration Status=Enabled

# Enable server-side encryption
aws s3api put-bucket-encryption \
  --bucket posly-terraform-state \
  --server-side-encryption-configuration \
    '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"aws:kms"}}]}'

# Create the DynamoDB lock table
aws dynamodb create-table \
  --table-name posly-terraform-locks \
  --attribute-definitions AttributeName=LockID,AttributeType=S \
  --key-schema AttributeName=LockID,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST \
  --region us-east-1
```

---

## Provision an Environment

```bash
cd infra/terraform/environments/<env>   # dev | stage | prod

# Edit terraform.tfvars — fill in ECR image URI and CI principal ARN
vi terraform.tfvars

terraform init
terraform plan -out=tfplan
terraform apply tfplan
```

Outputs include the ALB DNS name, ECS cluster/service names, and ECR URL (dev only).

---

## Configure Secrets

Secrets are **never stored in Terraform state** — populate them separately after `terraform apply`:

```bash
ENV=dev   # or stage | prod

aws secretsmanager put-secret-value \
  --secret-id "posly/$ENV/db-url" \
  --secret-string "******host:5432/posly"

aws secretsmanager put-secret-value \
  --secret-id "posly/$ENV/app-secret-key" \
  --secret-string "$(openssl rand -hex 32)"

aws secretsmanager put-secret-value \
  --secret-id "posly/$ENV/jwt-signing-key" \
  --secret-string "$(openssl rand -hex 64)"
```

### GitHub Actions secrets

Add the following repository/environment secrets in **Settings → Secrets and variables → Actions**:

| Secret | Description |
|--------|-------------|
| `AWS_ACCESS_KEY_ID` | Shared ECR push credentials |
| `AWS_SECRET_ACCESS_KEY` | Shared ECR push credentials |
| `DEV_AWS_ACCESS_KEY_ID` | Dev account credentials |
| `DEV_AWS_SECRET_ACCESS_KEY` | Dev account credentials |
| `DEV_DEPLOY_ROLE_ARN` | Output of `module.iam.deploy_role_arn` for dev |
| `STAGE_AWS_ACCESS_KEY_ID` | Stage account credentials |
| `STAGE_AWS_SECRET_ACCESS_KEY` | Stage account credentials |
| `STAGE_DEPLOY_ROLE_ARN` | Output of `module.iam.deploy_role_arn` for stage |
| `PROD_AWS_ACCESS_KEY_ID` | Prod account credentials |
| `PROD_AWS_SECRET_ACCESS_KEY` | Prod account credentials |
| `PROD_DEPLOY_ROLE_ARN` | Output of `module.iam.deploy_role_arn` for prod |

---

## CI Pipeline (Pull Requests)

The CI workflow (`.github/workflows/ci.yml`) runs automatically on every pull request:

1. **Lint & Test** — `ktlintCheck` + Gradle unit tests (server, sharedLogic, core)
2. **Security Scan** — Trivy scans the Docker image and the filesystem for CRITICAL/HIGH CVEs

All jobs must pass before a PR can be merged.
SARIF results are uploaded to the GitHub Security tab for review.

---

## Deploy to dev / stage

Deployments to dev and stage trigger **automatically** on every push to `main`:

1. The `build-and-push` job builds a fat JAR, builds the Docker image, and pushes it to ECR.
2. `deploy-dev` updates the ECS service and waits for stabilisation, then runs smoke tests.
3. If dev passes, `deploy-stage` promotes the same image tag automatically.

You can also trigger manually:

```
GitHub → Actions → Deploy → Run workflow
  environment: dev   (or stage)
  image_tag:   <optional SHA or tag>
```

---

## Deploy to prod

Prod deployments require **manual dispatch** with `environment: prod` and prior stage success:

```
GitHub → Actions → Deploy → Run workflow
  environment: prod
  image_tag:   <SHA that passed stage>
```

The `prod` GitHub environment should be configured with a **required reviewer** for
additional approval before the job runs.

---

## Smoke Tests

After every deployment the pipeline runs `scripts/smoke-test.sh <BASE_URL>`:

- `GET /health` → HTTP 200
- `GET /` → HTTP 200

To run smoke tests manually:

```bash
bash scripts/smoke-test.sh https://dev.posly.example.com
```

Exit code 0 = all checks passed. The script retries each check up to 10 times.

---

## Rollback

### Automatic rollback (pipeline)

The deploy workflow automatically rolls back ECS to the previous task definition revision
when either the ECS wait or the smoke test fails.

### Manual rollback via AWS CLI

```bash
ENV=dev
CLUSTER="posly-$ENV"
SERVICE="posly-server-$ENV"
TASK_FAMILY="posly-server-$ENV"

# List the last 5 revisions
aws ecs list-task-definitions \
  --family-prefix "$TASK_FAMILY" \
  --sort DESC \
  --max-items 5

# Roll back to a specific revision (e.g. revision 7)
aws ecs update-service \
  --cluster "$CLUSTER" \
  --service "$SERVICE" \
  --task-definition "$TASK_FAMILY:7" \
  --force-new-deployment

aws ecs wait services-stable \
  --cluster "$CLUSTER" \
  --services "$SERVICE"
```

### Terraform rollback (infrastructure only)

If an infrastructure change needs to be reverted:

```bash
cd infra/terraform/environments/<env>
git checkout <previous-commit> -- .
terraform apply
```

---

## Monitoring & Logs

| Resource | How to access |
|----------|---------------|
| Application logs | AWS CloudWatch → Log groups → `/posly/<env>/server` |
| ECS service events | AWS ECS console → Cluster → Service → Events tab |
| ALB access logs | Enable in the ALB attributes if needed |
| Container Insights | AWS CloudWatch → Container Insights → ECS |
| Prometheus metrics | `GET https://<env-host>/metrics` or scrape the task target on port `8080` |
| Grafana dashboards | Import `/home/runner/work/posly/posly/infra/observability/grafana/auth-dashboard.json` and `/home/runner/work/posly/posly/infra/observability/grafana/device-dashboard.json` |
| Prometheus alerts | Load `/home/runner/work/posly/posly/infra/observability/prometheus/alerts.yml` into the Prometheus or Alertmanager stack |
| Traces | Query Jaeger or the shared OTLP backend with service `posly-server` |

Stream live logs:

```bash
aws logs tail "/posly/dev/server" --follow
```

### Correlation IDs and trace flow

- Every inbound request accepts or generates `X-Correlation-Id`.
- The service returns both `X-Correlation-Id` and `X-Trace-Id` in response headers.
- Structured JSON logs include `correlationId`, `traceId`, `spanId`, `service`, and `environment`.
- Search Jaeger using `X-Trace-Id` or the `correlation.id` span attribute.

### Prometheus and Grafana bootstrap

Use `/home/runner/work/posly/posly/infra/observability/prometheus/scrape-config.yml` as the baseline scrape config.
Set the Terraform variable `observability_otlp_endpoint` for each environment to export spans to the shared collector or Jaeger OTLP endpoint.

### Suggested validation

1. Send a synthetic request with a known `X-Correlation-Id`.
2. Confirm `/metrics` shows `ktor_http_server_requests_seconds_*` and the relevant `posly_*` business counter.
3. Confirm CloudWatch log entries contain the same `correlationId` and `traceId`.
4. Find the trace in Jaeger using `X-Trace-Id` or `correlation.id`.
5. Trigger repeated auth failures until `PoslyAuthFailureSpike` fires.

### Scope note

This repository currently contains auth and device flows only. A payments dashboard must be added in the payments service repository or after that service is introduced here.

---

## Troubleshooting

| Symptom | Likely cause | Action |
|---------|-------------|--------|
| ECS service stuck in PENDING | Secret ARN wrong or missing | Check `posly/<env>/*` secrets exist in Secrets Manager |
| Task fails health check | App not listening on `/health` | Confirm `GET /health` returns 200; check CloudWatch logs |
| Image pull error | ECR auth expired | Re-run `aws ecr get-login-password` or check execution role |
| Smoke test times out | ALB not routing | Verify security groups and target group health |
| Terraform plan fails on state lock | Previous run crashed | `terraform force-unlock <LOCK_ID>` |
