package com.system.morapack.schemas;

import lombok.*;
import java.time.LocalDateTime;

/**
 * Represents a specific instance of a flight on a particular day
 *
 * Example:
 * - Flight LIM-CUZ departs daily at 08:00
 * - FlightInstanceSchema for Day 1: departureDateTime = 2025-01-02T08:00
 * - FlightInstanceSchema for Day 2: departureDateTime = 2025-01-03T08:00
 *
 * This allows tracking capacity per individual departure, not just per route
 */
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class FlightInstanceSchema {

    // Base flight information
    private Integer baseFlightId;           // Original flight from flights table
    private FlightSchema baseFlight;        // Reference to base flight schema

    // Instance-specific information
    private LocalDateTime departureDateTime; // Specific departure time (e.g., 2025-01-02T08:00)
    private LocalDateTime arrivalDateTime;   // Specific arrival time (e.g., 2025-01-02T13:00)
    private Integer instanceDay;             // Simulation day number (0, 1, 2, ...)

    // Capacity tracking (per instance)
    private Integer maxCapacity;             // Copied from base flight
    private Integer usedCapacity;            // How many products assigned to THIS departure

    // Computed unique identifier
    private String instanceId;               // e.g., "FL-123-DAY-1-0800"

    /**
     * Generate unique instance ID
     */
    public String generateInstanceId() {
        this.instanceId = String.format("FL-%d-DAY-%d-%02d%02d",
            baseFlightId,
            instanceDay,
            departureDateTime.getHour(),
            departureDateTime.getMinute()
        );
        return this.instanceId;
    }

    /**
     * Check if this instance has available capacity
     */
    public boolean hasCapacity(int quantity) {
        return (usedCapacity + quantity) <= maxCapacity;
    }

    /**
     * Reserve capacity on this instance
     */
    public void reserveCapacity(int quantity) {
        this.usedCapacity += quantity;
    }
}
