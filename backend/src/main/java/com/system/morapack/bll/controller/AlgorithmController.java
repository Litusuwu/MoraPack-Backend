package com.system.morapack.bll.controller;

import com.system.morapack.bll.service.AlgorithmPersistenceService;
import com.system.morapack.config.Constants;
import com.system.morapack.config.DataSourceOverride;
import com.system.morapack.dao.morapack_psql.repository.ProductRepository;
import com.system.morapack.schemas.*;
import com.system.morapack.schemas.CollapseResultSchema;
import com.system.morapack.schemas.algorithm.ALNS.Solution;
import com.system.morapack.schemas.algorithm.TabuSearch.TabuSearch;
import com.system.morapack.schemas.algorithm.TabuSearch.TabuSolution;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AlgorithmController {

  // NEW: Persistence service for batch DB operations
  private final AlgorithmPersistenceService persistenceService;
  
  // NEW: Data load service for collapse scenario
  private final com.system.morapack.bll.service.DataLoadService dataLoadService;
  
  // NEW: Simulation time service for state updates (PENDING -> IN_TRANSIT -> ARRIVED)
  private final com.system.morapack.bll.service.SimulationTimeService simulationTimeService;
  
  // NEW: Product repository for SLA violation checks
  private final ProductRepository productRepository;

  // ==================== COLLAPSE VISUAL SIMULATION STATE ====================
  // These track state across multiple calls to executeCollapseVisualDay()
  // Thread safety note: assumes single simulation at a time per server instance
  
  private volatile boolean collapseVisualInitialized = false;
  private volatile LocalDateTime collapseVisualSimStart = null;
  private volatile int collapseVisualTotalOrdersLoaded = 0;
  private volatile int collapseVisualPreviousBacklog = 0;
  private volatile int collapseVisualConsecutiveGrowingDays = 0;
  
  // Collapse thresholds (same as batch collapse)
  private static final double COLLAPSE_THRESHOLD_PERCENT = 10.0;
  private static final int MAX_GROWING_BACKLOG_DAYS = 5;
  private static final int MAX_SIMULATION_DAYS = 365;

  /**
   * Helper class to group flights by route
   */
  private static class FlightGroupInfo {
    FlightDTO flight;
    LocalDateTime departureTime;
    LocalDateTime arrivalTime;
    List<Integer> productIds = new ArrayList<>();
    List<Integer> orderIds = new ArrayList<>();
  }

  /**
   * Generate simulation timeline with temporal events
   * OPTIMIZED: Groups flights by route instead of creating individual events per product
   */
  private SimulationTimelineResult generateSimulationTimeline(
      List<ProductRouteDTO> productRoutes,
      LocalDateTime simulationStartTime) {
    
    List<FlightTimelineEvent> events = new ArrayList<>();
    Set<Integer> airportsSet = new HashSet<>();
    
    // Group flights by route (origin-destination-time) to avoid duplicates
    Map<String, FlightGroupInfo> flightGroups = new HashMap<>();

    // First pass: Group flights by route and time
    for (ProductRouteDTO productRoute : productRoutes) {
      // Each product starts at simulation start time
      LocalDateTime currentProductTime = simulationStartTime;

      for (int i = 0; i < productRoute.getFlights().size(); i++) {
        FlightDTO flight = productRoute.getFlights().get(i);

        // Track airports
        if (flight.getOriginAirportId() != null) {
          airportsSet.add(flight.getOriginAirportId());
        }
        if (flight.getDestinationAirportId() != null) {
          airportsSet.add(flight.getDestinationAirportId());
        }

        // Use real departure time from flights.txt if available
        LocalDateTime flightDepartureTime;
        LocalDateTime flightArrivalTime;

        if (flight.getDepartureTime() != null && flight.getArrivalTime() != null) {
          // Combine simulation date with flight's scheduled time
          flightDepartureTime = currentProductTime.toLocalDate().atTime(flight.getDepartureTime());

          // If the flight's departure time has already passed today, take it tomorrow
          if (flightDepartureTime.isBefore(currentProductTime)) {
            flightDepartureTime = flightDepartureTime.plusDays(1);
          }

          // Calculate arrival time: flight may cross midnight
          flightArrivalTime = flightDepartureTime.toLocalDate().atTime(flight.getArrivalTime());
          if (flight.getArrivalTime().isBefore(flight.getDepartureTime())) {
            // Flight crosses midnight
            flightArrivalTime = flightArrivalTime.plusDays(1);
          }
        } else {
          // Fallback: use transport time if no real schedule available
          long transportMinutes = (long) ((flight.getTransportTimeDays() != null ?
              flight.getTransportTimeDays() : 1.0) * 24 * 60);
          flightDepartureTime = currentProductTime;
          flightArrivalTime = flightDepartureTime.plusMinutes(transportMinutes);
        }

        // Create unique key for this flight (route + departure time rounded to hour)
        LocalDateTime departureHour = flightDepartureTime.withMinute(0).withSecond(0).withNano(0);
        String flightKey = flight.getOriginAirportId() + "-" +
                          flight.getDestinationAirportId() + "-" +
                          departureHour.toString();

        FlightGroupInfo groupInfo = flightGroups.get(flightKey);
        if (groupInfo == null) {
          groupInfo = new FlightGroupInfo();
          groupInfo.flight = flight;
          groupInfo.departureTime = flightDepartureTime;
          groupInfo.arrivalTime = flightArrivalTime;
          flightGroups.put(flightKey, groupInfo);
        }

        // Add this product to the group
        groupInfo.productIds.add(productRoute.getProductId());
        groupInfo.orderIds.add(productRoute.getOrderId());

        // Next flight: product must wait 1 hour layover after arrival
        currentProductTime = flightArrivalTime.plusMinutes(60);
      }
    }
    
    System.out.println("Grouped " + productRoutes.size() + " product routes into " + 
                      flightGroups.size() + " unique flight groups");
    
    // Second pass: Create events for grouped flights
    int eventCounter = 0;
    for (Map.Entry<String, FlightGroupInfo> entry : flightGroups.entrySet()) {
      FlightGroupInfo group = entry.getValue();
      FlightDTO flight = group.flight;
      
      // Departure event (represents multiple products)
      FlightTimelineEvent departureEvent = FlightTimelineEvent.builder()
          .eventId("DEP-GROUP-" + eventCounter)
          .eventType("DEPARTURE")
          .eventTime(group.departureTime)
          .flightId(flight.getId())
          .flightCode(flight.getCode() + " (" + group.productIds.size() + " pkgs)")
          .productId(group.productIds.get(0)) // Representative product
          .orderId(group.orderIds.get(0))
          .originCity(flight.getOriginCity())
          .destinationCity(flight.getDestinationCity())
          .originAirportId(flight.getOriginAirportId())
          .destinationAirportId(flight.getDestinationAirportId())
          .transportTimeDays(flight.getTransportTimeDays())
          .build();
      
      events.add(departureEvent);
      
      // Arrival event
      FlightTimelineEvent arrivalEvent = FlightTimelineEvent.builder()
          .eventId("ARR-GROUP-" + eventCounter)
          .eventType("ARRIVAL")
          .eventTime(group.arrivalTime)
          .flightId(flight.getId())
          .flightCode(flight.getCode() + " (" + group.productIds.size() + " pkgs)")
          .productId(group.productIds.get(0))
          .orderId(group.orderIds.get(0))
          .originCity(flight.getOriginCity())
          .destinationCity(flight.getDestinationCity())
          .originAirportId(flight.getOriginAirportId())
          .destinationAirportId(flight.getDestinationAirportId())
          .transportTimeDays(flight.getTransportTimeDays())
          .build();
      
      events.add(arrivalEvent);
      eventCounter++;
    }
    
    // Sort events by time
    events.sort((e1, e2) -> e1.getEventTime().compareTo(e2.getEventTime()));
    
    // Find simulation end time
    LocalDateTime endTime = events.isEmpty() ? simulationStartTime : 
        events.get(events.size() - 1).getEventTime();
    
    long durationMinutes = java.time.temporal.ChronoUnit.MINUTES.between(
        simulationStartTime, endTime);
    
    return SimulationTimelineResult.builder()
        .simulationStartTime(simulationStartTime)
        .simulationEndTime(endTime)
        .totalDurationMinutes(durationMinutes)
        .events(events)
        .productRoutes(productRoutes)
        .totalProducts(productRoutes.size())
        .totalFlights(flightGroups.size()) // Unique grouped flights
        .totalAirports(airportsSet.size())
        .build();
  }

  /**
   * Convert FlightSchema list to FlightDTO list to avoid circular references
   */
  private List<FlightDTO> convertFlightsToDTO(List<FlightSchema> flights) {
    List<FlightDTO> flightDTOs = new ArrayList<>();
    
    for (FlightSchema flight : flights) {
      String originCity = "Unknown";
      String destinationCity = "Unknown";
      Integer originAirportId = null;
      Integer destinationAirportId = null;
      
      if (flight.getOriginAirportSchema() != null) {
        originAirportId = flight.getOriginAirportSchema().getId();
        if (flight.getOriginAirportSchema().getCitySchema() != null) {
          originCity = flight.getOriginAirportSchema().getCitySchema().getName();
        }
      }
      
      if (flight.getDestinationAirportSchema() != null) {
        destinationAirportId = flight.getDestinationAirportSchema().getId();
        if (flight.getDestinationAirportSchema().getCitySchema() != null) {
          destinationCity = flight.getDestinationAirportSchema().getCitySchema().getName();
        }
      }
      
      FlightDTO flightDTO = FlightDTO.builder()
          .id(flight.getId())
          .code(flight.getCode())
          .routeType(flight.getRouteType())
          .maxCapacity(flight.getMaxCapacity())
          .transportTimeDays(flight.getTransportTimeDays())
          .status(flight.getStatus() != null ? flight.getStatus().toString() : "UNKNOWN")
          .originCity(originCity)
          .destinationCity(destinationCity)
          .originAirportId(originAirportId)
          .destinationAirportId(destinationAirportId)
          .departureTime(flight.getDepartureTime())  // Include real departure time
          .arrivalTime(flight.getArrivalTime())      // Include real arrival time
          .build();

      flightDTOs.add(flightDTO);
    }
    
    return flightDTOs;
  }

  /**
   * Execute DAILY SCENARIO: Incremental time window processing
   * Loads only orders within a small time window (e.g., 30 minutes)
   */
  public AlgorithmResultSchema executeDailyScenario(AlgorithmRequest request) {
    LocalDateTime executionStartTime = LocalDateTime.now();

    try {
      System.out.println("===========================================");
      System.out.println("EXECUTING DAILY SCENARIO");
      System.out.println("===========================================");
      
      // Log incoming request
      System.out.println("REQUEST PARAMETERS:");
      System.out.println("  simulationStartTime: " + request.getSimulationStartTime());
      System.out.println("  simulationDurationHours: " + request.getSimulationDurationHours());
      System.out.println("  useDatabase: " + request.getUseDatabase());
      System.out.println("  simulationSpeed: " + request.getSimulationSpeed());

      // Extract simulation speed (default to 1x if not provided)
      double simulationSpeed = request.getSimulationSpeed() != null ? 
          request.getSimulationSpeed() : 1.0;
      
      System.out.println("Simulation Speed Multiplier: " + simulationSpeed + "x");
      if (simulationSpeed != 1.0) {
        System.out.println("Note: Frontend will advance simulation time at " + 
            simulationSpeed + "x normal rate");
      }

      // Calculate simulation window
      LocalDateTime simStart = request.getSimulationStartTime();
      LocalDateTime simEnd = calculateSimulationEndTime(request);

      System.out.println("Simulation window: " + simStart + " to " + simEnd);
      System.out.println("Window duration: " + java.time.temporal.ChronoUnit.HOURS.between(simStart, simEnd) + " hours");

      // Execute ALNS with time window
      System.out.println("\n=== STARTING ALNS EXECUTION ===");
      
      // FORCE DATABASE MODE if requested: This ensures we only load unassigned orders from the DB
      // instead of re-loading everything from files. This prevents deadlocks and re-assignment of completed orders.
      if (Boolean.TRUE.equals(request.getUseDatabase())) {
          DataSourceOverride.setOverride(Constants.DataSourceMode.DATABASE);
      }
      
      Solution alnsSolution;
      try {
          alnsSolution = new Solution(simStart, simEnd);
          alnsSolution.solve();
      } finally {
          if (Boolean.TRUE.equals(request.getUseDatabase())) {
              DataSourceOverride.clearOverride();
          }
      }

      LocalDateTime executionEndTime = LocalDateTime.now();
      long executionTime = ChronoUnit.SECONDS.between(executionStartTime, executionEndTime);
      
      System.out.println("\n=== ALNS EXECUTION COMPLETED ===");
      System.out.println("Execution time: " + executionTime + " seconds (" + (executionTime / 60) + "m " + (executionTime % 60) + "s)");

      // NEW: Get order splits for batch persistence
      Map<String, List<Solution.OrderSplitInfo>> orderSplits = alnsSolution.getOrderSplits();
      int productsCreated = 0;
      Set<Integer> persistedOrderIds = new HashSet<>();

      if (orderSplits != null && !orderSplits.isEmpty()) {
        System.out.println("\n=== PERSISTING ORDER SPLITS TO DATABASE WITH FLIGHT INSTANCES ===");
        List<AlgorithmPersistenceService.OrderSplitWithInstances> persistenceSplits =
            convertToOrderSplitsWithInstances(orderSplits);
        List<AlgorithmPersistenceService.OrderSplitWithInstances> realtimeEligibleSplits =
            shouldApplyRealtimeFilter(request) ?
                filterRealtimeEligibleSplits(persistenceSplits, simStart) :
                persistenceSplits;

        if (realtimeEligibleSplits.isEmpty()) {
          System.out.println("No order splits eligible for persistence in this window.");
        } else {
          productsCreated = persistenceService.persistSolutionWithInstances(realtimeEligibleSplits);
          System.out.println("Persisted " + productsCreated + " product records with flight instances");

          persistedOrderIds.addAll(realtimeEligibleSplits.stream()
              .map(split -> extractNumericOrderId(split.getOrderName()))
              .filter(Objects::nonNull)
              .collect(java.util.stream.Collectors.toSet()));
        }
      } else {
        System.out.println("No order splits to persist");
      }

      // Get the product-level solution (may be empty if using orderSplits)
      Map<ProductSchema, ArrayList<FlightSchema>> productSolution = alnsSolution.getProductLevelSolution();
      
      System.out.println("\nDEBUG: productSolution size = " + (productSolution != null ? productSolution.size() : "NULL"));
      System.out.println("DEBUG: productsCreated = " + productsCreated);
      System.out.println("DEBUG: persistedOrderIds = " + persistedOrderIds.size());

      // Convert to result with simulation time info
      AlgorithmResultSchema result = convertALNSSolutionToResult(productSolution, executionStartTime,
                                                                  executionEndTime, executionTime,
                                                                  simStart, simEnd, productsCreated, persistedOrderIds);

      // Update message with persistence info
      String message = "ALNS algorithm executed successfully. " +
                      "Products persisted: " + productsCreated;
      result.setMessage(message);
      
      // Log final statistics
      System.out.println("\n=== FINAL STATISTICS ===");
      System.out.println("Total orders: " + result.getTotalOrders());
      System.out.println("Assigned orders: " + result.getAssignedOrders());
      System.out.println("Unassigned orders: " + result.getUnassignedOrders());
      System.out.println("Total products: " + result.getTotalProducts());
      System.out.println("Assigned products: " + result.getAssignedProducts());
      System.out.println("Unassigned products: " + result.getUnassignedProducts());
      System.out.println("Score: " + result.getScore());
      System.out.println("===========================================\n");

      return result;

    } catch (Exception e) {
      LocalDateTime executionEndTime = LocalDateTime.now();
      System.out.println("\n!!! ALGORITHM EXECUTION FAILED !!!");
      System.out.println("Error: " + e.getMessage());
      e.printStackTrace();
      System.out.println("===========================================\n");
      
      return AlgorithmResultSchema.builder()
          .success(false)
          .message("Daily scenario execution failed: " + e.getMessage())
          .executionStartTime(executionStartTime)
          .executionEndTime(executionEndTime)
          .executionTimeSeconds(ChronoUnit.SECONDS.between(executionStartTime, executionEndTime))
          .build();
    }
  }

  /**
   * Execute WEEKLY SCENARIO: 7-day batch processing
   * Loads all orders from 7 days and returns complete solution
   */
  public AlgorithmResultSchema executeWeeklyScenario(AlgorithmRequest request) {
    LocalDateTime executionStartTime = LocalDateTime.now();

    try {
      System.out.println("===========================================");
      System.out.println("EXECUTING WEEKLY SCENARIO (7 DAYS)");
      System.out.println("===========================================");

      // Calculate simulation window (always 7 days for weekly)
      LocalDateTime simStart = request.getSimulationStartTime();
      LocalDateTime simEnd = simStart.plusDays(7);

      System.out.println("Simulation window: " + simStart + " to " + simEnd);
      System.out.println("Expected execution time: 30-90 minutes");

      // Execute ALNS with time window
      Solution alnsSolution = new Solution(simStart, simEnd);
      alnsSolution.solve();

      LocalDateTime executionEndTime = LocalDateTime.now();
      long executionTime = ChronoUnit.SECONDS.between(executionStartTime, executionEndTime);

      System.out.println("Actual execution time: " + executionTime + " seconds (" +
                        (executionTime / 60) + " minutes)");

      // NEW: Get order splits for batch persistence
      Map<String, List<Solution.OrderSplitInfo>> orderSplits = alnsSolution.getOrderSplits();
      int productsCreated = 0;

      if (orderSplits != null && !orderSplits.isEmpty()) {
        System.out.println("\n=== PERSISTING ORDER SPLITS TO DATABASE WITH FLIGHT INSTANCES ===");
        List<AlgorithmPersistenceService.OrderSplitWithInstances> persistenceSplits = convertToOrderSplitsWithInstances(orderSplits);
        productsCreated = persistenceService.persistSolutionWithInstances(persistenceSplits);
        System.out.println("Persisted " + productsCreated + " product records with flight instances");
      } else {
        System.out.println("No order splits to persist");
      }

      // Get the product-level solution
      Map<ProductSchema, ArrayList<FlightSchema>> productSolution = alnsSolution.getProductLevelSolution();

      // Convert to result with simulation time info
      AlgorithmResultSchema result = convertALNSSolutionToResult(productSolution, executionStartTime,
                                                                  executionEndTime, executionTime,
                                                                  simStart, simEnd);

      // Update message with persistence info
      String message = "ALNS algorithm executed successfully. " +
                      "Execution time: " + (executionTime / 60) + " minutes. " +
                      "Products persisted: " + productsCreated;
      result.setMessage(message);

      return result;

    } catch (Exception e) {
      LocalDateTime executionEndTime = LocalDateTime.now();
      e.printStackTrace();
      return AlgorithmResultSchema.builder()
          .success(false)
          .message("Weekly scenario execution failed: " + e.getMessage())
          .executionStartTime(executionStartTime)
          .executionEndTime(executionEndTime)
          .executionTimeSeconds(ChronoUnit.SECONDS.between(executionStartTime, executionEndTime))
          .build();
    }
  }

  /**
   * NEW: Convert Solution's OrderSplitInfo to AlgorithmPersistenceService's OrderSplit
   * Enables batch persistence of order splits to database
   * @deprecated Use convertToOrderSplitsWithInstances() instead (includes flight instances)
   */
  @Deprecated
  private List<AlgorithmPersistenceService.OrderSplit> convertToOrderSplits(
      Map<String, List<Solution.OrderSplitInfo>> orderSplitsMap) {

    List<AlgorithmPersistenceService.OrderSplit> splits = new ArrayList<>();

    for (Map.Entry<String, List<Solution.OrderSplitInfo>> entry : orderSplitsMap.entrySet()) {
      String orderName = entry.getKey();
      List<Solution.OrderSplitInfo> splitInfos = entry.getValue();

      for (Solution.OrderSplitInfo splitInfo : splitInfos) {
        AlgorithmPersistenceService.OrderSplit split =
            new AlgorithmPersistenceService.OrderSplit(
                orderName,
                splitInfo.quantity,
                splitInfo.assignedRoute
            );
        splits.add(split);
      }
    }

    return splits;
  }

  /**
   * Parse latitude/longitude string to Double
   */
  private Double parseLatLng(String value) {
    if (value == null || value.trim().isEmpty()) {
      return null;
    }
    try {
      return Double.parseDouble(value);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * Convert Solution's OrderSplitInfo to OrderSplitWithInstances
   * Includes flight instances with departure times for state simulation
   */
  private List<AlgorithmPersistenceService.OrderSplitWithInstances> convertToOrderSplitsWithInstances(
      Map<String, List<Solution.OrderSplitInfo>> orderSplitsMap) {

    List<AlgorithmPersistenceService.OrderSplitWithInstances> splits = new ArrayList<>();

    for (Map.Entry<String, List<Solution.OrderSplitInfo>> entry : orderSplitsMap.entrySet()) {
      String orderName = entry.getKey();
      List<Solution.OrderSplitInfo> splitInfos = entry.getValue();

      for (Solution.OrderSplitInfo splitInfo : splitInfos) {
        AlgorithmPersistenceService.OrderSplitWithInstances split =
            new AlgorithmPersistenceService.OrderSplitWithInstances(
                orderName,
                splitInfo.quantity,
                splitInfo.assignedFlightInstances
            );
        splits.add(split);
      }
    }

    return splits;
  }

  /**
   * Filter order splits so only those whose first flight departs after the realtime cursor are
   * persisted. This prevents assigning packages onto departures that already left the hub.
   * 
   * NOTE: This filter is DISABLED for Daily Simulation (<= 1 hour windows).
   * In Daily Simulation, we want to use ALL available flights, not just those
   * departing within the short time window.
   */
  private boolean shouldApplyRealtimeFilter(AlgorithmRequest request) {
    // DISABLED for Daily Simulation - we need to use all available flights
    // Daily Simulation only filters ORDERS by delivery date, not flights by departure time
    return false;
    
    /* OLD LOGIC - Re-enable if needed for other scenarios
    if (request == null) {
      return false;
    }

    if (request.getSimulationDurationHours() == null) {
      return false;
    }

    // Consider realtime windows any slice <= 1 hour
    return request.getSimulationDurationHours() <= 1.0;
    */
  }

  private List<AlgorithmPersistenceService.OrderSplitWithInstances> filterRealtimeEligibleSplits(
      List<AlgorithmPersistenceService.OrderSplitWithInstances> splits,
      LocalDateTime realtimeWindowStart) {

    if (splits == null || splits.isEmpty() || realtimeWindowStart == null) {
      return splits;
    }

    List<AlgorithmPersistenceService.OrderSplitWithInstances> filtered = new ArrayList<>();

    for (AlgorithmPersistenceService.OrderSplitWithInstances split : splits) {
      LocalDateTime firstDeparture = null;

      if (split.getAssignedFlightInstances() != null) {
        firstDeparture = split.getAssignedFlightInstances().stream()
            .map(FlightInstanceSchema::getDepartureDateTime)
            .filter(Objects::nonNull)
            .sorted()
            .findFirst()
            .orElse(null);
      }

      if (firstDeparture == null || !firstDeparture.isBefore(realtimeWindowStart)) {
        filtered.add(split);
      } else {
        System.out.println(
            "Skipping split for " + split.getOrderName() + " (first departure " + firstDeparture +
                " < window start " + realtimeWindowStart + ")");
      }
    }

    return filtered;
  }

  private Integer extractNumericOrderId(String orderName) {
    if (orderName == null) {
      return null;
    }

    java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)").matcher(orderName);
    if (matcher.find()) {
      try {
        return Integer.parseInt(matcher.group(1));
      } catch (NumberFormatException ignored) {
        return null;
      }
    }

    return null;
  }

  /**
   * Calculate simulation end time based on request parameters
   */
  private LocalDateTime calculateSimulationEndTime(AlgorithmRequest request) {
    LocalDateTime startTime = request.getSimulationStartTime();

    // If endTime is explicitly provided, use it
    if (request.getSimulationEndTime() != null) {
      return request.getSimulationEndTime();
    }

    // Otherwise calculate from duration
    if (request.getSimulationDurationDays() != null && request.getSimulationDurationDays() > 0) {
      return startTime.plusDays(request.getSimulationDurationDays());
    }

    if (request.getSimulationDurationHours() != null && request.getSimulationDurationHours() > 0) {
      long minutes = (long) (request.getSimulationDurationHours() * 60);
      return startTime.plusMinutes(minutes);
    }

    // Default: 1 hour window
    return startTime.plusHours(1);
  }

  /**
   * Execute algorithm based on request parameters (LEGACY METHOD)
   * @deprecated Use executeDailyScenario or executeWeeklyScenario instead
   */
  @Deprecated
  public AlgorithmResultSchema executeAlgorithm(AlgorithmRequest request) {
    LocalDateTime startTime = LocalDateTime.now();

    try {
      // Set data source mode if specified
      if (request.getUseDatabase() != null) {
        // This would require modifying Constants or passing to algorithm constructors
        System.out.println("Using data source: " + (request.getUseDatabase() ? "DATABASE" : "FILE"));
      }

      String algorithmType = request.getAlgorithmType() != null ?
          request.getAlgorithmType().toUpperCase() : "TABU";

      AlgorithmResultSchema result;

      switch (algorithmType) {
        case "ALNS":
          result = executeALNS(request, startTime);
          break;
        case "TABU":
        default:
          result = executeTabuSearch(request, startTime);
          break;
      }

      return result;

    } catch (Exception e) {
      LocalDateTime endTime = LocalDateTime.now();
      return AlgorithmResultSchema.builder()
          .success(false)
          .message("Algorithm execution failed: " + e.getMessage())
          .algorithmType(request.getAlgorithmType())
          .executionStartTime(startTime)
          .executionEndTime(endTime)
          .executionTimeSeconds(ChronoUnit.SECONDS.between(startTime, endTime))
          .build();
    }
  }

  /**
   * Execute ALNS algorithm
   */
  private AlgorithmResultSchema executeALNS(AlgorithmRequest request, LocalDateTime startTime) {
    System.out.println("===========================================");
    System.out.println("EXECUTING ALNS ALGORITHM VIA API");
    System.out.println("===========================================");

    Solution alnsSolution = new Solution();
    alnsSolution.solve();

    LocalDateTime endTime = LocalDateTime.now();
    long executionTime = ChronoUnit.SECONDS.between(startTime, endTime);

    // Get the product-level solution from ALNS
    Map<ProductSchema, ArrayList<FlightSchema>> productSolution = alnsSolution.getProductLevelSolution();

    System.out.println("\n=== VERIFICANDO SOLUCIÓN ALNS ===");
    System.out.println("ProductSolution es nulo: " + (productSolution == null));
    if (productSolution != null) {
      System.out.println("Tamaño de productSolution: " + productSolution.size());
      System.out.println("Productos con rutas asignadas: " + productSolution.size());
    }

    // Convert to AlgorithmResultSchema with product routes
    return convertALNSSolutionToResult(productSolution, startTime, endTime, executionTime);
  }

  /**
   * Convert ALNS product-level solution to AlgorithmResultSchema (with simulation time)
   * Now accepts productsCreated count to handle orderSplits persistence
   */
  private AlgorithmResultSchema convertALNSSolutionToResult(
      Map<ProductSchema, ArrayList<FlightSchema>> productSolution,
      LocalDateTime executionStartTime,
      LocalDateTime executionEndTime,
      long executionTime,
      LocalDateTime simulationStartTime,
      LocalDateTime simulationEndTime,
      int productsCreated,
      Set<Integer> persistedOrderIds) {

    System.out.println("\n=== CONVERTING ALNS SOLUTION TO RESULT (with simulation time) ===");
    System.out.println("Products in solution map: " + (productSolution != null ? productSolution.size() : "NULL"));
    System.out.println("Products persisted to DB: " + productsCreated);
    System.out.println("Orders persisted: " + persistedOrderIds.size());
    System.out.println("Simulation window: " + simulationStartTime + " to " + simulationEndTime);
    System.out.println("NOTE: productRoutes NOT included (frontend queries DB directly)");

    int assignedProductsCount = 0;
    int unassignedProductsCount = 0;
    Set<Integer> assignedOrders = new HashSet<>();

    // PRIMARY: Use persisted products count (more reliable for orderSplits)
    if (productsCreated > 0) {
      System.out.println("Using persisted products count: " + productsCreated);
      assignedProductsCount = productsCreated;
      assignedOrders.addAll(persistedOrderIds);
    } else if (productSolution != null && !productSolution.isEmpty()) {
      // FALLBACK: Use productSolution if no persisted products
      System.out.println("Falling back to productSolution count: " + productSolution.size());
      
      for (Map.Entry<ProductSchema, ArrayList<FlightSchema>> entry : productSolution.entrySet()) {
        ProductSchema product = entry.getKey();
        ArrayList<FlightSchema> flights = entry.getValue();

        if (product != null && flights != null && !flights.isEmpty()) {
          assignedProductsCount++;

          // Track order
          if (product.getOrderId() != null) {
            assignedOrders.add(product.getOrderId());
          }
        }
      }
    } else {
      System.out.println("WARNING: No products found in solution or persisted data");
    }

    System.out.println("Final assigned products: " + assignedProductsCount);
    System.out.println("Final assigned orders: " + assignedOrders.size());
    System.out.println("productRoutes: NULL (use query endpoints instead)");

    return AlgorithmResultSchema.builder()
        .success(true)
        .message("ALNS algorithm executed successfully. Use /api/query endpoints to retrieve results.")
        .executionStartTime(executionStartTime)
        .executionEndTime(executionEndTime)
        .executionTimeSeconds(executionTime)
        .simulationStartTime(simulationStartTime)
        .simulationEndTime(simulationEndTime)
        .totalOrders(assignedOrders.size())
        .assignedOrders(assignedOrders.size())
        .unassignedOrders(0)
        .totalProducts(assignedProductsCount)
        .assignedProducts(assignedProductsCount)
        .unassignedProducts(unassignedProductsCount)
        .score((double) assignedProductsCount)
        .productRoutes(null)
        .build();
  }
  
  /**
   * Convert ALNS product-level solution to AlgorithmResultSchema (with simulation time)
   * OLD SIGNATURE - kept for compatibility
   * @deprecated Use version with productsCreated and persistedOrderIds
   */
  private AlgorithmResultSchema convertALNSSolutionToResult(
      Map<ProductSchema, ArrayList<FlightSchema>> productSolution,
      LocalDateTime executionStartTime,
      LocalDateTime executionEndTime,
      long executionTime,
      LocalDateTime simulationStartTime,
      LocalDateTime simulationEndTime) {

    return convertALNSSolutionToResult(
        productSolution, 
        executionStartTime, 
        executionEndTime, 
        executionTime, 
        simulationStartTime, 
        simulationEndTime, 
        0, 
        new HashSet<>()
    );
  }

  /**
   * Convert ALNS product-level solution to AlgorithmResultSchema (LEGACY - no simulation time)
   * @deprecated Use version with simulation time parameters
   */
  @Deprecated
  private AlgorithmResultSchema convertALNSSolutionToResult(
      Map<ProductSchema, ArrayList<FlightSchema>> productSolution,
      LocalDateTime startTime,
      LocalDateTime endTime,
      long executionTime) {

    System.out.println("\n=== CONVIRTIENDO SOLUCIÓN ALNS A RESULTADO (LEGACY) ===");
    System.out.println("Productos en solución: " + (productSolution != null ? productSolution.size() : "NULL"));

    List<ProductRouteDTO> productRoutes = new ArrayList<>();
    int assignedProductsCount = 0;
    int unassignedProductsCount = 0;
    Set<Integer> assignedOrders = new HashSet<>();

    if (productSolution == null || productSolution.isEmpty()) {
      System.out.println("WARNING: productSolution está vacío o nulo");
      return AlgorithmResultSchema.builder()
          .success(true)
          .message("ALNS algorithm executed but no products were assigned")
          .executionStartTime(startTime)
          .executionEndTime(endTime)
          .executionTimeSeconds(executionTime)
          .totalOrders(0)
          .assignedOrders(0)
          .unassignedOrders(0)
          .totalProducts(0)
          .assignedProducts(0)
          .unassignedProducts(0)
          .score(0.0)
          .productRoutes(new ArrayList<>())
          .build();
    }

    // Convert each product's route to ProductRouteSchema
    for (Map.Entry<ProductSchema, ArrayList<FlightSchema>> entry : productSolution.entrySet()) {
      ProductSchema product = entry.getKey();
      ArrayList<FlightSchema> flights = entry.getValue();

      if (flights != null && !flights.isEmpty()) {
        assignedProductsCount++;

        // Track which orders have at least one product assigned
        if (product.getOrderId() != null) {
          assignedOrders.add(product.getOrderId());
        }

        // Extract origin and destination from flights
        String originCity = "Unknown";
        String destinationCity = "Unknown";

        if (!flights.isEmpty()) {
          FlightSchema firstFlight = flights.get(0);
          FlightSchema lastFlight = flights.get(flights.size() - 1);

          if (firstFlight.getOriginAirportSchema() != null &&
              firstFlight.getOriginAirportSchema().getCitySchema() != null) {
            originCity = firstFlight.getOriginAirportSchema().getCitySchema().getName();
          }

          if (lastFlight.getDestinationAirportSchema() != null &&
              lastFlight.getDestinationAirportSchema().getCitySchema() != null) {
            destinationCity = lastFlight.getDestinationAirportSchema().getCitySchema().getName();
          }
        }

        // Convert FlightSchema to FlightDTO to avoid circular references
        List<FlightDTO> flightDTOs = convertFlightsToDTO(flights);
        
        ProductRouteDTO productRoute = ProductRouteDTO.builder()
            .productId(product.getId())
            .orderId(product.getOrderId())
            .orderName(product.getOrderId() != null ?
                "Order-" + product.getOrderId() : "Product-" + product.getId())
            .flights(flightDTOs)
            .originCity(originCity)
            .destinationCity(destinationCity)
            .flightCount(flights.size())
            .build();

        productRoutes.add(productRoute);
      } else {
        unassignedProductsCount++;
      }
    }

    int totalProducts = assignedProductsCount + unassignedProductsCount;

    System.out.println("===========================================");
    System.out.println("ALNS EXECUTION COMPLETED");
    System.out.println("Total products: " + totalProducts);
    System.out.println("Assigned products: " + assignedProductsCount);
    System.out.println("Unassigned products: " + unassignedProductsCount);
    System.out.println("Orders with assignments: " + assignedOrders.size());
    System.out.println("Execution time: " + executionTime + " seconds");
    System.out.println("===========================================");

    // Generate temporal simulation timeline
    System.out.println("\n=== GENERATING SIMULATION TIMELINE ===");
    SimulationTimelineResult timeline = generateSimulationTimeline(productRoutes, startTime);
    System.out.println("Timeline events: " + timeline.getEvents().size());
    System.out.println("Simulation duration: " + timeline.getTotalDurationMinutes() + " minutes");
    System.out.println("=====================================\n");

    return AlgorithmResultSchema.builder()
        .success(true)
        .message("ALNS algorithm executed successfully" +
                (unassignedProductsCount > 0 ?
                    " (" + unassignedProductsCount + " products could not be assigned)" :
                    " (all products assigned)"))
        .algorithmType("ALNS")
        .executionStartTime(startTime)
        .executionEndTime(endTime)
        .executionTimeSeconds(executionTime)
        .totalOrders(assignedOrders.size())
        .assignedOrders(assignedOrders.size())
        .unassignedOrders(0) // ALNS works at product level
        .totalProducts(totalProducts)
        .score((double) assignedProductsCount) // Score based on assigned products
        .productRoutes(productRoutes)
        .timeline(timeline)
        .build();
  }

  /**
   * Execute Tabu Search algorithm
   */
  private AlgorithmResultSchema executeTabuSearch(AlgorithmRequest request, LocalDateTime startTime) {
    System.out.println("===========================================");
    System.out.println("EXECUTING TABU SEARCH ALGORITHM VIA API");
    System.out.println("===========================================");

    // Set default parameters if not provided
    int maxIterations = request.getMaxIterations() != null ? request.getMaxIterations() : 1000;
    int maxNoImprovement = request.getMaxNoImprovement() != null ? request.getMaxNoImprovement() : 100;
    int neighborhoodSize = request.getNeighborhoodSize() != null ? request.getNeighborhoodSize() : 100;
    int tabuListSize = request.getTabuListSize() != null ? request.getTabuListSize() : 50;
    long tabuTenure = request.getTabuTenure() != null ? request.getTabuTenure() : 10000L;

    TabuSearch tabuSearch = new TabuSearch(
        Constants.AIRPORT_INFO_FILE_PATH,
        Constants.FLIGHTS_FILE_PATH,
        Constants.PRODUCTS_FILE_PATH,
        maxIterations,
        maxNoImprovement,
        neighborhoodSize,
        tabuListSize,
        tabuTenure
    );

    TabuSolution bestSolution = tabuSearch.solve();
    LocalDateTime endTime = LocalDateTime.now();
    long executionTime = ChronoUnit.SECONDS.between(startTime, endTime);

    // Convert TabuSolution to our response format
    return convertTabuSolutionToResult(bestSolution, startTime, endTime, executionTime);
  }

  /**
   * Convert TabuSolution to AlgorithmResultSchema with product routes
   */
  private AlgorithmResultSchema convertTabuSolutionToResult(
      TabuSolution tabuSolution,
      LocalDateTime startTime,
      LocalDateTime endTime,
      long executionTime) {

    HashMap<OrderSchema, ArrayList<FlightSchema>> solution = tabuSolution.getSolution();

    List<ProductRouteDTO> productRoutes = new ArrayList<>();
    int assignedCount = 0;

    // Convert each order's route to ProductRouteSchema
    for (Map.Entry<OrderSchema, ArrayList<FlightSchema>> entry : solution.entrySet()) {
      OrderSchema order = entry.getKey();
      ArrayList<FlightSchema> flights = entry.getValue();

      if (flights != null && !flights.isEmpty()) {
        assignedCount++;

        // Get product information from the order
        ArrayList<ProductSchema> products = order.getProductSchemas();

        // Convert flights to DTO
        List<FlightDTO> flightDTOs = convertFlightsToDTO(flights);
        
        if (products != null && !products.isEmpty()) {
          // Create a route for each product in the order
          for (ProductSchema product : products) {
            ProductRouteDTO productRoute = ProductRouteDTO.builder()
                .productId(product.getId())
                .orderId(order.getId())
                .orderName(order.getCustomerSchema() != null ?
                    order.getCustomerSchema().getName() : "Order-" + order.getId())
                .flights(flightDTOs) // Use DTO list
                .originCity(order.getCurrentLocation() != null ?
                    order.getCurrentLocation().getName() : "Unknown")
                .destinationCity(order.getDestinationCitySchema() != null ?
                    order.getDestinationCitySchema().getName() : "Unknown")
                .flightCount(flights.size())
                .build();

            productRoutes.add(productRoute);
          }
        } else {
          // If no products, create one route for the order itself
          ProductRouteDTO productRoute = ProductRouteDTO.builder()
              .productId(null)
              .orderId(order.getId())
              .orderName("Order-" + order.getId())
              .flights(flightDTOs) // Use DTO list
              .originCity(order.getCurrentLocation() != null ?
                  order.getCurrentLocation().getName() : "Unknown")
              .destinationCity(order.getDestinationCitySchema() != null ?
                  order.getDestinationCitySchema().getName() : "Unknown")
              .flightCount(flights.size())
              .build();

          productRoutes.add(productRoute);
        }
      }
    }

    int unassignedCount = tabuSolution.getUnassignedPackagesCount();
    int totalOrders = assignedCount + unassignedCount;

    return AlgorithmResultSchema.builder()
        .success(true)
        .message("Tabu Search algorithm executed successfully")
        .algorithmType("TABU")
        .executionStartTime(startTime)
        .executionEndTime(endTime)
        .executionTimeSeconds(executionTime)
        .totalOrders(totalOrders)
        .assignedOrders(assignedCount)
        .unassignedOrders(unassignedCount)
        .totalProducts(productRoutes.size())
        .score((double) tabuSolution.getScore())
        .productRoutes(productRoutes)
        .build();
  }

  /**
   * Execute COLLAPSE SCENARIO: Auto-load ALL orders and run algorithm until saturation
   * 
   * Strategy:
   * 1. Clear existing orders from database
   * 2. Simulate DAY BY DAY, loading orders incrementally
   * 3. Execute ALNS for each day's orders
   * 4. STOP at first day where any product cannot be assigned (SLA violation)
   * 5. Report the collapse day and statistics
   * 
   * Expected execution time: Minutes (stops at first problem, not hours)
   * 
   * COLLAPSE DEFINITION (SLA-based):
   * - Continental orders: must be delivered within 2 days (48 hours)
   * - Intercontinental orders: must be delivered within 3 days (72 hours)
   * - System collapses when ANY product cannot be assigned within SLA
   * 
   * ACCUMULATIVE LOGIC:
   * - Orders are loaded incrementally day by day
   * - Orders NOT deleted between days - they accumulate in the database
   * - Algorithm runs on ALL pending orders (new + previously unassigned)
   * - Collapse occurs when backlog grows uncontrollably
   */
  public CollapseResultSchema executeCollapseScenario(AlgorithmRequest request) {
    LocalDateTime executionStartTime = LocalDateTime.now();
    
    System.out.println("===========================================");
    System.out.println("EXECUTING COLLAPSE SCENARIO (ACCUMULATIVE)");
    System.out.println("SLA Rules:");
    System.out.println("  - Continental: ≤ 48 hours (2 days)");
    System.out.println("  - Intercontinental: ≤ 72 hours (3 days)");
    System.out.println("  - Mode: ACCUMULATIVE - orders pile up until collapse");
    System.out.println("===========================================");
    
    LocalDateTime simStart = request.getSimulationStartTime();
    final int MAX_DAYS = 365; // Maximum days to simulate before giving up
    
    // Collapse threshold: percentage of TOTAL ACCUMULATED products that are unassigned
    final double COLLAPSE_THRESHOLD_PERCENT = 10.0; // 10% of total backlog unassigned = collapse
    
    // Also track consecutive days with growing backlog
    int consecutiveGrowingBacklogDays = 0;
    final int MAX_GROWING_BACKLOG_DAYS = 5; // 5 days of growing backlog = collapse
    int previousUnassigned = 0;
    
    boolean hasCollapsed = false;
    String collapseReason = "NONE";
    int collapseDay = 0;
    LocalDateTime collapseTime = null;
    
    // Cumulative statistics
    int totalOrdersLoaded = 0;
    int totalProductsInSystem = 0;  // All products ever loaded
    int currentAssignedProducts = 0;
    int currentUnassignedProducts = 0;
    
    // SLA tracking
    int productsOnTime = 0;
    int productsLate = 0;
    int continentalTotal = 0;
    int continentalOnTime = 0;
    int intercontinentalTotal = 0;
    int intercontinentalOnTime = 0;
    
    List<CollapseResultSchema.DayStatistics> dailyStats = new ArrayList<>();
    
    try {
      // STEP 1: Clear existing data (start fresh)
      System.out.println("\n=== STEP 1: CLEARING EXISTING DATA ===");
      dataLoadService.clearAllOrders();
      System.out.println("Database cleared successfully");
      
      // STEP 2: Simulate day by day with ACCUMULATION
      System.out.println("\n=== STEP 2: SIMULATING DAY BY DAY (ACCUMULATIVE) ===");
      System.out.println("Orders will ACCUMULATE - unassigned orders carry over to next day");
      
      for (int day = 1; day <= MAX_DAYS && !hasCollapsed; day++) {
        LocalDateTime dayStart = simStart.plusDays(day - 1);
        LocalDateTime dayEnd = dayStart.plusDays(1);
        
        System.out.println("\n--- Day " + day + ": " + dayStart.toLocalDate() + " ---");
        
        // Load NEW orders for this day (they ADD to existing orders in DB)
        com.system.morapack.bll.service.DataLoadService.LoadOrdersResult loadResult =
            dataLoadService.loadOrdersFromFiles(
                com.system.morapack.config.Constants.ORDER_FILES_DIRECTORY,
                dayStart,
                dayEnd,
                false  // Check for duplicates
            );
        
        int newOrdersToday = loadResult.ordersCreated;
        totalOrdersLoaded += newOrdersToday;
        
        System.out.println("  New orders loaded today: " + newOrdersToday);
        System.out.println("  Total orders in system: " + totalOrdersLoaded);
        
        // Execute algorithm on ALL orders in the system (from simStart to current horizon)
        // This includes previously unassigned orders!
        LocalDateTime algoStart = simStart;  // Start from beginning
        LocalDateTime algoEnd = dayStart.plusDays(Constants.HORIZON_DAYS);
        
        System.out.println("  Executing ALNS on ALL orders from " + algoStart.toLocalDate() + " to " + algoEnd.toLocalDate());
        
        Solution alnsSolution = new Solution(algoStart, algoEnd);
        alnsSolution.solve();
        
        // Count results from order splits
        Map<String, List<Solution.OrderSplitInfo>> orderSplits = alnsSolution.getOrderSplits();
        int dayAssigned = 0;
        int dayUnassigned = 0;
        int dayTotal = 0;
        int dayLocalDelivery = 0;
        
        System.out.println("  OrderSplits returned: " + (orderSplits != null ? orderSplits.size() : "null") + " orders");
        
        if (orderSplits != null && !orderSplits.isEmpty()) {
          for (List<Solution.OrderSplitInfo> splits : orderSplits.values()) {
            for (Solution.OrderSplitInfo split : splits) {
              dayTotal += split.quantity;
              
              if (split.assignedRoute != null) {
                if (split.assignedRoute.isEmpty()) {
                  dayAssigned += split.quantity;
                  dayLocalDelivery += split.quantity;
                } else {
                  dayAssigned += split.quantity;
                }
              } else {
                dayUnassigned += split.quantity;
              }
            }
          }
          
          // CRITICAL: Persist assigned products to DB so next day sees filled capacities
          // This makes the simulation REALISTIC - flights fill up over time
          try {
            List<AlgorithmPersistenceService.OrderSplitWithInstances> persistenceSplits =
                convertToOrderSplitsWithInstances(orderSplits);
            
            if (!persistenceSplits.isEmpty()) {
              int persisted = persistenceService.persistSolutionWithInstances(persistenceSplits);
              System.out.println("  Persisted " + persisted + " products to DB (flights now have used capacity)");
            }
          } catch (Exception e) {
            System.out.println("  WARNING: Failed to persist day " + day + " results: " + e.getMessage());
            // Continue simulation even if persistence fails
          }
          
        } else if (newOrdersToday > 0) {
          System.out.println("  WARNING: No order splits returned from algorithm");
          continue;
        } else {
          System.out.println("  No orders to process today");
          continue;
        }
        
        // Update current state
        totalProductsInSystem = dayTotal;  // Total products algorithm sees
        currentAssignedProducts = dayAssigned;
        currentUnassignedProducts = dayUnassigned;
        
        double assignmentRate = dayTotal > 0 ? (dayAssigned * 100.0 / dayTotal) : 100.0;
        double unassignedRate = dayTotal > 0 ? (dayUnassigned * 100.0 / dayTotal) : 0.0;
        
        System.out.println("  Total products in system: " + dayTotal);
        System.out.println("  Assigned: " + dayAssigned + (dayLocalDelivery > 0 ? " (" + dayLocalDelivery + " local)" : ""));
        System.out.println("  Unassigned (backlog): " + dayUnassigned);
        System.out.println("  Assignment rate: " + String.format("%.1f", assignmentRate) + "%");
        
        // Track daily statistics
        dailyStats.add(CollapseResultSchema.DayStatistics.builder()
            .dayNumber(day)
            .dayStart(dayStart)
            .ordersProcessed(newOrdersToday)
            .productsAssigned(dayAssigned)
            .productsUnassigned(dayUnassigned)
            .assignmentRate(assignmentRate)
            .productsOnTime(dayAssigned)
            .productsLate(dayUnassigned)
            .slaComplianceRate(assignmentRate)
            .build());
        
        // CHECK FOR COLLAPSE: Two conditions
        
        // Condition 1: Unassigned percentage exceeds threshold
        if (unassignedRate > COLLAPSE_THRESHOLD_PERCENT) {
          hasCollapsed = true;
          collapseReason = "SLA_BREACH";
          collapseDay = day;
          collapseTime = dayStart;
          
          System.out.println("\n!!! COLLAPSE DETECTED ON DAY " + day + " !!!");
          System.out.println("  Unassigned rate: " + String.format("%.1f", unassignedRate) + "% > threshold " + COLLAPSE_THRESHOLD_PERCENT + "%");
          System.out.println("  " + dayUnassigned + " products cannot be delivered within SLA");
          
          productsLate = dayUnassigned;
          productsOnTime = dayAssigned;
          break;
        }
        
        // Condition 2: Backlog is growing continuously
        if (dayUnassigned > previousUnassigned && previousUnassigned > 0) {
          consecutiveGrowingBacklogDays++;
          System.out.println("  WARNING: Backlog growing (" + previousUnassigned + " -> " + dayUnassigned + ")");
          System.out.println("  Consecutive growing days: " + consecutiveGrowingBacklogDays + "/" + MAX_GROWING_BACKLOG_DAYS);
          
          if (consecutiveGrowingBacklogDays >= MAX_GROWING_BACKLOG_DAYS) {
            hasCollapsed = true;
            collapseReason = "CAPACITY_EXHAUSTED";
            collapseDay = day;
            collapseTime = dayStart;
            
            System.out.println("\n!!! COLLAPSE DETECTED ON DAY " + day + " !!!");
            System.out.println("  Backlog has been growing for " + MAX_GROWING_BACKLOG_DAYS + " consecutive days");
            System.out.println("  System cannot keep up with demand");
            
            productsLate = dayUnassigned;
            productsOnTime = dayAssigned;
            break;
          }
        } else if (dayUnassigned < previousUnassigned) {
          consecutiveGrowingBacklogDays = 0; // Reset if backlog is shrinking
        }
        
        previousUnassigned = dayUnassigned;
        productsOnTime = dayAssigned;
        productsLate = dayUnassigned;
        
        // Progress update every 10 days
        if (day % 10 == 0) {
          long elapsed = ChronoUnit.SECONDS.between(executionStartTime, LocalDateTime.now());
          System.out.println("\n  [Progress] Day " + day + "/" + MAX_DAYS + 
                           ", Time: " + elapsed + "s, Backlog: " + dayUnassigned);
        }
      }
      
      // If we reached MAX_DAYS without collapse
      if (!hasCollapsed) {
        collapseReason = "NO_COLLAPSE";
        System.out.println("\n=== NO COLLAPSE AFTER " + MAX_DAYS + " DAYS ===");
        System.out.println("System handled all orders within SLA");
      }
      
      LocalDateTime executionEndTime = LocalDateTime.now();
      long executionTime = ChronoUnit.SECONDS.between(executionStartTime, executionEndTime);
      
      double unassignedPercentage = totalProductsInSystem > 0 
          ? (currentUnassignedProducts * 100.0) / totalProductsInSystem 
          : 0.0;
      
      double slaCompliance = totalProductsInSystem > 0
          ? (currentAssignedProducts * 100.0) / totalProductsInSystem
          : 100.0;
      
      double slaViolation = totalProductsInSystem > 0
          ? (currentUnassignedProducts * 100.0) / totalProductsInSystem
          : 0.0;
      
      System.out.println("\n===========================================");
      System.out.println("COLLAPSE SCENARIO RESULTS");
      System.out.println("Has collapsed: " + hasCollapsed);
      if (hasCollapsed) {
        System.out.println("Collapse day: " + collapseDay);
        System.out.println("Collapse reason: " + collapseReason);
      }
      System.out.println("Days simulated: " + dailyStats.size());
      System.out.println("Total orders loaded: " + totalOrdersLoaded);
      System.out.println("Total products in system: " + totalProductsInSystem);
      System.out.println("Assigned (on time): " + currentAssignedProducts);
      System.out.println("Unassigned (SLA violated): " + currentUnassignedProducts);
      System.out.println("SLA compliance: " + String.format("%.1f", slaCompliance) + "%");
      System.out.println("Execution time: " + executionTime + " seconds");
      System.out.println("===========================================\n");
      
      String message;
      if (hasCollapsed) {
        message = "System collapsed on day " + collapseDay + ": " + currentUnassignedProducts + 
                  " products cannot be delivered within SLA (2 days continental / 3 days intercontinental)";
      } else {
        message = "Simulation completed: All " + currentAssignedProducts + 
                  " products can be delivered within SLA after " + dailyStats.size() + " days";
      }
      
      return CollapseResultSchema.builder()
          .success(true)
          .message(message)
          .hasCollapsed(hasCollapsed)
          .collapseDay(collapseDay)
          .collapseTime(collapseTime)
          .collapseReason(collapseReason)
          .executionStartTime(executionStartTime)
          .executionEndTime(executionEndTime)
          .executionTimeSeconds(executionTime)
          .simulationStartTime(simStart)
          .totalDaysSimulated(dailyStats.size())
          .totalOrdersProcessed(totalOrdersLoaded)
          .totalProductsProcessed(totalProductsInSystem)
          .assignedProducts(currentAssignedProducts)
          .unassignedProducts(currentUnassignedProducts)
          .unassignedPercentage(unassignedPercentage)
          // SLA metrics
          .productsOnTime(productsOnTime)
          .productsLate(productsLate)
          .slaCompliancePercentage(slaCompliance)
          .slaViolationPercentage(slaViolation)
          .slaThresholdUsed(COLLAPSE_THRESHOLD_PERCENT)
          // Continental breakdown (simplified)
          .continentalOrdersTotal(continentalTotal)
          .continentalOrdersOnTime(continentalOnTime)
          .continentalOrdersLate(0)
          .continentalSlaCompliance(100.0)
          .intercontinentalOrdersTotal(intercontinentalTotal)
          .intercontinentalOrdersOnTime(intercontinentalOnTime)
          .intercontinentalOrdersLate(0)
          .intercontinentalSlaCompliance(100.0)
          .slaViolations(null)
          .dailyStatistics(dailyStats)
          .build();
          
    } catch (Exception e) {
      LocalDateTime executionEndTime = LocalDateTime.now();
      System.out.println("\n!!! COLLAPSE SCENARIO FAILED !!!");
      System.out.println("Error: " + e.getMessage());
      e.printStackTrace();
      
      return CollapseResultSchema.builder()
          .success(false)
          .message("Collapse scenario failed: " + e.getMessage())
          .hasCollapsed(true)
          .collapseDay(1)
          .collapseTime(simStart)
          .collapseReason("ERROR")
          .executionStartTime(executionStartTime)
          .executionEndTime(executionEndTime)
          .executionTimeSeconds(ChronoUnit.SECONDS.between(executionStartTime, executionEndTime))
          .simulationStartTime(simStart)
          .totalDaysSimulated(1)
          .totalOrdersProcessed(0)
          .totalProductsProcessed(0)
          .assignedProducts(0)
          .unassignedProducts(0)
          .build();
    }
  }

  // ==================== COLLAPSE VISUAL SIMULATION (Day-by-Day) ====================

  /**
   * Initialize the visual collapse simulation
   * Must be called ONCE before the first call to executeCollapseVisualDay()
   * Clears database and resets all state
   * 
   * @param simulationStartTime The start time for simulation (default: 2025-01-02T00:00:00)
   * @return Success status
   */
  public CollapseVisualDayResultSchema initCollapseVisualSimulation(LocalDateTime simulationStartTime) {
    LocalDateTime executionStart = LocalDateTime.now();
    
    try {
      System.out.println("===========================================");
      System.out.println("INITIALIZING COLLAPSE VISUAL SIMULATION");
      System.out.println("Start time: " + simulationStartTime);
      System.out.println("===========================================");
      
      // Try to clear existing data, but continue if it fails
      try {
        dataLoadService.clearAllOrders();
        System.out.println("Database cleared successfully");
      } catch (Exception e) {
        System.out.println("WARNING: Could not clear orders (may have FK constraints): " + e.getMessage());
        System.out.println("Continuing with existing data...");
      }
      
      // Reset state
      collapseVisualInitialized = true;
      collapseVisualSimStart = simulationStartTime;
      collapseVisualTotalOrdersLoaded = 0;
      collapseVisualPreviousBacklog = 0;
      collapseVisualConsecutiveGrowingDays = 0;
      
      LocalDateTime executionEnd = LocalDateTime.now();
      
      return CollapseVisualDayResultSchema.builder()
          .success(true)
          .message("Collapse visual simulation initialized. Ready to process days.")
          .dayNumber(0)
          .dayStart(simulationStartTime)
          .dayEnd(simulationStartTime)
          .hasReachedCollapse(false)
          .continueSimulation(true)
          .totalDaysSimulated(0)
          .totalOrdersLoaded(0)
          .totalProductsInSystem(0)
          .cumulativeAssigned(0)
          .cumulativeBacklog(0)
          .cumulativeAssignmentRate(100.0)
          .consecutiveGrowingDays(0)
          .backlogIsGrowing(false)
          .executionStartTime(executionStart)
          .executionEndTime(executionEnd)
          .executionTimeMs(ChronoUnit.MILLIS.between(executionStart, executionEnd))
          .collapseProgress(0.0)
          .statusLabel("INITIALIZING")
          .build();
          
    } catch (Exception e) {
      System.out.println("!!! INIT FAILED: " + e.getMessage());
      e.printStackTrace();
      
      collapseVisualInitialized = false;
      
      return CollapseVisualDayResultSchema.builder()
          .success(false)
          .message("Failed to initialize: " + e.getMessage())
          .hasReachedCollapse(false)
          .continueSimulation(false)
          .statusLabel("ERROR")
          .build();
    }
  }

  /**
   * Execute ONE DAY of the visual collapse simulation
   * Call this repeatedly for each day until hasReachedCollapse=true or continueSimulation=false
   * 
   * This method:
   * 1. Loads orders for the specified day (adds to DB)
   * 2. Runs ALNS on all orders (new + backlog)
   * 3. Persists results
   * 4. Checks collapse conditions
   * 5. Returns day statistics for frontend visualization
   * 
   * @param dayNumber The day to process (1, 2, 3, ...)
   * @return Day results including collapse detection
   */
  public CollapseVisualDayResultSchema executeCollapseVisualDay(int dayNumber) {
    LocalDateTime executionStart = LocalDateTime.now();
    
    // Validate initialization
    if (!collapseVisualInitialized || collapseVisualSimStart == null) {
      return CollapseVisualDayResultSchema.builder()
          .success(false)
          .message("Simulation not initialized. Call initCollapseVisualSimulation first.")
          .hasReachedCollapse(false)
          .continueSimulation(false)
          .statusLabel("ERROR")
          .build();
    }
    
    // Check max days
    if (dayNumber > MAX_SIMULATION_DAYS) {
      return CollapseVisualDayResultSchema.builder()
          .success(true)
          .message("Maximum simulation days reached (" + MAX_SIMULATION_DAYS + ")")
          .dayNumber(dayNumber)
          .hasReachedCollapse(false)
          .collapseReason("MAX_DAYS_REACHED")
          .continueSimulation(false)
          .statusLabel("COMPLETED")
          .build();
    }
    
    try {
      LocalDateTime dayStart = collapseVisualSimStart.plusDays(dayNumber - 1);
      LocalDateTime dayEnd = dayStart.plusDays(1);
      
      System.out.println("\n--- COLLAPSE VISUAL: Day " + dayNumber + " (" + dayStart.toLocalDate() + ") ---");
      
      // STEP 1: Load orders for this day
      com.system.morapack.bll.service.DataLoadService.LoadOrdersResult loadResult =
          dataLoadService.loadOrdersFromFiles(
              com.system.morapack.config.Constants.ORDER_FILES_DIRECTORY,
              dayStart,
              dayEnd,
              false  // Check for duplicates
          );
      
      int newOrdersToday = loadResult.ordersCreated;
      collapseVisualTotalOrdersLoaded += newOrdersToday;
      
      System.out.println("  New orders loaded: " + newOrdersToday);
      System.out.println("  Total orders in system: " + collapseVisualTotalOrdersLoaded);
      
      // STEP 2: Execute ALNS on ALL orders (from simulation start to horizon)
      LocalDateTime algoStart = collapseVisualSimStart;
      LocalDateTime algoEnd = dayStart.plusDays(Constants.HORIZON_DAYS);
      
      // FORCE DATABASE MODE: This ensures we only load unassigned orders from the DB
      // instead of re-loading everything from files. This prevents deadlocks and re-assignment of completed orders.
      DataSourceOverride.setOverride(Constants.DataSourceMode.DATABASE);
      Solution alnsSolution;
      try {
          alnsSolution = new Solution(algoStart, algoEnd);
          alnsSolution.solve();
      } finally {
          DataSourceOverride.clearOverride();
      }
      
      // STEP 3: Count results from order splits
      Map<String, List<Solution.OrderSplitInfo>> orderSplits = alnsSolution.getOrderSplits();
      int dayAssigned = 0;
      int dayUnassigned = 0;
      int dayTotal = 0;
      
      // DEBUG: Log orderSplits info
      System.out.println("  [DEBUG] orderSplits null? " + (orderSplits == null));
      System.out.println("  [DEBUG] orderSplits size: " + (orderSplits != null ? orderSplits.size() : 0));
      
      // Collect unique flight instances used in solution
      Map<String, CollapseVisualDayResultSchema.FlightInstanceDTO> usedFlightInstances = new HashMap<>();
      
      int totalSplits = 0;
      int splitsWithInstances = 0;
      int totalInstancesProcessed = 0;
      
      if (orderSplits != null && !orderSplits.isEmpty()) {
        // DEBUG: Show first few orders in splits
        int debugCount = 0;
        for (Map.Entry<String, List<Solution.OrderSplitInfo>> entry : orderSplits.entrySet()) {
          if (debugCount < 3) {
            System.out.println("  [DEBUG] Order " + entry.getKey() + " has " + entry.getValue().size() + " splits");
            for (Solution.OrderSplitInfo split : entry.getValue()) {
              System.out.println("    qty=" + split.quantity + 
                  ", hasRoute=" + (split.assignedRoute != null && !split.assignedRoute.isEmpty()) +
                  ", instanceCount=" + (split.assignedFlightInstances != null ? split.assignedFlightInstances.size() : 0));
            }
            debugCount++;
          }
        }
        
        for (List<Solution.OrderSplitInfo> splits : orderSplits.values()) {
          for (Solution.OrderSplitInfo split : splits) {
            totalSplits++;
            dayTotal += split.quantity;
            
            if (split.assignedRoute != null) {
              dayAssigned += split.quantity;
              
              // Collect flight instances used in this split
              if (split.assignedFlightInstances != null && !split.assignedFlightInstances.isEmpty()) {
                splitsWithInstances++;
                for (FlightInstanceSchema instance : split.assignedFlightInstances) {
                  totalInstancesProcessed++;
                  if (instance != null && instance.getInstanceId() != null) {
                    String instId = instance.getInstanceId();
                    if (usedFlightInstances.containsKey(instId)) {
                      // Increment product count
                      var existing = usedFlightInstances.get(instId);
                      existing.setProductCount(existing.getProductCount() + split.quantity);
                    } else {
                      // Create new entry
                      var baseFlight = instance.getBaseFlight();
                      var originAirport = baseFlight != null ? baseFlight.getOriginAirportSchema() : null;
                      var destAirport = baseFlight != null ? baseFlight.getDestinationAirportSchema() : null;
                      
                      usedFlightInstances.put(instId, CollapseVisualDayResultSchema.FlightInstanceDTO.builder()
                          .instanceId(instId)
                          .flightId(instance.getBaseFlightId())
                          .flightCode(baseFlight != null ? baseFlight.getCode() : null)
                          .departureTime(instance.getDepartureDateTime())
                          .arrivalTime(instance.getArrivalDateTime())
                          .originCode(originAirport != null ? originAirport.getCodeIATA() : null)
                          .destinationCode(destAirport != null ? destAirport.getCodeIATA() : null)
                          .originLat(parseLatLng(originAirport != null ? originAirport.getLatitude() : null))
                          .originLng(parseLatLng(originAirport != null ? originAirport.getLongitude() : null))
                          .destLat(parseLatLng(destAirport != null ? destAirport.getLatitude() : null))
                          .destLng(parseLatLng(destAirport != null ? destAirport.getLongitude() : null))
                          .productCount(split.quantity)
                          .build());
                    }
                  }
                }
              }
            } else {
              dayUnassigned += split.quantity;
            }
          }
        }
        
        // Log flight instances collected
        System.out.println("  Order splits stats: total=" + totalSplits + 
            ", withInstances=" + splitsWithInstances + 
            ", instancesProcessed=" + totalInstancesProcessed);
        System.out.println("  Unique flight instances collected: " + usedFlightInstances.size());
        if (!usedFlightInstances.isEmpty()) {
          var firstInst = usedFlightInstances.values().iterator().next();
          System.out.println("  Sample instance: " + firstInst.getInstanceId() + 
              " from " + firstInst.getOriginCode() + " to " + firstInst.getDestinationCode() +
              " lat/lng: " + firstInst.getOriginLat() + "/" + firstInst.getOriginLng());
        }
        
        // STEP 4: Persist to DB so flights show used capacity
        try {
          List<AlgorithmPersistenceService.OrderSplitWithInstances> persistenceSplits =
              convertToOrderSplitsWithInstances(orderSplits);
          
          if (!persistenceSplits.isEmpty()) {
            int persisted = persistenceService.persistSolutionWithInstances(persistenceSplits);
            System.out.println("  Persisted " + persisted + " products to DB");
          }
        } catch (Exception e) {
          System.out.println("  WARNING: Persistence failed: " + e.getMessage());
        }
        
        // STEP 4.5: Update product states based on end of day time
        // This marks products as IN_TRANSIT or ARRIVED so they won't be re-processed
        // Uses retry logic to handle potential deadlocks
        boolean stateUpdateSuccess = false;
        for (int attempt = 1; attempt <= 3 && !stateUpdateSuccess; attempt++) {
          try {
            var stateUpdateStats = simulationTimeService.updateProductStates(dayEnd);
            System.out.println("  State updates: PENDING->IN_TRANSIT=" + stateUpdateStats.getPendingToInTransit() +
                             ", IN_TRANSIT->ARRIVED=" + stateUpdateStats.getInTransitToArrived() +
                             ", ARRIVED->DELIVERED=" + stateUpdateStats.getArrivedToDelivered());
            stateUpdateSuccess = true;
          } catch (Exception e) {
            if (attempt < 3) {
              System.out.println("  State update attempt " + attempt + " failed (deadlock?), retrying...");
              try { Thread.sleep(200L * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            } else {
              System.out.println("  WARNING: State update failed after 3 attempts: " + e.getMessage());
            }
          }
        }
      }
      
      // STEP 5: Calculate metrics
      double assignmentRate = dayTotal > 0 ? (dayAssigned * 100.0 / dayTotal) : 100.0;
      double unassignedRate = dayTotal > 0 ? (dayUnassigned * 100.0 / dayTotal) : 0.0;
      
      System.out.println("  Products total: " + dayTotal);
      System.out.println("  Assigned: " + dayAssigned);
      System.out.println("  Unassigned (backlog): " + dayUnassigned);
      System.out.println("  Assignment rate: " + String.format("%.1f", assignmentRate) + "%");
      
      // STEP 6: Check backlog trend
      boolean backlogGrowing = dayUnassigned > collapseVisualPreviousBacklog && collapseVisualPreviousBacklog > 0;
      if (backlogGrowing) {
        collapseVisualConsecutiveGrowingDays++;
        System.out.println("  WARNING: Backlog growing (" + collapseVisualPreviousBacklog + " -> " + dayUnassigned + ")");
      } else if (dayUnassigned < collapseVisualPreviousBacklog) {
        collapseVisualConsecutiveGrowingDays = 0; // Reset
      }
      
      int previousBacklog = collapseVisualPreviousBacklog;
      collapseVisualPreviousBacklog = dayUnassigned;
      
      // STEP 6.5: Check REAL SLA violations (products that exceed time limits)
      // Continental: orders with origin and destination in SAME continent must be delivered within 48h
      // Intercontinental: orders with origin and destination in DIFFERENT continents must be delivered within 72h
      LocalDateTime continentalDeadline = dayEnd.minusHours(48);  // Orders older than this violate continental SLA
      LocalDateTime intercontinentalDeadline = dayEnd.minusHours(72);  // Orders older than this violate intercontinental SLA
      
      int slaViolationsContinental = 0;
      int slaViolationsIntercontinental = 0;
      
      try {
        var violations = productRepository.countSLAViolationsByType(continentalDeadline, intercontinentalDeadline);
        for (Object[] row : violations) {
          String type = (String) row[0];
          long count = (Long) row[1];
          if ("CONTINENTAL".equals(type)) {
            slaViolationsContinental = (int) count;
          } else if ("INTERCONTINENTAL".equals(type)) {
            slaViolationsIntercontinental = (int) count;
          }
        }
        
        int totalSLAViolations = slaViolationsContinental + slaViolationsIntercontinental;
        if (totalSLAViolations > 0) {
          System.out.println("  ⚠️ SLA VIOLATIONS DETECTED:");
          if (slaViolationsContinental > 0) {
            System.out.println("    - Continental (>48h): " + slaViolationsContinental + " products");
          }
          if (slaViolationsIntercontinental > 0) {
            System.out.println("    - Intercontinental (>72h): " + slaViolationsIntercontinental + " products");
          }
        }
      } catch (Exception e) {
        System.out.println("  WARNING: Could not check SLA violations: " + e.getMessage());
      }
      
      // STEP 7: Check collapse conditions
      boolean hasCollapsed = false;
      String collapseReason = null;
      
      // Condition 0: ANY SLA violation = immediate collapse (strictest condition)
      if (slaViolationsContinental > 0 || slaViolationsIntercontinental > 0) {
        hasCollapsed = true;
        int totalViolations = slaViolationsContinental + slaViolationsIntercontinental;
        if (slaViolationsContinental > 0 && slaViolationsIntercontinental > 0) {
          collapseReason = String.format("SLA_VIOLATION: %d continental (>48h) + %d intercontinental (>72h) products exceeded delivery time", 
            slaViolationsContinental, slaViolationsIntercontinental);
        } else if (slaViolationsContinental > 0) {
          collapseReason = String.format("SLA_VIOLATION: %d continental products exceeded 48h delivery limit", slaViolationsContinental);
        } else {
          collapseReason = String.format("SLA_VIOLATION: %d intercontinental products exceeded 72h delivery limit", slaViolationsIntercontinental);
        }
        System.out.println("\n!!! COLLAPSE: " + collapseReason + " !!!");
      }
      
      // Condition 1: Unassigned percentage exceeds threshold (only if no SLA violation yet)
      if (!hasCollapsed && unassignedRate > COLLAPSE_THRESHOLD_PERCENT) {
        hasCollapsed = true;
        collapseReason = "BACKLOG_OVERFLOW: " + String.format("%.1f", unassignedRate) + "% of products unassigned (threshold: " + COLLAPSE_THRESHOLD_PERCENT + "%)";
        System.out.println("\n!!! COLLAPSE: " + collapseReason + " !!!");
      }
      
      // Condition 2: Backlog growing continuously
      if (!hasCollapsed && collapseVisualConsecutiveGrowingDays >= MAX_GROWING_BACKLOG_DAYS) {
        hasCollapsed = true;
        collapseReason = "CAPACITY_EXHAUSTED: Backlog grew for " + MAX_GROWING_BACKLOG_DAYS + " consecutive days";
        System.out.println("\n!!! COLLAPSE: " + collapseReason + " !!!");
      }
      
      // Calculate collapse progress (0-100)
      double collapseProgress = Math.min(100.0, Math.max(
          unassignedRate / COLLAPSE_THRESHOLD_PERCENT * 100.0,
          collapseVisualConsecutiveGrowingDays / (double) MAX_GROWING_BACKLOG_DAYS * 100.0
      ));
      
      // If collapsed (e.g. due to SLA violations), force progress to 100%
      if (hasCollapsed) {
        collapseProgress = 100.0;
      }
      
      // Status label
      String statusLabel;
      if (hasCollapsed) {
        statusLabel = "COLLAPSED";
      } else if (collapseProgress > 70) {
        statusLabel = "CRITICAL";
      } else if (collapseProgress > 40) {
        statusLabel = "WARNING";
      } else {
        statusLabel = "HEALTHY";
      }
      
      LocalDateTime executionEnd = LocalDateTime.now();
      
      // Convert flight instances map to list
      List<CollapseVisualDayResultSchema.FlightInstanceDTO> flightInstanceList = 
          new ArrayList<>(usedFlightInstances.values());
      System.out.println("  Flight instances used: " + flightInstanceList.size());
      
      return CollapseVisualDayResultSchema.builder()
          .success(true)
          .message(hasCollapsed ? "System collapsed on day " + dayNumber : "Day " + dayNumber + " completed")
          .dayNumber(dayNumber)
          .dayStart(dayStart)
          .dayEnd(dayEnd)
          .hasReachedCollapse(hasCollapsed)
          .collapseReason(collapseReason)
          .continueSimulation(!hasCollapsed && dayNumber < MAX_SIMULATION_DAYS)
          .ordersLoadedToday(newOrdersToday)
          .productsAssignedToday(dayAssigned)
          .productsUnassignedToday(dayUnassigned)
          .assignmentRateToday(assignmentRate)
          .totalDaysSimulated(dayNumber)
          .totalOrdersLoaded(collapseVisualTotalOrdersLoaded)
          .totalProductsInSystem(dayTotal)
          .cumulativeAssigned(dayAssigned)
          .cumulativeBacklog(dayUnassigned)
          .cumulativeAssignmentRate(assignmentRate)
          .productsOnTimeToday(dayAssigned)
          .productsLateToday(dayUnassigned)
          .slaComplianceToday(assignmentRate)
          .previousDayBacklog(previousBacklog)
          .consecutiveGrowingDays(collapseVisualConsecutiveGrowingDays)
          .backlogIsGrowing(backlogGrowing)
          .executionStartTime(executionStart)
          .executionEndTime(executionEnd)
          .executionTimeMs(ChronoUnit.MILLIS.between(executionStart, executionEnd))
          .collapseProgress(collapseProgress)
          .statusLabel(statusLabel)
          .assignedFlightInstances(flightInstanceList)
          .build();
          
    } catch (Exception e) {
      System.out.println("!!! Day " + dayNumber + " failed: " + e.getMessage());
      e.printStackTrace();
      
      return CollapseVisualDayResultSchema.builder()
          .success(false)
          .message("Day " + dayNumber + " failed: " + e.getMessage())
          .dayNumber(dayNumber)
          .hasReachedCollapse(true)
          .collapseReason("ERROR")
          .continueSimulation(false)
          .statusLabel("ERROR")
          .build();
    }
  }

  /**
   * Reset the visual collapse simulation state
   * Can be called to start over without restarting the server
   */
  public void resetCollapseVisualSimulation() {
    collapseVisualInitialized = false;
    collapseVisualSimStart = null;
    collapseVisualTotalOrdersLoaded = 0;
    collapseVisualPreviousBacklog = 0;
    collapseVisualConsecutiveGrowingDays = 0;
    System.out.println("Collapse visual simulation state reset");
  }
}

