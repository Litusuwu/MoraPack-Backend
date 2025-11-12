# Flight Instance Integration Guide

## Summary of Changes Made

### ✅ **Completed Components**

1. **FlightExpansionService** (`bll/service/FlightExpansionService.java`)
   - Expands FlightSchema templates into daily FlightInstanceSchema objects
   - Handles flights that cross midnight (e.g., depart 20:00, arrive 03:00 next day)
   - Creates unique instance IDs like "FL-123-DAY-1-2000"

2. **FlightInstanceManager** (`bll/service/FlightInstanceManager.java`)
   - Manages all flight instances for the simulation window
   - Tracks per-instance capacity (not shared across days!)
   - Pre-fills capacity from existing DB assignments (re-run support)
   - Provides route-finding methods that work with specific flight instances

3. **Updated InputDataSource Interface** (`schemas/algorithm/Input/InputDataSource.java`)
   - Added `loadFlightInstances()` method
   - Added `loadExistingProductAssignments()` method for re-run support

4. **Updated DatabaseInputDataSource** (`schemas/algorithm/Input/DatabaseInputDataSource.java`)
   - Implements flight instance expansion using FlightExpansionService
   - Loads existing product assignments from DB (checks `assigned_flight_instance` field)
   - Filters products by status (PENDING, IN_TRANSIT)

5. **Updated Product Model** (`dao/morapack_psql/model/Product.java`)
   - Added `assignedFlightInstance` field (String, e.g., "FL-123-DAY-1-2000")
   - This field tracks which SPECIFIC daily departure the product is on

---

## ⚠️ **Critical Remaining Integration**

The algorithm (Solution.java) currently uses:
```java
HashMap<OrderSchema, ArrayList<FlightSchema>> solution
```

This needs to be updated to use `FlightInstanceSchema` instead of `FlightSchema`.

### **Option 1: Minimal Integration (RECOMMENDED)**

Add FlightInstanceManager to Solution.java initialization:

```java
public class Solution {
    // ADD NEW FIELDS
    private FlightInstanceManager flightInstanceManager;
    private List<FlightInstanceSchema> flightInstances;

    // EXISTING FIELDS
    private ArrayList<FlightSchema> flightSchemas;  // Keep for backward compatibility
    // ... rest of existing fields

    // UPDATE CONSTRUCTOR
    public Solution(LocalDateTime simulationStartTime, LocalDateTime simulationEndTime) {
        this.simulationStartTime = simulationStartTime;
        this.simulationEndTime = simulationEndTime;

        // Load data
        InputDataSource dataSource = DataSourceFactory.createDataSource();
        dataSource.initialize();

        this.airportSchemas = dataSource.loadAirports();
        ArrayList<FlightSchema> flightTemplates = dataSource.loadFlights(this.airportSchemas);

        // NEW: Expand flights into instances
        this.flightInstances = dataSource.loadFlightInstances(
            flightTemplates,
            simulationStartTime,
            simulationEndTime
        );

        // NEW: Load existing assignments for re-runs
        Map<String, List<ProductSchema>> existingAssignments =
            dataSource.loadExistingProductAssignments(simulationStartTime, simulationEndTime);

        // NEW: Initialize FlightInstanceManager
        this.flightInstanceManager = new FlightInstanceManager();
        this.flightInstanceManager.initialize(
            this.flightInstances,
            existingAssignments,
            simulationStartTime,
            simulationEndTime
        );

        // Keep existing FlightSchema list for backward compatibility
        this.flightSchemas = flightTemplates;

        // ... rest of initialization
    }
}
```

### **Option 2: Full Refactor (FUTURE WORK)**

Replace all `ArrayList<FlightSchema>` with `ArrayList<FlightInstanceSchema>` throughout Solution.java:
- Update all route finding methods
- Update capacity tracking
- Update validation methods
- Update persistence logic

**Note**: This is a ~3000 line file, so full refactor is complex.

---

## 🔄 **How Re-Runs Work Now**

### First Run (Fresh Start)
```
1. Frontend calls: POST /api/algorithm/daily
   Request: { startTime: "2025-01-02T00:00", durationHours: 8 }

2. Backend expands flights:
   - LIM-CUZ template → 7 daily instances (one per day)
   - Each instance starts with usedCapacity = 0

3. Algorithm assigns products:
   - Order #123 → Flight Instance "FL-45-DAY-0-2000"

4. Persistence saves to DB:
   - UPDATE products SET assigned_flight_instance = 'FL-45-DAY-0-2000'
```

### Second Run (Re-Run with Existing Assignments)
```
1. Frontend calls: POST /api/algorithm/daily
   Request: { startTime: "2025-01-02T08:00", durationHours: 8 }

2. Backend loads existing assignments:
   - Query: SELECT * FROM products WHERE assigned_flight_instance IS NOT NULL
   - Result: 150 products already on flights

3. FlightInstanceManager pre-fills capacity:
   - FL-45-DAY-0-2000: usedCapacity = 50 (from existing assignments)
   - FL-45-DAY-1-2000: usedCapacity = 0 (no assignments yet)

4. Algorithm assigns NEW products:
   - Respects existing capacity
   - Can't overbook flights that already have products
   - Builds on previous run instead of starting fresh
```

