package com.system.morapack.bll.controller;

import com.system.morapack.bll.dto.FlightStatusDTO;
import com.system.morapack.bll.dto.OrderOnFlightDTO;
import com.system.morapack.bll.dto.ProductWithOrderDTO;
import com.system.morapack.dao.morapack_psql.model.Flight;
import com.system.morapack.dao.morapack_psql.model.Order;
import com.system.morapack.dao.morapack_psql.model.Product;
import com.system.morapack.dao.morapack_psql.model.ProductFlight;
import com.system.morapack.dao.morapack_psql.service.FlightService;
import com.system.morapack.dao.morapack_psql.service.OrderService;
import com.system.morapack.dao.morapack_psql.service.ProductService;
import com.system.morapack.dao.morapack_psql.service.ProductFlightService;
import com.system.morapack.schemas.PackageStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Controller for flight query operations
 * Handles business logic for querying flight status and assignments
 */
@Service
@RequiredArgsConstructor
public class FlightQueryController {

    private final FlightService flightService;
    private final ProductService productService;
    private final OrderService orderService;
    private final ProductFlightService productFlightService;

    /**
     * Get all flights with their current status and utilization
     * Used for map display
     */
    public Map<String, Object> getAllFlightStatuses() {
        List<Flight> allFlights = flightService.fetch(null);
        List<Product> allProducts = productService.fetchProducts(null);

        // Group products by flight code (extract from assigned_flight_instance)
        Map<String, List<Product>> productsByFlight = allProducts.stream()
            .filter(p -> p.getAssignedFlightInstance() != null && !p.getAssignedFlightInstance().isEmpty())
            .collect(Collectors.groupingBy(this::extractFlightCodeFromInstance));

        // Build FlightStatusDTO for each flight
        List<FlightStatusDTO> flightStatuses = allFlights.stream()
            .map(flight -> buildFlightStatusDTO(flight, productsByFlight.get(flight.getCode())))
            .collect(Collectors.toList());

        // Calculate statistics
        int totalCapacity = flightStatuses.stream()
            .mapToInt(FlightStatusDTO::getMaxCapacity)
            .sum();

        int totalUsedCapacity = flightStatuses.stream()
            .mapToInt(FlightStatusDTO::getUsedCapacity)
            .sum();

        double averageUtilization = totalCapacity > 0
            ? (double) totalUsedCapacity / totalCapacity * 100
            : 0.0;

        // Group by continent pairs
        Map<String, Long> flightsByContinent = flightStatuses.stream()
            .collect(Collectors.groupingBy(
                f -> getContinentPair(f),
                Collectors.counting()
            ));

        Map<String, Object> statistics = new HashMap<>();
        statistics.put("totalFlights", flightStatuses.size());
        statistics.put("activeFlights", flightStatuses.size());
        statistics.put("totalCapacity", totalCapacity);
        statistics.put("totalUsedCapacity", totalUsedCapacity);
        statistics.put("totalAvailableCapacity", totalCapacity - totalUsedCapacity);
        statistics.put("averageUtilization", Math.round(averageUtilization * 100.0) / 100.0);
        statistics.put("flightsByContinent", flightsByContinent);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("totalFlights", flightStatuses.size());
        response.put("flights", flightStatuses);
        response.put("statistics", statistics);

        return response;
    }

