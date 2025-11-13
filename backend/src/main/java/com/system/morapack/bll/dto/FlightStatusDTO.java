package com.system.morapack.bll.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for flight status information (used in map display)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FlightStatusDTO {
    private Integer id;
    private String code;
    private AirportDTO originAirport;
    private AirportDTO destinationAirport;
    private Integer maxCapacity;
    private Integer usedCapacity;
    private Integer availableCapacity;
    private Double transportTimeDays;
    private Integer dailyFrequency;
    private Double utilizationPercentage;
    private Integer assignedProducts;
    private Integer assignedOrders;
    private Boolean isActive;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AirportDTO {
        private String codeIATA;
        private CityDTO city;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CityDTO {
        private Integer id;
        private String name;
        private String continent;
        private String latitude;
        private String longitude;
    }
}
