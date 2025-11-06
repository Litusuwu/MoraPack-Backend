package com.system.morapack.schemas;

import lombok.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlgorithmResultSchema {
    private Boolean success;
    private String message;

    // Execution metrics
    private LocalDateTime executionStartTime;
    private LocalDateTime executionEndTime;
    private Long executionTimeSeconds;

    // Simulation window info (when the simulation ran)
    private LocalDateTime simulationStartTime;  // Start of simulation window
    private LocalDateTime simulationEndTime;    // End of simulation window

    // Solution metrics - Order level
    private Integer totalOrders;
    private Integer assignedOrders;
    private Integer unassignedOrders;

    // Solution metrics - Product level
    private Integer totalProducts;
    private Integer assignedProducts;
    private Integer unassignedProducts;
    private Double score;

    // The main result: map of products to their flight routes
    // Using DTO to avoid circular references and reduce JSON size
    private List<ProductRouteDTO> productRoutes;

    // Temporal simulation timeline
    private SimulationTimelineResult timeline;

    // Raw solution for debugging (optional)
    private Map<String, Object> rawSolution;

    // Legacy field for backward compatibility (deprecated)
    @Deprecated
    private String algorithmType; // No longer used - only ALNS is supported
}
