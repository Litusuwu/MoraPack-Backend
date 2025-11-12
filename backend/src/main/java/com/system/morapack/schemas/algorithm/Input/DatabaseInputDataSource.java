package com.system.morapack.schemas.algorithm.Input;

import com.system.morapack.bll.service.FlightExpansionService;
import com.system.morapack.dao.morapack_psql.model.Airport;
import com.system.morapack.dao.morapack_psql.model.City;
import com.system.morapack.dao.morapack_psql.model.Customer;
import com.system.morapack.dao.morapack_psql.model.Flight;
import com.system.morapack.dao.morapack_psql.model.Order;
import com.system.morapack.dao.morapack_psql.model.Product;
import com.system.morapack.dao.morapack_psql.service.AirportService;
import com.system.morapack.dao.morapack_psql.service.FlightService;
import com.system.morapack.dao.morapack_psql.service.OrderService;
import com.system.morapack.dao.morapack_psql.service.ProductService;
import com.system.morapack.schemas.AirportSchema;
import com.system.morapack.schemas.CitySchema;
import com.system.morapack.schemas.CustomerSchema;
import com.system.morapack.schemas.FlightInstanceSchema;
import com.system.morapack.schemas.FlightSchema;
import com.system.morapack.schemas.OrderSchema;
import com.system.morapack.schemas.ProductSchema;
import com.system.morapack.schemas.Status;
import com.system.morapack.schemas.WarehouseSchema;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Database-based implementation of InputDataSource.
 *
 * Reads data from PostgreSQL database via Spring Service layer:
 * - AirportService: Airports and warehouse information
 * - FlightService: Available flights and routes
 * - OrderService & ProductService: Orders and products to be delivered
 *
 * This implementation converts JPA entities to Schema objects used by the algorithm.
 *
 * NOTE: This class is a Spring Component and requires a running Spring context.
 * Use DataSourceFactory to create instances.
 */
@Component
public class DatabaseInputDataSource implements InputDataSource {

    @Autowired
    private AirportService airportService;

    @Autowired
    private FlightService flightService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private ProductService productService;

    @Autowired
    private FlightExpansionService flightExpansionService;

    // Cache para evitar conversiones repetidas
    private Map<Integer, AirportSchema> airportSchemaCache;
    private Map<Integer, CitySchema> citySchemaCache;

    public DatabaseInputDataSource() {
        this.airportSchemaCache = new HashMap<>();
        this.citySchemaCache = new HashMap<>();
    }

    @Override
    public ArrayList<AirportSchema> loadAirports() {
        System.out.println("[DATABASE] Loading airports from PostgreSQL via AirportService...");

        List<Airport> airports = airportService.fetchAirports(null); // null = fetch all
        ArrayList<AirportSchema> airportSchemas = new ArrayList<>();

        for (Airport airport : airports) {
            AirportSchema airportSchema = convertToAirportSchema(airport);
            airportSchemas.add(airportSchema);
            airportSchemaCache.put(airport.getId(), airportSchema);
        }

        System.out.println("[DATABASE] Loaded " + airportSchemas.size() + " airports from database");
        return airportSchemas;
    }

    @Override
    public ArrayList<FlightSchema> loadFlights(ArrayList<AirportSchema> airports) {
        System.out.println("[DATABASE] Loading flights from PostgreSQL via FlightService...");

        List<Flight> flights = flightService.fetch(null); // null = fetch all
        ArrayList<FlightSchema> flightSchemas = new ArrayList<>();

        for (Flight flight : flights) {
            FlightSchema flightSchema = convertToFlightSchema(flight, airports);
            if (flightSchema != null) {
                flightSchemas.add(flightSchema);
            }
        }

        System.out.println("[DATABASE] Loaded " + flightSchemas.size() + " flights from database");
        return flightSchemas;
    }

    @Override
    @Deprecated
    public ArrayList<OrderSchema> loadOrders(ArrayList<AirportSchema> airports) {
        System.out.println("[DATABASE] Loading ALL orders (no time filtering) - DEPRECATED");
        System.out.println("[DATABASE] Consider using loadOrders(airports, startTime, endTime) instead");

        List<Order> orders = orderService.fetchOrders(null); // null = fetch all
        ArrayList<OrderSchema> orderSchemas = new ArrayList<>();

        for (Order order : orders) {
            OrderSchema orderSchema = convertToOrderSchema(order, airports);
            if (orderSchema != null) {
                orderSchemas.add(orderSchema);
            }
        }

        System.out.println("[DATABASE] Loaded " + orderSchemas.size() + " orders from database");
        return orderSchemas;
    }

