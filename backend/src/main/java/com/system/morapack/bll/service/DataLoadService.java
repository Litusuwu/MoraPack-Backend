package com.system.morapack.bll.service;

import com.system.morapack.config.Constants;
import com.system.morapack.dao.morapack_psql.model.Airport;
import com.system.morapack.dao.morapack_psql.model.City;
import com.system.morapack.dao.morapack_psql.model.Customer;
import com.system.morapack.dao.morapack_psql.model.Order;
import com.system.morapack.dao.morapack_psql.model.User;
import com.system.morapack.dao.morapack_psql.service.AirportService;
import com.system.morapack.dao.morapack_psql.service.CustomerService;
import com.system.morapack.dao.morapack_psql.service.OrderService;
import com.system.morapack.dao.morapack_psql.service.UserService;
import com.system.morapack.schemas.PackageStatus;
import com.system.morapack.schemas.TypeUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Service for loading order data from files into the database
 * Separates data loading from algorithm execution (Option A architecture)
 */
@Service
@RequiredArgsConstructor
public class DataLoadService {

    private final OrderService orderService;
    private final AirportService airportService;
    private final CustomerService customerService;
    private final UserService userService;

    // Cache for airport lookups (airport code -> Airport entity)
    private Map<String, Airport> airportCache = new HashMap<>();
    // Cache for customer lookups (customerId -> Customer entity)
    private Map<String, Customer> customerCache = new HashMap<>();

    // Counter for newly created customers during load operation
    private int customersCreated = 0;

    // Batch size for JDBC inserts (optimal range: 1000-5000)
    private static final int JDBC_BATCH_SIZE = 2000;

    /**
     * Load orders from _pedidos_{AIRPORT}_ files into database
     *
     * @param dataDirectoryPath Path to data directory containing order files
     * @param simulationStartTime Optional: only load orders after this time
     * @param simulationEndTime Optional: only load orders before this time
     * @return Statistics about loaded orders
     */
    @Transactional
    public LoadOrdersResult loadOrdersFromFiles(String dataDirectoryPath,
                                                LocalDateTime simulationStartTime,
                                                LocalDateTime simulationEndTime) {

        System.out.println("========================================");
        System.out.println("LOADING ORDERS FROM FILES TO DATABASE");
        System.out.println("Data directory: " + dataDirectoryPath);
        if (simulationStartTime != null) {
            System.out.println("Time window: " + simulationStartTime + " to " + simulationEndTime);
        } else {
            System.out.println("Loading ALL orders (no time filtering)");
        }
        System.out.println("========================================");

        LoadOrdersResult result = new LoadOrdersResult();
        result.startTime = LocalDateTime.now();

        // Initialize caches
        initializeCaches();

        // Find all order files
        File dataDir = new File(dataDirectoryPath);
        if (!dataDir.exists() || !dataDir.isDirectory()) {
            result.success = false;
            result.errorMessage = "Data directory not found: " + dataDirectoryPath;
            return result;
        }

        File[] orderFiles = dataDir.listFiles((dir, name) ->
            name.startsWith("_pedidos_") && name.endsWith("_.txt"));

        if (orderFiles == null || orderFiles.length == 0) {
            result.success = false;
            result.errorMessage = "No order files found matching pattern _pedidos_{AIRPORT}_.txt in directory: " + dataDirectoryPath;
            return result;
        }

        System.out.println("Found " + orderFiles.length + " order files");

        // PHASE 1: Parse files and collect unique customer IDs
        List<ParsedOrderData> parsedOrders = new ArrayList<>();
        Set<String> uniqueCustomerIds = new HashSet<>();

        for (File orderFile : orderFiles) {
            String fileName = orderFile.getName();
            // Extract airport code from filename: _pedidos_LATI_.txt -> LATI
            String originAirportCode = fileName
                .replace("_pedidos_", "")
                .replace("_.txt", "");

            System.out.println("\nProcessing file: " + fileName + " (origin: " + originAirportCode + ")");

            try (BufferedReader reader = new BufferedReader(new FileReader(orderFile))) {
                String line;
                int lineNumber = 0;

                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    try {
                        ParsedOrderData orderData = parseOrderLineToData(line, originAirportCode);

                        // Filter by time window if specified
                        if (simulationStartTime != null && simulationEndTime != null) {
                            if (orderData.orderDate.isBefore(simulationStartTime) ||
                                orderData.orderDate.isAfter(simulationEndTime)) {
                                result.ordersFiltered++;
                                continue;
                            }
                        }

                        parsedOrders.add(orderData);
                        uniqueCustomerIds.add(orderData.customerId);
                        result.ordersLoaded++;

                    } catch (Exception e) {
                        result.parseErrors++;
                        System.err.println("Error parsing line " + lineNumber + " in " + fileName + ": " + e.getMessage());
                    }
                }

                System.out.println("  Lines processed: " + lineNumber);

            } catch (Exception e) {
                result.fileErrors++;
                System.err.println("Error reading file " + fileName + ": " + e.getMessage());
            }
        }

