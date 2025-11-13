package com.system.morapack.bll.controller;

import com.system.morapack.bll.dto.OrderQueryDTO;
import com.system.morapack.bll.dto.ProductWithOrderDTO;
import com.system.morapack.dao.morapack_psql.model.Order;
import com.system.morapack.dao.morapack_psql.model.Product;
import com.system.morapack.dao.morapack_psql.service.OrderService;
import com.system.morapack.dao.morapack_psql.service.ProductService;
import com.system.morapack.schemas.PackageStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Controller for order query operations
 * Handles business logic for querying orders and products
 */
@Service
@RequiredArgsConstructor
public class OrderQueryController {

    private final OrderService orderService;
    private final ProductService productService;

    /**
     * Get orders within a specific time window
     */
    public Map<String, Object> getOrdersInTimeWindow(LocalDateTime startTime, LocalDateTime endTime) {
        // Get all orders
        List<Order> allOrders = orderService.fetchOrders(null);

        // Filter by time window
        List<Order> filteredOrders = allOrders.stream()
            .filter(order -> {
                LocalDateTime creationDate = order.getCreationDate();
                return !creationDate.isBefore(startTime) && !creationDate.isAfter(endTime);
            })
            .collect(Collectors.toList());

        // Build OrderQueryDTO for each order
        List<OrderQueryDTO> orderDTOs = filteredOrders.stream()
            .map(this::buildOrderQueryDTO)
            .collect(Collectors.toList());

        // Calculate statistics
        Map<PackageStatus, Long> byStatus = filteredOrders.stream()
            .collect(Collectors.groupingBy(Order::getStatus, Collectors.counting()));

        Map<String, Object> statistics = new HashMap<>();
        statistics.put("totalOrders", orderDTOs.size());
        statistics.put("byStatus", byStatus);

        Map<String, Object> timeWindow = new HashMap<>();
        timeWindow.put("startTime", startTime);
        timeWindow.put("endTime", endTime);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("totalOrders", orderDTOs.size());
        response.put("orders", orderDTOs);
        response.put("timeWindow", timeWindow);
        response.put("statistics", statistics);

        return response;
    }

    /**
     * Get product splits for a specific order
     */
    public Map<String, Object> getProductSplitsForOrder(Integer orderId) {
        // Find order
        Order order;
        try {
            order = orderService.getOrder(orderId);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "Order not found: " + orderId);
            return error;
        }

        // Get all products for this order
        List<Product> products = productService.fetchProducts(null).stream()
            .filter(p -> p.getOrder().getId().equals(orderId))
            .collect(Collectors.toList());

        // Build ProductWithOrderDTO for each product
        List<ProductWithOrderDTO> productDTOs = products.stream()
            .map(this::buildProductWithOrderDTO)
            .collect(Collectors.toList());

        // Group by flight instance
        Map<String, Map<String, Object>> splits = products.stream()
            .filter(p -> p.getAssignedFlightInstance() != null && !p.getAssignedFlightInstance().isEmpty())
            .collect(Collectors.groupingBy(
                Product::getAssignedFlightInstance,
                Collectors.collectingAndThen(
                    Collectors.toList(),
                    list -> {
                        Map<String, Object> splitInfo = new HashMap<>();
                        splitInfo.put("count", list.size());
                        splitInfo.put("flightCode", extractFlightCode(list.get(0).getAssignedFlightInstance()));
                        return splitInfo;
                    }
                )
            ));

        // Calculate status breakdown
        Map<PackageStatus, Long> statusBreakdown = products.stream()
            .collect(Collectors.groupingBy(Product::getStatus, Collectors.counting()));

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("orderId", orderId);
        response.put("orderName", order.getName());
        response.put("totalProducts", products.size());
        response.put("products", productDTOs);
        response.put("splits", splits);
        response.put("statusBreakdown", statusBreakdown);

        return response;
    }

    // Helper methods

    private OrderQueryDTO buildOrderQueryDTO(Order order) {
        // Get all products for this order
        List<Product> products = productService.fetchProducts(null).stream()
            .filter(p -> p.getOrder().getId().equals(order.getId()))
            .collect(Collectors.toList());

        int totalProducts = products.size();
        int productsDelivered = (int) products.stream().filter(p -> p.getStatus() == PackageStatus.DELIVERED).count();
        int productsInTransit = (int) products.stream().filter(p -> p.getStatus() == PackageStatus.IN_TRANSIT).count();
        int productsPending = (int) products.stream().filter(p -> p.getStatus() == PackageStatus.PENDING).count();
        int productsArrived = (int) products.stream().filter(p -> p.getStatus() == PackageStatus.ARRIVED).count();

        return OrderQueryDTO.builder()
            .id(order.getId())
            .name(order.getName())
            .status(order.getStatus())
            .creationDate(order.getCreationDate())
            .deliveryDate(order.getDeliveryDate())
            .pickupTimeHours(order.getPickupTimeHours())
            .origin(OrderQueryDTO.CityInfo.builder()
                .id(order.getOrigin().getId())
                .name(order.getOrigin().getName())
                .continent(order.getOrigin().getContinent().toString())
                .build())
            .destination(OrderQueryDTO.CityInfo.builder()
                .id(order.getDestination().getId())
                .name(order.getDestination().getName())
                .continent(order.getDestination().getContinent().toString())
                .build())
            .customer(OrderQueryDTO.CustomerInfo.builder()
                .id(order.getCustomer().getId())
                .phone(order.getCustomer().getPhone())
                .fiscalAddress(order.getCustomer().getFiscalAddress())
                .build())
            .totalProducts(totalProducts)
            .productsDelivered(productsDelivered)
            .productsInTransit(productsInTransit)
            .productsPending(productsPending)
            .productsArrived(productsArrived)
            .build();
    }

    private ProductWithOrderDTO buildProductWithOrderDTO(Product product) {
        return ProductWithOrderDTO.builder()
            .id(product.getId())
            .status(product.getStatus())
            .assignedFlightInstance(product.getAssignedFlightInstance())
            .createdAt(product.getCreationDate())
            .order(ProductWithOrderDTO.OrderInfo.builder()
                .id(product.getOrder().getId())
                .name(product.getOrder().getName())
                .destination(product.getOrder().getDestination().getName())
                .customer(product.getOrder().getCustomer().getPhone())
                .build())
            .build();
    }

    private String extractFlightCode(String flightInstance) {
        if (flightInstance == null || flightInstance.isEmpty()) {
            return "";
        }

        // Format: "SPIM-SPZO-DAY-0-2000"
        // Extract "SPIM-SPZO"
        String[] parts = flightInstance.split("-");
        if (parts.length >= 2) {
            return parts[0] + "-" + parts[1];
        }

        return "";
    }
}
