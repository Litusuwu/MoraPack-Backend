package com.system.morapack.schemas;

import lombok.*;
import java.time.LocalTime;

/**
 * Simplified Flight Data Transfer Object for API responses
 * Contains only the essential flight information without circular references
 */
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class FlightDTO {
    private Integer id;
    private String code;
    private String routeType;
    private Integer maxCapacity;
    private Double transportTimeDays;
    private String status;

    // Simplified origin and destination (just city names)
    private String originCity;
    private String destinationCity;
    private Integer originAirportId;
    private Integer destinationAirportId;

    // Flight schedule times (from flights.txt: HH:mm format)
    private LocalTime departureTime;  // e.g., 03:34
    private LocalTime arrivalTime;    // e.g., 05:21
}


