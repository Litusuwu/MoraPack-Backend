package com.system.morapack.bll.dto;

import com.system.morapack.schemas.PackageStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO for order query responses with product breakdown
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderQueryDTO {
    private Integer id;
    private String name;
    private PackageStatus status;
    private LocalDateTime creationDate;
    private LocalDateTime deliveryDate;
    private Double pickupTimeHours;
    private CityInfo origin;
    private CityInfo destination;
    private CustomerInfo customer;
    private Integer totalProducts;
    private Integer productsDelivered;
    private Integer productsInTransit;
    private Integer productsPending;
    private Integer productsArrived;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CityInfo {
        private Integer id;
        private String name;
        private String continent;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CustomerInfo {
        private Integer id;
        private String phone;
        private String fiscalAddress;
    }
}
