package com.system.morapack.schemas.algorithm.ALNS;

import com.system.morapack.schemas.*;
import com.system.morapack.config.Constants;
import com.system.morapack.schemas.algorithm.Input.InputAirports;
import com.system.morapack.schemas.algorithm.Input.InputData;
import com.system.morapack.schemas.algorithm.Input.InputProducts;
import com.system.morapack.schemas.algorithm.Input.InputDataSource;
import com.system.morapack.schemas.algorithm.Input.DataSourceFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.PriorityQueue;
import java.util.Comparator;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

public class Solution {
    private HashMap<HashMap<OrderSchema, ArrayList<FlightSchema>>, Integer> solution;
    private InputAirports inputAirports;
    private InputData inputData;
    private InputProducts inputProducts;
    
    // CHANGED: Cache robusta CitySchema→AirportSchema por nombre (evita problemas de equals)
    private Map<String, AirportSchema> cityNameToAirportCache;
    private ArrayList<AirportSchema> airportSchemas;
    private ArrayList<FlightSchema> flightSchemas;
    private ArrayList<OrderSchema> orderSchemas;
    
    // PATCH: Unitización - flag y datos
    private static final boolean ENABLE_PRODUCT_UNITIZATION = true; // Flag para activar/desactivar
    private ArrayList<OrderSchema> originalOrderSchemas; // Packages originales antes de unitizar
    
    // NEW: Ancla temporal T0 para cálculos consistentes
    private LocalDateTime T0;
    // Mapa para rastrear la ocupación de almacenes por destino
    private HashMap<AirportSchema, Integer> warehouseOccupancy;
    // Matriz temporal para validar capacidad de almacenes por minuto [aeropuerto][minuto_del_dia]
    private HashMap<AirportSchema, int[]> temporalWarehouseOccupancy;
    // Generador de números aleatorios para diversificar soluciones
    private HashMap<HashMap<OrderSchema, ArrayList<FlightSchema>>, Integer> bestSolution;
    private Random random;
    
    // Variables para ALNS
    private ALNSDestruction destructionOperators;
    private ALNSRepair repairOperators;
    private double[][] operatorWeights; // Pesos de operadores [destrucción][reparación]
    private double[][] operatorScores;  // Puntajes de operadores [destrucción][reparación]
    private int[][] operatorUsage;      // Contador de uso de operadores [destrucción][reparación]
    private double temperature;
    private double coolingRate;
    private int maxIterations;
    private int segmentSize;

    // NEW: Optimized validators and trackers (Issue fixes #2, #3, #4)
    private RouteValidator routeValidator;  // Fixes Issue #3 (1-hour layover) and #4 (performance)
    private ProductTracker productTracker;  // Fixes Issue #2 (product-level tracking)
    
    // Mecanismos de diversificación e intensificación
    private int stagnationCounter;
    private int diversificationThreshold;
    private boolean diversificationMode;
    private int lastImprovementIteration;
    private double diversificationFactor;
    
    // OPTIMIZED: Pool de paquetes no asignados con PriorityQueue (Recomendación #4)
    // Ordena automáticamente por urgencia (deadline más cercano primero)
    private PriorityQueue<OrderSchema> unassignedPool;

    // Control de diversificación extrema / restart inteligente
    private int iterationsSinceSignificantImprovement;
    private int restartCount;

    // NEW: Snapshot para rollback de cambios especulativos (Recomendación #2)
    private HashMap<AirportSchema, int[]> temporalWarehouseSnapshot;

    // Simulation time window parameters
    private LocalDateTime simulationStartTime;
    private LocalDateTime simulationEndTime;

    // NEW: Order splitting tracking for batch persistence
    // Tracks order splits created during algorithm execution
    // Format: Map<orderName, List<SplitInfo>> where SplitInfo = {quantity, route}
    private Map<String, List<OrderSplitInfo>> orderSplits;

    /**
     * Inner class to track order split information
     */
    public static class OrderSplitInfo {
        public Integer quantity;
        public ArrayList<FlightSchema> assignedRoute;
        public ArrayList<FlightInstanceSchema> assignedFlightInstances;

        public OrderSplitInfo(Integer quantity, ArrayList<FlightSchema> route) {
            this.quantity = quantity;
            this.assignedRoute = route;
            this.assignedFlightInstances = new ArrayList<>();
        }

        public OrderSplitInfo(Integer quantity, ArrayList<FlightSchema> route, ArrayList<FlightInstanceSchema> instances) {
            this.quantity = quantity;
            this.assignedRoute = route;
            this.assignedFlightInstances = instances;
        }
    }

    /**
     * Constructor with simulation time window (PREFERRED for daily/weekly scenarios)
     * Loads only orders within the specified time window
     *
     * @param simulationStartTime Start of simulation time window
     * @param simulationEndTime End of simulation time window
     */
    public Solution(LocalDateTime simulationStartTime, LocalDateTime simulationEndTime) {
        this.simulationStartTime = simulationStartTime;
        this.simulationEndTime = simulationEndTime;

        System.out.println("========================================");
        System.out.println("ALNS SOLUTION - TIME WINDOW MODE");
        System.out.println("Simulation Start: " + simulationStartTime);
        System.out.println("Simulation End:   " + simulationEndTime);
        System.out.println("========================================");

        initializeSolution(simulationStartTime, simulationEndTime);
    }

    /**
     * Default constructor (loads ALL orders - no time filtering)
     * @deprecated Use Solution(simulationStartTime, simulationEndTime) for scenarios
     */
    @Deprecated
    public Solution() {
        System.out.println("========================================");
        System.out.println("ALNS SOLUTION - LEGACY MODE (NO TIME FILTERING)");
        System.out.println("WARNING: Loading ALL orders from data source");
        System.out.println("========================================");

        this.simulationStartTime = null;
        this.simulationEndTime = null;

        initializeSolution(null, null);
    }

    /**
     * Common initialization logic for both constructors
     */
    private void initializeSolution(LocalDateTime simStart, LocalDateTime simEnd) {
        // ========== MODULAR DATA SOURCE ==========
        // Create data source based on Constants.DATA_SOURCE_MODE
        // Supports FILE (data/ directory) and DATABASE (PostgreSQL) modes
        InputDataSource dataSource = DataSourceFactory.createDataSource();
        dataSource.initialize();

        System.out.println("========================================");
        System.out.println("DATA SOURCE: " + dataSource.getSourceName());
        System.out.println("========================================");

        this.solution = new HashMap<>();

        // Load data from selected source (FILE or DATABASE)
        this.airportSchemas = dataSource.loadAirports();
        this.flightSchemas = dataSource.loadFlights(this.airportSchemas);

        // Load orders with or without time filtering
        if (simStart != null && simEnd != null) {
            System.out.println("Loading orders with time window filtering...");
            this.originalOrderSchemas = dataSource.loadOrders(this.airportSchemas, simStart, simEnd);
        } else {
            System.out.println("Loading ALL orders (no time filtering)...");
            this.originalOrderSchemas = dataSource.loadOrders(this.airportSchemas);
        }

        // Keep references to old file-based readers for backward compatibility
        // (these will be null when using DATABASE mode, but not accessed)
        this.inputAirports = null;
        this.inputData = null;
        this.inputProducts = null;

        // PATCH: Aplicar unitización si está habilitada
        if (ENABLE_PRODUCT_UNITIZATION) {
            this.orderSchemas = expandPackagesToProductUnits(this.originalOrderSchemas);
            System.out.println("UNITIZACIÓN APLICADA: " + this.originalOrderSchemas.size() +
                             " paquetes originales → " + this.orderSchemas.size() + " unidades de producto");
        } else {
            this.orderSchemas = new ArrayList<>(this.originalOrderSchemas);
            System.out.println("UNITIZACIÓN DESHABILITADA: Usando paquetes originales");
        }

        this.warehouseOccupancy = new HashMap<>();
        this.temporalWarehouseOccupancy = new HashMap<>();

        // NEW: Initialize order splits tracking
        this.orderSplits = new HashMap<>();
        System.out.println("Order splits tracking initialized (for batch persistence)");

        // CHANGED: Inicializar cache robusta y T0
        initializeCityToAirportCache();
        initializeT0();

        // Inicializar generador de números aleatorios con semilla basada en tiempo actual
        this.random = new Random(System.currentTimeMillis());

        // Inicializar operadores ALNS
        this.destructionOperators = new ALNSDestruction();
        this.repairOperators = new ALNSRepair(airportSchemas, flightSchemas, warehouseOccupancy);

        // NEW: Initialize optimized validators and trackers
        System.out.println("Inicializando RouteValidator (optimización O(1))...");
        this.routeValidator = new RouteValidator(airportSchemas, flightSchemas);

        System.out.println("Inicializando ProductTracker (seguimiento de productos)...");
        this.productTracker = new ProductTracker();
        this.productTracker.initializeFromOrders(this.orderSchemas);

        // Inicializar parámetros ALNS
        initializeALNSParameters();

        // Inicializar ocupación de almacenes y vuelos (base)
        initializeWarehouseOccupancy();
        initializeTemporalWarehouseOccupancy();

        // NEW: Load existing product assignments from DB (for incremental scheduling)
        if (simStart != null && simEnd != null) {
            System.out.println("\n=== LOADING EXISTING DB STATE FOR INCREMENTAL SCHEDULING ===");
            try {
                Map<String, List<ProductSchema>> existingAssignments =
                    dataSource.loadExistingProductAssignments(simStart, simEnd);

                if (existingAssignments != null && !existingAssignments.isEmpty()) {
                    // Initialize flight capacities from existing assignments
                    initializeFlightCapacitiesFromDB(existingAssignments);

                    // Initialize warehouse occupancy from existing assignments
                    initializeWarehouseOccupancyFromDB(existingAssignments);

                    System.out.println("DB state loaded successfully - algorithm will skip already-assigned products");
                } else {
                    System.out.println("No existing assignments found - fresh run");
                }
            } catch (Exception e) {
                System.err.println("WARNING: Failed to load existing DB state: " + e.getMessage());
                System.err.println("Continuing with fresh run (no incremental scheduling)");
                e.printStackTrace();
            }
            System.out.println("=============================================================\n");
        }
    }
    
    /**
     * Inicializa los parámetros del algoritmo ALNS
     */
    private void initializeALNSParameters() {
        // Número de operadores de destrucción y reparación
        int numDestructionOps = 4; // random, geographic, timeBased, congestedRoute
        int numRepairOps = 4;      // greedy, regret, timeBased, capacityBased
        
        // Inicializar matrices de pesos, puntajes y uso
        this.operatorWeights = new double[numDestructionOps][numRepairOps];
        this.operatorScores = new double[numDestructionOps][numRepairOps];
        this.operatorUsage = new int[numDestructionOps][numRepairOps];
        
        // Inicializar pesos uniformemente (1.0 para todos)
        for (int i = 0; i < numDestructionOps; i++) {
            for (int j = 0; j < numRepairOps; j++) {
                this.operatorWeights[i][j] = 1.0;
                this.operatorScores[i][j] = 0.0;
                this.operatorUsage[i][j] = 0;
            }
        }
        
        // Parámetros del algoritmo optimizados para MoraPack
        this.temperature = 100.0;         // Temperatura inicial más baja
        this.coolingRate = 0.98;          // Enfriamiento más rápido
        this.maxIterations = 1000;        // Muchas más iteraciones
        this.segmentSize = 25;            // Segmentos más pequeños para mejor adaptación
        
        // Inicializar mecanismos de diversificación
        this.stagnationCounter = 0;
        this.diversificationThreshold = 100; // Cambiar a diversificación después de 100 iteraciones sin mejora
        this.diversificationMode = false;
        this.lastImprovementIteration = 0;
        this.diversificationFactor = 1.0;
        
        // OPTIMIZED: Inicializar pool de paquetes no asignados con PriorityQueue
        // Ordena por deadline (más urgente primero) - Recomendación #4
        this.unassignedPool = new PriorityQueue<>(new Comparator<OrderSchema>() {
            @Override
            public int compare(OrderSchema p1, OrderSchema p2) {
                // Ordenar por deadline (más cercano primero = mayor urgencia)
                if (p1.getDeliveryDeadline() == null && p2.getDeliveryDeadline() == null) return 0;
                if (p1.getDeliveryDeadline() == null) return 1; // nulls last
                if (p2.getDeliveryDeadline() == null) return -1;

                int deadlineComp = p1.getDeliveryDeadline().compareTo(p2.getDeliveryDeadline());
                if (deadlineComp != 0) return deadlineComp;

                // Tie-break por prioridad (mayor primero)
                int priorityComp = Double.compare(p2.getPriority(), p1.getPriority());
                if (priorityComp != 0) return priorityComp;

                // Tie-break final por ID (Recomendación #3)
                return Integer.compare(p1.getId(), p2.getId());
            }
        });
        
        // Inicializar control de restart inteligente
        this.iterationsSinceSignificantImprovement = 0;
        this.restartCount = 0;
    }

    public void solve() {
        // 1. Inicialización
        System.out.println("Iniciando solución ALNS");
        System.out.println("Lectura de aeropuertos");
        System.out.println("Aeropuertos leídos: " + this.airportSchemas.size());
        System.out.println("Lectura de vuelos");
        System.out.println("Vuelos leídos: " + this.flightSchemas.size());
        System.out.println("Lectura de productos");
        System.out.println("Productos leídos: " + this.orderSchemas.size());
        
        // 2. Generar una solución inicial s_actual
        System.out.println("\n=== GENERANDO SOLUCIÓN INICIAL ===");
        this.generateInitialSolution();
        
        // Validar solución generada
        System.out.println("Validando solución...");
        boolean isValid = this.isSolutionValid();
        System.out.println("Solución válida: " + (isValid ? "SÍ" : "NO"));
        
        // Mostrar descripción de la solución inicial
        this.printSolutionDescription(1);
        
        // 3. Establecer s_mejor = s_actual
        bestSolution = new HashMap<>(solution);
        
        // 3.5. Inicializar pool de paquetes no asignados para ALNS
        initializeUnassignedPool();
        
        // 4. Ejecutar algoritmo ALNS
        System.out.println("\n=== INICIANDO ALGORITMO ALNS ===");
        runALNSAlgorithm();

        // 4.5. NEW: Update product tracking after algorithm completes
        System.out.println("\n=== ACTUALIZANDO SEGUIMIENTO DE PRODUCTOS ===");
        updateProductTracking();

        // 5. Mostrar resultado final
        System.out.println("\n=== RESULTADO FINAL ALNS ===");
        this.printSolutionDescription(2);

        // 5.5. NEW: Print product tracking summary
        productTracker.printTrackingSummary();
    }

    /**
     * NEW: Updates product tracking with final solution assignments
     * Fixes Issue #2: Proper product-level tracking
     */
    private void updateProductTracking() {
        // Get current solution from bestSolution (which has the best found solution)
        HashMap<OrderSchema, ArrayList<FlightSchema>> currentSolution = null;
        if (bestSolution != null && !bestSolution.isEmpty()) {
            currentSolution = bestSolution.keySet().iterator().next();
        }

        if (currentSolution == null || currentSolution.isEmpty()) {
            System.out.println("No hay solución para rastrear productos");
            return;
        }

        // Assign each order's products to their routes
        int productsTracked = 0;
        for (Map.Entry<OrderSchema, ArrayList<FlightSchema>> entry : currentSolution.entrySet()) {
            OrderSchema order = entry.getKey();
            ArrayList<FlightSchema> route = entry.getValue();

            // This updates ProductSchema.assignedFlight and status
            productTracker.assignOrderToRoute(order, route);

            int productCount = order.getProductSchemas() != null ? order.getProductSchemas().size() : 1;
            productsTracked += productCount;
        }

        System.out.println("Productos rastreados: " + productsTracked);
    }

    /**
     * NEW: Get product-level solution for API endpoint
     * Returns Map<Product, List<Flight>> as specified in requirements
     */
    public Map<ProductSchema, ArrayList<FlightSchema>> getProductLevelSolution() {
        return productTracker.getProductLevelSolution();
    }

    /**
     * Get simulation start time (for API response)
     */
    public LocalDateTime getSimulationStartTime() {
        return simulationStartTime;
    }

    /**
     * Get simulation end time (for API response)
     */
    public LocalDateTime getSimulationEndTime() {
        return simulationEndTime;
    }

    /**
     * Get order splits for batch persistence
     * Returns map of order ID to list of splits (quantity + route)
     * This enables batch DB inserts after algorithm completes
     */
    public Map<String, List<OrderSplitInfo>> getOrderSplits() {
        return orderSplits;
    }

    /**
     * NEW: SLA Metrics container for collapse simulation
     */
    public static class SLAMetrics {
        public int totalProducts = 0;
        public int assignedProducts = 0;
        public int unassignedProducts = 0;
        public int productsOnTime = 0;
        public int productsLate = 0;
        public double slaCompliancePercentage = 0.0;
        public double slaViolationPercentage = 0.0;
        
        // Continental breakdown
        public int continentalTotal = 0;
        public int continentalOnTime = 0;
        public int continentalLate = 0;
        public double continentalCompliance = 0.0;
        
        // Intercontinental breakdown
        public int intercontinentalTotal = 0;
        public int intercontinentalOnTime = 0;
        public int intercontinentalLate = 0;
        public double intercontinentalCompliance = 0.0;
        
        // Detailed violations (limited to first 100 for performance)
        public List<SLAViolationInfo> violations = new ArrayList<>();
    }
    
    /**
     * NEW: Individual SLA violation info
     */
    public static class SLAViolationInfo {
        public String orderName;
        public String originContinent;
        public String destContinent;
        public boolean isContinental;
        public int slaMaxHours;
        public double actualHours;
        public double hoursOverdue;
        public LocalDateTime orderDate;
        public LocalDateTime deadline;
        public LocalDateTime estimatedDelivery;
    }

