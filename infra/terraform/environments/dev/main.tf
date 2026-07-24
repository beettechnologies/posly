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
    key            = "dev/terraform.tfstate"
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
  env = "dev"
  common_tags = {
    Project     = "posly"
    Environment = local.env
    ManagedBy   = "terraform"
  }
}

# ---------------------------------------------------------------
# Networking
# ---------------------------------------------------------------
module "networking" {
  source = "../../modules/networking"

  env                = local.env
  vpc_cidr           = var.vpc_cidr
  availability_zones = var.availability_zones
  tags               = local.common_tags
}

# ---------------------------------------------------------------
# IAM / RBAC
# ---------------------------------------------------------------
module "iam" {
  source = "../../modules/iam"

  env              = local.env
  ci_principal_arn = var.ci_principal_arn
  ci_external_id   = var.ci_external_id
  tags             = local.common_tags
}

# ---------------------------------------------------------------
# Secrets Management
# ---------------------------------------------------------------
module "secrets" {
  source = "../../modules/secrets"

  env  = local.env
  tags = local.common_tags
}

# ---------------------------------------------------------------
# ECS (application)
# ---------------------------------------------------------------
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

  task_cpu           = 256
  task_memory        = 512
  desired_count      = 1
  min_capacity       = 1
  max_capacity       = 3
  log_retention_days = 14
  create_ecr         = true  # ECR repository created once in dev

  tags = local.common_tags
}
