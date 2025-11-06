package com.system.morapack.schemas.algorithm.Input;

import com.system.morapack.config.Constants;
import com.system.morapack.schemas.AirportSchema;
import com.system.morapack.schemas.FlightSchema;
import com.system.morapack.schemas.OrderSchema;

import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;

/**
 * File-based implementation of InputDataSource.
 *
 * Reads data from text files in the data/ directory:
 * - airportInfo.txt: Airport and warehouse information
 * - flights.txt: Available flights and routes
 * - _pedidos_{AIRPORT}_: Orders and products to be delivered (per airport)
 *
 * This is the CURRENT implementation used by the algorithm.
 */
public class FileInputDataSource implements InputDataSource {

    private InputAirports inputAirports;
    private InputData inputData;
    private InputProducts inputProducts;

    public FileInputDataSource() {
        // Initialize file readers with paths from Constants
        this.inputAirports = new InputAirports(Constants.AIRPORT_INFO_FILE_PATH);
    }

    @Override
    public ArrayList<AirportSchema> loadAirports() {
        System.out.println("[FILE] Loading airports from: " + Constants.AIRPORT_INFO_FILE_PATH);
        return inputAirports.readAirports();
    }

    @Override
    public ArrayList<FlightSchema> loadFlights(ArrayList<AirportSchema> airports) {
        System.out.println("[FILE] Loading flights from: " + Constants.FLIGHTS_FILE_PATH);
        this.inputData = new InputData(Constants.FLIGHTS_FILE_PATH, airports);
        return inputData.readFlights();
    }

    @Override
    @Deprecated
    public ArrayList<OrderSchema> loadOrders(ArrayList<AirportSchema> airports) {
        System.out.println("[FILE] Loading ALL orders (no time filtering) - DEPRECATED");
        System.out.println("[FILE] Consider using loadOrders(airports, startTime, endTime) instead");

        // Get data directory from Constants.PRODUCTS_FILE_PATH
        File productsFile = new File(Constants.PRODUCTS_FILE_PATH);
        String dataDirectory = productsFile.getParent();

        // Use legacy constructor (no time filtering)
        this.inputProducts = new InputProducts(Constants.PRODUCTS_FILE_PATH, airports);
        return inputProducts.readProducts();
    }

    @Override
    public ArrayList<OrderSchema> loadOrders(ArrayList<AirportSchema> airports,
                                            LocalDateTime simulationStartTime,
                                            LocalDateTime simulationEndTime) {
        System.out.println("[FILE] Loading orders with time window filtering");
        System.out.println("[FILE] Time window: " + simulationStartTime + " to " + simulationEndTime);

        // Get data directory from Constants.PRODUCTS_FILE_PATH
        File productsFile = new File(Constants.PRODUCTS_FILE_PATH);
        String dataDirectory = productsFile.getParent();

        System.out.println("[FILE] Data directory: " + dataDirectory);

        // Use new constructor with time window filtering
        this.inputProducts = new InputProducts(dataDirectory, airports,
                                               simulationStartTime, simulationEndTime);
        return inputProducts.readProducts();
    }

    @Override
    public String getSourceName() {
        return "FILE";
    }

    @Override
    public void initialize() {
        System.out.println("[FILE] File-based data source initialized");
    }

    @Override
    public void cleanup() {
        System.out.println("[FILE] File-based data source cleanup (no action needed)");
    }
}
