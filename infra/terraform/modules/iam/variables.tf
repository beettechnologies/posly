variable "env" {
  description = "Environment name (dev | stage | prod)"
  type        = string
}

variable "ci_principal_arn" {
  description = "ARN of the IAM principal (user/role) used by CI/CD to assume the deploy role"
  type        = string
}

variable "ci_external_id" {
  description = "External ID required when CI/CD assumes the deploy role"
  type        = string
  sensitive   = true
}

variable "tags" {
  description = "Common tags applied to all resources"
  type        = map(string)
  default     = {}
}
