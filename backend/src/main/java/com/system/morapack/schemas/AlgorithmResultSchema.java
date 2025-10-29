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
    private String algorithmType;
    private LocalDateTime executionStartTime;
    private LocalDateTime executionEndTime;
    private Long executionTimeSeconds;

    // Solution metrics
    private Integer totalOrders;
    private Integer assignedOrders;
    private Integer unassignedOrders;
    private Integer totalProducts;
    private Double score;

    // The main result: map of products to their flight routes
    // Using DTO to avoid circular references and reduce JSON size
    private List<ProductRouteDTO> productRoutes;
    
    // Temporal simulation timeline
    private SimulationTimelineResult timeline;

    // Raw solution for debugging (optional)
    private Map<String, Object> rawSolution;
}
