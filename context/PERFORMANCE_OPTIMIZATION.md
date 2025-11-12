# Performance Optimization Guide for Bulk Order Loading

## Current Situation

You're loading **4,227,716 orders** with **32,768 unique customers** - this is a massive dataset!

## Expected Performance

### ⏱️ Current Setup (No Batch Configuration)

**Estimated Time: 60-120 minutes** ❌

Without Hibernate batch configuration:
- Each order = separate INSERT statement
- ~1,000-1,500 inserts/second
- Calculation: 4,227,716 / 1,200 = 3,523 seconds ≈ **58 minutes minimum**

## 🚀 Optimization Options

### Option 1: Enable Hibernate Batch Insert (RECOMMENDED - Quick Fix)

**Add to `application.properties`:**

```properties
# ========================================
# BULK INSERT OPTIMIZATION
# ========================================

# Hibernate batch insert configuration
spring.jpa.properties.hibernate.jdbc.batch_size=2000
spring.jpa.properties.hibernate.order_inserts=true
spring.jpa.properties.hibernate.order_updates=true
spring.jpa.properties.hibernate.batch_versioned_data=true

# CRITICAL: Use sequence generation instead of identity
# IDENTITY generation doesn't support batching!
spring.jpa.properties.hibernate.id.new_generator_mappings=true

# Show SQL for debugging (disable in production)
# spring.jpa.show-sql=false
# spring.jpa.properties.hibernate.format_sql=false
```

**Update Order entity to use SEQUENCE strategy:**

```java
@Entity
@Table(name = "orders")
public class Order {
  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "order_seq")
  @SequenceGenerator(name = "order_seq", sequenceName = "order_id_seq", allocationSize = 50)
  @Column(name = "id", nullable = false)
  private Integer id;

  // ... rest of fields
}
```

**Expected Performance:**
- **Time: 10-20 minutes** ⚡
- ~5,000-10,000 inserts/second
- Calculation: 4,227,716 / 7,000 = 604 seconds ≈ **10 minutes**

---

### Option 2: Use JDBC Batch Insert (FASTER)

**Already implemented:** `OrderBatchInsertService.java`

**How to use:**

```java
// In DataLoadService, replace:
List<Order> createdOrders = orderService.bulkCreateOrders(ordersToCreate);

// With:
int inserted = orderBatchInsertService.batchInsertOrders(ordersToCreate, JDBC_BATCH_SIZE);
```

**Expected Performance:**
- **Time: 5-10 minutes** ⚡⚡
- ~10,000-20,000 inserts/second
- Bypasses JPA/Hibernate overhead
- Direct JDBC PreparedStatement batching

---

### Option 3: PostgreSQL COPY Command (FASTEST)

**For extreme performance with very large datasets:**

```java
@Service
@RequiredArgsConstructor
public class OrderCopyService {
    private final DataSource dataSource;

    public void copyFromCSV(String filePath) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            CopyManager copyManager = new CopyManager((BaseConnection) conn);

            String sql = """
                COPY orders (name, origin_city_id, destination_city_id,
                             delivery_date, status, pickup_time_hours,
                             creation_date, customer_id, updated_at)
                FROM STDIN WITH CSV
                """;

            try (FileReader reader = new FileReader(filePath)) {
                copyManager.copyIn(sql, reader);
            }
        }
    }
}
```

**Expected Performance:**
- **Time: 2-5 minutes** ⚡⚡⚡
- ~20,000-50,000 inserts/second
- Native PostgreSQL bulk load
- Fastest option available

---

## 📊 Performance Comparison Table

| Method | Time | Inserts/Sec | Complexity | Notes |
|--------|------|-------------|------------|-------|
| **Current (No batching)** | 60-120 min | 1,000-1,500 | Low | Default behavior ❌ |
| **Hibernate Batch** | 10-20 min | 5,000-10,000 | Low | Config change only ⚡ |
| **JDBC Batch** | 5-10 min | 10,000-20,000 | Medium | Already implemented ⚡⚡ |
| **PostgreSQL COPY** | 2-5 min | 20,000-50,000 | Medium | Native bulk load ⚡⚡⚡ |

---

## 🎯 Recommended Steps (Immediate)

### Step 1: Enable Hibernate Batching (Do This Now!)

1. Add the properties above to `application.properties`
2. Restart your application
3. Run the order load endpoint

**This single change will reduce your load time from 60+ minutes to ~10-15 minutes!**

### Step 2: Monitor Progress

Watch the console output:
```
========================================
BATCH INSERTING 4227716 ORDERS TO DATABASE
========================================
```

### Step 3: Verify Batching is Working

Enable SQL logging temporarily:
```properties
spring.jpa.show-sql=true
logging.level.org.hibernate.SQL=DEBUG
logging.level.org.hibernate.type.descriptor.sql.BasicBinder=TRACE
```

You should see batched inserts like:
```sql
Hibernate: insert into orders (...) values (?, ?, ...)
Hibernate: insert into orders (...) values (?, ?, ...)
...
[Batch of 2000 statements executed]
```

---

## 🔍 Additional Optimizations

### 1. Temporarily Disable Indexes During Bulk Load

```sql
-- Before loading
DROP INDEX IF EXISTS idx_orders_customer;
DROP INDEX IF EXISTS idx_orders_creation_date;

-- Load data (fast!)

-- After loading
CREATE INDEX idx_orders_customer ON orders(customer_id);
CREATE INDEX idx_orders_creation_date ON orders(creation_date);
```

**Benefit:** 30-50% faster inserts

### 2. Increase PostgreSQL Configuration

In `postgresql.conf`:
```
# Increase work memory for bulk operations
work_mem = 256MB

# Increase maintenance work memory
maintenance_work_mem = 512MB

# Increase shared buffers (if you have RAM)
shared_buffers = 2GB

# Disable synchronous commit temporarily (careful!)
# synchronous_commit = off
```

### 3. Use UNLOGGED Tables Temporarily

```sql
ALTER TABLE orders SET UNLOGGED;
-- Load data (much faster - no WAL writes)
ALTER TABLE orders SET LOGGED;
```

**Warning:** Data loss risk if crash during load!

---

## 📈 Real-World Performance Data

Based on your numbers:

```
Customers: 32,768
├─ Users batch insert: ~1-2 seconds ✅
└─ Customers batch insert: ~1-2 seconds ✅

Orders: 4,227,716
├─ Without batching: 60-120 minutes ❌
├─ With Hibernate batch: 10-20 minutes ⚡
├─ With JDBC batch: 5-10 minutes ⚡⚡
└─ With COPY command: 2-5 minutes ⚡⚡⚡
```

---

## 🎬 Quick Start

**Fastest way to improve right now:**

1. **Edit** `backend/src/main/resources/application.properties`
2. **Add** these 4 lines:
   ```properties
   spring.jpa.properties.hibernate.jdbc.batch_size=2000
   spring.jpa.properties.hibernate.order_inserts=true
   spring.jpa.properties.hibernate.order_updates=true
   spring.jpa.properties.hibernate.batch_versioned_data=true
   ```
3. **Restart** your application
4. **Run** the endpoint again

**Expected result:** Load time drops from 60+ minutes to ~10-15 minutes!

---

## 📞 Need Help?

If load time is still too slow after enabling batching:
1. Check if `GenerationType.IDENTITY` is being used (doesn't support batching)
2. Verify batch size in logs
3. Consider JDBC batch insert approach
4. Consider PostgreSQL COPY for extreme performance

---

Generated: 2025-11-06
For: MoraPack Backend - Order Loading Optimization
Dataset: 4.2M orders, 32K customers
