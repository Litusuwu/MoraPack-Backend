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
}
