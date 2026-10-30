# ECS Cluster for PayStream Microservices
resource "aws_ecs_cluster" "main" {
  name = "paygate-cluster"

  setting {
    name  = "containerInsights"
    value = "disabled" # Keep disabled to save CloudWatch metrics costs
  }

  tags = {
    Name = "paygate-cluster"
  }
}

# AWS Cloud Map Private DNS Namespace for Inter-Service Discovery
resource "aws_service_discovery_private_dns_namespace" "main" {
  name        = "paygate.local"
  description = "Private DNS namespace for PayStream microservices discovery"
  vpc         = aws_vpc.main.id

  tags = {
    Name = "paygate-dns-namespace"
  }
}

# IAM Role for ECS Task Execution (Pull from ECR & CloudWatch Logs)
resource "aws_iam_role" "ecs_execution" {
  name = "paygate-ecs-execution-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "ecs-tasks.amazonaws.com"
        }
      }
    ]
  })

  tags = {
    Name = "paygate-ecs-execution-role"
  }
}

resource "aws_iam_role_policy_attachment" "ecs_execution" {
  role       = aws_iam_role.ecs_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# IAM Role for ECS Task Runtime
resource "aws_iam_role" "ecs_task" {
  name = "paygate-ecs-task-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "ecs-tasks.amazonaws.com"
        }
      }
    ]
  })

  tags = {
    Name = "paygate-ecs-task-role"
  }
}

# CloudWatch Log Group for All Microservices
resource "aws_cloudwatch_log_group" "ecs" {
  name              = "/ecs/paygate"
  retention_in_days = 1

  tags = {
    Name = "paygate-ecs-logs"
  }
}

# Helper local for common container definition parameters
locals {
  ecr_registry = "${var.ecr_account_id}.dkr.ecr.${var.ecr_region}.amazonaws.com"
}

# ==============================================================================
# Service Discovery Services (Cloud Map)
# ==============================================================================

resource "aws_service_discovery_service" "services" {
  for_each = toset([
    "paygate-gateway",
    "paygate-auth-service",
    "paygate-payment-service",
    "paygate-processor-service",
    "paygate-refund-service",
    "paygate-webhook-service",
    "paygate-ledger-service",
    "paygate-audit-service"
  ])

  name = each.key

  dns_config {
    namespace_id = aws_service_discovery_private_dns_namespace.main.id

    dns_records {
      ttl  = 10
      type = "A"
    }

    routing_policy = "MULTIVALUE"
  }

  health_check_custom_config {
    failure_threshold = 1
  }

  tags = {
    Name = each.key
  }
}

# ==============================================================================
# Task Definitions & ECS Fargate Services
# ==============================================================================

# 1. Gateway
resource "aws_ecs_task_definition" "gateway" {
  family                   = "paygate-gateway"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = "512"
  memory                   = "1024"
  execution_role_arn       = aws_iam_role.ecs_execution.arn
  task_role_arn            = aws_iam_role.ecs_task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }

  container_definitions = jsonencode([
    {
      name      = "gateway"
      image     = "${local.ecr_registry}/paygate-gateway:latest"
      essential = true
      portMappings = [
        {
          containerPort = 8080
          hostPort      = 8080
          protocol      = "tcp"
        }
      ]
      environment = [
        { name = "PORT", value = "8080" },
        { name = "PAYMENT_SERVICE_URL", value = "http://paygate-payment-service.paygate.local:8081" },
        { name = "AUTH_SERVICE_URL", value = "http://paygate-auth-service.paygate.local:8082" },
        { name = "REFUND_SERVICE_URL", value = "http://paygate-refund-service.paygate.local:8084" },
        { name = "WEBHOOK_SERVICE_URL", value = "http://paygate-webhook-service.paygate.local:8085" },
        { name = "LEDGER_SERVICE_URL", value = "http://paygate-ledger-service.paygate.local:8086" },
        { name = "AUDIT_SERVICE_URL", value = "http://paygate-audit-service.paygate.local:8087" },
        { name = "JWT_SECRET", value = "tro2+Eo1zqjMfBiijExxweDO/nCGKcBXNeZglIzz97/CsC2cYGN7/QkAldZIyY5o" },
        { name = "REDIS_HOST", value = aws_elasticache_cluster.redis.cache_nodes[0].address },
        { name = "REDIS_PORT", value = "6379" },
        { name = "SPRING_DATA_REDIS_HOST", value = aws_elasticache_cluster.redis.cache_nodes[0].address },
        { name = "SPRING_DATA_REDIS_PORT", value = "6379" },
        { name = "RATE_LIMIT_PER_SECOND", value = "1000" },
        { name = "RATE_LIMIT_BURST", value = "2000" },
        { name = "JAVA_TOOL_OPTIONS", value = "-Xms256m -Xmx768m" }
      ]
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.ecs.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "gateway"
        }
      }
    }
  ])

  tags = { Name = "paygate-gateway" }
}

