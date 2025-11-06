package com.system.morapack.api;

import com.system.morapack.bll.service.DataLoadService;
import com.system.morapack.config.Constants;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * REST API for loading data from files into the database
 * Implements Option A architecture: separate data loading from algorithm execution
 *
 * Typical workflow:
 * 1. POST /api/data/load-orders (load order files to DB)
 * 2. POST /api/algorithm/daily (algorithm reads from DB)
 */
@RestController
@RequestMapping("/api/data")
@RequiredArgsConstructor
public class DataLoadAPI {

    private final DataLoadService dataLoadService;

    /**
     * Load orders from _pedidos_{AIRPORT}_ files into database
     *
     * @param dataDirectory Optional: path to data directory (defaults to Constants.DATA_DIRECTORY)
     * @param startTime Optional: only load orders after this time
     * @param endTime Optional: only load orders before this time
     * @return Statistics about loaded orders
     *
     * Examples:
     *   POST /api/data/load-orders
     *   POST /api/data/load-orders?startTime=2025-01-02T00:00:00&endTime=2025-01-02T01:00:00
     *   POST /api/data/load-orders?dataDirectory=/custom/path
     */
    @PostMapping("/load-orders")
    public ResponseEntity<Map<String, Object>> loadOrders(
            @RequestParam(required = false) String dataDirectory,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime startTime,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime endTime) {

        try {
            System.out.println("========================================");
            System.out.println("API: LOAD ORDERS REQUEST RECEIVED");
            System.out.println("========================================");

            // Use default data directory if not specified
            String dirPath = dataDirectory != null ? dataDirectory : getDefaultDataDirectory();

            // Load orders
            DataLoadService.LoadOrdersResult result =
                dataLoadService.loadOrdersFromFiles(dirPath, startTime, endTime);

            // Build response
            Map<String, Object> response = new HashMap<>();
            response.put("success", result.success);
            response.put("message", result.success ?
                "Orders loaded successfully" : result.errorMessage);
            response.put("statistics", Map.of(
                "ordersLoaded", result.ordersLoaded,
                "ordersCreated", result.ordersCreated,
                "ordersFiltered", result.ordersFiltered,
                "parseErrors", result.parseErrors,
                "fileErrors", result.fileErrors,
                "durationSeconds", result.durationSeconds
            ));
            response.put("startTime", result.startTime);
            response.put("endTime", result.endTime);

            if (startTime != null && endTime != null) {
                response.put("timeWindow", Map.of(
                    "startTime", startTime.toString(),
                    "endTime", endTime.toString()
                ));
            }

            return result.success ?
                ResponseEntity.ok(response) :
                ResponseEntity.internalServerError().body(response);

        } catch (Exception e) {
            e.printStackTrace();
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to load orders: " + e.getMessage());
            errorResponse.put("error", e.getClass().getSimpleName());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * Get data loading status and statistics
     * Shows how many orders are currently in the database
     *
     * Example: GET /api/data/status
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getDataStatus() {
        try {
            // TODO: Implement actual status check
            // For now, return placeholder

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Data status endpoint (placeholder)");
            response.put("dataDirectory", getDefaultDataDirectory());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to get status: " + e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * Get default data directory path
     */
    private String getDefaultDataDirectory() {
        // Get directory from PRODUCTS_FILE_PATH (which is in the data directory)
        String productsPath = Constants.PRODUCTS_FILE_PATH;
        java.io.File productsFile = new java.io.File(productsPath);
        return productsFile.getParent();
    }
}
