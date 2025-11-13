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
import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

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
    public Map<String, Object> importAirports() {
        Map<String, Object> result = new HashMap<>();

        try {
            // Reiniciar tablas dependientes (limpiar antes de recargar)
            productRepository.deleteAll();
            orderRepository.deleteAll();
            flightRepository.deleteAll();
            airportRepository.deleteAll();
            warehouseRepository.deleteAll();
            cityRepository.deleteAll();

            // Leer archivo local
            File source = new File("data/airportInfo.txt");
            if (!source.exists()) {
                result.put("success", false);
                result.put("message", "No se encontró el archivo data/airportInfo.txt");
                return result;
            }

            // Leer aeropuertos usando InputAirports
            InputAirports inputAirports = new InputAirports(source.getPath());
            ArrayList<AirportSchema> airportSchemas = inputAirports.readAirports();

            if (airportSchemas.isEmpty()) {
                result.put("success", false);
                result.put("message", "No se encontraron aeropuertos en el archivo");
                result.put("count", 0);
                return result;
            }

            // Convertir schemas a entidades JPA
            List<City> cities = new ArrayList<>();
            List<Warehouse> warehouses = new ArrayList<>();
            List<Airport> airports = new ArrayList<>();
            Map<String, City> cityMap = new HashMap<>();

            for (AirportSchema schema : airportSchemas) {
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

                WarehouseSchema warehouseSchema = schema.getWarehouse();
                Warehouse warehouse = Warehouse.builder()
                        .name(warehouseSchema.getName())
                        .maxCapacity(warehouseSchema.getMaxCapacity())
                        .usedCapacity(warehouseSchema.getUsedCapacity())
                        .isMainWarehouse(warehouseSchema.getIsMainWarehouse())
                        .build();
                warehouses.add(warehouse);

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

            // Guardar en BD
            cityRepository.saveAll(cities);
            warehouseRepository.saveAll(warehouses);
            List<Airport> savedAirports = airportRepository.saveAll(airports);

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
    public Map<String, Object> importFlights() {
        Map<String, Object> result = new HashMap<>();

        try {
            // Limpiar vuelos previos
            flightRepository.deleteAll();

            // Verificar aeropuertos existentes
            List<Airport> existingAirports = airportRepository.findAll();
            if (existingAirports.isEmpty()) {
                result.put("success", false);
                result.put("message", "Debe cargar aeropuertos primero antes de cargar vuelos");
                result.put("count", 0);
                return result;
            }

            // Leer archivo local
            File source = new File("data/flights.txt");
            if (!source.exists()) {
                result.put("success", false);
                result.put("message", "No se encontró el archivo data/flights.txt");
                return result;
            }

            // Convertir aeropuertos a schemas
            ArrayList<AirportSchema> airportSchemas = convertAirportsToSchemas(existingAirports);

            // Leer vuelos usando InputData
            InputData inputData = new InputData(source.getPath(), airportSchemas);
            ArrayList<FlightSchema> flightSchemas = inputData.readFlights();

            if (flightSchemas.isEmpty()) {
                result.put("success", false);
                result.put("message", "No se encontraron vuelos en el archivo");
                result.put("count", 0);
                return result;
            }

            // Convertir FlightSchemas a entidades JPA
            Map<String, Airport> airportCodeMap = new HashMap<>();
            for (Airport airport : existingAirports) {
                airportCodeMap.put(airport.getCodeIATA(), airport);
            }

            List<Flight> flights = new ArrayList<>();
            for (FlightSchema schema : flightSchemas) {
                Airport origin = airportCodeMap.get(schema.getOriginAirportSchema().getCodeIATA());
                Airport destination = airportCodeMap.get(schema.getDestinationAirportSchema().getCodeIATA());

                if (origin == null || destination == null) {
                    System.err.println("Warning: No se encontraron aeropuertos para el vuelo, se omite.");
                    continue;
                }

                Flight flight = Flight.builder()
                        .code("FL-" + schema.getId())
                        .routeType(determineSameContinent(origin, destination) ? "CONTINENTAL" : "INTERCONTINENTAL")
                        .maxCapacity(schema.getMaxCapacity())
                        .transportTimeDays(schema.getTransportTime() / 24.0)
                        .dailyFrequency((int) Math.round(schema.getFrequencyPerDay()))
                        .status("ACTIVE")
                        .createdAt(java.time.LocalDateTime.now())
                        .originAirport(origin)
                        .destinationAirport(destination)
                        .build();

                flights.add(flight);
            }

            // Guardar en BD
            List<Flight> savedFlights = flightRepository.saveAll(flights);

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
                        .name(orderSchema.getName() != null ? orderSchema.getName() : "ORDER-" + orderSchema.getId())
                        .customer(defaultCustomer)
                        .creationDate(orderSchema.getOrderDate())  // FIX: Set actual order date from file
                        .deliveryDate(orderSchema.getDeliveryDeadline())
                        .pickupTimeHours((double) orderSchema.getOrderDate().getHour())
                        .status(orderSchema.getStatus())
                        .origin(originCity)  // Now uses main warehouse city
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

    @Transactional
    public Map<String, Object> importOrdersByDateRange(String startDateStr, String endDateStr) {
        Map<String, Object> result = new HashMap<>();
        try {
            // Fechas que vienen del frontend → dd/MM/yyyy
            DateTimeFormatter uiFormatter = DateTimeFormatter.ofPattern("yyyyMMdd");
            LocalDate start = LocalDate.parse(startDateStr, uiFormatter);
            LocalDate end   = LocalDate.parse(endDateStr, uiFormatter);

            // Fechas dentro de los archivos → yyyyMMdd
            DateTimeFormatter fileFormatter = DateTimeFormatter.BASIC_ISO_DATE;

            if (end.isBefore(start)) {
                result.put("success", false);
                result.put("message", "La fecha final no puede ser anterior a la inicial");
                return result;
            }

            List<Airport> airports = airportRepository.findAll();
            if (airports.isEmpty()) {
                result.put("success", false);
                result.put("message", "Debe cargar aeropuertos primero antes de cargar pedidos");
                return result;
            }

            productRepository.deleteAll();
            orderRepository.deleteAll();

            Map<String, Airport> airportByCode = new HashMap<>();
            for (Airport a : airports) airportByCode.put(a.getCodeIATA(), a);
            Customer defaultCustomer = getOrCreateDefaultCustomer();

            File folder = new File("data/products");
            if (!folder.exists() || !folder.isDirectory()) {
                result.put("success", false);
                result.put("message", "No se encontró la carpeta data/products");
                return result;
            }

            int ordersCount = 0, productCount = 0;
            List<Order> orders = new ArrayList<>();
            List<Product> products = new ArrayList<>();

            for (File file : Objects.requireNonNull(folder.listFiles((d, n) -> n.endsWith(".txt")))) {
                List<String> lines = Files.readAllLines(file.toPath());
                for (String line : lines) {
                    // Formato: 000000001-20250102-01-38-EBCI-006-0007729
                    String[] p = line.split("-");
                    if (p.length < 7) continue;

                    LocalDate date;
                    try {
                        String clean = p[1]
                                .replaceAll("[^0-9/]", "")  // elimina cualquier carácter no numérico ni '/'
                                .trim();

                        if (clean.isEmpty()) continue;

                        if (clean.contains("/")) {
                            date = LocalDate.parse(clean, DateTimeFormatter.ofPattern("dd/MM/yyyy"));
                        } else if (clean.length() == 8) {
                            date = LocalDate.parse(clean, DateTimeFormatter.ofPattern("yyyyMMdd"));
                        } else {
                            System.err.println("Fecha inválida: " + p[1]);
                            continue;
                        }
                    } catch (Exception ex) {
                        System.err.println("Error parseando fecha: " + p[1]);
                        continue;
                    }
                    if (date.isBefore(start) || date.isAfter(end)) continue;

                    String id = p[0];
                    String airportCode = p[4];
                    int qty;
                    double pickupHour;
                    try {
                        qty = Integer.parseInt(p[5]);
                        pickupHour = Double.parseDouble(p[2]);
                    } catch (NumberFormatException ex) {
                        continue;
                    }

                    Airport destAirport = airportByCode.get(airportCode);
                    if (destAirport == null || destAirport.getCity() == null) continue;
                    City destCity = destAirport.getCity();

                    Order order = Order.builder()
                            .name("ORDER-" + id)
                            .customer(defaultCustomer)
                            .creationDate(date.atStartOfDay())              // <-- FIX
                            .deliveryDate(date.plusDays(1).atStartOfDay())  // por ejemplo +1 día          // o la fecha que corresponda
                            .pickupTimeHours(pickupHour)
                            .status(PackageStatus.PENDING)
                            .origin(destCity)
                            .destination(destCity)
                            .build();
                    orders.add(order);
                    ordersCount++;

                    for (int i = 0; i < qty; i++) {
                        products.add(Product.builder()
                                .name("PRODUCT-" + (productCount + 1))
                                .weight(1.0)
                                .volume(1.0)
                                .order(order)
                                .build());
                        productCount++;
                    }
                }
            }

            System.out.println("Fechas recibidas: " + start + " → " + end);
            System.out.println("Archivos leídos: " + folder.listFiles((d, n) -> n.endsWith(".txt")).length);
            System.out.println("Órdenes totales: " + ordersCount + ", Productos: " + productCount);

            int batchSize = 2000; // o 1000 si el entorno es limitado
            for (int i = 0; i < orders.size(); i += batchSize) {
                int finald = Math.min(i + batchSize, orders.size());
                orderRepository.saveAll(orders.subList(i, finald));
            }

            for (int i = 0; i < products.size(); i += batchSize) {
                int finald = Math.min(i + batchSize, products.size());
                productRepository.saveAll(products.subList(i, finald));
            }

            result.put("success", true);
            result.put("message", "Pedidos cargados exitosamente por rango de fechas");
            result.put("orders", ordersCount);
            result.put("products", productCount);
            return result;

        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "Error al cargar pedidos: " + e.getMessage());
            result.put("error", e.getClass().getSimpleName());
            e.printStackTrace();
            return result;
        }
    }

}

