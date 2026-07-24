output "db_url_secret_arn" {
  description = "ARN of the database URL secret"
  value       = aws_secretsmanager_secret.db_url.arn
}

output "app_secret_key_arn" {
  description = "ARN of the application secret key"
  value       = aws_secretsmanager_secret.app_secret_key.arn
}

output "jwt_signing_key_arn" {
  description = "ARN of the JWT signing key"
  value       = aws_secretsmanager_secret.jwt_signing_key.arn
}
