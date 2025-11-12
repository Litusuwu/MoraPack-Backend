package com.system.morapack.schemas.algorithm.Input;

import com.system.morapack.schemas.*;
import com.system.morapack.schemas.AirportSchema;
import com.system.morapack.schemas.OrderSchema;
import com.system.morapack.config.Constants;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class InputProducts {
    private ArrayList<OrderSchema> orderSchemas;
    private final String dataDirectoryPath;
    private ArrayList<AirportSchema> airportSchemas;
    private Map<String, AirportSchema> airportMap;
    private Random random;
    private int productIdCounter = 1;
    private int orderIdCounter = 1;

    // NEW: Simulation time window for filtering orders
    private LocalDateTime simulationStartTime;
    private LocalDateTime simulationEndTime;

    /**
     * Constructor for reading from data directory with simulation time window
     */
    public InputProducts(String dataDirectoryPath, ArrayList<AirportSchema> airportSchemas,
                        LocalDateTime simulationStartTime, LocalDateTime simulationEndTime) {
        this.dataDirectoryPath = dataDirectoryPath;
        this.orderSchemas = new ArrayList<>();
        this.airportSchemas = airportSchemas;
        this.random = new Random();
        this.simulationStartTime = simulationStartTime;
        this.simulationEndTime = simulationEndTime;
        createAirportMap();
    }

    /**
     * Legacy constructor (uses all orders, no time filtering)
     */
    @Deprecated
    public InputProducts(String filePath, ArrayList<AirportSchema> airportSchemas) {
        this.dataDirectoryPath = new File(filePath).getParent();
        this.orderSchemas = new ArrayList<>();
        this.airportSchemas = airportSchemas;
        this.random = new Random();
        this.simulationStartTime = null; // No filtering
        this.simulationEndTime = null;
        createAirportMap();
    }

    private void createAirportMap() {
        this.airportMap = new HashMap<>();
        for (AirportSchema airportSchema : airportSchemas) {
            airportMap.put(airportSchema.getCodeIATA(), airportSchema);
        }
    }

    /**
     * Read all order files from the data directory
     * Files follow pattern: _pedidos_{AIRPORT_CODE}_
     * Format: id_pedido-aaaammdd-hh-mm-dest-###-IdClien
     */
    public ArrayList<OrderSchema> readProducts() {
        File dataDir = new File(dataDirectoryPath);

        if (!dataDir.exists() || !dataDir.isDirectory()) {
            System.err.println("ERROR: Data directory not found: " + dataDirectoryPath);
            return orderSchemas;
        }

        // Find all files matching _pedidos_{AIRPORT}_
        File[] orderFiles = dataDir.listFiles((dir, name) ->
            name.startsWith("_pedidos_") && name.endsWith("_"));

        if (orderFiles == null || orderFiles.length == 0) {
            System.err.println("WARNING: No order files found in " + dataDirectoryPath);
            System.err.println("Looking for files matching pattern: _pedidos_{AIRPORT}_");
            return orderSchemas;
        }

        System.out.println("========================================");
        System.out.println("LOADING ORDERS FROM DATA DIRECTORY");
        System.out.println("Directory: " + dataDirectoryPath);
        System.out.println("Found " + orderFiles.length + " order files");
        if (simulationStartTime != null && simulationEndTime != null) {
            System.out.println("Time window: " + simulationStartTime + " to " + simulationEndTime);
        } else {
            System.out.println("Time window: ALL ORDERS (no filtering)");
        }
        System.out.println("========================================");

        int totalLinesRead = 0;
        int ordersLoaded = 0;
        int ordersFiltered = 0;

        // Read each order file
        for (File orderFile : orderFiles) {
            // Extract origin airport code from filename
            // Example: _pedidos_LDZA_ -> LDZA
            String fileName = orderFile.getName();
            String originAirportCode = fileName.replace("_pedidos_", "").replace("_", "");

            AirportSchema originAirport = airportMap.get(originAirportCode);
            if (originAirport == null) {
                System.err.println("WARNING: Unknown origin airport code: " + originAirportCode + " in file: " + fileName);
                continue;
            }

            System.out.println("Reading orders from: " + fileName + " (origin: " + originAirportCode + ")");

            try (BufferedReader reader = new BufferedReader(new FileReader(orderFile))) {
                String line;
                int linesInFile = 0;
                int ordersInFile = 0;

                while ((line = reader.readLine()) != null) {
                    totalLinesRead++;
                    linesInFile++;

                    // Skip empty lines
                    if (line.trim().isEmpty()) {
                        continue;
                    }

                    // Parse order data
                    // Format: id_pedido-aaaammdd-hh-mm-dest-###-IdClien
                    // Example: 000000001-20250102-01-38-EBCI-006-0007729
                    String[] parts = line.trim().split("-");

                    if (parts.length != 7) {
                        System.err.println("WARNING: Invalid line format (expected 7 parts, got " + parts.length + "): " + line);
                        continue;
                    }

                    try {
                        String orderId = parts[0];
                        String dateStr = parts[1];          // aaaammdd (e.g., 20250102)
                        int hour = Integer.parseInt(parts[2]);
                        int minute = Integer.parseInt(parts[3]);
                        String destinationAirportCode = parts[4];
                        int productQuantity = Integer.parseInt(parts[5]);
                        String customerId = parts[6];

                        // Parse order creation date and time
                        // Format: aaaammdd -> yyyy-MM-dd
                        int year = Integer.parseInt(dateStr.substring(0, 4));
                        int month = Integer.parseInt(dateStr.substring(4, 6));
                        int day = Integer.parseInt(dateStr.substring(6, 8));

                        LocalDateTime orderDate = LocalDateTime.of(year, month, day, hour, minute, 0);

                        // FILTER: Skip orders outside simulation time window
                        if (simulationStartTime != null && simulationEndTime != null) {
                            if (orderDate.isBefore(simulationStartTime) || orderDate.isAfter(simulationEndTime)) {
                                ordersFiltered++;
                                continue; // Skip this order
                            }
                        }

                        // Find destination airport
                        AirportSchema destinationAirport = airportMap.get(destinationAirportCode);
                        if (destinationAirport == null) {
                            System.err.println("WARNING: Unknown destination airport: " + destinationAirportCode);
                            continue;
                        }

                        // Calculate delivery deadline based on route type
                        CitySchema originCity = originAirport.getCitySchema();
                        CitySchema destinationCity = destinationAirport.getCitySchema();
                        boolean sameContinentRoute = originCity.getContinent() == destinationCity.getContinent();

                        // MoraPack delivery promise: 2 days (same continent), 3 days (different continent)
                        int deliveryDays = sameContinentRoute ? 2 : 3;
                        LocalDateTime deliveryDeadline = orderDate.plusDays(deliveryDays);

                        // Create customer
                        CustomerSchema customer = new CustomerSchema();
                        customer.setId(Integer.parseInt(customerId));
                        customer.setName("Customer " + customerId);
                        customer.setEmail("customer" + customerId + "@morapack.com");
                        customer.setDeliveryCitySchema(destinationCity);

                        // Create order (WITHOUT products - products will be created at end of algorithm)
                        OrderSchema order = new OrderSchema();
                        order.setId(orderIdCounter++);
                        order.setName("Order-" + orderId);
                        order.setCustomerSchema(customer);
                        order.setCurrentLocation(originCity);
                        order.setDestinationCitySchema(destinationCity);
                        order.setOrderDate(orderDate);
                        order.setDeliveryDeadline(deliveryDeadline);
                        order.setStatus(PackageStatus.PENDING);

                        // Calculate priority based on time window
                        double priorityValue = calculatePriority(orderDate, deliveryDeadline);
                        order.setPriority(priorityValue);

                        // OPTIMIZATION: Don't create ProductSchema objects here
                        // Just store the product quantity - products will be created when order is split
                        // This avoids creating 45 product records for an order with 45 items
                        // Instead, we create products only when the order is split during algorithm execution
                        order.setProductSchemas(new ArrayList<>()); // Empty list for now

                        // Store quantity in order for later use (if OrderSchema doesn't have this field, algorithm will track it)
                        // The algorithm will split orders as needed and create Product records at the end

                        orderSchemas.add(order);
                        ordersInFile++;
                        ordersLoaded++;

                    } catch (Exception e) {
                        System.err.println("ERROR parsing line: " + line);
                        System.err.println("Error: " + e.getMessage());
                    }
                }

                System.out.println("  → Processed " + linesInFile + " lines, loaded " + ordersInFile + " orders");

            } catch (IOException e) {
                System.err.println("ERROR reading file " + orderFile.getName() + ": " + e.getMessage());
                e.printStackTrace();
            }
        }

        System.out.println("========================================");
        System.out.println("ORDER LOADING SUMMARY");
        System.out.println("Total lines read: " + totalLinesRead);
        System.out.println("Orders loaded: " + ordersLoaded);
        System.out.println("Orders filtered (outside time window): " + ordersFiltered);
        System.out.println("NOTE: Products will be created at END of algorithm (when orders are split)");
        System.out.println("========================================");

        return orderSchemas;
    }

    /**
     * Calculate priority based on delivery time window
     * Shorter time windows get higher priority
     */
    private double calculatePriority(LocalDateTime orderDate, LocalDateTime deliveryDeadline) {
        long hours = ChronoUnit.HOURS.between(orderDate, deliveryDeadline);

        // Normalize priority: shorter delivery windows get higher priority (1.0 is highest)
        if (hours <= 24) {
            return 1.0; // Highest priority for 1-day delivery
        } else if (hours <= 48) {
            return 0.9; // Very high priority for 2-day delivery
        } else if (hours <= 72) {
            return 0.8; // High priority for 3-day delivery
        } else if (hours <= 96) {
            return 0.6; // Medium priority for 4-day delivery
        } else {
            return 0.4; // Lower priority for longer deliveries
        }
    }
}
