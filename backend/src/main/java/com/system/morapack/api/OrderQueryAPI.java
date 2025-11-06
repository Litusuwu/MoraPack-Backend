package com.system.morapack.api;

import com.system.morapack.dao.morapack_psql.model.Order;
import com.system.morapack.dao.morapack_psql.model.Product;
import com.system.morapack.dao.morapack_psql.service.OrderService;
import com.system.morapack.dao.morapack_psql.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
}