    /**
     * NEW: Calculate SLA metrics for collapse simulation
     * Analyzes all assigned products and determines if they meet SLA:
     * - Continental: 48 hours (2 days)
     * - Intercontinental: 72 hours (3 days)
     */
    public SLAMetrics getSLAMetrics() {
        SLAMetrics metrics = new SLAMetrics();
        
        Map<ProductSchema, ArrayList<FlightSchema>> productSolution = getProductLevelSolution();
        
        if (productSolution == null || productSolution.isEmpty()) {
            System.out.println("No product solution available for SLA calculation");
            return metrics;
        }
        
        int violationCount = 0;
        final int MAX_VIOLATIONS_TO_TRACK = 100;
        
        for (Map.Entry<ProductSchema, ArrayList<FlightSchema>> entry : productSolution.entrySet()) {
            ProductSchema product = entry.getKey();
            ArrayList<FlightSchema> route = entry.getValue();
            
            metrics.totalProducts++;
            
            // Find the order for this product to get SLA info
            OrderSchema order = findOrderForProduct(product);
            if (order == null) {
                metrics.unassignedProducts++;
                continue;
            }
            
            if (route == null || route.isEmpty()) {
                metrics.unassignedProducts++;
                continue;
            }
            
            metrics.assignedProducts++;
            
            // Determine if continental or intercontinental
            CitySchema origin = order.getCurrentLocation();
            CitySchema destination = order.getDestinationCitySchema();
            
            if (origin == null || destination == null) {
                // Can't determine, assume continental
                metrics.continentalTotal++;
                metrics.continentalOnTime++;
                metrics.productsOnTime++;
                continue;
            }
            
            boolean isContinental = origin.getContinent() == destination.getContinent();
            int slaMaxHours = isContinental ? 48 : 72;  // 2 days or 3 days
            
            // Calculate actual delivery time
            double actualDeliveryHours = calculateRouteDeliveryTime(route);
            
            // Add connection time (2 hours per connection)
            if (route.size() > 1) {
                actualDeliveryHours += (route.size() - 1) * 2.0;
            }
            
            // Update continental/intercontinental breakdown
            if (isContinental) {
                metrics.continentalTotal++;
            } else {
                metrics.intercontinentalTotal++;
            }
            
            // Check SLA compliance
            boolean isOnTime = actualDeliveryHours <= slaMaxHours;
            
            if (isOnTime) {
                metrics.productsOnTime++;
                if (isContinental) {
                    metrics.continentalOnTime++;
                } else {
                    metrics.intercontinentalOnTime++;
                }
            } else {
                metrics.productsLate++;
                if (isContinental) {
                    metrics.continentalLate++;
                } else {
                    metrics.intercontinentalLate++;
                }
                
                // Track violation details (limited)
                if (violationCount < MAX_VIOLATIONS_TO_TRACK) {
                    SLAViolationInfo violation = new SLAViolationInfo();
                    violation.orderName = order.getName();
                    violation.originContinent = origin.getContinent() != null ? origin.getContinent().toString() : "Unknown";
                    violation.destContinent = destination.getContinent() != null ? destination.getContinent().toString() : "Unknown";
                    violation.isContinental = isContinental;
                    violation.slaMaxHours = slaMaxHours;
                    violation.actualHours = actualDeliveryHours;
                    violation.hoursOverdue = actualDeliveryHours - slaMaxHours;
                    violation.orderDate = order.getOrderDate();
                    violation.deadline = order.getDeliveryDeadline();
                    
                    // Calculate estimated delivery time
                    if (order.getOrderDate() != null) {
                        violation.estimatedDelivery = order.getOrderDate().plusHours((long) actualDeliveryHours);
                    }
                    
                    metrics.violations.add(violation);
                    violationCount++;
                }
            }
        }
        
        // Calculate percentages
        if (metrics.assignedProducts > 0) {
            metrics.slaCompliancePercentage = (metrics.productsOnTime * 100.0) / metrics.assignedProducts;
            metrics.slaViolationPercentage = (metrics.productsLate * 100.0) / metrics.assignedProducts;
        }
        
        if (metrics.continentalTotal > 0) {
            metrics.continentalCompliance = (metrics.continentalOnTime * 100.0) / metrics.continentalTotal;
        }
        
        if (metrics.intercontinentalTotal > 0) {
            metrics.intercontinentalCompliance = (metrics.intercontinentalOnTime * 100.0) / metrics.intercontinentalTotal;
        }
        
        // Log summary
        System.out.println("\n=== SLA METRICS SUMMARY ===");
        System.out.println("Total products: " + metrics.totalProducts);
        System.out.println("Assigned: " + metrics.assignedProducts);
        System.out.println("On Time: " + metrics.productsOnTime + " (" + String.format("%.1f", metrics.slaCompliancePercentage) + "%)");
        System.out.println("Late (SLA Violated): " + metrics.productsLate + " (" + String.format("%.1f", metrics.slaViolationPercentage) + "%)");
        System.out.println("\nContinental (≤48h): " + metrics.continentalOnTime + "/" + metrics.continentalTotal + 
                          " (" + String.format("%.1f", metrics.continentalCompliance) + "% on time)");
        System.out.println("Intercontinental (≤72h): " + metrics.intercontinentalOnTime + "/" + metrics.intercontinentalTotal + 
                          " (" + String.format("%.1f", metrics.intercontinentalCompliance) + "% on time)");
        System.out.println("===========================\n");
        
        return metrics;
    }
    
    /**
     * Find the original order for a product
     */
    private OrderSchema findOrderForProduct(ProductSchema product) {
        if (product == null || originalOrderSchemas == null) {
            return null;
        }
        
        // Try to find by order ID
        Integer orderId = product.getOrderId();
        if (orderId != null) {
            for (OrderSchema order : originalOrderSchemas) {
                if (order.getId() != null && order.getId().equals(orderId)) {
                    return order;
                }
            }
        }
        
        // Try to find by name pattern (Order-XXX-YYY)
        String productName = product.getName();
        if (productName != null) {
            for (OrderSchema order : originalOrderSchemas) {
                if (order.getName() != null && productName.contains(order.getName())) {
                    return order;
                }
            }
        }
        
        return null;
    }
    
    /**
     * Calculate total delivery time for a route in hours
     */
    private double calculateRouteDeliveryTime(ArrayList<FlightSchema> route) {
        if (route == null || route.isEmpty()) {
            return 0.0;
        }
        
        double totalHours = 0.0;
        for (FlightSchema flight : route) {
            // transportTime is primitive double, always valid
            totalHours += flight.getTransportTime();
        }
        
        return totalHours;
    }

    /**
     * Generate flight instances for a route
     * Creates specific flight departures with timestamps for each hop in the route
     *
     * @param route List of flights in the route
     * @param orderCreationTime When the order was created (used as starting point)
     * @return List of flight instances with departure times
     */
    private ArrayList<FlightInstanceSchema> generateFlightInstances(ArrayList<FlightSchema> route, LocalDateTime orderCreationTime) {
        ArrayList<FlightInstanceSchema> instances = new ArrayList<>();

        if (route == null || route.isEmpty()) {
            return instances;
        }

        // Start from order creation time
        LocalDateTime currentTime = orderCreationTime;
        int dayCounter = 0;

        for (int i = 0; i < route.size(); i++) {
            FlightSchema flight = route.get(i);

            // Get the real departure time from the flight (from flights.txt)
            LocalTime flightDepartureTime = flight.getDepartureTime();
            if (flightDepartureTime == null) {
                // Fallback to default if not available
                flightDepartureTime = LocalTime.of(0, 0);
            }

            // Find the next occurrence of this flight's departure time after currentTime
            LocalDateTime candidateDeparture = currentTime.toLocalDate()
                .atTime(flightDepartureTime);

            // If the candidate is before or equal to current time, move to next day
            if (!candidateDeparture.isAfter(currentTime)) {
                candidateDeparture = candidateDeparture.plusDays(1);
            }

            LocalDateTime departureTime = candidateDeparture;

            // Update day counter
            dayCounter = (int) ChronoUnit.DAYS.between(orderCreationTime.toLocalDate(), departureTime.toLocalDate());

            // FIX: Calcular arrival time usando hora REAL del vuelo (igual que SimulationTimeService)
            // ANTES: usaba transportTimeDays * 24 horas, causando inconsistencias
            LocalTime flightArrivalTime = flight.getArrivalTime();
            if (flightArrivalTime == null) {
                // Fallback: usar transportTimeDays si no hay hora de llegada
                Double transportTimeDays = flight.getTransportTimeDays();
                if (transportTimeDays == null || transportTimeDays <= 0) {
                    transportTimeDays = 0.5; // Default to 12 hours
                }
                long transportHours = (long) (transportTimeDays * 24);
                flightArrivalTime = departureTime.plusHours(transportHours).toLocalTime();
            }

            // Calcular arrival datetime considerando cruce de medianoche
            LocalDateTime arrivalTime;
            if (flightArrivalTime.isBefore(flightDepartureTime)) {
                // Vuelo cruza medianoche: llega al día siguiente
                arrivalTime = departureTime.plusDays(1).with(flightArrivalTime);
            } else {
                // Mismo día
                arrivalTime = departureTime.with(flightArrivalTime);
            }

            // Handle null max capacity
            Integer maxCapacity = flight.getMaxCapacity();
            if (maxCapacity == null || maxCapacity <= 0) {
                maxCapacity = 300; // Default capacity
            }

            // Create flight instance
            FlightInstanceSchema instance = FlightInstanceSchema.builder()
                .baseFlightId(flight.getId())
                .baseFlight(flight)
                .departureDateTime(departureTime)
                .arrivalDateTime(arrivalTime)
                .instanceDay(dayCounter)
                .maxCapacity(maxCapacity)
                .usedCapacity(0)
                .build();

            // Generate instance ID
            instance.generateInstanceId();

            instances.add(instance);

            // Advance time for next leg: arrival + 1 hour minimum layover
            currentTime = arrivalTime.plusHours(1);

            // Update day counter based on new time
            dayCounter = (int) ChronoUnit.DAYS.between(orderCreationTime.toLocalDate(), currentTime.toLocalDate());
        }

        return instances;
    }

    /**
     * Track an order assignment or split
     * Called when an order (or part of it) is assigned to a route
     */
    private void trackOrderAssignment(String orderName, Integer quantity, ArrayList<FlightSchema> route) {
        if (!orderSplits.containsKey(orderName)) {
            orderSplits.put(orderName, new ArrayList<>());
        }

        // Find the order to get its creation time
        OrderSchema order = null;
        for (OrderSchema o : orderSchemas) {
            if (o.getName().equals(orderName)) {
                order = o;
                break;
            }
        }

        // Generate flight instances with timestamps
        ArrayList<FlightInstanceSchema> instances = new ArrayList<>();
        if (order != null && route != null && !route.isEmpty()) {
            LocalDateTime orderTime = order.getOrderDate();
            if (orderTime == null) {
                orderTime = simulationStartTime; // Fallback to simulation start
            }
            instances = generateFlightInstances(route, orderTime);
        }

        orderSplits.get(orderName).add(new OrderSplitInfo(quantity, route, instances));

        if (Constants.VERBOSE_LOGGING) {
            System.out.println("Tracked assignment: Order " + orderName + " - " + quantity + " items on " + instances.size() + " flight instances");
        }
    }

    /**
     * Inicializa el pool de paquetes no asignados para expansión ALNS
     */
    private void initializeUnassignedPool() {
        unassignedPool.clear();
        
        // Obtener la solución actual
        HashMap<OrderSchema, ArrayList<FlightSchema>> currentSolution = solution.keySet().iterator().next();
        
        // Agregar todos los paquetes no asignados al pool
        for (OrderSchema pkg : orderSchemas) {
            if (!currentSolution.containsKey(pkg)) {
                unassignedPool.add(pkg);
            }
        }
        
        if (Constants.VERBOSE_LOGGING) {
            System.out.println("Pool de no asignados inicializado: " + unassignedPool.size() + " paquetes disponibles para expansión ALNS");
        }
    }
    
    /**
     * Expande la lista de paquetes a reparar con algunos del pool no asignado
     * para permitir que ALNS explore la asignación de nuevos paquetes
     */
    private ArrayList<Map.Entry<OrderSchema, ArrayList<FlightSchema>>> expandWithUnassignedPackages(
            ArrayList<Map.Entry<OrderSchema, ArrayList<FlightSchema>>> destroyedPackages, int maxToAdd) {
        
        if (unassignedPool.isEmpty() || maxToAdd <= 0) {
            return destroyedPackages;
        }
        
        ArrayList<Map.Entry<OrderSchema, ArrayList<FlightSchema>>> expandedList = new ArrayList<>(destroyedPackages);
        
        // Determinar probabilidad de expansión según pool no asignado
        double poolRatio = (double) unassignedPool.size() / orderSchemas.size();
        double expansionProbability;
        
        if (poolRatio > 0.5) {
            // Si >50% no asignados: expansión MUY AGRESIVA
            expansionProbability = diversificationMode ? 0.9 : 0.7; // 90%/70%
        } else if (poolRatio > 0.3) {
            // Si >30% no asignados: expansión AGRESIVA  
            expansionProbability = diversificationMode ? 0.7 : 0.5; // 70%/50%
        } else if (poolRatio > 0.1) {
            // Si >10% no asignados: expansión MODERADA
            expansionProbability = diversificationMode ? 0.5 : 0.3; // 50%/30%
        } else {
            // Si <10% no asignados: expansión CONSERVADORA
            expansionProbability = diversificationMode ? 0.3 : 0.1; // 30%/10%
        }
        
        if (random.nextDouble() < expansionProbability) {
            // OPTIMIZED: PriorityQueue ya está ordenada por urgencia (Recomendación #4)
            // No necesitamos ordenar, solo convertir a lista para acceso por índice
            ArrayList<OrderSchema> sortedUnassigned = new ArrayList<>(unassignedPool);
            
            // Determinar cantidad a agregar según pool no asignado
            int dynamicMaxToAdd;
            if (poolRatio > 0.5) {
                dynamicMaxToAdd = Math.min(200, unassignedPool.size()); // Hasta 200 si >50% no asignados
            } else if (poolRatio > 0.3) {
                dynamicMaxToAdd = Math.min(100, unassignedPool.size()); // Hasta 100 si >30% no asignados
            } else {
                dynamicMaxToAdd = Math.min(50, unassignedPool.size());  // Hasta 50 si pocos no asignados
            }
            
            int toAdd = Math.min(dynamicMaxToAdd, sortedUnassigned.size());
            
            for (int i = 0; i < toAdd; i++) {
                OrderSchema pkg = sortedUnassigned.get(i);
                // Crear entrada con ruta vacía (será determinada por reparación)
                expandedList.add(new java.util.AbstractMap.SimpleEntry<>(pkg, new ArrayList<>()));
            }
            
            if (Constants.VERBOSE_LOGGING) {
                System.out.println("Expansión ALNS: Agregando " + toAdd + " paquetes no asignados para exploración" +
                                 " (Pool: " + unassignedPool.size() + "/" + orderSchemas.size() +
                                 " = " + String.format("%.1f%%", poolRatio * 100) + 
                                 ", Prob: " + String.format("%.0f%%", expansionProbability * 100) + ")");
            }
        }
        
        return expandedList;
    }
    
    /**
     * Actualiza el pool de paquetes no asignados basado en la solución actual
     */
    private void updateUnassignedPool(HashMap<OrderSchema, ArrayList<FlightSchema>> currentSolution) {
        unassignedPool.clear();
        
        // Agregar todos los paquetes no asignados al pool
        for (OrderSchema pkg : orderSchemas) {
            if (!currentSolution.containsKey(pkg)) {
                unassignedPool.add(pkg);
            }
        }
    }
    
    /**
     * Aplica diversificación extrema cuando el algoritmo se estanca
     * Utiliza diferentes estrategias para escapar de óptimos locales
     */
    private HashMap<OrderSchema, ArrayList<FlightSchema>> applyExtremeDiversification(
            HashMap<OrderSchema, ArrayList<FlightSchema>> currentSolution, int iteration) {
        
        System.out.println("\n🚀 ACTIVANDO DIVERSIFICACIÓN EXTREMA 🚀");
        System.out.println("Iteración " + iteration + ": " + iterationsSinceSignificantImprovement + 
                         " iteraciones sin mejora significativa");
        System.out.println("Restart #" + (restartCount + 1) + "/" + Constants.MAX_RESTARTS);
        
        // Seleccionar estrategia de restart basada en el número de restart
        HashMap<OrderSchema, ArrayList<FlightSchema>> newSolution;
        
        switch (restartCount % 3) {
            case 0:
                System.out.println("Estrategia: DESTRUCCIÓN EXTREMA (80%)");
                newSolution = extremeDestruction(currentSolution);
                break;
                
            case 1:
                System.out.println("Estrategia: RESTART GREEDY COMPLETO");
                newSolution = greedyRestart();
                break;
                
            case 2:
                System.out.println("Estrategia: RESTART HÍBRIDO");
                newSolution = hybridRestart(currentSolution);
                break;
                
            default:
                System.out.println("Estrategia: DESTRUCCIÓN EXTREMA (fallback)");
                newSolution = extremeDestruction(currentSolution);
                break;
        }
        
        // Actualizar contadores
        restartCount++;
        iterationsSinceSignificantImprovement = 0;

        // Reiniciar temperatura para mayor exploración
        temperature = 100.0;

        // NEW: Clear RouteValidator caches after significant solution changes
        // This ensures cached validations don't interfere with new solution exploration
        routeValidator.clearCaches();
        if (Constants.VERBOSE_LOGGING) {
            System.out.println("RouteValidator caches cleared for fresh exploration");
        }

        // Actualizar el pool de no asignados
        updateUnassignedPool(newSolution);
        
        int newWeight = calculateSolutionWeight(newSolution);
        System.out.println("Peso después de diversificación extrema: " + newWeight);
        System.out.println("Paquetes asignados: " + newSolution.size() + "/" + orderSchemas.size());
        System.out.println("=== FIN DIVERSIFICACIÓN EXTREMA ===\n");
        
        return newSolution;
    }
    
    /**
     * Estrategia 1: Destrucción extrema (80% de la solución)
     */
    private HashMap<OrderSchema, ArrayList<FlightSchema>> extremeDestruction(HashMap<OrderSchema, ArrayList<FlightSchema>> currentSolution) {
        HashMap<OrderSchema, ArrayList<FlightSchema>> newSolution = new HashMap<>(currentSolution);
        
        // Destruir 80% de los paquetes aleatoriamente
        ArrayList<OrderSchema> assignedOrderSchemas = new ArrayList<>(newSolution.keySet());
        Collections.shuffle(assignedOrderSchemas, random);
        
        int packagesToRemove = (int)(assignedOrderSchemas.size() * Constants.EXTREME_DESTRUCTION_RATIO);
        
        for (int i = 0; i < packagesToRemove && i < assignedOrderSchemas.size(); i++) {
            newSolution.remove(assignedOrderSchemas.get(i));
        }
        
        System.out.println("Destruidos " + packagesToRemove + "/" + assignedOrderSchemas.size() + " paquetes");
        
        // Reconstruir estado de capacidades
        rebuildCapacitiesFromSolution(newSolution);
        rebuildWarehousesFromSolution(newSolution);
        
        return newSolution;
    }
    
