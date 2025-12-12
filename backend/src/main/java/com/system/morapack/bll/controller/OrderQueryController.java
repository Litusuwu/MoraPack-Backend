package com.system.morapack.bll.controller;

import com.system.morapack.bll.dto.OrderQueryDTO;
import com.system.morapack.bll.dto.ProductWithOrderDTO;
import com.system.morapack.dao.morapack_psql.model.Order;
import com.system.morapack.dao.morapack_psql.model.Product;
import com.system.morapack.dao.morapack_psql.model.ProductFlight;
import com.system.morapack.dao.morapack_psql.service.OrderService;
import com.system.morapack.dao.morapack_psql.service.ProductService;
import com.system.morapack.dao.morapack_psql.service.ProductFlightService;
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
    private final ProductFlightService productFlightService;

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
            .deliveredAt(product.getOrder() != null ? product.getOrder().getDeliveryDate() : null)
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

    /**
     * Get all flight legs for a specific product (multi-hop routes)
     * Returns flights in sequence order
     */
    public Map<String, Object> getProductFlightLegs(Integer productId) {
        List<ProductFlight> productFlights = productFlightService.getFlightsForProduct(productId);

        List<Map<String, Object>> flightLegs = productFlights.stream()
            .map(pf -> {
                Map<String, Object> leg = new HashMap<>();
                leg.put("flightId", pf.getFlight().getId());
                leg.put("flightCode", pf.getFlight().getCode());
                leg.put("originAirportCode", pf.getFlight().getOriginAirport().getCodeIATA());
                leg.put("destinationAirportCode", pf.getFlight().getDestinationAirport().getCodeIATA());
                leg.put("sequenceOrder", pf.getSequenceOrder());
                leg.put("departureTime", pf.getFlight().getDepartureTime() != null
                    ? pf.getFlight().getDepartureTime().toString() : null);
                leg.put("arrivalTime", pf.getFlight().getArrivalTime() != null
                    ? pf.getFlight().getArrivalTime().toString() : null);
                return leg;
            })
            .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("productId", productId);
        response.put("flightLegs", flightLegs);
        response.put("totalLegs", flightLegs.size());

        return response;
    }

    /**
     * Get all flight legs for multiple products of an order
     * Returns a map of productId -> list of flight legs
     */
    public Map<String, Object> getOrderFlightLegs(Integer orderId) {
        // Get all products for this order
        List<Product> products = productService.fetchProducts(null).stream()
            .filter(p -> p.getOrder().getId().equals(orderId))
            .collect(Collectors.toList());

        if (products.isEmpty()) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("orderId", orderId);
            response.put("flightLegs", Collections.emptyList());
            response.put("totalLegs", 0);
            return response;
        }

        // Get product IDs
        List<Integer> productIds = products.stream()
            .map(Product::getId)
            .collect(Collectors.toList());

        // Get all flight legs for all products
        List<ProductFlight> allProductFlights = productFlightService.getFlightsForProducts(productIds);

        // Build unique flight legs (avoid duplicates if products share the same route)
        Set<String> seenFlights = new HashSet<>();
        List<Map<String, Object>> uniqueFlightLegs = new ArrayList<>();

        for (ProductFlight pf : allProductFlights) {
            String key = pf.getFlight().getId() + "-" + pf.getSequenceOrder();
            if (!seenFlights.contains(key)) {
                seenFlights.add(key);
                Map<String, Object> leg = new HashMap<>();
                leg.put("flightId", pf.getFlight().getId());
                leg.put("flightCode", pf.getFlight().getCode());
                leg.put("originAirportCode", pf.getFlight().getOriginAirport().getCodeIATA());
                leg.put("destinationAirportCode", pf.getFlight().getDestinationAirport().getCodeIATA());
                leg.put("sequenceOrder", pf.getSequenceOrder());
                leg.put("departureTime", pf.getFlight().getDepartureTime() != null
                    ? pf.getFlight().getDepartureTime().toString() : null);
                leg.put("arrivalTime", pf.getFlight().getArrivalTime() != null
                    ? pf.getFlight().getArrivalTime().toString() : null);
                uniqueFlightLegs.add(leg);
            }
        }

        // Sort by sequence order
        uniqueFlightLegs.sort((a, b) ->
            ((Integer) a.get("sequenceOrder")).compareTo((Integer) b.get("sequenceOrder")));

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("orderId", orderId);
        response.put("flightLegs", uniqueFlightLegs);
        response.put("totalLegs", uniqueFlightLegs.size());

        return response;
    }
}
