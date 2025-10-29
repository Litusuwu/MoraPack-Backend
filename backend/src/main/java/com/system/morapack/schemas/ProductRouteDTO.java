package com.system.morapack.schemas;

import lombok.*;
import java.util.List;

/**
 * Simplified Product Route Data Transfer Object for API responses
 * Uses FlightDTO instead of FlightSchema to avoid circular references
 */
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ProductRouteDTO {
    private Integer productId;
    private Integer orderId;
    private String orderName;
    private List<FlightDTO> flights;
    private String originCity;
    private String destinationCity;
    private Integer flightCount;
}


