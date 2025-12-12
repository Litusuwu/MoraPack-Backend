package com.system.morapack.api;

import com.system.morapack.bll.controller.OrderQueryController;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * REST API for order query operations
 * Provides endpoints for querying orders and products
 */
@RestController
@RequestMapping("/api/query")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class OrderQueryAPI {

    private final OrderQueryController controller;

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
            @RequestParam(required = true)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime startTime,
            @RequestParam(required = true)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime endTime) {

        try {
            Map<String, Object> response = controller.getOrdersInTimeWindow(startTime, endTime);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of(
                    "success", false,
                    "message", "Failed to fetch orders: " + e.getMessage()
                ));
        }
    }

    /**
     * Get product splits for a specific order
     *
     * @param orderId Order ID
     * @return List of products (splits) for the order with flight assignments
     *
     * Example: GET /api/query/products/12345
     */
    @GetMapping("/products/{orderId}")
    public ResponseEntity<Map<String, Object>> getProductsForOrder(@PathVariable Integer orderId) {

        try {
            Map<String, Object> response = controller.getProductSplitsForOrder(orderId);

            if (response.containsKey("success") && !(Boolean) response.get("success")) {
                return ResponseEntity.notFound().build();
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of(
                    "success", false,
                    "message", "Failed to fetch products: " + e.getMessage()
                ));
        }
    }

    /**
     * Get flight legs for a specific product (multi-hop routes)
     *
     * @param productId Product ID
     * @return List of flight legs in sequence order
     *
     * Example: GET /api/query/products/198/flights
     */
    @GetMapping("/products/{productId}/flights")
    public ResponseEntity<Map<String, Object>> getProductFlightLegs(@PathVariable Integer productId) {

        try {
            Map<String, Object> response = controller.getProductFlightLegs(productId);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of(
                    "success", false,
                    "message", "Failed to fetch flight legs: " + e.getMessage()
                ));
        }
    }

    /**
     * Get all flight legs for an order (combines all products' routes)
     *
     * @param orderId Order ID
     * @return List of unique flight legs across all products of the order
     *
     * Example: GET /api/query/orders/3/flights
     */
    @GetMapping("/orders/{orderId}/flights")
    public ResponseEntity<Map<String, Object>> getOrderFlightLegs(@PathVariable Integer orderId) {

        try {
            Map<String, Object> response = controller.getOrderFlightLegs(orderId);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of(
                    "success", false,
                    "message", "Failed to fetch order flight legs: " + e.getMessage()
                ));
        }
    }
}
