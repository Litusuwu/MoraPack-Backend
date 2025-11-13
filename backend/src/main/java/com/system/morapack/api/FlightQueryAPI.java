package com.system.morapack.api;

import com.system.morapack.bll.controller.FlightQueryController;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST API for flight query operations
 * Provides endpoints for querying flight status and assignments
 */
@RestController
@RequestMapping("/api/query/flights")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class FlightQueryAPI {

    private final FlightQueryController controller;

    /**
     * GET /api/query/flights/status
     * Get all flights with their current status and utilization
     * Used for map display
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getAllFlightStatuses() {
        try {
            Map<String, Object> response = controller.getAllFlightStatuses();
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of(
                    "success", false,
                    "message", "Failed to get flight statuses: " + e.getMessage()
                ));
        }
    }

    /**
     * GET /api/query/flights/{flightCode}/orders
     * Get orders assigned to a specific flight
     * Used when user clicks on a flight in the map
     */
    @GetMapping("/{flightCode}/orders")
    public ResponseEntity<Map<String, Object>> getOrdersForFlight(@PathVariable String flightCode) {
        try {
            Map<String, Object> response = controller.getOrdersForFlight(flightCode);

            if (response.containsKey("success") && !(Boolean) response.get("success")) {
                return ResponseEntity.notFound().build();
            }

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of(
                    "success", false,
                    "message", "Failed to get orders for flight: " + e.getMessage()
                ));
        }
    }

    /**
     * GET /api/query/flights/{flightCode}/products
     * Get products assigned to a specific flight
     */
    @GetMapping("/{flightCode}/products")
    public ResponseEntity<Map<String, Object>> getProductsForFlight(@PathVariable String flightCode) {
        try {
            Map<String, Object> response = controller.getProductsForFlight(flightCode);

            if (response.containsKey("success") && !(Boolean) response.get("success")) {
                return ResponseEntity.notFound().build();
            }

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of(
                    "success", false,
                    "message", "Failed to get products for flight: " + e.getMessage()
                ));
        }
    }
}
