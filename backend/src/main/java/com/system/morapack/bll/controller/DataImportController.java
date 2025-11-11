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

    // === NUEVO ===
    @PostMapping(value = "/airports", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<Map<String, Object>> loadAirports() {
        Map<String, Object> result = dataImportService.importAirports();
        boolean success = (boolean) result.get("success");
        return ResponseEntity.status(success ? HttpStatus.OK : HttpStatus.BAD_REQUEST).body(result);
    }

    // === NUEVO ===
    @PostMapping(value = "/flights", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<Map<String, Object>> loadFlights() {
        Map<String, Object> result = dataImportService.importFlights();
        boolean success = (boolean) result.get("success");
        return ResponseEntity.status(success ? HttpStatus.OK : HttpStatus.BAD_REQUEST).body(result);
    }

    // === SIN CAMBIOS ===
    @PostMapping(value = "/orders", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadOrders(
            @RequestParam("file") MultipartFile file) {

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

        Map<String, Object> result = dataImportService.importOrders(file);
        boolean success = (boolean) result.get("success");
        HttpStatus status = success ? HttpStatus.OK : HttpStatus.BAD_REQUEST;

        return ResponseEntity.status(status).body(result);
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getImportStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("message", "Data import endpoints are operational");
        status.put("endpoints", Map.of(
                "airports", "/api/data-import/airports (POST)",
                "flights", "/api/data-import/flights (POST)",
                "orders", "/api/data-import/orders (POST)"
        ));
        return ResponseEntity.ok(status);
    }

    @PostMapping("/orders-by-date")
    public ResponseEntity<Map<String, Object>> importOrdersByDateRange(
            @RequestParam("startDate") String startDate,
            @RequestParam("endDate") String endDate) {

        Map<String, Object> result = dataImportService.importOrdersByDateRange(startDate, endDate);
        boolean success = (boolean) result.get("success");
        HttpStatus status = success ? HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(result);
    }
}
