package com.system.morapack.schemas;

import lombok.*;
import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlgorithmRequest {
    // Simulation time parameters (for daily/weekly scenarios)
    private LocalDateTime simulationStartTime;  // When simulation starts
    private LocalDateTime simulationEndTime;    // When simulation ends (optional, can calculate from duration)
    private Integer simulationDurationDays;     // Alternative: duration in days
    private Double simulationDurationHours;     // Alternative: duration in hours (e.g., 0.5 = 30 minutes)

    // Data source
    private Boolean useDatabase; // true = use database data, false = use file data

    // ALNS parameters (optional, will use defaults if not provided)
    private Integer maxIterations;
    private Double destructionRate;

    // Simulation speed multiplier (1 = normal speed, 60 = 60x faster, etc.)
    // Used to control how frequently events are generated/transmitted
    private Double simulationSpeed;

    // Legacy fields for backward compatibility (deprecated)
    @Deprecated
    private String algorithmType; // No longer used - only ALNS is supported
    @Deprecated
    private Integer maxNoImprovement;
    @Deprecated
    private Integer neighborhoodSize;
    @Deprecated
    private Integer tabuListSize;
    @Deprecated
    private Long tabuTenure;
}