        // PHASE 2: Batch create customers
        if (!parsedOrders.isEmpty()) {
            System.out.println("\n========================================");
            System.out.println("BATCH CREATING CUSTOMERS");
            System.out.println("Unique customer IDs found: " + uniqueCustomerIds.size());
            System.out.println("========================================");

            batchCreateCustomers(uniqueCustomerIds);
            result.customersCreated = customersCreated;
        }

        // PHASE 3: Create Order entities with customer references
        List<Order> ordersToCreate = new ArrayList<>();
        for (ParsedOrderData orderData : parsedOrders) {
            try {
                Order order = buildOrderEntity(orderData);
                ordersToCreate.add(order);
            } catch (Exception e) {
                result.parseErrors++;
                System.err.println("Error building order entity: " + e.getMessage());
            }
        }

        // PHASE 4: Batch insert orders
        if (!ordersToCreate.isEmpty()) {
            System.out.println("\n========================================");
            System.out.println("BATCH INSERTING " + ordersToCreate.size() + " ORDERS TO DATABASE");
            System.out.println("========================================");

            try {
                List<Order> createdOrders = orderService.bulkCreateOrders(ordersToCreate);
                result.ordersCreated = createdOrders.size();
                result.success = true;
            } catch (Exception e) {
                result.success = false;
                result.errorMessage = "Failed to insert orders: " + e.getMessage();
                e.printStackTrace();
            }
        } else {
            result.success = false;
            result.errorMessage = "No orders to insert";
        }

        result.endTime = LocalDateTime.now();
        result.durationSeconds = java.time.temporal.ChronoUnit.SECONDS.between(result.startTime, result.endTime);

        System.out.println("\n========================================");
        System.out.println("DATA LOAD COMPLETE");
        System.out.println("Orders loaded: " + result.ordersLoaded);
        System.out.println("Orders created: " + result.ordersCreated);
        System.out.println("Orders filtered: " + result.ordersFiltered);
        System.out.println("Customers created: " + result.customersCreated);
        System.out.println("Parse errors: " + result.parseErrors);
        System.out.println("Duration: " + result.durationSeconds + " seconds");
        System.out.println("========================================");

