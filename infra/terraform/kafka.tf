# Query Latest Amazon Linux 2023 ARM64 AMI
data "aws_ami" "al2023_arm64" {
  most_recent = true
  owners      = ["amazon"]

  filter {
    name   = "name"
    values = ["al2023-ami-2023.*-arm64"]
  }

  filter {
    name   = "state"
    values = ["available"]
  }
}

# IAM Role for Kafka EC2 Instance (Enables AWS Systems Manager SSM)
resource "aws_iam_role" "kafka" {
  name = "paygate-kafka-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "ec2.amazonaws.com"
        }
      }
    ]
  })

  tags = {
    Name = "paygate-kafka-role"
  }
}

# Attach SSM Managed Instance Core Policy
resource "aws_iam_role_policy_attachment" "kafka_ssm" {
  role       = aws_iam_role.kafka.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

# Instance Profile for Kafka EC2
resource "aws_iam_instance_profile" "kafka" {
  name = "paygate-kafka-instance-profile"
  role = aws_iam_role.kafka.name
}

# Apache Kafka EC2 Instance (Graviton t4g.small in KRaft Mode)
resource "aws_instance" "kafka" {
  ami                  = data.aws_ami.al2023_arm64.id
  instance_type        = "t4g.small"
  subnet_id            = aws_subnet.private_app[0].id
  vpc_security_group_ids = [aws_security_group.kafka.id]
  iam_instance_profile = aws_iam_instance_profile.kafka.name

  root_block_device {
    volume_size           = 20
    volume_type           = "gp3"
    delete_on_termination = true
    encrypted             = true
    tags = {
      Name = "paygate-kafka-root"
    }
  }

  user_data = <<-EOF
    #!/bin/bash
    set -e
    exec > >(tee /var/log/paygate-bootstrap.log|logger -t user-data -s 2>/dev/console) 2>&1
    echo "=========================================================="
    echo "Starting PayStream Kafka & RDS Multi-DB Bootstrap"
    echo "=========================================================="

    # 1. Update OS & Install Java 21 & PostgreSQL client
    dnf update -y
    dnf install -y java-21-amazon-corretto-headless postgresql16 || dnf install -y java-21-amazon-corretto-headless postgresql

    # 2. Create kafka system user & directories
    useradd -r -m -U -d /opt/kafka -s /bin/bash kafka || true
    mkdir -p /var/lib/kafka/data
    chown -R kafka:kafka /var/lib/kafka

    # 3. Download & extract Apache Kafka 3.8.0
    cd /tmp
    KAFKA_VERSION="3.8.0"
    SCALA_VERSION="2.13"
    curl -sSL -O "https://archive.apache.org/dist/kafka/$${KAFKA_VERSION}/kafka_$${SCALA_VERSION}-$${KAFKA_VERSION}.tgz"
    tar -xzf "kafka_$${SCALA_VERSION}-$${KAFKA_VERSION}.tgz" -C /opt/kafka --strip-components=1
    chown -R kafka:kafka /opt/kafka

    # 4. Configure Kafka KRaft properties
    IMDS_TOKEN=$(curl -s -X PUT "http://169.254.169.254/latest/api/token" -H "X-aws-ec2-metadata-token-ttl-seconds: 21600" || true)
    PRIVATE_IP=$(curl -s -H "X-aws-ec2-metadata-token: $IMDS_TOKEN" http://169.254.169.254/latest/meta-data/local-ipv4 || hostname -I | awk '{print $1}')
    cat <<'KRAFT_CONF' > /opt/kafka/config/kraft/server.properties
    process.roles=broker,controller
    node.id=1
    controller.quorum.voters=1@localhost:9093
    listeners=PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
    inter.broker.listener.name=PLAINTEXT
    advertised.listeners=PLAINTEXT://PRIVATE_IP_PLACEHOLDER:9092
    controller.listener.names=CONTROLLER
    listener.security.protocol.map=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,SSL:SSL,SASL_PLAINTEXT:SASL_PLAINTEXT,SASL_SSL:SASL_SSL
    num.network.threads=3
    num.io.threads=8
    socket.send.buffer.bytes=102400
    socket.receive.buffer.bytes=102400
    socket.request.max.bytes=104857600
    log.dirs=/var/lib/kafka/data
    num.partitions=3
    num.recovery.threads.per.data.dir=1
    offsets.topic.replication.factor=1
    transaction.state.log.replication.factor=1
    transaction.state.log.min.isr=1
    log.retention.hours=168
    log.segment.bytes=1073741824
    log.retention.check.interval.ms=300000
    auto.create.topics.enable=true
    KRAFT_CONF

    sed -i "s/PRIVATE_IP_PLACEHOLDER/$${PRIVATE_IP}/g" /opt/kafka/config/kraft/server.properties
    chown kafka:kafka /opt/kafka/config/kraft/server.properties

    # 5. Format KRaft storage
    KAFKA_CLUSTER_ID=$(/opt/kafka/bin/kafka-storage.sh random-uuid)
    sudo -u kafka /opt/kafka/bin/kafka-storage.sh format -t "$${KAFKA_CLUSTER_ID}" -c /opt/kafka/config/kraft/server.properties

    # 6. Configure & start systemd service
    cat <<'SERVICE' > /etc/systemd/system/kafka.service
    [Unit]
    Description=Apache Kafka Distributed Message Broker (KRaft Mode)
    Documentation=https://kafka.apache.org/documentation/
    After=network.target

    [Service]
    Type=simple
    User=kafka
    Group=kafka
    Environment="KAFKA_HEAP_OPTS=-Xmx1024M -Xms512M"
    Environment="JAVA_HOME=/usr/lib/jvm/jre-21"
    ExecStart=/opt/kafka/bin/kafka-server-start.sh /opt/kafka/config/kraft/server.properties
    ExecStop=/opt/kafka/bin/kafka-server-stop.sh
    Restart=on-failure
    RestartSec=5

    [Install]
    WantedBy=multi-user.target
    SERVICE

    systemctl daemon-reload
    systemctl enable kafka
    systemctl start kafka

    # 7. Wait for Kafka broker readiness and create topics
    echo "Waiting for Kafka broker on port 9092..."
    until /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list >/dev/null 2>&1; do
      sleep 2
    done
    echo "Kafka is online! Creating production event topics..."
    /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic payments --partitions 3 --replication-factor 1
    /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic refunds --partitions 3 --replication-factor 1
    /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic webhooks --partitions 3 --replication-factor 1
    /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic dead-letter --partitions 3 --replication-factor 1

    # 8. Bootstrap PostgreSQL microservices databases on RDS
    RDS_HOST="${aws_db_instance.postgres.address}"
    echo "Initializing multi-database schemas on RDS host: $${RDS_HOST}"
    until PGPASSWORD="${var.db_password}" psql -h "$${RDS_HOST}" -U "${var.db_username}" -d postgres -c "SELECT 1;" >/dev/null 2>&1; do
      echo "Waiting for RDS connectivity..."
      sleep 3
    done

    for db_name in payments auth refunds webhooks ledger audit; do
      EXISTS=$(PGPASSWORD="${var.db_password}" psql -h "$${RDS_HOST}" -U "${var.db_username}" -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='$${db_name}';")
      if [ "$${EXISTS}" != "1" ]; then
        echo "Creating database: $${db_name}..."
        PGPASSWORD="${var.db_password}" psql -h "$${RDS_HOST}" -U "${var.db_username}" -d postgres -c "CREATE DATABASE $${db_name};"
      else
        echo "Database $${db_name} already exists."
      fi
    done

    echo "=========================================================="
    echo "PayStream Kafka & RDS Multi-DB Bootstrap COMPLETED!"
    echo "=========================================================="
  EOF

  tags = {
    Name = "paygate-kafka"
  }

  depends_on = [
    aws_nat_gateway.main,
    aws_route_table_association.private_app,
    aws_db_instance.postgres
  ]
}
