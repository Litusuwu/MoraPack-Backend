package com.system.morapack.schemas.algorithm.Input;

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
            LocalDateTime orderDate = order.getCreationDate() != null ?
                    order.getCreationDate() : LocalDateTime.now();

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
        orderSchema.setOrderDate(order.getCreationDate() != null ? order.getCreationDate() : LocalDateTime.now());
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

        // Load and convert products for this order using ProductService
        List<Product> products = productService.getProductsByOrder(order.getId());
        ArrayList<ProductSchema> productSchemas = new ArrayList<>();

        for (Product product : products) {
            ProductSchema productSchema = convertToProductSchema(product);
            productSchemas.add(productSchema);
        }

        orderSchema.setProductSchemas(productSchemas);

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
}
