# Load Orders API Examples

## Load 1 Week of Orders

### Example 1: Load orders from January 2-9, 2025 (7 days)

```bash
curl -X POST http://localhost:8080/api/data/load-orders \
  -H "Content-Type: application/json" \
  -d '{
    "startTime": "2025-01-02T00:00:00",
    "endTime": "2025-01-09T23:59:59"
  }'
```

**Note:** The endpoint uses query parameters, not request body. Correct syntax:

```bash
curl -X POST "http://localhost:8080/api/data/load-orders?startTime=2025-01-02T00:00:00&endTime=2025-01-09T23:59:59"
```

### Example 2: Load 1 week starting from today

```bash
# Using current date + 7 days
curl -X POST "http://localhost:8080/api/data/load-orders?startTime=2025-11-06T00:00:00&endTime=2025-11-13T23:59:59"
```

### Example 3: Load ALL orders (no time filter)

```bash
curl -X POST http://localhost:8080/api/data/load-orders
```

---

## Expected Response

```json
{
  "success": true,
  "message": "Orders loaded successfully",
  "statistics": {
    "ordersLoaded": 150000,      // Orders read from files
    "ordersCreated": 150000,     // Orders inserted to DB
    "ordersFiltered": 50000,     // Orders outside time window
    "customersCreated": 8500,    // New customers created
    "parseErrors": 0,            // Parse failures
    "fileErrors": 0,             // File read errors
    "durationSeconds": 180       // ~3 minutes (with batching)
  },
  "timeWindow": {
    "startTime": "2025-01-02T00:00:00",
    "endTime": "2025-01-09T23:59:59"
  },
  "startTime": "2025-11-06T13:45:00",
  "endTime": "2025-11-06T13:48:00"
}
```

---

## Using Postman

1. **Method:** POST
2. **URL:** `http://localhost:8080/api/data/load-orders`
3. **Params:**
   - Key: `startTime`, Value: `2025-01-02T00:00:00`
   - Key: `endTime`, Value: `2025-01-09T23:59:59`
4. **Send**

---

## Using HTTPie

```bash
http POST localhost:8080/api/data/load-orders \
  startTime=="2025-01-02T00:00:00" \
  endTime=="2025-01-09T23:59:59"
```

---

## Date Format

The endpoint expects **ISO 8601** format:
- Format: `yyyy-MM-ddTHH:mm:ss`
- Example: `2025-01-02T00:00:00` (January 2, 2025 at midnight)
- Timezone: Treated as server timezone

---

## How It Works

### Order File Format
```
File: _pedidos_LATI_.txt
─────────────────────────────────────────
000000001-20250102-01-38-EBCI-006-0007729
          ^^^^^^^^ ^^^^^ - Date & Time
          20250102 = January 2, 2025
                   01:38 = 1:38 AM
```

### Filtering Process
```
1. Read all _pedidos_*.txt files
2. Parse each order line
3. Extract orderDate from line
4. Filter: if (orderDate >= startTime && orderDate <= endTime)
5. Keep matching orders, discard others
6. Batch insert filtered orders
```

---

## Performance Estimates

### With Batch Insert Enabled (After Running Migration)

| Time Window | Expected Orders | Load Time | Customers |
|-------------|----------------|-----------|-----------|
| 1 Day | ~600,000 | 1-2 min ⚡ | ~4,000 |
| 1 Week | ~4,200,000 | 10-20 min ⚡ | ~32,000 |
| 1 Month | ~18,000,000 | 45-90 min ⚡ | ~140,000 |
| All Data | ~20,000,000+ | 60-120 min ⚡ | ~150,000 |

*(Times assume Hibernate batch configuration is active)*

---

## Common Time Windows

### Load 1 Week
```bash
curl -X POST "http://localhost:8080/api/data/load-orders?startTime=2025-01-02T00:00:00&endTime=2025-01-09T23:59:59"
```

### Load 1 Day
```bash
curl -X POST "http://localhost:8080/api/data/load-orders?startTime=2025-01-02T00:00:00&endTime=2025-01-02T23:59:59"
```

### Load 1 Hour
```bash
curl -X POST "http://localhost:8080/api/data/load-orders?startTime=2025-01-02T01:00:00&endTime=2025-01-02T02:00:00"
```

### Load Specific Days
```bash
# Only January 5, 2025
curl -X POST "http://localhost:8080/api/data/load-orders?startTime=2025-01-05T00:00:00&endTime=2025-01-05T23:59:59"
```

---

## Troubleshooting

### Error: "No order files found"
**Solution:** Check that files exist in `backend/data/products/`
```bash
ls backend/data/products/_pedidos_*.txt
```

### Error: "Airport not found"
**Solution:** Load airport data first
```bash
# Load airports before orders
make set-up-db
```

### Error: "No customers found"
**Solution:** Customers are created automatically now! Should not happen.

### Very slow loading (60+ minutes)
**Solution:** Run the sequence migration to enable batching
```bash
psql -h localhost -p 5435 -U postgres -d postgres \
  -f backend/db/migrations/001_create_orders_sequence.sql
```

---

## Verification

Check how many orders were loaded:
```bash
# Connect to database
psql -h localhost -p 5435 -U postgres -d postgres

# Count orders
SELECT COUNT(*) FROM orders;

# Check orders by date range
SELECT
  DATE(creation_date) as order_date,
  COUNT(*) as order_count
FROM orders
WHERE creation_date BETWEEN '2025-01-02' AND '2025-01-09'
GROUP BY DATE(creation_date)
ORDER BY order_date;

# Check customers created
SELECT COUNT(*) FROM customers;
```

---

## Pro Tips

1. **Start small:** Load 1 day first to verify everything works
2. **Monitor logs:** Watch console output for progress
3. **Check duration:** Note the `durationSeconds` in response
4. **Verify data:** Query database after load completes
5. **Batch enabled?** Should take ~10-20 min for 1 week, not 60+ min

---

Generated: 2025-11-06
For: MoraPack Backend - Load Orders Endpoint
