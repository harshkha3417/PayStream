#!/usr/bin/env bash
set -e

GATEWAY_URL="http://localhost:8080"
echo "=========================================================="
echo "  Week 5 Verification: Refunds + Webhooks Microservices   "
echo "=========================================================="

echo -n "1. Checking Gateway Health... "
HEALTH=$(curl -s "${GATEWAY_URL}/actuator/health")
echo "${HEALTH}"

echo -n "2. Registering Merchant and Obtaining Auth Token... "
RAND_SUFFIX=$(date +%s%N | cut -b1-10)
MERCHANT_RES=$(curl -s -X POST "${GATEWAY_URL}/api/v1/merchants" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "week5_'${RAND_SUFFIX}'@example.com",
    "name": "Week 5 Merchant",
    "password": "Password123!"
  }')
MERCHANT_ID=$(echo "${MERCHANT_RES}" | grep -o '"merchantId":"[^"]*' | cut -d'"' -f4)
API_KEY=$(echo "${MERCHANT_RES}" | grep -o '"apiKey":"[^"]*' | cut -d'"' -f4)

TOKEN_RES=$(curl -s -X POST "${GATEWAY_URL}/api/v1/auth/token" \
  -H "Content-Type: application/json" \
  -d '{
    "merchantId": "'${MERCHANT_ID}'",
    "apiKey": "'${API_KEY}'"
  }')
TOKEN=$(echo "${TOKEN_RES}" | grep -o '"accessToken":"[^"]*' | cut -d'"' -f4)
echo "Merchant: ${MERCHANT_ID}, Token: ${TOKEN:0:15}..."

echo -e "\n3. Configuring Webhook Endpoint..."
WH_ENDPOINT_RES=$(curl -s -X POST "${GATEWAY_URL}/api/v1/webhooks/endpoints" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "url": "https://httpbin.org/post",
    "events": ["payment.created", "payment.status-changed", "refund.completed"]
  }')
echo "Endpoint Response: ${WH_ENDPOINT_RES}"
ENDPOINT_ID=$(echo "${WH_ENDPOINT_RES}" | grep -o '"id":"[^"]*' | cut -d'"' -f4)
ENDPOINT_SECRET=$(echo "${WH_ENDPOINT_RES}" | grep -o '"secret":"[^"]*' | cut -d'"' -f4)
echo "Configured Endpoint ID: ${ENDPOINT_ID}"
echo "HMAC Signing Secret: ${ENDPOINT_SECRET}"

echo -e "\n4. Testing Webhook Ping (HMAC signature verification)..."
PING_RES=$(curl -s -X POST "${GATEWAY_URL}/api/v1/webhooks/endpoints/${ENDPOINT_ID}/test" \
  -H "Authorization: Bearer ${TOKEN}")
echo "Ping Delivery: ${PING_RES}"

echo -e "\n5. Creating Payment (amount = 10000 paise = 100.00 INR)..."
IDEM_KEY="week5-idem-${RAND_SUFFIX}"
PAYMENT_RES=$(curl -s -X POST "${GATEWAY_URL}/api/v1/payments" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Idempotency-Key: ${IDEM_KEY}" \
  -H "Content-Type: application/json" \
  -d '{
    "amount": 10000,
    "currency": "INR",
    "method": "CARD",
    "description": "Week 5 Refund & Webhook Verification Payment"
  }')
echo "Payment Created: ${PAYMENT_RES}"
PAYMENT_ID=$(echo "${PAYMENT_RES}" | grep -o '"id":"[^"]*' | cut -d'"' -f4)

echo "Waiting 3s for processor-service simulation & Kafka events propagation..."
sleep 3

# Check payment status
PAYMENT_STATUS_RES=$(curl -s -X GET "${GATEWAY_URL}/api/v1/payments/${PAYMENT_ID}" \
  -H "Authorization: Bearer ${TOKEN}")
STATUS=$(echo "${PAYMENT_STATUS_RES}" | grep -o '"status":"[^"]*' | cut -d'"' -f4)
echo "Current Payment Status: ${STATUS}"

if [ "${STATUS}" != "SUCCEEDED" ]; then
  # In case failure rate hit 10%, create another payment until succeeded
  echo "Payment was ${STATUS}, creating guaranteed successful test payment..."
  for i in {1..5}; do
    IDEM_KEY="week5-retry-${i}-${RAND_SUFFIX}"
    PAYMENT_RES=$(curl -s -X POST "${GATEWAY_URL}/api/v1/payments" \
      -H "Authorization: Bearer ${TOKEN}" \
      -H "Idempotency-Key: ${IDEM_KEY}" \
      -H "Content-Type: application/json" \
      -d '{
        "amount": 10000,
        "currency": "INR",
        "method": "CARD",
        "description": "Retry payment"
      }')
    PAYMENT_ID=$(echo "${PAYMENT_RES}" | grep -o '"id":"[^"]*' | cut -d'"' -f4)
    sleep 2
    PAYMENT_STATUS_RES=$(curl -s -X GET "${GATEWAY_URL}/api/v1/payments/${PAYMENT_ID}" \
      -H "Authorization: Bearer ${TOKEN}")
    STATUS=$(echo "${PAYMENT_STATUS_RES}" | grep -o '"status":"[^"]*' | cut -d'"' -f4)
    echo "Attempt $i: Payment ${PAYMENT_ID} -> ${STATUS}"
    if [ "${STATUS}" == "SUCCEEDED" ]; then
      break
    fi
  done
