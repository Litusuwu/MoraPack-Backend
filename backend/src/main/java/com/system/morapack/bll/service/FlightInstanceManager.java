package com.system.morapack.bll.service;

import com.system.morapack.schemas.AirportSchema;
import com.system.morapack.schemas.CitySchema;
import com.system.morapack.schemas.FlightInstanceSchema;
import com.system.morapack.schemas.FlightSchema;
import com.system.morapack.schemas.OrderSchema;
import com.system.morapack.schemas.ProductSchema;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Manages FlightInstanceSchema objects for multi-day simulations
 *
 * Responsibilities:
 * - Store all flight instances for the simulation window
 * - Track per-instance capacity usage
 * - Provide route-finding methods that work with specific flight instances
 * - Pre-fill capacity from existing product assignments (for re-runs)
 */
@Service
public class FlightInstanceManager {

    // All flight instances in the simulation window
    private List<FlightInstanceSchema> allInstances;

    // Quick lookup: originAirportId-destAirportId -> List of instances (sorted by departure time)
    private Map<String, List<FlightInstanceSchema>> routeLookup;

    // Quick lookup: FlightInstanceID -> FlightInstanceSchema
    private Map<String, FlightInstanceSchema> instanceById;

    // Simulation time window
    private LocalDateTime simulationStartTime;
    private LocalDateTime simulationEndTime;

    public FlightInstanceManager() {
        this.allInstances = new ArrayList<>();
        this.routeLookup = new HashMap<>();
        this.instanceById = new HashMap<>();
    }

    /**
     * Initialize with flight instances and existing assignments
     *
     * @param instances All flight instances for the simulation
     * @param existingAssignments Map of instance ID -> products already assigned
     * @param simStart Simulation start time
     * @param simEnd Simulation end time
     */
    public void initialize(
            List<FlightInstanceSchema> instances,
            Map<String, List<ProductSchema>> existingAssignments,
            LocalDateTime simStart,
            LocalDateTime simEnd) {

        this.allInstances = new ArrayList<>(instances);
        this.simulationStartTime = simStart;
        this.simulationEndTime = simEnd;

        System.out.println("===========================================");
        System.out.println("INITIALIZING FLIGHT INSTANCE MANAGER");
        System.out.println("Flight instances: " + instances.size());
        System.out.println("Simulation: " + simStart + " to " + simEnd);
        System.out.println("===========================================");

        // Build lookup maps
        buildLookupMaps();

        // Pre-fill capacity from existing assignments
        prefillCapacityFromExistingAssignments(existingAssignments);

        System.out.println("FlightInstanceManager initialized successfully");
        System.out.println("Routes available: " + routeLookup.size());
        System.out.println("===========================================\n");
    }

    /**
     * Build lookup maps for efficient route finding
     */
    private void buildLookupMaps() {
        routeLookup.clear();
        instanceById.clear();

        for (FlightInstanceSchema instance : allInstances) {
            // Build route key: "originId-destId"
            FlightSchema baseFlight = instance.getBaseFlight();
            String routeKey = baseFlight.getOriginAirportId() + "-" +
                            baseFlight.getDestinationAirportId();

            // Add to route lookup
            routeLookup.computeIfAbsent(routeKey, k -> new ArrayList<>())
                       .add(instance);

            // Add to ID lookup
            instanceById.put(instance.getInstanceId(), instance);
        }

        // Sort each route's instances by departure time
        for (List<FlightInstanceSchema> instances : routeLookup.values()) {
            instances.sort(Comparator.comparing(FlightInstanceSchema::getDepartureDateTime));
        }
    }

    /**
     * Pre-fill flight instance capacities from existing product assignments
     * This is critical for re-runs to maintain consistency
     */
    private void prefillCapacityFromExistingAssignments(
            Map<String, List<ProductSchema>> existingAssignments) {

        if (existingAssignments == null || existingAssignments.isEmpty()) {
            System.out.println("[PREFILL] No existing assignments to pre-fill");
            return;
        }

        int instancesUpdated = 0;
        int totalProductsLoaded = 0;

        for (Map.Entry<String, List<ProductSchema>> entry : existingAssignments.entrySet()) {
            String instanceId = entry.getKey();
            List<ProductSchema> products = entry.getValue();

            FlightInstanceSchema instance = instanceById.get(instanceId);
            if (instance != null) {
                // Update used capacity
                int existingCapacity = products.size();
                instance.setUsedCapacity(existingCapacity);

                instancesUpdated++;
                totalProductsLoaded += existingCapacity;

                System.out.println("[PREFILL] " + instanceId + ": " + existingCapacity + " products pre-assigned");
            } else {
                System.out.println("[WARNING] Flight instance " + instanceId + " not found in current simulation window");
            }
        }

        System.out.println("\n[PREFILL SUMMARY]");
        System.out.println("Flight instances with pre-assignments: " + instancesUpdated);
        System.out.println("Total products pre-assigned: " + totalProductsLoaded);
        System.out.println("===========================================\n");
    }

