# Weekly Algorithm Parameters Explained

## Parameter Structure

When calling `/api/algorithm/weekly`, you send these parameters:

```json
{
  "simulationStartTime": "2025-01-02T00:00:00",
  "simulationEndTime": "2025-01-03T00:00:00",
  "simulationDurationDays": 0,
  "simulationDurationHours": 0,
  "useDatabase": true,
  "maxIterations": 0,
  "destructionRate": 0
}
```

---

## 📅 Time Window Parameters

### 1. `simulationStartTime` (REQUIRED)

**Purpose:** Defines when the simulation begins.

**Format:** ISO 8601 date-time string `yyyy-MM-ddTHH:mm:ss`

**What it does:**
- Filters orders: Only loads orders with `creation_date >= simulationStartTime`
- Defines the starting point for the 7-day planning horizon
- All flights and deliveries are scheduled relative to this time

**Example:**
```json
"simulationStartTime": "2025-01-02T00:00:00"
```
This starts the simulation at January 2, 2025 at midnight.

---

### 2. `simulationEndTime` (OPTIONAL)

**Purpose:** Explicitly defines when the simulation ends.

**Default:** If not provided, calculated from `simulationStartTime + simulationDurationDays`

**What it does:**
- Filters orders: Only loads orders with `creation_date <= simulationEndTime`
- Defines the cutoff point for order loading
- Used to limit the planning horizon

**Example:**
```json
"simulationEndTime": "2025-01-09T23:59:59"
```
This ends the simulation at January 9, 2025 at 11:59:59 PM.

**When to use:**
- ✅ Use if you want precise control over the end time
- ❌ Leave as null/0 if you prefer to use `simulationDurationDays`

---

### 3. `simulationDurationDays` (RECOMMENDED for Weekly)

**Purpose:** Defines simulation length in days.

**Default:** For `/api/algorithm/weekly`, automatically set to **7 days** if not provided

**What it does:**
- Calculates `simulationEndTime = simulationStartTime + durationDays`
- For weekly scenario: Processes 7 consecutive days of orders
- Determines the planning horizon

**Example:**
```json
"simulationDurationDays": 7
```
This processes 7 days worth of orders starting from `simulationStartTime`.

**Typical values:**
- **7** (1 week) - Standard weekly scenario
- **1** (1 day) - Daily scenario
- **30** (1 month) - Monthly batch

---

### 4. `simulationDurationHours` (OPTIONAL)

**Purpose:** Alternative to `simulationDurationDays` for sub-day simulations.

**Format:** Decimal hours (e.g., 0.5 = 30 minutes, 1.0 = 1 hour)

**What it does:**
- Calculates `simulationEndTime = simulationStartTime + durationHours`
- Useful for incremental/real-time scenarios
- Primarily used for `/api/algorithm/daily` endpoint

**Example:**
```json
"simulationDurationHours": 0.5
```
This processes 30 minutes worth of orders.

**When to use:**
- ✅ Daily scenario with frequent updates (every 30 min)
- ❌ Weekly scenario (use `simulationDurationDays` instead)

---

## 🗄️ Data Source Parameter

### 5. `useDatabase` (REQUIRED)

**Purpose:** Determines where the algorithm loads data from.

**Type:** Boolean

**Options:**
- `true` → Load data from PostgreSQL database (orders, flights, airports)
- `false` → Load data from text files in `data/` directory

**What it does:**
```
useDatabase = true:
├─ Loads orders from `orders` table
├─ Loads flights from `flights` table
├─ Loads airports from `airports` table
└─ Uses DataSourceAdapter to convert DB models to algorithm schemas

useDatabase = false:
├─ Reads _pedidos_*.txt files from data/products/
├─ Reads flights.txt from data/
├─ Reads airportInfo.txt from data/
└─ Parses text format into algorithm schemas
```

**Example:**
```json
"useDatabase": true
```

**Recommendation:**
- ✅ `true` - If you've loaded orders via `/api/data/load-orders`
- ✅ `false` - For testing with sample data files
- ✅ `true` - Production use after proper data loading

