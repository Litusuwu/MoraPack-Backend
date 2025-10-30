package com.system.morapack.schemas;

import lombok.*;
import java.time.LocalDateTime;

/**
 * Represents a temporal event in the flight simulation timeline
 * Used to track when flights depart and arrive for temporal simulation
 */
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class FlightTimelineEvent {
    private String eventId;
    private String eventType; // "DEPARTURE" or "ARRIVAL"
    private LocalDateTime eventTime;
    private Integer flightId;
    private String flightCode;
    private Integer productId;
    private Integer orderId;
    private String originCity;
    private String destinationCity;
    private Integer originAirportId;
    private Integer destinationAirportId;
    private Double transportTimeDays;
}


