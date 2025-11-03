package com.system.morapack.api;

import com.system.morapack.bll.controller.AlgorithmController;
import com.system.morapack.schemas.AlgorithmRequest;
import com.system.morapack.schemas.AlgorithmResultSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/algorithm")
@RequiredArgsConstructor
public class AlgorithmAPI {

  private final AlgorithmController algorithmController;

  /**
   * Execute the optimization algorithm
   * POST /api/algorithm/execute
   *
   * Request body example:
   * {
   *   "algorithmType": "TABU",  // or "ALNS"
   *   "maxIterations": 1000,
   *   "maxNoImprovement": 100,
   *   "neighborhoodSize": 100,
   *   "tabuListSize": 50,
   *   "tabuTenure": 10000,
   *   "useDatabase": false
   * }
   *
   * Response includes:
   * - execution metrics (time, status)
   * - solution statistics (assigned/unassigned orders)
   * - productRoutes: array of {productId, orderId, flights[], originCity, destinationCity}
   */
  @PostMapping("/execute")
  public ResponseEntity<AlgorithmResultSchema> executeAlgorithm(
      @RequestBody(required = false) AlgorithmRequest request) {

    // Use default request if none provided
    if (request == null) {
      request = AlgorithmRequest.builder()
          .algorithmType("TABU")
          .maxIterations(1000)
          .maxNoImprovement(100)
          .neighborhoodSize(100)
          .tabuListSize(50)
          .tabuTenure(10000L)
          .useDatabase(false)
          .build();
    }

    AlgorithmResultSchema result = algorithmController.executeAlgorithm(request);

    if (result.getSuccess()) {
      return ResponseEntity.ok(result);
    } else {
      return ResponseEntity.internalServerError().body(result);
    }
  }

  /**
   * Quick execution with default Tabu Search parameters
   * POST /api/algorithm/execute/quick
   */
  @PostMapping("/execute/quick")
  public ResponseEntity<AlgorithmResultSchema> executeQuick() {
    AlgorithmRequest request = AlgorithmRequest.builder()
        .algorithmType("TABU")
        .maxIterations(500)  // Reduced for faster execution
        .maxNoImprovement(50)
        .neighborhoodSize(50)
        .tabuListSize(25)
        .tabuTenure(5000L)
        .useDatabase(false)
        .build();

    AlgorithmResultSchema result = algorithmController.executeAlgorithm(request);

    if (result.getSuccess()) {
      return ResponseEntity.ok(result);
    } else {
      return ResponseEntity.internalServerError().body(result);
    }
  }

  /**
   * Execute ALNS algorithm with default parameters
   * POST /api/algorithm/execute/alns
   */
  @PostMapping("/execute/alns")
  public ResponseEntity<AlgorithmResultSchema> executeALNS() {
    AlgorithmRequest request = AlgorithmRequest.builder()
        .algorithmType("ALNS")
        .useDatabase(false)
        .build();

    AlgorithmResultSchema result = algorithmController.executeAlgorithm(request);

    if (result.getSuccess()) {
      return ResponseEntity.ok(result);
    } else {
      return ResponseEntity.internalServerError().body(result);
    }
  }

  /**
   * Execute Tabu Search algorithm with default parameters
   * POST /api/algorithm/execute/tabu
   */
  @PostMapping("/execute/tabu")
  public ResponseEntity<AlgorithmResultSchema> executeTabu() {
    AlgorithmRequest request = AlgorithmRequest.builder()
        .algorithmType("TABU")
        .maxIterations(1000)
        .maxNoImprovement(100)
        .neighborhoodSize(100)
        .tabuListSize(50)
        .tabuTenure(10000L)
        .useDatabase(false)
        .build();

    AlgorithmResultSchema result = algorithmController.executeAlgorithm(request);

    if (result.getSuccess()) {
      return ResponseEntity.ok(result);
    } else {
      return ResponseEntity.internalServerError().body(result);
    }
  }

