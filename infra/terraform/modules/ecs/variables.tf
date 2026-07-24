variable "env" {
  description = "Environment name (dev | stage | prod)"
  type        = string
}

variable "aws_region" {
  description = "AWS region"
  type        = string
}

variable "server_image" {
  description = "Full Docker image URI for the Ktor server"
  type        = string
}

variable "task_execution_role_arn" {
  description = "ARN of the ECS task execution role"
  type        = string
}

variable "task_role_arn" {
  description = "ARN of the ECS task role"
  type        = string
}

variable "private_subnet_ids" {
  description = "Private subnet IDs for ECS tasks"
  type        = list(string)
}

variable "ecs_tasks_sg_id" {
  description = "Security group ID for ECS tasks"
  type        = string
}

variable "target_group_arn" {
  description = "ARN of the ALB target group"
  type        = string
}

variable "db_url_secret_arn" {
  description = "ARN of the DB URL secret"
  type        = string
}

variable "app_secret_key_arn" {
  description = "ARN of the application secret key secret"
  type        = string
}

variable "jwt_signing_key_arn" {
  description = "ARN of the JWT signing key secret"
  type        = string
}

variable "task_cpu" {
  description = "CPU units for the ECS task (256 = 0.25 vCPU)"
  type        = number
  default     = 512
}

variable "task_memory" {
  description = "Memory in MiB for the ECS task"
  type        = number
  default     = 1024
}

variable "desired_count" {
  description = "Desired number of running task instances"
  type        = number
  default     = 1
}

variable "min_capacity" {
  description = "Minimum number of tasks for auto-scaling"
  type        = number
  default     = 1
}

variable "max_capacity" {
  description = "Maximum number of tasks for auto-scaling"
  type        = number
  default     = 4
}

variable "log_retention_days" {
  description = "Number of days to retain CloudWatch logs"
  type        = number
  default     = 30
}

variable "create_ecr" {
  description = "Whether to create the ECR repository (only needed once, typically in dev)"
  type        = bool
  default     = false
}

variable "tags" {
  description = "Common tags applied to all resources"
  type        = map(string)
  default     = {}
}
