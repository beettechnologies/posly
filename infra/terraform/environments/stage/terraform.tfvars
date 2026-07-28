aws_region         = "us-east-1"
vpc_cidr           = "10.1.0.0/16"
availability_zones = ["us-east-1a", "us-east-1b"]

server_image     = "REPLACE_WITH_ECR_IMAGE_URI"
ci_principal_arn = "REPLACE_WITH_CI_PRINCIPAL_ARN"

# Set to a real address (or remove to skip the subscription) before relying on these alerts.
alert_email = ""

monthly_budget_usd = 500
