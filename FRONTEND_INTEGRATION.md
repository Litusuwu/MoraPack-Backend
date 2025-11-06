# MoraPack Backend - Frontend Integration Guide

**Version:** 1.0
**Date:** November 6, 2025
**Backend Branch:** `claude/update-algorithm-011CUquytT8psduDnVZhgtaL`

---

## Table of Contents
1. [Architecture Overview](#architecture-overview)
2. [Complete Workflow](#complete-workflow)
3. [API Endpoints Reference](#api-endpoints-reference)
4. [Data Models](#data-models)
5. [Algorithm Explanation](#algorithm-explanation)
6. [Important Notes](#important-notes)
7. [Example Frontend Flow](#example-frontend-flow)

---

## Architecture Overview

### System Design (Option A)

The backend uses a **separation of concerns** architecture:

```
┌─────────────────────────────────────────────────────────────┐
│                      FRONTEND                                │
└─────────────────────────────────────────────────────────────┘
                         │
        ┌────────────────┼────────────────┐
        │                │                │
        ▼                ▼                ▼
   [Load Data]    [Run Algorithm]   [Query Results]
        │                │                │
        ▼                ▼                ▼
┌─────────────────────────────────────────────────────────────┐
│                    BACKEND API                               │
│  POST /api/data/load-orders                                 │
│  POST /api/algorithm/daily                                  │
│  POST /api/algorithm/weekly                                 │
│  GET  /api/query/orders                                     │
│  GET  /api/query/flights                                    │
│  GET  /api/query/products                                   │
└─────────────────────────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────┐
│                   PostgreSQL DATABASE                        │
│  - Orders (loaded from files)                               │
│  - Products (created by algorithm)                          │
│  - Flights, Airports, Cities                                │
└─────────────────────────────────────────────────────────────┘
```

### Key Concepts

1. **Data Loading (Step 1):** Load order data from files into the database
2. **Algorithm Execution (Step 2):** Run optimization algorithm (reads from DB)
3. **Result Querying (Step 3):** Query optimized results from database

---

## Complete Workflow

### Three-Step Process

#### Step 1: Load Orders into Database

```http
POST /api/data/load-orders
Content-Type: application/json

Optional query parameters:
  ?startTime=2025-01-02T00:00:00
  &endTime=2025-01-02T01:00:00
```

**What it does:**
- Reads `_pedidos_{AIRPORT}_` files from backend data directory
- Parses order format: `id_pedido-aaaammdd-hh-mm-dest-###-IdClien`
- Inserts orders into PostgreSQL database
- Returns statistics

**Response:**
```json
{
  "success": true,
  "message": "Orders loaded successfully",
  "statistics": {
    "ordersLoaded": 10000,
    "ordersCreated": 10000,
    "ordersFiltered": 2000,
    "parseErrors": 0,
    "fileErrors": 0,
    "durationSeconds": 5
  },
  "startTime": "2025-11-06T12:00:00",
  "endTime": "2025-11-06T12:00:05"
}
```

#### Step 2: Run Algorithm

**For Daily Operations (30-minute incremental windows):**
```http
POST /api/algorithm/daily
Content-Type: application/json

{
  "simulationStartTime": "2025-01-02T00:00:00",
  "simulationDurationHours": 0.5,
  "useDatabase": true
}
```

**For Weekly Operations (7-day batch processing):**
```http
POST /api/algorithm/weekly
Content-Type: application/json

{
  "simulationStartTime": "2025-01-02T00:00:00",
  "simulationDurationDays": 7,
  "useDatabase": true
}
```

**What it does:**
- Loads orders from database (filtered by time window)
- Runs ALNS optimization algorithm
- Splits orders when needed (e.g., 45 products → 22 + 23)
- Assigns products to flights
- Batch persists product assignments to database
- Returns statistics (NO productRoutes - use query endpoints instead)

**Response:**
```json
{
  "success": true,
  "message": "ALNS algorithm executed successfully. Products persisted: 8500",
  "executionStartTime": "2025-11-06T12:05:00",
  "executionEndTime": "2025-11-06T12:05:30",
  "executionTimeSeconds": 30,
  "simulationStartTime": "2025-01-02T00:00:00",
  "simulationEndTime": "2025-01-02T00:30:00",
  "totalOrders": 1000,
  "assignedOrders": 950,
  "unassignedOrders": 50,
  "totalProducts": 8500,
  "assignedProducts": 8000,
  "unassignedProducts": 500,
  "productRoutes": null
}
```

**⚠️ IMPORTANT:** `productRoutes` is `null`. Use query endpoints to get results.

#### Step 3: Query Results

After algorithm completes, query the database for results:

```http
# Get all flights
GET /api/query/flights

# Get products on a specific flight
GET /api/query/flights/LIMA-BRUS-001/products

# Get orders on a specific flight
GET /api/query/flights/LIMA-BRUS-001/orders

# Get orders in time window
GET /api/query/orders?startTime=2025-01-02T00:00:00&endTime=2025-01-02T01:00:00

# Get products for a specific order
GET /api/query/products/12345
```

---

## API Endpoints Reference

### Data Loading Endpoints

#### `POST /api/data/load-orders`

Load order files into database.

**Query Parameters:**
- `dataDirectory` (optional, string): Custom data directory path
- `startTime` (optional, ISO datetime): Only load orders after this time
- `endTime` (optional, ISO datetime): Only load orders before this time

**Response:**
```json
{
  "success": true,
  "message": "Orders loaded successfully",
  "statistics": {
    "ordersLoaded": 10000,
    "ordersCreated": 10000,
    "ordersFiltered": 2000,
    "parseErrors": 0,
    "fileErrors": 0,
    "durationSeconds": 5
  }
}
```

#### `GET /api/data/status`

Get data loading status (placeholder).

---

### Algorithm Execution Endpoints

#### `POST /api/algorithm/daily`

Execute algorithm for daily scenario (incremental 30-minute windows).

**Request Body:**
```json
{
  "simulationStartTime": "2025-01-02T00:00:00",
  "simulationEndTime": "2025-01-02T00:30:00",
  "simulationDurationHours": 0.5,
  "useDatabase": true,
  "maxIterations": 1000,
  "destructionRate": 0.3
}
```

**Fields:**
- `simulationStartTime` (required, ISO datetime): Start of simulation window
- `simulationEndTime` (optional, ISO datetime): End of simulation window
- `simulationDurationHours` (optional, number): Duration in hours (alternative to endTime)
- `simulationDurationDays` (optional, number): Duration in days (alternative to endTime)
- `useDatabase` (optional, boolean): Use DATABASE mode (default: true)
- `maxIterations` (optional, number): Max ALNS iterations (default: 1000)
- `destructionRate` (optional, number): Destruction rate for ALNS (default: 0.3)

**Response:**
```json
{
  "success": true,
  "message": "ALNS algorithm executed successfully. Products persisted: 8500",
  "executionStartTime": "2025-11-06T12:05:00",
  "executionEndTime": "2025-11-06T12:05:30",
  "executionTimeSeconds": 30,
  "simulationStartTime": "2025-01-02T00:00:00",
  "simulationEndTime": "2025-01-02T00:30:00",
  "totalOrders": 1000,
  "assignedOrders": 950,
  "totalProducts": 8500,
  "assignedProducts": 8000,
  "productRoutes": null
}
```

#### `POST /api/algorithm/weekly`

Execute algorithm for weekly scenario (7-day batch processing).

**Request Body:**
```json
{
  "simulationStartTime": "2025-01-02T00:00:00",
  "simulationDurationDays": 7,
  "useDatabase": true
}
```

**Response:** Same format as daily endpoint.

**Expected execution time:** 30-90 minutes.

---

### Query Endpoints

#### Orders

**`GET /api/query/orders`**

Get all orders (optionally filtered by time window).

**Query Parameters:**
- `startTime` (optional, ISO datetime): Filter orders after this time
- `endTime` (optional, ISO datetime): Filter orders before this time

**Response:**
```json
{
  "success": true,
  "totalOrders": 1000,
  "orders": [
    {
      "id": 1,
      "name": "Order-000000001-EBCI",
      "status": "PENDING",
      "creationDate": "2025-01-02T00:38:00",
      "deliveryDate": "2025-01-05T00:38:00",
      "origin": { "id": 1, "name": "Lima" },
      "destination": { "id": 5, "name": "Brussels" }
    }
  ]
}
```

**`GET /api/query/orders/{orderId}`**

Get specific order with its products.

**Response:**
```json
{
  "success": true,
  "order": { /* order details */ },
  "productCount": 45,
  "products": [ /* product list */ ]
}
```

#### Products

**`GET /api/query/products`**

Get all products with status breakdown.

**Response:**
```json
{
  "success": true,
  "totalProducts": 8500,
  "products": [ /* product list */ ],
  "statusBreakdown": {
    "ASSIGNED": 8000,
    "PENDING": 500
  }
}
```

**`GET /api/query/products/{orderId}`**

Get products (splits) for a specific order.

**Response:**
```json
{
  "success": true,
  "orderId": 12345,
  "productCount": 3,
  "products": [
    {
      "id": 1001,
      "name": "Product-Split-12345-0",
      "status": "ASSIGNED",
      "assignedFlight": "LDZA-EBCI -> EBCI-SBBR",
      "order": { "id": 12345 }
    },
    {
      "id": 1002,
      "name": "Product-Split-12345-1",
      "status": "ASSIGNED",
      "assignedFlight": "LDZA-EBCI -> EBCI-LIRF",
      "order": { "id": 12345 }
    }
  ]
}
```

#### Flights

**`GET /api/query/flights`**

Get all flights with status breakdown.

**Response:**
```json
{
  "success": true,
  "totalFlights": 150,
  "flights": [
    {
      "id": 1,
      "code": "LIMA-BRUS-001",
      "status": "ACTIVE",
      "maxCapacity": 300,
      "routeType": "INTERCONTINENTAL",
      "originAirport": { "id": 1, "codeIATA": "LIMA" },
      "destinationAirport": { "id": 5, "codeIATA": "BRUS" }
    }
  ],
  "statusBreakdown": {
    "ACTIVE": 120,
    "CANCELLED": 30
  }
}
```

**`GET /api/query/flights/{flightCode}`**

Get flight details with capacity usage.

**Response:**
```json
{
  "success": true,
  "flight": { /* flight details */ },
  "productCount": 250,
  "orderCount": 45,
  "capacityUsed": 250,
  "capacityTotal": 300,
  "capacityAvailable": 50
}
```

**`GET /api/query/flights/{flightCode}/products`**

Get all products assigned to this flight.

**Response:**
```json
{
  "success": true,
  "flightCode": "LIMA-BRUS-001",
  "productCount": 250,
  "products": [ /* product list */ ],
  "statusBreakdown": {
    "ASSIGNED": 250
  }
}
```

**`GET /api/query/flights/{flightCode}/orders`**

Get all orders with products on this flight.

**Response:**
```json
{
  "success": true,
  "flightCode": "LIMA-BRUS-001",
  "orderCount": 45,
  "orders": [
    {
      "orderId": 1,
      "status": "IN_TRANSIT",
      "creationDate": "2025-01-02T00:38:00",
      "productsOnFlight": 6
    }
  ],
  "totalProductsOnFlight": 250
}
```

**`GET /api/query/flights/status`**

Get overall flight assignment statistics.

**Response:**
```json
{
  "success": true,
  "totalProducts": 8500,
  "assignedProducts": 8000,
  "unassignedProducts": 500,
  "assignmentRate": 94.12
}
```

---

## Data Models

### Order Data Format (Files)

Orders are stored in files with pattern: `_pedidos_{AIRPORT}_`

**Format:** `id_pedido-aaaammdd-hh-mm-dest-###-IdClien`

**Example:** `000000001-20250102-01-38-EBCI-006-0007729`

**Fields:**
- `id_pedido`: Order ID (000000001)
- `aaaammdd`: Date in format YYYYMMDD (20250102 = Jan 2, 2025)
- `hh`: Hour (01)
- `mm`: Minute (38)
- `dest`: Destination airport code (EBCI)
- `###`: Product quantity (006)
- `IdClien`: Customer ID (0007729)

### Order Entity (Database)

```json
{
  "id": 1,
  "name": "Order-000000001-EBCI",
  "origin": {
    "id": 1,
    "name": "Lima",
    "continent": "SOUTH_AMERICA"
  },
  "destination": {
    "id": 5,
    "name": "Brussels",
    "continent": "EUROPE"
  },
  "deliveryDate": "2025-01-05T00:38:00",
  "status": "PENDING",
  "pickupTimeHours": 2.0,
  "creationDate": "2025-01-02T00:38:00",
  "customer": {
    "id": 1,
    "name": "Customer-0007729"
  }
}
```

### Product Entity (Database)

Created by the algorithm when orders are split.

```json
{
  "id": 1001,
  "name": "Product-Split-12345-0",
  "status": "ASSIGNED",
  "assignedFlight": "LDZA-EBCI -> EBCI-SBBR",
  "order": {
    "id": 12345
  }
}
```

### Flight Entity

```json
{
  "id": 1,
  "code": "LIMA-BRUS-001",
  "status": "ACTIVE",
  "maxCapacity": 300,
  "transportTimeDays": 1.0,
  "dailyFrequency": 2,
  "routeType": "INTERCONTINENTAL",
  "originAirport": {
    "id": 1,
    "codeIATA": "LIMA",
    "city": { "name": "Lima" }
  },
  "destinationAirport": {
    "id": 5,
    "codeIATA": "BRUS",
    "city": { "name": "Brussels" }
  }
}
```

---

## Algorithm Explanation

### ALNS (Adaptive Large Neighborhood Search)

The backend uses an **ALNS metaheuristic algorithm** to optimize package routing.

#### What the Algorithm Does

1. **Loads orders** from database (filtered by simulation time window)
2. **Finds routes** for each order using available flights
3. **Assigns products to flights** respecting:
   - Flight capacity constraints
   - Warehouse capacity constraints
   - Delivery deadlines
   - Pickup windows
4. **Splits orders** when they don't fit (e.g., 45 products → 22 + 23)
5. **Optimizes** using destruction and repair operators
6. **Persists results** to database in batch

#### Key Features

**Order Splitting:**
- When an order doesn't fit in a flight due to capacity
- Split in half (e.g., 45 → 22 + 23)
- Each split tracked separately
- Persisted as individual products in database

**Unlimited Main Warehouse Capacity:**
- Lima, Brussels (Bruselas), and Baku have **unlimited capacity**
- This is a critical optimization
- Other warehouses have capacity limits

**Time Window Filtering:**
- Daily scenario: Load only orders in 30-minute window
- Weekly scenario: Load 7 days of orders
- Avoids loading 36MB of data at once

**Batch Persistence:**
- All products created in memory during algorithm
- Single database transaction at end
- Minimizes DB calls for performance

#### Algorithm Parameters

**Request parameters:**
```json
{
  "maxIterations": 1000,
  "destructionRate": 0.3
}
```

- `maxIterations`: How many ALNS iterations to run (higher = better solution, slower)
- `destructionRate`: Percentage of solution to destroy each iteration (0.0-1.0)

**Default values work well for most cases.**

#### Expected Performance

- **Daily scenario (30-minute window):** ~30 seconds
- **Weekly scenario (7 days):** 30-90 minutes

---

## Important Notes

### ⚠️ Critical Points

1. **productRoutes is NULL**
   - Algorithm response does NOT include `productRoutes`
   - Frontend MUST use query endpoints to get results
   - This reduces API payload size significantly

2. **Two-Step Process**
   - Step 1: Load orders (`POST /api/data/load-orders`)
   - Step 2: Run algorithm (`POST /api/algorithm/daily`)
   - Don't skip step 1!

3. **Database Mode**
   - Algorithm reads from DATABASE by default
   - Make sure orders are loaded before running algorithm

4. **Time Windows**
   - Use ISO 8601 format: `2025-01-02T00:00:00`
   - Always specify `simulationStartTime`
   - Either specify `simulationEndTime` OR `simulationDurationHours`/`simulationDurationDays`

5. **Order Splitting**
   - Orders may be split into multiple products
   - Use `GET /api/query/products/{orderId}` to see all splits
   - Each split has its own flight assignment

### 🔄 Data Flow

```
Files → Database → Algorithm → Database → Frontend
  ↓         ↓          ↓           ↓          ↓
_pedidos  Orders    ALNS      Products    Query
  files   table   optimize   table     endpoints
```

### 📊 Status Values

**Order Status:**
- `PENDING` - Waiting to be assigned
- `IN_TRANSIT` - Assigned to flights
- `DELIVERED` - Reached destination
- `CANCELLED` - Cancelled

**Product Status:**
- `PENDING` - Not assigned
- `ASSIGNED` - Assigned to flight(s)
- `IN_TRANSIT` - Currently on flight
- `DELIVERED` - Delivered to customer

**Flight Status:**
- `ACTIVE` - Available for assignments
- `CANCELLED` - Not available
- `DELAYED` - Delayed (3 hours standard)

---

## Example Frontend Flow

### Complete Integration Example

```javascript
// ========================================
// STEP 1: Load Orders
// ========================================
async function loadOrders() {
  const response = await fetch('http://localhost:8080/api/data/load-orders', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' }
  });

  const result = await response.json();
  console.log(`Loaded ${result.statistics.ordersCreated} orders`);
  // Output: "Loaded 10000 orders"
}

// ========================================
// STEP 2: Run Algorithm (Daily Scenario)
// ========================================
async function runDailyAlgorithm() {
  const response = await fetch('http://localhost:8080/api/algorithm/daily', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      simulationStartTime: '2025-01-02T00:00:00',
      simulationDurationHours: 0.5,  // 30 minutes
      useDatabase: true
    })
  });

  const result = await response.json();
  console.log(`Algorithm completed in ${result.executionTimeSeconds}s`);
  console.log(`Assigned ${result.assignedProducts} products`);
  // Output: "Algorithm completed in 30s"
  // Output: "Assigned 8000 products"
}

// ========================================
// STEP 3: Query Results
// ========================================
async function displayFlights() {
  // Get all flights
  const flightsResponse = await fetch('http://localhost:8080/api/query/flights');
  const flights = await flightsResponse.json();

  console.log(`Total flights: ${flights.totalFlights}`);

  // For each flight, get products
  for (const flight of flights.flights) {
    const productsResponse = await fetch(
      `http://localhost:8080/api/query/flights/${flight.code}/products`
    );
    const products = await productsResponse.json();

    console.log(`Flight ${flight.code}: ${products.productCount} products`);
    // Output: "Flight LIMA-BRUS-001: 250 products"
  }
}

async function displayOrders() {
  // Get orders in time window
  const response = await fetch(
    'http://localhost:8080/api/query/orders?' +
    'startTime=2025-01-02T00:00:00&endTime=2025-01-02T01:00:00'
  );

  const result = await response.json();
  console.log(`Orders in window: ${result.totalOrders}`);

  // For each order, get products
  for (const order of result.orders) {
    const productsResponse = await fetch(
      `http://localhost:8080/api/query/products/${order.id}`
    );
    const products = await productsResponse.json();

    console.log(`Order ${order.id}: ${products.productCount} products/splits`);
  }
}

// ========================================
// COMPLETE WORKFLOW
// ========================================
async function completeWorkflow() {
  // Step 1: Load data
  await loadOrders();

  // Step 2: Run algorithm
  await runDailyAlgorithm();

  // Step 3: Display results
  await displayFlights();
  await displayOrders();
}
```

### Real-Time Monitoring Example

```javascript
// Poll for algorithm completion
async function runAlgorithmWithProgress() {
  // Start algorithm
  const startResponse = await fetch('http://localhost:8080/api/algorithm/weekly', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      simulationStartTime: '2025-01-02T00:00:00',
      simulationDurationDays: 7
    })
  });

  // This will take 30-90 minutes...
  console.log('Algorithm started...');

  // Poll for status
  const checkInterval = setInterval(async () => {
    const statusResponse = await fetch('http://localhost:8080/api/query/flights/status');
    const status = await statusResponse.json();

    console.log(`Progress: ${status.assignedProducts} products assigned`);

    // Check if complete (implementation-specific)
    if (status.assignedProducts > 0) {
      clearInterval(checkInterval);
      console.log('Algorithm complete!');
    }
  }, 10000); // Check every 10 seconds
}
```

### Error Handling Example

```javascript
async function safeAlgorithmExecution() {
  try {
    // Step 1: Load orders
    const loadResponse = await fetch('http://localhost:8080/api/data/load-orders', {
      method: 'POST'
    });

    if (!loadResponse.ok) {
      throw new Error('Failed to load orders');
    }

    const loadResult = await loadResponse.json();

    if (!loadResult.success) {
      throw new Error(loadResult.message || 'Load failed');
    }

    console.log(`✅ Loaded ${loadResult.statistics.ordersCreated} orders`);

    // Step 2: Run algorithm
    const algoResponse = await fetch('http://localhost:8080/api/algorithm/daily', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        simulationStartTime: '2025-01-02T00:00:00',
        simulationDurationHours: 0.5
      })
    });

    if (!algoResponse.ok) {
      throw new Error('Algorithm execution failed');
    }

    const algoResult = await algoResponse.json();

    if (!algoResult.success) {
      throw new Error(algoResult.message || 'Algorithm failed');
    }

    console.log(`✅ Algorithm completed: ${algoResult.assignedProducts} products assigned`);

    return algoResult;

  } catch (error) {
    console.error('❌ Error:', error.message);
    // Show user-friendly error message
    alert(`Operation failed: ${error.message}`);
    return null;
  }
}
```

---

## Quick Reference

### Typical Daily Scenario Workflow

```bash
# 1. Load orders for today
POST /api/data/load-orders?startTime=2025-01-02T00:00:00&endTime=2025-01-03T00:00:00

