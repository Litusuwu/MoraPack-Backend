# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

MoraPack is a package distribution and routing optimization system for a company that delivers MPE products to major cities across America, Asia, and Europe. The system handles:

- **Package routing and delivery planning** (Component Planificador)
- **Real-time order tracking** with 2-hour customer pickup windows
- **Three operational scenarios**: day-to-day operations, weekly simulation, and collapse simulation
- **Two metaheuristic algorithms**: ALNS (Adaptive Large Neighborhood Search) and Tabu Search

### Key Business Rules

- **Headquarters**: Lima (Peru), Brussels (Belgium), Baku (Azerbaijan) - unlimited stock
- **Delivery deadlines**: 2 days max (same continent), 3 days max (different continent)
- **PACK airline agreement**: 0.5 days transport (same continent), 1 day (different continent)
- **Flight capacity**: 200-300 packages (same continent), 250-400 (different continent)
- **Warehouse capacity**: 600-1000 packages per airport
- **Customer pickup window**: 2 hours max at destination airport
- **Minimum layover time**: 1 hour for products in transit at intermediate destinations
- **Products within an order** can arrive at different times, as long as all meet the deadline
- **Flights can be cancelled** (only before takeoff) and **delayed** (3 hour standard delay)
- **Products in transit** (on flights) can be reassigned
- **Products on ground** can be reassigned only at intermediate stops, not at final delivery points

## Development Commands

### Build and Run

```bash
# Build the project
mvn clean install

# Run Spring Boot application (REST API backend)
mvn spring-boot:run

# Run algorithm comparison (standalone)
mvn exec:java -Dexec.mainClass="com.system.morapack.Main"

# Run specific algorithm
mvn exec:java -Dexec.mainClass="com.system.morapack.Main" -Dexec.args="alns"
mvn exec:java -Dexec.mainClass="com.system.morapack.Main" -Dexec.args="tabu"
```

### Testing

```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=MoraPackApplicationTests
```

### Database

```bash
# Start PostgreSQL via Docker Compose
docker compose up -d

# Stop database
docker compose down

# Database connection details
# Host: localhost
# Port: 5435
# Database: postgres
# Username: postgres
# Password: postgres
```

### Python Experimental Analysis

The `experiment/` directory contains Python scripts for numerical analysis:

```bash
# Setup Python environment
cd experiment
python3 -m venv venv
source venv/bin/activate  # On Unix/macOS
pip install -r requirements.txt

# Run algorithm comparison experiments
python run_simulations.py
```

## Architecture

### Layer Structure

```
src/main/java/com/system/morapack/
├── api/                    # REST API controllers
├── bll/                    # Business Logic Layer
│   ├── adapter/           # Data transformation between layers
│   └── controller/        # Business logic controllers
├── config/                # Configuration and constants
│   └── Constants.java     # All algorithm and business parameters
├── dao/                   # Data Access Object layer
│   └── morapack_psql/     # PostgreSQL implementation
│       ├── model/         # JPA entities (Order, Product, Flight, etc.)
│       └── repository/    # Spring Data JPA repositories
├── schemas/               # Core domain objects and algorithms
│   ├── algorithm/         # Optimization algorithms
│   │   ├── ALNS/         # Adaptive Large Neighborhood Search
│   │   ├── TabuSearch/   # Tabu Search metaheuristic
│   │   └── Input/        # Data input handling
│   └── *Schema.java       # Domain models (OrderSchema, FlightSchema, etc.)
└── utils/                 # Utility classes
```

### Key Architecture Patterns

1. **Dual Data Model**:
   - **Schema classes** (`schemas/` package): Used by algorithms for optimization
   - **JPA entities** (`dao/morapack_psql/model/`): Used for database persistence
   - **Adapters** bridge between these representations

2. **Algorithm Core** (`src/main/java/com/system/morapack/schemas/algorithm/`):
   - Two independent metaheuristic implementations (ALNS and TabuSearch)
   - Both solve the same routing optimization problem
   - Input data from `data/` directory: `airportInfo.txt`, `flights.txt`, `products.txt`

3. **Product Unitization** (Feature Toggle):
   - Controlled by `Constants.ENABLE_PRODUCT_UNITIZATION`
   - When enabled: breaks orders into individual product units for flexible routing
   - When disabled: treats entire orders as atomic units

4. **REST API Backend**:
   - Provides endpoints for frontend to track orders, flights, and status
   - Updates database with real-time changes
   - Intended to support three operational scenarios

## Critical Implementation Details

### Product vs Order Distinction

- **Order (OrderSchema)**: A customer order containing 1+ products
- **Product (ProductSchema)**: Individual MPE items that can be tracked separately
- **Current limitation**: Algorithm primarily uses OrderSchema; product-level tracking needs improvement
- **Location**: Orders have `currentLocation` and `destinationCitySchema`

