package com.system.morapack;

import com.system.morapack.bll.service.FlightExpansionService;
import com.system.morapack.bll.service.FlightInstanceManager;
import com.system.morapack.schemas.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test para verificar que el sistema de FlightInstance funciona correctamente
 */
@SpringBootTest
public class FlightInstanceTest {

    @Autowired
    private FlightExpansionService flightExpansionService;

    /**
     * Test 1: Verificar expansión de vuelos
     * Un vuelo que se repite 7 días debe crear 7 instancias
     */
    @Test
    public void testFlightExpansion() {
        System.out.println("\n========================================");
        System.out.println("TEST 1: FLIGHT EXPANSION");
        System.out.println("========================================");

        // Crear un vuelo template (LIM-CUZ que sale a las 8 PM, llega a las 3 AM del día siguiente)
        FlightSchema template = createFlightTemplate(
            1,
            "LIM-CUZ",
            "SPIM",  // Lima
            "SPZO",  // Cusco
            LocalTime.of(20, 0),  // 8:00 PM
            LocalTime.of(3, 0),   // 3:00 AM (día siguiente!)
            300
        );

        // Simular 7 días
        LocalDateTime startTime = LocalDateTime.of(2025, 1, 2, 0, 0);
        LocalDateTime endTime = LocalDateTime.of(2025, 1, 9, 0, 0);

        // Expandir vuelos
        List<FlightInstanceSchema> instances = flightExpansionService.expandFlightsForSimulation(
            List.of(template),
            startTime,
            endTime
        );

        // Verificar
        System.out.println("Vuelo template: " + template.getCode());
        System.out.println("Horario: " + template.getDepartureTime() + " → " + template.getArrivalTime());
        System.out.println("Instancias creadas: " + instances.size());
        System.out.println("\nDetalles de instancias:");

        assertEquals(7, instances.size(), "Debe crear 7 instancias (una por día)");

        for (int i = 0; i < instances.size(); i++) {
            FlightInstanceSchema inst = instances.get(i);
            System.out.println(String.format("  Day %d: %s - Departure: %s, Arrival: %s, Capacity: %d/%d",
                inst.getInstanceDay(),
                inst.getInstanceId(),
                inst.getDepartureDateTime(),
                inst.getArrivalDateTime(),
                inst.getUsedCapacity(),
                inst.getMaxCapacity()
            ));

            // Verificar que el vuelo cruza medianoche
            assertTrue(inst.getArrivalDateTime().isAfter(inst.getDepartureDateTime()),
                "Arrival debe ser después de departure");
            assertEquals(0, inst.getUsedCapacity(), "Capacidad inicial debe ser 0");
            assertEquals(300, inst.getMaxCapacity(), "Capacidad máxima debe ser 300");
        }

        System.out.println("✅ Test 1 PASSED\n");
    }

    /**
     * Test 2: Verificar FlightInstanceManager
     * Debe poder buscar vuelos disponibles y reservar capacidad
     */
    @Test
    public void testFlightInstanceManager() {
        System.out.println("\n========================================");
        System.out.println("TEST 2: FLIGHT INSTANCE MANAGER");
        System.out.println("========================================");

        // Crear vuelos template
        List<FlightSchema> templates = new ArrayList<>();
        templates.add(createFlightTemplate(1, "LIM-CUZ", "SPIM", "SPZO",
            LocalTime.of(8, 0), LocalTime.of(13, 0), 300));
        templates.add(createFlightTemplate(2, "LIM-AQP", "SPIM", "SPQU",
            LocalTime.of(10, 0), LocalTime.of(12, 0), 250));

        // Expandir para 3 días
        LocalDateTime startTime = LocalDateTime.of(2025, 1, 2, 0, 0);
        LocalDateTime endTime = LocalDateTime.of(2025, 1, 5, 0, 0);

        List<FlightInstanceSchema> instances = flightExpansionService.expandFlightsForSimulation(
            templates,
            startTime,
            endTime
        );

        System.out.println("Total instancias creadas: " + instances.size());
        assertEquals(6, instances.size(), "2 vuelos × 3 días = 6 instancias");

        // Inicializar FlightInstanceManager
        FlightInstanceManager manager = new FlightInstanceManager();
        manager.initialize(instances, Map.of(), startTime, endTime);

        // Crear aeropuertos ficticios
        AirportSchema limAirport = createAirport(1, "SPIM", "Lima");
        AirportSchema cuzAirport = createAirport(2, "SPZO", "Cusco");

        // Test: Buscar vuelo directo
        System.out.println("\nBuscando vuelo LIM → CUZ después de 2025-01-02 09:00...");
        List<FlightInstanceSchema> availableFlights = manager.findDirectFlightInstances(
            limAirport,
            cuzAirport,
            LocalDateTime.of(2025, 1, 2, 9, 0)
        );

        System.out.println("Vuelos encontrados: " + availableFlights.size());
        assertTrue(availableFlights.size() > 0, "Debe encontrar al menos un vuelo");

        availableFlights.forEach(inst -> {
            System.out.println("  - " + inst.getInstanceId() +
                " (Departure: " + inst.getDepartureDateTime() +
                ", Capacity: " + inst.getUsedCapacity() + "/" + inst.getMaxCapacity() + ")");
        });

        // Test: Reservar capacidad
        System.out.println("\nReservando 50 productos en primer vuelo disponible...");
        FlightInstanceSchema firstFlight = availableFlights.get(0);
        int capacityBefore = firstFlight.getUsedCapacity();

        manager.reserveCapacity(firstFlight, 50);

        int capacityAfter = firstFlight.getUsedCapacity();
        System.out.println("Capacidad antes: " + capacityBefore + "/" + firstFlight.getMaxCapacity());
        System.out.println("Capacidad después: " + capacityAfter + "/" + firstFlight.getMaxCapacity());

        assertEquals(50, capacityAfter, "Capacidad usada debe ser 50");

        // Test: Buscar siguiente vuelo con capacidad disponible
        System.out.println("\nBuscando siguiente vuelo con capacidad para 100 productos...");
        FlightInstanceSchema nextAvailable = manager.findNextAvailableInstance(
            limAirport,
            cuzAirport,
            LocalDateTime.of(2025, 1, 2, 9, 0),
            100
        );

        assertNotNull(nextAvailable, "Debe encontrar un vuelo disponible");
        System.out.println("Vuelo encontrado: " + nextAvailable.getInstanceId());
        System.out.println("Capacidad disponible: " +
            (nextAvailable.getMaxCapacity() - nextAvailable.getUsedCapacity()) + "/" +
            nextAvailable.getMaxCapacity());

        assertTrue(nextAvailable.hasCapacity(100), "Debe tener capacidad para 100 productos");

        System.out.println("✅ Test 2 PASSED\n");
    }

