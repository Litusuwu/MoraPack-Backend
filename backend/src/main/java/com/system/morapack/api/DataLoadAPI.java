package com.system.morapack.api;

import com.system.morapack.bll.service.DataLoadService;
import com.system.morapack.config.Constants;
import com.system.morapack.dao.morapack_psql.repository.AirportRepository;
import com.system.morapack.dao.morapack_psql.repository.FlightRepository;
import com.system.morapack.dao.morapack_psql.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
    private final AirportRepository airportRepository;
    private final FlightRepository flightRepository;
    private final OrderRepository orderRepository;

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
            Map<String, Object> statistics = new HashMap<>();
            statistics.put("ordersLoaded", result.ordersLoaded);
            statistics.put("ordersCreated", result.ordersCreated);
            statistics.put("ordersFiltered", result.ordersFiltered);
            statistics.put("customersCreated", result.customersCreated);
            statistics.put("parseErrors", result.parseErrors);
            statistics.put("fileErrors", result.fileErrors);
            statistics.put("durationSeconds", result.durationSeconds);
            response.put("statistics", statistics);
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
     * Load orders for Daily Simulation with automatic cleanup and 10-minute timeframe
     * 
     * This endpoint is optimized for Daily Simulation:
     * - Automatically clears old orders/products before loading
     * - Loads orders within a 10-minute window from startTime
     * - Returns detailed statistics for UI feedback
     * 
     * Example: POST /api/data/load-for-daily?startTime=2025-01-02T00:00:00
     * 
     * @param startTimeStr Required: Start time of the simulation (ISO format string)
     * @return Statistics about the data loading process
     */
    @PostMapping("/load-for-daily")
    public ResponseEntity<Map<String, Object>> loadForDailySimulation(
            @RequestParam String startTime) {

        try {
            System.out.println("========================================");
            System.out.println("API: LOAD FOR DAILY SIMULATION");
            System.out.println("Received startTime param: " + startTime);
            System.out.println("========================================");

            // Parse startTime (handle various formats including timezone)
            LocalDateTime parsedStartTime;
            try {
                // Remove timezone suffix if present (.000Z or Z)
                String cleanedTime = startTime.replaceAll("\\.\\d{3}Z$", "").replaceAll("Z$", "");
                parsedStartTime = LocalDateTime.parse(cleanedTime, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                System.out.println("Parsed start time: " + parsedStartTime);
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid date format. Expected ISO 8601 format (e.g., 2025-01-02T00:00:00): " + e.getMessage());
            }

            // Calculate end time: startTime + 10 minutes (fixed timeframe)
            final int TIMEFRAME_MINUTES = 10;
            LocalDateTime endTime = parsedStartTime.plusMinutes(TIMEFRAME_MINUTES);

            System.out.println("Time Window: " + parsedStartTime + " to " + endTime);

            // Step 1: Clear all existing orders and products for clean start
            System.out.println("Clearing existing orders and products...");
            dataLoadService.clearAllOrders();
            System.out.println("✓ Old data cleared");

            // Step 2: Load orders within timeframe
            System.out.println("Loading orders from files...");
            String dirPath = getDefaultDataDirectory();
            DataLoadService.LoadOrdersResult result =
                dataLoadService.loadOrdersFromFiles(dirPath, parsedStartTime, endTime);

            // Step 3: Build response
            Map<String, Object> response = new HashMap<>();
            response.put("success", result.success);
            response.put("message", result.success ?
                "Data loaded successfully for daily simulation" : result.errorMessage);
            
            // Statistics
            Map<String, Object> statistics = new HashMap<>();
            statistics.put("ordersLoaded", result.ordersLoaded);
            statistics.put("ordersCreated", result.ordersCreated);
            statistics.put("ordersFiltered", result.ordersFiltered);
            statistics.put("customersCreated", result.customersCreated);
            statistics.put("parseErrors", result.parseErrors);
            statistics.put("fileErrors", result.fileErrors);
            statistics.put("durationSeconds", result.durationSeconds);
            response.put("statistics", statistics);

            // Time window info
            response.put("timeWindow", Map.of(
                "startTime", parsedStartTime.toString(),
                "endTime", endTime.toString(),
                "durationMinutes", TIMEFRAME_MINUTES
            ));

            System.out.println("✓ Loaded " + result.ordersCreated + " orders");
            System.out.println("========================================");

            return result.success ?
                ResponseEntity.ok(response) :
                ResponseEntity.internalServerError().body(response);

        } catch (Exception e) {
            e.printStackTrace();
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to load data for daily simulation: " + e.getMessage());
            errorResponse.put("error", e.getClass().getSimpleName());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * Refresh orders for Daily Simulation WITHOUT clearing existing orders
     * 
     * This endpoint is used when re-running the algorithm during Daily simulation:
     * - Does NOT clear existing orders (preserves manual orders and previous loads)
     * - Loads orders within the 10-minute window from startTime
     * - Skips duplicates (orders with same ID already in DB)
     * - Perfect for incremental order loading during simulation
     * 
     * Example: POST /api/data/refresh-orders-for-daily?startTime=2025-01-02T00:10:00
     * 
     * @param startTime Required: Start time of the current simulation window (ISO format string)
     * @return Statistics about the data loading process
     */
    @PostMapping("/refresh-orders-for-daily")
    public ResponseEntity<Map<String, Object>> refreshOrdersForDailySimulation(
            @RequestParam String startTime) {

        try {
            System.out.println("========================================");
            System.out.println("API: REFRESH ORDERS FOR DAILY SIMULATION");
            System.out.println("Received startTime param: " + startTime);
            System.out.println("(NOT clearing existing orders - checking duplicates)");
            System.out.println("========================================");

            // Parse startTime (handle various formats including timezone)
            LocalDateTime parsedStartTime;
            try {
                String cleanedTime = startTime.replaceAll("\\.\\d{3}Z$", "").replaceAll("Z$", "");
                parsedStartTime = LocalDateTime.parse(cleanedTime, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                System.out.println("Parsed start time: " + parsedStartTime);
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid date format. Expected ISO 8601 format (e.g., 2025-01-02T00:00:00): " + e.getMessage());
            }

            // Calculate end time: startTime + 10 minutes (fixed timeframe)
            final int TIMEFRAME_MINUTES = 10;
            LocalDateTime endTime = parsedStartTime.plusMinutes(TIMEFRAME_MINUTES);

            System.out.println("Time Window: " + parsedStartTime + " to " + endTime);

            // NOTE: We do NOT clear orders here - just load new ones with duplicate checking
            System.out.println("Loading orders from files (with duplicate check)...");
            String dirPath = getDefaultDataDirectory();
            
            // skipDuplicateCheck = false (default) means we CHECK for duplicates
            DataLoadService.LoadOrdersResult result =
                dataLoadService.loadOrdersFromFiles(dirPath, parsedStartTime, endTime, false);

            // Build response
            Map<String, Object> response = new HashMap<>();
            response.put("success", result.success);
            response.put("message", result.success ?
                "Orders refreshed successfully (duplicates skipped)" : result.errorMessage);
            
            // Statistics
            Map<String, Object> statistics = new HashMap<>();
            statistics.put("ordersLoaded", result.ordersLoaded);
            statistics.put("ordersCreated", result.ordersCreated);
            statistics.put("ordersFiltered", result.ordersFiltered);
            statistics.put("duplicatesSkipped", result.ordersLoaded - result.ordersCreated);
            statistics.put("customersCreated", result.customersCreated);
            statistics.put("parseErrors", result.parseErrors);
            statistics.put("fileErrors", result.fileErrors);
            statistics.put("durationSeconds", result.durationSeconds);
            response.put("statistics", statistics);

            // Time window info
            response.put("timeWindow", Map.of(
                "startTime", parsedStartTime.toString(),
                "endTime", endTime.toString(),
                "durationMinutes", TIMEFRAME_MINUTES
            ));

            System.out.println("✓ Created " + result.ordersCreated + " new orders (skipped " + 
                (result.ordersLoaded - result.ordersCreated) + " duplicates)");
            System.out.println("========================================");

            return result.success ?
                ResponseEntity.ok(response) :
                ResponseEntity.internalServerError().body(response);

        } catch (Exception e) {
            e.printStackTrace();
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to refresh orders: " + e.getMessage());
            errorResponse.put("error", e.getClass().getSimpleName());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * Get data loading status and statistics
     * Shows how many airports, flights and orders are currently in the database
     *
     * Example: GET /api/data/status
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getDataStatus() {
        try {
            // Get counts from database
            long airportCount = airportRepository.count();
            long flightCount = flightRepository.count();
            long orderCount = orderRepository.count();

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Data status retrieved successfully");
            response.put("dataDirectory", getDefaultDataDirectory());
            
            // Statistics
            Map<String, Object> statistics = new HashMap<>();
            statistics.put("airports", airportCount);
            statistics.put("flights", flightCount);
            statistics.put("orders", orderCount);
            response.put("statistics", statistics);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to get status: " + e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * Clear all orders and products from database
     * Use this before loading fresh data to avoid duplicates
     *
     * Example: DELETE /api/data/clear-orders
     */
    @DeleteMapping("/clear-orders")
    public ResponseEntity<Map<String, Object>> clearOrders() {
        try {
            System.out.println("========================================");
            System.out.println("API: CLEAR ORDERS REQUEST RECEIVED");
            System.out.println("========================================");

            dataLoadService.clearAllOrders();

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "All orders and products cleared successfully");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            e.printStackTrace();
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to clear orders: " + e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * Get default data directory path for order files
     */
    private String getDefaultDataDirectory() {
        // Return the directory containing order files (_pedidos_{AIRPORT}_.txt)
        return Constants.ORDER_FILES_DIRECTORY;
    }
}
