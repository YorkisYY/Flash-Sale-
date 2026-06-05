#!/usr/bin/env bash
# Load test helper. Seeds a product, then runs k6 against it.
#
# Requires: k6, curl, jq.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080/api}"
PRODUCT_STOCK="${PRODUCT_STOCK:-100}"
TARGET_VUS="${TARGET_VUS:-500}"

DROP_AT_MS=$(($(date +%s) * 1000 - 60000))  # 1 minute ago, so the drop is open

echo "Seeding a product with stock=${PRODUCT_STOCK}..."
SEED_RESP=$(curl -sf -X POST "${BASE_URL}/internal/products" \
  -H 'Content-Type: application/json' \
  -d "{
    \"name\": \"k6 Load Drop\",
    \"price\": 1000,
    \"totalStock\": ${PRODUCT_STOCK},
    \"dropStartsAt\": \"$(date -u -d @$((DROP_AT_MS / 1000)) +%Y-%m-%dT%H:%M:%SZ)\"
  }")

PRODUCT_ID=$(echo "${SEED_RESP}" | jq -r .id)
echo "Seeded product id=${PRODUCT_ID}"

echo "Running k6..."
PRODUCT_ID="${PRODUCT_ID}" \
  PRODUCT_STOCK="${PRODUCT_STOCK}" \
  TARGET_VUS="${TARGET_VUS}" \
  BASE_URL="${BASE_URL}" \
  k6 run flash-sale.js

echo ""
echo "Final stock for product ${PRODUCT_ID}:"
curl -s "${BASE_URL}/drops/${PRODUCT_ID}" | jq '{availableStock, totalStock}'
