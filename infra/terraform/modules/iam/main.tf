terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
  region     = data.aws_region.current.name
}

# ---------------------------------------------------------------
# ECS Task Execution Role
# Allows ECS agent to pull images and write logs.
# ---------------------------------------------------------------
resource "aws_iam_role" "ecs_task_execution" {
  name = "${var.env}-posly-ecs-task-execution-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })

  tags = var.tags
}

resource "aws_iam_role_policy_attachment" "ecs_task_execution_managed" {
  role       = aws_iam_role.ecs_task_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# Allow the execution role to read secrets from Secrets Manager
resource "aws_iam_role_policy" "ecs_task_execution_secrets" {
  name = "${var.env}-posly-ecs-execution-secrets-policy"
  role = aws_iam_role.ecs_task_execution.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = [
        "secretsmanager:GetSecretValue",
        "ssm:GetParameters"
      ]
      Resource = [
        "arn:aws:secretsmanager:${local.region}:${local.account_id}:secret:posly/${var.env}/*"
      ]
    }]
  })
}

# ---------------------------------------------------------------
# ECS Task Role
# Permissions held by the running application itself.
# ---------------------------------------------------------------
resource "aws_iam_role" "ecs_task" {
  name = "${var.env}-posly-ecs-task-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })

  tags = var.tags
}

# Minimal application-level policy — extend as needed
resource "aws_iam_role_policy" "ecs_task_app" {
  name = "${var.env}-posly-ecs-task-app-policy"
  role = aws_iam_role.ecs_task.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "logs:CreateLogStream",
          "logs:PutLogEvents"
        ]
        Resource = "arn:aws:logs:${local.region}:${local.account_id}:log-group:/posly/${var.env}/*"
      }
    ]
  })
}

# ---------------------------------------------------------------
# CI/CD Deploy Role
# Assumed by GitHub Actions to deploy to this environment.
# ---------------------------------------------------------------
resource "aws_iam_role" "deploy" {
  name = "${var.env}-posly-deploy-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = {
        AWS = var.ci_principal_arn
      }
      Action = "sts:AssumeRole"
      Condition = {
        StringEquals = {
          "sts:ExternalId" = var.ci_external_id
        }
      }
    }]
  })

  tags = var.tags
}

resource "aws_iam_role_policy" "deploy" {
  name = "${var.env}-posly-deploy-policy"
  role = aws_iam_role.deploy.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        # ECR: allow push/pull of images
        Effect = "Allow"
        Action = [
          "ecr:GetAuthorizationToken",
          "ecr:BatchCheckLayerAvailability",
          "ecr:InitiateLayerUpload",
          "ecr:UploadLayerPart",
          "ecr:CompleteLayerUpload",
          "ecr:PutImage",
          "ecr:BatchGetImage"
        ]
        Resource = "*"
      },
      {
        # ECS: allow updating services and registering task definitions
        Effect = "Allow"
        Action = [
          "ecs:RegisterTaskDefinition",
          "ecs:DescribeTaskDefinition",
          "ecs:UpdateService",
          "ecs:DescribeServices",
          "ecs:DescribeClusters"
        ]
        Resource = "*"
      },
      {
        # Allow passing roles to ECS tasks
        Effect   = "Allow"
        Action   = "iam:PassRole"
        Resource = [
          aws_iam_role.ecs_task_execution.arn,
          aws_iam_role.ecs_task.arn
        ]
      }
    ]
  })
}