---

## ⚙️ Algorithm Configuration Parameters

### 6. `maxIterations` (OPTIONAL)

**Purpose:** Maximum number of ALNS iterations to run.

**Type:** Integer

**Default:** Uses `Constants.UPPERBOUND_SOLUTION_SPACE` (200) if not provided

**What it does:**
- Controls how long the algorithm searches for better solutions
- More iterations = potentially better solution, but longer execution time
- Algorithm stops when reaching this limit OR when no improvement found

**Example:**
```json
"maxIterations": 500
```

**Typical values:**
- **0 or null** → Use default (200)
- **100-200** → Quick optimization (~5-10 minutes)
- **500-1000** → Deep optimization (~30-90 minutes)
- **1000+** → Very thorough search (hours)

**Performance guide:**
```
maxIterations    Execution Time    Use Case
─────────────────────────────────────────────────
100-200          5-15 minutes      Quick testing
500-1000         30-90 minutes     Production weekly scenario
1000-2000        1-3 hours         Deep optimization
```

---

### 7. `destructionRate` (OPTIONAL)

**Purpose:** Percentage of solution to destroy in each ALNS iteration.

**Type:** Decimal (0.0 to 1.0)

**Default:** Uses `Constants.DESTRUCTION_RATIO` (0.15 = 15%) if not provided

**What it does:**
- In each ALNS iteration, this % of assigned packages are removed from routes
- Higher rate = more aggressive exploration, but potentially unstable
- Lower rate = more stable, but might miss optimal solutions
- Removed packages are then re-inserted using different strategies

**Example:**
```json
"destructionRate": 0.15
```

**Typical values:**
- **0 or null** → Use default (0.15 = 15%)
- **0.10** (10%) → Conservative, stable optimization
- **0.15** (15%) → Balanced (recommended)
- **0.20** (20%) → Aggressive exploration
- **0.30-0.80** → Extreme diversification (used for restarts)

**How it works:**
```
Example with 1000 assigned packages:

destructionRate = 0.15 (15%)
├─ Destroy: Remove 150 packages from current routes
├─ Reconstruct: Re-insert 150 packages using different strategy
└─ Evaluate: Check if new solution is better
```

**Recommendation:**
- ✅ Leave as 0 (use default) for standard optimization
- ✅ Use 0.10-0.20 for fine-tuning
- ❌ Avoid > 0.30 unless testing extreme scenarios

---

## 🎯 Complete Example Requests

### Example 1: Standard Weekly Scenario (Recommended)

```bash
curl -X POST http://localhost:8080/api/algorithm/weekly \
  -H "Content-Type: application/json" \
  -d '{
    "simulationStartTime": "2025-01-02T00:00:00",
    "simulationDurationDays": 7,
    "useDatabase": true
  }'
```

**What happens:**
- Loads orders from Jan 2-9, 2025 (7 days) from database
- Runs ALNS with default parameters:
  - maxIterations: 200
  - destructionRate: 0.15
- Returns optimal routes for the week
- Execution time: ~30-90 minutes

---

### Example 2: Custom Algorithm Parameters

```bash
curl -X POST http://localhost:8080/api/algorithm/weekly \
  -H "Content-Type: application/json" \
  -d '{
    "simulationStartTime": "2025-01-02T00:00:00",
    "simulationDurationDays": 7,
    "useDatabase": true,
    "maxIterations": 1000,
    "destructionRate": 0.20
  }'
```

**What happens:**
- Same time window as Example 1
- More iterations (1000 vs 200) → Better solution, longer time
- Higher destruction rate (20% vs 15%) → More exploration
- Execution time: ~90-180 minutes

---

### Example 3: Quick Test with File Data

```bash
curl -X POST http://localhost:8080/api/algorithm/weekly \
  -H "Content-Type: application/json" \
  -d '{
    "simulationStartTime": "2025-01-02T00:00:00",
    "simulationDurationDays": 7,
    "useDatabase": false,
    "maxIterations": 100
  }'
```

