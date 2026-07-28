output "ops_alerts_topic_arn" {
  description = "SNS topic ARN that every capacity/cost alarm publishes to"
  value       = aws_sns_topic.ops_alerts.arn
}