    /**
     * Test 3: Verificar re-runs (carga de asignaciones existentes)
     */
    @Test
    public void testReRunSupport() {
        System.out.println("\n========================================");
        System.out.println("TEST 3: RE-RUN SUPPORT");
        System.out.println("========================================");

        // Crear vuelo template
        FlightSchema template = createFlightTemplate(1, "LIM-CUZ", "SPIM", "SPZO",
            LocalTime.of(8, 0), LocalTime.of(13, 0), 300);

        LocalDateTime startTime = LocalDateTime.of(2025, 1, 2, 0, 0);
        LocalDateTime endTime = LocalDateTime.of(2025, 1, 5, 0, 0);

        List<FlightInstanceSchema> instances = flightExpansionService.expandFlightsForSimulation(
            List.of(template),
            startTime,
            endTime
        );

        // Simular productos ya asignados (como si viniera de la DB)
        Map<String, List<ProductSchema>> existingAssignments = Map.of(
            "FL-1-DAY-0-0800", List.of(
                createProduct(1, "Product-1"),
                createProduct(2, "Product-2"),
                createProduct(3, "Product-3")
            ),
            "FL-1-DAY-1-0800", List.of(
                createProduct(4, "Product-4"),
                createProduct(5, "Product-5")
            )
        );

        System.out.println("Productos pre-asignados:");
        existingAssignments.forEach((instanceId, products) -> {
            System.out.println("  " + instanceId + ": " + products.size() + " productos");
        });

        // Inicializar manager CON asignaciones existentes
        FlightInstanceManager manager = new FlightInstanceManager();
        manager.initialize(instances, existingAssignments, startTime, endTime);

        // Verificar que las capacidades se pre-llenaron
        FlightInstanceSchema day0Flight = manager.getInstanceById("FL-1-DAY-0-0800");
        FlightInstanceSchema day1Flight = manager.getInstanceById("FL-1-DAY-1-0800");

        assertNotNull(day0Flight, "Vuelo Day 0 debe existir");
        assertNotNull(day1Flight, "Vuelo Day 1 debe existir");

        System.out.println("\nCapacidades después de pre-fill:");
        System.out.println("  FL-1-DAY-0-0800: " + day0Flight.getUsedCapacity() + "/300 (esperado: 3)");
        System.out.println("  FL-1-DAY-1-0800: " + day1Flight.getUsedCapacity() + "/300 (esperado: 2)");

        assertEquals(3, day0Flight.getUsedCapacity(), "Day 0 debe tener 3 productos pre-asignados");
        assertEquals(2, day1Flight.getUsedCapacity(), "Day 1 debe tener 2 productos pre-asignados");

        System.out.println("\n✅ Test 3 PASSED");
        System.out.println("Re-runs funcionan correctamente!");
        System.out.println("========================================\n");
    }

    // ==========================================
    // HELPER METHODS
    // ==========================================

    private FlightSchema createFlightTemplate(int id, String code, String originCode, String destCode,
                                             LocalTime departure, LocalTime arrival, int capacity) {
        AirportSchema origin = createAirport(id * 10, originCode, "Origin-" + originCode);
        AirportSchema dest = createAirport(id * 10 + 1, destCode, "Dest-" + destCode);

        return FlightSchema.builder()
            .id(id)
            .code(code)
            .originAirportId(origin.getId())
            .originAirportCode(originCode)
            .originAirportSchema(origin)
            .destinationAirportId(dest.getId())
            .destinationAirportCode(destCode)
            .destinationAirportSchema(dest)
            .departureTime(departure)
            .arrivalTime(arrival)
            .maxCapacity(capacity)
            .transportTimeDays(0.5)
            .build();
    }

    private AirportSchema createAirport(int id, String code, String cityName) {
        CitySchema city = new CitySchema();
        city.setId(id);
        city.setName(cityName);
        city.setContinent(Continent.AMERICA);

        AirportSchema airport = new AirportSchema();
        airport.setId(id);
        airport.setCodeIATA(code);
        airport.setCitySchema(city);

        return airport;
    }

    private ProductSchema createProduct(int id, String name) {
        ProductSchema product = new ProductSchema();
        product.setId(id);
        product.setStatus(Status.ASSIGNED);
        return product;
    }
}