    /**
     * Get all flight instances that have products assigned
     * Returns the actual instance IDs like "FL-123-DAY-0-0800" with product counts
     * This is used for accurate visualization - only show loaded planes for correct instances
     *
     * This method uses the assignedFlightInstance field from products to determine
     * the correct DAY for each product, then generates instanceIds for ALL flights
     * in the product's route (from product_flights table) for that same day.
     */
    public Map<String, Object> getAssignedFlightInstances() {
        // Step 1: Get all products with their assigned flight instances
        List<Product> allProducts = productService.fetchProducts(null);

        // Step 2: For each product, extract the DAY from its assignedFlightInstance
        // and generate instanceIds for ALL flights in its route
        Map<String, Long> instanceCounts = new HashMap<>();

        for (Product product : allProducts) {
            String assignedInstance = product.getAssignedFlightInstance();
            if (assignedInstance == null || assignedInstance.isEmpty()) {
                continue;
            }

            // Parse the day from assignedFlightInstance: "FL-{flightId}-DAY-{day}-{HHMM}"
            Integer dayNumber = extractDayFromInstanceId(assignedInstance);
            if (dayNumber == null) {
                // Fallback: just count the assignedFlightInstance as-is
                instanceCounts.merge(assignedInstance, 1L, Long::sum);
                continue;
            }

            // Get all flights in this product's route from product_flights table
            List<ProductFlight> productFlights = productFlightService.getFlightsForProduct(product.getId());

            if (productFlights.isEmpty()) {
                // No multi-hop data, just use the assignedFlightInstance
                instanceCounts.merge(assignedInstance, 1L, Long::sum);
            } else {
                // Generate instanceId for each flight in the route, using the same day
                for (ProductFlight pf : productFlights) {
                    Flight flight = pf.getFlight();
                    if (flight != null && flight.getDepartureTime() != null) {
                        String hhmm = formatTimeAsHHMM(flight.getDepartureTime().toString());
                        String instanceId = String.format("FL-%d-DAY-%d-%s",
                            flight.getId(), dayNumber, hhmm);
                        instanceCounts.merge(instanceId, 1L, Long::sum);
                    }
                }
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("totalInstances", instanceCounts.size());
        response.put("instances", instanceCounts);

        return response;
    }

    /**
     * Extract the day number from an instanceId like "FL-123-DAY-2-0800"
     * Returns null if parsing fails
     */
    private Integer extractDayFromInstanceId(String instanceId) {
        if (instanceId == null || !instanceId.startsWith("FL-")) {
            return null;
        }
        try {
            // Format: FL-{flightId}-DAY-{day}-{HHMM}
            String[] parts = instanceId.split("-");
            if (parts.length >= 4 && "DAY".equals(parts[2])) {
                return Integer.parseInt(parts[3]);
            }
        } catch (NumberFormatException e) {
            // Ignore parsing errors
        }
        return null;
    }

    /**
     * Format a time string as HHMM (e.g., "08:30:00" -> "0830")
     */
    private String formatTimeAsHHMM(String timeStr) {
        if (timeStr == null || timeStr.isEmpty()) {
            return "0000";
        }
        if (timeStr.contains(":")) {
            String[] parts = timeStr.split(":");
            return String.format("%02d%02d",
                Integer.parseInt(parts[0]),
                Integer.parseInt(parts[1]));
        }
        return "0000";
    }

    /**
     * Get orders assigned to a specific flight
     * Used when user clicks on a flight in the map
     * Updated to use product_flights table to find ALL orders using this flight
     */
    public Map<String, Object> getOrdersForFlight(String flightCode) {
        // Find flight by code
        Flight flight = flightService.getFlightByCode(flightCode);
        if (flight == null) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "Flight not found: " + flightCode);
            return error;
        }

        // Get all products using this flight from product_flights table
        List<ProductFlight> productFlights = productFlightService.getProductsUsingFlight(flight.getId());
        List<Product> products = productFlights.stream()
            .map(ProductFlight::getProduct)
            .distinct()
            .collect(Collectors.toList());

        // Group products by order
        Map<Integer, List<Product>> productsByOrder = products.stream()
            .collect(Collectors.groupingBy(p -> p.getOrder().getId()));

        // Build OrderOnFlightDTO for each order
        List<OrderOnFlightDTO> orders = productsByOrder.entrySet().stream()
            .map(entry -> {
                Integer orderId = entry.getKey();
                List<Product> orderProducts = entry.getValue();
                Order order = orderService.getOrder(orderId);

                return buildOrderOnFlightDTO(order, orderProducts.size(), orderProducts.get(0));
            })
            .collect(Collectors.toList());

        // Calculate statistics
        Map<PackageStatus, Long> byStatus = products.stream()
            .collect(Collectors.groupingBy(Product::getStatus, Collectors.counting()));

        Map<String, Long> byDestinationContinent = products.stream()
            .collect(Collectors.groupingBy(
                p -> p.getOrder().getDestination().getContinent().toString(),
                Collectors.counting()
            ));

        Map<String, Object> statistics = new HashMap<>();
        statistics.put("totalOrders", orders.size());
        statistics.put("totalProducts", products.size());
        statistics.put("byStatus", byStatus);
        statistics.put("byDestinationContinent", byDestinationContinent);

        Map<String, Object> flightInfo = new HashMap<>();
        flightInfo.put("id", flight.getId());
        flightInfo.put("code", flight.getCode());
        flightInfo.put("origin", flight.getOriginAirport().getCity().getName());
        flightInfo.put("destination", flight.getDestinationAirport().getCity().getName());
        flightInfo.put("usedCapacity", products.size());
        flightInfo.put("maxCapacity", flight.getMaxCapacity());
        flightInfo.put("utilizationPercentage",
            flight.getMaxCapacity() > 0 ? (double) products.size() / flight.getMaxCapacity() * 100 : 0.0);
        flightInfo.put("transportTimeDays", flight.getTransportTimeDays());
        flightInfo.put("dailyFrequency", flight.getDailyFrequency());

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("flightCode", flightCode);
        response.put("totalOrders", orders.size());
        response.put("orders", orders);
        response.put("flight", flightInfo);
        response.put("statistics", statistics);

        return response;
    }

