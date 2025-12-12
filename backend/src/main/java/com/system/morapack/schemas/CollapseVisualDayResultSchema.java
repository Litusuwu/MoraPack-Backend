package com.system.morapack.schemas;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Schema for VISUAL collapse simulation - ONE DAY at a time
 * 
 * Unlike CollapseResultSchema which runs the entire simulation to completion,
 * this schema returns results for a SINGLE DAY, allowing the frontend to:
 * 1. Animate flights and packages on the map
 * 2. Show day-by-day progression visually
 * 3. Detect and display collapse in real-time
 * 
 * The frontend calls this endpoint repeatedly for each day until collapse or stop.
 * 
 * COLLAPSE DETECTION (same as batch collapse):
 * - Continental SLA: 48 hours (2 days)
 * - Intercontinental SLA: 72 hours (3 days)  
 * - Collapse when unassigned rate > 10% OR backlog grows 5 consecutive days
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CollapseVisualDayResultSchema {

    private Boolean success;
    private String message;
    
    // Day identification
    private Integer dayNumber;                    // Current day being processed (1, 2, 3...)
    private LocalDateTime dayStart;               // Start of this day (00:00)
    private LocalDateTime dayEnd;                 // End of this day (23:59:59)
    
    // Collapse status
    private Boolean hasReachedCollapse;           // True if system collapsed ON THIS DAY
    private String collapseReason;                // SLA_BREACH, CAPACITY_EXHAUSTED, or null if no collapse
    private Boolean continueSimulation;           // False if collapsed or max days reached
    
    // Today's statistics
    private Integer ordersLoadedToday;            // New orders loaded for this day
    private Integer productsAssignedToday;        // Products assigned in this day's run
    private Integer productsUnassignedToday;      // Products that couldn't be assigned
    private Double assignmentRateToday;           // Percentage assigned today
    
    // Cumulative statistics (across all days so far)
    private Integer totalDaysSimulated;           // Total days run so far
    private Integer totalOrdersLoaded;            // All orders loaded so far
    private Integer totalProductsInSystem;        // All products in system (assigned + backlog)
    private Integer cumulativeAssigned;           // All assigned products so far
    private Integer cumulativeBacklog;            // Current backlog size
    private Double cumulativeAssignmentRate;      // Overall assignment rate
    
    // SLA metrics for this day
    private Integer productsOnTimeToday;
    private Integer productsLateToday;
    private Double slaComplianceToday;            // % within SLA today
    
    // Backlog trend (for collapse prediction)
    private Integer previousDayBacklog;           // Backlog from yesterday
    private Integer consecutiveGrowingDays;       // Days backlog has been growing
    private Boolean backlogIsGrowing;             // True if backlog grew vs yesterday
    
    // Execution metrics
    private LocalDateTime executionStartTime;
    private LocalDateTime executionEndTime;
    private Long executionTimeMs;                 // Milliseconds for this day's processing
    
    // Progress indicators for UI
    private Double collapseProgress;              // 0-100, estimate of how close to collapse
    private String statusLabel;                   // "HEALTHY", "WARNING", "CRITICAL", "COLLAPSED"
    
    // Flight instances used in this day's solution (for accurate visualization)
    // Each entry contains: flightId, departureTime, arrivalTime, origin, destination, productCount
    private java.util.List<FlightInstanceDTO> assignedFlightInstances;
    
    /**
     * DTO for flight instances used in the solution
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FlightInstanceDTO {
        private String instanceId;          // e.g. "FL-123-DAY-1-0800"
        private Integer flightId;
        private String flightCode;
        private LocalDateTime departureTime;
        private LocalDateTime arrivalTime;
        private String originCode;          // IATA code
        private String destinationCode;     // IATA code
        private Double originLat;
        private Double originLng;
        private Double destLat;
        private Double destLng;
        private Integer productCount;       // Products assigned to this instance
    }
}