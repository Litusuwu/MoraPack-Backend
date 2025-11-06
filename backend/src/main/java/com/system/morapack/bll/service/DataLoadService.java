package com.system.morapack.bll.service;

import com.system.morapack.config.Constants;
import com.system.morapack.dao.morapack_psql.model.City;
import com.system.morapack.dao.morapack_psql.model.Customer;
import com.system.morapack.dao.morapack_psql.model.Order;
import com.system.morapack.dao.morapack_psql.service.CityService;
import com.system.morapack.dao.morapack_psql.service.CustomerService;
import com.system.morapack.dao.morapack_psql.service.OrderService;
import com.system.morapack.schemas.PackageStatus;
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
    private final CityService cityService;
    private final CustomerService customerService;

    // Cache for city lookups (airport code -> City entity)
    private Map<String, City> cityCache = new HashMap<>();
    // Cache for customer lookups (customerId -> Customer entity)
    private Map<String, Customer> customerCache = new HashMap<>();

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
            name.startsWith("_pedidos_") && name.endsWith("_"));

        if (orderFiles == null || orderFiles.length == 0) {
            result.success = false;
            result.errorMessage = "No order files found matching pattern _pedidos_{AIRPORT}_";
            return result;
        }

        System.out.println("Found " + orderFiles.length + " order files");

        // Load orders from each file
        List<Order> ordersToCreate = new ArrayList<>();

        for (File orderFile : orderFiles) {
            String fileName = orderFile.getName();
            String originAirportCode = fileName.replace("_pedidos_", "").replace("_", "");

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

        // Calculate delivery deadline based on origin/destination continents
        // For now, use simple rule: +2 days same continent, +3 days different continent
        // TODO: Implement proper continent detection
        LocalDateTime deliveryDeadline = orderDate.plusDays(3);

        // Get or create entities
        City originCity = getCityByAirportCode(originAirportCode);
        City destinationCity = getCityByAirportCode(destinationAirportCode);
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
     * Initialize city and customer caches from database
     */
    private void initializeCaches() {
        System.out.println("Initializing caches...");

        // Load all cities
        List<City> cities = cityService.fetchCities(null);
        for (City city : cities) {
            // Cache by airport code (if available)
            // TODO: Implement proper airport code mapping
            cityCache.put(city.getName().toUpperCase(), city);
        }

        System.out.println("City cache initialized: " + cityCache.size() + " cities");

        // Customer cache populated on-demand
        customerCache.clear();
    }

    /**
     * Get city by airport code (with fallback to city name)
     */
    private City getCityByAirportCode(String airportCode) {
        // Check cache first
        if (cityCache.containsKey(airportCode)) {
            return cityCache.get(airportCode);
        }

        // Try to find in database
        // TODO: Implement proper airport code to city mapping
        // For now, return first city as fallback
        List<City> cities = cityService.fetchCities(null);
        if (!cities.isEmpty()) {
            City city = cities.get(0);
            cityCache.put(airportCode, city);
            return city;
        }

        throw new IllegalStateException("No cities found in database. Please load city data first.");
    }

    /**
     * Get or create customer by ID
     */
    private Customer getOrCreateCustomer(String customerId) {
        if (customerCache.containsKey(customerId)) {
            return customerCache.get(customerId);
        }

        // Try to find existing customer
        List<Customer> allCustomers = customerService.fetchCustomers(null);

        // TODO: Implement proper customer matching by ID
        // Customer entity doesn't have a name field - it has phone, fiscalAddress, and person (User)
        // For now, just return first customer as fallback
        if (!allCustomers.isEmpty()) {
            Customer customer = allCustomers.get(0);
            customerCache.put(customerId, customer);
            System.out.println("Warning: Using fallback customer (ID: " + customer.getId() + ") for customerId: " + customerId);
            return customer;
        }

        throw new IllegalStateException("No customers found in database. Please load customer data first.");
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
        public int parseErrors = 0;
        public int fileErrors = 0;
        public LocalDateTime startTime;
        public LocalDateTime endTime;
        public long durationSeconds;
    }
}
