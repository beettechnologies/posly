variable "env" {
  description = "Environment name (dev | stage | prod)"
  type        = string
}

variable "cluster_name" {
  description = "ECS cluster name being monitored (module.ecs.cluster_name)"
  type        = string
}

variable "service_name" {
  description = "ECS service name being monitored (module.ecs.service_name)"
  type        = string
}

variable "max_capacity" {
  description = "The ECS service's auto-scaling max_capacity - the 'at capacity ceiling' alarm fires when RunningTaskCount reaches this for a sustained period, i.e. auto-scaling has nowhere left to go"
  type        = number
}

variable "cpu_alarm_threshold" {
  description = "ECS service CPU utilization (%) that, sustained, triggers the 'approaching autoscale ceiling' alarm - deliberately above the 70% autoscaling target (see modules/ecs's aws_appautoscaling_policy.cpu) so this only pages when scaling out isn't keeping up, not on every routine scale-out event"
  type        = number
  default     = 85
}

variable "memory_alarm_threshold" {
  description = "ECS service memory utilization (%) that, sustained, triggers an alarm"
  type        = number
  default     = 85
}

variable "alert_email" {
  description = "Email address subscribed to the ops-alerts SNS topic and to budget notifications. Empty string skips the subscription (e.g. wire a real on-call integration here instead before going to production)."
  type        = string
  default     = ""
}

variable "monthly_budget_usd" {
  description = "Monthly AWS cost budget (USD) for this environment's cost alert. Not scoped by cost-allocation tags - that requires activating cost allocation tags in the AWS Billing console first, a manual account-level step outside Terraform's control, so this budget currently reflects total account spend, not just this environment's resources."
  type        = number
}

variable "tags" {
  description = "Common tags applied to all resources"
  type        = map(string)
  default     = {}
}