        return result;
    }

    /**
     * Parse a single order line from file to intermediate data structure
     * Format: id_pedido-aaaammdd-hh-mm-dest-###-IdClien
     * Example: 000000001-20250102-01-38-EBCI-006-0007729
     */
    private ParsedOrderData parseOrderLineToData(String line, String originAirportCode) {
        String[] parts = line.split("-");
        if (parts.length != 7) {
            throw new IllegalArgumentException("Invalid order format: " + line);
        }

        // Parse fields
        String orderId = parts[0];
        String dateStr = parts[1];  // aaaammdd
        int hour = Integer.parseInt(parts[2]);
        int minute = Integer.parseInt(parts[3]);
        String destinationAirportCode = parts[4];
        int productQuantity = Integer.parseInt(parts[5]);
        String customerId = parts[6];

        // Parse date (aaaammdd -> LocalDateTime)
        int year = Integer.parseInt(dateStr.substring(0, 4));
        int month = Integer.parseInt(dateStr.substring(4, 6));
        int day = Integer.parseInt(dateStr.substring(6, 8));
        LocalDateTime orderDate = LocalDateTime.of(year, month, day, hour, minute, 0);

        // Return intermediate data structure (no DB calls yet)
        return new ParsedOrderData(
            orderId,
            originAirportCode,
            destinationAirportCode,
            orderDate,
            productQuantity,
            customerId
        );
    }

    /**
     * Build Order entity from parsed data (after customers are created)
     */
    private Order buildOrderEntity(ParsedOrderData data) {
        // Get destination airport from order data
        Airport destinationAirport = getAirportByCode(data.destinationAirportCode);
        City destinationCity = destinationAirport.getCity();

        // FIX: Origin should be one of the THREE MAIN WAREHOUSES (Lima, Brussels, Baku)
        // NOT the file's airport - that's just where the order was registered
        // Assign based on destination continent for optimal routing
        City originCity = getMainWarehouseForContinent(destinationCity.getContinent());

        // Calculate delivery deadline based on origin/destination continents
        // Business rules: 2 days max (same continent), 3 days max (different continent)
        boolean sameContinent = originCity.getContinent() == destinationCity.getContinent();
        int deliveryDays = sameContinent ? 2 : 3;
        LocalDateTime deliveryDeadline = data.orderDate.plusDays(deliveryDays);

        // Get customer from cache (already created in batch)
        Customer customer = customerCache.get(data.customerId);
        if (customer == null) {
            throw new IllegalStateException("Customer not found in cache: " + data.customerId);
        }

        // Build Order entity
        return Order.builder()
            .name("Order-" + data.orderId + "-" + data.destinationAirportCode)
            .origin(originCity)  // Now uses main warehouse city
            .destination(destinationCity)
            .deliveryDate(deliveryDeadline)
            .status(PackageStatus.PENDING)
            .pickupTimeHours(2.0)  // 2 hour pickup window
            .creationDate(data.orderDate)
            .customer(customer)
            .build();
    }

    /**
     * Get the main warehouse city for a given destination continent
     * Main warehouses: Lima (America), Brussels (Europe), Baku (Asia)
     * These have unlimited stock capacity
     */
    private City getMainWarehouseForContinent(com.system.morapack.schemas.Continent continent) {
        String warehouseCityName;

        switch (continent) {
            case America:
                warehouseCityName = "Lima";
                break;
            case Europa:
                warehouseCityName = "Bruselas";  // Brussels in Spanish
                break;
            case Asia:
                warehouseCityName = "Baku";
                break;
            default:
                // Fallback to Lima for unknown continents
                warehouseCityName = "Lima";
                System.err.println("Warning: Unknown continent " + continent + ", defaulting to Lima warehouse");
        }

        // Try to find the city by name (case-insensitive)
        try {
            // Search through airport cache for the main warehouse city
            for (Airport airport : airportCache.values()) {
                City city = airport.getCity();
                if (city.getName().toLowerCase().contains(warehouseCityName.toLowerCase()) ||
                    warehouseCityName.toLowerCase().contains(city.getName().toLowerCase())) {
                    return city;
                }
            }

            throw new IllegalStateException("Main warehouse city not found: " + warehouseCityName);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to find main warehouse for continent " + continent + ": " + e.getMessage(), e);
        }
    }

    /**
     * Batch create customers for all unique customer IDs
     */
    private void batchCreateCustomers(Set<String> customerIds) {
        // Filter out customers that already exist in cache
        Set<String> newCustomerIds = new HashSet<>();
        for (String customerId : customerIds) {
            if (!customerCache.containsKey(customerId)) {
                newCustomerIds.add(customerId);
            }
        }

        if (newCustomerIds.isEmpty()) {
            System.out.println("All customers already exist in database");
            return;
        }

        System.out.println("Creating " + newCustomerIds.size() + " new customers");

        // Batch create Users
        List<User> usersToCreate = new ArrayList<>();
        for (String customerId : newCustomerIds) {
            User user = User.builder()
                .name("Customer")
                .lastName(customerId)
                .userType(TypeUser.CUSTOMER)
                .build();
            usersToCreate.add(user);
        }

        List<User> savedUsers = userService.bulkCreateUsers(usersToCreate);
        System.out.println("Created " + savedUsers.size() + " users");

        // Batch create Customers (linked to created Users)
        List<Customer> customersToCreate = new ArrayList<>();
        int index = 0;
        for (String customerId : newCustomerIds) {
            Customer customer = Customer.builder()
                .phone(customerId)
                .fiscalAddress("Address-" + customerId)
                .createdAt(LocalDateTime.now())
                .person(savedUsers.get(index))
                .build();
            customersToCreate.add(customer);
            index++;
        }

        List<Customer> savedCustomers = customerService.bulkCreateCustomers(customersToCreate);
        System.out.println("Created " + savedCustomers.size() + " customers");

        // Add to cache
        index = 0;
        for (String customerId : newCustomerIds) {
            customerCache.put(customerId, savedCustomers.get(index));
            index++;
        }

        customersCreated = savedCustomers.size();
    }

    /**
     * Initialize airport and customer caches from database
     */
    private void initializeCaches() {
        System.out.println("Initializing caches...");

        // Load all airports
        List<Airport> airports = airportService.fetchAirports(null);
        for (Airport airport : airports) {
            // Cache by IATA code
            if (airport.getCodeIATA() != null) {
                airportCache.put(airport.getCodeIATA().toUpperCase(), airport);
            }
        }

        System.out.println("Airport cache initialized: " + airportCache.size() + " airports");

        // Load existing customers
        List<Customer> existingCustomers = customerService.fetchCustomers(null);
        for (Customer customer : existingCustomers) {
            // Try to extract customer ID from phone or fiscal address
            // This is a best-effort attempt to link existing customers
            if (customer.getPhone() != null && customer.getPhone().matches("\\d{7}")) {
                customerCache.put(customer.getPhone(), customer);
            }
        }
        System.out.println("Customer cache initialized: " + customerCache.size() + " existing customers");

        // Reset counter
        customersCreated = 0;
    }

    /**
     * Get airport by IATA code
     */
    private Airport getAirportByCode(String airportCode) {
        // Normalize to uppercase
        String codeUpper = airportCode.toUpperCase();

        // Check cache first
        if (airportCache.containsKey(codeUpper)) {
            return airportCache.get(codeUpper);
        }

        // Try to find in database
        try {
            Airport airport = airportService.getByCode(codeUpper);
            airportCache.put(codeUpper, airport);
            return airport;
        } catch (Exception e) {
            throw new IllegalStateException("Airport not found with code: " + airportCode +
                ". Please ensure airport data is loaded in the database.", e);
        }
    }

    /**
     * Helper class to hold parsed order data before creating entities
     */
    private static class ParsedOrderData {
        final String orderId;
        final String originAirportCode;
        final String destinationAirportCode;
        final LocalDateTime orderDate;
        final int productQuantity;
        final String customerId;

        ParsedOrderData(String orderId, String originAirportCode, String destinationAirportCode,
                       LocalDateTime orderDate, int productQuantity, String customerId) {
            this.orderId = orderId;
            this.originAirportCode = originAirportCode;
            this.destinationAirportCode = destinationAirportCode;
            this.orderDate = orderDate;
            this.productQuantity = productQuantity;
            this.customerId = customerId;
        }
    }

    /**
     * Result object for load operation
     */
    public static class LoadOrdersResult {
        public boolean success;
        public String errorMessage;
        public int ordersLoaded = 0;
        public int ordersCreated = 0;
        public int ordersFiltered = 0;
        public int customersCreated = 0;
        public int parseErrors = 0;
        public int fileErrors = 0;
        public LocalDateTime startTime;
        public LocalDateTime endTime;
        public long durationSeconds;
    }
}