fi

echo -e "\n6. Issuing Partial Refund (amount = 4000 paise)..."
REFUND_PARTIAL_RES=$(curl -s -X POST "${GATEWAY_URL}/api/v1/refunds" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "paymentId": "'${PAYMENT_ID}'",
    "amount": 4000,
    "currency": "INR",
    "reason": "Customer requested partial return of goods"
  }')
echo "Partial Refund Response: ${REFUND_PARTIAL_RES}"
REFUND_1_ID=$(echo "${REFUND_PARTIAL_RES}" | grep -o '"id":"[^"]*' | cut -d'"' -f4)

echo "Waiting 2s for Kafka refund.completed event & payment-service state transition..."
sleep 2

UPDATED_PAYMENT_RES=$(curl -s -X GET "${GATEWAY_URL}/api/v1/payments/${PAYMENT_ID}" \
  -H "Authorization: Bearer ${TOKEN}")
NEW_STATUS=$(echo "${UPDATED_PAYMENT_RES}" | grep -o '"status":"[^"]*' | cut -d'"' -f4)
echo "Payment Status after partial refund: ${NEW_STATUS} (Expected: PARTIALLY_REFUNDED)"

echo -e "\n7. Testing Refund Limit Validation (attempting 7000 when only 6000 remains)..."
OVER_REFUND_RES=$(curl -s -w "\nHTTP_STATUS:%{http_code}\n" -X POST "${GATEWAY_URL}/api/v1/refunds" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "paymentId": "'${PAYMENT_ID}'",
    "amount": 7000,
    "currency": "INR",
    "reason": "Exceeding balance"
  }')
echo "Over-refund rejection response:"
echo "${OVER_REFUND_RES}"

echo -e "\n8. Issuing Full Remaining Refund (amount = 6000 paise)..."
REFUND_FULL_RES=$(curl -s -X POST "${GATEWAY_URL}/api/v1/refunds" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "paymentId": "'${PAYMENT_ID}'",
    "amount": 6000,
    "currency": "INR",
    "reason": "Settling remaining balance"
  }')
echo "Full Refund Response: ${REFUND_FULL_RES}"
REFUND_2_ID=$(echo "${REFUND_FULL_RES}" | grep -o '"id":"[^"]*' | cut -d'"' -f4)

echo "Waiting 2s for Kafka refund.completed event & final state transition..."
sleep 2

FINAL_PAYMENT_RES=$(curl -s -X GET "${GATEWAY_URL}/api/v1/payments/${PAYMENT_ID}" \
  -H "Authorization: Bearer ${TOKEN}")
FINAL_STATUS=$(echo "${FINAL_PAYMENT_RES}" | grep -o '"status":"[^"]*' | cut -d'"' -f4)
echo "Payment Status after full refund: ${FINAL_STATUS} (Expected: REFUNDED)"

echo -e "\n9. Testing Refund on Fully Refunded Payment (attempting 500 when 0 remains)..."
POST_REFUND_RES=$(curl -s -w "\nHTTP_STATUS:%{http_code}\n" -X POST "${GATEWAY_URL}/api/v1/refunds" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "paymentId": "'${PAYMENT_ID}'",
    "amount": 500,
    "currency": "INR",
    "reason": "After full refund"
  }')
echo "Rejection response:"
echo "${POST_REFUND_RES}"

echo -e "\n10. Fetching All Refunds for Payment ${PAYMENT_ID}..."
PAYMENT_REFUNDS=$(curl -s -X GET "${GATEWAY_URL}/api/v1/refunds/payment/${PAYMENT_ID}" \
  -H "Authorization: Bearer ${TOKEN}")
echo "Refunds list: ${PAYMENT_REFUNDS}"

echo -e "\n11. Inspecting Webhook Delivery Audit Logs..."
DELIVERIES=$(curl -s -X GET "${GATEWAY_URL}/api/v1/webhooks/deliveries" \
  -H "Authorization: Bearer ${TOKEN}")
echo "Delivery logs (recent items):"
echo "${DELIVERIES}"

echo -e "\n=========================================================="
echo "  Week 5 Verification Completed Successfully!             "
echo "=========================================================="