    @Override
    public ArrayList<OrderSchema> loadOrders(ArrayList<AirportSchema> airports,
                                            LocalDateTime simulationStartTime,
                                            LocalDateTime simulationEndTime) {
        System.out.println("[DATABASE] Loading orders with time window filtering from PostgreSQL");
        System.out.println("[DATABASE] Time window: " + simulationStartTime + " to " + simulationEndTime);

        // Fetch all orders and filter by creation date
        // TODO: Optimize by adding query method to OrderService that filters by date range
        List<Order> allOrders = orderService.fetchOrders(null);
        ArrayList<OrderSchema> orderSchemas = new ArrayList<>();

        int filteredCount = 0;

        for (Order order : allOrders) {
            // Filter by creation date (order date)
            // WORKAROUND: If creationDate is not properly set (e.g., set to DB insertion time),
            // calculate approximate order date from delivery date
            LocalDateTime orderDate;
            if (order.getCreationDate() != null) {
                LocalDateTime creationDate = order.getCreationDate();
                LocalDateTime deliveryDate = order.getDeliveryDate();

                // Check if creationDate looks like a DB timestamp (after delivery date)
                // This indicates creationDate was auto-set during import, not from file
                if (creationDate.isAfter(deliveryDate)) {
                    // Calculate order date from delivery date (typically order_date + 2-3 days = delivery_date)
                    // Use conservative estimate: delivery_date - 3 days
                    orderDate = deliveryDate.minusDays(3);
                    System.out.println("[DATABASE] Warning: Order " + order.getName() +
                                     " has creationDate after deliveryDate. Using calculated orderDate: " + orderDate);
                } else {
                    orderDate = creationDate;
                }
            } else {
                orderDate = LocalDateTime.now();
            }

            // Skip orders outside simulation time window
            if (orderDate.isBefore(simulationStartTime) || orderDate.isAfter(simulationEndTime)) {
                filteredCount++;
                continue;
            }

            OrderSchema orderSchema = convertToOrderSchema(order, airports);
            if (orderSchema != null) {
                orderSchemas.add(orderSchema);
            }
        }

        System.out.println("[DATABASE] Loaded " + orderSchemas.size() + " orders from database");
        System.out.println("[DATABASE] Filtered out " + filteredCount + " orders outside time window");
        return orderSchemas;
    }

    @Override
    public String getSourceName() {
        return "DATABASE";
    }

    @Override
    public void initialize() {
        System.out.println("[DATABASE] Database-based data source initialized");
        System.out.println("[DATABASE] Using Spring Service layer (AirportService, FlightService, OrderService, ProductService)");
    }

    @Override
    public void cleanup() {
        System.out.println("[DATABASE] Database-based data source cleanup");
        airportSchemaCache.clear();
        citySchemaCache.clear();
    }

    // ========== CONVERSION METHODS: JPA Entity → Schema ==========

    /**
     * Converts JPA Airport entity to AirportSchema
     */
    private AirportSchema convertToAirportSchema(Airport airport) {
        AirportSchema airportSchema = new AirportSchema();

        airportSchema.setId(airport.getId());
        airportSchema.setCodeIATA(airport.getCodeIATA());
        airportSchema.setAlias(airport.getAlias());
        airportSchema.setTimezoneUTC(airport.getTimezoneUTC());
        airportSchema.setLatitude(airport.getLatitude());
        airportSchema.setLongitude(airport.getLongitude());
        airportSchema.setState(airport.getState());

        // Convert City
        if (airport.getCity() != null) {
            CitySchema citySchema = getCitySchema(airport.getCity());
            airportSchema.setCitySchema(citySchema);
        }

        // Convert Warehouse
        if (airport.getWarehouse() != null) {
            WarehouseSchema warehouse = convertToWarehouse(airport.getWarehouse());
            airportSchema.setWarehouse(warehouse);
        }

        return airportSchema;
    }

