package com.system.morapack.bll.service;

import com.system.morapack.schemas.FlightInstanceSchema;
import com.system.morapack.schemas.FlightSchema;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service that expands FlightSchema templates into daily FlightInstanceSchema objects
 * for multi-day simulations.
 *
 * Example:
 * - Flight LIM-CUZ departs daily at 20:00, arrives 03:00 next day
 * - Simulation: Jan 2-9 (7 days)
 * - Result: 7 FlightInstanceSchema objects, one per day
 */
@Service
public class FlightExpansionService {

    /**
     * Expands flight templates into daily instances for the simulation window
     *
     * @param flightTemplates Base flight templates (from DB or files)
     * @param simulationStartTime Start of simulation window
     * @param simulationEndTime End of simulation window
     * @return List of flight instances covering all days in the window
     */
    public List<FlightInstanceSchema> expandFlightsForSimulation(
            List<FlightSchema> flightTemplates,
            LocalDateTime simulationStartTime,
            LocalDateTime simulationEndTime) {

        List<FlightInstanceSchema> instances = new ArrayList<>();

        System.out.println("========================================");
        System.out.println("EXPANDING FLIGHTS FOR SIMULATION");
        System.out.println("Window: " + simulationStartTime + " to " + simulationEndTime);
        System.out.println("Flight templates: " + flightTemplates.size());
        System.out.println("========================================");

        // Calculate number of days in simulation
        long totalDays = ChronoUnit.DAYS.between(
            simulationStartTime.toLocalDate(),
            simulationEndTime.toLocalDate()
        ) + 1; // Include end day

        System.out.println("Simulation spans " + totalDays + " days");

        // For each flight template, create instances for each day
        for (FlightSchema template : flightTemplates) {
            if (template.getDepartureTime() == null || template.getArrivalTime() == null) {
                System.out.println("WARNING: Flight " + template.getCode() +
                                 " has no schedule times, skipping expansion");
                continue;
            }

            List<FlightInstanceSchema> dailyInstances = expandSingleFlight(
                template,
                simulationStartTime,
                simulationEndTime,
                (int) totalDays
            );

            instances.addAll(dailyInstances);
        }

        System.out.println("Total flight instances created: " + instances.size());
        System.out.println("========================================\n");

        return instances;
    }

    /**
     * Expands a single flight template into daily instances
     */
    private List<FlightInstanceSchema> expandSingleFlight(
            FlightSchema template,
            LocalDateTime simulationStartTime,
            LocalDateTime simulationEndTime,
            int totalDays) {

        List<FlightInstanceSchema> instances = new ArrayList<>();
        LocalDate startDate = simulationStartTime.toLocalDate();

        for (int day = 0; day < totalDays; day++) {
            LocalDate currentDate = startDate.plusDays(day);

            // Create departure datetime by combining date + time
            LocalDateTime departureDateTime = LocalDateTime.of(
                currentDate,
                template.getDepartureTime()
            );

            // Only create instance if departure is within simulation window
            if (departureDateTime.isBefore(simulationStartTime) ||
                departureDateTime.isAfter(simulationEndTime)) {
                continue; // Skip this instance
            }

            // Calculate arrival datetime
            LocalDateTime arrivalDateTime = calculateArrivalDateTime(
                departureDateTime,
                template.getDepartureTime(),
                template.getArrivalTime(),
                template.getTransportTimeDays()
            );

            // Create flight instance
            FlightInstanceSchema instance = FlightInstanceSchema.builder()
                .baseFlightId(template.getId())
                .baseFlight(template)
                .departureDateTime(departureDateTime)
                .arrivalDateTime(arrivalDateTime)
                .instanceDay(day)
                .maxCapacity(template.getMaxCapacity())
                .usedCapacity(0) // Initially empty
                .build();

            // Generate unique instance ID
            instance.generateInstanceId();

            instances.add(instance);
        }

        return instances;
    }

