#!/bin/bash

# Test script to verify local delivery (same origin and destination) handling
# Tests that orders from LIMA to LIMA are immediately marked as DELIVERED

echo "=========================================="
echo "LOCAL DELIVERY TEST - LIMA to LIMA Orders"
echo "=========================================="

# Base URL
BASE_URL="http://localhost:8080"

# Step 1: Clear database and reload data
echo ""
echo "Step 1: Clearing database and reloading test data..."
psql -h localhost -p 5435 -U postgres -d postgres -c "TRUNCATE TABLE products, orders, order_trackings RESTART IDENTITY CASCADE;"

# Step 2: Load airports and flights
echo ""
echo "Step 2: Loading airports and flights..."
curl -X POST "$BASE_URL/api/data-import/airports" -H "Content-Type: application/json"
echo ""
curl -X POST "$BASE_URL/api/data-import/flights" -H "Content-Type: application/json"
echo ""

# Step 3: Load orders for a specific time window
echo ""
echo "Step 3: Loading orders from 2025-01-02 00:00 to 2025-01-03 00:00..."
curl -X POST "$BASE_URL/api/data/load-orders" \
  -H "Content-Type: application/json" \
  -d '{
    "startDate": "2025-01-02T00:00:00",
    "endDate": "2025-01-03T00:00:00"
  }'
echo ""

# Step 4: Run algorithm for the same time window
echo ""
echo "Step 4: Running algorithm for daily scenario..."
curl -X POST "$BASE_URL/api/algorithm/daily" \
  -H "Content-Type: application/json" \
  -d '{
    "simulationStartTime": "2025-01-02T00:00:00",
    "simulationDurationHours": 24,
    "useDatabase": true
  }' | jq '.'
echo ""

# Step 5: Check product statuses in database
echo ""
echo "Step 5: Checking product statuses in database..."
echo ""
echo "Products by status:"
psql -h localhost -p 5435 -U postgres -d postgres -c \
  "SELECT status, COUNT(*) as count FROM products GROUP BY status ORDER BY status;"

echo ""
echo "Sample of DELIVERED products (local deliveries):"
psql -h localhost -p 5435 -U postgres -d postgres -c \
  "SELECT p.id, p.name, p.status, o.name as order_name,
          o.origin_id, o.destination_id,
          p.assigned_flight_instance
   FROM products p
   JOIN orders o ON p.order_id = o.id
   WHERE p.status = 'DELIVERED'
   LIMIT 10;"

echo ""
echo "Verification: Check if any products have same origin and destination but are NOT DELIVERED:"
psql -h localhost -p 5435 -U postgres -d postgres -c \
  "SELECT p.id, p.name, p.status, o.name as order_name,
          o.origin_id, o.destination_id,
          c_origin.name as origin_city,
          c_dest.name as destination_city
   FROM products p
   JOIN orders o ON p.order_id = o.id
   JOIN cities c_origin ON o.origin_id = c_origin.id
   JOIN cities c_dest ON o.destination_id = c_dest.id
   WHERE o.origin_id = o.destination_id
     AND p.status != 'DELIVERED'
   LIMIT 10;"

echo ""
echo "=========================================="
echo "TEST COMPLETE"
echo "=========================================="
echo ""
echo "Expected results:"
echo "1. Products with empty assigned_flight_instance should have status = DELIVERED"
echo "2. Products where origin_id = destination_id should have status = DELIVERED"
echo "3. Last query should return 0 rows (no local deliveries stuck in PENDING)"
