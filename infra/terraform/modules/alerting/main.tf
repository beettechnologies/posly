terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

# ---------------------------------------------------------------
# SNS topic - the single fan-out point for every alarm below.
# Wire a real on-call integration (PagerDuty/Opsgenie/Slack) onto
# this topic before relying on it in production; today it only
# supports an optional plain email subscription.
# ---------------------------------------------------------------
resource "aws_sns_topic" "ops_alerts" {
  name = "posly-${var.env}-ops-alerts"
  tags = var.tags
}

resource "aws_sns_topic_subscription" "ops_alerts_email" {
  count     = var.alert_email != "" ? 1 : 0
  topic_arn = aws_sns_topic.ops_alerts.arn
  protocol  = "email"
  endpoint  = var.alert_email
}

# AWS Budgets publishes to SNS as the budgets.amazonaws.com service principal, not as an IAM
# principal - without this policy, budget notifications to the topic are silently undelivered.
data "aws_iam_policy_document" "ops_alerts_topic_policy" {
  statement {
    sid       = "AllowBudgetsPublish"
    effect    = "Allow"
    actions   = ["SNS:Publish"]
    resources = [aws_sns_topic.ops_alerts.arn]

    principals {
      type        = "Service"
      identifiers = ["budgets.amazonaws.com"]
    }
  }

  statement {
    sid       = "AllowCloudWatchPublish"
    effect    = "Allow"
    actions   = ["SNS:Publish"]
    resources = [aws_sns_topic.ops_alerts.arn]

    principals {
      type        = "Service"
      identifiers = ["cloudwatch.amazonaws.com"]
    }
  }
}

resource "aws_sns_topic_policy" "ops_alerts" {
  arn    = aws_sns_topic.ops_alerts.arn
  policy = data.aws_iam_policy_document.ops_alerts_topic_policy.json
}

# ---------------------------------------------------------------
# CloudWatch alarms - capacity signals
# ---------------------------------------------------------------

# Fires when the service is running hot despite auto-scaling already reacting (target is 70%,
# see modules/ecs) - a sign scale-out isn't keeping pace with demand, not just routine scaling.
resource "aws_cloudwatch_metric_alarm" "ecs_cpu_high" {
  alarm_name          = "posly-${var.env}-ecs-cpu-approaching-ceiling"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 3
  period              = 300
  namespace           = "AWS/ECS"
  metric_name         = "CPUUtilization"
  statistic           = "Average"
  threshold           = var.cpu_alarm_threshold
  treat_missing_data  = "notBreaching"

  dimensions = {
    ClusterName = var.cluster_name
    ServiceName = var.service_name
  }

  alarm_description = "ECS service CPU utilization has stayed above ${var.cpu_alarm_threshold}% for 15m despite auto-scaling. See docs/runbooks/capacity-scale-incidents.md."
  alarm_actions     = [aws_sns_topic.ops_alerts.arn]
  ok_actions        = [aws_sns_topic.ops_alerts.arn]
  tags              = var.tags
}

resource "aws_cloudwatch_metric_alarm" "ecs_memory_high" {
  alarm_name          = "posly-${var.env}-ecs-memory-approaching-ceiling"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 3
  period              = 300
  namespace           = "AWS/ECS"
  metric_name         = "MemoryUtilization"
  statistic           = "Average"
  threshold           = var.memory_alarm_threshold
  treat_missing_data  = "notBreaching"

  dimensions = {
    ClusterName = var.cluster_name
    ServiceName = var.service_name
  }

  alarm_description = "ECS service memory utilization has stayed above ${var.memory_alarm_threshold}% for 15m. See docs/runbooks/capacity-scale-incidents.md."
  alarm_actions     = [aws_sns_topic.ops_alerts.arn]
  ok_actions        = [aws_sns_topic.ops_alerts.arn]
  tags              = var.tags
}

# Fires when the service has been sitting at its auto-scaling max_capacity for a sustained
# period - i.e. scale-out has already used up all the headroom this environment is configured
# for, and the only remaining lever is raising max_capacity or shedding load (see the
# heavy-analytics kill switch in server/src/main/kotlin/com/beettechnologies/posly/capacity).
# Sourced from the ECS/ContainerInsights namespace (enabled on the cluster in modules/ecs) since
# AWS/ECS does not publish a running-task-count metric.
resource "aws_cloudwatch_metric_alarm" "ecs_at_max_capacity" {
  alarm_name          = "posly-${var.env}-ecs-at-max-capacity"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = 3
  period              = 300
  namespace           = "ECS/ContainerInsights"
  metric_name         = "RunningTaskCount"
  statistic           = "Average"
  threshold           = var.max_capacity
  treat_missing_data  = "notBreaching"

  dimensions = {
    ClusterName = var.cluster_name
    ServiceName = var.service_name
  }

  alarm_description = "ECS service has run at its configured max_capacity (${var.max_capacity} tasks) for 15m - auto-scaling has no more headroom. See docs/runbooks/capacity-scale-incidents.md."
  alarm_actions     = [aws_sns_topic.ops_alerts.arn]
  ok_actions        = [aws_sns_topic.ops_alerts.arn]
  tags              = var.tags
}

# ---------------------------------------------------------------
# Cost alert
# ---------------------------------------------------------------
resource "aws_budgets_budget" "monthly_cost" {
  name         = "posly-${var.env}-monthly-cost"
  budget_type  = "COST"
  limit_amount = tostring(var.monthly_budget_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  notification {
    comparison_operator       = "GREATER_THAN"
    threshold                 = 80
    threshold_type            = "PERCENTAGE"
    notification_type         = "ACTUAL"
    subscriber_sns_topic_arns = [aws_sns_topic.ops_alerts.arn]
  }

  notification {
    comparison_operator       = "GREATER_THAN"
    threshold                 = 100
    threshold_type            = "PERCENTAGE"
    notification_type         = "FORECASTED"
    subscriber_sns_topic_arns = [aws_sns_topic.ops_alerts.arn]
  }
}
