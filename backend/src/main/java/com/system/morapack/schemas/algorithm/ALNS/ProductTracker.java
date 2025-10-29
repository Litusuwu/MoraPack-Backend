package com.system.morapack.schemas.algorithm.ALNS;

import com.system.morapack.schemas.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Fixes Issue #2: Proper Product-level tracking within Orders
 *
 * This class maintains the relationship between individual products and their assigned flights,
 * enabling proper tracking as specified in the problem statement:
 * "Los productos pueden llegar en distintos momentos siempre que todos lleguen dentro del plazo establecido"
 */
public class ProductTracker {

    // Maps product ID to its assigned flight route
    private Map<Integer, ArrayList<FlightSchema>> productToRouteMap;

    // Maps product ID to its parent order
    private Map<Integer, OrderSchema> productToOrderMap;

    // Maps order ID to its products
    private Map<Integer, ArrayList<ProductSchema>> orderToProductsMap;

    public ProductTracker() {
        this.productToRouteMap = new HashMap<>();
        this.productToOrderMap = new HashMap<>();
        this.orderToProductsMap = new HashMap<>();
    }

    /**
     * Initialize tracker with orders and their products
     */
    public void initializeFromOrders(ArrayList<OrderSchema> orders) {
        for (OrderSchema order : orders) {
            if (order.getProductSchemas() == null || order.getProductSchemas().isEmpty()) {
                // Create default product for orders without explicit products
                ProductSchema defaultProduct = new ProductSchema();
                defaultProduct.setId(order.getId() * 1000); // Derive product ID from order ID
                defaultProduct.setOrderId(order.getId()); // SET ORDER ID!
                defaultProduct.setStatus(Status.NOT_ASSIGNED);

                ArrayList<ProductSchema> products = new ArrayList<>();
                products.add(defaultProduct);
                order.setProductSchemas(products);

                orderToProductsMap.put(order.getId(), products);
                productToOrderMap.put(defaultProduct.getId(), order);
            } else {
                // Map existing products
                orderToProductsMap.put(order.getId(), order.getProductSchemas());
                for (ProductSchema product : order.getProductSchemas()) {
                    // Ensure each product has the orderId set
                    if (product.getOrderId() == null) {
                        product.setOrderId(order.getId());
                    }
                    productToOrderMap.put(product.getId(), order);
                }
            }
        }
    }

    /**
     * CRITICAL: Assign a route to a specific product and update its flight information
     * This properly implements product-level tracking
     *
     * @param product Product to assign
     * @param route Flight route for this product
     */
    public void assignProductToRoute(ProductSchema product, ArrayList<FlightSchema> route) {
        if (product == null || route == null) {
            return;
        }

        // Store the route for this product
        productToRouteMap.put(product.getId(), new ArrayList<>(route));

        // Update product's assigned flight information
        if (!route.isEmpty()) {
            StringBuilder flightInfo = new StringBuilder();
            for (int i = 0; i < route.size(); i++) {
                FlightSchema flight = route.get(i);
                flightInfo.append(flight.getId());
                if (i < route.size() - 1) {
                    flightInfo.append(" -> ");
                }
            }
            product.setAssignedFlight(flightInfo);
            product.setStatus(Status.ASSIGNED);
        } else {
            // Product already at destination
            product.setStatus(Status.DELIVERED);
        }
    }

    /**
     * Assign an entire order's products to a route
     * If order has multiple products (due to unitization), each gets the same route
     *
     * @param order Order to assign
     * @param route Route for all products in the order
     */
    public void assignOrderToRoute(OrderSchema order, ArrayList<FlightSchema> route) {
        if (order == null || order.getProductSchemas() == null) {
            return;
        }

        for (ProductSchema product : order.getProductSchemas()) {
            assignProductToRoute(product, route);
        }
    }

    /**
     * Get all products for a specific order
     */
    public ArrayList<ProductSchema> getProductsForOrder(OrderSchema order) {
        if (order == null) {
            return new ArrayList<>();
        }
        return orderToProductsMap.getOrDefault(order.getId(), new ArrayList<>());
    }

    /**
     * Get the route assigned to a specific product
     */
    public ArrayList<FlightSchema> getRouteForProduct(ProductSchema product) {
        if (product == null) {
            return null;
        }
        return productToRouteMap.get(product.getId());
    }