    /**
     * Converts JPA Flight entity to FlightSchema
     */
    private FlightSchema convertToFlightSchema(Flight flight, ArrayList<AirportSchema> airports) {
        FlightSchema flightSchema = new FlightSchema();

        flightSchema.setId(flight.getId());
        flightSchema.setMaxCapacity(flight.getMaxCapacity());
        flightSchema.setUsedCapacity(0); // Initial capacity is 0 (will be updated by algorithm)

        // Convert transport time from days to hours
        flightSchema.setTransportTime(flight.getTransportTimeDays() * 24.0);

        // Set frequency per day
        flightSchema.setFrequencyPerDay(flight.getDailyFrequency());

        // Find origin and destination airports from the provided list
        AirportSchema originAirport = findAirportSchemaById(airports, flight.getOriginAirport().getId());
        AirportSchema destAirport = findAirportSchemaById(airports, flight.getDestinationAirport().getId());

        if (originAirport == null || destAirport == null) {
            System.err.println("[DATABASE] Warning: Could not find airports for flight " + flight.getCode());
            return null;
        }

        flightSchema.setOriginAirportSchema(originAirport);
        flightSchema.setDestinationAirportSchema(destAirport);

        return flightSchema;
    }

    /**
     * Converts JPA Order entity to OrderSchema (with Products)
     */
    private OrderSchema convertToOrderSchema(Order order, ArrayList<AirportSchema> airports) {
        OrderSchema orderSchema = new OrderSchema();

        orderSchema.setId(order.getId());
        orderSchema.setName(order.getName()); // Set order name for persistence lookup

        // DEBUG: Log order name to verify it's being set
        if (order.getName() == null) {
            System.err.println("WARNING: Order " + order.getId() + " has NULL name in database!");
        } else {
            System.out.println("Loaded order: " + order.getName());
        }

        // Calculate proper order date (same logic as filtering)
        LocalDateTime orderDate;
        if (order.getCreationDate() != null) {
            LocalDateTime creationDate = order.getCreationDate();
            LocalDateTime deliveryDate = order.getDeliveryDate();

            // Check if creationDate looks like a DB timestamp (after delivery date)
            if (creationDate.isAfter(deliveryDate)) {
                // Calculate order date from delivery date
                orderDate = deliveryDate.minusDays(3);
            } else {
                orderDate = creationDate;
            }
        } else {
            orderDate = LocalDateTime.now();
        }

        orderSchema.setOrderDate(orderDate);
        orderSchema.setDeliveryDeadline(order.getDeliveryDate());
        orderSchema.setStatus(order.getStatus()); // Already PackageStatus in both JPA and Schema

        // Set priority (can be derived from customer or set to default)
        orderSchema.setPriority(1.0); // Default priority

        // Convert origin and destination cities
        if (order.getOrigin() != null) {
            CitySchema originCity = getCitySchema(order.getOrigin());
            orderSchema.setCurrentLocation(originCity);
        }

        if (order.getDestination() != null) {
            CitySchema destCity = getCitySchema(order.getDestination());
            orderSchema.setDestinationCitySchema(destCity);
        }

        // Convert customer
        if (order.getCustomer() != null) {
            CustomerSchema customerSchema = convertToCustomerSchema(order.getCustomer());
            orderSchema.setCustomerSchema(customerSchema);
        }

        // OPTIMIZATION: Don't load products here - they will be created at the end of algorithm
        // Products are created only when orders are split during algorithm execution
        // This avoids loading unnecessary data and reduces DB calls
        orderSchema.setProductSchemas(new ArrayList<>()); // Empty list for now

        return orderSchema;
    }

    /**
     * Converts JPA Product entity to ProductSchema
     */
    private ProductSchema convertToProductSchema(Product product) {
        ProductSchema productSchema = new ProductSchema();

        productSchema.setId(product.getId());
        productSchema.setStatus(Status.NOT_ASSIGNED); // Initial status
        
        // SET ORDER ID from product's order
        if (product.getOrder() != null) {
            productSchema.setOrderId(product.getOrder().getId());
        }

        // Product doesn't have assignedFlight yet (will be set by algorithm)
        productSchema.setAssignedFlight(new StringBuilder());

        return productSchema;
    }

    /**
     * Converts JPA Warehouse entity to Warehouse schema
     */
    private WarehouseSchema convertToWarehouse(com.system.morapack.dao.morapack_psql.model.Warehouse w) {
        WarehouseSchema s = new WarehouseSchema();
        s.setId(w.getId());
        s.setName(w.getName());
        s.setMaxCapacity(w.getMaxCapacity());
        s.setUsedCapacity(w.getUsedCapacity());
        s.setIsMainWarehouse(w.getIsMainWarehouse()); // o setMainWarehouse(...) según tu schema
        // si quieres exponer info básica del airport:
        if (w.getAirport() != null) {
            AirportSchema a = new AirportSchema();
            a.setId(w.getAirport().getId());
            a.setCodeIATA(w.getAirport().getCodeIATA());
            s.setAirportSchema(a);
        }
        return s;
    }

