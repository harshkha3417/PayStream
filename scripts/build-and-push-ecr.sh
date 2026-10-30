#!/usr/bin/env bash
set -euo pipefail

# Build and Push all PayStream Microservice Images to Amazon ECR (ARM64 / Graviton)
AWS_ACCOUNT_ID="${AWS_ACCOUNT_ID:-537695480640}"
AWS_REGION="${AWS_REGION:-us-east-1}"
ECR_REGISTRY="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
TAG="${1:-latest}"

echo "=========================================================="
echo "  Building & Pushing PayStream Images to Amazon ECR       "
echo "  Registry: ${ECR_REGISTRY}                               "
echo "  Architecture: linux/arm64 (AWS Graviton)                "
echo "  Tag: ${TAG}                                             "
echo "=========================================================="

SERVICES=(
  "gateway"
  "auth-service"
  "payment-service"
  "processor-service"
  "refund-service"
  "webhook-service"
  "ledger-service"
  "audit-service"
)

# Authenticate Docker with ECR
echo "--> Authenticating with Amazon ECR..."
aws ecr get-login-password --region "${AWS_REGION}" | docker login --username AWS --password-stdin "${ECR_REGISTRY}"

for svc in "${SERVICES[@]}"; do
  echo ""
  echo "----------------------------------------------------------"
  echo "  Building ${svc}..."
  echo "----------------------------------------------------------"
  
  REPO_URI="${ECR_REGISTRY}/paygate-${svc}"
  
  # Build natively for linux/arm64 (AWS Graviton)
  docker build --platform linux/arm64 -t "${REPO_URI}:${TAG}" "${svc}"
  
  echo "--> Pushing ${REPO_URI}:${TAG} to ECR..."
  docker push "${REPO_URI}:${TAG}"
  
  echo "✓ Successfully pushed paygate-${svc}:${TAG}"
done

echo ""
echo "=========================================================="
echo "  All 8 PayStream Microservices Successfully Pushed!      "
echo "=========================================================="
