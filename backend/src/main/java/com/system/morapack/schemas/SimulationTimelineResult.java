package com.system.morapack.schemas;

import lombok.*;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Contains the temporal timeline of the simulation
 * Includes all events ordered by time for playback in the frontend
 */
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SimulationTimelineResult {
    private LocalDateTime simulationStartTime;
    private LocalDateTime simulationEndTime;
    private Long totalDurationMinutes;
    private List<FlightTimelineEvent> events;
    private List<ProductRouteDTO> productRoutes;
    
    // Summary statistics
    private Integer totalProducts;
    private Integer totalFlights;
    private Integer totalAirports;
}