    /**
     * Converts JPA Customer entity to CustomerSchema
     */
    private CustomerSchema convertToCustomerSchema(Customer customer) {
        CustomerSchema customerSchema = new CustomerSchema();

        customerSchema.setId(customer.getId());
        // CustomerSchema only has id, name, email, deliveryCitySchema
        // Customer entity has phone and fiscalAddress, name/email would come from customer.getPerson()
        // For now, set placeholder values since Person entity info is not readily available
        customerSchema.setName("Customer-" + customer.getId());
        customerSchema.setEmail("customer" + customer.getId() + "@morapack.com");

        return customerSchema;
    }

    /**
     * Gets or creates CitySchema from JPA City entity (with caching)
     */
    private CitySchema getCitySchema(City city) {
        if (citySchemaCache.containsKey(city.getId())) {
            return citySchemaCache.get(city.getId());
        }

        CitySchema citySchema = new CitySchema();
        citySchema.setId(city.getId());
        citySchema.setName(city.getName());
        // City entity doesn't have country field, only name and continent
        citySchema.setContinent(city.getContinent());

        citySchemaCache.put(city.getId(), citySchema);
        return citySchema;
    }

    /**
     * Helper: Find AirportSchema by ID from list
     */
    private AirportSchema findAirportSchemaById(ArrayList<AirportSchema> airports, Integer id) {
        for (AirportSchema airport : airports) {
            if (airport.getId() == id) {  // Use == for int comparison
                return airport;
            }
        }
        return null;
    }

    /**
     * NEW: Loads flight instances for the simulation window
     * Uses FlightExpansionService to expand flight templates into daily instances
     */
    @Override
    public List<FlightInstanceSchema> loadFlightInstances(
            List<FlightSchema> flightTemplates,
            LocalDateTime simulationStartTime,
            LocalDateTime simulationEndTime) {

        System.out.println("[DATABASE] Expanding flight templates into daily instances...");

        // Use FlightExpansionService to expand flights across simulation window
        List<FlightInstanceSchema> instances = flightExpansionService.expandFlightsForSimulation(
            flightTemplates,
            simulationStartTime,
            simulationEndTime
        );

        // Print summary
        flightExpansionService.printExpansionSummary(instances);

        return instances;
    }

    /**
     * NEW: Loads existing product assignments from database for re-runs
     * This enables the algorithm to build on previous runs
     *
     * Returns: Map<FlightInstanceID, List<ProductSchema>>
     * Example: "LIM-CUZ-2025-01-02-20:00" -> [Product1, Product2, ...]
     */
    @Override
    public Map<String, List<ProductSchema>> loadExistingProductAssignments(
            LocalDateTime simulationStartTime,
            LocalDateTime simulationEndTime) {

        System.out.println("[DATABASE] Loading existing product assignments for re-run support...");
        System.out.println("[DATABASE] Time window: " + simulationStartTime + " to " + simulationEndTime);

        Map<String, List<ProductSchema>> assignmentMap = new HashMap<>();

        // Fetch all products (we'll filter them)
        List<Product> allProducts = productService.fetchProducts(null);

        int loadedCount = 0;
        int skippedCount = 0;

        for (Product product : allProducts) {
            // Only load products that:
            // 1. Have an assigned flight instance
            // 2. Are within simulation window (based on order date or flight schedule)
            // 3. Status is ASSIGNED or IN_TRANSIT

            if (product.getAssignedFlightInstance() == null ||
                product.getAssignedFlightInstance().trim().isEmpty()) {
                skippedCount++;
                continue; // Skip unassigned products
            }

            // Check status - only load products that are pending or in transit
            com.system.morapack.schemas.PackageStatus status = product.getStatus();
            if (status != com.system.morapack.schemas.PackageStatus.PENDING &&
                status != com.system.morapack.schemas.PackageStatus.IN_TRANSIT) {
                skippedCount++;
                continue; // Skip delivered or delayed products
            }

            // Convert to ProductSchema
            ProductSchema productSchema = convertToProductSchema(product);

            // Group by flight instance ID
            String flightInstanceId = product.getAssignedFlightInstance();
            assignmentMap.computeIfAbsent(flightInstanceId, k -> new ArrayList<>())
                         .add(productSchema);

            loadedCount++;
        }

        System.out.println("[DATABASE] Loaded " + loadedCount + " existing product assignments");
        System.out.println("[DATABASE] Skipped " + skippedCount + " products (unassigned or completed)");
        System.out.println("[DATABASE] Flight instances with assignments: " + assignmentMap.size());

        return assignmentMap;
    }
}