    /**
     * Calculates arrival datetime handling flights that cross midnight
     *
     * Examples:
     * - Depart 20:00, Arrive 03:00 → next day at 03:00
     * - Depart 08:00, Arrive 12:00 → same day at 12:00
     */
    private LocalDateTime calculateArrivalDateTime(
            LocalDateTime departureDateTime,
            LocalTime departureTime,
            LocalTime arrivalTime,
            Double transportTimeDays) {

        // Method 1: Use scheduled times (preferred if available)
        if (departureTime != null && arrivalTime != null) {
            LocalDateTime arrivalSameDay = LocalDateTime.of(
                departureDateTime.toLocalDate(),
                arrivalTime
            );

            // If arrival time is before departure time, flight crosses midnight
            if (arrivalTime.isBefore(departureTime)) {
                return arrivalSameDay.plusDays(1);
            } else {
                return arrivalSameDay;
            }
        }

        // Method 2: Fallback to transport time (if schedule not available)
        if (transportTimeDays != null) {
            long transportMinutes = (long) (transportTimeDays * 24 * 60);
            return departureDateTime.plusMinutes(transportMinutes);
        }

        // Method 3: Last resort - assume same day
        System.out.println("WARNING: No schedule or transport time available for flight calculation");
        return departureDateTime.plusHours(1); // Default 1 hour flight
    }

    /**
     * Creates a lookup map for quick instance retrieval
     * Key: "originCode-destCode-YYYY-MM-DD-HH:mm"
     *
     * Example: "LIM-CUZ-2025-01-02-20:00"
     */
    public Map<String, FlightInstanceSchema> createInstanceLookupMap(
            List<FlightInstanceSchema> instances) {

        Map<String, FlightInstanceSchema> lookupMap = new HashMap<>();

        for (FlightInstanceSchema instance : instances) {
            String key = buildLookupKey(instance);
            lookupMap.put(key, instance);
        }

        System.out.println("Created flight instance lookup map with " + lookupMap.size() + " entries");
        return lookupMap;
    }

    /**
     * Builds a lookup key for a flight instance
     */
    private String buildLookupKey(FlightInstanceSchema instance) {
        FlightSchema baseFlight = instance.getBaseFlight();
        LocalDateTime departure = instance.getDepartureDateTime();

        return String.format("%s-%s-%s-%02d:%02d",
            baseFlight.getOriginAirportCode(),
            baseFlight.getDestinationAirportCode(),
            departure.toLocalDate(),
            departure.getHour(),
            departure.getMinute()
        );
    }

    /**
     * Filters instances that depart within a specific time window
     * Useful for incremental algorithm runs
     */
    public List<FlightInstanceSchema> filterByDepartureWindow(
            List<FlightInstanceSchema> instances,
            LocalDateTime windowStart,
            LocalDateTime windowEnd) {

        List<FlightInstanceSchema> filtered = new ArrayList<>();

        for (FlightInstanceSchema instance : instances) {
            LocalDateTime departure = instance.getDepartureDateTime();

            if (!departure.isBefore(windowStart) && !departure.isAfter(windowEnd)) {
                filtered.add(instance);
            }
        }

        return filtered;
    }

    /**
     * Groups instances by base flight ID
     * Useful for analyzing daily repetitions
     */
    public Map<Integer, List<FlightInstanceSchema>> groupByBaseFlight(
            List<FlightInstanceSchema> instances) {

        Map<Integer, List<FlightInstanceSchema>> grouped = new HashMap<>();

        for (FlightInstanceSchema instance : instances) {
            grouped.computeIfAbsent(instance.getBaseFlightId(), k -> new ArrayList<>())
                   .add(instance);
        }

        return grouped;
    }

    /**
     * Print expansion summary for debugging
     */
    public void printExpansionSummary(List<FlightInstanceSchema> instances) {
        System.out.println("\n=== FLIGHT EXPANSION SUMMARY ===");

        Map<Integer, List<FlightInstanceSchema>> grouped = groupByBaseFlight(instances);

        for (Map.Entry<Integer, List<FlightInstanceSchema>> entry : grouped.entrySet()) {
            List<FlightInstanceSchema> dailyInstances = entry.getValue();
            if (!dailyInstances.isEmpty()) {
                FlightInstanceSchema first = dailyInstances.get(0);
                System.out.println(
                    first.getBaseFlight().getCode() + ": " +
                    dailyInstances.size() + " daily instances created"
                );
            }
        }

        System.out.println("Total instances: " + instances.size());
        System.out.println("=================================\n");
    }
}
