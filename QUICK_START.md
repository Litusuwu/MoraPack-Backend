# Quick Start - Flight Instance Implementation

## ✅ **What's Already Done**

All infrastructure components are implemented and compiling! ✅

---

## 🚀 **How to Use (3 Steps)**

### **Step 1: Start the Application**

The database schema will auto-update:

```bash
cd backend
mvn spring-boot:run
```

**What happens automatically**:
- Hibernate detects the new `assignedFlightInstance` field
- Adds column: `ALTER TABLE products ADD COLUMN assigned_flight_instance VARCHAR(100)`
- Your database is now ready! ✅

**Verify** (optional):
```sql
-- Check the column exists
\d products;
-- Should see: assigned_flight_instance | character varying(100)
```

---

### **Step 2: Test Flight Expansion** (Optional)

Create a simple test to see flight instances in action:

```java
@Autowired
private FlightExpansionService flightExpansionService;

@Test
public void testFlightExpansion() {
    // Create a flight template
    FlightSchema template = FlightSchema.builder()
        .id(1)
        .code("LIM-CUZ")
        .departureTime(LocalTime.of(20, 0))  // 8 PM
        .arrivalTime(LocalTime.of(3, 0))     // 3 AM next day!
        .maxCapacity(300)
        .build();

    // Expand for 7 days
    List<FlightInstanceSchema> instances = flightExpansionService.expandFlightsForSimulation(
        List.of(template),
        LocalDateTime.of(2025, 1, 2, 0, 0),
        LocalDateTime.of(2025, 1, 9, 0, 0)
    );

    // Should create 7 instances (one per day)
    System.out.println("Created " + instances.size() + " flight instances");

    instances.forEach(inst -> {
        System.out.println(inst.getInstanceId() + ": " +
            inst.getDepartureDateTime() + " → " + inst.getArrivalDateTime());
    });
}
```

**Expected Output**:
```
Created 7 flight instances
FL-1-DAY-0-2000: 2025-01-02T20:00 → 2025-01-03T03:00
FL-1-DAY-1-2000: 2025-01-03T20:00 → 2025-01-04T03:00
FL-1-DAY-2-2000: 2025-01-04T20:00 → 2025-01-05T03:00
...
```

---

### **Step 3: Use FlightInstanceManager in Your Algorithm**

#### **Current Code** (uses FlightSchema)
```java
// Solution.java constructor
this.flightSchemas = dataSource.loadFlights(airports);
```

#### **Updated Code** (uses FlightInstanceManager)
```java
// Solution.java constructor
public Solution(LocalDateTime simulationStartTime, LocalDateTime simulationEndTime) {
    // ... existing initialization ...

    // Load flight templates
    ArrayList<FlightSchema> flightTemplates = dataSource.loadFlights(this.airportSchemas);

    // NEW: Expand into daily instances
    this.flightInstances = dataSource.loadFlightInstances(
        flightTemplates,
        simulationStartTime,
        simulationEndTime
    );

    // NEW: Load existing assignments (for re-runs)
    Map<String, List<ProductSchema>> existingAssignments =
        dataSource.loadExistingProductAssignments(simulationStartTime, simulationEndTime);

    // NEW: Initialize flight instance manager
    this.flightInstanceManager = new FlightInstanceManager();
    this.flightInstanceManager.initialize(
        this.flightInstances,
        existingAssignments,
        simulationStartTime,
        simulationEndTime
    );

    // Keep existing FlightSchema for backward compatibility
    this.flightSchemas = flightTemplates;

    // ... rest of existing code ...
}
```

---

## 🔄 **How Re-Runs Work**

### **First Run** (00:00 - 08:00)
```bash
POST /api/algorithm/daily
{ "startTime": "2025-01-02T00:00", "durationHours": 8 }
```

**What happens**:
1. FlightInstanceManager creates 350 flight instances (50 routes × 7 days)
2. All instances start with `usedCapacity = 0`
3. Algorithm assigns 150 products
4. Database saves: `UPDATE products SET assigned_flight_instance = 'FL-1-DAY-0-2000'`

