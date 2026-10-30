output "vpc_id" {
  description = "The ID of the PayStream VPC"
  value       = aws_vpc.main.id
}

output "public_subnet_ids" {
  description = "IDs of the public subnets"
  value       = aws_subnet.public[*].id
}

output "private_app_subnet_ids" {
  description = "IDs of the private application subnets"
  value       = aws_subnet.private_app[*].id
}

output "private_data_subnet_ids" {
  description = "IDs of the private data subnets"
  value       = aws_subnet.private_data[*].id
}

output "rds_endpoint" {
  description = "Connection endpoint for RDS PostgreSQL"
  value       = aws_db_instance.postgres.endpoint
}

output "rds_address" {
  description = "Hostname for RDS PostgreSQL"
  value       = aws_db_instance.postgres.address
}

output "rds_port" {
  description = "Port for RDS PostgreSQL"
  value       = aws_db_instance.postgres.port
}

output "redis_endpoint" {
  description = "Hostname for ElastiCache Redis"
  value       = aws_elasticache_cluster.redis.cache_nodes[0].address
}

output "redis_port" {
  description = "Port for ElastiCache Redis"
  value       = aws_elasticache_cluster.redis.port
}

output "alb_security_group_id" {
  description = "Security Group ID for Application Load Balancer"
  value       = aws_security_group.alb.id
}

output "ecs_security_group_id" {
  description = "Security Group ID for ECS tasks"
  value       = aws_security_group.ecs_tasks.id
}

output "rds_security_group_id" {
  description = "Security Group ID for RDS PostgreSQL"
  value       = aws_security_group.rds.id
}

output "redis_security_group_id" {
  description = "Security Group ID for ElastiCache Redis"
  value       = aws_security_group.redis.id
}

output "kafka_instance_id" {
  description = "EC2 Instance ID for Apache Kafka broker"
  value       = aws_instance.kafka.id
}

output "kafka_private_ip" {
  description = "Private IP address of Apache Kafka broker"
  value       = aws_instance.kafka.private_ip
}

output "kafka_bootstrap_servers" {
  description = "Kafka Bootstrap Servers connection string"
  value       = "${aws_instance.kafka.private_ip}:9092"
}

output "kafka_security_group_id" {
  description = "Security Group ID for Apache Kafka"
  value       = aws_security_group.kafka.id
}


output "ecs_cluster_name" {
  description = "Name of the ECS Fargate Cluster"
  value       = aws_ecs_cluster.main.name
}

output "service_discovery_namespace" {
  description = "Cloud Map Private DNS Namespace"
  value       = aws_service_discovery_private_dns_namespace.main.name
}