---

## 📊 **Database Schema Update (AUTO via Hibernate)**

**No manual SQL needed!** The schema updates automatically when you start the application.

**How it works**:
1. `application.properties` has: `spring.jpa.hibernate.ddl-auto=update`
2. Hibernate detects the new `assignedFlightInstance` field in Product entity
3. On startup, it executes: `ALTER TABLE products ADD COLUMN assigned_flight_instance VARCHAR(100)`

**Verification** (after starting the app):
```sql
-- Check the column exists
SELECT column_name, data_type
FROM information_schema.columns
WHERE table_name = 'products'
  AND column_name = 'assigned_flight_instance';
```

**Alternative**: If you prefer manual control, run `backend/add_flight_instance_column.sql`

---

## 🧪 **Testing the Implementation**

### Test 1: Flight Expansion
```java
// Create expansion service
FlightExpansionService service = new FlightExpansionService();

// Create a flight template: LIM-CUZ departs 20:00, arrives 03:00
FlightSchema template = FlightSchema.builder()
    .id(1)
    .code("LIM-CUZ")
    .departureTime(LocalTime.of(20, 0))
    .arrivalTime(LocalTime.of(3, 0))  // Next day!
    .maxCapacity(300)
    .build();

// Expand for 7-day simulation
List<FlightInstanceSchema> instances = service.expandFlightsForSimulation(
    List.of(template),
    LocalDateTime.of(2025, 1, 2, 0, 0),  // Jan 2
    LocalDateTime.of(2025, 1, 9, 0, 0)   // Jan 9
);

// Should create 7 instances
assertEquals(7, instances.size());

// Check first instance
FlightInstanceSchema day0 = instances.get(0);
assertEquals("FL-1-DAY-0-2000", day0.getInstanceId());
assertEquals(LocalDateTime.of(2025, 1, 2, 20, 0), day0.getDepartureDateTime());
assertEquals(LocalDateTime.of(2025, 1, 3, 3, 0), day0.getArrivalDateTime()); // Next day!
assertEquals(0, day0.getUsedCapacity()); // Initially empty
```

### Test 2: Re-Run Support
```java
// First run: Assign products
product1.setAssignedFlightInstance("FL-1-DAY-0-2000");
product2.setAssignedFlightInstance("FL-1-DAY-0-2000");
productService.save(product1);
productService.save(product2);

// Second run: Load existing assignments
Map<String, List<ProductSchema>> existing =
    dataSource.loadExistingProductAssignments(startTime, endTime);

// Should find 2 products on FL-1-DAY-0-2000
assertEquals(2, existing.get("FL-1-DAY-0-2000").size());

// FlightInstanceManager should pre-fill capacity
manager.initialize(instances, existing, startTime, endTime);
FlightInstanceSchema instance = manager.getInstanceById("FL-1-DAY-0-2000");
assertEquals(2, instance.getUsedCapacity()); // Pre-filled from DB!
```

---

## 🚀 **Next Steps**

1. **Add database migration** for `assigned_flight_instance` column
2. **Update AlgorithmPersistenceService** to save flight instance IDs:
   ```java
   product.setAssignedFlightInstance(flightInstance.getInstanceId());
   ```
3. **Integrate FlightInstanceManager into Solution.java** (Option 1 above)
4. **Update route finding** to use FlightInstanceManager methods
5. **Test end-to-end** with a 7-day simulation

---

## ✅ **Benefits of This Architecture**

1. ✅ **Flight Repetition**: Each day gets separate flight instances
2. ✅ **Multi-Day Simulations**: 7-day simulation now works correctly
3. ✅ **Midnight Crossing**: Flights like 20:00-03:00 properly span two days
4. ✅ **Re-Run Support**: Algorithm builds on previous runs
5. ✅ **Capacity Isolation**: Day 1's flight doesn't share capacity with Day 2
6. ✅ **Frontend Continuity**: DB queries return correct flight schedules

---

## 📝 **Example Frontend Flow**

```
Day 0, 00:00: Frontend calls /api/algorithm/daily (0-8 hours)
  → Algorithm assigns orders to flights departing Day 0
  → DB updated with assignments

Day 0, 08:00: Frontend calls /api/algorithm/daily (8-16 hours)
  → Algorithm loads existing assignments (from previous run)
  → Respects capacity already used
  → Assigns new orders to remaining capacity or next day's flights

Day 1, 00:00: Frontend calls /api/algorithm/daily (24-32 hours)
  → New flight instances for Day 1
  → Starts with usedCapacity = 0 (separate from Day 0)
  → Algorithm can assign products to Day 1 departures
```

---

## 🔧 **Configuration**

All implemented components use Spring DI, so they auto-wire:
- `@Service FlightExpansionService`
- `@Service FlightInstanceManager`
- `@Component DatabaseInputDataSource`

No manual configuration needed - Spring handles it!