    /**
     * Get products assigned to a specific flight
     * Updated to use product_flights table to find ALL products using this flight
     * (not just products where this is the final/assigned flight)
     */
    public Map<String, Object> getProductsForFlight(String flightCode) {
        // Find flight by code
        Flight flight = flightService.getFlightByCode(flightCode);
        if (flight == null) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "Flight not found: " + flightCode);
            return error;
        }

        // Get all products using this flight from product_flights table
        List<ProductFlight> productFlights = productFlightService.getProductsUsingFlight(flight.getId());
        List<Product> products = productFlights.stream()
            .map(ProductFlight::getProduct)
            .distinct()
            .collect(Collectors.toList());

        // Build ProductWithOrderDTO for each product
        List<ProductWithOrderDTO> productDTOs = products.stream()
            .map(this::buildProductWithOrderDTO)
            .collect(Collectors.toList());

        // Group by order
        Map<Integer, Map<String, Object>> groupedByOrder = products.stream()
            .collect(Collectors.groupingBy(
                p -> p.getOrder().getId(),
                Collectors.collectingAndThen(
                    Collectors.toList(),
                    list -> {
                        Map<String, Object> orderSummary = new HashMap<>();
                        Product first = list.get(0);
                        orderSummary.put("orderName", first.getOrder().getName());
                        orderSummary.put("productCount", list.size());
                        orderSummary.put("destination", first.getOrder().getDestination().getName());
                        return orderSummary;
                    }
                )
            ));

        // Calculate status breakdown
        Map<String, Long> statusBreakdown = products.stream()
            .collect(Collectors.groupingBy(
                p -> p.getStatus() != null ? p.getStatus().toString() : "UNKNOWN",
                Collectors.counting()
            ));

        Map<String, Object> flightInfo = new HashMap<>();
        flightInfo.put("id", flight.getId());
        flightInfo.put("code", flight.getCode());
        flightInfo.put("origin", flight.getOriginAirport().getCity().getName());
        flightInfo.put("destination", flight.getDestinationAirport().getCity().getName());
        flightInfo.put("usedCapacity", products.size());
        flightInfo.put("maxCapacity", flight.getMaxCapacity());

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("flightCode", flightCode);
        response.put("totalProducts", products.size());
        response.put("productCount", products.size());  // For frontend compatibility
        response.put("products", productDTOs);
        response.put("flight", flightInfo);
        response.put("groupedByOrder", groupedByOrder);
        response.put("statusBreakdown", statusBreakdown);  // For frontend totals display

        return response;
    }

    // Helper methods

    private FlightStatusDTO buildFlightStatusDTO(Flight flight, List<Product> products) {
        int usedCapacity = products != null ? products.size() : 0;
        int maxCapacity = flight.getMaxCapacity() != null ? flight.getMaxCapacity() : 300;
        int availableCapacity = maxCapacity - usedCapacity;
        double utilization = maxCapacity > 0 ? (double) usedCapacity / maxCapacity * 100 : 0.0;

        // Count unique orders
        int assignedOrders = products != null ?
            (int) products.stream()
                .map(p -> p.getOrder().getId())
                .distinct()
                .count() : 0;

        return FlightStatusDTO.builder()
            .id(flight.getId())
            .code(flight.getCode())
            .originAirport(FlightStatusDTO.AirportDTO.builder()
                .codeIATA(flight.getOriginAirport().getCodeIATA())
                .city(FlightStatusDTO.CityDTO.builder()
                    .id(flight.getOriginAirport().getCity().getId())
                    .name(flight.getOriginAirport().getCity().getName())
                    .continent(flight.getOriginAirport().getCity().getContinent().toString())
                    .latitude(flight.getOriginAirport().getLatitude())
                    .longitude(flight.getOriginAirport().getLongitude())
                    .build())
                .build())
            .destinationAirport(FlightStatusDTO.AirportDTO.builder()
                .codeIATA(flight.getDestinationAirport().getCodeIATA())
                .city(FlightStatusDTO.CityDTO.builder()
                    .id(flight.getDestinationAirport().getCity().getId())
                    .name(flight.getDestinationAirport().getCity().getName())
                    .continent(flight.getDestinationAirport().getCity().getContinent().toString())
                    .latitude(flight.getDestinationAirport().getLatitude())
                    .longitude(flight.getDestinationAirport().getLongitude())
                    .build())
                .build())
            .maxCapacity(maxCapacity)
            .usedCapacity(usedCapacity)
            .availableCapacity(availableCapacity)
            .transportTimeDays(flight.getTransportTimeDays())
            .dailyFrequency(flight.getDailyFrequency())
            .utilizationPercentage(Math.round(utilization * 100.0) / 100.0)
            .assignedProducts(usedCapacity)
            .assignedOrders(assignedOrders)
            .isActive(true)
            .departureTime(flight.getDepartureTime())
            .arrivalTime(flight.getArrivalTime())
            .build();
    }

    private OrderOnFlightDTO buildOrderOnFlightDTO(Order order, int productsOnFlight, Product sampleProduct) {
        int totalProducts = productService.fetchProducts(null).stream()
            .filter(p -> p.getOrder().getId().equals(order.getId()))
            .collect(Collectors.toList())
            .size();

        return OrderOnFlightDTO.builder()
            .id(order.getId())
            .name(order.getName())
            .status(order.getStatus())
            .productsOnFlight(productsOnFlight)
            .totalProducts(totalProducts)
            .origin(OrderOnFlightDTO.CityInfo.builder()
                .id(order.getOrigin().getId())
                .name(order.getOrigin().getName())
                .build())
            .destination(OrderOnFlightDTO.CityInfo.builder()
                .id(order.getDestination().getId())
                .name(order.getDestination().getName())
                .build())
            .customer(OrderOnFlightDTO.CustomerInfo.builder()
                .id(order.getCustomer().getId())
                .phone(order.getCustomer().getPhone())
                .build())
            .flightInstance(sampleProduct.getAssignedFlightInstance())
            .build();
    }

    private ProductWithOrderDTO buildProductWithOrderDTO(Product product) {
        return ProductWithOrderDTO.builder()
            .id(product.getId())
            .status(product.getStatus())
            .assignedFlightInstance(product.getAssignedFlightInstance())
            .createdAt(product.getCreationDate())
            .order(ProductWithOrderDTO.OrderInfo.builder()
                .id(product.getOrder().getId())
                .name(product.getOrder().getName())
                    .origin(product.getOrder().getOrigin().getName())          // 👈 NUEVO
                .destination(product.getOrder().getDestination().getName())
                .customer(product.getOrder().getCustomer().getPhone())
                .build())
            .build();
    }

    private String extractFlightCodeFromInstance(Product product) {
        // Extract flight code from instance like "FL-6545-DAY-0-2000"
        String instance = product.getAssignedFlightInstance();
        if (instance == null || instance.isEmpty()) {
            System.out.println("WARNING: Product " + product.getId() + " has no assigned flight instance");
            return "";
        }

        // Format: "FL-6545-DAY-0-2000"
        // We need to extract the base flight ID (6545) and find its flight code
        if (instance.startsWith("FL-")) {
            // Parse: FL-{flightId}-DAY-{day}-{time}
            String[] parts = instance.split("-");
            if (parts.length >= 2) {
                try {
                    Integer flightId = Integer.parseInt(parts[1]);
                    // Find the flight by ID and get its code
                    Flight flight = flightService.get(flightId);
                    if (flight != null) {
                        return flight.getCode();
                    } else {
                        System.err.println("  ERROR: Flight with ID " + flightId + " not found for instance: " + instance);
                    }
                } catch (NumberFormatException e) {
                    System.err.println("  ERROR: Failed to parse flight ID from instance: " + instance);
                }
            }
        }

        return "";
    }

    private String getContinentPair(FlightStatusDTO flight) {
        String originContinent = flight.getOriginAirport().getCity().getContinent();
        String destContinent = flight.getDestinationAirport().getCity().getContinent();

        if (originContinent.equals(destContinent)) {
            return originContinent + "-" + originContinent;
        } else {
            return "Intercontinental";
        }
    }
}
