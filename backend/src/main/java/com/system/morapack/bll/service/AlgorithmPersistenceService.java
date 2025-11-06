package com.system.morapack.bll.service;

import com.system.morapack.dao.morapack_psql.model.Product;
import com.system.morapack.dao.morapack_psql.service.OrderService;
import com.system.morapack.dao.morapack_psql.service.ProductService;
import com.system.morapack.schemas.FlightSchema;
import com.system.morapack.schemas.OrderSchema;
import com.system.morapack.schemas.PackageStatus;
import com.system.morapack.schemas.ProductSchema;
import com.system.morapack.schemas.Status;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for persisting algorithm results to the database
 *
 * This service handles:
 * - Creating Product records from order splits
 * - Assigning flights to orders/products
 * - Updating order and product statuses
 * - Batch operations to minimize DB calls
 */
@Service
@RequiredArgsConstructor
public class AlgorithmPersistenceService {

    private final ProductService productService;
    private final OrderService orderService;

    /**
     * Represents a split portion of an order
     * When an order is too large for a single flight, it's split into multiple parts
     * Each split becomes a Product record in the database
     */
    public static class OrderSplit {
        private Integer orderId;
        private Integer quantity;  // Number of items in this split
        private List<FlightSchema> assignedFlights;
        private Status status;

        public OrderSplit(Integer orderId, Integer quantity, List<FlightSchema> flights) {
            this.orderId = orderId;
            this.quantity = quantity;
            this.assignedFlights = flights;
            this.status = Status.ASSIGNED;
        }

        public Integer getOrderId() { return orderId; }
        public Integer getQuantity() { return quantity; }
        public List<FlightSchema> getAssignedFlights() { return assignedFlights; }
        public Status getStatus() { return status; }
    }

    /**
     * Persist algorithm solution to database
     * Creates Product records for all order splits
     *
     * @param orderSplits List of order splits created during algorithm execution
     * @return Number of product records created
     */
    @Transactional
    public int persistSolution(List<OrderSplit> orderSplits) {
        System.out.println("========================================");
        System.out.println("PERSISTING ALGORITHM SOLUTION TO DATABASE");
        System.out.println("Total splits to persist: " + orderSplits.size());
        System.out.println("========================================");

        int productsCreated = 0;
        Map<Integer, List<OrderSplit>> splitsByOrder = groupSplitsByOrder(orderSplits);

        // For each order, create products for its splits
        for (Map.Entry<Integer, List<OrderSplit>> entry : splitsByOrder.entrySet()) {
            Integer orderId = entry.getKey();
            List<OrderSplit> splits = entry.getValue();

            System.out.println("Order " + orderId + ": Creating " + splits.size() + " product(s)");

            for (OrderSplit split : splits) {
                // Create Product record for this split
                Product product = new Product();
                product.setName("Product-Split-" + orderId + "-" + productsCreated);
                product.setStatus(split.getStatus());

                // Set order reference
                com.system.morapack.dao.morapack_psql.model.Order orderEntity =
                    new com.system.morapack.dao.morapack_psql.model.Order();
                orderEntity.setId(orderId);
                product.setOrder(orderEntity);

                // Build flight path string
                StringBuilder flightPath = new StringBuilder();
                for (FlightSchema flight : split.getAssignedFlights()) {
                    if (flightPath.length() > 0) {
                        flightPath.append(" -> ");
                    }
                    flightPath.append(flight.getOriginAirportSchema().getCodeIATA())
                              .append("-")
                              .append(flight.getDestinationAirportSchema().getCodeIATA());
                }
                product.setAssignedFlight(flightPath);

                // Save product
                productService.createProduct(product);
                productsCreated++;
            }

            // Update order status to IN_TRANSIT
            updateOrderStatus(orderId, PackageStatus.IN_TRANSIT);
        }

        System.out.println("========================================");
        System.out.println("PERSISTENCE COMPLETE");
        System.out.println("Products created: " + productsCreated);
        System.out.println("Orders updated: " + splitsByOrder.size());
        System.out.println("========================================");

        return productsCreated;
    }

    /**
     * Update order status in database
     */
    @Transactional
    public void updateOrderStatus(Integer orderId, PackageStatus status) {
        com.system.morapack.dao.morapack_psql.model.Order order =
            orderService.fetchOrders(null).stream()
                .filter(o -> o.getId().equals(orderId))
                .findFirst()
                .orElse(null);

        if (order != null) {
            order.setStatus(status);
            orderService.save(order);
        }
    }

    /**
     * Group splits by order ID for batch processing
     */
    private Map<Integer, List<OrderSplit>> groupSplitsByOrder(List<OrderSplit> splits) {
        Map<Integer, List<OrderSplit>> grouped = new HashMap<>();

        for (OrderSplit split : splits) {
            grouped.computeIfAbsent(split.getOrderId(), k -> new ArrayList<>()).add(split);
        }

        return grouped;
    }

    /**
     * Get statistics about the persisted solution
     */
    public Map<String, Integer> getSolutionStatistics(List<OrderSplit> splits) {
        Map<String, Integer> stats = new HashMap<>();

        stats.put("totalSplits", splits.size());
        stats.put("uniqueOrders", (int) splits.stream()
            .map(OrderSplit::getOrderId)
            .distinct()
            .count());
        stats.put("totalQuantity", splits.stream()
            .mapToInt(OrderSplit::getQuantity)
            .sum());

        return stats;
    }
}
