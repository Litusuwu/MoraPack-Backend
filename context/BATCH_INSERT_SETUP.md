# Batch Insert Setup Instructions

## ⚠️ Important: Run Database Migration First!

Before starting your application with the new batch insert configuration, you **MUST** run the database migration to create the sequence.

## Step 1: Run the Database Migration

### Option A: Using psql (Recommended)

```bash
# Connect to your PostgreSQL database
psql -h localhost -p 5435 -U postgres -d postgres

# Run the migration script
\i backend/db/migrations/001_create_orders_sequence.sql

# Verify the sequence was created
\ds orders_id_seq
```

### Option B: Using Docker exec

```bash
# If using Docker Compose for PostgreSQL
docker exec -i morapack-postgres psql -U postgres -d postgres < backend/db/migrations/001_create_orders_sequence.sql
```

### Option C: Using DBeaver or pgAdmin

1. Open your database client
2. Connect to: `localhost:5435`, database: `postgres`, user: `postgres`
3. Open `backend/db/migrations/001_create_orders_sequence.sql`
4. Execute the script

## Step 2: Verify Migration Success

Run this query to verify the sequence exists:

```sql
SELECT
    sequence_name,
    start_value,
    increment_by,
    last_value
FROM information_schema.sequences
WHERE sequence_name = 'orders_id_seq';
```

Expected output:
```
  sequence_name  | start_value | increment_by | last_value
-----------------+-------------+--------------+------------
 orders_id_seq   |      1      |      50      |     NULL
```

## Step 3: Restart Your Application

The batch insert configuration is already in `application.properties`. Just restart:

```bash
# Stop the application if running
# Then start it again
mvn spring-boot:run
```

## What Changed

### 1. Order Entity (`Order.java`)
```java
// BEFORE (No batching support)
@GeneratedValue(strategy = GenerationType.IDENTITY)

// AFTER (Batching enabled!)
@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "order_seq")
@SequenceGenerator(name = "order_seq", sequenceName = "orders_id_seq", allocationSize = 50)
```

### 2. Application Properties
Added Hibernate batch configuration:
- `spring.jpa.properties.hibernate.jdbc.batch_size=2000`
- `spring.jpa.properties.hibernate.order_inserts=true`
- `spring.jpa.properties.hibernate.order_updates=true`
- And more...

## Expected Performance Improvement

### Before (IDENTITY + No Batching):
```
4,227,716 orders
Speed: ~1,000-1,500 inserts/second
Time: 60-120 minutes ❌
```

### After (SEQUENCE + Batching):
```
4,227,716 orders
Speed: ~5,000-10,000 inserts/second
Time: 10-20 minutes ⚡ (6x faster!)
```

## Verification

To verify batching is working, temporarily enable SQL logging in `application.properties`:

```properties
# Uncomment these lines
spring.jpa.show-sql=true
spring.jpa.properties.hibernate.format_sql=true
logging.level.org.hibernate.SQL=DEBUG
```

Look for output like:
```
Hibernate: insert into orders (...) values (?, ?, ...)
Hibernate: insert into orders (...) values (?, ?, ...)
...
[Batch of 2000 statements executed]
```

## Troubleshooting

### Error: "relation 'orders_id_seq' does not exist"
**Solution:** You forgot to run the migration! Go back to Step 1.

### Error: "duplicate key value violates unique constraint"
**Solution:** The sequence start value is too low. Run:
```sql
SELECT setval('orders_id_seq', (SELECT MAX(id) FROM orders) + 1);
```

### Still slow after changes?
**Check:**
1. Sequence migration was applied ✓
2. Application was restarted ✓
3. SQL logs show batch execution ✓
4. `GenerationType.SEQUENCE` is in Order.java ✓

## Next Steps

After confirming batching works:
1. Monitor first load time (should be ~10-20 min)
2. If still too slow, consider JDBC batch insert (see `PERFORMANCE_OPTIMIZATION.md`)
3. For extreme performance, consider PostgreSQL COPY command

---

**Created:** 2025-11-06
**For:** MoraPack Backend Order Loading Optimization
