package com.system.morapack.bll.dto;

import com.system.morapack.schemas.PackageStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for orders assigned to a specific flight
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderOnFlightDTO {
    private Integer id;
    private String name;
    private PackageStatus status;
    private Integer productsOnFlight;
    private Integer totalProducts;
    private CityInfo origin;
    private CityInfo destination;
    private CustomerInfo customer;
    private String flightInstance;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CityInfo {
        private Integer id;
        private String name;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CustomerInfo {
        private Integer id;
        private String phone;
    }
}
