#!/bin/bash

BASE_URL="http://localhost:8080/api"

echo "======================================"
echo "TEST: Full Day Simulation (30-min intervals)"
echo "======================================"

# 1. Clear old data
echo -e "\n1. Clearing old data..."
curl -s -X DELETE "$BASE_URL/data/clear-orders" > /dev/null
echo "✓ Done"

# 2. Load orders for Jan 02
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
for status, count in sorted(statuses.items()):
    print(f\"    {status}: {count}\")
"

# 4. Run algorithm for full day
echo -e "\n4. Running algorithm for Day 1 (24 hours)..."
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

# 5. Update states every 30 minutes for 24 hours (48 updates)
echo -e "\n5. Updating states every 30 minutes (24 hours = 48 updates)...\n"
START_TIME="2025-01-02T00:00:00"

for i in {1..48}; do
    # Calculate time (i * 30 minutes)
    MINUTES=$((i * 30))
    TIME=$(python3 -c "
from datetime import datetime, timedelta
start = datetime.fromisoformat('$START_TIME')
new_time = start + timedelta(minutes=$MINUTES)
print(new_time.strftime('%Y-%m-%dT%H:%M:%S'))
")

    HOURS=$(python3 -c "print($MINUTES / 60)")

    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "Update #$i: $TIME (Hour $HOURS)"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

    UPDATE_RESULT=$(curl -s -X POST "$BASE_URL/simulation/update-states" \
      -H "Content-Type: application/json" \
      -d "{\"currentTime\": \"$TIME\"}")

    echo "$UPDATE_RESULT" | python3 -c "
import sys, json
data = json.load(sys.stdin)
trans = data.get('transitions', {})
total = trans.get('total', 0)

print(f\"Transitions: {total}\")
if total > 0:
    pa = trans.get('pendingToAssigned', 0)
    pi = trans.get('pendingToInTransit', 0)
    ai = trans.get('assignedToInTransit', 0)
    ia = trans.get('inTransitToArrived', 0)
    ad = trans.get('arrivedToDelivered', 0)

    if pa > 0: print(f\"  ✈️  PENDING → ASSIGNED: {pa}\")
    if pi > 0: print(f\"  🚀 PENDING → IN_TRANSIT: {pi}\")
    if ai > 0: print(f\"  🛫 ASSIGNED → IN_TRANSIT: {ai}\")
    if ia > 0: print(f\"  🛬 IN_TRANSIT → ARRIVED: {ia}\")
    if ad > 0: print(f\"  📦 ARRIVED → DELIVERED: {ad}\")
"

    # Show warehouse status every 2 hours (every 4 updates)
    if [ $((i % 4)) -eq 0 ]; then
        echo -e "\n📊 Current Status Distribution:"
        curl -s "$BASE_URL/orders" | python3 -c "
import sys, json
from collections import Counter
data = json.load(sys.stdin)
jan_02 = [o for o in data if o.get('creationDate', '').startswith('2025-01-02')]
statuses = Counter(o.get('status') for o in jan_02)
total = len(jan_02)

for status in ['PENDING', 'ASSIGNED', 'IN_TRANSIT', 'ARRIVED', 'DELIVERED']:
    count = statuses.get(status, 0)
    pct = (count / $total * 100) if $total > 0 else 0
    if count > 0:
        bar = '█' * int(pct / 2)
        print(f\"  {status:12} {count:3} ({pct:5.1f}%) {bar}\")
"
        echo ""
    fi
done

echo ""
echo "======================================"
echo "SIMULATION COMPLETE"
echo "======================================"
echo ""
echo "📊 Final Status Distribution:"
curl -s "$BASE_URL/orders" | python3 -c "
import sys, json
from collections import Counter
data = json.load(sys.stdin)
jan_02 = [o for o in data if o.get('creationDate', '').startswith('2025-01-02')]
statuses = Counter(o.get('status') for o in jan_02)
total = len(jan_02)

print(f\"\nTotal Orders: {total}\n\")

for status in ['PENDING', 'ASSIGNED', 'IN_TRANSIT', 'ARRIVED', 'DELIVERED']:
    count = statuses.get(status, 0)
    pct = (count / total * 100) if total > 0 else 0
    if count > 0:
        bar = '█' * int(pct / 2)
        print(f\"{status:12} {count:3} ({pct:5.1f}%) {bar}\")
"

echo ""
echo "======================================"
