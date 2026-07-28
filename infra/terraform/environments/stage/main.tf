terraform {
  required_version = ">= 1.5"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  backend "s3" {
    bucket         = "posly-terraform-state"
    key            = "stage/terraform.tfstate"
    region         = "us-east-1"
    encrypt        = true
    dynamodb_table = "posly-terraform-locks"
  }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = local.common_tags
  }
}

locals {
  env = "stage"
  common_tags = {
    Project     = "posly"
    Environment = local.env
    ManagedBy   = "terraform"
  }
}

module "networking" {
  source = "../../modules/networking"

  env                = local.env
  vpc_cidr           = var.vpc_cidr
  availability_zones = var.availability_zones
  tags               = local.common_tags
}

module "iam" {
  source = "../../modules/iam"

  env              = local.env
  ci_principal_arn = var.ci_principal_arn
  ci_external_id   = var.ci_external_id
  tags             = local.common_tags
}

module "secrets" {
  source = "../../modules/secrets"

  env  = local.env
  tags = local.common_tags
}

module "ecs" {
  source = "../../modules/ecs"

  env        = local.env
  aws_region = var.aws_region

  server_image            = var.server_image
  task_execution_role_arn = module.iam.task_execution_role_arn
  task_role_arn           = module.iam.task_role_arn

  private_subnet_ids = module.networking.private_subnet_ids
  ecs_tasks_sg_id    = module.networking.ecs_tasks_sg_id
  target_group_arn   = module.networking.target_group_arn

  db_url_secret_arn   = module.secrets.db_url_secret_arn
  app_secret_key_arn  = module.secrets.app_secret_key_arn
  jwt_signing_key_arn = module.secrets.jwt_signing_key_arn

  task_cpu           = 512
  task_memory        = 1024
  desired_count      = 1
  min_capacity       = 1
  max_capacity       = 4
  log_retention_days = 30
  create_ecr         = false

  tags = local.common_tags
}

# ---------------------------------------------------------------
# Alerting (capacity + cost)
# ---------------------------------------------------------------
module "alerting" {
  source = "../../modules/alerting"

  env          = local.env
  cluster_name = module.ecs.cluster_name
  service_name = module.ecs.service_name
  max_capacity = 4

  alert_email        = var.alert_email
  monthly_budget_usd = var.monthly_budget_usd

  tags = local.common_tags
}
