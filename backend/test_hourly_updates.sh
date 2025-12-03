#!/bin/bash

BASE_URL="http://localhost:8080/api"

echo "======================================"
echo "TEST: Hourly State Updates"
echo "======================================"

# 1. Clear old data
echo -e "\n1. Clearing old data..."
curl -s -X DELETE "$BASE_URL/data/clear-orders" > /dev/null
echo "✓ Done"

# 2. Load orders
echo -e "\n2. Loading orders for Jan 02..."
LOAD_RESULT=$(curl -s -X POST "$BASE_URL/data/load-orders?startTime=2025-01-02T00:00:00&endTime=2025-01-02T23:59:59")
echo "$LOAD_RESULT" | python3 -c "
import sys, json
data = json.load(sys.stdin)
stats = data.get('statistics', {})
print(f\"  Orders loaded: {stats.get('ordersLoaded')}\")
print(f\"  Orders created: {stats.get('ordersCreated')}\")
"

# 3. Check initial order status
echo -e "\n3. Initial order status:"
curl -s "$BASE_URL/orders" | python3 -c "
import sys, json
from collections import Counter
data = json.load(sys.stdin)
jan_02 = [o for o in data if o.get('creationDate', '').startswith('2025-01-02')]
statuses = Counter(o.get('status') for o in jan_02)
print(f\"  Total orders from 2025-01-02: {len(jan_02)}\")
for status, count in statuses.items():
    print(f\"    {status}: {count}\")
"

# 4. Run algorithm
echo -e "\n4. Running algorithm for Day 1..."
ALG_RESULT=$(curl -s -X POST "$BASE_URL/algorithm/daily" \
  -H "Content-Type: application/json" \
  -d '{
    "simulationStartTime": "2025-01-02T00:00:00",
    "simulationDurationHours": 24,
    "useDatabase": true
  }')

echo "$ALG_RESULT" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    print(f\"  Total Orders: {data.get('totalOrders')}\")
    print(f\"  Total Products: {data.get('totalProducts')}\")
    print(f\"  Assigned Products: {data.get('assignedProducts')}\")
except:
    print('  Algorithm response error')
"

# 5. Update states hourly
echo -e "\n5. Updating states hourly (12 hours)..."
START_TIME="2025-01-02T00:00:00"

for hour in {1..12}; do
    TIME=$(python3 -c "
from datetime import datetime, timedelta
start = datetime.fromisoformat('$START_TIME')
new_time = start + timedelta(hours=$hour)
print(new_time.strftime('%Y-%m-%dT%H:%M:%S'))
")

    echo -e "\n  Hour $hour: $TIME"

    UPDATE_RESULT=$(curl -s -X POST "$BASE_URL/simulation/update-states" \
      -H "Content-Type: application/json" \
      -d "{\"currentTime\": \"$TIME\"}")

    echo "$UPDATE_RESULT" | python3 -c "
import sys, json
data = json.load(sys.stdin)
trans = data.get('transitions', {})
total = trans.get('total', 0)
if total > 0:
    print(f\"    Transitions: {total}\")
    print(f\"      PENDING -> ASSIGNED: {trans.get('pendingToAssigned', 0)}\")
    print(f\"      PENDING -> IN_TRANSIT: {trans.get('pendingToInTransit', 0)}\")
    print(f\"      ASSIGNED -> IN_TRANSIT: {trans.get('assignedToInTransit', 0)}\")
    print(f\"      IN_TRANSIT -> ARRIVED: {trans.get('inTransitToArrived', 0)}\")
    print(f\"      ARRIVED -> DELIVERED: {trans.get('arrivedToDelivered', 0)}\")
else:
    print(f\"    No transitions\")
"

    # Show order status distribution every 3 hours
    if [ $((hour % 3)) -eq 0 ]; then
        echo "    Order status distribution:"
        curl -s "$BASE_URL/orders" | python3 -c "
import sys, json
from collections import Counter
data = json.load(sys.stdin)
jan_02 = [o for o in data if o.get('creationDate', '').startswith('2025-01-02')]
statuses = Counter(o.get('status') for o in jan_02)
for status, count in sorted(statuses.items()):
    print(f\"      {status}: {count}\")
"
    fi
done

echo -e "\n======================================"
echo "TEST COMPLETE"
echo "======================================"
