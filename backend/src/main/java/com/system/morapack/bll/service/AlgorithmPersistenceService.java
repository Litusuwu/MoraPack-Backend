package com.system.morapack.bll.service;

import com.system.morapack.dao.morapack_psql.model.Flight;
import com.system.morapack.dao.morapack_psql.model.Product;
import com.system.morapack.dao.morapack_psql.model.ProductFlight;
import com.system.morapack.dao.morapack_psql.service.FlightService;
import com.system.morapack.dao.morapack_psql.service.OrderService;
import com.system.morapack.dao.morapack_psql.service.ProductService;
import com.system.morapack.schemas.FlightInstanceSchema;
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
 * - Assigning flights to orders/products (via ProductFlight junction table)
 * - Supporting multi-hop flight routes with sequence ordering
 * - Updating order and product statuse
 * - Batch operations to minimize DB calls
 */
@Service
@RequiredArgsConstructor
public class AlgorithmPersistenceService {

    private final ProductService productService;
    private final OrderService orderService;
    private final FlightService flightService;

    /**
     * Represents a split portion of an order
     * When an order is too large for a single flight, it's split into multiple parts
     * Each split becomes a Product record in the database
     */
    public static class OrderSplit {
        private String orderName;  // Order identifier (e.g., "Order-000000001")
        private Integer quantity;  // Number of items in this split
        private List<FlightSchema> assignedFlights;
        private Status status;

        public OrderSplit(String orderName, Integer quantity, List<FlightSchema> flights) {
            this.orderName = orderName;
            this.quantity = quantity;
            this.assignedFlights = flights;
            this.status = Status.ASSIGNED;
        }

        public String getOrderName() { return orderName; }
        public Integer getQuantity() { return quantity; }
        public List<FlightSchema> getAssignedFlights() { return assignedFlights; }
        public Status getStatus() { return status; }
    }

    /**
     * NEW: Represents order split with FlightInstance support (for multi-day simulations)
     * This version tracks which SPECIFIC daily departure the products are assigned to
     */
    public static class OrderSplitWithInstances {
        private String orderName;
        private Integer quantity;
        private List<FlightInstanceSchema> assignedFlightInstances;
        private Status status;

        public OrderSplitWithInstances(String orderName, Integer quantity, List<FlightInstanceSchema> instances) {
            this.orderName = orderName;
            this.quantity = quantity;
            this.assignedFlightInstances = instances;
            this.status = Status.ASSIGNED;
        }

        public String getOrderName() { return orderName; }
        public Integer getQuantity() { return quantity; }
        public List<FlightInstanceSchema> getAssignedFlightInstances() { return assignedFlightInstances; }
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

        int ordersSkipped = 0;
        Map<String, List<OrderSplit>> splitsByOrder = groupSplitsByOrder(orderSplits);

        // OPTIMIZATION: Collect all products for batch insert
        List<Product> allProducts = new ArrayList<>();
        int productCounter = 0;

        // For each order, create products for its splits
        for (Map.Entry<String, List<OrderSplit>> entry : splitsByOrder.entrySet()) {
            String orderName = entry.getKey();
            List<OrderSplit> splits = entry.getValue();

            System.out.println("Order " + orderName + ": Preparing " + splits.size() + " product(s)");

            // Fetch the order entity once for all splits (performance optimization)
            // If order doesn't exist (e.g., loaded from FILE mode but not in DB), skip
            com.system.morapack.dao.morapack_psql.model.Order orderEntity;
            try {
                orderEntity = orderService.getOrderByName(orderName);
            } catch (jakarta.persistence.EntityNotFoundException e) {
                System.out.println("  WARNING: Order " + orderName + " not found in database. Skipping product creation.");
                System.out.println("  (This is normal when using FILE mode without pre-loading orders to DB)");
                ordersSkipped++;
                continue; // Skip to next order
            }

            for (OrderSplit split : splits) {
                // Create Product record for this split
                Product product = new Product();
                product.setName("Product-Split-" + orderName + "-" + productCounter);
                product.setStatus(mapStatusToPackageStatus(split.getStatus()));

                // Set default weight and volume (1 unit per product)
                // These represent standardized package units
                product.setWeight(1.0);  // 1 kg per unit
                product.setVolume(1.0);  // 1 m³ per unit

                // Set order reference (use the fetched entity)
                product.setOrder(orderEntity);

                // Build flight path string (legacy field, kept for backward compatibility)
                StringBuilder flightPath = new StringBuilder();
                for (FlightSchema flight : split.getAssignedFlights()) {
                    if (flightPath.length() > 0) {
                        flightPath.append(" -> ");
                    }
                    flightPath.append(flight.getOriginAirportSchema().getCodeIATA())
                              .append("-")
                              .append(flight.getDestinationAirportSchema().getCodeIATA());
                }
                product.setAssignedFlight(flightPath.toString());

                // Create ProductFlight records for multi-hop support
                List<ProductFlight> productFlights = new ArrayList<>();
                int sequenceOrder = 1;
                for (FlightSchema flightSchema : split.getAssignedFlights()) {
                    // Fetch the Flight entity from database
                    Flight flight = flightService.get(flightSchema.getId());

                    // Create ProductFlight junction record
                    ProductFlight productFlight = ProductFlight.builder()
                        .product(product)
                        .flight(flight)
                        .sequenceOrder(sequenceOrder++)
                        .build();

                    productFlights.add(productFlight);
                }

                // Set the product flights (will be cascaded on save)
                product.setProductFlights(productFlights);

                // Add to batch list instead of saving immediately
                allProducts.add(product);
                productCounter++;
            }

            // Update order status to IN_TRANSIT
            updateOrderStatus(orderName, PackageStatus.IN_TRANSIT);
        }

