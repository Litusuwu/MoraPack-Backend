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

            // Group by status
            Map<String, Long> statusCounts = allProducts.stream()
                .collect(Collectors.groupingBy(
                    product -> product.getStatus() != null ? product.getStatus().toString() : "UNKNOWN",
                    Collectors.counting()
                ));

            // Build response
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("totalProducts", allProducts.size());
            response.put("products", allProducts);
            response.put("statusBreakdown", statusCounts);

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
            List<Product> allProducts = productService.fetchProducts(null);

            // Count products by assignment status
            long assignedCount = allProducts.stream()
                .filter(product -> product.getAssignedFlight() != null &&
                                  !product.getAssignedFlight().toString().isEmpty())
                .count();

            long unassignedCount = allProducts.size() - assignedCount;

            // Build response
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("totalProducts", allProducts.size());
            response.put("assignedProducts", assignedCount);
            response.put("unassignedProducts", unassignedCount);
            response.put("assignmentRate", allProducts.size() > 0 ?
                (double) assignedCount / allProducts.size() * 100 : 0.0);

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
            List<Product> allProducts = productService.fetchProducts(null);

            // Filter products that have this flight in their assigned route
            // assignedFlight format: "LDZA-EBCI -> EBCI-SBBR"
            List<Product> flightProducts = allProducts.stream()
                .filter(product -> product.getAssignedFlight() != null &&
                                  product.getAssignedFlight().toString().contains(flightCode))
                .collect(Collectors.toList());

            // Group by status
            Map<String, Long> statusCounts = flightProducts.stream()
                .collect(Collectors.groupingBy(
                    product -> product.getStatus() != null ? product.getStatus().toString() : "UNKNOWN",
                    Collectors.counting()
                ));

            // Build response
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("flightCode", flightCode);
            response.put("productCount", flightProducts.size());
            response.put("products", flightProducts);
            response.put("statusBreakdown", statusCounts);

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
            List<Product> allProducts = productService.fetchProducts(null);

            // Get unique order IDs for products on this flight
            Set<Integer> orderIds = allProducts.stream()
                .filter(product -> product.getAssignedFlight() != null &&
                                  product.getAssignedFlight().toString().contains(flightCode))
                .filter(product -> product.getOrder() != null && product.getOrder().getId() != null)
                .map(product -> product.getOrder().getId())
                .collect(Collectors.toSet());

            // Fetch the actual orders
            List<Order> allOrders = orderService.fetchOrders(null);
            List<Order> flightOrders = allOrders.stream()
                .filter(order -> orderIds.contains(order.getId()))
                .collect(Collectors.toList());

            // Count products per order on this flight
            Map<Integer, Long> productsPerOrder = allProducts.stream()
                .filter(product -> product.getAssignedFlight() != null &&
                                  product.getAssignedFlight().toString().contains(flightCode))
                .filter(product -> product.getOrder() != null && product.getOrder().getId() != null)
                .collect(Collectors.groupingBy(
                    product -> product.getOrder().getId(),
                    Collectors.counting()
                ));

            // Build response with order details
            List<Map<String, Object>> orderDetails = new ArrayList<>();
            for (Order order : flightOrders) {
                Map<String, Object> orderInfo = new HashMap<>();
                orderInfo.put("orderId", order.getId());
                orderInfo.put("status", order.getStatus());
                orderInfo.put("creationDate", order.getCreationDate());
                orderInfo.put("productsOnFlight", productsPerOrder.getOrDefault(order.getId(), 0L));
                orderDetails.add(orderInfo);
            }

            // Build response
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("flightCode", flightCode);
            response.put("orderCount", flightOrders.size());
            response.put("orders", orderDetails);
            response.put("totalProductsOnFlight", productsPerOrder.values().stream()
                .mapToLong(Long::longValue).sum());

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

            // Count products on this flight
            List<Product> allProducts = productService.fetchProducts(null);
            long productCount = allProducts.stream()
                .filter(product -> product.getAssignedFlight() != null &&
                                  product.getAssignedFlight().toString().contains(flightCode))
                .count();

            // Count unique orders
            long orderCount = allProducts.stream()
                .filter(product -> product.getAssignedFlight() != null &&
                                  product.getAssignedFlight().toString().contains(flightCode))
                .filter(product -> product.getOrder() != null && product.getOrder().getId() != null)
                .map(product -> product.getOrder().getId())
                .distinct()
                .count();

            // Build response
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("flight", flight);
            response.put("productCount", productCount);
            response.put("orderCount", orderCount);
            response.put("capacityUsed", productCount);
            response.put("capacityTotal", flight.getMaxCapacity());
            response.put("capacityAvailable", Math.max(0, flight.getMaxCapacity() - productCount));

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to fetch flight details: " + e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }
}
