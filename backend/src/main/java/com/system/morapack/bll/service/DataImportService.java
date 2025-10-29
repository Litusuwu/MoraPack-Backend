package com.system.morapack.bll.service;

import com.system.morapack.dao.morapack_psql.model.*;
import com.system.morapack.dao.morapack_psql.repository.*;
import com.system.morapack.schemas.*;
import com.system.morapack.schemas.algorithm.Input.InputAirports;
import com.system.morapack.schemas.algorithm.Input.InputData;
import com.system.morapack.schemas.algorithm.Input.InputProducts;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

@Service
@RequiredArgsConstructor
public class DataImportService {

    private final AirportRepository airportRepository;
    private final FlightRepository flightRepository;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final CityRepository cityRepository;
    private final WarehouseRepository warehouseRepository;
    private final CustomerRepository customerRepository;
    private final com.system.morapack.dao.morapack_psql.repository.UserRepository userRepository;

    /**
     * Imports airports from uploaded file
     * Format: Same as airportInfo.txt
     */
    @Transactional
    public Map<String, Object> importAirports(MultipartFile file) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // Save file temporarily
            Path tempFile = saveTempFile(file);
            
            // Read airports using InputAirports
            InputAirports inputAirports = new InputAirports(tempFile.toString());
            ArrayList<AirportSchema> airportSchemas = inputAirports.readAirports();
            
            if (airportSchemas.isEmpty()) {
                result.put("success", false);
                result.put("message", "No se encontraron aeropuertos en el archivo");
                result.put("count", 0);
                deleteTempFile(tempFile);
                return result;
            }
            
            // Convert Schemas to JPA entities and save
            List<City> cities = new ArrayList<>();
            List<Warehouse> warehouses = new ArrayList<>();
            List<Airport> airports = new ArrayList<>();
            Map<String, City> cityMap = new HashMap<>();
            
            for (AirportSchema schema : airportSchemas) {
                // Handle City (with Continent)
                CitySchema citySchema = schema.getCitySchema();
                String cityKey = citySchema.getName() + "-" + citySchema.getContinent();
                City city = cityMap.get(cityKey);
                
                if (city == null) {
                    city = City.builder()
                            .name(citySchema.getName())
                            .continent(citySchema.getContinent())
                            .country(citySchema.getCountry())
                            .build();
                    cities.add(city);
                    cityMap.put(cityKey, city);
                }
                
                // Handle Warehouse
                WarehouseSchema warehouseSchema = schema.getWarehouse();
                Warehouse warehouse = Warehouse.builder()
                        .name(warehouseSchema.getName())
                        .maxCapacity(warehouseSchema.getMaxCapacity())
                        .usedCapacity(warehouseSchema.getUsedCapacity())
                        .isMainWarehouse(warehouseSchema.getIsMainWarehouse())
                        .build();
                warehouses.add(warehouse);
                
                // Handle Airport
                Airport airport = Airport.builder()
                        .codeIATA(schema.getCodeIATA())
                        .alias(schema.getAlias())
                        .timezoneUTC(schema.getTimezoneUTC())
                        .latitude(schema.getLatitude())
                        .longitude(schema.getLongitude())
                        .state(schema.getState())
                        .city(city)
                        .warehouse(warehouse)
                        .build();
                airports.add(airport);
            }
            
            // Save to database
            cityRepository.saveAll(cities);
            warehouseRepository.saveAll(warehouses);
            List<Airport> savedAirports = airportRepository.saveAll(airports);
            
            // Clean up
            deleteTempFile(tempFile);
            