    /**
     * Find direct flight instances between two airports
     * Returns instances that depart after the specified earliestDeparture time
     *
     * @param originAirport Origin airport
     * @param destAirport Destination airport
     * @param earliestDeparture Earliest acceptable departure time (e.g., when product arrives at origin)
     * @return List of available flight instances (sorted by departure time)
     */
    public List<FlightInstanceSchema> findDirectFlightInstances(
            AirportSchema originAirport,
            AirportSchema destAirport,
            LocalDateTime earliestDeparture) {

        String routeKey = originAirport.getId() + "-" + destAirport.getId();
        List<FlightInstanceSchema> routeInstances = routeLookup.get(routeKey);

        if (routeInstances == null || routeInstances.isEmpty()) {
            return Collections.emptyList();
        }

        // Filter by earliest departure time
        List<FlightInstanceSchema> available = new ArrayList<>();
        for (FlightInstanceSchema instance : routeInstances) {
            if (!instance.getDepartureDateTime().isBefore(earliestDeparture)) {
                available.add(instance);
            }
        }

        return available;
    }

    /**
     * Find the next available flight instance (with capacity) for a route
     */
    public FlightInstanceSchema findNextAvailableInstance(
            AirportSchema originAirport,
            AirportSchema destAirport,
            LocalDateTime earliestDeparture,
            int requiredCapacity) {

        List<FlightInstanceSchema> instances = findDirectFlightInstances(
            originAirport,
            destAirport,
            earliestDeparture
        );

        // Find first instance with enough capacity
        for (FlightInstanceSchema instance : instances) {
            if (instance.hasCapacity(requiredCapacity)) {
                return instance;
            }
        }

        return null; // No available instance found
    }

    /**
     * Find all airports that have direct flights from origin
     */
    public List<AirportSchema> findConnectedAirports(AirportSchema originAirport) {
        Set<AirportSchema> connected = new HashSet<>();

        for (String routeKey : routeLookup.keySet()) {
            String[] parts = routeKey.split("-");
            int originId = Integer.parseInt(parts[0]);

            if (originId == originAirport.getId()) {
                int destId = Integer.parseInt(parts[1]);

                // Find the destination airport from instances
                List<FlightInstanceSchema> instances = routeLookup.get(routeKey);
                if (!instances.isEmpty()) {
                    connected.add(instances.get(0).getBaseFlight().getDestinationAirportSchema());
                }
            }
        }

        return new ArrayList<>(connected);
    }

    /**
     * Reserve capacity on a flight instance
     */
    public void reserveCapacity(FlightInstanceSchema instance, int quantity) {
        instance.reserveCapacity(quantity);
    }

    /**
     * Check if route exists between two airports (at any time)
     */
    public boolean hasRoute(AirportSchema origin, AirportSchema dest) {
        String routeKey = origin.getId() + "-" + dest.getId();
        return routeLookup.containsKey(routeKey);
    }

    /**
     * Get flight instance by ID
     */
    public FlightInstanceSchema getInstanceById(String instanceId) {
        return instanceById.get(instanceId);
    }

    /**
     * Get all instances for debugging/logging
     */
    public List<FlightInstanceSchema> getAllInstances() {
        return new ArrayList<>(allInstances);
    }

    /**
     * Print capacity summary for debugging
     */
    public void printCapacitySummary() {
        System.out.println("\n=== FLIGHT INSTANCE CAPACITY SUMMARY ===");

        Map<Integer, List<FlightInstanceSchema>> byBaseFlight = new HashMap<>();
        for (FlightInstanceSchema instance : allInstances) {
            byBaseFlight.computeIfAbsent(instance.getBaseFlightId(), k -> new ArrayList<>())
                       .add(instance);
        }

        for (Map.Entry<Integer, List<FlightInstanceSchema>> entry : byBaseFlight.entrySet()) {
            List<FlightInstanceSchema> instances = entry.getValue();
            if (!instances.isEmpty()) {
                FlightInstanceSchema first = instances.get(0);
                FlightSchema base = first.getBaseFlight();

                int totalCapacity = 0;
                int totalUsed = 0;

                for (FlightInstanceSchema inst : instances) {
                    totalCapacity += inst.getMaxCapacity();
                    totalUsed += inst.getUsedCapacity();
                }

                System.out.println(String.format("%s (%d instances): %d/%d used (%.1f%%)",
                    base.getCode(),
                    instances.size(),
                    totalUsed,
                    totalCapacity,
                    (totalUsed * 100.0 / Math.max(1, totalCapacity))
                ));
            }
        }

        System.out.println("=======================================\n");
    }
}