resource "aws_ecs_service" "gateway" {
  name                              = "paygate-gateway"
  cluster                           = aws_ecs_cluster.main.id
  task_definition                  = aws_ecs_task_definition.gateway.arn
  desired_count                     = 1
  launch_type                       = "FARGATE"
  health_check_grace_period_seconds = 60
  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 200

  network_configuration {
    subnets          = aws_subnet.private_app[*].id
    security_groups  = [aws_security_group.ecs_tasks.id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.gateway.arn
    container_name   = "gateway"
    container_port   = 8080
  }

  service_registries {
    registry_arn = aws_service_discovery_service.services["paygate-gateway"].arn
  }

  depends_on = [aws_lb_listener.http]
  tags       = { Name = "paygate-gateway" }
}

# 2. Auth Service
resource "aws_ecs_task_definition" "auth_service" {
  family                   = "paygate-auth-service"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = "256"
  memory                   = "512"
  execution_role_arn       = aws_iam_role.ecs_execution.arn
  task_role_arn            = aws_iam_role.ecs_task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }

  container_definitions = jsonencode([
    {
      name      = "auth-service"
      image     = "${local.ecr_registry}/paygate-auth-service:latest"
      essential = true
      portMappings = [
        {
          containerPort = 8082
          hostPort      = 8082
          protocol      = "tcp"
        }
      ]
      environment = [
        { name = "DB_URL", value = "jdbc:postgresql://${aws_db_instance.postgres.address}:5432/auth?reWriteBatchedInserts=true" },
        { name = "DB_USER", value = var.db_username },
        { name = "DB_PASSWORD", value = var.db_password },
        { name = "SPRING_DATASOURCE_URL", value = "jdbc:postgresql://${aws_db_instance.postgres.address}:5432/auth?reWriteBatchedInserts=true" },
        { name = "SPRING_DATASOURCE_USERNAME", value = var.db_username },
        { name = "SPRING_DATASOURCE_PASSWORD", value = var.db_password },
        { name = "JWT_SECRET", value = "tro2+Eo1zqjMfBiijExxweDO/nCGKcBXNeZglIzz97/CsC2cYGN7/QkAldZIyY5o" },
        { name = "JAVA_TOOL_OPTIONS", value = "-Xms256m -Xmx400m" }
      ]
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.ecs.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "auth"
        }
      }
    }
  ])

  tags = { Name = "paygate-auth-service" }
}

resource "aws_ecs_service" "auth_service" {
  name            = "paygate-auth-service"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.auth_service.arn
  desired_count   = 1
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = aws_subnet.private_app[*].id
    security_groups  = [aws_security_group.ecs_tasks.id]
    assign_public_ip = false
  }

  service_registries {
    registry_arn = aws_service_discovery_service.services["paygate-auth-service"].arn
  }

  tags = { Name = "paygate-auth-service" }
}

# 3. Payment Service
resource "aws_ecs_task_definition" "payment_service" {
  family                   = "paygate-payment-service"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = "1024"
  memory                   = "2048"
  execution_role_arn       = aws_iam_role.ecs_execution.arn
  task_role_arn            = aws_iam_role.ecs_task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }

  container_definitions = jsonencode([
    {
      name      = "payment-service"
      image     = "${local.ecr_registry}/paygate-payment-service:latest"
      essential = true
      portMappings = [
        {
          containerPort = 8081
          hostPort      = 8081
          protocol      = "tcp"
        }
      ]
      environment = [
        { name = "DB_URL", value = "jdbc:postgresql://${aws_db_instance.postgres.address}:5432/payments?reWriteBatchedInserts=true" },
        { name = "DB_USER", value = var.db_username },
        { name = "DB_PASSWORD", value = var.db_password },
        { name = "SPRING_DATASOURCE_URL", value = "jdbc:postgresql://${aws_db_instance.postgres.address}:5432/payments?reWriteBatchedInserts=true" },
        { name = "SPRING_DATASOURCE_USERNAME", value = var.db_username },
        { name = "SPRING_DATASOURCE_PASSWORD", value = var.db_password },
        { name = "REDIS_HOST", value = aws_elasticache_cluster.redis.cache_nodes[0].address },
        { name = "REDIS_PORT", value = "6379" },
        { name = "SPRING_DATA_REDIS_HOST", value = aws_elasticache_cluster.redis.cache_nodes[0].address },
        { name = "SPRING_DATA_REDIS_PORT", value = "6379" },
        { name = "KAFKA_BOOTSTRAP_SERVERS", value = "${aws_instance.kafka.private_ip}:9092" },
        { name = "SPRING_KAFKA_BOOTSTRAP_SERVERS", value = "${aws_instance.kafka.private_ip}:9092" },
        { name = "PROCESSOR_SERVICE_URL", value = "http://paygate-processor-service.paygate.local:8083" },
        { name = "JAVA_TOOL_OPTIONS", value = "-Xms512m -Xmx1536m" }
      ]
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.ecs.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "payment"
        }
      }
    }
  ])

  tags = { Name = "paygate-payment-service" }
}

