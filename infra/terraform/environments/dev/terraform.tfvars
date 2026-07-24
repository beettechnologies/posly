aws_region         = "us-east-1"
vpc_cidr           = "10.0.0.0/16"
availability_zones = ["us-east-1a", "us-east-1b"]

# Set server_image to your ECR URI, e.g.:
# server_image = "123456789012.dkr.ecr.us-east-1.amazonaws.com/posly-server:latest"
server_image = "REPLACE_WITH_ECR_IMAGE_URI"

# ARN of the CI/CD IAM user/role that will assume the deploy role
ci_principal_arn = "REPLACE_WITH_CI_PRINCIPAL_ARN"
