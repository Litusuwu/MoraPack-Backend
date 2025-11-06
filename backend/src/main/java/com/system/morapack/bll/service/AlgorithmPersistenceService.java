package com.system.morapack.bll.service;

import com.system.morapack.dao.morapack_psql.model.Flight;
import com.system.morapack.dao.morapack_psql.model.Product;
import com.system.morapack.dao.morapack_psql.repository.FlightRepository;
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
import java.util.Optional;

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
    private final FlightRepository flightRepository;

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

        // For each order, create products for its splits and assign flight
        for (Map.Entry<Integer, List<OrderSplit>> entry : splitsByOrder.entrySet()) {
            Integer orderId = entry.getKey();
            List<OrderSplit> splits = entry.getValue();

            System.out.println("Order " + orderId + ": Creating " + splits.size() + " product(s)");

            // Get the flight assignment (use first split's first flight)
            Flight assignedFlight = null;
            if (!splits.isEmpty() && splits.get(0).getAssignedFlights() != null && !splits.get(0).getAssignedFlights().isEmpty()) {
                String flightCode = splits.get(0).getAssignedFlights().get(0).getCode();
                Optional<Flight> flightOpt = flightRepository.findByCode(flightCode);

                if (flightOpt.isPresent()) {
                    assignedFlight = flightOpt.get();
                    System.out.println("  -> Assigned to flight: " + flightCode);
                } else {
                    System.err.println("  -> Warning: Flight not found with code: " + flightCode);
                }
            }

            for (OrderSplit split : splits) {
                // Create Product record for this split
                Product product = new Product();
                product.setName("Product-Split-" + orderId + "-" + productsCreated);

                // Set default weight and volume (placeholder values)
                // TODO: Get actual weight/volume from order or configuration
                product.setWeight(1.0);
                product.setVolume(1.0);

                // Set order reference
                com.system.morapack.dao.morapack_psql.model.Order orderEntity =
                    new com.system.morapack.dao.morapack_psql.model.Order();
                orderEntity.setId(orderId);
                product.setOrder(orderEntity);

                // Save product
                productService.createProduct(product);
                productsCreated++;
            }

            // Update order status and assign flight
            updateOrderStatusAndFlight(orderId, PackageStatus.IN_TRANSIT, assignedFlight);
        }

        System.out.println("========================================");
        System.out.println("PERSISTENCE COMPLETE");
        System.out.println("Products created: " + productsCreated);
        System.out.println("Orders updated: " + splitsByOrder.size());
        System.out.println("========================================");

        return productsCreated;
    }

    /**
     * Update order status and assign flight in database
     */
    @Transactional
    public void updateOrderStatusAndFlight(Integer orderId, PackageStatus status, Flight assignedFlight) {
        try {
            // Get the order entity
            com.system.morapack.dao.morapack_psql.model.Order order = orderService.get(orderId);

            // Update status
            order.setStatus(status);

            // Assign flight if provided
            if (assignedFlight != null) {
                order.setAssignedFlight(assignedFlight);
            }

            // Save the updated order
            orderService.update(orderId, order);

        } catch (Exception e) {
            System.err.println("Warning: Failed to update order " + orderId + ": " + e.getMessage());
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