resource "aws_ecs_service" "payment_service" {
  name            = "paygate-payment-service"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.payment_service.arn
  desired_count   = 1
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = aws_subnet.private_app[*].id
    security_groups  = [aws_security_group.ecs_tasks.id]
    assign_public_ip = false
  }

  service_registries {
    registry_arn = aws_service_discovery_service.services["paygate-payment-service"].arn
  }

  tags = { Name = "paygate-payment-service" }
}

# 4. Processor Service
resource "aws_ecs_task_definition" "processor_service" {
  family                   = "paygate-processor-service"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = "256"
  memory                   = "512"
  execution_role_arn       = aws_iam_role.ecs_execution.arn
  task_role_arn            = aws_iam_role.ecs_task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }

  container_definitions = jsonencode([
    {
      name      = "processor-service"
      image     = "${local.ecr_registry}/paygate-processor-service:latest"
      essential = true
      portMappings = [
        {
          containerPort = 8083
          hostPort      = 8083
          protocol      = "tcp"
        }
      ]
      environment = [
        { name = "KAFKA_BOOTSTRAP_SERVERS", value = "${aws_instance.kafka.private_ip}:9092" },
        { name = "SPRING_KAFKA_BOOTSTRAP_SERVERS", value = "${aws_instance.kafka.private_ip}:9092" },
        { name = "PROCESSOR_FAILURE_RATE", value = "0.0" },
        { name = "JAVA_TOOL_OPTIONS", value = "-Xms256m -Xmx400m" }
      ]
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.ecs.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "processor"
        }
      }
    }
  ])

  tags = { Name = "paygate-processor-service" }
}

resource "aws_ecs_service" "processor_service" {
  name            = "paygate-processor-service"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.processor_service.arn
  desired_count   = 1
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = aws_subnet.private_app[*].id
    security_groups  = [aws_security_group.ecs_tasks.id]
    assign_public_ip = false
  }

  service_registries {
    registry_arn = aws_service_discovery_service.services["paygate-processor-service"].arn
  }

  tags = { Name = "paygate-processor-service" }
}

# 5. Refund Service
resource "aws_ecs_task_definition" "refund_service" {
  family                   = "paygate-refund-service"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = "256"
  memory                   = "512"
  execution_role_arn       = aws_iam_role.ecs_execution.arn
  task_role_arn            = aws_iam_role.ecs_task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }

  container_definitions = jsonencode([
    {
      name      = "refund-service"
      image     = "${local.ecr_registry}/paygate-refund-service:latest"
      essential = true
      portMappings = [
        {
          containerPort = 8084
          hostPort      = 8084
          protocol      = "tcp"
        }
      ]
      environment = [
        { name = "DB_URL", value = "jdbc:postgresql://${aws_db_instance.postgres.address}:5432/refunds?reWriteBatchedInserts=true" },
        { name = "DB_USER", value = var.db_username },
        { name = "DB_PASSWORD", value = var.db_password },
        { name = "SPRING_DATASOURCE_URL", value = "jdbc:postgresql://${aws_db_instance.postgres.address}:5432/refunds?reWriteBatchedInserts=true" },
        { name = "SPRING_DATASOURCE_USERNAME", value = var.db_username },
        { name = "SPRING_DATASOURCE_PASSWORD", value = var.db_password },
        { name = "REDIS_HOST", value = aws_elasticache_cluster.redis.cache_nodes[0].address },
        { name = "REDIS_PORT", value = "6379" },
        { name = "SPRING_DATA_REDIS_HOST", value = aws_elasticache_cluster.redis.cache_nodes[0].address },
        { name = "SPRING_DATA_REDIS_PORT", value = "6379" },
        { name = "KAFKA_BOOTSTRAP_SERVERS", value = "${aws_instance.kafka.private_ip}:9092" },
        { name = "SPRING_KAFKA_BOOTSTRAP_SERVERS", value = "${aws_instance.kafka.private_ip}:9092" },
        { name = "PROCESSOR_SERVICE_URL", value = "http://paygate-processor-service.paygate.local:8083" },
        { name = "PAYMENT_SERVICE_URL", value = "http://paygate-payment-service.paygate.local:8081" },
        { name = "JAVA_TOOL_OPTIONS", value = "-Xms256m -Xmx400m" }
      ]
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.ecs.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "refund"
        }
      }
    }
  ])

  tags = { Name = "paygate-refund-service" }
}

