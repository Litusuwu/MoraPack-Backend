package com.system.morapack.api;

import com.system.morapack.bll.controller.FlightController;
import com.system.morapack.schemas.FlightSchema;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/flights")
@RequiredArgsConstructor
public class FlightAPI {

  private final FlightController flightController;

  @GetMapping("/{id}")
  public ResponseEntity<FlightSchema> getFlight(@PathVariable Integer id) {
    return ResponseEntity.ok(flightController.getFlight(id));
  }

  @GetMapping
  public ResponseEntity<List<FlightSchema>> getFlights(
      @RequestParam(required = false) List<Integer> ids,
      @RequestParam(required = false) Integer airplaneId,
      @RequestParam(required = false) Integer originAirportId,
      @RequestParam(required = false) Integer destinationAirportId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer minCapacity,
      @RequestParam(required = false) Double minTransportTime,
      @RequestParam(required = false) Double maxTransportTime,
      @RequestParam(required = false) Integer minFrequency,
      @RequestParam(required = false) Integer maxFrequency,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate) {

    if (airplaneId != null) {
      return ResponseEntity.ok(flightController.getByAirplane(airplaneId));
    }

    if (originAirportId != null) {
      return ResponseEntity.ok(flightController.getByOrigin(originAirportId));
    }

    if (destinationAirportId != null) {
      return ResponseEntity.ok(flightController.getByDestination(destinationAirportId));
    }

    if (status != null) {
      return ResponseEntity.ok(flightController.getByStatus(status));
    }

    if (minCapacity != null) {
      return ResponseEntity.ok(flightController.getByCapacityAtLeast(minCapacity));
    }

    if (minTransportTime != null && maxTransportTime != null) {
      return ResponseEntity.ok(flightController.getByTransportTimeRange(minTransportTime, maxTransportTime));
    }

    if (minFrequency != null && maxFrequency != null) {
      return ResponseEntity.ok(flightController.getByDailyFrequencyRange(minFrequency, maxFrequency));
    }

    if (startDate != null && endDate != null) {
      return ResponseEntity.ok(flightController.getByCreatedAtRange(startDate, endDate));
    }

    return ResponseEntity.ok(flightController.fetchFlights(ids));
  }

  @PostMapping
  public ResponseEntity<FlightSchema> createFlight(@RequestBody FlightSchema flight) {
    return ResponseEntity.ok(flightController.createFlight(flight));
  }

  @PostMapping("/bulk")
  public ResponseEntity<List<FlightSchema>> createFlights(@RequestBody List<FlightSchema> flights) {
    return ResponseEntity.ok(flightController.bulkCreateFlights(flights));
  }

  @PutMapping("/{id}")
  public ResponseEntity<FlightSchema> updateFlight(@PathVariable Integer id, @RequestBody FlightSchema updates) {
    return ResponseEntity.ok(flightController.updateFlight(id, updates));
  }

  /**
   * Update flight status (e.g., cancel a flight by setting status to "INACTIVE")
   * PATCH /api/flights/{id}/status
   * 
   * Body: { "status": "INACTIVE" } or { "status": "ACTIVE" }
   */
  @PatchMapping("/{id}/status")
  public ResponseEntity<FlightSchema> updateFlightStatus(
      @PathVariable Integer id,
      @RequestBody FlightStatusUpdateRequest request) {
    FlightSchema flight = flightController.getFlight(id);
    flight.setStatus(request.getStatus());
    return ResponseEntity.ok(flightController.updateFlight(id, flight));
  }

  /**
   * Cancel a flight during simulation
   * POST /api/flights/{id}/cancel
   * 
   * This endpoint:
   * - Validates the flight exists and can be cancelled
   * - Sets flight status to INACTIVE/CANCELLED
   * - Returns updated flight and affected products count
   * 
   * Response example:
   * {
   *   "success": true,
   *   "message": "Flight F123 cancelled successfully",
   *   "flight": { ... },
   *   "affectedProducts": 25,
   *   "requiresReplanning": true
   * }
   */
  @PostMapping("/{id}/cancel")
  public ResponseEntity<FlightCancellationResponse> cancelFlight(
      @PathVariable Integer id,
      @RequestBody(required = false) FlightCancellationRequest request) {
    
    System.out.println("===========================================");
    System.out.println("FLIGHT CANCELLATION REQUEST");
    System.out.println("Flight ID: " + id);
    if (request != null) {
      System.out.println("Reason: " + request.getReason());
      System.out.println("Simulation Time: " + request.getCurrentSimulationTime());
    }
    System.out.println("===========================================");
    
    // Get the flight
    FlightSchema flight = flightController.getFlight(id);
    
    if (flight == null) {
      return ResponseEntity.badRequest().body(
          FlightCancellationResponse.builder()
              .success(false)
              .message("Flight with ID " + id + " not found")
              .build()
      );
    }
    
    // Validate that flight can be cancelled (not already completed or in-flight)
    if ("COMPLETADO".equals(flight.getStatus()) || "EN_VUELO".equals(flight.getStatus())) {
      return ResponseEntity.badRequest().body(
          FlightCancellationResponse.builder()
              .success(false)
              .message("Cannot cancel flight " + flight.getCode() + " - already " + flight.getStatus())
              .flight(flight)
              .requiresReplanning(false)
              .build()
      );
    }
    
    // Update flight status to INACTIVE (cancelled)
    flight.setStatus("INACTIVE");
    FlightSchema updatedFlight = flightController.updateFlight(id, flight);
    
    System.out.println("Flight " + flight.getCode() + " cancelled successfully");
    
    return ResponseEntity.ok(
        FlightCancellationResponse.builder()
            .success(true)
            .message("Flight " + flight.getCode() + " cancelled successfully")
            .flight(updatedFlight)
            .affectedProducts(0) // TODO: Calculate affected products
            .requiresReplanning(true)
            .build()
    );
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> deleteFlight(@PathVariable Integer id) {
    flightController.deleteFlight(id);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/count")
  public ResponseEntity<Long> countFlights(@RequestParam(required = false) String status) {
    if (status != null) {
      return ResponseEntity.ok(flightController.countFlightsByStatus(status));
    }
    return ResponseEntity.ok(flightController.countAllFlights());
  }

  /**
   * Request DTO for updating flight status
   */
  @Data
  static class FlightStatusUpdateRequest {
    private String status;
  }

  /**
   * Request DTO for flight cancellation
   */
  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  static class FlightCancellationRequest {
    private String reason; // "WEATHER", "MECHANICAL", "MANUAL", etc.
    private String currentSimulationTime; // ISO timestamp of current simulation time
  }

  /**
   * Response DTO for flight cancellation
   */
  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  static class FlightCancellationResponse {
    private Boolean success;
    private String message;
    private FlightSchema flight;
    private Integer affectedProducts;
    private Boolean requiresReplanning;
  }
}
