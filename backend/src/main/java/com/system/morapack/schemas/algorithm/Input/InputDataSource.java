package com.system.morapack.schemas.algorithm.Input;

import com.system.morapack.schemas.AirportSchema;
import com.system.morapack.schemas.FlightSchema;
import com.system.morapack.schemas.OrderSchema;

import java.time.LocalDateTime;
import java.util.ArrayList;

/**
 * Interface for abstracting data input sources for the ALNS algorithm.
 *
 * This allows the algorithm to work with different data sources without changing its core logic:
 * - FILE: Read from data/ directory (airportInfo.txt, flights.txt, products.txt)
 * - DATABASE: Read from PostgreSQL via Spring Data JPA repositories
 *
 * Implementations must provide methods to load airports, flights, and orders/products.
 */
public interface InputDataSource {

    /**
     * Loads all airports from the data source
     *
     * @return List of AirportSchema objects with complete information
     */
    ArrayList<AirportSchema> loadAirports();

    /**
     * Loads all flights from the data source
     *
     * @param airports List of airports to link flights to (for origin/destination references)
     * @return List of FlightSchema objects with complete information
     */
    ArrayList<FlightSchema> loadFlights(ArrayList<AirportSchema> airports);

    /**
     * Loads all orders/products from the data source
     *
     * @param airports List of airports to link products to (for location references)
     * @return List of OrderSchema objects with complete information (including ProductSchemas)
     * @deprecated Use loadOrders(airports, simulationStartTime, simulationEndTime) instead
     */
    @Deprecated
    ArrayList<OrderSchema> loadOrders(ArrayList<AirportSchema> airports);

    /**
     * Loads orders/products from the data source within a specific time window
     * This is the preferred method for daily and weekly scenarios
     *
     * @param airports List of airports to link products to (for location references)
     * @param simulationStartTime Start of simulation time window (inclusive)
     * @param simulationEndTime End of simulation time window (inclusive)
     * @return List of OrderSchema objects created within the time window
     */
    default ArrayList<OrderSchema> loadOrders(ArrayList<AirportSchema> airports,
                                              LocalDateTime simulationStartTime,
                                              LocalDateTime simulationEndTime) {
        // Default implementation: call legacy method (no filtering)
        // Implementations should override this to support time window filtering
        System.out.println("[WARNING] Time window filtering not implemented for " + getSourceName() + " data source");
        return loadOrders(airports);
    }

    /**
     * Returns the name of this data source for logging purposes
     *
     * @return String identifier (e.g., "FILE", "DATABASE")
     */
    String getSourceName();

    /**
     * Optional: Initialize any resources needed by this data source
     * (e.g., database connections, file handles)
     */
    default void initialize() {
        // Default: no initialization needed
    }

    /**
     * Optional: Clean up any resources used by this data source
     */
    default void cleanup() {
        // Default: no cleanup needed
    }
}
