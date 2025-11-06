package com.system.morapack.bll.controller;

import com.system.morapack.config.Constants;
import com.system.morapack.schemas.*;
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
   * Execute algorithm based on request parameters
   */
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
   * Convert ALNS product-level solution to AlgorithmResultSchema
   */
  private AlgorithmResultSchema convertALNSSolutionToResult(
      Map<ProductSchema, ArrayList<FlightSchema>> productSolution,
      LocalDateTime startTime,
      LocalDateTime endTime,
      long executionTime) {

    System.out.println("\n=== CONVIRTIENDO SOLUCIÓN ALNS A RESULTADO ===");
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
          .algorithmType("ALNS")
          .executionStartTime(startTime)
          .executionEndTime(endTime)
          .executionTimeSeconds(executionTime)
          .totalOrders(0)
          .assignedOrders(0)
          .unassignedOrders(0)
          .totalProducts(0)
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
}
