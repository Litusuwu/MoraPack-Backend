package com.system.morapack.bll.controller;

import com.system.morapack.bll.service.DataImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/data-import")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class DataImportController {

    private final DataImportService dataImportService;

    /**
     * Upload and import airports from text file
     * POST /api/data-import/airports
     * 
     * Expected file format (same as airportInfo.txt):
     * ID CodeIATA City Country Alias Timezone Capacity Latitude: X.XXXX Longitude: Y.YYYY
     * 
     * @param file MultipartFile containing airport data
     * @return ImportResult with success status, message, and count
     */
    @PostMapping(value = "/airports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadAirports(
            @RequestParam("file") MultipartFile file) {
        
        // Validate file
        if (file.isEmpty()) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "El archivo está vacío");
            return ResponseEntity.badRequest().body(error);
        }
        
        if (!file.getOriginalFilename().endsWith(".txt")) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "El archivo debe ser formato .txt");
            return ResponseEntity.badRequest().body(error);
        }
        
        // Process import
        Map<String, Object> result = dataImportService.importAirports(file);
        
        boolean success = (boolean) result.get("success");
        HttpStatus status = success ? HttpStatus.OK : HttpStatus.BAD_REQUEST;
        
        return ResponseEntity.status(status).body(result);
    }

    /**
     * Upload and import flights from text file
     * POST /api/data-import/flights
     * 
     * Expected file format (same as flights.txt):
     * ORIGIN-DESTINATION-DEPARTURE-ARRIVAL-CAPACITY
     * Example: BOG-UIO-0830-1045-250
     * 
     * @param file MultipartFile containing flight data
     * @return ImportResult with success status, message, and count
     */
    @PostMapping(value = "/flights", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadFlights(
            @RequestParam("file") MultipartFile file) {
        
        // Validate file
        if (file.isEmpty()) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "El archivo está vacío");
            return ResponseEntity.badRequest().body(error);
        }
        
        if (!file.getOriginalFilename().endsWith(".txt")) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "El archivo debe ser formato .txt");
            return ResponseEntity.badRequest().body(error);
        }
        
        // Process import
        Map<String, Object> result = dataImportService.importFlights(file);
        
        boolean success = (boolean) result.get("success");
        HttpStatus status = success ? HttpStatus.OK : HttpStatus.BAD_REQUEST;
        
        return ResponseEntity.status(status).body(result);
    }

    /**
     * Upload and import orders/products from text file
     * POST /api/data-import/orders
     * 
     * Expected file format (same as products.txt):
     * dd hh mm dest ### IdClien
     * dd: priority days (01/04/12/24)
     * hh: hours (01-23)
     * mm: minutes (01-59)
     * dest: destination airport code
     * ###: product quantity (001-999)
     * IdClien: customer ID (7 digits)
     * 
     * Example: 01 10 30 BOG 005 1234567
     * 
     * @param file MultipartFile containing order/product data
     * @return ImportResult with success status, message, orders count, products count
     */
    @PostMapping(value = "/orders", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadOrders(
            @RequestParam("file") MultipartFile file) {
        
        // Validate file
        if (file.isEmpty()) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "El archivo está vacío");
            return ResponseEntity.badRequest().body(error);
        }
        
        if (!file.getOriginalFilename().endsWith(".txt")) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "El archivo debe ser formato .txt");
            return ResponseEntity.badRequest().body(error);
        }
        
        // Process import
        Map<String, Object> result = dataImportService.importOrders(file);
        
        boolean success = (boolean) result.get("success");
        HttpStatus status = success ? HttpStatus.OK : HttpStatus.BAD_REQUEST;
        
        return ResponseEntity.status(status).body(result);
    }

    /**
     * Get import status and statistics
     * GET /api/data-import/status
     * 
     * @return Current counts of airports, flights, orders, and products in database
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getImportStatus() {
        // This endpoint could be extended to show import statistics
        Map<String, Object> status = new HashMap<>();
        status.put("message", "Data import endpoints are operational");
        status.put("endpoints", Map.of(
            "airports", "/api/data-import/airports (POST)",
            "flights", "/api/data-import/flights (POST)",
            "orders", "/api/data-import/orders (POST)"
        ));
        return ResponseEntity.ok(status);
    }
}


