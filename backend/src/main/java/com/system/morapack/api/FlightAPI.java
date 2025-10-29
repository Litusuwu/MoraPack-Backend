package com.system.morapack.api;

import com.system.morapack.bll.controller.FlightController;
import com.system.morapack.schemas.FlightSchema;
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
}
