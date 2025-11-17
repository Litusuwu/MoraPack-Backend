package com.system.morapack.bll.dto;

import com.system.morapack.schemas.PackageStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO for product with order information
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductWithOrderDTO {
    private Integer id;
    private PackageStatus status;
    private String assignedFlightInstance;
    private LocalDateTime createdAt;
    private OrderInfo order;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderInfo {
        private Integer id;
        private String name;
        private String destination;
        private String customer;
    }
}