    /**
     * Estrategia 2: Restart greedy completo con estrategia diferente
     */
    private HashMap<OrderSchema, ArrayList<FlightSchema>> greedyRestart() {
        // Resetear capacidades completamente
        for (FlightSchema f : flightSchemas) {
            f.setUsedCapacity(0);
        }
        initializeWarehouseOccupancy();
        
        HashMap<OrderSchema, ArrayList<FlightSchema>> newSolution = new HashMap<>();
        
        // Usar una estrategia de ordenamiento diferente para diversificación
        ArrayList<OrderSchema> sortedOrderSchemas = new ArrayList<>(orderSchemas);
        
        // Alternar entre diferentes estrategias según el número de restart
        switch (restartCount % 4) {
            case 0:
                // Por prioridad inversa (menor prioridad primero para explorar diferente)
                sortedOrderSchemas.sort((p1, p2) -> {
                    double priority1 = p1.getPriority();
                    double priority2 = p2.getPriority();
                    return Double.compare(priority1, priority2); // Menor prioridad primero
                });
                System.out.println("Ordenamiento: Prioridad inversa");
                break;
                
            case 1:
                // Por número de productos (más productos primero)
                sortedOrderSchemas.sort((p1, p2) -> {
                    int products1 = p1.getProductSchemas() != null ? p1.getProductSchemas().size() : 1;
                    int products2 = p2.getProductSchemas() != null ? p2.getProductSchemas().size() : 1;
                    return Integer.compare(products2, products1);
                });
                System.out.println("Ordenamiento: Más productos primero");
                break;
                
            case 2:
                // Por distancia (más lejanos primero)
                sortedOrderSchemas.sort((p1, p2) -> {
                    boolean p1SameContinent = p1.getCurrentLocation().getContinent() == p1.getDestinationCitySchema().getContinent();
                    boolean p2SameContinent = p2.getCurrentLocation().getContinent() == p2.getDestinationCitySchema().getContinent();
                    return Boolean.compare(p1SameContinent, p2SameContinent); // Intercontinentales primero
                });
                System.out.println("Ordenamiento: Intercontinentales primero");
                break;
                
            case 3:
                // Completamente aleatorio
                Collections.shuffle(sortedOrderSchemas, random);
                System.out.println("Ordenamiento: Aleatorio");
                break;
        }
        
        // Asignar paquetes con estrategia greedy
        int assigned = 0;
        for (OrderSchema pkg : sortedOrderSchemas) {
            ArrayList<FlightSchema> bestRoute = findBestRouteWithTimeWindows(pkg, newSolution);
            if (bestRoute != null) {
                int productCount = pkg.getProductSchemas() != null ? pkg.getProductSchemas().size() : 1;
                if (canAssignWithSpaceOptimization(pkg, bestRoute, newSolution)) {
                    newSolution.put(pkg, bestRoute);
                    updateFlightCapacities(bestRoute, productCount);
                    
                    AirportSchema destinationAirportSchema = getAirportByCity(pkg.getDestinationCitySchema());
                    if (destinationAirportSchema != null) {
                        incrementWarehouseOccupancy(destinationAirportSchema, productCount);
                    }
                    assigned++;
                }
            }
        }
        
        System.out.println("Restart greedy: " + assigned + "/" + orderSchemas.size() + " paquetes asignados");
        return newSolution;
    }
    
    /**
     * Estrategia 3: Restart híbrido (combina elementos de diferentes soluciones)
     */
    private HashMap<OrderSchema, ArrayList<FlightSchema>> hybridRestart(HashMap<OrderSchema, ArrayList<FlightSchema>> currentSolution) {
        // Mantener solo los 30% mejores paquetes de la solución actual
        HashMap<OrderSchema, ArrayList<FlightSchema>> newSolution = new HashMap<>();
        
        // Ordenar paquetes por calidad de ruta (directos y con buen tiempo)
        ArrayList<Map.Entry<OrderSchema, ArrayList<FlightSchema>>> sortedEntries = new ArrayList<>(currentSolution.entrySet());
        sortedEntries.sort((e1, e2) -> {
            try {
                int score1 = calculateRouteQuality(e1.getKey(), e1.getValue());
                int score2 = calculateRouteQuality(e2.getKey(), e2.getValue());
                
                // Comparación principal por calidad
                int qualityComparison = Integer.compare(score2, score1); // Mejores primero
                if (qualityComparison != 0) return qualityComparison;
                
                // Criterio de desempate 1: Por número de vuelos (menos es mejor)
                int flightCountComparison = Integer.compare(e1.getValue().size(), e2.getValue().size());
                if (flightCountComparison != 0) return flightCountComparison;
                
                // Criterio de desempate 2: Por prioridad del paquete (mayor es mejor)
                int priorityComparison = Double.compare(e2.getKey().getPriority(), e1.getKey().getPriority());
                if (priorityComparison != 0) return priorityComparison;
                
                // FIXED: Criterio de desempate final por getId() en vez de hashCode()
                // Recomendación #3: getId() es más estable y predecible
                return Integer.compare(e1.getKey().getId(), e2.getKey().getId());
                
            } catch (Exception ex) {
                // Fallback seguro: usar solo propiedades básicas
                System.out.println("Warning: Error en comparación de calidad, usando fallback");
                return Integer.compare(e1.getValue().size(), e2.getValue().size());
            }
        });
        
        // Mantener el 30% mejor
        int packagesToKeep = (int)(sortedEntries.size() * 0.3);
        for (int i = 0; i < packagesToKeep && i < sortedEntries.size(); i++) {
            Map.Entry<OrderSchema, ArrayList<FlightSchema>> entry = sortedEntries.get(i);
            newSolution.put(entry.getKey(), entry.getValue());
        }
        
        System.out.println("Híbrido: Manteniendo " + packagesToKeep + " mejores paquetes, " +
                         "regenerando " + (currentSolution.size() - packagesToKeep));
        
        // Reconstruir capacidades basadas en lo que se mantuvo
        rebuildCapacitiesFromSolution(newSolution);
        rebuildWarehousesFromSolution(newSolution);
        
        return newSolution;
    }
    
    /**
     * Calcula la calidad de una ruta (mayor es mejor)
     * VERSIÓN ESTABLE: Solo usa propiedades intrínsecas para comparación consistente
     */
    private int calculateRouteQuality(OrderSchema pkg, ArrayList<FlightSchema> route) {
        if (route == null || route.isEmpty()) return 0;
        
        int score = 0;
        
        // Bonus por rutas directas (propiedad intrínseca)
        if (route.size() == 1) score += 1000;
        else if (route.size() == 2) score += 500;
        else score += 100; // Penalizar rutas muy largas
        
        // Calcular tiempo total de ruta (propiedad intrínseca)
        double totalTime = 0;
        for (FlightSchema flightSchema : route) {
            totalTime += flightSchema.getTransportTime();
        }
        if (route.size() > 1) {
            totalTime += (route.size() - 1) * 2.0; // Tiempo de conexión
        }
        
        // Bonus basado en tiempo total (menos tiempo = mejor)
        // Usar escala que mantenga valores positivos
        score += Math.max(0, 2000 - (int)(totalTime * 10));
        
        // Bonus por número de productos (más productos = mejor utilización)
        int productCount = pkg.getProductSchemas() != null ? pkg.getProductSchemas().size() : 1;
        score += productCount * 10;
        
        // Bonus por prioridad del paquete (propiedad intrínseca)
        score += (int)(pkg.getPriority() * 50);
        
        // Bonus por rutas continentales vs intercontinentales (propiedad intrínseca)
        boolean sameContinentRoute = pkg.getCurrentLocation().getContinent() == 
                                   pkg.getDestinationCitySchema().getContinent();
        if (sameContinentRoute) {
            score += 200; // Rutas continentales son más eficientes
        } else {
            score += 100; // Rutas intercontinentales son más complejas pero necesarias
        }
        
        // Asegurar que siempre retorna un valor positivo y consistente
        return Math.max(1, score);
    }
    
    /**
     * Ejecuta el algoritmo ALNS (Adaptive Large Neighborhood Search)
     */
    private void runALNSAlgorithm() {
      // Tiempo de inicio para tracking de ejecución total
      long algorithmStartTime = System.currentTimeMillis();

      // Obtener la solución actual y su peso
      HashMap<OrderSchema, ArrayList<FlightSchema>> currentSolution = null;
      int currentWeight = Integer.MAX_VALUE;
      
      for (Map.Entry<HashMap<OrderSchema, ArrayList<FlightSchema>>, Integer> entry : solution.entrySet()) {
          currentSolution = new HashMap<>(entry.getKey());
          currentWeight = entry.getValue();
          break;
      }
      
      if (currentSolution == null) {
        if (Constants.VERBOSE_LOGGING) {
          System.out.println("Error: No se pudo obtener la solución inicial");
        }
        return;
      }
      
      System.out.println("Peso de solución inicial: " + currentWeight);
      
      iterationsSinceSignificantImprovement = 0;
      
      int bestWeight = currentWeight;
      int improvements = 0;
      int noImprovementCount = 0;
      
      for (int iteration = 0; iteration < maxIterations; iteration++) {
          // Log de iteración solo si es verboso o es múltiplo del intervalo
          if (iteration % Constants.LOG_ITERATION_INTERVAL == 0) {
              System.out.println("ALNS Iteración " + iteration + "/" + maxIterations);
          }
          
          // Seleccionar operadores basado en pesos
          int[] selectedOps = selectOperators();
          int destructionOp = selectedOps[0];
          int repairOp = selectedOps[1];
          
          // Log de operadores solo en modo verboso
          if (Constants.VERBOSE_LOGGING) {
              System.out.println("  Operadores seleccionados: Destrucción=" + destructionOp + ", Reparación=" + repairOp);
          }
          
          // Crear copia de la solución actual
          HashMap<OrderSchema, ArrayList<FlightSchema>> tempSolution = new HashMap<>(currentSolution);
          
          // PATCH: Crear snapshots completos antes de modificar
          Map<FlightSchema, Integer> capacitySnapshot = snapshotCapacities();
          Map<AirportSchema, Integer> warehouseSnapshot = snapshotWarehouses();
          
          // Aplicar operador de destrucción
          if (Constants.VERBOSE_LOGGING) {
              System.out.println("  Aplicando operador de destrucción...");
          }
          long startTime = System.currentTimeMillis();
          ALNSDestruction.DestructionResult destructionResult = applyDestructionOperator(
              tempSolution, destructionOp);
          long endTime = System.currentTimeMillis();
          
          if (Constants.VERBOSE_LOGGING) {
              System.out.println("  Operador de destrucción completado en " + (endTime - startTime) + "ms");
          }
          
          if (destructionResult == null || destructionResult.getDestroyedPackages().isEmpty()) {
              if (Constants.VERBOSE_LOGGING) {
                  System.out.println("  No se pudo destruir nada, continuando...");
              }
              continue; // No se pudo destruir nada
          }
          
          if (Constants.VERBOSE_LOGGING) {
              System.out.println("  Paquetes destruidos: " + destructionResult.getDestroyedPackages().size());
          }
          
          // PATCH: Usar solución parcial de destrucción y reconstruir estado
          tempSolution = new HashMap<>(destructionResult.getPartialSolution());
          rebuildCapacitiesFromSolution(tempSolution);
          rebuildWarehousesFromSolution(tempSolution);
          
          // NUEVO: Expandir con paquetes no asignados para exploración
          ArrayList<Map.Entry<OrderSchema, ArrayList<FlightSchema>>> expandedPackages =
              expandWithUnassignedPackages(destructionResult.getDestroyedPackages(), 100);
          
          // Aplicar operador de reparación con lista expandida
          ALNSRepair.RepairResult repairResult = applyRepairOperator(
              tempSolution, repairOp, expandedPackages);
          
          if (repairResult == null || !repairResult.isSuccess()) {
              // PATCH: Restaurar snapshots si falla la reparación
              restoreCapacities(capacitySnapshot);
              restoreWarehouses(warehouseSnapshot);
              continue; // No se pudo reparar
          }
          
          // PATCH: Usar solución reparada y reconstruir estado
          tempSolution = new HashMap<>(repairResult.getRepairedSolution());
          rebuildCapacitiesFromSolution(tempSolution);
          rebuildWarehousesFromSolution(tempSolution);
          
          // Evaluar nueva solución
          int tempWeight = calculateSolutionWeight(tempSolution);
          
          // Actualizar contador de uso
          operatorUsage[destructionOp][repairOp]++;
          
          // Criterio de aceptación mejorado con múltiples niveles de recompensa
          boolean accepted = false;
          double improvementRatio = 0.0;
          
          if (tempWeight > currentWeight) {
              improvementRatio = (double)(tempWeight - currentWeight) / Math.max(currentWeight, 1);
              currentSolution = tempSolution;
              currentWeight = tempWeight;
              accepted = true;

              if (tempWeight > bestWeight) {
                  // Nueva mejor solución global
                  bestWeight = tempWeight;
                  bestSolution.clear();
                  bestSolution.put(new HashMap<>(currentSolution), currentWeight);
                  operatorScores[destructionOp][repairOp] += 300; // Recompensa máxima
                  improvements++;
                  noImprovementCount = 0;
                  lastImprovementIteration = iteration;
                  stagnationCounter = 0;
                  diversificationMode = false; // Volver a intensificación después de mejora
                  updateUnassignedPool(currentSolution); // Actualizar pool de no asignados

                  // EARLY STOPPING: Si todos los paquetes están asignados, terminar algoritmo
                  if (unassignedPool.isEmpty()) {
                      long executionTime = System.currentTimeMillis() - algorithmStartTime;
                      System.out.println("\n========================================");
                      System.out.println("¡EARLY STOPPING! Todos los paquetes asignados exitosamente");
                      System.out.println("Iteración: " + iteration + " / " + maxIterations);
                      System.out.println("Peso final: " + bestWeight);
                      System.out.println("Total de órdenes: " + orderSchemas.size());
                      System.out.println("Mejoras realizadas: " + improvements);
                      System.out.println("Tiempo total de ejecución: " + executionTime + " ms (" +
                                       String.format("%.2f", executionTime/1000.0) + " segundos)");
                      System.out.println("========================================\n");
                      break; // Salir del bucle de iteraciones - solución completa encontrada
                  }

                  // CONTROL DE DIVERSIFICACIÓN EXTREMA: Verificar si es mejora significativa
                  if (improvementRatio >= Constants.SIGNIFICANT_IMPROVEMENT_THRESHOLD / 100.0) {
                      // Mejora significativa (≥0.1%) - resetear contador de estancamiento
                      iterationsSinceSignificantImprovement = 0;
                  } else {
                      // Mejora mínima - continuar contando estancamiento
                      iterationsSinceSignificantImprovement++;
                  }
                  
                  // Siempre mostrar mejoras en la mejor solución global
                  System.out.println("Iteración " + iteration + ": ¡Nueva mejor solución! Peso: " + bestWeight + 
                                  " (mejora: " + String.format("%.2f%%", improvementRatio * 100) + ")" +
                                  " | No asignados: " + unassignedPool.size());
              } else if (improvementRatio > 0.05) {
                  // Mejora significativa (>5%)
                  operatorScores[destructionOp][repairOp] += 100;
                  noImprovementCount = Math.max(0, noImprovementCount - 5);
                  updateUnassignedPool(currentSolution); // Actualizar pool de no asignados
              } else if (improvementRatio > 0.01) {
                  // Mejora moderada (1-5%)
                  operatorScores[destructionOp][repairOp] += 50;
                  noImprovementCount = Math.max(0, noImprovementCount - 2);
                  updateUnassignedPool(currentSolution); // Actualizar pool de no asignados
              } else {
                  // Mejora pequeña (<1%)
                  operatorScores[destructionOp][repairOp] += 25;
                  updateUnassignedPool(currentSolution); // Actualizar pool de no asignados
              }
          } else {
              // Simulated Annealing con ajuste por calidad de la solución
              double delta = tempWeight - currentWeight;
              double adjustedTemp = temperature * (1.0 + 0.1 * Math.random()); // Pequeña variación
              double probability = Math.exp(delta / adjustedTemp);
              
              if (random.nextDouble() < probability) {
                  currentSolution = tempSolution;
                  currentWeight = tempWeight;
                  accepted = true;
                  updateUnassignedPool(currentSolution); // Actualizar pool de no asignados
                  operatorScores[destructionOp][repairOp] += 15; // Recompensa menor por exploración
                  noImprovementCount++;
              } else {
                  operatorScores[destructionOp][repairOp] += 5; // Recompensa mínima por intentar
                  noImprovementCount++;
              }
              
              // INCREMENTAR CONTADOR si no hubo mejora
              if (!accepted) {
                  iterationsSinceSignificantImprovement++;
              }
          }
          
          // PATCH: Restaurar snapshots si no se acepta la solución
          if (!accepted) {
              restoreCapacities(capacitySnapshot);
              restoreWarehouses(warehouseSnapshot);
              noImprovementCount++;
          }
          // NOTA: Si se acepta, tempSolution ya tiene el estado correcto reconstruido
          
          // Manejar diversificación vs intensificación
          stagnationCounter = iteration - lastImprovementIteration;
          if (stagnationCounter > diversificationThreshold && !diversificationMode) {
              diversificationMode = true;
              diversificationFactor = 1.5; // Aumentar destrucción
              temperature *= 2.0; // Aumentar temperatura para más exploración
              if (Constants.VERBOSE_LOGGING) {
                  System.out.println("Iteración " + iteration + ": Activando modo DIVERSIFICACIÓN");
              }
          } else if (diversificationMode && stagnationCounter <= diversificationThreshold / 2) {
              diversificationMode = false;
              diversificationFactor = 1.0; // Destrucción normal
              if (Constants.VERBOSE_LOGGING) {
                  System.out.println("Iteración " + iteration + ": Volviendo a modo INTENSIFICACIÓN");
              }
          }
          
          // CONTROL DE DIVERSIFICACIÓN EXTREMA
          if (iterationsSinceSignificantImprovement >= Constants.STAGNATION_THRESHOLD_FOR_RESTART && 
              restartCount < Constants.MAX_RESTARTS) {
              
              // Aplicar diversificación extrema para escapar del óptimo local
              currentSolution = applyExtremeDiversification(currentSolution, iteration);
              currentWeight = calculateSolutionWeight(currentSolution);
              
              // Actualizar la mejor solución si la diversificación extrema mejoró
              if (currentWeight > bestWeight) {
                  bestWeight = currentWeight;
                  bestSolution.clear();
                  bestSolution.put(new HashMap<>(currentSolution), currentWeight);
                  improvements++;
                  System.out.println("🎉 ¡Diversificación extrema encontró mejor solución! Peso: " + bestWeight);
              }
              
              // Reconstruir estado después de diversificación extrema
              rebuildCapacitiesFromSolution(currentSolution);
              rebuildWarehousesFromSolution(currentSolution);
          }
          
          // Actualizar pesos cada segmentSize iteraciones
          if ((iteration + 1) % segmentSize == 0) {
              updateOperatorWeights();
              temperature *= coolingRate;
              
              // Reportar estado
              if (iteration % 100 == 0) {
                  System.out.println("Iteración " + iteration + 
                                  " | Mejor peso: " + bestWeight + 
                                  " | Temperatura: " + String.format("%.2f", temperature) +
                                  " | Modo: " + (diversificationMode ? "DIVERSIFICACIÓN" : "INTENSIFICACIÓN"));
              }
          }
          
          // Parada temprana inteligente
          if (stagnationCounter > 300) { // Más iteraciones antes de parar
              if (Constants.VERBOSE_LOGGING) {
                  System.out.println("Parada temprana en iteración " + iteration + 
                                  " (sin mejoras por " + stagnationCounter + " iteraciones)");
              }
              break;
          }
      }
      
        
        // Actualizar la solución final
        solution.clear();
        solution.putAll(bestSolution);
        
        // Siempre mostrar resumen final
        System.out.println("ALNS completado:");
        System.out.println("  Mejoras encontradas: " + improvements);
        System.out.println("  Peso final: " + bestWeight);
        System.out.println("  Diversificaciones extremas aplicadas: " + restartCount + "/" + Constants.MAX_RESTARTS);
        if (Constants.VERBOSE_LOGGING) {
            System.out.println("  Temperatura final: " + temperature);
        }
    }
    
