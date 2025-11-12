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
   * DAILY SCENARIO: Execute ALNS for incremental real-time operations
   * POST /api/algorithm/daily
   *
   * Request body example:
   * {
   *   "simulationStartTime": "2025-01-01T00:00:00",
   *   "simulationDurationHours": 0.5,  // 30 minutes
   *   "useDatabase": false
   * }
   *
   * This endpoint is designed for continuous real-time simulation:
   * - Frontend calls every ~30 simulation minutes
   * - Loads only orders within the time window
   * - Returns routes for this time slice
   * - Runs indefinitely until stopped
   */
  @PostMapping("/daily")
  public ResponseEntity<AlgorithmResultSchema> executeDaily(
      @RequestBody AlgorithmRequest request) {

    if (request == null || request.getSimulationStartTime() == null) {
      return ResponseEntity.badRequest().build();
    }

    AlgorithmResultSchema result = algorithmController.executeDailyScenario(request);

    if (result.getSuccess()) {
      return ResponseEntity.ok(result);
    } else {
      return ResponseEntity.internalServerError().body(result);
    }
  }

  /**
   * WEEKLY SCENARIO: Execute ALNS for 7-day batch simulation
   * POST /api/algorithm/weekly
   *
   * Request body example:
   * {
   *   "simulationStartTime": "2025-01-01T00:00:00",
   *   "simulationDurationDays": 7,
   *   "useDatabase": false
   * }
   *
   * This endpoint processes an entire week at once:
   * - Loads all orders from 7 days
   * - Returns complete 7-day solution
   * - Should execute in 30-90 minutes (per requirements)
   */
  @PostMapping("/weekly")
  public ResponseEntity<AlgorithmResultSchema> executeWeekly(
      @RequestBody AlgorithmRequest request) {

    if (request == null || request.getSimulationStartTime() == null) {
      return ResponseEntity.badRequest().build();
    }

    // Force 7-day duration for weekly scenario
    if (request.getSimulationDurationDays() == null) {
      request.setSimulationDurationDays(7);
    }

    AlgorithmResultSchema result = algorithmController.executeWeeklyScenario(request);

    if (result.getSuccess()) {
      return ResponseEntity.ok(result);
    } else {
      return ResponseEntity.internalServerError().body(result);
    }
  }

  /**
   * Execute the optimization algorithm (LEGACY - generic endpoint)
   * POST /api/algorithm/execute
   *
   * @deprecated Use /daily or /weekly endpoints for scenario-specific execution
   *
   * Request body example:
   * {
   *   "simulationStartTime": "2025-01-01T00:00:00",
   *   "simulationDurationDays": 7,
   *   "useDatabase": false
   * }
   *
   * Response includes:
   * - execution metrics (time, status)
   * - solution statistics (assigned/unassigned orders)
   * - productRoutes: array of {productId, orderId, flights[], originCity, destinationCity}
   */
  @PostMapping("/execute")
  @Deprecated
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
}