        // BATCH INSERT: Save all products in a single database transaction
        System.out.println("========================================");
        System.out.println("BATCH INSERTING " + allProducts.size() + " PRODUCTS");
        System.out.println("========================================");

        List<Product> savedProducts = productService.bulkCreateProducts(allProducts);
        int productsCreated = savedProducts.size();

        System.out.println("========================================");
        System.out.println("PERSISTENCE COMPLETE");
        System.out.println("Products created: " + productsCreated);
        System.out.println("Orders processed: " + (splitsByOrder.size() - ordersSkipped) + " / " + splitsByOrder.size());
        if (ordersSkipped > 0) {
            System.out.println("Orders skipped (not in DB): " + ordersSkipped);
            System.out.println("NOTE: Skipped orders were loaded from files but not persisted to the database.");
            System.out.println("To persist file-based orders, use DATABASE mode or import orders before running the algorithm.");
        }
        System.out.println("========================================");

        return productsCreated;
    }

    /**
     * Update order status in database
     */
    @Transactional
    public void updateOrderStatus(String orderName, PackageStatus status) {
        try {
            com.system.morapack.dao.morapack_psql.model.Order order = orderService.getOrderByName(orderName);
            order.setStatus(status);
            orderService.save(order);
        } catch (jakarta.persistence.EntityNotFoundException e) {
            // Order not in database (FILE mode) - skip status update
            System.out.println("  WARNING: Cannot update status for " + orderName + " (not in database)");
        }
    }

    /**
     * Group splits by order name for batch processing
     */
    private Map<String, List<OrderSplit>> groupSplitsByOrder(List<OrderSplit> splits) {
        Map<String, List<OrderSplit>> grouped = new HashMap<>();

        for (OrderSplit split : splits) {
            grouped.computeIfAbsent(split.getOrderName(), k -> new ArrayList<>()).add(split);
        }

        return grouped;
    }

    /**
     * Map Status enum to PackageStatus enum
     */
    private PackageStatus mapStatusToPackageStatus(Status status) {
        switch (status) {
            case ASSIGNED:
                return PackageStatus.IN_TRANSIT;
            case NOT_ASSIGNED:
                return PackageStatus.PENDING;
            case DELIVERED:
                return PackageStatus.DELIVERED;
            case NOT_DELIVERED:
                return PackageStatus.DELAYED;
            default:
                return PackageStatus.PENDING;
        }
    }

    /**
     * Get statistics about the persisted solution
     */
    public Map<String, Integer> getSolutionStatistics(List<OrderSplit> splits) {
        Map<String, Integer> stats = new HashMap<>();

        stats.put("totalSplits", splits.size());
        stats.put("uniqueOrders", (int) splits.stream()
            .map(OrderSplit::getOrderName)
            .distinct()
            .count());
        stats.put("totalQuantity", splits.stream()
            .mapToInt(OrderSplit::getQuantity)
            .sum());

        return stats;
    }

    // =====================================================================
    // NEW: FLIGHT INSTANCE PERSISTENCE (for multi-day simulations)
    // =====================================================================