    /**
     * Selecciona operadores de destrucción y reparación basado en sus pesos
     */
    private int[] selectOperators() {
        try {
            if (Constants.VERBOSE_LOGGING) {
                System.out.println("    Seleccionando operadores...");
            }
            
            // Selección por ruleta basada en pesos
            double totalWeight = 0.0;
            for (int i = 0; i < operatorWeights.length; i++) {
                for (int j = 0; j < operatorWeights[i].length; j++) {
                    totalWeight += operatorWeights[i][j];
                }
            }
            
            if (Constants.VERBOSE_LOGGING) {
                System.out.println("    Peso total: " + totalWeight);
            }
            double randomValue = random.nextDouble() * totalWeight;
            double cumulativeWeight = 0.0;
            
            for (int i = 0; i < operatorWeights.length; i++) {
                for (int j = 0; j < operatorWeights[i].length; j++) {
                    cumulativeWeight += operatorWeights[i][j];
                    if (randomValue <= cumulativeWeight) {
                        if (Constants.VERBOSE_LOGGING) {
                            System.out.println("    Operadores seleccionados: " + i + ", " + j);
                        }
                        return new int[]{i, j};
                    }
                }
            }
            
            // Fallback: seleccionar el primero
            if (Constants.VERBOSE_LOGGING) {
                System.out.println("    Usando fallback: 0, 0");
            }
            return new int[]{0, 0};
        } catch (Exception e) {
            // Siempre mostrar errores críticos
            System.out.println("    Error en selección de operadores: " + e.getMessage());
            e.printStackTrace();
            return new int[]{0, 0};
        }
    }
    
