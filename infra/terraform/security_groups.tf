# Security Group for Public Application Load Balancer
resource "aws_security_group" "alb" {
  name        = "paygate-alb-sg"
  description = "Controls public inbound traffic to the PayStream ALB"
  vpc_id      = aws_vpc.main.id

  ingress {
    description = "Allow inbound HTTP from anywhere"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    description = "Allow all outbound traffic to VPC"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "paygate-alb-sg"
  }
}

# Security Group for ECS Fargate Microservice Tasks
resource "aws_security_group" "ecs_tasks" {
  name        = "paygate-ecs-tasks-sg"
  description = "Controls traffic to and between ECS Fargate microservices"
  vpc_id      = aws_vpc.main.id

  # Ingress from ALB to Gateway
  ingress {
    description     = "Allow inbound HTTP from ALB to Gateway"
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }

  # Ingress for inter-service communication between ECS tasks
  ingress {
    description = "Allow inter-service communication between ECS tasks"
    from_port   = 0
    to_port     = 65535
    protocol    = "tcp"
    self        = true
  }

  # Allow VPC internal communication
  ingress {
    description = "Allow communication from VPC CIDR"
    from_port   = 0
    to_port     = 65535
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
  }

  egress {
    description = "Allow all outbound traffic (NAT / ECR / Data Stores)"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "paygate-ecs-tasks-sg"
  }
}

# Security Group for Amazon RDS PostgreSQL
resource "aws_security_group" "rds" {
  name        = "paygate-rds-sg"
  description = "Controls database access to Amazon RDS PostgreSQL"
  vpc_id      = aws_vpc.main.id

  ingress {
    description     = "Allow PostgreSQL from ECS tasks"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.ecs_tasks.id]
  }

  ingress {
    description = "Allow PostgreSQL from within VPC"
    from_port   = 5432
    to_port     = 5432
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
  }

  egress {
    description = "Allow all outbound traffic"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "paygate-rds-sg"
  }
}

# Security Group for Amazon ElastiCache Redis
resource "aws_security_group" "redis" {
  name        = "paygate-redis-sg"
  description = "Controls cache access to Amazon ElastiCache Redis"
  vpc_id      = aws_vpc.main.id

  ingress {
    description     = "Allow Redis from ECS tasks"
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = [aws_security_group.ecs_tasks.id]
  }

  ingress {
    description = "Allow Redis from within VPC"
    from_port   = 6379
    to_port     = 6379
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
  }

  egress {
    description = "Allow all outbound traffic"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "paygate-redis-sg"
  }
}

# Security Group for Apache Kafka Event Broker
resource "aws_security_group" "kafka" {
  name        = "paygate-kafka-sg"
  description = "Controls event streaming access to Apache Kafka"
  vpc_id      = aws_vpc.main.id

  ingress {
    description     = "Allow Kafka plaintext from ECS tasks"
    from_port       = 9092
    to_port         = 9092
    protocol        = "tcp"
    security_groups = [aws_security_group.ecs_tasks.id]
  }

  ingress {
    description = "Allow Kafka plaintext from within VPC"
    from_port   = 9092
    to_port     = 9092
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
  }

  egress {
    description = "Allow all outbound traffic (NAT / Package downloads)"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "paygate-kafka-sg"
  }
}

