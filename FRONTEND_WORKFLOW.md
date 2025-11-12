# MoraPack Frontend Integration Workflow

**Version:** 2.0
**Date:** November 12, 2025
**Backend Branch:** `claude/optimize-algorithm-011CUs6svQpHeMAbfx13ivJz`

---

## Table of Contents

1. [Overview](#overview)
2. [Complete Simulation Flow](#complete-simulation-flow)
3. [API Endpoints Reference](#api-endpoints-reference)
4. [Workflow Diagrams](#workflow-diagrams)
5. [State Management](#state-management)
6. [Error Handling](#error-handling)
7. [Testing Guide](#testing-guide)

---

## Overview

MoraPack backend provides a **continuous incremental scheduling system** where:
- The algorithm runs in **time windows** (e.g., every 30 simulation minutes)
- Each run **respects existing assignments** from previous runs
- Frontend **controls simulation time** and triggers algorithm runs
- Frontend **queries database directly** for current state (no large JSON responses)

### Key Concepts

| Concept | Description |
|---------|-------------|
| **Simulation Time** | Virtual time that progresses faster than real time (e.g., 1 real second = 1 simulation minute) |
| **Time Window** | Period for which algorithm schedules orders (e.g., 2pm-2am = 12 hours) |
| **Incremental Scheduling** | Algorithm only schedules NEW orders, respecting existing flight assignments |
| **Flight Instance** | Specific flight departure (e.g., "FL-45 departing Day 0 at 20:00") |
| **Product Split** | When an order is divided across multiple flights |

---

## Complete Simulation Flow

### Phase 1: Initialization

```javascript
// 1. Initialize simulation clock
const simulationState = {
  startTime: new Date("2025-01-02T00:00:00"),
  currentTime: new Date("2025-01-02T00:00:00"),
  endTime: new Date("2025-01-09T00:00:00"), // 7 days later
  timeScale: 60, // 1 real second = 60 simulation seconds
  isRunning: false,
  scheduledOrders: new Set(),
  activeFlights: new Map(),
};

// 2. Load initial data from backend
async function initializeSimulation() {
  // Load airports and flights (static data)
  const airports = await fetch('/api/data-import/airports').then(r => r.json());
  const flights = await fetch('/api/data-import/flights').then(r => r.json());

  // Load orders for first time window (e.g., first 12 hours)
  const ordersResponse = await fetch('/api/data/load-orders', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      startDate: "2025-01-02T00:00:00",
      endDate: "2025-01-02T12:00:00"
    })
  });

  console.log('Simulation initialized:', ordersResponse);
}
```

---

### Phase 2: Algorithm Execution (Daily Scenario)

```javascript
// Run algorithm for current time window
async function runAlgorithmForTimeWindow(startTime, durationHours) {
  console.log(`Running algorithm: ${startTime} for ${durationHours}h`);

  const response = await fetch('/api/algorithm/daily', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      simulationStartTime: startTime.toISOString(),
      simulationDurationHours: durationHours,
      useDatabase: true // IMPORTANT: Use DB for persistence
    })
  });

  const result = await response.json();

  /*
  Example response:
  {
    "success": true,
    "message": "ALNS algorithm executed successfully. Use /api/query endpoints to retrieve results.",
    "executionStartTime": "2025-01-02T14:00:00",
    "executionEndTime": "2025-01-02T14:00:32",
    "executionTimeSeconds": 32,
    "simulationStartTime": "2025-01-02T00:00:00",
    "simulationEndTime": "2025-01-02T12:00:00",
    "totalOrders": 1250,
    "assignedOrders": 1200,
    "unassignedOrders": 50,
    "totalProducts": 15000,
    "assignedProducts": 14500,
    "unassignedProducts": 500,
    "score": 0.0,
    "productRoutes": null  // NULL - query DB directly
  }
  */

  // Track which orders were scheduled
  for (let i = 0; i < result.assignedOrders; i++) {
    simulationState.scheduledOrders.add(/* order ID */);
  }

  return result;
}

// Initial run: Schedule first 12 hours
await runAlgorithmForTimeWindow(
  new Date("2025-01-02T00:00:00"),
  12 // 12 hours
);
```

---

### Phase 3: Simulation Time Progression

```javascript
// Start simulation clock (runs continuously)
function startSimulation() {
  simulationState.isRunning = true;

  const tickInterval = setInterval(() => {
    if (!simulationState.isRunning) {
      clearInterval(tickInterval);
      return;
    }

    // Advance simulation time (1 real second = 60 simulation seconds)
    simulationState.currentTime = new Date(
      simulationState.currentTime.getTime() + 60000
    );

    // Update UI clock display
    updateSimulationClock(simulationState.currentTime);

    // Check for time-based triggers
    checkSimulationTriggers();

  }, 1000); // Tick every real second
}

function checkSimulationTriggers() {
  const currentTime = simulationState.currentTime;

  // Trigger 1: Run algorithm every 12 simulation hours
  const hoursSinceLastRun = getHoursSince(lastAlgorithmRun);
  if (hoursSinceLastRun >= 12) {
    runAlgorithmForTimeWindow(currentTime, 12);
  }

  // Trigger 2: Update product states every 30 simulation minutes
  const minutesSinceLastUpdate = getMinutesSince(lastStateUpdate);
  if (minutesSinceLastUpdate >= 30) {
    updateProductStates(currentTime);
  }

  // Trigger 3: Refresh flight display every 5 simulation minutes
  const minutesSinceLastRefresh = getMinutesSince(lastFlightRefresh);
  if (minutesSinceLastRefresh >= 5) {
    refreshFlightDisplay();
  }
}
```

---

### Phase 4: Product State Updates

```javascript
// Update product states based on current simulation time
async function updateProductStates(currentTime) {
  const response = await fetch('/api/simulation/update-states', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      currentTime: currentTime.toISOString()
    })
  });

  const result = await response.json();

  /*
  Example response:
  {
    "success": true,
    "currentSimulationTime": "2025-01-02T08:00:00",
    "transitions": {
      "pendingToInTransit": 0,
      "inTransitToArrived": 450,
      "arrivedToDelivered": 120,
      "total": 570
    }
  }
  */

  console.log(`State transitions: ${result.transitions.total}`);

  // Update UI to reflect new states
  if (result.transitions.inTransitToArrived > 0) {
    showNotification(`${result.transitions.inTransitToArrived} products arrived!`);
    refreshFlightDisplay(); // Update map
  }

  return result;
}

// Alternative: Advance time by specific duration
async function advanceSimulationTime(hours) {
  const response = await fetch('/api/simulation/advance-time', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      currentTime: simulationState.currentTime.toISOString(),
      hoursToAdvance: hours
    })
  });

  const result = await response.json();
  simulationState.currentTime = new Date(result.currentSimulationTime);

  return result;
}
```

---

### Phase 5: Querying Current State

#### 5.1 Get Orders in Time Window

```javascript
// Query orders scheduled in specific time window
async function getOrdersInTimeWindow(startTime, endTime) {
  const params = new URLSearchParams({
    startTime: startTime.toISOString(),
    endTime: endTime.toISOString()
  });

  const response = await fetch(`/api/query/orders?${params}`);
  const result = await response.json();

  /*
  Example response:
  {
    "success": true,
    "totalOrders": 1250,
    "orders": [
      {
        "id": 12345,
        "name": "000000001-20250102-01-38-EBCI-006-0007729",
        "status": "IN_TRANSIT",
        "creationDate": "2025-01-02T01:38:00",
        "deliveryDate": "2025-01-04T01:38:00",
        "origin": { "id": 1, "name": "Lima" },
        "destination": { "id": 15, "name": "Brussels" },
        "customer": { "id": 7729, "name": "Customer-7729" }
      },
      // ... more orders
    ],
    "timeWindow": {
      "startTime": "2025-01-02T00:00:00",
      "endTime": "2025-01-02T12:00:00"
    }
  }
  */

  return result.orders;
}

// Example: Get orders for current 12-hour window
const currentOrders = await getOrdersInTimeWindow(
  new Date("2025-01-02T00:00:00"),
  new Date("2025-01-02T12:00:00")
);
```

#### 5.2 Get Product Splits for an Order

```javascript
// Query how an order was split across flights
async function getProductSplitsForOrder(orderId) {
  const response = await fetch(`/api/query/products/${orderId}`);
  const result = await response.json();

  /*
  Example response:
  {
    "success": true,
    "orderId": 12345,
    "orderName": "000000001-20250102-01-38-EBCI-006-0007729",
    "totalProducts": 6,
    "products": [
      {
        "id": 50001,
        "status": "IN_TRANSIT",
        "assignedFlightInstance": "LIM-CUZ-DAY-0-2000",
        "order": { "id": 12345, "name": "000000001..." }
      },
      {
        "id": 50002,
        "status": "IN_TRANSIT",
        "assignedFlightInstance": "LIM-CUZ-DAY-0-2000",
        "order": { "id": 12345, "name": "000000001..." }
      },
      // ... 4 more products
    ],
    "splits": {
      "LIM-CUZ-DAY-0-2000": 3,
      "CUZ-EBCI-DAY-1-0800": 3
    }
  }
  */

  return result.products;
}

// Example: Show order details with splits
async function displayOrderDetails(orderId) {
  const products = await getProductSplitsForOrder(orderId);

  console.log(`Order ${orderId} split across ${Object.keys(products.splits).length} flights:`);
  for (const [flightInstance, count] of Object.entries(products.splits)) {
    console.log(`  - ${flightInstance}: ${count} products`);
  }
}
```

#### 5.3 Get All Flight Statuses (for Map Display)

```javascript
// Get current status of all flights
async function getAllFlightStatuses() {
  const response = await fetch('/api/query/flights/status');
  const result = await response.json();

  /*
  Example response:
  {
    "success": true,
    "totalFlights": 120,
    "flights": [
      {
        "id": 45,
        "code": "LIM-CUZ",
        "originAirport": { "codeIATA": "SPIM", "city": { "name": "Lima" } },
        "destinationAirport": { "codeIATA": "SPZO", "city": { "name": "Cusco" } },
        "maxCapacity": 300,
        "usedCapacity": 245,
        "availableCapacity": 55,
        "transportTimeDays": 0.5,
        "dailyFrequency": 2,
        "utilizationPercentage": 81.67,
        "assignedProducts": 245,
        "assignedOrders": 82
      },
      // ... more flights
    ],
    "statistics": {
      "totalCapacity": 36000,
      "totalUsedCapacity": 18500,
      "averageUtilization": 51.39
    }
  }
  */

  return result.flights;
}

// Display flights on map
async function refreshFlightDisplay() {
  const flights = await getAllFlightStatuses();

  // Clear existing flight markers
  clearFlightMarkers();

  // Add flight markers to map
  flights.forEach(flight => {
    const marker = createFlightMarker({
      origin: [flight.originAirport.latitude, flight.originAirport.longitude],
      destination: [flight.destinationAirport.latitude, flight.destinationAirport.longitude],
      label: flight.code,
      utilization: flight.utilizationPercentage,
      products: flight.assignedProducts
    });

    // Color code by utilization
    if (flight.utilizationPercentage > 90) {
      marker.setColor('red'); // Nearly full
    } else if (flight.utilizationPercentage > 70) {
      marker.setColor('yellow'); // Moderately full
    } else {
      marker.setColor('green'); // Available capacity
    }

    addMarkerToMap(marker);
  });
}
```

#### 5.4 Get Orders Per Flight (Key Query!)

```javascript
// Query which orders are assigned to a specific flight
async function getOrdersPerFlight(flightCode) {
  const response = await fetch(`/api/query/flights/${flightCode}/orders`);
  const result = await response.json();

  /*
  Example response:
  {
    "success": true,
    "flightCode": "LIM-CUZ",
    "totalOrders": 82,
    "orders": [
      {
        "id": 12345,
        "name": "000000001-20250102-01-38-EBCI-006-0007729",
        "status": "IN_TRANSIT",
        "productsOnFlight": 3,
        "totalProducts": 6,
        "origin": { "name": "Lima" },
        "destination": { "name": "Brussels" }
      },
      // ... more orders
    ],
    "flight": {
      "code": "LIM-CUZ",
      "usedCapacity": 245,
      "maxCapacity": 300
    }
  }
  */

  return result.orders;
}

// Display flight details when user clicks on flight marker
async function onFlightMarkerClick(flightCode) {
  // Show loading indicator
  showLoadingModal();

  // Get orders for this flight
  const orders = await getOrdersPerFlight(flightCode);

  // Get products for this flight (more detailed)
  const productsResponse = await fetch(`/api/query/flights/${flightCode}/products`);
  const productsData = await productsResponse.json();

  // Display in modal/panel
  displayFlightDetailsModal({
    flightCode: flightCode,
    orders: orders,
    products: productsData.products,
    capacity: {
      used: productsData.products.length,
      max: productsData.flight.maxCapacity
    }
  });
}

// Example: Show order list for a flight
function displayFlightDetailsModal(data) {
  const modal = createModal({
    title: `Flight ${data.flightCode}`,
    content: `
      <div class="flight-details">
        <h3>Capacity: ${data.capacity.used} / ${data.capacity.max}</h3>
        <h4>Orders on this flight (${data.orders.length}):</h4>
        <ul>
          ${data.orders.map(order => `
            <li>
              <strong>${order.name}</strong>
              - ${order.productsOnFlight}/${order.totalProducts} products
              - Status: ${order.status}
              - Destination: ${order.destination.name}
            </li>
          `).join('')}
        </ul>
      </div>
    `
  });

  modal.show();
}
```

---

### Phase 6: Incremental Scheduling (Critical!)

```javascript
// IMPORTANT: How incremental scheduling works

// First run at 2pm: Schedule orders from 2pm-2am (12 hours)
const run1 = await runAlgorithmForTimeWindow(
  new Date("2025-01-02T14:00:00"), // 2pm
  12 // 12 hours
);
console.log(`Run 1: Assigned ${run1.assignedProducts} products`);
// Result: 1500 products assigned to flights

// Simulation progresses: 2pm → 2am (12 simulation hours pass)
await advanceSimulationTime(12);

// Second run at 2am: Schedule orders from 2am-2pm (next 12 hours)
const run2 = await runAlgorithmForTimeWindow(
  new Date("2025-01-02T02:00:00"), // 2am (next day)
  12 // 12 hours
);
console.log(`Run 2: Assigned ${run2.assignedProducts} products`);
// Result: 200 NEW products assigned (not 1500 again!)

/*
Why run2 only assigned 200 products:
1. Backend loaded existing DB state (1500 products already assigned)
2. Backend initialized flight capacities from DB (flights partially full)
3. Backend initialized warehouse occupancy from DB (300 products in warehouses)
4. Backend only scheduled NEW orders that arrived between 2am-2pm
5. Backend respected existing flight capacities

Console output from backend:
=== LOADING EXISTING DB STATE FOR INCREMENTAL SCHEDULING ===
[DATABASE] Loading existing product assignments for re-run support...
Initialized flight capacities: 45 flights, 1500 products loaded from DB
Initialized warehouse occupancy: 300 products in warehouses
DB state loaded successfully - algorithm will skip already-assigned products
=============================================================
*/
```

---

## API Endpoints Reference

### Algorithm Execution

#### POST `/api/algorithm/daily`
Execute algorithm for daily scenario (incremental scheduling)

**Request:**
```json
{
  "simulationStartTime": "2025-01-02T14:00:00",
  "simulationDurationHours": 12,
  "useDatabase": true
}
```

**Response:**
```json
{
  "success": true,
  "message": "ALNS algorithm executed successfully",
  "executionTimeSeconds": 32,
  "simulationStartTime": "2025-01-02T14:00:00",
  "simulationEndTime": "2025-01-03T02:00:00",
  "totalOrders": 1250,
  "assignedOrders": 1200,
  "unassignedOrders": 50,
  "totalProducts": 15000,
  "assignedProducts": 14500,
  "unassignedProducts": 500,
  "productRoutes": null
}
```

#### POST `/api/algorithm/weekly`
Execute algorithm for weekly scenario (7-day batch)

**Request:**
```json
{
  "simulationStartTime": "2025-01-02T00:00:00",
  "simulationDurationDays": 7,
  "useDatabase": true
}
```

**Response:** Same as daily, but covers 7 days

---

### Simulation Control

#### POST `/api/simulation/update-states`
Update product states based on current simulation time

**Request:**
```json
{
  "currentTime": "2025-01-02T20:00:00"
}
```

**Response:**
```json
{
  "success": true,
  "currentSimulationTime": "2025-01-02T20:00:00",
  "transitions": {
    "pendingToInTransit": 0,
    "inTransitToArrived": 450,
    "arrivedToDelivered": 120,
    "total": 570
  }
}
```

#### POST `/api/simulation/advance-time`
Advance simulation time by N hours

**Request:**
```json
{
  "currentTime": "2025-01-02T00:00:00",
  "hoursToAdvance": 8
}
```

**Response:**
```json
{
  "success": true,
  "currentSimulationTime": "2025-01-02T08:00:00",
  "transitions": { /* same as update-states */ }
}
```

---

### Query Endpoints

#### GET `/api/query/orders?startTime={time}&endTime={time}`
Get orders within time window

**Example:**
```
GET /api/query/orders?startTime=2025-01-02T00:00:00&endTime=2025-01-02T12:00:00
```

**Response:**
```json
{
  "success": true,
  "totalOrders": 1250,
  "orders": [/* array of Order objects */],
  "timeWindow": {
    "startTime": "2025-01-02T00:00:00",
    "endTime": "2025-01-02T12:00:00"
  }
}
```

#### GET `/api/query/products/{orderId}`
Get product splits for specific order

**Example:**
```
GET /api/query/products/12345
```

**Response:**
```json
{
  "success": true,
  "orderId": 12345,
  "orderName": "000000001-20250102-01-38-EBCI-006-0007729",
  "totalProducts": 6,
  "products": [/* array of Product objects */],
  "splits": {
    "LIM-CUZ-DAY-0-2000": 3,
    "CUZ-EBCI-DAY-1-0800": 3
  }
}
```

#### GET `/api/query/flights/status`
Get all flight statuses (for map display)

**Response:**
```json
{
  "success": true,
  "totalFlights": 120,
  "flights": [/* array of Flight objects with utilization */],
  "statistics": {
    "totalCapacity": 36000,
    "totalUsedCapacity": 18500,
    "averageUtilization": 51.39
  }
}
```

#### GET `/api/query/flights/{flightCode}/orders` ⭐ KEY ENDPOINT
Get orders assigned to specific flight

**Example:**
```
GET /api/query/flights/LIM-CUZ/orders
```

**Response:**
```json
{
  "success": true,
  "flightCode": "LIM-CUZ",
  "totalOrders": 82,
  "orders": [
    {
      "id": 12345,
      "name": "000000001-20250102-01-38-EBCI-006-0007729",
      "productsOnFlight": 3,
      "totalProducts": 6,
      "status": "IN_TRANSIT"
    }
  ],
  "flight": {
    "code": "LIM-CUZ",
    "usedCapacity": 245,
    "maxCapacity": 300
  }
}
```

#### GET `/api/query/flights/{flightCode}/products`
Get products assigned to specific flight

**Example:**
```
GET /api/query/flights/LIM-CUZ/products
```

**Response:**
```json
{
  "success": true,
  "flightCode": "LIM-CUZ",
  "totalProducts": 245,
  "products": [/* array of Product objects */],
  "flight": {
    "code": "LIM-CUZ",
    "usedCapacity": 245,
    "maxCapacity": 300
  }
}
```

---

## Workflow Diagrams

### Daily Scenario Timeline

```
Real Time:  0s -----> 30s -----> 60s -----> 90s -----> 120s
Sim Time:   Day 0     Day 0      Day 0      Day 0      Day 0
            00:00     00:30      01:00      01:30      02:00

Actions:    [RUN      [UPDATE    [UPDATE    [UPDATE    [RUN
            ALGO]     STATES]    STATES]    STATES]    ALGO]

            │         │          │          │          │
            ├─────────┼──────────┼──────────┼──────────┤
            │                                           │
    Schedule orders                            Schedule NEW orders
    0:00 - 12:00                               2:00 - 14:00
    (1500 products)                            (200 products)
```

### Incremental Scheduling Flow

```
┌─────────────────────────────────────────────────────────────┐
│  Run 1: POST /api/algorithm/daily (Day 0, 00:00 - 12:00)   │
└────────────────────────┬────────────────────────────────────┘
                         │
                         ├──> Algorithm loads orders (Day 0, 00:00-12:00)
                         ├──> No existing DB state (fresh run)
                         ├──> Schedules 1500 products on flights
                         └──> Persists to DB with flight instances

┌─────────────────────────────────────────────────────────────┐
│  Frontend: Simulation progresses (12 hours pass)            │
│  POST /api/simulation/update-states every 30 min            │
└────────────────────────┬────────────────────────────────────┘
                         │
                         ├──> Products: PENDING → IN_TRANSIT
                         ├──> Flights depart and arrive
                         └──> Products: IN_TRANSIT → ARRIVED

┌─────────────────────────────────────────────────────────────┐
│  Run 2: POST /api/algorithm/daily (Day 0, 12:00 - 24:00)   │
└────────────────────────┬────────────────────────────────────┘
                         │
                         ├──> Algorithm loads orders (Day 0, 12:00-24:00)
                         ├──> ✅ Loads existing DB state:
                         │     - 1500 products already assigned
                         │     - Flight capacities updated
                         │     - Warehouse occupancy initialized
                         ├──> Schedules 200 NEW products
                         └──> Respects existing flight capacities
```

### Query Flow for Map Display

```
┌──────────────────┐
│  User Opens Map  │
└────────┬─────────┘
         │
         ├──> GET /api/query/flights/status
         │    (Get all flights with utilization)
         │
         ├──> Display flight markers on map
         │    Color-coded by utilization
         │
         └──> User clicks flight marker "LIM-CUZ"
              │
              ├──> GET /api/query/flights/LIM-CUZ/orders
              │    (Get orders on this flight)
              │
              ├──> Display modal with:
              │    - Flight capacity: 245/300
              │    - 82 orders on this flight
              │    - Order details (name, destination, products)
              │
              └──> User clicks order "000000001-20250102..."
                   │
                   └──> GET /api/query/products/12345
                        (Get product splits for this order)
                        Display: "Order split across 2 flights"
```

---

## State Management

### Frontend State Structure

```javascript
const appState = {
  simulation: {
    startTime: Date,
    currentTime: Date,
    endTime: Date,
    isRunning: Boolean,
    timeScale: Number,
    lastAlgorithmRun: Date,
    lastStateUpdate: Date,
  },

  data: {
    airports: Map<id, Airport>,
    flights: Map<code, Flight>,
    orders: Map<id, Order>,
    scheduledOrders: Set<orderId>,
  },

  statistics: {
    totalOrders: Number,
    assignedOrders: Number,
    unassignedOrders: Number,
    totalProducts: Number,
    assignedProducts: Number,
    flightsUtilization: Number,
  },

  ui: {
    selectedFlight: String,
    selectedOrder: Number,
    mapView: Object,
    activeModals: Array,
  }
};
```

### Synchronization Strategy

```javascript
// Refresh data at different intervals
const refreshSchedule = {
  // Fast refresh (every 5 simulation minutes)
  fastRefresh: ['flightStatus', 'productStates'],

  // Medium refresh (every 30 simulation minutes)
  mediumRefresh: ['updateStates', 'orderStatistics'],

  // Slow refresh (every 12 simulation hours)
  slowRefresh: ['runAlgorithm', 'loadNewOrders'],
};

// Implement refresh loop
setInterval(() => {
  const simMinutes = getSimulationMinutesSinceLastRefresh();

  if (simMinutes % 5 === 0) {
    refreshFlightDisplay(); // Fast
  }

  if (simMinutes % 30 === 0) {
    updateProductStates(simulationState.currentTime); // Medium
  }

  if (simMinutes % 720 === 0) { // 720 min = 12 hours
    runAlgorithmForTimeWindow(simulationState.currentTime, 12); // Slow
  }
}, 1000); // Check every real second
```

---

## Error Handling

### Common Errors and Solutions

#### 1. Algorithm Returns 0 Assigned Products

**Symptoms:**
```json
{
  "assignedProducts": 0,
  "unassignedProducts": 15000
}
```

**Possible Causes:**
- No orders in time window
- All flights at full capacity
- Warehouse capacity constraints

**Solution:**
```javascript
async function handleAlgorithmResult(result) {
  if (result.assignedProducts === 0) {
    // Check if there are orders in this time window
    const orders = await getOrdersInTimeWindow(
      result.simulationStartTime,
      result.simulationEndTime
    );

    if (orders.length === 0) {
      console.log('No orders in time window - normal');
    } else {
      console.warn('Orders exist but none assigned - capacity issue!');
      showWarningModal('Flight capacity exhausted. Consider adding more flights.');
    }
  }
}
```

#### 2. Duplicate Assignments (Products Scheduled Twice)

**Symptoms:**
- Run 1: 1500 products assigned
- Run 2: 1500 products assigned again (should be ~200)

**Cause:** `useDatabase: false` in request (not using persistence)

**Solution:**
```javascript
// ❌ WRONG
await fetch('/api/algorithm/daily', {
  body: JSON.stringify({
    simulationStartTime: "...",
    useDatabase: false  // ❌ Algorithm won't persist!
  })
});

// ✅ CORRECT
await fetch('/api/algorithm/daily', {
  body: JSON.stringify({
    simulationStartTime: "...",
    useDatabase: true  // ✅ Algorithm persists and loads DB state
  })
});
```

#### 3. State Updates Not Working

**Symptoms:**
- Products stuck in `IN_TRANSIT` status
- Never transition to `ARRIVED`

**Cause:** Not calling `/api/simulation/update-states`

**Solution:**
```javascript
// Make sure to call update-states regularly
setInterval(async () => {
  await updateProductStates(simulationState.currentTime);
}, 30000); // Every 30 simulation minutes (or 30 real seconds)
```

#### 4. Flight Query Returns Empty

**Symptoms:**
```
GET /api/query/flights/LIM-CUZ/orders
→ { "totalOrders": 0 }
```

**Cause:**
- Algorithm hasn't run yet
- Wrong flight code
- Products not persisted to DB

**Solution:**
```javascript
// Check flight code is correct (use GET /api/query/flights/status first)
const allFlights = await getAllFlightStatuses();
const flightCodes = allFlights.map(f => f.code);
console.log('Available flights:', flightCodes);

// Then query specific flight
const orders = await getOrdersPerFlight('LIM-CUZ');
```

---

## Testing Guide

### Manual Test Scenario

```bash
# Terminal 1: Start backend
cd backend
mvn spring-boot:run

# Terminal 2: Run test sequence
# Test 1: Initial algorithm run
curl -X POST http://localhost:8080/api/algorithm/daily \
  -H "Content-Type: application/json" \
  -d '{
    "simulationStartTime": "2025-01-02T00:00:00",
    "simulationDurationHours": 12,
    "useDatabase": true
  }'
# Expected: assignedProducts > 0

# Test 2: Query flights
curl http://localhost:8080/api/query/flights/status
# Expected: flights with usedCapacity > 0

# Test 3: Query orders per flight
curl http://localhost:8080/api/query/flights/SPIM-SPZO/orders
# Expected: List of orders

# Test 4: Update product states
curl -X POST http://localhost:8080/api/simulation/update-states \
  -H "Content-Type: application/json" \
  -d '{
    "currentTime": "2025-01-02T12:00:00"
  }'
# Expected: State transitions > 0

# Test 5: Run algorithm again (incremental scheduling)
curl -X POST http://localhost:8080/api/algorithm/daily \
  -H "Content-Type: application/json" \
  -d '{
    "simulationStartTime": "2025-01-02T12:00:00",
    "simulationDurationHours": 12,
    "useDatabase": true
  }'
# Expected: assignedProducts < first run (only NEW orders)
```

### Frontend Integration Checklist

- [ ] Simulation clock displays and advances correctly
- [ ] Algorithm runs every 12 simulation hours
- [ ] Product states update every 30 simulation minutes
- [ ] Map displays flights with color-coded utilization
- [ ] Clicking flight shows orders on that flight
- [ ] Order details show product splits
- [ ] Incremental scheduling works (second run assigns fewer products)
- [ ] Console logs show "DB state loaded successfully"
- [ ] Error handling for failed API calls
- [ ] Loading indicators during API calls

---

## Performance Considerations

### Optimization Tips

1. **Cache Static Data**
   ```javascript
   // Load once at startup, don't re-fetch
   const airports = await loadAirports(); // Cache for entire session
   const flightTemplates = await loadFlights(); // Cache
   ```

2. **Debounce Map Updates**
   ```javascript
   // Don't update map on every state change
   const debouncedMapUpdate = debounce(refreshFlightDisplay, 5000);
   ```

3. **Pagination for Large Lists**
   ```javascript
   // If flight has 1000+ orders, paginate
   async function getOrdersPerFlight(flightCode, page = 1, pageSize = 50) {
     // Implement pagination on frontend
   }
   ```

4. **WebSocket for Real-Time Updates (Future Enhancement)**
   ```javascript
   // Instead of polling, use WebSocket for push notifications
   const ws = new WebSocket('ws://localhost:8080/ws/simulation');
   ws.onmessage = (event) => {
     const update = JSON.parse(event.data);
     if (update.type === 'PRODUCT_STATE_CHANGE') {
       updateUI(update.data);
     }
   };
   ```

---

## Summary

### Key Points

1. **Algorithm runs incrementally** - Each run respects previous assignments
2. **Frontend controls time** - Backend is stateless, frontend drives simulation
3. **Query DB directly** - No large JSON responses, query what you need
4. **Orders per flight** - Use `/api/query/flights/{code}/orders` for map clicks
5. **State updates required** - Call `/api/simulation/update-states` regularly
6. **Use `useDatabase: true`** - Critical for persistence and incremental scheduling

### Workflow Summary

```
1. Initialize → Load airports, flights, orders
2. Run Algorithm → POST /api/algorithm/daily (12h window)
3. Display Map → GET /api/query/flights/status
4. Advance Time → POST /api/simulation/update-states (every 30min)
5. Repeat Step 2 → Run algorithm for next 12h window
6. On Flight Click → GET /api/query/flights/{code}/orders
7. On Order Click → GET /api/query/products/{orderId}
```

### Need Help?

- Backend logs: Check `mvn spring-boot:run` output for "DB state loaded successfully"
- API errors: Check HTTP status codes and error messages
- State issues: Verify `/api/simulation/update-states` is called regularly
- Incremental scheduling: Look for "Initialized flight capacities: X flights, Y products loaded"

---

**Last Updated:** November 12, 2025
**Backend Version:** 2.0 (with incremental scheduling)
**Contact:** Backend team via GitHub issues
