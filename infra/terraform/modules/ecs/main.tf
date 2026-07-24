terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

# ---------------------------------------------------------------
# CloudWatch Log Group
# ---------------------------------------------------------------
resource "aws_cloudwatch_log_group" "server" {
  name              = "/posly/${var.env}/server"
  retention_in_days = var.log_retention_days

  tags = var.tags
}

# ---------------------------------------------------------------
# ECR Repository (shared across environments)
# ---------------------------------------------------------------
resource "aws_ecr_repository" "server" {
  count                = var.create_ecr ? 1 : 0
  name                 = "posly-server"
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  tags = var.tags
}

resource "aws_ecr_lifecycle_policy" "server" {
  count      = var.create_ecr ? 1 : 0
  repository = aws_ecr_repository.server[0].name

  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep only the last 20 images"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 20
      }
      action = { type = "expire" }
    }]
  })
}

# ---------------------------------------------------------------
# ECS Cluster
# ---------------------------------------------------------------
resource "aws_ecs_cluster" "main" {
  name = "posly-${var.env}"

  setting {
    name  = "containerInsights"
    value = "enabled"
  }

  tags = var.tags
}

resource "aws_ecs_cluster_capacity_providers" "main" {
  cluster_name       = aws_ecs_cluster.main.name
  capacity_providers = ["FARGATE", "FARGATE_SPOT"]

  default_capacity_provider_strategy {
    capacity_provider = var.env == "prod" ? "FARGATE" : "FARGATE_SPOT"
    weight            = 1
  }
}

# ---------------------------------------------------------------
# ECS Task Definition
# ---------------------------------------------------------------
resource "aws_ecs_task_definition" "server" {
  family                   = "posly-server-${var.env}"
  cpu                      = var.task_cpu
  memory                   = var.task_memory
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  execution_role_arn       = var.task_execution_role_arn
  task_role_arn            = var.task_role_arn

  container_definitions = jsonencode([{
    name      = "server"
    image     = var.server_image
    essential = true

    portMappings = [{
      containerPort = 8080
      protocol      = "tcp"
    }]

    environment = [
      { name = "ENVIRONMENT", value = var.env },
      { name = "PORT",        value = "8080"  },
      { name = "OBSERVABILITY_SERVICE_NAME", value = "posly-server" },
      { name = "OBSERVABILITY_METRICS_PATH", value = var.observability_metrics_path },
      { name = "OTEL_EXPORTER_OTLP_ENDPOINT", value = var.observability_otlp_endpoint }
    ]

    dockerLabels = {
      "prometheus.io/scrape" = "true"
      "prometheus.io/path"   = var.observability_metrics_path
      "prometheus.io/port"   = "8080"
      "service.name"         = "posly-server"
    }

    secrets = [
      {
        name      = "DB_URL"
        valueFrom = var.db_url_secret_arn
      },
      {
        name      = "APP_SECRET_KEY"
        valueFrom = var.app_secret_key_arn
      },
      {
        name      = "JWT_SIGNING_KEY"
        valueFrom = var.jwt_signing_key_arn
      }
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.server.name
        "awslogs-region"        = var.aws_region
        "awslogs-stream-prefix" = "ecs"
      }
    }

    healthCheck = {
      command     = ["CMD-SHELL", "curl -f http://localhost:8080/health || exit 1"]
      interval    = 30
      timeout     = 5
      retries     = 3
      startPeriod = 15
    }
  }])

  tags = var.tags
}

# ---------------------------------------------------------------
# ECS Service
# ---------------------------------------------------------------
resource "aws_ecs_service" "server" {
  name                               = "posly-server-${var.env}"
  cluster                            = aws_ecs_cluster.main.id
  task_definition                    = aws_ecs_task_definition.server.arn
  desired_count                      = var.desired_count
  launch_type                        = "FARGATE"
  health_check_grace_period_seconds  = 60
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [var.ecs_tasks_sg_id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = var.target_group_arn
    container_name   = "server"
    container_port   = 8080
  }

  lifecycle {
    ignore_changes = [task_definition, desired_count]
  }

  tags = var.tags
}

# ---------------------------------------------------------------
# Auto-scaling
# ---------------------------------------------------------------
resource "aws_appautoscaling_target" "server" {
  max_capacity       = var.max_capacity
  min_capacity       = var.min_capacity
  resource_id        = "service/${aws_ecs_cluster.main.name}/${aws_ecs_service.server.name}"
  scalable_dimension = "ecs:service:DesiredCount"
  service_namespace  = "ecs"
}

resource "aws_appautoscaling_policy" "cpu" {
  name               = "posly-${var.env}-cpu-scaling"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.server.resource_id
  scalable_dimension = aws_appautoscaling_target.server.scalable_dimension
  service_namespace  = aws_appautoscaling_target.server.service_namespace

  target_tracking_scaling_policy_configuration {
    target_value       = 70
    scale_in_cooldown  = 300
    scale_out_cooldown = 60

    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
  }
}