resource "aws_ecs_service" "refund_service" {
  name            = "paygate-refund-service"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.refund_service.arn
  desired_count   = 1
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = aws_subnet.private_app[*].id
    security_groups  = [aws_security_group.ecs_tasks.id]
    assign_public_ip = false
  }

  service_registries {
    registry_arn = aws_service_discovery_service.services["paygate-refund-service"].arn
  }

  tags = { Name = "paygate-refund-service" }
}

# 6. Webhook Service
resource "aws_ecs_task_definition" "webhook_service" {
  family                   = "paygate-webhook-service"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = "256"
  memory                   = "512"
  execution_role_arn       = aws_iam_role.ecs_execution.arn
  task_role_arn            = aws_iam_role.ecs_task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }

  container_definitions = jsonencode([
    {
      name      = "webhook-service"
      image     = "${local.ecr_registry}/paygate-webhook-service:latest"
      essential = true
      portMappings = [
        {
          containerPort = 8085
          hostPort      = 8085
          protocol      = "tcp"
        }
      ]
      environment = [
        { name = "DB_URL", value = "jdbc:postgresql://${aws_db_instance.postgres.address}:5432/webhooks?reWriteBatchedInserts=true" },
        { name = "DB_USER", value = var.db_username },
        { name = "DB_PASSWORD", value = var.db_password },
        { name = "SPRING_DATASOURCE_URL", value = "jdbc:postgresql://${aws_db_instance.postgres.address}:5432/webhooks?reWriteBatchedInserts=true" },
        { name = "SPRING_DATASOURCE_USERNAME", value = var.db_username },
        { name = "SPRING_DATASOURCE_PASSWORD", value = var.db_password },
        { name = "REDIS_HOST", value = aws_elasticache_cluster.redis.cache_nodes[0].address },
        { name = "REDIS_PORT", value = "6379" },
        { name = "SPRING_DATA_REDIS_HOST", value = aws_elasticache_cluster.redis.cache_nodes[0].address },
        { name = "SPRING_DATA_REDIS_PORT", value = "6379" },
        { name = "KAFKA_BOOTSTRAP_SERVERS", value = "${aws_instance.kafka.private_ip}:9092" },
        { name = "SPRING_KAFKA_BOOTSTRAP_SERVERS", value = "${aws_instance.kafka.private_ip}:9092" },
        { name = "JAVA_TOOL_OPTIONS", value = "-Xms256m -Xmx400m" }
      ]
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.ecs.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "webhook"
        }
      }
    }
  ])

  tags = { Name = "paygate-webhook-service" }
}

resource "aws_ecs_service" "webhook_service" {
  name            = "paygate-webhook-service"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.webhook_service.arn
  desired_count   = 1
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = aws_subnet.private_app[*].id
    security_groups  = [aws_security_group.ecs_tasks.id]
    assign_public_ip = false
  }

  service_registries {
    registry_arn = aws_service_discovery_service.services["paygate-webhook-service"].arn
  }

  tags = { Name = "paygate-webhook-service" }
}