---

### **Second Run** (08:00 - 16:00) - **RE-RUN!**
```bash
POST /api/algorithm/daily
{ "startTime": "2025-01-02T08:00", "durationHours": 8 }
```

**What happens**:
1. DatabaseInputDataSource loads existing assignments:
   ```sql
   SELECT * FROM products
   WHERE assigned_flight_instance IS NOT NULL
     AND status IN ('PENDING', 'IN_TRANSIT');
   ```
   Result: 150 products already assigned

2. FlightInstanceManager pre-fills capacity:
   - FL-1-DAY-0-2000: `usedCapacity = 50` ← From DB!
   - FL-1-DAY-1-2000: `usedCapacity = 30` ← From DB!
   - FL-2-DAY-0-0800: `usedCapacity = 0` ← Not used yet

3. Algorithm runs with **pre-filled state**:
   - Respects existing capacity
   - Assigns new products to remaining space
   - **Builds on previous run!** ✅

---

## 📊 **Benefits**

| Before | After |
|--------|-------|
| ❌ Only 1 flight instance exists | ✅ 7 daily instances (one per day) |
| ❌ Days 0-6 share 300 capacity | ✅ Each day has own 300 capacity |
| ❌ Re-runs start fresh | ✅ Re-runs build on previous assignments |
| ❌ Can't handle midnight-crossing | ✅ Properly spans two days |

---

## 🧪 **Testing Checklist**

- [ ] Start application (verify `assigned_flight_instance` column created)
- [ ] Run algorithm for 8-hour window
- [ ] Check database: `SELECT * FROM products WHERE assigned_flight_instance IS NOT NULL;`
- [ ] Run algorithm again (same time window) → Should load existing assignments
- [ ] Check logs for: `"[PREFILL] Flight instance FL-X-DAY-Y-HHMM: N products pre-assigned"`

---

## 📁 **Files Modified**

**Core Infrastructure** (✅ All Done):
1. `bll/service/FlightExpansionService.java` - Flight expansion
2. `bll/service/FlightInstanceManager.java` - Instance management
3. `schemas/algorithm/Input/InputDataSource.java` - New methods
4. `schemas/algorithm/Input/DatabaseInputDataSource.java` - Implementations
5. `dao/morapack_psql/model/Product.java` - New field
6. `bll/service/AlgorithmPersistenceService.java` - Instance persistence
7. `application.properties` - Hibernate auto-DDL

**To Be Updated** (⏳ Next Step):
1. `schemas/algorithm/ALNS/Solution.java` - Integrate FlightInstanceManager

---

## ❓ **FAQ**

**Q: Do I need to run SQL scripts?**
A: No! Hibernate auto-creates the column when you start the app.

**Q: Will this break my existing algorithm?**
A: No! The FlightSchema fields remain unchanged for backward compatibility.

**Q: How do I verify it's working?**
A: Check the logs on startup:
```
[DATABASE] Expanding flight templates into daily instances...
Created flight instances: 350
[PREFILL] No existing assignments to pre-fill
FlightInstanceManager initialized successfully
```

**Q: What if I want manual SQL control?**
A: Run `backend/add_flight_instance_column.sql` and set `spring.jpa.hibernate.ddl-auto=validate`

---

## 🆘 **Troubleshooting**

**Problem**: Column not created on startup
**Solution**: Check `application.properties` has `spring.jpa.hibernate.ddl-auto=update`

**Problem**: "assignedFlightInstance not found" error
**Solution**: Restart the application to trigger schema update

**Problem**: Re-runs don't load existing assignments
**Solution**: Check products have `assigned_flight_instance` populated in DB

---

## 📚 **More Documentation**

- **FLIGHT_INSTANCE_INTEGRATION_GUIDE.md** - Detailed integration steps
- **IMPLEMENTATION_SUMMARY.md** - Complete overview
- **add_flight_instance_column.sql** - Manual SQL (if needed)

---

**Ready to go!** Start the app and the database will auto-update. Then integrate FlightInstanceManager into your algorithm. 🚀
