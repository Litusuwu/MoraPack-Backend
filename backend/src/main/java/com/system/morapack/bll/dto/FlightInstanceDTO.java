package com.system.morapack.bll.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class FlightInstanceDTO {
    private String id;
    private Integer flightId;
    private String flightCode;
    private LocalDateTime departureTime;
    private LocalDateTime arrivalTime;

    private Integer originAirportId;
    private Integer destinationAirportId;

    // 👇 Estructura anidada que espera TS
    private AirportLocationDTO originAirport;
    private AirportLocationDTO destinationAirport;

    private String status;          // 'SCHEDULED', 'IN_FLIGHT', etc.
    private Integer assignedProducts;
}