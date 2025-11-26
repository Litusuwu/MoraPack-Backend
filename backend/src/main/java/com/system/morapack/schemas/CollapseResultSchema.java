package com.system.morapack.schemas;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Schema for collapse simulation results
 * Used when running the algorithm until system collapse
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CollapseResultSchema {

    private Boolean success;
    private String message;
    
    // Collapse detection
    private Boolean hasCollapsed;
    private Integer collapseDay;
    private LocalDateTime collapseTime;
    private String collapseReason; // UNASSIGNED_ORDERS, WAREHOUSE_SATURATED, NO_FLIGHTS, MAX_DAYS_REACHED
    
    // Execution metrics
    private LocalDateTime executionStartTime;
    private LocalDateTime executionEndTime;
    private Long executionTimeSeconds;
    
    // Simulation window
    private LocalDateTime simulationStartTime;
    private Integer totalDaysSimulated;
    
    // Cumulative statistics
    private Integer totalOrdersProcessed;
    private Integer totalProductsProcessed;
    private Integer assignedProducts;
    private Integer unassignedProducts;
    private Double unassignedPercentage;
    
    // Per-day breakdown (optional, for detailed analysis)
    private List<DayStatistics> dailyStatistics;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DayStatistics {
        private Integer dayNumber;
        private LocalDateTime dayStart;
        private Integer ordersProcessed;
        private Integer productsAssigned;
        private Integer productsUnassigned;
        private Double assignmentRate;
    }
}