### Algorithm Constraints

The algorithms must satisfy:

1. **Temporal constraints**:
   - Respect PACK airline transport times (0.5 days same continent, 1 day different)
   - Honor MoraPack delivery promises (2/3 days based on continents)
   - Enforce minimum 1-hour layover at intermediate stops
   - 2-hour customer pickup window at final destination

2. **Capacity constraints**:
   - Flight capacity limits (200-400 packages depending on route type)
   - Warehouse capacity at each airport (600-1000 packages)
   - Temporal validation (minute-by-minute occupancy tracking)

3. **Business rules**:
   - Origin validation (packages must start from Lima, Brussels, or Baku)
   - Flight cancellation/delay handling
   - Product reassignment rules (different for in-flight vs on-ground)

### Algorithm Parameters

All tunable parameters are centralized in `config/Constants.java`:

- Destruction ratios for ALNS
- Tabu tenure and iteration limits
- Diversification/intensification thresholds
- Safety margins and validation toggles
- Feature flags (product unitization, headquarters validation, verbose logging)

## Operational Scenarios

The system must support three distinct operational scenarios:

### 1. Daily Scenario (Real-time Operations)
- **Behavior:** Continuous simulation that runs indefinitely
- **Frontend calls:** Every ~30 minutes of simulation time
- **Algorithm input:** Current simulation time (e.g., `Day 0, 00:00:00`)
- **Algorithm output:** Routes for orders within the time window
- **Time progression:** Incremental (00:00 → 00:30 → 01:00 → ...)
- **Use case:** Real-time order tracking and route visualization

### 2. Weekly Scenario (7-Day Simulation)
- **Behavior:** Fixed 7-day simulation
- **Frontend calls:** Single call for entire week
- **Algorithm input:** Start time (Day 0, 00:00:00) + 7-day duration
- **Algorithm output:** Complete routes for all orders in 7 days
- **Execution time:** Should take 30-90 minutes (per requirements)
- **Use case:** Weekly planning and optimization

### 3. Collapse Scenario (Stress Testing)
- **Behavior:** Simulate until system capacity is exceeded
- **Status:** Planned for later implementation
- **Use case:** Identify system breaking points and capacity limits

## Critical Issues to Address

### Issue #1: Simulation Time Handling ⚠️ **HIGH PRIORITY**

**Problem:**
- Current implementation uses `LocalDateTime.now()` as reference time
- No concept of simulation progression or time windows
- Frontend cannot control which orders are processed

**Required Changes:**
1. Accept simulation time parameters from frontend:
   - Current date/time in simulation
   - Duration/time window to process
2. Filter orders based on simulation time window
3. Use simulation time (not system time) for all calculations

**API Changes Needed:**
```java
// Add to AlgorithmRequest
private LocalDateTime simulationStartTime;  // When simulation starts
private LocalDateTime simulationEndTime;    // When simulation ends
```

### Issue #2: Order Data Loading ⚠️ **HIGH PRIORITY**

**Problem:**
- Old code reads single `products.txt` file
- New structure has multiple files per airport (~36MB total)
- Need to filter by time window to avoid loading all orders

**Required Changes:**
1. Update `InputProducts.java` to:
   - Read all `_pedidos_{AIRPORT}_` files in `backend/data/`
   - Parse new format: `id_pedido-aaaammdd-hh-mm-dest-###-IdClien`
   - Filter orders by simulation time window
   - Handle multiple origin airports correctly

**Example:**
```java
// Parse: 000000001-20250102-01-38-EBCI-006-0007729
String[] parts = line.split("-");
String orderId = parts[0];           // "000000001"
String dateStr = parts[1];           // "20250102"
int hour = Integer.parseInt(parts[2]);     // 01
int minute = Integer.parseInt(parts[3]);   // 38
String destCode = parts[4];          // "EBCI"
int quantity = Integer.parseInt(parts[5]); // 006
String customerId = parts[6];        // "0007729"

// Convert to LocalDateTime
LocalDateTime orderDate = LocalDateTime.parse(
    dateStr + "T" + String.format("%02d:%02d:00", hour, minute)
);

// Filter by simulation window
if (orderDate.isBefore(simStart) || orderDate.isAfter(simEnd)) {
    continue; // Skip this order
}
```

### Issue #3: API Response Simplification

**Problem:**
- Response includes `algorithmType` (only using ALNS)
- Tabu-specific parameters in request
- No clear simulation time info in response

**Required Changes:**
1. Remove `algorithmType` from `AlgorithmResultSchema`
2. Add simulation window info to response:
   ```java
   private LocalDateTime simulationStartTime;
   private LocalDateTime simulationEndTime;
   ```