**What happens:**
- Reads from text files instead of database
- Quick run with only 100 iterations
- Good for testing algorithm changes
- Execution time: ~5-10 minutes

---

### Example 4: Precise Time Control

```bash
curl -X POST http://localhost:8080/api/algorithm/weekly \
  -H "Content-Type: application/json" \
  -d '{
    "simulationStartTime": "2025-01-02T00:00:00",
    "simulationEndTime": "2025-01-05T23:59:59",
    "useDatabase": true
  }'
```

**What happens:**
- Loads orders from Jan 2-5 (4 days only)
- Ignores `simulationDurationDays` (overridden by explicit end time)
- Uses default ALNS parameters

---

## 📊 How Parameters Interact

```
Flow Diagram:

1. TIME WINDOW CALCULATION
   ┌─────────────────────────────────┐
   │ simulationStartTime (required)  │
   └──────────────┬──────────────────┘
                  ↓
   ┌─────────────────────────────────┐
   │ Has simulationEndTime?          │
   └──────────┬──────────────────────┘
              ├─ YES → Use explicit end time
              └─ NO  → Calculate from duration
                       ↓
                ┌─────────────────────────┐
                │ simulationDurationDays? │
                └────────┬────────────────┘
                         ├─ Set → Add days to start
                         └─ Not set → Default to 7 (weekly)

2. DATA LOADING
   ┌─────────────────────────────────┐
   │ useDatabase?                    │
   └──────────┬──────────────────────┘
              ├─ true  → Load from PostgreSQL
              └─ false → Load from text files

3. ALGORITHM EXECUTION
   ┌─────────────────────────────────┐
   │ maxIterations?                  │
   └──────────┬──────────────────────┘
              ├─ Set (> 0) → Use custom value
              └─ Not set   → Use default (200)
   ┌─────────────────────────────────┐
   │ destructionRate?                │
   └──────────┬──────────────────────┘
              ├─ Set (> 0) → Use custom value
              └─ Not set   → Use default (0.15)
```

---

## 🎓 Best Practices

### For Production Weekly Scenario:
```json
{
  "simulationStartTime": "2025-01-02T00:00:00",
  "simulationDurationDays": 7,
  "useDatabase": true,
  "maxIterations": 500,
  "destructionRate": 0.15
}
```

### For Quick Testing:
```json
{
  "simulationStartTime": "2025-01-02T00:00:00",
  "simulationDurationDays": 1,
  "useDatabase": false,
  "maxIterations": 100
}
```

### For Deep Optimization:
```json
{
  "simulationStartTime": "2025-01-02T00:00:00",
  "simulationDurationDays": 7,
  "useDatabase": true,
  "maxIterations": 1000,
  "destructionRate": 0.20
}
```

---

## ❓ Common Questions

**Q: Why are all my values 0 in the example?**
A: Zeros or nulls mean "use defaults". The algorithm will:
- Set `simulationDurationDays = 7` automatically for weekly
- Use `maxIterations = 200` from Constants
- Use `destructionRate = 0.15` from Constants

**Q: Should I always provide all parameters?**
A: No! Only required parameter is `simulationStartTime`. Others have sensible defaults.

**Q: What's the difference between weekly and daily endpoints?**
A:
- `/api/algorithm/weekly` → Processes 7 days at once (batch)
- `/api/algorithm/daily` → Processes small time windows incrementally (real-time)

**Q: How do I load 1 week of orders?**
A: Use the same dates for both `/api/data/load-orders` AND `/api/algorithm/weekly`:
```bash
# Step 1: Load orders into database
curl -X POST "http://localhost:8080/api/data/load-orders?startTime=2025-01-02T00:00:00&endTime=2025-01-09T23:59:59"

# Step 2: Run algorithm on those orders
curl -X POST http://localhost:8080/api/algorithm/weekly \
  -H "Content-Type: application/json" \
  -d '{
    "simulationStartTime": "2025-01-02T00:00:00",
    "simulationDurationDays": 7,
    "useDatabase": true
  }'
```

---

Generated: 2025-11-06
For: MoraPack Backend - Algorithm Weekly Parameters