# 7. Ledger Service
resource "aws_ecs_task_definition" "ledger_service" {
  family                   = "paygate-ledger-service"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = "512"
  memory                   = "1024"
  execution_role_arn       = aws_iam_role.ecs_execution.arn
  task_role_arn            = aws_iam_role.ecs_task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }

  container_definitions = jsonencode([
    {
      name      = "ledger-service"
      image     = "${local.ecr_registry}/paygate-ledger-service:latest"
      essential = true
      portMappings = [
        {
          containerPort = 8086
          hostPort      = 8086
          protocol      = "tcp"
        }
      ]
      environment = [
        { name = "DB_URL", value = "jdbc:postgresql://${aws_db_instance.postgres.address}:5432/ledger?reWriteBatchedInserts=true" },
        { name = "DB_USER", value = var.db_username },
        { name = "DB_PASSWORD", value = var.db_password },
        { name = "SPRING_DATASOURCE_URL", value = "jdbc:postgresql://${aws_db_instance.postgres.address}:5432/ledger?reWriteBatchedInserts=true" },
        { name = "SPRING_DATASOURCE_USERNAME", value = var.db_username },
        { name = "SPRING_DATASOURCE_PASSWORD", value = var.db_password },
        { name = "REDIS_HOST", value = aws_elasticache_cluster.redis.cache_nodes[0].address },
        { name = "REDIS_PORT", value = "6379" },
        { name = "SPRING_DATA_REDIS_HOST", value = aws_elasticache_cluster.redis.cache_nodes[0].address },
        { name = "SPRING_DATA_REDIS_PORT", value = "6379" },
        { name = "KAFKA_BOOTSTRAP_SERVERS", value = "${aws_instance.kafka.private_ip}:9092" },
        { name = "SPRING_KAFKA_BOOTSTRAP_SERVERS", value = "${aws_instance.kafka.private_ip}:9092" },
        { name = "JAVA_TOOL_OPTIONS", value = "-Xms256m -Xmx768m" }
      ]
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.ecs.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "ledger"
        }
      }
    }
  ])

  tags = { Name = "paygate-ledger-service" }
}

resource "aws_ecs_service" "ledger_service" {
  name            = "paygate-ledger-service"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.ledger_service.arn
  desired_count   = 1
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = aws_subnet.private_app[*].id
    security_groups  = [aws_security_group.ecs_tasks.id]
    assign_public_ip = false
  }

  service_registries {
    registry_arn = aws_service_discovery_service.services["paygate-ledger-service"].arn
  }

  tags = { Name = "paygate-ledger-service" }
}

# 8. Audit Service
resource "aws_ecs_task_definition" "audit_service" {
  family                   = "paygate-audit-service"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = "512"
  memory                   = "1024"
  execution_role_arn       = aws_iam_role.ecs_execution.arn
  task_role_arn            = aws_iam_role.ecs_task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }

  container_definitions = jsonencode([
    {
      name      = "audit-service"
      image     = "${local.ecr_registry}/paygate-audit-service:latest"
      essential = true
      portMappings = [
        {
          containerPort = 8087
          hostPort      = 8087
          protocol      = "tcp"
        }
      ]
      environment = [
        { name = "DB_URL", value = "jdbc:postgresql://${aws_db_instance.postgres.address}:5432/audit?reWriteBatchedInserts=true" },
        { name = "DB_USER", value = var.db_username },
        { name = "DB_PASSWORD", value = var.db_password },
        { name = "SPRING_DATASOURCE_URL", value = "jdbc:postgresql://${aws_db_instance.postgres.address}:5432/audit?reWriteBatchedInserts=true" },
        { name = "SPRING_DATASOURCE_USERNAME", value = var.db_username },
        { name = "SPRING_DATASOURCE_PASSWORD", value = var.db_password },
        { name = "REDIS_HOST", value = aws_elasticache_cluster.redis.cache_nodes[0].address },
        { name = "REDIS_PORT", value = "6379" },
        { name = "SPRING_DATA_REDIS_HOST", value = aws_elasticache_cluster.redis.cache_nodes[0].address },
        { name = "SPRING_DATA_REDIS_PORT", value = "6379" },
        { name = "KAFKA_BOOTSTRAP_SERVERS", value = "${aws_instance.kafka.private_ip}:9092" },
        { name = "SPRING_KAFKA_BOOTSTRAP_SERVERS", value = "${aws_instance.kafka.private_ip}:9092" },
        { name = "JAVA_TOOL_OPTIONS", value = "-Xms256m -Xmx768m" }
      ]
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.ecs.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "audit"
        }
      }
    }
  ])

  tags = { Name = "paygate-audit-service" }
}

resource "aws_ecs_service" "audit_service" {
  name            = "paygate-audit-service"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.audit_service.arn
  desired_count   = 1
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = aws_subnet.private_app[*].id
    security_groups  = [aws_security_group.ecs_tasks.id]
    assign_public_ip = false
  }

  service_registries {
    registry_arn = aws_service_discovery_service.services["paygate-audit-service"].arn
  }

  tags = { Name = "paygate-audit-service" }
}