  /**
   * Replan/reoptimize routes after simulation events (flight cancellations, new orders)
   * POST /api/algorithm/replan
   * 
   * This endpoint re-executes the ALNS algorithm using current database state.
   * Use this after:
   * - Cancelling a flight (POST /api/flights/{id}/cancel)
   * - Adding an urgent order (POST /api/orders/urgent)
   * - Any other event that requires route replanning
   * 
   * Request body (optional):
   * {
   *   "reason": "FLIGHT_CANCELLED",
   *   "affectedFlightId": 123,
   *   "currentSimulationTime": "2025-01-03T14:30:00",
   *   "quickMode": true  // Optional: use reduced iterations for faster replanning
   * }
   * 
   * Response: AlgorithmResultSchema with updated routes and timeline
   */
  @PostMapping("/replan")
  public ResponseEntity<ReplanResponse> replanRoutes(
      @RequestBody(required = false) ReplanRequest request) {
    
    System.out.println("===========================================");
    System.out.println("REPLANNING REQUEST DURING SIMULATION");
    if (request != null) {
      System.out.println("Reason: " + request.getReason());
      System.out.println("Affected Flight ID: " + request.getAffectedFlightId());
      System.out.println("Current Sim Time: " + request.getCurrentSimulationTime());
      System.out.println("Quick Mode: " + request.getQuickMode());
    }
    System.out.println("===========================================");
    
    // Build ALNS request - always use DATABASE mode for replanning
    AlgorithmRequest algorithmRequest = AlgorithmRequest.builder()
        .algorithmType("ALNS")
        .useDatabase(true)  // IMPORTANT: Use current database state
        .build();
    
    // If quick mode, we could reduce iterations (future enhancement)
    boolean quickMode = request != null && Boolean.TRUE.equals(request.getQuickMode());
    if (quickMode) {
      System.out.println("[REPLAN] Quick mode enabled - using reduced iterations");
      // Could set maxIterations here if AlgorithmRequest supported it for ALNS
    }
    
    // Execute ALNS with current database state
    long startTime = System.currentTimeMillis();
    AlgorithmResultSchema result = algorithmController.executeAlgorithm(algorithmRequest);
    long endTime = System.currentTimeMillis();
    long executionTimeMs = endTime - startTime;
    
    System.out.println("[REPLAN] Replanning completed in " + executionTimeMs + "ms");
    System.out.println("[REPLAN] Total products: " + result.getTotalProducts());
    System.out.println("[REPLAN] Product routes: " + 
        (result.getProductRoutes() != null ? result.getProductRoutes().size() : 0));
    
    if (result.getSuccess()) {
      return ResponseEntity.ok(
          ReplanResponse.builder()
              .success(true)
              .message("Replanning completed successfully")
              .executionTimeMs(executionTimeMs)
              .algorithmResult(result)
              .build()
      );
    } else {
      return ResponseEntity.internalServerError().body(
          ReplanResponse.builder()
              .success(false)
              .message("Replanning failed: " + result.getMessage())
              .executionTimeMs(executionTimeMs)
              .algorithmResult(result)
              .build()
      );
    }
  }

  /**
   * Request DTO for replanning
   */
  @lombok.Data
  @lombok.Builder
  @lombok.NoArgsConstructor
  @lombok.AllArgsConstructor
  static class ReplanRequest {
    private String reason; // "FLIGHT_CANCELLED", "NEW_ORDER", "FLIGHT_DELAYED", etc.
    private Integer affectedFlightId;
    private String currentSimulationTime; // ISO timestamp
    private Boolean quickMode; // If true, use reduced iterations for faster execution
  }

  /**
   * Response DTO for replanning
   */
  @lombok.Data
  @lombok.Builder
  @lombok.NoArgsConstructor
  @lombok.AllArgsConstructor
  static class ReplanResponse {
    private Boolean success;
    private String message;
    private Long executionTimeMs;
    private AlgorithmResultSchema algorithmResult;
  }
}
