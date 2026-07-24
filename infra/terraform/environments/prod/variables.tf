variable "aws_region" {
  description = "AWS region"
  type        = string
  default     = "us-east-1"
}

variable "vpc_cidr" {
  description = "CIDR block for the VPC"
  type        = string
}

variable "availability_zones" {
  description = "Availability Zones"
  type        = list(string)
}

variable "server_image" {
  description = "Full Docker image URI for the Ktor server"
  type        = string
}

variable "ci_principal_arn" {
  description = "ARN of the IAM principal used by CI/CD to assume the deploy role"
  type        = string
}

variable "ci_external_id" {
  description = "External ID for CI/CD role assumption"
  type        = string
  sensitive   = true
}

variable "observability_otlp_endpoint" {
  description = "Optional OTLP endpoint for trace export"
  type        = string
  default     = ""
}
