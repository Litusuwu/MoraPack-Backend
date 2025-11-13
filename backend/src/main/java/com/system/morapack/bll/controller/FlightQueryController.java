package com.system.morapack.bll.controller;

import com.system.morapack.bll.dto.FlightStatusDTO;
import com.system.morapack.bll.dto.OrderOnFlightDTO;
import com.system.morapack.bll.dto.ProductWithOrderDTO;
import com.system.morapack.dao.morapack_psql.model.Flight;
import com.system.morapack.dao.morapack_psql.model.Order;
import com.system.morapack.dao.morapack_psql.model.Product;
import com.system.morapack.dao.morapack_psql.service.FlightService;
import com.system.morapack.dao.morapack_psql.service.OrderService;
import com.system.morapack.dao.morapack_psql.service.ProductService;
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
     * Get orders assigned to a specific flight
     * Used when user clicks on a flight in the map
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

        // Get all products assigned to this flight
        List<Product> products = productService.fetchProducts(null).stream()
            .filter(p -> p.getAssignedFlightInstance() != null &&
                         extractFlightCodeFromInstance(p).equals(flightCode))
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

        // Get all products assigned to this flight
        List<Product> products = productService.fetchProducts(null).stream()
            .filter(p -> p.getAssignedFlightInstance() != null &&
                         extractFlightCodeFromInstance(p).equals(flightCode))
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
        response.put("products", productDTOs);
        response.put("flight", flightInfo);
        response.put("groupedByOrder", groupedByOrder);

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
                .destination(product.getOrder().getDestination().getName())
                .customer(product.getOrder().getCustomer().getPhone())
                .build())
            .build();
    }

    private String extractFlightCodeFromInstance(Product product) {
        // Extract flight code from instance like "FL-6545-DAY-0-2000" or "SPIM-SPZO-DAY-0-2000"
        String instance = product.getAssignedFlightInstance();
        if (instance == null || instance.isEmpty()) {
            return "";
        }

        // Check if it starts with "FL-" (old format with ID)
        if (instance.startsWith("FL-")) {
            // Format: "FL-6545-DAY-0-2000"
            // We need to find the actual flight code from the database
            // For now, return empty - this should be improved
            return "";
        }

        // Format: "SPIM-SPZO-DAY-0-2000"
        // Extract "SPIM-SPZO"
        String[] parts = instance.split("-");
        if (parts.length >= 2) {
            return parts[0] + "-" + parts[1];
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
