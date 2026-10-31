# Subnet Group for RDS PostgreSQL
resource "aws_db_subnet_group" "rds" {
  name        = "paygate-rds-subnet-group"
  description = "Database subnet group spanning Multi-AZ private data subnets"
  subnet_ids  = aws_subnet.private_data[*].id

  tags = {
    Name = "paygate-rds-subnet-group"
  }
}

# Parameter Group for PostgreSQL 16
resource "aws_db_parameter_group" "rds" {
  name   = "paygate-pg16-params"
  family = "postgres16"

  parameter {
    name         = "max_connections"
    value        = "200"
    apply_method = "pending-reboot"
  }

  tags = {
    Name = "paygate-pg16-params"
  }
}

# Amazon RDS PostgreSQL Instance (Graviton db.t4g.micro)
resource "aws_db_instance" "postgres" {
  identifier        = "paygate-rds-postgres"
  engine            = "postgres"
  engine_version     = "16.9"
  instance_class    = "db.t4g.small"
  allocated_storage = 20
  storage_type      = "gp3"

  db_name  = var.db_name
  username = var.db_username
  password = var.db_password

  db_subnet_group_name   = aws_db_subnet_group.rds.name
  parameter_group_name   = aws_db_parameter_group.rds.name
  vpc_security_group_ids = [aws_security_group.rds.id]

  publicly_accessible    = false
  skip_final_snapshot    = true
  deletion_protection    = false
  backup_retention_period = 0
  apply_immediately      = true

  tags = {
    Name = "paygate-rds-postgres"
  }
}
