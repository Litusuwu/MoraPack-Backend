package com.system.morapack.api;

import com.system.morapack.dao.morapack_psql.model.Flight;
import com.system.morapack.dao.morapack_psql.model.Order;
import com.system.morapack.dao.morapack_psql.model.Product;
import com.system.morapack.dao.morapack_psql.service.FlightService;
import com.system.morapack.dao.morapack_psql.service.OrderService;
import com.system.morapack.dao.morapack_psql.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST API endpoints for querying algorithm results
 * Frontend uses these to get current state from database
 * instead of receiving productRoutes in algorithm response
 */
@RestController
@RequestMapping("/api/query")
@RequiredArgsConstructor
public class OrderQueryAPI {

    private final OrderService orderService;
    private final ProductService productService;
    private final FlightService flightService;

    /**
     * Get orders within a simulation time window
     *
     * @param startTime Start of time window (ISO format: 2025-01-02T00:00:00)
     * @param endTime End of time window (ISO format: 2025-01-02T01:00:00)
     * @return List of orders created within the time window
     *
     * Example: GET /api/query/orders?startTime=2025-01-02T00:00:00&endTime=2025-01-02T01:00:00
     */
    @GetMapping("/orders")
    public ResponseEntity<Map<String, Object>> getOrdersByTimeWindow(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime startTime,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime endTime) {

        try {
            List<Order> allOrders = orderService.fetchOrders(null);

            // Filter by time window if provided
            List<Order> filteredOrders = allOrders;
            if (startTime != null || endTime != null) {
                filteredOrders = allOrders.stream()
                    .filter(order -> {
                        LocalDateTime orderDate = order.getCreationDate();
                        if (orderDate == null) return false;

                        boolean afterStart = startTime == null || !orderDate.isBefore(startTime);
                        boolean beforeEnd = endTime == null || !orderDate.isAfter(endTime);

                        return afterStart && beforeEnd;
                    })
                    .collect(Collectors.toList());
            }

            // Build response
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("totalOrders", filteredOrders.size());
            response.put("orders", filteredOrders);
            response.put("timeWindow", Map.of(
                "startTime", startTime != null ? startTime.toString() : "not specified",
                "endTime", endTime != null ? endTime.toString() : "not specified"
            ));

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to fetch orders: " + e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * Get product splits for a specific order
     *
     * @param orderId Order ID
     * @return List of products (splits) for the order
     *
     * Example: GET /api/query/products/12345
     */
    @GetMapping("/products/{orderId}")
    public ResponseEntity<Map<String, Object>> getProductsForOrder(@PathVariable Integer orderId) {

        try {
            List<Product> allProducts = productService.fetchProducts(null);

            // Filter products for this order
            List<Product> orderProducts = allProducts.stream()
                .filter(product -> product.getOrder() != null &&
                                  orderId.equals(product.getOrder().getId()))
                .collect(Collectors.toList());

            // Build response
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("orderId", orderId);
            response.put("productCount", orderProducts.size());
            response.put("products", orderProducts);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to fetch products: " + e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * Get all products with their assigned flights
     *
     * @return List of all products with status and flight assignments
     *
     * Example: GET /api/query/products
     */
    @GetMapping("/products")
    public ResponseEntity<Map<String, Object>> getAllProducts() {

        try {
            List<Product> allProducts = productService.fetchProducts(null);

            // NOTE: Product model doesn't currently have status field
            // TODO: Add status field to Product model for full functionality

            // Build response
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("totalProducts", allProducts.size());
            response.put("products", allProducts);
            response.put("note", "Product status tracking not yet implemented");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to fetch products: " + e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * Get flight assignment status
     * Returns statistics about product assignments to flights
     *
     * @return Summary of flight assignments
     *
     * Example: GET /api/query/flights/status
     */
    @GetMapping("/flights/status")
    public ResponseEntity<Map<String, Object>> getFlightStatus() {

        try {
            List<Order> allOrders = orderService.fetchOrders(null);
            List<Product> allProducts = productService.fetchProducts(null);
            List<Flight> allFlights = flightService.fetch(null);

            // Count orders assigned to flights
            long ordersAssigned = allOrders.stream()
                .filter(order -> order.getAssignedFlight() != null)
                .count();

            long ordersUnassigned = allOrders.size() - ordersAssigned;

            // Count products assigned via orders
            Set<Integer> assignedOrderIds = allOrders.stream()
                .filter(order -> order.getAssignedFlight() != null)
                .map(Order::getId)
                .collect(Collectors.toSet());

            long productsAssigned = allProducts.stream()
                .filter(product -> product.getOrder() != null &&
                                  assignedOrderIds.contains(product.getOrder().getId()))
                .count();

            long productsUnassigned = allProducts.size() - productsAssigned;

            // Count flights with orders
            Set<Integer> usedFlightIds = allOrders.stream()
                .filter(order -> order.getAssignedFlight() != null)
                .map(order -> order.getAssignedFlight().getId())
                .collect(Collectors.toSet());

            long flightsUsed = usedFlightIds.size();
            long flightsUnused = allFlights.size() - flightsUsed;

            // Build response
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("orders", Map.of(
                "total", allOrders.size(),
                "assigned", ordersAssigned,
                "unassigned", ordersUnassigned
            ));
            response.put("products", Map.of(
                "total", allProducts.size(),
                "assigned", productsAssigned,
                "unassigned", productsUnassigned
            ));
            response.put("flights", Map.of(
                "total", allFlights.size(),
                "used", flightsUsed,
                "unused", flightsUnused
            ));

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to fetch flight status: " + e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * Get order status by ID
     *
     * @param orderId Order ID
     * @return Order details with products
     *
     * Example: GET /api/query/orders/12345
     */
    @GetMapping("/orders/{orderId}")
    public ResponseEntity<Map<String, Object>> getOrderStatus(@PathVariable Integer orderId) {

        try {
            // Get order
            List<Order> allOrders = orderService.fetchOrders(null);
            Order order = allOrders.stream()
                .filter(o -> orderId.equals(o.getId()))
                .findFirst()
                .orElse(null);

            if (order == null) {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("success", false);
                errorResponse.put("message", "Order not found: " + orderId);
                return ResponseEntity.notFound().build();
            }

            // Get products for this order
            List<Product> allProducts = productService.fetchProducts(null);
            List<Product> orderProducts = allProducts.stream()
                .filter(product -> product.getOrder() != null &&
                                  orderId.equals(product.getOrder().getId()))
                .collect(Collectors.toList());

            // Build response
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("order", order);
            response.put("productCount", orderProducts.size());
            response.put("products", orderProducts);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to fetch order: " + e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * Get all flights
     *
     * @return List of all flights in the system
     *
     * Example: GET /api/query/flights
     */
    @GetMapping("/flights")
    public ResponseEntity<Map<String, Object>> getAllFlights() {

        try {
            List<Flight> allFlights = flightService.fetch(null);

            // Group by status
            Map<String, Long> statusCounts = allFlights.stream()
                .collect(Collectors.groupingBy(
                    flight -> flight.getStatus() != null ? flight.getStatus() : "UNKNOWN",
                    Collectors.counting()
                ));

            // Build response
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("totalFlights", allFlights.size());
            response.put("flights", allFlights);
            response.put("statusBreakdown", statusCounts);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to fetch flights: " + e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * Get products assigned to a specific flight
     *
     * @param flightCode Flight code (e.g., "LIMA-BRUS-001")
     * @return List of products assigned to this flight
     *
     * Example: GET /api/query/flights/LIMA-BRUS-001/products
     */
    @GetMapping("/flights/{flightCode}/products")
    public ResponseEntity<Map<String, Object>> getProductsForFlight(@PathVariable String flightCode) {

        try {
            // Find flight by code
            List<Flight> allFlights = flightService.fetch(null);
            Flight targetFlight = allFlights.stream()
                .filter(f -> flightCode.equals(f.getCode()))
                .findFirst()
                .orElse(null);

            if (targetFlight == null) {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("success", false);
                errorResponse.put("message", "Flight not found: " + flightCode);
                return ResponseEntity.notFound().build();
            }

            // Find all orders assigned to this flight
            List<Order> allOrders = orderService.fetchOrders(null);
            Set<Integer> flightOrderIds = allOrders.stream()
                .filter(order -> order.getAssignedFlight() != null &&
                               targetFlight.getId().equals(order.getAssignedFlight().getId()))
                .map(Order::getId)
                .collect(Collectors.toSet());

            // Find all products belonging to these orders
            List<Product> allProducts = productService.fetchProducts(null);
            List<Product> flightProducts = allProducts.stream()
                .filter(product -> product.getOrder() != null &&
                                  flightOrderIds.contains(product.getOrder().getId()))
                .collect(Collectors.toList());

            // Build response
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("flightCode", flightCode);
            response.put("flightId", targetFlight.getId());
            response.put("productCount", flightProducts.size());
            response.put("products", flightProducts);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to fetch products for flight: " + e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * Get orders assigned to a specific flight
     * Returns unique orders that have products on this flight
     *
     * @param flightCode Flight code (e.g., "LIMA-BRUS-001")
     * @return List of orders with products on this flight
     *
     * Example: GET /api/query/flights/LIMA-BRUS-001/orders
     */
    @GetMapping("/flights/{flightCode}/orders")
    public ResponseEntity<Map<String, Object>> getOrdersForFlight(@PathVariable String flightCode) {

        try {
            // Find flight by code
            List<Flight> allFlights = flightService.fetch(null);
            Flight targetFlight = allFlights.stream()
                .filter(f -> flightCode.equals(f.getCode()))
                .findFirst()
                .orElse(null);

            if (targetFlight == null) {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("success", false);
                errorResponse.put("message", "Flight not found: " + flightCode);
                return ResponseEntity.notFound().build();
            }

            // Find all orders assigned to this flight
            List<Order> allOrders = orderService.fetchOrders(null);
            List<Order> flightOrders = allOrders.stream()
                .filter(order -> order.getAssignedFlight() != null &&
                               targetFlight.getId().equals(order.getAssignedFlight().getId()))
                .collect(Collectors.toList());

            // Build response
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("flightCode", flightCode);
            response.put("flightId", targetFlight.getId());
            response.put("orderCount", flightOrders.size());
            response.put("orders", flightOrders);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to fetch orders for flight: " + e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * Get flight details by code
     *
     * @param flightCode Flight code (e.g., "LIMA-BRUS-001")
     * @return Flight details with product/order counts
     *
     * Example: GET /api/query/flights/LIMA-BRUS-001
     */
    @GetMapping("/flights/{flightCode}")
    public ResponseEntity<Map<String, Object>> getFlightDetails(@PathVariable String flightCode) {

        try {
            // Find flight by code
            List<Flight> allFlights = flightService.fetch(null);
            Flight flight = allFlights.stream()
                .filter(f -> flightCode.equals(f.getCode()))
                .findFirst()
                .orElse(null);

            if (flight == null) {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("success", false);
                errorResponse.put("message", "Flight not found: " + flightCode);
                return ResponseEntity.notFound().build();
            }

            // Find all orders assigned to this flight
            List<Order> allOrders = orderService.fetchOrders(null);
            Set<Integer> flightOrderIds = allOrders.stream()
                .filter(order -> order.getAssignedFlight() != null &&
                               flight.getId().equals(order.getAssignedFlight().getId()))
                .map(Order::getId)
                .collect(Collectors.toSet());

            // Find all products belonging to these orders
            List<Product> allProducts = productService.fetchProducts(null);
            long productCount = allProducts.stream()
                .filter(product -> product.getOrder() != null &&
                                  flightOrderIds.contains(product.getOrder().getId()))
                .count();

            // Build response
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("flight", flight);
            response.put("orderCount", flightOrderIds.size());
            response.put("productCount", productCount);
            response.put("capacityTotal", flight.getMaxCapacity());
            response.put("capacityUsed", productCount);
            response.put("capacityRemaining", flight.getMaxCapacity() - productCount);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to fetch flight details: " + e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }
}
