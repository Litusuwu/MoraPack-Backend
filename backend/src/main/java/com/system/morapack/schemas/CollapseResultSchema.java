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
 * 
 * COLLAPSE DEFINITION (SLA-based):
 * - Continental orders: must be delivered within 2 days (48 hours)
 * - Intercontinental orders: must be delivered within 3 days (72 hours)
 * - System collapses when SLA violation rate exceeds threshold (default: 5%)
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
    private String collapseReason; // SLA_BREACH, CAPACITY_EXHAUSTED, NO_FLIGHTS, MAX_DAYS_REACHED
    
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
    
    // NEW: SLA-based collapse metrics
    private Integer productsOnTime;           // Products delivered within SLA
    private Integer productsLate;             // Products delivered after SLA deadline
    private Double slaCompliancePercentage;   // % of products on time (100% = perfect)
    private Double slaViolationPercentage;    // % of products late (0% = perfect)
    private Double slaThresholdUsed;          // Threshold used to determine collapse (e.g., 5%)
    
    // Continental vs Intercontinental breakdown
    private Integer continentalOrdersTotal;
    private Integer continentalOrdersOnTime;
    private Integer continentalOrdersLate;
    private Double continentalSlaCompliance;
    
    private Integer intercontinentalOrdersTotal;
    private Integer intercontinentalOrdersOnTime;
    private Integer intercontinentalOrdersLate;
    private Double intercontinentalSlaCompliance;
    
    // Detailed SLA violations (optional, for analysis)
    private List<SLAViolationDetail> slaViolations;
    
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
        // NEW: SLA metrics per day
        private Integer productsOnTime;
        private Integer productsLate;
        private Double slaComplianceRate;
    }
    
    /**
     * Details of individual SLA violations
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SLAViolationDetail {
        private String orderName;
        private String originContinent;
        private String destinationContinent;
        private Boolean isContinental;        // true = same continent
        private Integer slaMaxHours;          // 48 for continental, 72 for intercontinental
        private Double actualDeliveryHours;   // Actual time taken
        private Double hoursOverdue;          // How many hours late
        private LocalDateTime orderDate;
        private LocalDateTime expectedDeadline;
        private LocalDateTime actualDelivery;
    }
}

