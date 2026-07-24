terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

# ---------------------------------------------------------------
# Application secrets stored in AWS Secrets Manager.
# Values are populated outside Terraform (e.g., via the AWS
# console, CLI, or a separate sealed-secrets process) to avoid
# storing plaintext secrets in state or source control.
# ---------------------------------------------------------------

resource "aws_secretsmanager_secret" "db_url" {
  name                    = "posly/${var.env}/db-url"
  description             = "Database connection URL for Posly ${var.env}"
  recovery_window_in_days = var.env == "prod" ? 30 : 7

  tags = merge(var.tags, { SecretType = "db" })
}

resource "aws_secretsmanager_secret" "app_secret_key" {
  name                    = "posly/${var.env}/app-secret-key"
  description             = "Application secret key for Posly ${var.env}"
  recovery_window_in_days = var.env == "prod" ? 30 : 7

  tags = merge(var.tags, { SecretType = "app" })
}

resource "aws_secretsmanager_secret" "jwt_signing_key" {
  name                    = "posly/${var.env}/jwt-signing-key"
  description             = "JWT signing key for Posly ${var.env}"
  recovery_window_in_days = var.env == "prod" ? 30 : 7

  tags = merge(var.tags, { SecretType = "auth" })
}
