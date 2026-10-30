variable "aws_region" {
  description = "AWS deployment region"
  type        = string
  default     = "ap-south-1"
}

variable "ecr_region" {
  description = "AWS region hosting the ECR repositories"
  type        = string
  default     = "us-east-1"
}

variable "environment" {
  description = "Environment identifier"
  type        = string
  default     = "production"
}

variable "vpc_cidr" {
  description = "CIDR block for the PayStream VPC"
  type        = string
  default     = "10.0.0.0/16"
}

variable "availability_zones" {
  description = "List of Availability Zones for high availability"
  type        = list(string)
  default     = ["ap-south-1a", "ap-south-1b"]
}

variable "public_subnet_cidrs" {
  description = "CIDR blocks for public subnets (ALB & NAT Gateway)"
  type        = list(string)
  default     = ["10.0.1.0/24", "10.0.2.0/24"]
}

variable "private_app_subnet_cidrs" {
  description = "CIDR blocks for private application subnets (ECS Fargate microservices)"
  type        = list(string)
  default     = ["10.0.10.0/24", "10.0.11.0/24"]
}

variable "private_data_subnet_cidrs" {
  description = "CIDR blocks for private data subnets (RDS PostgreSQL & ElastiCache Redis)"
  type        = list(string)
  default     = ["10.0.20.0/24", "10.0.21.0/24"]
}

variable "db_username" {
  description = "Master username for RDS PostgreSQL"
  type        = string
  default     = "paygate_admin"
}

variable "db_password" {
  description = "Master password for RDS PostgreSQL"
  type        = string
  default     = "PayGateSecret2026!#"
  sensitive   = true
}

variable "db_name" {
  description = "Root database name for RDS PostgreSQL"
  type        = string
  default     = "postgres"
}

variable "ecr_account_id" {
  description = "AWS Account ID hosting the ECR repositories"
  type        = string
  default     = "537695480640"
}
