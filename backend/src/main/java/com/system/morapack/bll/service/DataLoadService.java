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
import java.util.List;
import java.util.Map;

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

        // Load orders from each file
        List<Order> ordersToCreate = new ArrayList<>();

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
                        Order order = parseOrderLine(line, originAirportCode);

                        // Filter by time window if specified
                        if (simulationStartTime != null && simulationEndTime != null) {
                            if (order.getCreationDate().isBefore(simulationStartTime) ||
                                order.getCreationDate().isAfter(simulationEndTime)) {
                                result.ordersFiltered++;
                                continue;
                            }
                        }

                        ordersToCreate.add(order);
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

        // Batch insert orders
        if (!ordersToCreate.isEmpty()) {
            System.out.println("\n========================================");
            System.out.println("BATCH INSERTING " + ordersToCreate.size() + " ORDERS TO DATABASE");
            System.out.println("========================================");

            try {
                List<Order> createdOrders = orderService.bulkCreateOrders(ordersToCreate);
                result.ordersCreated = createdOrders.size();
                result.customersCreated = customersCreated;
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
     * Parse a single order line from file
     * Format: id_pedido-aaaammdd-hh-mm-dest-###-IdClien
     * Example: 000000001-20250102-01-38-EBCI-006-0007729
     */
    private Order parseOrderLine(String line, String originAirportCode) {
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

        // Get or create entities
        Airport originAirport = getAirportByCode(originAirportCode);
        Airport destinationAirport = getAirportByCode(destinationAirportCode);
        City originCity = originAirport.getCity();
        City destinationCity = destinationAirport.getCity();

        // Calculate delivery deadline based on origin/destination continents
        // Business rules: 2 days max (same continent), 3 days max (different continent)
        boolean sameContinent = originCity.getContinent() == destinationCity.getContinent();
        int deliveryDays = sameContinent ? 2 : 3;
        LocalDateTime deliveryDeadline = orderDate.plusDays(deliveryDays);
        Customer customer = getOrCreateCustomer(customerId);

        // Build Order entity
        return Order.builder()
            .name("Order-" + orderId + "-" + destinationAirportCode)
            .origin(originCity)
            .destination(destinationCity)
            .deliveryDate(deliveryDeadline)
            .status(PackageStatus.PENDING)
            .pickupTimeHours(2.0)  // 2 hour pickup window
            .creationDate(orderDate)
            .customer(customer)
            .build();
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
     * Get or create customer by ID
     * Creates new customers automatically with associated user accounts
     */
    private Customer getOrCreateCustomer(String customerId) {
        // Check cache first
        if (customerCache.containsKey(customerId)) {
            return customerCache.get(customerId);
        }

        // Customer doesn't exist, create new one
        System.out.println("Creating new customer with ID: " + customerId);

        try {
            // Create User (person) for the customer
            User user = User.builder()
                .name("Customer")
                .lastName(customerId)  // Use customer ID as last name for now
                .userType(TypeUser.CUSTOMER)
                .build();

            User savedUser = userService.createUser(user);

            // Create Customer
            Customer customer = Customer.builder()
                .phone(customerId)  // Use customer ID as phone for tracking
                .fiscalAddress("Address-" + customerId)  // Placeholder address
                .createdAt(LocalDateTime.now())
                .person(savedUser)
                .build();

            Customer savedCustomer = customerService.createCustomer(customer);

            // Cache the customer
            customerCache.put(customerId, savedCustomer);
            customersCreated++;

            System.out.println("Customer created successfully: " + customerId);
            return savedCustomer;

        } catch (Exception e) {
            System.err.println("Failed to create customer " + customerId + ": " + e.getMessage());
            throw new IllegalStateException("Failed to create customer with ID: " + customerId, e);
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