    /**
     * Get the parent order for a specific product
     */
    public OrderSchema getOrderForProduct(ProductSchema product) {
        if (product == null) {
            return null;
        }
        return productToOrderMap.get(product.getId());
    }

    /**
     * Check if a product has been assigned to a route
     */
    public boolean isProductAssigned(ProductSchema product) {
        if (product == null) {
            return false;
        }
        return productToRouteMap.containsKey(product.getId());
    }

    /**
     * Get statistics about product assignments
     */
    public ProductTrackingStats getStats() {
        ProductTrackingStats stats = new ProductTrackingStats();

        stats.totalProducts = productToOrderMap.size();
        stats.assignedProducts = productToRouteMap.size();
        stats.unassignedProducts = stats.totalProducts - stats.assignedProducts;
        stats.totalOrders = orderToProductsMap.size();

        // Count orders with all products assigned
        int ordersFullyAssigned = 0;
        for (Map.Entry<Integer, ArrayList<ProductSchema>> entry : orderToProductsMap.entrySet()) {
            boolean allAssigned = true;
            for (ProductSchema product : entry.getValue()) {
                if (!isProductAssigned(product)) {
                    allAssigned = false;
                    break;
                }
            }
            if (allAssigned) {
                ordersFullyAssigned++;
            }
        }
        stats.ordersFullyAssigned = ordersFullyAssigned;
        stats.ordersPartiallyAssigned = stats.totalOrders - ordersFullyAssigned;

        return stats;
    }

    /**
     * Update product status based on route position
     * This would be called during simulation to track real-time progress
     */
    public void updateProductStatus(ProductSchema product, int currentFlightIndex) {
        ArrayList<FlightSchema> route = getRouteForProduct(product);
        if (route == null || route.isEmpty()) {
            product.setStatus(Status.DELIVERED);
            return;
        }

        if (currentFlightIndex < 0) {
            product.setStatus(Status.ASSIGNED);
        } else if (currentFlightIndex >= route.size()) {
            product.setStatus(Status.DELIVERED);
        } else {
            product.setStatus(Status.ASSIGNED);
            // Could also update assignedFlight to show current flight
            FlightSchema currentFlight = route.get(currentFlightIndex);
            product.setAssignedFlight(new StringBuilder(String.valueOf(currentFlight.getId())));
        }
    }

    /**
     * Generate a solution map at the product level
     * Returns: Map<Product, List<Flight>>
     * This is the desired output format mentioned in CLAUDE.md
     */
    public Map<ProductSchema, ArrayList<FlightSchema>> getProductLevelSolution() {
        Map<ProductSchema, ArrayList<FlightSchema>> solution = new HashMap<>();

        for (Map.Entry<Integer, ArrayList<FlightSchema>> entry : productToRouteMap.entrySet()) {
            int productId = entry.getKey();
            ArrayList<FlightSchema> route = entry.getValue();

            // Find the product object
            for (ArrayList<ProductSchema> products : orderToProductsMap.values()) {
                for (ProductSchema product : products) {
                    if (product.getId() == productId) {
                        solution.put(product, route);
                        break;
                    }
                }
            }
        }

        return solution;
    }

    /**
     * Clear all tracking data
     */
    public void clear() {
        productToRouteMap.clear();
        productToOrderMap.clear();
        orderToProductsMap.clear();
    }

    /**
     * Print tracking summary for debugging
     */
    public void printTrackingSummary() {
        ProductTrackingStats stats = getStats();
        System.out.println("\n=== PRODUCT TRACKING SUMMARY ===");
        System.out.println("Total Products: " + stats.totalProducts);
        System.out.println("Assigned Products: " + stats.assignedProducts);
        System.out.println("Unassigned Products: " + stats.unassignedProducts);
        System.out.println("Total Orders: " + stats.totalOrders);
        System.out.println("Orders Fully Assigned: " + stats.ordersFullyAssigned);
        System.out.println("Orders Partially Assigned: " + stats.ordersPartiallyAssigned);
        System.out.println("Assignment Rate: " +
            String.format("%.2f%%", (stats.assignedProducts * 100.0 / stats.totalProducts)));
        System.out.println("================================\n");
    }

    /**
     * Inner class for tracking statistics
     */
    public static class ProductTrackingStats {
        public int totalProducts;
        public int assignedProducts;
        public int unassignedProducts;
        public int totalOrders;
        public int ordersFullyAssigned;
        public int ordersPartiallyAssigned;
    }
}
