# Subnet Group for ElastiCache Redis
resource "aws_elasticache_subnet_group" "redis" {
  name       = "paygate-redis-subnet-group"
  subnet_ids = aws_subnet.private_data[*].id

  tags = {
    Name = "paygate-redis-subnet-group"
  }
}

# Parameter Group for Redis 7
resource "aws_elasticache_parameter_group" "redis" {
  name   = "paygate-redis7-params"
  family = "redis7"

  tags = {
    Name = "paygate-redis7-params"
  }
}

# Amazon ElastiCache Redis Cluster (Graviton cache.t4g.micro)
resource "aws_elasticache_cluster" "redis" {
  cluster_id           = "paygate-redis"
  engine               = "redis"
  engine_version       = "7.1"
  node_type            = "cache.t4g.micro"
  num_cache_nodes      = 1
  parameter_group_name = aws_elasticache_parameter_group.redis.name
  port                 = 6379
  subnet_group_name    = aws_elasticache_subnet_group.redis.name
  security_group_ids   = [aws_security_group.redis.id]
  apply_immediately    = true

  tags = {
    Name = "paygate-redis"
  }
}