            result.put("success", true);
            result.put("message", "Aeropuertos cargados exitosamente");
            result.put("count", savedAirports.size());
            result.put("cities", cities.size());
            
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "Error al cargar aeropuertos: " + e.getMessage());
            result.put("error", e.getClass().getSimpleName());
            e.printStackTrace();
        }
        
        return result;
    }

    /**
     * Imports flights from uploaded file
     * Format: Same as flights.txt (ORIGIN-DESTINATION-DEPARTURE-ARRIVAL-CAPACITY)
     */
    @Transactional
    public Map<String, Object> importFlights(MultipartFile file) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // Verify airports exist in database
            List<Airport> existingAirports = airportRepository.findAll();
            if (existingAirports.isEmpty()) {
                result.put("success", false);
                result.put("message", "Debe cargar aeropuertos primero antes de cargar vuelos");
                result.put("count", 0);
                return result;
            }
            
            // Save file temporarily
            Path tempFile = saveTempFile(file);
            
            // Convert JPA Airports to AirportSchemas for InputData
            ArrayList<AirportSchema> airportSchemas = convertAirportsToSchemas(existingAirports);
            
            // Read flights using InputData
            InputData inputData = new InputData(tempFile.toString(), airportSchemas);
            ArrayList<FlightSchema> flightSchemas = inputData.readFlights();
            
            if (flightSchemas.isEmpty()) {
                result.put("success", false);
                result.put("message", "No se encontraron vuelos en el archivo");
                result.put("count", 0);
                deleteTempFile(tempFile);
                return result;
            }
            
            // Convert FlightSchemas to JPA entities
            Map<String, Airport> airportCodeMap = new HashMap<>();
            for (Airport airport : existingAirports) {
                airportCodeMap.put(airport.getCodeIATA(), airport);
            }
            
            List<Flight> flights = new ArrayList<>();
            for (FlightSchema schema : flightSchemas) {
                Airport origin = airportCodeMap.get(schema.getOriginAirportSchema().getCodeIATA());
                Airport destination = airportCodeMap.get(schema.getDestinationAirportSchema().getCodeIATA());
                
                if (origin == null || destination == null) {
                    System.err.println("Warning: Could not find airports for flight, skipping");
                    continue;
                }
                
                Flight flight = Flight.builder()
                        .code("FL-" + schema.getId())
                        .routeType(determineSameContinent(origin, destination) ? "CONTINENTAL" : "INTERCONTINENTAL")
                        .maxCapacity(schema.getMaxCapacity())
                        .transportTimeDays(schema.getTransportTime() / 24.0) // Convert hours to days
                        .dailyFrequency((int) Math.round(schema.getFrequencyPerDay()))
                        .status("ACTIVE")
                        .createdAt(java.time.LocalDateTime.now())
                        .originAirport(origin)
                        .destinationAirport(destination)
                        .build();
                
                flights.add(flight);
            }
            
            // Save to database
            List<Flight> savedFlights = flightRepository.saveAll(flights);
            
            // Clean up
            deleteTempFile(tempFile);
            
            result.put("success", true);
            result.put("message", "Vuelos cargados exitosamente");
            result.put("count", savedFlights.size());
            
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "Error al cargar vuelos: " + e.getMessage());
            result.put("error", e.getClass().getSimpleName());
            e.printStackTrace();
        }
        
        return result;
    }

    /**
     * Imports orders/products from uploaded file
     * Format: Same as products.txt (dd-hh-mm-dest-###-IdClien)
     */
    @Transactional
    public Map<String, Object> importOrders(MultipartFile file) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // Verify airports exist in database
            List<Airport> existingAirports = airportRepository.findAll();
            if (existingAirports.isEmpty()) {
                result.put("success", false);
                result.put("message", "Debe cargar aeropuertos primero antes de cargar pedidos");
                result.put("count", 0);
                return result;
            }
            
            // Save file temporarily
            Path tempFile = saveTempFile(file);
            
            // Convert JPA Airports to AirportSchemas for InputProducts
            ArrayList<AirportSchema> airportSchemas = convertAirportsToSchemas(existingAirports);
            
            // Read orders using InputProducts
            InputProducts inputProducts = new InputProducts(tempFile.toString(), airportSchemas);
            ArrayList<OrderSchema> orderSchemas = inputProducts.readProducts();
            
            if (orderSchemas.isEmpty()) {
                result.put("success", false);
                result.put("message", "No se encontraron pedidos en el archivo");
                result.put("count", 0);
                deleteTempFile(tempFile);
                return result;
            }
            
            // Convert OrderSchemas to JPA entities
            // Note: We'll create simplified orders without full customer/product details
            // The user can later enrich these with full data if needed
            List<Order> orders = new ArrayList<>();
            List<Product> allProducts = new ArrayList<>();
            Map<String, City> cityMap = new HashMap<>();
            
            // Build city map
            List<City> existingCities = cityRepository.findAll();
            for (City city : existingCities) {
                cityMap.put(city.getName(), city);
            }
            
            // Get or create a default customer for import
            Customer defaultCustomer = getOrCreateDefaultCustomer();
            
            int productCount = 0;
            for (OrderSchema orderSchema : orderSchemas) {
                // Handle Order
                City destCity = cityMap.get(orderSchema.getDestinationCitySchema().getName());
                City originCity = cityMap.get(orderSchema.getCurrentLocation().getName());
                
                if (destCity == null || originCity == null) {
                    System.err.println("Warning: Could not find cities for order " + orderSchema.getId());
                    continue;
                }
                
                Order order = Order.builder()
                        .name("ORDER-" + orderSchema.getId())
                        .customer(defaultCustomer)
                        .deliveryDate(orderSchema.getDeliveryDeadline())
                        .pickupTimeHours((double) orderSchema.getOrderDate().getHour())
                        .status(orderSchema.getStatus())
                        .origin(originCity)
                        .destination(destCity)
                        .build();
                
                orders.add(order);
                
                // Handle Products for this order (simplified)
                for (ProductSchema productSchema : orderSchema.getProductSchemas()) {
                    Product product = Product.builder()
                            .name("PRODUCT-" + (++productCount))
                            .weight(1.0) // Default weight
                            .volume(1.0) // Default volume
                            .order(order)
                            .build();
                    allProducts.add(product);
                }
            }
            
            // Save to database
            List<Order> savedOrders = orderRepository.saveAll(orders);
            List<Product> savedProducts = productRepository.saveAll(allProducts);
            
            // Clean up
            deleteTempFile(tempFile);
            
            result.put("success", true);
            result.put("message", "Pedidos cargados exitosamente");
            result.put("orders", savedOrders.size());
            result.put("products", savedProducts.size());
            
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "Error al cargar pedidos: " + e.getMessage());
            result.put("error", e.getClass().getSimpleName());
            e.printStackTrace();
        }
        
        return result;
    }

    // =========== HELPER METHODS ===========
    
    private Path saveTempFile(MultipartFile file) throws IOException {
        Path tempDir = Files.createTempDirectory("morapack-import-");
        Path tempFile = tempDir.resolve(file.getOriginalFilename());
        Files.copy(file.getInputStream(), tempFile, StandardCopyOption.REPLACE_EXISTING);
        return tempFile;
    }
    
    private void deleteTempFile(Path tempFile) {
        try {
            Files.deleteIfExists(tempFile);
            Files.deleteIfExists(tempFile.getParent());
        } catch (IOException e) {
            System.err.println("Warning: Could not delete temp file: " + e.getMessage());
        }
    }
    
    private ArrayList<AirportSchema> convertAirportsToSchemas(List<Airport> airports) {
        ArrayList<AirportSchema> schemas = new ArrayList<>();
        Map<Integer, CitySchema> citySchemaMap = new HashMap<>();
        
        for (Airport airport : airports) {
            // Convert City
            CitySchema citySchema = citySchemaMap.get(airport.getCity().getId());
            if (citySchema == null) {
                citySchema = new CitySchema();
                citySchema.setId(airport.getCity().getId());
                citySchema.setName(airport.getCity().getName());
                // Continent is already an enum in the JPA model
                Continent continent = airport.getCity().getContinent();
                citySchema.setContinent(continent);
                citySchemaMap.put(airport.getCity().getId(), citySchema);
            }
            
            // Convert Warehouse
            WarehouseSchema warehouseSchema = new WarehouseSchema();
            if (airport.getWarehouse() != null) {
                warehouseSchema.setId(airport.getWarehouse().getId());
                warehouseSchema.setName(airport.getWarehouse().getName());
                warehouseSchema.setMaxCapacity(airport.getWarehouse().getMaxCapacity());
                warehouseSchema.setUsedCapacity(airport.getWarehouse().getUsedCapacity());
                warehouseSchema.setIsMainWarehouse(airport.getWarehouse().getIsMainWarehouse());
            }
            
            // Convert Airport
            AirportSchema schema = new AirportSchema();
            schema.setId(airport.getId());
            schema.setCodeIATA(airport.getCodeIATA());
            schema.setAlias(airport.getAlias());
            schema.setTimezoneUTC(airport.getTimezoneUTC());
            schema.setLatitude(airport.getLatitude());
            schema.setLongitude(airport.getLongitude());
            schema.setState(airport.getState());
            schema.setCitySchema(citySchema);
            schema.setWarehouse(warehouseSchema);
            
            schemas.add(schema);
        }
        
        return schemas;
    }
    
    private boolean determineSameContinent(Airport origin, Airport destination) {
        return origin.getCity().getContinent().equals(destination.getCity().getContinent());
    }
    
    /**
     * Get or create a default customer for data import.
     * This avoids the need to create individual customers for each order during import.
     */
    private Customer getOrCreateDefaultCustomer() {
        // Try to find existing default customer
        List<Customer> existingCustomers = customerRepository.findAll();
        if (!existingCustomers.isEmpty()) {
            return existingCustomers.get(0); // Use first customer as default
        }
        
        // Create a default user for the customer
        User defaultUser = User.builder()
                .name("IMPORT")
                .lastName("DEFAULT_USER")
                .userType(com.system.morapack.schemas.TypeUser.CUSTOMER)
                .build();
        defaultUser = userRepository.save(defaultUser);
        
        // Create default customer
        Customer defaultCustomer = Customer.builder()
                .phone("000-000-0000")
                .fiscalAddress("Default Import Address")
                .person(defaultUser)
                .createdAt(java.time.LocalDateTime.now())
                .build();
        
        return customerRepository.save(defaultCustomer);
    }
}

