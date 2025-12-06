#!/bin/bash

BASE_URL="http://localhost:8080/api"

echo "==================================="
echo "WAREHOUSE CAPACITY UPDATE TEST"
echo "==================================="

# 1. Load initial data
echo -e "\n1. Loading airports..."
curl -s -X POST "$BASE_URL/data-import/airports" | grep -o '"count":[0-9]*'

echo -e "\n2. Loading flights..."
curl -s -X POST "$BASE_URL/data-import/flights" | grep -o '"count":[0-9]*'

echo -e "\n3. Loading orders for Day 1..."
curl -s -X POST "$BASE_URL/data/load-orders?startTime=2025-01-02T00:00:00&endTime=2025-01-02T23:59:59" | grep -o '"ordersLoaded":[0-9]*'

# 2. Check initial warehouse capacities
echo -e "\n4. Initial warehouse capacities (first 5):"
curl -s "$BASE_URL/warehouses" | python3 -c "
import sys, json
data = json.load(sys.stdin)
for w in data[:5]:
    print(f\"  {w['name']}: {w['usedCapacity']}/{w['maxCapacity']}\")
"

# 3. Run algorithm for Day 1
echo -e "\n5. Running algorithm for Day 1..."
RESULT=$(curl -s -X POST "$BASE_URL/algorithm/daily" \
  -H "Content-Type: application/json" \
  -d '{
    "simulationStartTime": "2025-01-02T00:00:00",
    "simulationDurationHours": 24,
    "useDatabase": true
  }')

echo "$RESULT" | python3 -c "
import sys, json
data = json.load(sys.stdin)
print(f\"  Total Orders: {data.get('totalOrders', 0)}\")
print(f\"  Assigned Orders: {data.get('assignedOrders', 0)}\")
print(f\"  Total Products: {data.get('totalProducts', 0)}\")
print(f\"  Assigned Products: {data.get('assignedProducts', 0)}\")
"

# 4. Check warehouses after algorithm
echo -e "\n6. Main warehouse capacities AFTER algorithm:"
curl -s "$BASE_URL/warehouses" | python3 -c "
import sys, json
data = json.load(sys.stdin)
for w in data:
    if 'Lima' in w['name'] or 'Bruselas' in w['name'] or 'Baku' in w['name']:
        print(f\"  {w['name']}: {w['usedCapacity']}/{w['maxCapacity']}\")
"

# 5. Update states at T+8 hours
echo -e "\n7. Updating states at 2025-01-02T08:00:00..."
UPDATE_RESULT=$(curl -s -X POST "$BASE_URL/simulation/update-states" \
  -H "Content-Type: application/json" \
  -d '{"currentTime": "2025-01-02T08:00:00"}')

echo "$UPDATE_RESULT" | python3 -c "
import sys, json
data = json.load(sys.stdin)
trans = data.get('transitions', {})
print(f\"  Transitions: {trans.get('total', 0)} total\")
print(f\"    - IN_TRANSIT -> ARRIVED: {trans.get('inTransitToArrived', 0)}\")
print(f\"    - ARRIVED -> DELIVERED: {trans.get('arrivedToDelivered', 0)}\")
"

# 6. Check warehouse capacities after update-states
echo -e "\n8. Warehouse capacities AFTER update-states:"
WAREHOUSES=$(curl -s "$BASE_URL/warehouses")

echo "$WAREHOUSES" | python3 -c "
import sys, json
data = json.load(sys.stdin)
changed = []
for w in data:
    if w['usedCapacity'] > 0:
        changed.append(w)

if changed:
    print(f\"  Found {len(changed)} warehouses with products:\")
    for w in changed[:10]:  # Show first 10
        print(f\"    - {w['name']}: {w['usedCapacity']}/{w['maxCapacity']}\")
else:
    print(\"  WARNING: No warehouses showing used capacity!\")
"

# 7. Update states at T+16 hours
echo -e "\n9. Updating states at 2025-01-02T16:00:00..."
UPDATE_RESULT2=$(curl -s -X POST "$BASE_URL/simulation/update-states" \
  -H "Content-Type: application/json" \
  -d '{"currentTime": "2025-01-02T16:00:00"}')

echo "$UPDATE_RESULT2" | python3 -c "
import sys, json
data = json.load(sys.stdin)
trans = data.get('transitions', {})
print(f\"  Transitions: {trans.get('total', 0)} total\")
"

# 8. Final warehouse check
echo -e "\n10. Final warehouse capacities:"
curl -s "$BASE_URL/warehouses" | python3 -c "
import sys, json
data = json.load(sys.stdin)
non_zero = [w for w in data if w['usedCapacity'] > 0]
print(f\"  Warehouses with products: {len(non_zero)}\")
for w in non_zero[:10]:
    print(f\"    - {w['name']}: {w['usedCapacity']}/{w['maxCapacity']}\")
"

echo -e "\n==================================="
echo "TEST COMPLETE"
echo "==================================="