3. Add product-level metrics:
   ```java
   private Integer assignedProducts;
   private Integer unassignedProducts;
   ```

### Issue #4: Scenario-Specific Endpoints

**Problem:**
- Single generic `/execute` endpoint
- Frontend must manage scenario logic
- No validation for scenario requirements

**Required Changes:**
Create dedicated endpoints:
- `POST /api/algorithm/daily` - For incremental daily operations
- `POST /api/algorithm/weekly` - For 7-day batch processing
- `POST /api/algorithm/collapse` - For stress testing (future)

## Current Development Tasks

Priority order for implementation:

1. ✅ **Update data format documentation** - COMPLETED
2. 🔄 **Implement simulation time handling** - IN PROGRESS
   - Update `AlgorithmRequest` to accept simulation time
   - Update `InputProducts` to parse new format and filter by time
   - Update `Solution` constructor to use simulation time
3. ⏳ **Create scenario endpoints** - PENDING
   - Add `/api/algorithm/daily` endpoint
   - Add `/api/algorithm/weekly` endpoint
4. ⏳ **Simplify API responses** - PENDING
   - Remove algorithm type field
   - Add simulation time info
   - Add product-level metrics
5. ⏳ **Optimize ALNS for scenarios** - PENDING
   - Tune parameters for daily vs weekly
   - Add proper product-level tracking
   - Fix 1-hour layover constraint

## Data Files

Input data located in `backend/data/` directory:

### Airport Information
- **File:** `airportInfo.txt`
- **Content:** Airport and warehouse capacity information

### Flights
- **File:** `flights.txt`
- **Format:** `ORIGIN-DESTINATION-DEPARTURE-ARRIVAL-CAPACITY`
- **Content:** Available flight routes and schedules

### Orders (Updated Structure)
- **Files:** `_pedidos_{AIRPORT_CODE}_` (one file per origin airport)
- **Examples:** `_pedidos_LDZA_`, `_pedidos_SVMI_`, `_pedidos_SBBR_`
- **Format:** `id_pedido-aaaammdd-hh-mm-dest-###-IdClien`
- **Example:** `000000001-20250102-01-38-EBCI-006-0007729`

**Field Description:**
- `id_pedido`: Order identifier (unique per destination)
- `aaaammdd`: Order creation date (YYYY-MM-DD format)
  - Example: `20250102` = January 2, 2025
- `hh`: Hour when order is created (00-23)
- `mm`: Minute when order is created (00-59)
- `dest`: Destination airport code (e.g., EBCI, SVMI, SBBR)
- `###`: Product quantity as 3-digit string (001-999)
- `IdClien`: Customer identifier as 7-digit number (0000001-9999999)

**Important Notes:**
- Each file represents orders originating from a specific airport
- Orders include precise timestamp (date + time) for creation
- The algorithm must filter orders based on simulation time window
- File size: ~36MB total across all airport files (do not read all at once)

## Technology Stack

- **Java 17** with Spring Boot 3.5.5
- **Spring Modulith** for modular architecture
- **Spring Data JPA** with PostgreSQL
- **Lombok** for boilerplate reduction
- **Maven** for build management
- **Docker Compose** for PostgreSQL container
- **Python 3.9+** for experimental analysis (optional)

## Important Notes

- The Spring application (`MoraPackApplication.java`) runs the REST API backend
- The standalone `Main.java` runs algorithm comparisons without Spring
- Algorithm execution time for weekly simulation should be 30-90 minutes (per requirements)
- Frontend is in a separate project and consumes the REST API endpoints
- Solution output format should be: `Map<Product, List<Flight>>` (not yet implemented)

## Problem Statement

The project wants to cover this problem statement:

"La empresa MoraPack se dedica a la venta y distribución de su producto estrella MPE a las
principales ciudades de América, Asia y Europa. MoraPack ha tenido un relativo éxito en el
cumplimiento de sus plazos de entrega, lo que le ha permitido crecer considerablemente en clientes y
ventas (envíos). La Empresa tiene como política que cada cliente debe recoger sus productos (uno o
más), directamente en sus oficinas en los aeropuertos. Se debe considerar que sólo se trabaja con un
aeropuerto en cada ciudad y el cliente tiene como plazo de recojo dos horas como máximo. Además,
los productos pueden llegar en distintos momentos siempre que todos lleguen dentro del plazo
establecido.
El cliente recibe, al inicio y luego a demanda (a pedido), el plan de viaje y ubicación (en
tiempo real) de sus productos comprados en cualquier momento. El servicio de monitoreo, indica en
qué ciudad está en ese momento, según el plan de viaje del paquete (actividad manual que hacían en
cada destino por donde pasaba el paquete).
MoraPack tiene sede en Lima (Perú), Bruselas (Bélgica) y Baku (Azerbaiyan) y stock
ilimitado en esas sedes. El plazo de entrega de los productos establecido en MoraPack es de dos días
como máximo para ciudades del mismo continente y de tres días para distinto continente. La
Empresa tiene un acuerdo de negocios con la Aerolínea PACK por la cual tiene un tiempo para el
traslado de paquete entre dos ciudades del mismo continente de medio día y diferente continente de
un día. Los vuelos de PACK se realizan una o más veces al día entre ciudades del mismo continente
y al menos una vez al día entre algunas ciudades de distinto continente. La capacidad máxima actual
de traslado para vuelos dentro del mismo continente varía entre de 200 y 300 paquetes según el
vuelo; y para distinto continente varía entre 250 y 400 paquetes según el vuelo. La capacidad de
almacenamiento en cada almacén en el aeropuerto varía entre 600 y 1000 paquetes, según la ciudad.
La empresa MoraPack ha contratado a usted y su equipo para que desarrolle una solución
informática para sus principales necesidades. Dichas necesidades se resumen en: (i) registrar la
cantidad de productos (MPE) a ser enviados a los clientes; (ii) planificar -y replanificar- las rutas
de los productos cumpliendo los plazos comprometidos (componente planificador); y (iii) presentar
gráficamente el monitoreo de las operaciones de la Empresa, en un mapa (componente visualizador).
Para la evaluación del curso, se manejará 3 escenarios: las operaciones día a día (en tiempo real), la
simulación semanal del traslado de los productos MPE y la simulación hasta el colapso de las
operaciones de la MoraPack. Para ello se requiere que: (a) el componente planificador resuelva
mediante parámetros los 3 escenarios; y (b) presente de manera gráfica información relevante del
desempeño de las operaciones (en los 3 escenarios). El primer escenario en resolverse debe ser el
de la simulación semanal que debe tomar en ejecutarse entre 30 y 90 minutos.
Requisitos No Funcionales:
Para este proyecto se establecen los siguientes requisitos no funcionales:
a. Presentar dos soluciones algorítmicas en Lenguaje Java y evaluadas por experimentación
numérica.
b. Los dos algoritmos de la experimentación numérica deben ser del tipo metaheurísticos.

Common questions:

Un pedido/envío (ORDER) está compuesto de 1 o más productos.
Se trata de un tipo de producto que puede ser entregado a cualquier cliente de manera indistinta.
Cuando un producto está en tránsito (en un vuelo), sí puede ser reasignado.
Cuando un producto está en tierra (en un almacen), sí puede ser reasignado si el almacén es de paso (o de escala).
Cuando un producto está en tierra (en un almacen), no debe ser reasignado si el almacén es el de entrega (punto final).
Cada producto puede llegar de manera individual (o colectiva), siendo la única condición que se cumpla el plazo de entrega establecido.

-¿Un vuelo puede ser cancelado?
Sí, Un vuelo puede ser cancelado.
Durante la interacción con el software en los tres escenarios (día a día, sim. semanal y sim. colapso) se puede cancelar manualmente un avión (no importa la causa). La cancelación puede ser desde el mapa vía el aeropuerto o desde un panel de seleccion.
Un vuelo no puede ser cancelado una vez que ha despegado.
Además, se va a generar un grupo de archivos para cancelaciones "programadas", para el caso de sim. semanal y sim.colapso.

-¿Un vuelo puede ser demorado?
Sí. La demora que se va a fijar es de 3 horas.

-¿los vuelos se pueden reprogramar?
No.

-¿los vuelos se pueden cancelar?
Sí
Se van a generar archivos mensuales de cancelación de vuelos.
dd.id-vuelo

Donde
dd: días en dos posiciones 01, 04, 12, 24
id-vuelo : ORIGEN-DESTINO-HoraOrigen

-¿Tiempos de carga, descarga y estancia de los paquetes?
Los tiempos de carga y descarga serán considerados despreciables (instantaneos).
Los tiempos de estancia mínima para los productos en tránsito (destino intermedio) es de 1 hora.

"


The problem and the implementation of the two algorithms (ALNS and Tabu Search) are in the src/main/java/com/system/morapack/schemas/algorithm directory.
This is the core of the problem.

Im using java 17 with spring; I am also building the backend for the problem, my approach is to get the REST API to reach the database and change constantly the track of the orders, his status and also the flights, airports and the status; so the front-end in other project its going to reach the endpoints to update it. Also, theres an endpoint in the backend that returns the solution structure, it should be the 'map[products, array[flights]]' but i need to do some tasks first.


1. Do the endpoints