# 2. Run algorithm for first 30-minute window
POST /api/algorithm/daily
{ "simulationStartTime": "2025-01-02T00:00:00", "simulationDurationHours": 0.5 }

# 3. Query results
GET /api/query/flights
GET /api/query/flights/{code}/products
GET /api/query/orders?startTime=2025-01-02T00:00:00&endTime=2025-01-02T00:30:00

# 4. Run algorithm for next 30-minute window
POST /api/algorithm/daily
{ "simulationStartTime": "2025-01-02T00:30:00", "simulationDurationHours": 0.5 }

# ... repeat every 30 minutes
```

### Typical Weekly Scenario Workflow

```bash
# 1. Load orders for the week
POST /api/data/load-orders?startTime=2025-01-02T00:00:00&endTime=2025-01-09T00:00:00

# 2. Run algorithm for entire week (takes 30-90 minutes)
POST /api/algorithm/weekly
{ "simulationStartTime": "2025-01-02T00:00:00", "simulationDurationDays": 7 }

# 3. Query results
GET /api/query/flights
GET /api/query/orders
```

---

## Support

**Backend Repository:** [MoraPack-Backend](https://github.com/Litusuwu/MoraPack-Backend)
**Branch:** `claude/update-algorithm-011CUquytT8psduDnVZhgtaL`

For questions or issues, refer to:
- `backend/CLAUDE.md` - Technical implementation details
- This file - Frontend integration guide

---

**End of Frontend Integration Guide**