    /**
     * Aplica el operador de destrucción seleccionado
     */
    private ALNSDestruction.DestructionResult applyDestructionOperator(
            HashMap<OrderSchema, ArrayList<FlightSchema>> solution, int operatorIndex) {
        try {
            // Ajustar ratio de destrucción según modo de diversificación
            double adjustedRatio = Constants.DESTRUCTION_RATIO * diversificationFactor;
            int adjustedMin = (int)(Constants.DESTRUCTION_MIN_PACKAGES * diversificationFactor);
            int adjustedMax = (int)(Constants.DESTRUCTION_MAX_PACKAGES * diversificationFactor);
            
            switch (operatorIndex) {
                case 0: // Random Destroy
                    if (Constants.VERBOSE_LOGGING) {
                        System.out.println("    Ejecutando randomDestroy... (ratio: " + String.format("%.2f", adjustedRatio) + ")");
                    }
                    return destructionOperators.randomDestroy(solution, adjustedRatio, adjustedMin, adjustedMax);
                case 1: // Geographic Destroy
                    if (Constants.VERBOSE_LOGGING) {
                        System.out.println("    Ejecutando geographicDestroy... (ratio: " + String.format("%.2f", adjustedRatio) + ")");
                    }
                    return destructionOperators.geographicDestroy(solution, adjustedRatio, adjustedMin, adjustedMax);
                case 2: // Time Based Destroy
                    if (Constants.VERBOSE_LOGGING) {
                        System.out.println("    Ejecutando timeBasedDestroy... (ratio: " + String.format("%.2f", adjustedRatio) + ")");
                    }
                    return destructionOperators.timeBasedDestroy(solution, adjustedRatio, adjustedMin, adjustedMax);
                case 3: // Congested RouteSchema Destroy - OPTIMIZADO
                    if (Constants.VERBOSE_LOGGING) {
                        System.out.println("    Ejecutando congestedRouteDestroy... (ratio: " + String.format("%.2f", adjustedRatio) + ")");
                    }
                    return destructionOperators.congestedRouteDestroy(solution, adjustedRatio, adjustedMin, adjustedMax);
                default:
                    if (Constants.VERBOSE_LOGGING) {
                        System.out.println("    Ejecutando randomDestroy (default)... (ratio: " + String.format("%.2f", adjustedRatio) + ")");
                    }
                    return destructionOperators.randomDestroy(solution, adjustedRatio, adjustedMin, adjustedMax);
            }
        } catch (Exception e) {
            // Siempre mostrar errores críticos
            System.out.println("    Error en operador de destrucción: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * Aplica el operador de reparación seleccionado
     */
    private ALNSRepair.RepairResult applyRepairOperator(
            HashMap<OrderSchema, ArrayList<FlightSchema>> solution, int operatorIndex,
            ArrayList<Map.Entry<OrderSchema, ArrayList<FlightSchema>>> destroyedPackages) {
        
        // Los operadores de reparación esperan los Map.Entry completos
        switch (operatorIndex) {
            case 0: // Greedy Repair
                return repairOperators.greedyRepair(solution, destroyedPackages);
            case 1: // Regret Repair
                return repairOperators.regretRepair(solution, destroyedPackages, 2); // regretLevel = 2
            case 2: // Time Based Repair
                return repairOperators.timeBasedRepair(solution, destroyedPackages);
            case 3: // Capacity Based Repair
                return repairOperators.capacityBasedRepair(solution, destroyedPackages);
            default:
                return repairOperators.greedyRepair(solution, destroyedPackages);
        }
    }
    
    /**
     * Actualiza los pesos de los operadores basado en sus puntajes
     */
    private void updateOperatorWeights() {
        double lambda = 0.1; // Factor de aprendizaje
        
        for (int i = 0; i < operatorScores.length; i++) {
            for (int j = 0; j < operatorScores[i].length; j++) {
                if (operatorUsage[i][j] > 0) {
                    double averageScore = operatorScores[i][j] / operatorUsage[i][j];
                    operatorWeights[i][j] = (1 - lambda) * operatorWeights[i][j] + 
                                          lambda * averageScore;
                    
                    // Reiniciar puntajes y contador
                    operatorScores[i][j] = 0.0;
                    operatorUsage[i][j] = 0;
                }
            }
        }
    }
    
    /**
     * CORRECCIÓN: Crear snapshot de capacidades de vuelos
     */
    private Map<FlightSchema, Integer> snapshotCapacities() {
        Map<FlightSchema, Integer> snapshot = new HashMap<>();
        for (FlightSchema f : flightSchemas) {
            snapshot.put(f, f.getUsedCapacity());
        }
        return snapshot;
    }
    
    /**
     * CORRECCIÓN: Restaurar capacidades desde snapshot
     */
    private void restoreCapacities(Map<FlightSchema, Integer> snapshot) {
        for (FlightSchema f : flightSchemas) {
            f.setUsedCapacity(snapshot.getOrDefault(f, 0));
        }
    }
    
    /**
     * CORRECCIÓN: Reconstruir capacidades limpiamente desde una solución
     */
    private void rebuildCapacitiesFromSolution(HashMap<OrderSchema, ArrayList<FlightSchema>> solution) {
        // Primero resetear todas las capacidades
        for (FlightSchema f : flightSchemas) {
            f.setUsedCapacity(0);
        }
        
        // Luego reconstruir desde la solución
        for (Map.Entry<OrderSchema, ArrayList<FlightSchema>> entry : solution.entrySet()) {
            OrderSchema pkg = entry.getKey();
            ArrayList<FlightSchema> route = entry.getValue();
            int productCount = pkg.getProductSchemas() != null ? pkg.getProductSchemas().size() : 1;
            
            for (FlightSchema f : route) {
                f.setUsedCapacity(f.getUsedCapacity() + productCount);
            }
        }
    }
    
    /**
     * PATCH: Snapshot/restore completo de almacenes para ALNS
     */
    private Map<AirportSchema, Integer> snapshotWarehouses() {
        Map<AirportSchema, Integer> snapshot = new HashMap<>();
        for (AirportSchema airportSchema : airportSchemas) {
            snapshot.put(airportSchema, warehouseOccupancy.getOrDefault(airportSchema, 0));
        }
        return snapshot;
    }
    
    /**
     * PATCH: Restaurar ocupación de almacenes desde snapshot
     */
    private void restoreWarehouses(Map<AirportSchema, Integer> snapshot) {
        warehouseOccupancy.clear();
        warehouseOccupancy.putAll(snapshot);
    }
    
    /**
     * PATCH: Reconstruir almacenes limpiamente desde una solución
     */
    private void rebuildWarehousesFromSolution(HashMap<OrderSchema, ArrayList<FlightSchema>> solution) {
        // Resetear todas las ocupaciones
        initializeWarehouseOccupancy();
        
        // Reconstruir desde la solución
        for (Map.Entry<OrderSchema, ArrayList<FlightSchema>> entry : solution.entrySet()) {
            OrderSchema pkg = entry.getKey();
            ArrayList<FlightSchema> route = entry.getValue();
            int productCount = pkg.getProductSchemas() != null ? pkg.getProductSchemas().size() : 1;
            
            if (route == null || route.isEmpty()) {
                // Paquete ya en destino final
                AirportSchema destinationAirportSchema = getAirportByCity(pkg.getDestinationCitySchema());
                if (destinationAirportSchema != null) {
                    incrementWarehouseOccupancy(destinationAirportSchema, productCount);
                }
            } else {
                // Paquete en ruta - ocupa almacén de destino del último vuelo
                FlightSchema lastFlightSchema = route.get(route.size() - 1);
                incrementWarehouseOccupancy(lastFlightSchema.getDestinationAirportSchema(), productCount);
            }
        }
    }
    
    /**
     * PATCH: Helper para validar capacidad por cantidad de productos
     * @param route ruta de vuelos a validar
     * @param qty cantidad de productos que se quieren asignar
     * @return true si todos los vuelos de la ruta pueden acomodar qty productos adicionales
     */
    private boolean fitsCapacity(ArrayList<FlightSchema> route, int qty) {
        if (route == null || route.isEmpty()) return true;
        
        for (FlightSchema flightSchema : route) {
            if (flightSchema.getUsedCapacity() + qty > flightSchema.getMaxCapacity()) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * CHANGED: Cache robusta CitySchema→AirportSchema por nombre de ciudad
     * Evita problemas de equals/hashCode con objetos CitySchema
     */
    private void initializeCityToAirportCache() {
        cityNameToAirportCache = new HashMap<>();
        for (AirportSchema airportSchema : airportSchemas) {
            if (airportSchema.getCitySchema() != null && airportSchema.getCitySchema().getName() != null) {
                String cityKey = airportSchema.getCitySchema().getName().toLowerCase().trim();
                cityNameToAirportCache.put(cityKey, airportSchema);
            }
        }
        System.out.println("Cache inicializada: " + cityNameToAirportCache.size() + " ciudades");
    }
    
    /**
     * NEW: Inicializar T0 como mínimo orderDate o now si vacío
     */
    private void initializeT0() {
        T0 = LocalDateTime.now(); // Default fallback
        
        if (orderSchemas != null && !orderSchemas.isEmpty()) {
            LocalDateTime minOrderDate = orderSchemas.stream()
                .filter(pkg -> pkg.getOrderDate() != null)
                .map(OrderSchema::getOrderDate)
                .min(LocalDateTime::compareTo)
                .orElse(LocalDateTime.now());
            T0 = minOrderDate;
        }
        
        System.out.println("T0 inicializado: " + T0);
    }
    
    /**
     * PATCH: Unitización - expandir paquetes a unidades de producto
     * 
     * Estrategia: cada paquete con N productos se convierte en N "package units"
     * independientes, cada uno con 1 producto, permitiendo que viajen en vuelos diferentes.
     * 
     * Para desactivar: cambiar ENABLE_PRODUCT_UNITIZATION = false
     * 
     * @param originalOrderSchemas lista de paquetes originales
     * @return lista de unidades de producto (1 producto = 1 package unit)
     */
    private ArrayList<OrderSchema> expandPackagesToProductUnits(ArrayList<OrderSchema> originalOrderSchemas) {
        ArrayList<OrderSchema> productUnits = new ArrayList<>();
        
        for (OrderSchema originalPkg : originalOrderSchemas) {
            int productCount = (originalPkg.getProductSchemas() != null && !originalPkg.getProductSchemas().isEmpty())
                             ? originalPkg.getProductSchemas().size() : 1;
            
            // Crear una unidad por cada producto
            for (int i = 0; i < productCount; i++) {
                OrderSchema unit = createPackageUnit(originalPkg, i);
                productUnits.add(unit);
            }
        }
        
        return productUnits;
    }
    
    /**
     * PATCH: Crear una unidad de paquete (1 producto) a partir del paquete original
     * 
     * @param originalPkg paquete original
     * @param unitIndex índice de la unidad (0, 1, 2, ...)
     * @return nueva unidad de paquete con ID derivado y 1 producto
     */
    private OrderSchema createPackageUnit(OrderSchema originalPkg, int unitIndex) {
        OrderSchema unit = new OrderSchema();
        
        // PATCH: ID derivado usando hash para compatibilidad con int
        String unitIdString = originalPkg.getId() + "#" + unitIndex;
        unit.setId(unitIdString.hashCode());
        
        // Copiar todos los metadatos del paquete original
        unit.setName(originalPkg.getName());  // CRITICAL: Copy order name for database persistence
        unit.setCustomerSchema(originalPkg.getCustomerSchema());
        unit.setDestinationCitySchema(originalPkg.getDestinationCitySchema());
        unit.setOrderDate(originalPkg.getOrderDate());
        unit.setDeliveryDeadline(originalPkg.getDeliveryDeadline());
        unit.setStatus(originalPkg.getStatus());
        unit.setCurrentLocation(originalPkg.getCurrentLocation());
        unit.setPriority(originalPkg.getPriority());
        unit.setAssignedRouteSchema(originalPkg.getAssignedRouteSchema());
        
        // CRÍTICO: Crear lista con exactamente 1 producto
        ArrayList<ProductSchema> singleProductSchema = new ArrayList<>();
        if (originalPkg.getProductSchemas() != null && unitIndex < originalPkg.getProductSchemas().size()) {
            // Copiar el producto específico del paquete original
            ProductSchema originalProductSchema = originalPkg.getProductSchemas().get(unitIndex);
            ProductSchema productSchemaCopy = new ProductSchema();
            productSchemaCopy.setId(originalProductSchema.getId());
            productSchemaCopy.setAssignedFlight(originalProductSchema.getAssignedFlight());
            productSchemaCopy.setStatus(originalProductSchema.getStatus());
            singleProductSchema.add(productSchemaCopy);
        } else {
            // Crear un producto genérico si no existe
            ProductSchema genericProductSchema = new ProductSchema();
            String productIdString = originalPkg.getId() + "_P" + unitIndex;
            genericProductSchema.setId(productIdString.hashCode());
            singleProductSchema.add(genericProductSchema);
        }
        
        unit.setProductSchemas(singleProductSchema);
        
        return unit;
    }
    
    /**
     * CHANGED: getAirportByCity usando cache robusta por nombre
     * Eliminada dependencia de equals/hashCode de objetos CitySchema
     */
    /**
     * OPTIMIZED: Uses RouteValidator for O(1) airport lookup (Issue #4 fix)
     */
    private AirportSchema getAirportByCity(CitySchema citySchema) {
        // Delegate to RouteValidator for optimized O(1) lookup
        return routeValidator.getAirportByCity(citySchema);
    }
    
    /**
     * OPTIMIZED: Uses RouteValidator for O(1) flight lookup (Issue #4 fix)
     * Before: O(f) linear search through all flights
     * After: O(1) HashMap lookup via RouteValidator
     */
    private ArrayList<FlightSchema> findDirectRoute(CitySchema origin, CitySchema destination) {
        AirportSchema originAirportSchema = getAirportByCity(origin);
        AirportSchema destAirportSchema = getAirportByCity(destination);

        if (originAirportSchema == null || destAirportSchema == null) return null;

        // Use RouteValidator's O(1) flight lookup
        FlightSchema directFlight = routeValidator.findDirectFlight(originAirportSchema, destAirportSchema);

        if (directFlight != null) {
            ArrayList<FlightSchema> route = new ArrayList<>();
            route.add(directFlight);
            return route;
        }

        return null; // No hay vuelo directo
    }
    
    /**
     * OPTIMIZED: Uses RouteValidator with 1-hour layover enforcement (Issues #3, #4 fix)
     * This now properly validates:
     * - Minimum 1-hour layover at intermediate stops (Issue #3)
     * - All route constraints with cached validation (Issue #4)
     */
    private boolean isRouteValid(OrderSchema pkg, ArrayList<FlightSchema> route) {
        // Delegate to RouteValidator which includes:
        // - 1-hour minimum layover validation
        // - Optimized lookups (O(1))
        // - Cached deadline validation
        // - MoraPack delivery promise validation
        return routeValidator.isRouteValidWithLayoverCheck(pkg, route);
    }
    
    /**
     * FIXED: Validar capacidad de almacén para TODA la ruta (no solo destino final)
     * CRÍTICO: Recomendación #1 - Debe validar todos los aeropuertos intermedios
     */
    private boolean canAssignWithSpaceOptimization(OrderSchema pkg, ArrayList<FlightSchema> route,
                                                   HashMap<OrderSchema, ArrayList<FlightSchema>> currentSolution) {
        if (route == null || route.isEmpty()) {
            // Ruta vacía = paquete ya en destino, solo validar destino
            AirportSchema destinationAirportSchema = getAirportByCity(pkg.getDestinationCitySchema());
            if (destinationAirportSchema == null || destinationAirportSchema.getWarehouse() == null) {
                return false;
            }

            // FIXED: Exempt main warehouses (unlimited capacity)
            if (isMainWarehouse(destinationAirportSchema)) {
                return true; // Main warehouses have unlimited capacity
            }

            int productCount = pkg.getProductSchemas() != null ? pkg.getProductSchemas().size() : 1;
            int currentOccupancy = warehouseOccupancy.getOrDefault(destinationAirportSchema, 0);
            int maxCapacity = destinationAirportSchema.getWarehouse().getMaxCapacity();
            return (currentOccupancy + productCount) <= maxCapacity;
        }

        int productCount = pkg.getProductSchemas() != null ? pkg.getProductSchemas().size() : 1;

        // CRITICAL FIX: Validar TODOS los aeropuertos en la ruta (incluyendo intermedios)
        for (int i = 0; i < route.size(); i++) {
            FlightSchema flight = route.get(i);
            AirportSchema arrivalAirport = flight.getDestinationAirportSchema();

            if (arrivalAirport == null || arrivalAirport.getWarehouse() == null) {
                if (Constants.VERBOSE_LOGGING) {
                    System.out.println("Warehouse validation failed: No warehouse at " +
                                     (arrivalAirport != null ? arrivalAirport.getCitySchema().getName() : "null"));
                }
                return false;
            }
            if (isMainWarehouse(arrivalAirport)) {
                continue; // Skip capacity check for main warehouses
            }
            // Validar capacidad de este almacén
            int currentOccupancy = warehouseOccupancy.getOrDefault(arrivalAirport, 0);
            int maxCapacity = arrivalAirport.getWarehouse().getMaxCapacity();

            if ((currentOccupancy + productCount) > maxCapacity) {
                if (Constants.VERBOSE_LOGGING) {
                    System.out.println("Warehouse capacity exceeded at " + arrivalAirport.getCitySchema().getName() +
                                     ": " + (currentOccupancy + productCount) + " > " + maxCapacity);
                }
                return false;
            }
        }

        return true; // Todos los almacenes tienen capacidad
    }
    
    /**
     * PATCH: Implementar updateFlightCapacities (método crítico)
     */
    private void updateFlightCapacities(ArrayList<FlightSchema> route, int productCount) {
        for (FlightSchema flightSchema : route) {
            flightSchema.setUsedCapacity(flightSchema.getUsedCapacity() + productCount);
        }
    }
    
    /**
     * PATCH: Implementar incrementWarehouseOccupancy (método crítico)
     */
    private void incrementWarehouseOccupancy(AirportSchema airportSchema, int productCount) {
        int currentOccupancy = warehouseOccupancy.getOrDefault(airportSchema, 0);
        warehouseOccupancy.put(airportSchema, currentOccupancy + productCount);
    }

    /**
     * NEW: Attempt to split an order when it doesn't fit
     * Splits order in half and tries to assign both halves
     *
     * @param pkg Order to split
     * @param route Route to assign
     * @param currentSolution Current solution map
     * @return true if at least one split was assigned
     */
    private boolean attemptOrderSplit(OrderSchema pkg, ArrayList<FlightSchema> route,
                                     HashMap<OrderSchema, ArrayList<FlightSchema>> currentSolution) {
        // Get product count (default to order quantity if no products)
        int totalQuantity = pkg.getProductSchemas() != null && !pkg.getProductSchemas().isEmpty()
                          ? pkg.getProductSchemas().size()
                          : pkg.getQuantity();

        // Don't split if quantity is 1 or too small
        if (totalQuantity <= 1) {
            return false;
        }

        // Split in half
        int firstHalf = totalQuantity / 2;
        int secondHalf = totalQuantity - firstHalf;

        if (Constants.VERBOSE_LOGGING) {
            System.out.println("Attempting to split order " + pkg.getId() +
                             " (" + totalQuantity + " items) into " + firstHalf + " + " + secondHalf);
        }

        boolean anyAssigned = false;

        // Try to assign first half
        if (tryAssignPartialOrder(pkg, firstHalf, route, currentSolution)) {
            anyAssigned = true;
            if (Constants.VERBOSE_LOGGING) {
                System.out.println("  First half (" + firstHalf + " items) assigned successfully");
            }
        }

        // Try to assign second half (might need different route)
        if (tryAssignPartialOrder(pkg, secondHalf, route, currentSolution)) {
            anyAssigned = true;
            if (Constants.VERBOSE_LOGGING) {
                System.out.println("  Second half (" + secondHalf + " items) assigned successfully");
            }
        } else if (anyAssigned) {
            // First half was assigned but second wasn't - try alternative routes for second half
            ArrayList<FlightSchema> alternativeRoute = findBestRouteWithTimeWindows(pkg, currentSolution);
            if (alternativeRoute != null && !alternativeRoute.equals(route)) {
                if (tryAssignPartialOrder(pkg, secondHalf, alternativeRoute, currentSolution)) {
                    if (Constants.VERBOSE_LOGGING) {
                        System.out.println("  Second half assigned to alternative route");
                    }
                }
            }
        }

        return anyAssigned;
    }

    /**
     * NEW: Try to assign a partial order (split) to a route
     *
     * @param originalPkg Original order
     * @param quantity Quantity to assign in this split
     * @param route Route to assign to
     * @param currentSolution Current solution map
     * @return true if assignment succeeded
     */
    private boolean tryAssignPartialOrder(OrderSchema originalPkg, int quantity,
                                         ArrayList<FlightSchema> route,
                                         HashMap<OrderSchema, ArrayList<FlightSchema>> currentSolution) {
        if (route == null || quantity <= 0) {
            return false;
        }

        // Create a temporary order schema to test capacity with reduced quantity
        OrderSchema testPkg = createPartialOrder(originalPkg, quantity);

        // Check if this partial order can be assigned
        if (canAssignWithSpaceOptimization(testPkg, route, currentSolution)) {
            // Track this split for batch persistence
            trackOrderAssignment(originalPkg.getName(), quantity, route);

            // Update capacities
            updateFlightCapacities(route, quantity);

            AirportSchema destinationAirport = getAirportByCity(originalPkg.getDestinationCitySchema());
            if (destinationAirport != null) {
                incrementWarehouseOccupancy(destinationAirport, quantity);
            }

            return true;
        }

        return false;
    }

    /**
     * NEW: Create a partial order for testing capacity
     *
     * @param original Original order
     * @param quantity Quantity for this partial order
     * @return New OrderSchema with reduced quantity
     */
    private OrderSchema createPartialOrder(OrderSchema original, int quantity) {
        OrderSchema partial = new OrderSchema();
        partial.setId(original.getId());
        partial.setQuantity(quantity);
        partial.setOrderDate(original.getOrderDate());
        partial.setDeliveryDeadline(original.getDeliveryDeadline());
        partial.setCurrentLocation(original.getCurrentLocation());
        partial.setDestinationCitySchema(original.getDestinationCitySchema());
        partial.setPriority(original.getPriority());
        partial.setCustomerId(original.getCustomerId());

        // Create empty product list with specified quantity size
        ArrayList<ProductSchema> partialProducts = new ArrayList<>();
        for (int i = 0; i < quantity; i++) {
            partialProducts.add(new ProductSchema()); // Dummy products for capacity calculation
        }
        partial.setProductSchemas(partialProducts);

        return partial;
    }

    /**
     * NEW: getPackageStartTime corregido con ancla T0 y clamp
     */
    private int getPackageStartTime(OrderSchema pkg) {
        if (pkg == null || pkg.getOrderDate() == null || T0 == null) {
            return 0;
        }
        
        long minutesFromT0 = ChronoUnit.MINUTES.between(T0, pkg.getOrderDate());
        int offset = Math.floorMod(pkg.getId(), 60); // Offset por ID
        int startMinute = (int) (minutesFromT0 + offset);
        
        // Clamp a rango válido [0, TOTAL_MINUTES-1]
        final int TOTAL_MINUTES = HORIZON_DAYS * 24 * 60;
        return Math.max(0, Math.min(startMinute, TOTAL_MINUTES - 1));
    }
    
    /**
     * PATCH: Implementar findBestRouteWithTimeWindows (método crítico)
     */
    private ArrayList<FlightSchema> findBestRouteWithTimeWindows(OrderSchema pkg, HashMap<OrderSchema, ArrayList<FlightSchema>> currentSolution) {
        // Primero intentar con el método original
        ArrayList<FlightSchema> originalRoute = findBestRoute(pkg);
        
        // Si no funciona, intentar con diferentes horarios de salida
        if (originalRoute == null || !canAssignWithSpaceOptimization(pkg, originalRoute, currentSolution)) {
            return findRouteWithDelayedDeparture(pkg, currentSolution);
        }
        
        return originalRoute;
    }
    
    /**
     * CHANGED: calculateRouteTimeMargin unificado sin doble conteo
     * Solo transportTime + 2h conexiones, margen vs orderDate-deadline
     */
    private double calculateRouteTimeMargin(OrderSchema pkg, ArrayList<FlightSchema> route) {
        if (pkg == null || route == null) return 1.0;
        if (pkg.getOrderDate() == null || pkg.getDeliveryDeadline() == null) return 1.0;
        
        // Tiempo total de la ruta
        double totalTime = 0.0;
        for (FlightSchema flightSchema : route) {
            totalTime += flightSchema.getTransportTime();
        }
        
        // Añadir 2 horas por conexión
        if (route.size() > 1) {
            totalTime += (route.size() - 1) * 2.0;
        }
        
        // Margen disponible
        long availableHours = ChronoUnit.HOURS.between(pkg.getOrderDate(), pkg.getDeliveryDeadline());
        double margin = availableHours - totalTime;
        
        return Math.max(margin, 0.0) + 1.0; // +1 para evitar margen 0
    }
    
    public void generateInitialSolution() {
        // NEW: Usar flag de Constants para decidir tipo de solución inicial
        if (Constants.USE_GREEDY_INITIAL_SOLUTION) {
            generateGreedyInitialSolution();
        } else {
            generateRandomInitialSolution();
        }
    }
    
    /**
     * NEW: Generar solución inicial completamente aleatoria para probar ALNS
     */
    private void generateRandomInitialSolution() {
        System.out.println("=== GENERANDO SOLUCIÓN INICIAL ALEATORIA ===");
        System.out.println("Probabilidad de asignación: " + (Constants.RANDOM_ASSIGNMENT_PROBABILITY * 100) + "%");
        
        HashMap<OrderSchema, ArrayList<FlightSchema>> currentSolution = new HashMap<>();
        int assignedPackages = 0;
        
        // Barajar paquetes para orden aleatorio
        ArrayList<OrderSchema> shuffledOrderSchemas = new ArrayList<>(orderSchemas);
        Collections.shuffle(shuffledOrderSchemas, random);
        
        for (OrderSchema pkg : shuffledOrderSchemas) {
            // Asignación aleatoria basada en probabilidad
            if (random.nextDouble() < Constants.RANDOM_ASSIGNMENT_PROBABILITY) {
                ArrayList<FlightSchema> randomRoute = generateRandomRoute(pkg);
                
                if (randomRoute != null && !randomRoute.isEmpty()) {
                    int productCount = pkg.getProductSchemas() != null ? pkg.getProductSchemas().size() : 1;
                    
                    // Validación básica de capacidad
                    if (fitsCapacity(randomRoute, productCount)) {
                        AirportSchema destinationAirportSchema = getAirportByCity(pkg.getDestinationCitySchema());
                        if (destinationAirportSchema != null &&
                            canAssignWithSpaceOptimization(pkg, randomRoute, currentSolution)) {
                            
                            currentSolution.put(pkg, randomRoute);
                            updateFlightCapacities(randomRoute, productCount);
                            incrementWarehouseOccupancy(destinationAirportSchema, productCount);
                            assignedPackages++;
                        }
                    }
                }
            }
        }
        
        // Calcular el peso/costo de esta solución
        int solutionWeight = calculateSolutionWeight(currentSolution);
        
        // Almacenar la solución con su peso
        solution.put(currentSolution, solutionWeight);
        
        System.out.println("Random initial solution generated: " + assignedPackages + "/" + orderSchemas.size() + " orderSchemas assigned");
        System.out.println("SolutionSchema weight: " + solutionWeight);
    }
    
    /**
     * NEW: Generar una ruta completamente aleatoria para testing
     */
    private ArrayList<FlightSchema> generateRandomRoute(OrderSchema pkg) {
        CitySchema origin = pkg.getCurrentLocation();
        CitySchema destination = pkg.getDestinationCitySchema();
        
        if (origin == null || destination == null) return null;
        
        AirportSchema originAirportSchema = getAirportByCity(origin);
        AirportSchema destAirportSchema = getAirportByCity(destination);
        
        if (originAirportSchema == null || destAirportSchema == null) return null;
        
        // Intentar encontrar cualquier ruta válida (directo prioritario)
        ArrayList<FlightSchema> directRoute = findDirectRoute(origin, destination);
        if (directRoute != null && !directRoute.isEmpty()) {
            return directRoute;
        }
        
        // Si no hay directo, intentar ruta con 1 escala aleatoria
        ArrayList<AirportSchema> shuffledAirportSchemas = new ArrayList<>(airportSchemas);
        Collections.shuffle(shuffledAirportSchemas, random);
        
        for (int i = 0; i < Math.min(5, shuffledAirportSchemas.size()); i++) { // Máximo 5 intentos
            AirportSchema intermediate = shuffledAirportSchemas.get(i);
            if (intermediate.equals(originAirportSchema) || intermediate.equals(destAirportSchema)) continue;
            
            ArrayList<FlightSchema> leg1 = findDirectRoute(origin, intermediate.getCitySchema());
            ArrayList<FlightSchema> leg2 = findDirectRoute(intermediate.getCitySchema(), destination);
            
            if (leg1 != null && leg2 != null && !leg1.isEmpty() && !leg2.isEmpty()) {
                ArrayList<FlightSchema> route = new ArrayList<>();
                route.addAll(leg1);
                route.addAll(leg2);
                return route;
            }
        }
        
        return null; // No se pudo generar ruta
    }
    
    /**
     * RENAMED: Método greedy original (antes generateInitialSolution)
     */
    private void generateGreedyInitialSolution() {
        System.out.println("=== GENERANDO SOLUCIÓN INICIAL GREEDY ===");
        
        // Crear estructura de solución temporal
        HashMap<OrderSchema, ArrayList<FlightSchema>> currentSolution = new HashMap<>();
        
        // Ordenar paquetes con un componente aleatorio
        ArrayList<OrderSchema> sortedOrderSchemas = new ArrayList<>(orderSchemas);
        
        // Decidir aleatoriamente entre diferentes estrategias de ordenamiento
        int sortStrategy = 0; // Añadido una estrategia más
        
        switch (sortStrategy) {
            case 0:
                // Ordenamiento por deadline (original)
                System.out.println("Estrategia de ordenamiento: Por deadline optimizado");
                sortedOrderSchemas.sort((p1, p2) -> p1.getDeliveryDeadline().compareTo(p2.getDeliveryDeadline()));
                break;
            case 1:
                // Ordenamiento por prioridad
                System.out.println("Estrategia de ordenamiento: Por prioridad");
                sortedOrderSchemas.sort((p1, p2) -> Double.compare(p2.getPriority(), p1.getPriority()));
                break;
            case 2:
                // Ordenamiento por distancia entre continentes
                System.out.println("Estrategia de ordenamiento: Por distancia entre continentes");
                sortedOrderSchemas.sort((p1, p2) -> {
                    boolean p1DiffContinent = p1.getCurrentLocation().getContinent() != p1.getDestinationCitySchema().getContinent();
                    boolean p2DiffContinent = p2.getCurrentLocation().getContinent() != p2.getDestinationCitySchema().getContinent();
                    return Boolean.compare(p1DiffContinent, p2DiffContinent);
                });
                break;
            case 3:
                // Ordenamiento por margen de tiempo (más urgentes primero)
                System.out.println("Estrategia de ordenamiento: Por margen de tiempo");
                sortedOrderSchemas.sort((p1, p2) -> {
                    LocalDateTime now = LocalDateTime.now();
                    long p1Margin = ChronoUnit.HOURS.between(now, p1.getDeliveryDeadline());
                    long p2Margin = ChronoUnit.HOURS.between(now, p2.getDeliveryDeadline());
                    return Long.compare(p1Margin, p2Margin);
                });
                break;
            case 4:
                // Ordenamiento aleatorio
                System.out.println("Estrategia de ordenamiento: Aleatorio");
                Collections.shuffle(sortedOrderSchemas, random);
                break;
        }
        
        // Usar algoritmo optimizado con ventanas de tiempo y reasignación dinámica
        int assignedPackages = generateOptimizedSolution(currentSolution, sortedOrderSchemas);
        
        // Calcular el peso/costo de esta solución
        int solutionWeight = calculateSolutionWeight(currentSolution);
        
        // Almacenar la solución con su peso
        solution.put(currentSolution, solutionWeight);
        
        System.out.println("Initial solution generated: " + assignedPackages + "/" + orderSchemas.size() + " orderSchemas assigned");
        System.out.println("SolutionSchema weight: " + solutionWeight);
    }
    
    /**
     * Genera una solución optimizada usando ventanas de tiempo y reasignación dinámica
     * para aprovechar mejor la liberación de espacio en almacenes.
     * 
     * @param currentSolution solución actual
     * @param sortedOrderSchemas paquetes ordenados por estrategia
     * @return número de paquetes asignados
     */
    private int generateOptimizedSolution(HashMap<OrderSchema, ArrayList<FlightSchema>> currentSolution,
                                        ArrayList<OrderSchema> sortedOrderSchemas) {
        int assignedPackages = 0;
        int maxIterations = 3; // Máximo número de iteraciones para reasignación
        
        System.out.println("Iniciando algoritmo optimizado con " + maxIterations + " iteraciones...");
        
        for (int iteration = 0; iteration < maxIterations; iteration++) {
            if (iteration > 0) {
                System.out.println("Iteración " + iteration + " - Reasignación dinámica...");
                // En iteraciones posteriores, intentar reasignar paquetes no asignados
                ArrayList<OrderSchema> unassignedOrderSchemas = new ArrayList<>();
                for (OrderSchema pkg : sortedOrderSchemas) {
                    if (!currentSolution.containsKey(pkg)) {
                        unassignedOrderSchemas.add(pkg);
                    }
                }
                sortedOrderSchemas = unassignedOrderSchemas;
            }
            
            int iterationAssigned = 0;
            
            for (OrderSchema pkg : sortedOrderSchemas) {
                AirportSchema destinationAirportSchema = getAirportByCity(pkg.getDestinationCitySchema());
                if (destinationAirportSchema == null) continue;
                
                int productCount = pkg.getProductSchemas() != null ? pkg.getProductSchemas().size() : 1;
                
                // Intentar asignar el paquete usando diferentes estrategias
                ArrayList<FlightSchema> bestRoute = findBestRouteWithTimeWindows(pkg, currentSolution);
                
                if (bestRoute != null && isRouteValid(pkg, bestRoute)) {
                    // Primero validar temporalmente sin actualizar capacidades
                    if (canAssignWithSpaceOptimization(pkg, bestRoute, currentSolution)) {
                      // Si la validación temporal pasa, entonces actualizar capacidades
                      currentSolution.put(pkg, bestRoute);
                      assignedPackages++;
                      iterationAssigned++;

                      // Actualizar capacidades DESPUÉS de la validación
                      updateFlightCapacities(bestRoute, productCount);
                      incrementWarehouseOccupancy(destinationAirportSchema, productCount);

                      // NEW: Track this assignment for batch persistence
                      trackOrderAssignment(pkg.getName(), productCount, bestRoute);

                      if (iteration > 0) {
                        System.out.println("  Reasignado paquete " + pkg.getId() + " en iteración " + iteration);
                      }
                    }
                  } else {
                    // NEW: Order doesn't fit - attempt to split it
                    if (attemptOrderSplit(pkg, bestRoute, currentSolution)) {
                        // At least one split was assigned (tracked internally)
                        iterationAssigned++;
                        System.out.println("  Order " + pkg.getId() + " split and partially assigned");
                    }

                }
            }
            
            System.out.println("  Iteración " + iteration + " completada: " + iterationAssigned + " paquetes asignados");
            
            // Si no se asignaron paquetes en esta iteración, no hay punto en continuar
            if (iterationAssigned == 0) {
                break;
            }
        }
        
        return assignedPackages;
    }
    
    
    /**
     * Encuentra una ruta con horarios de salida retrasados para aprovechar liberación de espacio
     */
    private ArrayList<FlightSchema> findRouteWithDelayedDeparture(OrderSchema pkg,
                                                                  HashMap<OrderSchema, ArrayList<FlightSchema>> currentSolution) {
        // Intentar con diferentes horarios de salida (cada 2 horas)
        for (int delayHours = 2; delayHours <= 12; delayHours += 2) {
            // Simular un paquete con horario de salida retrasado
            OrderSchema delayedPkg = createDelayedPackage(pkg, delayHours);
            if (delayedPkg == null) continue;
            
            ArrayList<FlightSchema> route = findBestRoute(delayedPkg);
            if (route != null && canAssignWithSpaceOptimization(delayedPkg, route, currentSolution)) {
                return route;
            }
        }
        
        return null;
    }
    
    /**
     * Crea un paquete con horario de salida retrasado para probar diferentes ventanas de tiempo
     */
    private OrderSchema createDelayedPackage(OrderSchema originalPkg, int delayHours) {
        // Verificar si el retraso no viola el deadline
        LocalDateTime delayedOrderDate = originalPkg.getOrderDate().plusHours(delayHours);
        if (delayedOrderDate.isAfter(originalPkg.getDeliveryDeadline())) {
            return null; // El retraso violaría el deadline
        }
        
        // Crear una copia del paquete con el nuevo horario
        OrderSchema delayedPkg = new OrderSchema();
        delayedPkg.setId(originalPkg.getId());
        delayedPkg.setCustomerSchema(originalPkg.getCustomerSchema());
        delayedPkg.setDestinationCitySchema(originalPkg.getDestinationCitySchema());
        delayedPkg.setOrderDate(delayedOrderDate);
        delayedPkg.setDeliveryDeadline(originalPkg.getDeliveryDeadline());
        delayedPkg.setCurrentLocation(originalPkg.getCurrentLocation());
        delayedPkg.setProductSchemas(originalPkg.getProductSchemas());
        delayedPkg.setPriority(originalPkg.getPriority());
        
        return delayedPkg;
    }
    
    private ArrayList<FlightSchema> findBestRoute(OrderSchema pkg) {
        CitySchema origin = pkg.getCurrentLocation();
        CitySchema destination = pkg.getDestinationCitySchema();
        
        // Si ya está en la ciudad destino, no necesita vuelos
        if (origin.equals(destination)) {
            return new ArrayList<>();
        }
        
        // Introducir aleatoriedad en el orden de búsqueda de rutas
        ArrayList<ArrayList<FlightSchema>> validRoutes = new ArrayList<>();
        ArrayList<String> routeTypes = new ArrayList<>();
        ArrayList<Double> routeScores = new ArrayList<>(); // Puntajes para cada ruta
        
        // 1. Buscar ruta directa
        ArrayList<FlightSchema> directRoute = findDirectRoute(origin, destination);
        if (directRoute != null && isRouteValid(pkg, directRoute)) {
            validRoutes.add(directRoute);
            routeTypes.add("directa");
            
            // Calcular margen de tiempo para la ruta directa
            double directScore = calculateRouteTimeMargin(pkg, directRoute);
            routeScores.add(directScore);
        }
        
        // 2. Buscar ruta con una escala
        ArrayList<FlightSchema> oneStopRoute = findOneStopRoute(origin, destination);
        if (oneStopRoute != null && isRouteValid(pkg, oneStopRoute)) {
            validRoutes.add(oneStopRoute);
            routeTypes.add("una escala");
            
            // Calcular margen de tiempo para la ruta con una escala
            double oneStopScore = calculateRouteTimeMargin(pkg, oneStopRoute);
            routeScores.add(oneStopScore);
        }
        
        // 3. Buscar ruta con dos escalas
        ArrayList<FlightSchema> twoStopRoute = findTwoStopRoute(origin, destination);
        if (twoStopRoute != null && isRouteValid(pkg, twoStopRoute)) {
            validRoutes.add(twoStopRoute);
            routeTypes.add("dos escalas");
            
            // Calcular margen de tiempo para la ruta con dos escalas
            double twoStopScore = calculateRouteTimeMargin(pkg, twoStopRoute);
            routeScores.add(twoStopScore);
        }
        
        // Si no hay rutas válidas, intentar un segundo pase con menos restricciones
        if (validRoutes.isEmpty()) {
            // Podríamos implementar un reintento con criterios más flexibles aquí
            return null;
        }
        
        // Seleccionar una ruta basada en probabilidad ponderada por margen de tiempo
        int totalRoutes = validRoutes.size();
        int selectedIndex;
        
        if (totalRoutes > 1) {
            // Calcular probabilidades basadas en puntajes
            double totalScore = 0;
            for (double score : routeScores) {
                totalScore += score;
            }
            
            if (totalScore > 0) {
                // Selección con probabilidad proporcional al margen de tiempo
                double rand = random.nextDouble() * totalScore;
                double cumulativeScore = 0;
                selectedIndex = 0;
                
                for (int i = 0; i < routeScores.size(); i++) {
                    cumulativeScore += routeScores.get(i);
                    if (rand <= cumulativeScore) {
                        selectedIndex = i;
                        break;
                    }
                }
            } else {
                // Si todos los puntajes son 0 o negativos, selección aleatoria simple
                selectedIndex = random.nextInt(totalRoutes);
            }
        } else {
            // Solo hay una ruta disponible
            selectedIndex = 0;
        }
        
        return validRoutes.get(selectedIndex);
    }
    
    /**
     * Calcula un puntaje para una ruta basado en el margen de tiempo antes del deadline
     * Rutas con mayor margen reciben puntajes más altos
     */
    
    
    
    private ArrayList<FlightSchema> findOneStopRoute(CitySchema origin, CitySchema destination) {
        AirportSchema originAirportSchema = getAirportByCity(origin);
        AirportSchema destinationAirportSchema = getAirportByCity(destination);
        
        if (originAirportSchema == null || destinationAirportSchema == null) {
            return null;
        }
        
        // Crear una lista de aeropuertos intermedios potenciales y barajarla
        ArrayList<AirportSchema> potentialIntermediates = new ArrayList<>();
        for (AirportSchema airportSchema : airportSchemas) {
            if (!airportSchema.equals(originAirportSchema) && !airportSchema.equals(destinationAirportSchema)) {
                potentialIntermediates.add(airportSchema);
            }
        }
        
        // Barajar la lista para explorar aeropuertos intermedios en orden aleatorio
        Collections.shuffle(potentialIntermediates, random);
        
        // Buscar escala intermedia
        for (AirportSchema intermediateAirportSchema : potentialIntermediates) {
            
            // Buscar vuelo de origen a intermedio
            FlightSchema firstFlightSchema = null;
            for (FlightSchema flightSchema : flightSchemas) {
                if (flightSchema.getOriginAirportSchema().equals(originAirportSchema) &&
                    flightSchema.getDestinationAirportSchema().equals(intermediateAirportSchema) &&
                    flightSchema.getUsedCapacity() < flightSchema.getMaxCapacity()) {
                    firstFlightSchema = flightSchema;
                    break;
                }
            }
            
            if (firstFlightSchema == null) continue;
            
            // Buscar vuelo de intermedio a destino
            FlightSchema secondFlightSchema = null;
            for (FlightSchema flightSchema : flightSchemas) {
                if (flightSchema.getOriginAirportSchema().equals(intermediateAirportSchema) &&
                    flightSchema.getDestinationAirportSchema().equals(destinationAirportSchema) &&
                    flightSchema.getUsedCapacity() < flightSchema.getMaxCapacity()) {
                    secondFlightSchema = flightSchema;
                    break;
                }
            }
            
            if (secondFlightSchema != null) {
                ArrayList<FlightSchema> route = new ArrayList<>();
                route.add(firstFlightSchema);
                route.add(secondFlightSchema);
                return route;
            }
        }
        
        return null;
    }
    
    private ArrayList<FlightSchema> findTwoStopRoute(CitySchema origin, CitySchema destination) {
        AirportSchema originAirportSchema = getAirportByCity(origin);
        AirportSchema destinationAirportSchema = getAirportByCity(destination);
        
        if (originAirportSchema == null || destinationAirportSchema == null) {
            return null;
        }
        
        // Crear listas de posibles aeropuertos intermedios
        ArrayList<AirportSchema> firstIntermediates = new ArrayList<>();
        for (AirportSchema airportSchema : airportSchemas) {
            if (!airportSchema.equals(originAirportSchema) && !airportSchema.equals(destinationAirportSchema)) {
                firstIntermediates.add(airportSchema);
            }
        }
        
        // Barajar la primera lista de intermedios
        Collections.shuffle(firstIntermediates, random);
        
        // Limitar la búsqueda a un subconjunto aleatorio para mejorar rendimiento
        int maxFirstIntermediates = Math.min(10, firstIntermediates.size());
        
        // Buscar ruta con dos escalas intermedias
        for (int i = 0; i < maxFirstIntermediates; i++) {
            AirportSchema firstIntermediate = firstIntermediates.get(i);
            
            // Crear y barajar lista de segundos intermedios
            ArrayList<AirportSchema> secondIntermediates = new ArrayList<>();
            for (AirportSchema airportSchema : airportSchemas) {
                if (!airportSchema.equals(originAirportSchema) &&
                    !airportSchema.equals(destinationAirportSchema) &&
                    !airportSchema.equals(firstIntermediate)) {
                    secondIntermediates.add(airportSchema);
                }
            }
            
            Collections.shuffle(secondIntermediates, random);
            
            // Limitar también la búsqueda del segundo intermedio
            int maxSecondIntermediates = Math.min(10, secondIntermediates.size());
            
            for (int j = 0; j < maxSecondIntermediates; j++) {
                AirportSchema secondIntermediate = secondIntermediates.get(j);
                
                // Buscar primer vuelo: origen -> primera escala
                FlightSchema firstFlightSchema = null;
                for (FlightSchema flightSchema : flightSchemas) {
                    if (flightSchema.getOriginAirportSchema().equals(originAirportSchema) &&
                        flightSchema.getDestinationAirportSchema().equals(firstIntermediate) &&
                        flightSchema.getUsedCapacity() < flightSchema.getMaxCapacity()) {
                        firstFlightSchema = flightSchema;
                        break;
                    }
                }
                
                if (firstFlightSchema == null) continue;
                
                // Buscar segundo vuelo: primera escala -> segunda escala
                FlightSchema secondFlightSchema = null;
                for (FlightSchema flightSchema : flightSchemas) {
                    if (flightSchema.getOriginAirportSchema().equals(firstIntermediate) &&
                        flightSchema.getDestinationAirportSchema().equals(secondIntermediate) &&
                        flightSchema.getUsedCapacity() < flightSchema.getMaxCapacity()) {
                        secondFlightSchema = flightSchema;
                        break;
                    }
                }
                
                if (secondFlightSchema == null) continue;
                
                // Buscar tercer vuelo: segunda escala -> destino
                FlightSchema thirdFlightSchema = null;
                for (FlightSchema flightSchema : flightSchemas) {
                    if (flightSchema.getOriginAirportSchema().equals(secondIntermediate) &&
                        flightSchema.getDestinationAirportSchema().equals(destinationAirportSchema) &&
                        flightSchema.getUsedCapacity() < flightSchema.getMaxCapacity()) {
                        thirdFlightSchema = flightSchema;
                        break;
                    }
                }
                
                if (thirdFlightSchema != null) {
                    ArrayList<FlightSchema> route = new ArrayList<>();
                    route.add(firstFlightSchema);
                    route.add(secondFlightSchema);
                    route.add(thirdFlightSchema);
                    
                    // Verificar que la ruta total no exceda límites de tiempo
                    double totalTime = firstFlightSchema.getTransportTime() +
                                      secondFlightSchema.getTransportTime() +
                                      thirdFlightSchema.getTransportTime();
                    
                    // Agregar penalización por múltiples escalas (tiempo de conexión)
                    totalTime += 2.0; // 2 horas adicionales por cada escala
                    
                    // Si la ruta es demasiado larga, continuar buscando
                    if (totalTime > Constants.DIFFERENT_CONTINENT_MAX_DELIVERY_TIME * 24) {
                        continue;
                    }
                    
                    return route;
                }
            }
        }
        
        return null;
    }
    
    
    
    private boolean isDeadlineRespected(OrderSchema pkg, ArrayList<FlightSchema> route) {
        if (pkg == null || pkg.getOrderDate() == null || pkg.getDeliveryDeadline() == null) {
            return false;
        }
        double totalTime = 0;
        
        // CORRECCIÓN: Solo usar transportTime de vuelos (que ya respeta la política PACK)
        for (FlightSchema flightSchema : route) {
            totalTime += flightSchema.getTransportTime();
        }
        
        // Añadir penalización por conexiones (2 horas por conexión)
        if (route.size() > 1) {
            totalTime += (route.size() - 1) * 2.0;
        }
        
        // CORRECCIÓN: Eliminar doble conteo - transportTime ya incluye política de continentes
        // No agregar tiempo adicional por continente porque ya está en flight.getTransportTime()
        
        // CORRECCIÓN: Usar validación robusta de promesas MoraPack
        if (!validateMoraPackDeliveryPromise(pkg, totalTime)) {
            return false; // Excede promesas MoraPack
        }
        
        // Factor de seguridad aleatorio (1-10%) para asegurar entregas a tiempo
        // Más margen de seguridad para rutas complejas o intercontinentales
        double safetyMargin = 0.0;
        if (random != null) { // Verificar que random esté inicializado
            // CORRECCIÓN: Recalcular sameContinentRoute para el factor de complejidad
            CitySchema origin = pkg.getCurrentLocation();
            CitySchema destination = pkg.getDestinationCitySchema();
            boolean sameContinentRoute = (origin != null && destination != null) &&
                                        origin.getContinent() == destination.getContinent();

            int complexityFactor = route.size() + (sameContinentRoute ? 0 : 2);
            // FIX: Ensure bound is at least 1 for random.nextInt()
            int randomBound = Math.max(1, complexityFactor * 3);
            safetyMargin = 0.01 * (1 + random.nextInt(randomBound));
            totalTime = totalTime * (1.0 + safetyMargin); // Aumentar tiempo estimado para ser conservadores
        }
        
        // CORRECCIÓN: Calcular tiempo límite desde orderDate, no desde "now"
        long hoursUntilDeadline = ChronoUnit.HOURS.between(pkg.getOrderDate(), pkg.getDeliveryDeadline());
        
        return totalTime <= hoursUntilDeadline;
    }
    
    /**
     * CORRECCIÓN: Validación explícita y robusta de promesas de entrega MoraPack
     * 
     * Promesas MoraPack:
     * - Mismo continente: máximo 2 días (48 horas)
     * - Diferentes continentes: máximo 3 días (72 horas)
     * 
     * También verifica que el tiempo estimado no exceda el deadline del cliente
     */
    private boolean validateMoraPackDeliveryPromise(OrderSchema pkg, double totalTimeHours) {
        // 1. Verificar promesa MoraPack según continentes
        CitySchema origin = pkg.getCurrentLocation();
        CitySchema destination = pkg.getDestinationCitySchema();
        
        if (origin == null || destination == null) {
            System.err.println("Error: origen o destino nulo para paquete " + pkg.getId());
            return false;
        }
        
        boolean sameContinentRoute = origin.getContinent() == destination.getContinent();
        long moraPackPromiseHours = sameContinentRoute ? 48 : 72; // 2 días intra / 3 días inter
        
        // Verificar promesa MoraPack
        if (totalTimeHours > moraPackPromiseHours) {
            if (DEBUG_MODE) {
                System.out.println("VIOLACIÓN PROMESA MORAPACK - Paquete " + pkg.getId() + 
                    ": " + totalTimeHours + "h > " + moraPackPromiseHours + "h (" + 
                    (sameContinentRoute ? "mismo continente" : "diferentes continentes") + ")");
            }
            return false;
        }
        
        // 2. Verificar deadline específico del cliente
        long hoursUntilDeadline = ChronoUnit.HOURS.between(pkg.getOrderDate(), pkg.getDeliveryDeadline());
        
        if (totalTimeHours > hoursUntilDeadline) {
            if (DEBUG_MODE) {
                System.out.println("VIOLACIÓN DEADLINE CLIENTE - Paquete " + pkg.getId() + 
                    ": " + totalTimeHours + "h > " + hoursUntilDeadline + "h disponibles");
            }
            return false;
        }
        
        // 3. Verificar que el origen sea efectivamente una sede MoraPack
        if (!isMoraPackHeadquarters(origin)) {
            if (DEBUG_MODE) {
                System.out.println("ADVERTENCIA - Paquete " + pkg.getId() + 
                    " no origina desde sede MoraPack: " + origin.getName());
            }
            // No bloquear, pero advertir
        }
        
        return true; // Cumple todas las promesas
    }
    
    /**
     * CORRECCIÓN: Verificar si una ciudad es sede principal de MoraPack
     */
    private boolean isMoraPackHeadquarters(CitySchema citySchema) {
        if (citySchema == null || citySchema.getName() == null) return false;
        
        String cityName = citySchema.getName().toLowerCase();
        return cityName.contains("lima") || 
               cityName.contains("bruselas") || cityName.contains("brussels") ||
               cityName.contains("baku");
    }
    
    private static final boolean DEBUG_MODE = false; // Cambiar a true para debug detallado
    
    
    private int calculateSolutionWeight(HashMap<OrderSchema, ArrayList<FlightSchema>> solutionMap) {
        // El peso de la solución considera múltiples factores:
        // 1. Número total de paquetes asignados (maximizar)
        // 2. Número total de productos transportados (maximizar) - NUEVO
        // 3. Tiempo total de entrega (minimizar)
        // 4. Utilización de capacidad de vuelos (maximizar)
        // 5. Cumplimiento de deadlines (maximizar)
        // 6. Margen de seguridad antes de deadline (maximizar)
        
        int totalPackages = solutionMap.size();
        int totalProducts = 0; // NUEVO: contador de productos
        double totalDeliveryTime = 0;
        int onTimeDeliveries = 0;
        double totalCapacityUtilization = 0;
        int totalFlightsUsed = 0;
        double totalDeliveryMargin = 0; // Margen total antes del deadline
        
        // Calcular métricas
        for (Map.Entry<OrderSchema, ArrayList<FlightSchema>> entry : solutionMap.entrySet()) {
            OrderSchema pkg = entry.getKey();
            ArrayList<FlightSchema> route = entry.getValue();
            
            // Contar productos en este paquete
            int packageProducts = pkg.getProductSchemas() != null ? pkg.getProductSchemas().size() : 1;
            totalProducts += packageProducts;
            
            // Tiempo total de la ruta
            double routeTime = 0;
            for (FlightSchema flightSchema : route) {
                routeTime += flightSchema.getTransportTime();
                totalCapacityUtilization += (double) flightSchema.getUsedCapacity() / flightSchema.getMaxCapacity();
                totalFlightsUsed++;
            }
            
            // Añadir penalización por conexiones
            if (route.size() > 1) {
                routeTime += (route.size() - 1) * 2.0; // 2 horas por cada conexión
            }
            
            totalDeliveryTime += routeTime;
            
            // Verificar si llega a tiempo y calcular margen
            if (isDeadlineRespected(pkg, route)) {
                onTimeDeliveries++;
                
                // Calcular margen de tiempo antes del deadline (en horas)
                LocalDateTime estimatedDelivery = pkg.getOrderDate().plusHours((long)routeTime);
                double marginHours = ChronoUnit.HOURS.between(estimatedDelivery, pkg.getDeliveryDeadline());
                totalDeliveryMargin += marginHours;
            }
        }
        
        // Fórmula de peso optimizada para MoraPack
        double avgDeliveryTime = totalPackages > 0 ? totalDeliveryTime / totalPackages : 0;
        double avgCapacityUtilization = totalFlightsUsed > 0 ? totalCapacityUtilization / totalFlightsUsed : 0;
        double onTimeRate = totalPackages > 0 ? (double) onTimeDeliveries / totalPackages : 0;
        double avgDeliveryMargin = onTimeDeliveries > 0 ? totalDeliveryMargin / onTimeDeliveries : 0;
        
        // Componentes específicos de MoraPack
        double continentalEfficiency = calculateContinentalEfficiency(solutionMap);
        double warehouseUtilization = calculateWarehouseUtilization();
        
        // Peso final REBALANCEADO - PRIORIDAD: MÁS PAQUETES ASIGNADOS
        int weight = (int) (
            // PRIORIDAD ABSOLUTA: Cantidad de paquetes y productos (MAXIMIZAR)
            totalPackages * 5000 +                // 100,000 puntos por paquete (DOMINANTE)
            totalProducts * 1000 +                 // 10,000 puntos por producto (MUY ALTO)
            
            // FACTOR CALIDAD: On-time como multiplicador, no aditivo
            onTimeRate * 500 +                     // 5,000 puntos máximo por calidad on-time
            
            // EFICIENCIA OPERATIVA (secundaria)
            Math.min(avgDeliveryMargin * 50, 1000) + // Margen de seguridad reducido
            continentalEfficiency * 500 +           // Eficiencia continental
            avgCapacityUtilization * 200 +          // Utilización de vuelos
            warehouseUtilization * 100 +            // Utilización de almacenes
            
            // PENALIZACIONES MENORES
            - avgDeliveryTime * 20 -                // Penalización tiempo reducida
            - calculateRoutingComplexity(solutionMap) * 50 // Penalización complejidad reducida
        );
        
        // NUEVA PENALIZACIÓN: Solo si on-time rate es muy bajo (< 80%)
        if (onTimeRate < 0.8) {
            weight = (int)(weight * 0.5); // Penalización 50% solo si muy malo
        }
        
        // BONUS MODERADO por alta calidad (≥95% on-time)
        if (onTimeRate >= 0.95 && totalPackages > 10) {
            weight = (int)(weight * 1.1); // 10% bonus moderado por alta calidad
        }
        
        // BONUS EXTRA por volumen alto (>1000 paquetes asignados)
        if (totalPackages > 1000) {
            weight = (int)(weight * 1.15); // 15% bonus por alto volumen
        }
        
        return weight;
    }
    
    /**
     * Calcula la eficiencia de rutas continentales vs intercontinentales
     * Premia rutas directas y penaliza complejidad innecesaria
     */
    private double calculateContinentalEfficiency(HashMap<OrderSchema, ArrayList<FlightSchema>> solutionMap) {
        if (solutionMap.isEmpty()) return 0.0;
        
        int sameContinentDirect = 0;
        int sameContinentOneStop = 0;
        int differentContinentDirect = 0;
        int differentContinentOneStop = 0;
        int inefficientRoutes = 0;
        
        for (Map.Entry<OrderSchema, ArrayList<FlightSchema>> entry : solutionMap.entrySet()) {
            OrderSchema pkg = entry.getKey();
            ArrayList<FlightSchema> route = entry.getValue();
            
            boolean sameContinentRoute = pkg.getCurrentLocation().getContinent() == 
                                        pkg.getDestinationCitySchema().getContinent();
            
            if (route.isEmpty()) continue; // Ya en destino
            
            if (sameContinentRoute) {
                if (route.size() == 1) sameContinentDirect++;
                else if (route.size() == 2) sameContinentOneStop++;
                else inefficientRoutes++;
            } else {
                if (route.size() == 1) differentContinentDirect++;
                else if (route.size() <= 2) differentContinentOneStop++;
                else inefficientRoutes++;
            }
        }
        
        // Puntuación basada en eficiencia esperada para MoraPack
        double efficiency = sameContinentDirect * 1.0 +        // Ideal para mismo continente
                           sameContinentOneStop * 0.8 +         // Aceptable para mismo continente
                           differentContinentDirect * 1.2 +     // Excelente para diferentes continentes
                           differentContinentOneStop * 1.0 +    // Bueno para diferentes continentes
                           inefficientRoutes * (-0.5);         // Penalizar rutas ineficientes
        
        return efficiency;
    }
    
    /**
     * Calcula la utilización promedio de almacenes
     */
    private double calculateWarehouseUtilization() {
        if (warehouseOccupancy.isEmpty()) return 0.0;
        
        double totalUtilization = 0.0;
        int validWarehouses = 0;
        
        for (Map.Entry<AirportSchema, Integer> entry : warehouseOccupancy.entrySet()) {
            AirportSchema airportSchema = entry.getKey();
            int occupancy = entry.getValue();
            
            if (airportSchema.getWarehouse() != null && airportSchema.getWarehouse().getMaxCapacity() > 0) {
                double utilization = (double) occupancy / airportSchema.getWarehouse().getMaxCapacity();
                totalUtilization += utilization;
                validWarehouses++;
            }
        }
        
        return validWarehouses > 0 ? totalUtilization / validWarehouses : 0.0;
    }
    
    /**
     * Calcula la complejidad de enrutamiento - penaliza rutas excesivamente complejas
     */
    private double calculateRoutingComplexity(HashMap<OrderSchema, ArrayList<FlightSchema>> solutionMap) {
        if (solutionMap.isEmpty()) return 0.0;
        
        double totalComplexity = 0.0;
        
        for (Map.Entry<OrderSchema, ArrayList<FlightSchema>> entry : solutionMap.entrySet()) {
            OrderSchema pkg = entry.getKey();
            ArrayList<FlightSchema> route = entry.getValue();
            
            if (route.isEmpty()) continue;
            
            // Penalizar rutas con más escalas de las necesarias
            boolean sameContinentRoute = pkg.getCurrentLocation().getContinent() == 
                                        pkg.getDestinationCitySchema().getContinent();
            
            int expectedMaxStops = sameContinentRoute ? 1 : 2; // 1 para mismo continente, 2 para diferentes
            
            if (route.size() > expectedMaxStops) {
                totalComplexity += (route.size() - expectedMaxStops) * 2.0; // Penalización por escala extra
            }
            
            // Penalizar vuelos con baja utilización en rutas largas
            if (route.size() > 1) {
                for (FlightSchema flightSchema : route) {
                    double utilization = (double) flightSchema.getUsedCapacity() / flightSchema.getMaxCapacity();
                    if (utilization < 0.3) { // Vuelos con menos del 30% de utilización
                        totalComplexity += 1.0;
                    }
                }
            }
        }
        
        return totalComplexity;
    }

    public boolean isSolutionValid() {
        if (solution.isEmpty()) {
            return false;
        }
        
        // Obtener la solución actual
        HashMap<OrderSchema, ArrayList<FlightSchema>> currentSolution = solution.keySet().iterator().next();
        
        // Verificar que todos los paquetes asignados tengan rutas válidas
        for (Map.Entry<OrderSchema, ArrayList<FlightSchema>> entry : currentSolution.entrySet()) {
            OrderSchema pkg = entry.getKey();
            ArrayList<FlightSchema> route = entry.getValue();
            
            if (!isRouteValid(pkg, route)) {
                return false;
            }
        }
        
        // Validación temporal de capacidades de almacenes
        if (!isTemporalSolutionValid(currentSolution)) {
            System.out.println("SolutionSchema violates temporal warehouse capacity constraints");
            return false;
        }
        
        return true;
    }
    
    public boolean isSolutionCapacityValid() {
        if (solution.isEmpty()) return false;

        // Tomamos la solución actual
        HashMap<OrderSchema, ArrayList<FlightSchema>> currentSolution = solution.keySet().iterator().next();

        // Uso de capacidad por vuelo en términos de "productos"
        Map<FlightSchema, Integer> flightUsage = new HashMap<>();

        for (Map.Entry<OrderSchema, ArrayList<FlightSchema>> entry : currentSolution.entrySet()) {
            OrderSchema pkg = entry.getKey();
            ArrayList<FlightSchema> route = entry.getValue();

            // Contar productos del paquete (mínimo 1)
            int products = (pkg.getProductSchemas() != null && !pkg.getProductSchemas().isEmpty())
                    ? pkg.getProductSchemas().size()
                    : 1;

            // Sumar esos productos a cada vuelo de la ruta
            for (FlightSchema f : route) {
                flightUsage.merge(f, products, Integer::sum);
            }
        }

        // Verificar que ningún vuelo exceda su capacidad máxima
        for (Map.Entry<FlightSchema, Integer> e : flightUsage.entrySet()) {
            FlightSchema f = e.getKey();
            int used = e.getValue(); // productos cargados en ese vuelo según la solución
            if (used > f.getMaxCapacity()) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Imprime una descripción detallada de la solución actual.
     * Muestra estadísticas generales y las rutas asignadas a cada paquete.
     * 
     * @param detailLevel nivel de detalle (1: resumen, 2: rutas principales, 3: todas las rutas)
     */
    public void printSolutionDescription(int detailLevel) {
        if (solution.isEmpty()) {
            System.out.println("No hay solución disponible para mostrar.");
            return;
        }
        
        // Obtener la solución actual y su peso
        HashMap<OrderSchema, ArrayList<FlightSchema>> currentSolution = solution.keySet().iterator().next();
        int solutionWeight = solution.get(currentSolution);
        
        // Calcular total de productos
        int totalProductsAssigned = 0;
        int totalProductsInSystem = 0;
        for (OrderSchema pkg : orderSchemas) {
            int productCount = pkg.getProductSchemas() != null ? pkg.getProductSchemas().size() : 1;
            totalProductsInSystem += productCount;
            if (currentSolution.containsKey(pkg)) {
                totalProductsAssigned += productCount;
            }
        }
        
        // Estadísticas generales
        System.out.println("\n========== DESCRIPCIÓN DE LA SOLUCIÓN ==========");
        System.out.println("Peso de la solución: " + solutionWeight);
        System.out.println("Paquetes asignados: " + currentSolution.size() + "/" + orderSchemas.size());
        System.out.println("Productos transportados: " + totalProductsAssigned + "/" + totalProductsInSystem);
        
        // Calcular estadísticas adicionales
        int directRoutes = 0;
        int oneStopRoutes = 0;
        int twoStopRoutes = 0;
        int sameContinentRoutes = 0;
        int differentContinentRoutes = 0;
        int onTimeDeliveries = 0;
        
        for (Map.Entry<OrderSchema, ArrayList<FlightSchema>> entry : currentSolution.entrySet()) {
            OrderSchema pkg = entry.getKey();
            ArrayList<FlightSchema> route = entry.getValue();
            
            // Contar tipos de rutas
            if (route.size() == 1) directRoutes++;
            else if (route.size() == 2) oneStopRoutes++;
            else if (route.size() == 3) twoStopRoutes++;
            
            // Contar rutas por continente
            if (pkg.getCurrentLocation().getContinent() == pkg.getDestinationCitySchema().getContinent()) {
                sameContinentRoutes++;
            } else {
                differentContinentRoutes++;
            }
            
            // Contar entregas a tiempo
            if (isDeadlineRespected(pkg, route)) {
                onTimeDeliveries++;
            }
        }
        
        // Mostrar estadísticas detalladas
        System.out.println("\n----- Estadísticas de Rutas -----");
        System.out.println("Rutas directas: " + directRoutes + " (" + formatPercentage(directRoutes, currentSolution.size()) + "%)");
        System.out.println("Rutas con 1 escala: " + oneStopRoutes + " (" + formatPercentage(oneStopRoutes, currentSolution.size()) + "%)");
        System.out.println("Rutas con 2 escalas: " + twoStopRoutes + " (" + formatPercentage(twoStopRoutes, currentSolution.size()) + "%)");
        System.out.println("Rutas en mismo continente: " + sameContinentRoutes + " (" + formatPercentage(sameContinentRoutes, currentSolution.size()) + "%)");
        System.out.println("Rutas entre continentes: " + differentContinentRoutes + " (" + formatPercentage(differentContinentRoutes, currentSolution.size()) + "%)");
        System.out.println("Entregas a tiempo: " + onTimeDeliveries + " (" + formatPercentage(onTimeDeliveries, currentSolution.size()) + "% de asignados)");
        System.out.println("Entregas a tiempo del total: " + onTimeDeliveries + "/" + orderSchemas.size() + " (" + formatPercentage(onTimeDeliveries, orderSchemas.size()) + "%)");
        
        int unassignedPackages = orderSchemas.size() - currentSolution.size();
        if (unassignedPackages > 0) {
            System.out.println("Paquetes no asignados: " + unassignedPackages + "/" + orderSchemas.size() + " (" + formatPercentage(unassignedPackages, orderSchemas.size()) + "%)");
            System.out.println("Razón principal: Capacidad de almacenes insuficiente");
        }
        
        // Mostrar estadísticas de ocupación de almacenes
        System.out.println("\n----- Ocupación de Almacenes -----");
        int totalWarehouseCapacity = 0;
        int totalWarehouseOccupancy = 0;
        int warehousesAtCapacity = 0;
        
        for (Map.Entry<AirportSchema, Integer> entry : warehouseOccupancy.entrySet()) {
            AirportSchema airportSchema = entry.getKey();
            int occupancy = entry.getValue();
            
            if (airportSchema.getWarehouse() != null) {
                int maxCapacity = airportSchema.getWarehouse().getMaxCapacity();
                totalWarehouseCapacity += maxCapacity;
                totalWarehouseOccupancy += occupancy;
                
                if (occupancy >= maxCapacity) {
                    warehousesAtCapacity++;
                }
                
                // Mostrar almacenes con alta ocupación (>80%)
                double occupancyPercentage = (occupancy * 100.0) / maxCapacity;
                if (occupancyPercentage > 80.0) {
                    System.out.println("  " + airportSchema.getCitySchema().getName() + ": " + occupancy + "/" + maxCapacity +
                                      " (" + String.format("%.1f", occupancyPercentage) + "%)");
                }
            }
        }
        
        double avgOccupancyPercentage = totalWarehouseCapacity > 0 ? 
            (totalWarehouseOccupancy * 100.0) / totalWarehouseCapacity : 0.0;
        
        System.out.println("Ocupación promedio de almacenes: " + String.format("%.1f", avgOccupancyPercentage) + "%");
        System.out.println("Almacenes llenos: " + warehousesAtCapacity + "/" + airportSchemas.size());
        
        // Mostrar información de picos temporales si la validación temporal está disponible
        if (temporalWarehouseOccupancy != null && !temporalWarehouseOccupancy.isEmpty()) {
            System.out.println("\n----- Picos de Ocupación Temporal -----");
            for (AirportSchema airportSchema : airportSchemas) {
                if (airportSchema.getWarehouse() != null) {
                    int[] peakInfo = findPeakOccupancy(airportSchema);
                    int peakMinute = peakInfo[0];
                    int maxOccupancy = peakInfo[1];
                    
                    if (maxOccupancy > 0) {
                        int peakHour = peakMinute / 60;
                        int peakMin = peakMinute % 60;
                        double peakPercentage = (maxOccupancy * 100.0) / airportSchema.getWarehouse().getMaxCapacity();
                        
                        if (peakPercentage > 50.0) { // Mostrar solo aeropuertos con picos significativos
                            System.out.println("  " + airportSchema.getCitySchema().getName() +
                                              " - Pico: " + maxOccupancy + "/" + airportSchema.getWarehouse().getMaxCapacity() +
                                              " (" + String.format("%.1f", peakPercentage) + "%) a las " + 
                                              String.format("%02d:%02d", peakHour, peakMin));
                        }
                    }
                }
            }
        }
        
        // Si el nivel de detalle es bajo, terminar aquí
        if (detailLevel < 2) {
            return;
        }
        
        // Mostrar rutas por prioridad
        System.out.println("\n----- Rutas por Prioridad -----");
        
        // Ordenar paquetes por prioridad
        List<OrderSchema> sortedOrderSchemas = new ArrayList<>(currentSolution.keySet());
        sortedOrderSchemas.sort((p1, p2) -> {
            // Primero por prioridad (mayor a menor)
            int priorityCompare = Double.compare(p2.getPriority(), p1.getPriority());
            if (priorityCompare != 0) return priorityCompare;
            
            // Luego por deadline (más cercano primero)
            return p1.getDeliveryDeadline().compareTo(p2.getDeliveryDeadline());
        });
        
        // Mostrar rutas de alta prioridad o todas según el nivel de detalle
        int routesToShow = detailLevel == 2 ? Math.min(10, sortedOrderSchemas.size()) : sortedOrderSchemas.size();
        
        for (int i = 0; i < routesToShow; i++) {
            OrderSchema pkg = sortedOrderSchemas.get(i);
            ArrayList<FlightSchema> route = currentSolution.get(pkg);
            
            System.out.println("\nPaquete #" + pkg.getId() + 
                              " (Prioridad: " + String.format("%.2f", pkg.getPriority()) + 
                              ", Deadline: " + pkg.getDeliveryDeadline() + ")");
            
            System.out.println("  Origen: " + pkg.getCurrentLocation().getName() + 
                              " (" + pkg.getCurrentLocation().getContinent() + ")");
            System.out.println("  Destino: " + pkg.getDestinationCitySchema().getName() +
                              " (" + pkg.getDestinationCitySchema().getContinent() + ")");
            
            if (route.isEmpty()) {
                System.out.println("  Ruta: Ya está en el destino");
                continue;
            }
            
            System.out.println("  Ruta (" + route.size() + " vuelos):");
            double totalTime = 0;
            
            for (int j = 0; j < route.size(); j++) {
                FlightSchema flightSchema = route.get(j);
                totalTime += flightSchema.getTransportTime();
                
                System.out.println("    " + (j+1) + ". " + 
                                  flightSchema.getOriginAirportSchema().getCitySchema().getName() + " → " +
                                  flightSchema.getDestinationAirportSchema().getCitySchema().getName() +
                                  " (" + String.format("%.1f", flightSchema.getTransportTime()) + "h, " +
                                  flightSchema.getUsedCapacity() + "/" + flightSchema.getMaxCapacity() + " paquetes)");
            }
            
            // Agregar tiempo de conexión
            if (route.size() > 1) {
                totalTime += (route.size() - 1) * 2.0; // 2 horas por conexión
            }
            
            System.out.println("  Tiempo total estimado: " + String.format("%.1f", totalTime) + "h");
            
            boolean onTime = isDeadlineRespected(pkg, route);
            System.out.println("  Entrega a tiempo: " + (onTime ? "SÍ" : "NO"));
        }
        
        if (routesToShow < sortedOrderSchemas.size()) {
            System.out.println("\n... y " + (sortedOrderSchemas.size() - routesToShow) + " paquetes más (use nivel de detalle 3 para ver todos)");
        }
        
        System.out.println("\n=================================================");
    }
    
    private String formatPercentage(int value, int total) {
        if (total == 0) return "0.0";
        return String.format("%.1f", (value * 100.0) / total);
    }
    
    /**
     * Inicializa el mapa de ocupación de almacenes.
     * Cada aeropuerto de destino inicia con 0 paquetes asignados.
     */
    private void initializeWarehouseOccupancy() {
        for (AirportSchema airportSchema : airportSchemas) {
            warehouseOccupancy.put(airportSchema, 0);
        }
    }

    /**
     * NEW: Initialize flight capacities from existing DB assignments (for incremental scheduling)
     * Reads products already assigned to flights and updates usedCapacity accordingly
     *
     * @param existingAssignments Map of FlightInstance ID -> List of assigned products
     */
    private void initializeFlightCapacitiesFromDB(Map<String, List<ProductSchema>> existingAssignments) {
        if (existingAssignments == null || existingAssignments.isEmpty()) {
            System.out.println("No existing flight assignments to initialize");
            return;
        }

        int totalProductsLoaded = 0;
        int flightsUpdated = 0;

        // For each flight instance with existing assignments
        for (Map.Entry<String, List<ProductSchema>> entry : existingAssignments.entrySet()) {
            String flightInstanceId = entry.getKey();
            List<ProductSchema> assignedProducts = entry.getValue();

            if (assignedProducts == null || assignedProducts.isEmpty()) {
                continue;
            }

            // Parse flight instance ID: "FL-45-DAY-0-2000" -> flight code "FL-45"
            String flightCode = parseFlightCodeFromInstance(flightInstanceId);
            if (flightCode == null) {
                continue;
            }

            // Find corresponding FlightSchema
            FlightSchema flight = findFlightByCode(flightCode);
            if (flight == null) {
                if (Constants.VERBOSE_LOGGING) {
                    System.out.println("WARNING: Flight " + flightCode + " not found in flight schemas");
                }
                continue;
            }

            // Update used capacity
            int productsOnFlight = assignedProducts.size();
            flight.setUsedCapacity(flight.getUsedCapacity() + productsOnFlight);
            totalProductsLoaded += productsOnFlight;
            flightsUpdated++;

            if (Constants.VERBOSE_LOGGING) {
                System.out.println("Loaded " + productsOnFlight + " products on flight " + flightCode +
                                 " (capacity: " + flight.getUsedCapacity() + "/" + flight.getMaxCapacity() + ")");
            }
        }

        System.out.println("Initialized flight capacities: " + flightsUpdated + " flights, " +
                         totalProductsLoaded + " products loaded from DB");
    }

    /**
     * NEW: Initialize warehouse occupancy from existing DB assignments (for incremental scheduling)
     * Counts products currently at each warehouse (arrived but not yet delivered)
     *
     * @param existingAssignments Map of FlightInstance ID -> List of assigned products
     */
    private void initializeWarehouseOccupancyFromDB(Map<String, List<ProductSchema>> existingAssignments) {
        if (existingAssignments == null || existingAssignments.isEmpty()) {
            System.out.println("No existing warehouse occupancy to initialize");
            return;
        }

        int totalProductsInWarehouses = 0;

        // Count products that have ARRIVED at warehouses (status = ARRIVED)
        for (Map.Entry<String, List<ProductSchema>> entry : existingAssignments.entrySet()) {
            List<ProductSchema> products = entry.getValue();

            if (products == null) {
                continue;
            }

            for (ProductSchema product : products) {
                // Only count products that are physically at warehouses (not in-transit)
                if (product.getStatus() == Status.ASSIGNED) {
                    // Get destination airport for this product
                    if (product.getOrderId() != null) {
                        // Find the order to get destination
                        OrderSchema order = findOrderById(product.getOrderId());
                        if (order != null && order.getDestinationCitySchema() != null) {
                            AirportSchema destinationAirport = getAirportByCity(order.getDestinationCitySchema());
                            if (destinationAirport != null) {
                                // Increment warehouse occupancy
                                int currentOccupancy = warehouseOccupancy.getOrDefault(destinationAirport, 0);
                                warehouseOccupancy.put(destinationAirport, currentOccupancy + 1);
                                totalProductsInWarehouses++;
                            }
                        }
                    }
                }
            }
        }

        System.out.println("Initialized warehouse occupancy: " + totalProductsInWarehouses + " products in warehouses");

        if (Constants.VERBOSE_LOGGING) {
            for (Map.Entry<AirportSchema, Integer> entry : warehouseOccupancy.entrySet()) {
                if (entry.getValue() > 0) {
                    System.out.println("  " + entry.getKey().getCitySchema().getName() + ": " +
                                     entry.getValue() + " products");
                }
            }
        }
    }

    /**
     * Helper: Parse flight code from flight instance ID
     * Example: "FL-45-DAY-0-2000" -> "FL-45"
     */
    private String parseFlightCodeFromInstance(String flightInstanceId) {
        if (flightInstanceId == null || !flightInstanceId.contains("-DAY-")) {
            return null;
        }

        // Split by "-DAY-" and take the first part
        String[] parts = flightInstanceId.split("-DAY-");
        return parts.length > 0 ? parts[0] : null;
    }

    /**
     * Helper: Find FlightSchema by flight code
     */
    private FlightSchema findFlightByCode(String flightCode) {
        if (flightCode == null || flightSchemas == null) {
            return null;
        }

        for (FlightSchema flight : flightSchemas) {
            if (flightCode.equals(flight.getCode())) {
                return flight;
            }
        }
        return null;
    }

    /**
     * Helper: Find OrderSchema by order ID
     */
    private OrderSchema findOrderById(Integer orderId) {
        if (orderId == null || orderSchemas == null) {
            return null;
        }

        for (OrderSchema order : orderSchemas) {
            if (orderId.equals(order.getId())) {
                return order;
            }
        }
        return null;
    }
    
    /**
     * Inicializa la matriz temporal de ocupación de almacenes.
     * Cada aeropuerto tiene un array de 1440 elementos (24h * 60min).
     */
    /**
     * CORRECCIÓN: Horizonte temporal extendido a 4 días (cubre 3 días de promesas + holgura)
     */
    private static final int HORIZON_DAYS = 4;
    
    private void initializeTemporalWarehouseOccupancy() {
        final int TOTAL_MINUTES = HORIZON_DAYS * 24 * 60; // 5760 minutos (4 días)
        for (AirportSchema airportSchema : airportSchemas) {
            temporalWarehouseOccupancy.put(airportSchema, new int[TOTAL_MINUTES]);
        }
    }

    /**
     * NEW: Crea un snapshot del estado actual de temporalWarehouseOccupancy
     * CRÍTICO: Recomendación #2 - Para rollback de cambios especulativos
     */
    private void snapshotTemporalWarehouse() {
        if (temporalWarehouseOccupancy == null || temporalWarehouseOccupancy.isEmpty()) {
            temporalWarehouseSnapshot = new HashMap<>();
            return;
        }

        temporalWarehouseSnapshot = new HashMap<>();
        final int TOTAL_MINUTES = HORIZON_DAYS * 24 * 60;

        for (Map.Entry<AirportSchema, int[]> entry : temporalWarehouseOccupancy.entrySet()) {
            AirportSchema airport = entry.getKey();
            int[] original = entry.getValue();

            // Deep copy del array
            int[] copy = new int[TOTAL_MINUTES];
            System.arraycopy(original, 0, copy, 0, TOTAL_MINUTES);

            temporalWarehouseSnapshot.put(airport, copy);
        }

        if (Constants.VERBOSE_LOGGING) {
            System.out.println("Temporal warehouse snapshot created");
        }
    }

    /**
     * NEW: Restaura el estado desde el snapshot
     * CRÍTICO: Recomendación #2 - Para rollback cuando una asignación especulativa falla
     */
    private void restoreTemporalWarehouse() {
        if (temporalWarehouseSnapshot == null || temporalWarehouseSnapshot.isEmpty()) {
            if (Constants.VERBOSE_LOGGING) {
                System.out.println("Warning: No snapshot available to restore");
            }
            return;
        }

        final int TOTAL_MINUTES = HORIZON_DAYS * 24 * 60;

        for (Map.Entry<AirportSchema, int[]> entry : temporalWarehouseSnapshot.entrySet()) {
            AirportSchema airport = entry.getKey();
            int[] snapshotArray = entry.getValue();

            // Deep copy de vuelta
            int[] current = temporalWarehouseOccupancy.get(airport);
            if (current != null) {
                System.arraycopy(snapshotArray, 0, current, 0, TOTAL_MINUTES);
            }
        }

        if (Constants.VERBOSE_LOGGING) {
            System.out.println("Temporal warehouse restored from snapshot");
        }
    }
    
    
    /**
     * Valida temporalmente si una solución respeta las capacidades de almacenes
     * durante todo el día, simulando el flujo de paquetes minuto a minuto.
     * 
     * @param solutionMap mapa de paquetes y sus rutas asignadas
     * @return true si no hay violaciones de capacidad, false si las hay
     */
    public boolean isTemporalSolutionValid(HashMap<OrderSchema, ArrayList<FlightSchema>> solutionMap) {
        // Reinicializar matriz temporal
        initializeTemporalWarehouseOccupancy();
        
        // Simular el flujo de cada paquete
        for (Map.Entry<OrderSchema, ArrayList<FlightSchema>> entry : solutionMap.entrySet()) {
            OrderSchema pkg = entry.getKey();
            ArrayList<FlightSchema> route = entry.getValue();
            
            if (!simulatePackageFlow(pkg, route)) {
                return false; // Se encontró una violación de capacidad
            }
        }
        
        return true; // No hay violaciones de capacidad
    }
    
    /**
     * Simula el flujo temporal de un paquete a través de su ruta asignada.
     * 
     * @param pkg paquete a simular
     * @param route ruta asignada al paquete
     * @return true si no viola capacidades, false si las viola
     */
    private boolean simulatePackageFlow(OrderSchema pkg, ArrayList<FlightSchema> route) {
        if (route == null || route.isEmpty()) {
            // El paquete ya está en destino, cliente tiene 2 horas para recoger
            AirportSchema destinationAirportSchema = getAirportByCity(pkg.getDestinationCitySchema());
            int productCount = pkg.getProductSchemas() != null ? pkg.getProductSchemas().size() : 1;
            int startMinute = getPackageStartTime(pkg);
            return addTemporalOccupancy(destinationAirportSchema, startMinute, Constants.CUSTOMER_PICKUP_MAX_HOURS * 60, productCount); // 2 horas para pickup
        }
        
        int currentMinute = getPackageStartTime(pkg); // Momento cuando el paquete inicia su viaje
        int productCount = pkg.getProductSchemas() != null ? pkg.getProductSchemas().size() : 1;
        
        for (int i = 0; i < route.size(); i++) {
            FlightSchema flightSchema = route.get(i);
            AirportSchema departureAirportSchema = flightSchema.getOriginAirportSchema();
            AirportSchema arrivalAirportSchema = flightSchema.getDestinationAirportSchema();
            
            // FASE 1: El paquete está en el aeropuerto de origen esperando el vuelo
            // Asumimos que llega 2 horas antes del vuelo para procesamiento
            int waitingTime = 120; // 2 horas de espera antes del vuelo
            if (!addTemporalOccupancy(departureAirportSchema, currentMinute, waitingTime, productCount)) {
                System.out.println("Capacity violation at " + departureAirportSchema.getCitySchema().getName() +
                                  " at minute " + currentMinute + " (waiting phase) for package " + pkg.getId());
                return false;
            }
            
            // FASE 2: El vuelo despega - productos dejan de ocupar origen
            int flightStartMinute = currentMinute + waitingTime;
            int flightDuration = (int)(flightSchema.getTransportTime() * 60);
            
            // FASE 3: El vuelo llega - productos ocupan destino
            int arrivalMinute = flightStartMinute + flightDuration;
            
            // FASE 4: Productos permanecen en destino (tiempo de conexión si hay más vuelos)
            int stayDuration;
            if (i < route.size() - 1) {
                stayDuration = 120; // 2 horas de conexión hasta el siguiente vuelo
            } else {
                // Es el destino final - cliente tiene máximo 2 horas para recoger
                stayDuration = Constants.CUSTOMER_PICKUP_MAX_HOURS * 60; // 2 horas para pickup del cliente
            }
            
            if (stayDuration > 0 && !addTemporalOccupancy(arrivalAirportSchema, arrivalMinute, stayDuration, productCount)) {
                System.out.println("Capacity violation at " + arrivalAirportSchema.getCitySchema().getName() +
                                  " at minute " + arrivalMinute + " (arrival phase) for package " + pkg.getId());
                return false;
            }
            
            // Actualizar tiempo para el siguiente vuelo
            currentMinute = arrivalMinute;
            if (i < route.size() - 1) {
                currentMinute += 120; // Tiempo de conexión
            }
        }
        
        // La ocupación del destino final ya se maneja en el bucle anterior
        
        return true;
    }
        
    /**
     * Check if an airport is a main MoraPack warehouse with unlimited capacity
     * Main warehouses: Lima, Brussels (Bruselas), Baku
     *
     * @param airportSchema Airport to check
     * @return true if it's a main warehouse with unlimited capacity
     */
    private boolean isMainWarehouse(AirportSchema airportSchema) {
        if (airportSchema == null || airportSchema.getCitySchema() == null) {
            return false;
        }

        String cityName = airportSchema.getCitySchema().getName();
        if (cityName == null) {
            return false;
        }

        // Check for main warehouse cities (case-insensitive)
        String cityLower = cityName.toLowerCase();
        return cityLower.contains("lima") ||
               cityLower.contains("brusel") ||  // Brussels/Bruselas
               cityLower.contains("baku");
    }

    /**
     * Agrega ocupación temporal a un aeropuerto durante un período de tiempo.
     *      *
     * IMPORTANT: Main warehouses (Lima, Brussels, Baku) have UNLIMITED capacity

     * @param airportSchema aeropuerto donde agregar ocupación
     * @param startMinute minuto de inicio (0-1439)
     * @param durationMinutes duración en minutos
     * @param productCount número de productos a agregar
     * @return true si no excede capacidad, false si la excede
     */
    private boolean addTemporalOccupancy(AirportSchema airportSchema, int startMinute, int durationMinutes, int productCount) {
        if (airportSchema == null || airportSchema.getWarehouse() == null) {
            return false;
        }
                // CRITICAL: Main warehouses (Lima, Brussels, Baku) have UNLIMITED capacity
        if (isMainWarehouse(airportSchema)) {
            // Just track occupancy for statistics, but don't enforce limit
            int[] occupancyArray = temporalWarehouseOccupancy.get(airportSchema);
            final int TOTAL_MINUTES = HORIZON_DAYS * 24 * 60;
            int clampedStart = Math.max(0, Math.min(startMinute, TOTAL_MINUTES - 1));
            int clampedEnd = Math.max(0, Math.min(startMinute + durationMinutes, TOTAL_MINUTES));
            for (int minute = clampedStart; minute < clampedEnd; minute++) {
                occupancyArray[minute] += productCount;
            }
            return true; // Always allow - unlimited capacity
        }

        // For non-main warehouses, enforce capacity limits

        int[] occupancyArray = temporalWarehouseOccupancy.get(airportSchema);
        int maxCapacity = airportSchema.getWarehouse().getMaxCapacity();
        
        // CORRECCIÓN: Verificar y agregar ocupación para cada minuto del período (4 días)
        final int TOTAL_MINUTES = HORIZON_DAYS * 24 * 60;
        int clampedStart = Math.max(0, Math.min(startMinute, TOTAL_MINUTES - 1));
        int clampedEnd = Math.max(0, Math.min(startMinute + durationMinutes, TOTAL_MINUTES));
        for (int minute = clampedStart; minute < clampedEnd; minute++) {
            occupancyArray[minute] += productCount;
            if (occupancyArray[minute] > maxCapacity) {
                return false; // Violación de capacidad
            }
        }
        
        return true;
    }
    
    
    /**
     * Encuentra el minuto del día con mayor ocupación en un aeropuerto específico.
     * 
     * @param airportSchema aeropuerto a analizar
     * @return array [minuto, ocupación_máxima]
     */
    private int[] findPeakOccupancy(AirportSchema airportSchema) {
        int[] occupancyArray = temporalWarehouseOccupancy.get(airportSchema);
        int maxOccupancy = 0;
        int peakMinute = 0;
        
        final int TOTAL_MINUTES = HORIZON_DAYS * 24 * 60;
        for (int minute = 0; minute < TOTAL_MINUTES; minute++) {
            if (occupancyArray[minute] > maxOccupancy) {
                maxOccupancy = occupancyArray[minute];
                peakMinute = minute;
            }
        }
        
        return new int[]{peakMinute, maxOccupancy};
    }

    private int calculateMaxWarehouseCapacity(List<AirportSchema> airportSchemas) {
        if (airportSchemas == null || airportSchemas.isEmpty()) {
            return 0; // O un valor por defecto
        }
        
        int maxCapacity = 0;
        for (AirportSchema airportSchema : airportSchemas) {
            if (airportSchema.getWarehouse() != null) {
                int capacity = airportSchema.getWarehouse().getMaxCapacity();
                maxCapacity += capacity;
            }
        }
        
        return maxCapacity;
    }
}
