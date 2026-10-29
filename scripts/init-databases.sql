-- Initializes extra databases for microservices on fresh container creation
SELECT 'CREATE DATABASE refunds' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'refunds')\gexec
SELECT 'CREATE DATABASE webhooks' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'webhooks')\gexec
