package com.system.morapack.schemas;

import lombok.*;
import java.time.LocalDateTime;
import java.util.ArrayList;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OrderSchema {
    private Integer id;
    private String name;
    private Integer originCityId;
    private String originCityName;
    private Integer destinationCityId;
    private String destinationCityName;
    private LocalDateTime deliveryDate;
    private PackageStatus status;
    private Double pickupTimeHours;
    private LocalDateTime creationDate;
    private LocalDateTime updatedAt;
    private Integer customerId;

    // Legacy fields for algorithm compatibility
    private CustomerSchema customerSchema;
    private CitySchema destinationCitySchema;
    private LocalDateTime orderDate;
    private LocalDateTime deliveryDeadline;
    private CitySchema currentLocation;
    private RouteSchema assignedRouteSchema;
    private double priority;
    private ArrayList<ProductSchema> productSchemas;

    // Quantity field for order splitting (used by ALNS algorithm)
    private Integer quantity;
}