    /**
     * NEW: Persist algorithm solution with FlightInstance tracking
     * This method saves which SPECIFIC daily departure each product is assigned to
     *
     * @param orderSplits List of order splits with flight instance assignments
     * @return Number of product records created
     */
    @Transactional
    public int persistSolutionWithInstances(List<OrderSplitWithInstances> orderSplits) {
        System.out.println("========================================");
        System.out.println("PERSISTING ALGORITHM SOLUTION WITH FLIGHT INSTANCES");
        System.out.println("Total splits to persist: " + orderSplits.size());
        System.out.println("========================================");

        int ordersSkipped = 0;
        Map<String, List<OrderSplitWithInstances>> splitsByOrder = groupSplitsByOrderWithInstances(orderSplits);

        // OPTIMIZATION: Collect all products for batch insert
        List<Product> allProducts = new ArrayList<>();
        int productCounter = 0;

        // For each order, create products for its splits
        for (Map.Entry<String, List<OrderSplitWithInstances>> entry : splitsByOrder.entrySet()) {
            String orderName = entry.getKey();
            List<OrderSplitWithInstances> splits = entry.getValue();

            System.out.println("Order " + orderName + ": Preparing " + splits.size() + " product(s)");

            // Fetch the order entity
            com.system.morapack.dao.morapack_psql.model.Order orderEntity;
            try {
                orderEntity = orderService.getOrderByName(orderName);
            } catch (jakarta.persistence.EntityNotFoundException e) {
                System.out.println("  WARNING: Order " + orderName + " not found in database. Skipping.");
                ordersSkipped++;
                continue;
            }

            for (OrderSplitWithInstances split : splits) {
                // Create Product record
                Product product = new Product();
                product.setName("Product-Split-" + orderName + "-" + productCounter);
                product.setStatus(mapStatusToPackageStatus(split.getStatus()));
                product.setWeight(1.0);
                product.setVolume(1.0);
                product.setOrder(orderEntity);

                // Build flight path string (legacy field)
                StringBuilder flightPath = new StringBuilder();
                for (FlightInstanceSchema instance : split.getAssignedFlightInstances()) {
                    if (flightPath.length() > 0) {
                        flightPath.append(" -> ");
                    }
                    FlightSchema baseFlight = instance.getBaseFlight();
                    flightPath.append(baseFlight.getOriginAirportSchema().getCodeIATA())
                              .append("-")
                              .append(baseFlight.getDestinationAirportSchema().getCodeIATA());
                }
                product.setAssignedFlight(flightPath.toString());

                // CRITICAL: Save the first flight instance ID (for re-run support)
                if (!split.getAssignedFlightInstances().isEmpty()) {
                    FlightInstanceSchema firstInstance = split.getAssignedFlightInstances().get(0);
                    product.setAssignedFlightInstance(firstInstance.getInstanceId());

                    System.out.println("  Product assigned to instance: " + firstInstance.getInstanceId());
                }

                // Create ProductFlight records
                List<ProductFlight> productFlights = new ArrayList<>();
                int sequenceOrder = 1;
                for (FlightInstanceSchema instanceSchema : split.getAssignedFlightInstances()) {
                    // Get base flight entity
                    Flight flight = flightService.get(instanceSchema.getBaseFlightId());

                    // Create ProductFlight junction record
                    ProductFlight productFlight = ProductFlight.builder()
                        .product(product)
                        .flight(flight)
                        .sequenceOrder(sequenceOrder++)
                        .build();

                    productFlights.add(productFlight);
                }

                product.setProductFlights(productFlights);
                allProducts.add(product);
                productCounter++;
            }

            // Update order status
            updateOrderStatus(orderName, PackageStatus.IN_TRANSIT);
        }

        // BATCH INSERT
        System.out.println("========================================");
        System.out.println("BATCH INSERTING " + allProducts.size() + " PRODUCTS");
        System.out.println("========================================");

        List<Product> savedProducts = productService.bulkCreateProducts(allProducts);
        int productsCreated = savedProducts.size();

        System.out.println("========================================");
        System.out.println("PERSISTENCE WITH FLIGHT INSTANCES COMPLETE");
        System.out.println("Products created: " + productsCreated);
        System.out.println("Orders processed: " + (splitsByOrder.size() - ordersSkipped));
        System.out.println("========================================");

        return productsCreated;
    }

    /**
     * Group splits by order name (for OrderSplitWithInstances)
     */
    private Map<String, List<OrderSplitWithInstances>> groupSplitsByOrderWithInstances(
            List<OrderSplitWithInstances> splits) {

        Map<String, List<OrderSplitWithInstances>> grouped = new HashMap<>();

        for (OrderSplitWithInstances split : splits) {
            grouped.computeIfAbsent(split.getOrderName(), k -> new ArrayList<>()).add(split);
        }

        return grouped;
    }
}
