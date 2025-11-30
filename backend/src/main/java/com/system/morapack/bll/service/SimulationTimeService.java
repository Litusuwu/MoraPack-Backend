package com.system.morapack.bll.service;

import com.system.morapack.api.SimulationAPI;
import com.system.morapack.dao.morapack_psql.model.Flight;
import com.system.morapack.dao.morapack_psql.model.Order;
import com.system.morapack.dao.morapack_psql.model.Product;
import com.system.morapack.dao.morapack_psql.model.ProductFlight;
import com.system.morapack.dao.morapack_psql.repository.FlightRepository;
import com.system.morapack.dao.morapack_psql.repository.ProductRepository;
import com.system.morapack.dao.morapack_psql.repository.OrderRepository;
import com.system.morapack.dao.morapack_psql.service.FlightService;
import com.system.morapack.dao.morapack_psql.service.OrderService;
import com.system.morapack.dao.morapack_psql.service.ProductService;
import com.system.morapack.dao.morapack_psql.service.WarehouseService;
import com.system.morapack.dao.morapack_psql.repository.AirportRepository;
import com.system.morapack.schemas.PackageStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

/**
 * Service para simular el paso del tiempo y actualizar estados de productos
 *
 * Responsabilidades:
 * - Calcular fechas de llegada de productos basándose en sus vuelos
 * - Actualizar estados (IN_TRANSIT → ARRIVED → DELIVERED)
 * - Simular el avance del tiempo en la simulación
 */
@Service
@RequiredArgsConstructor
public class SimulationTimeService {

    private final ProductService productService;
    private final OrderService orderService;
    private final WarehouseService warehouseService;
    private final AirportRepository airportRepository;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final FlightRepository flightRepository;

    // Cache for capacity stats to avoid expensive aggregations on every call
    private SimulationAPI.CapacityStats cachedCapacityStats = null;
    private LocalDateTime lastCapacityCalculation = null;

    /**
     * Actualiza estados de productos basándose en el tiempo actual de simulación
     *
     * @param currentSimulationTime Tiempo actual en la simulación
     * @return Estadísticas de actualizaciones
     */
    @Transactional
    public SimulationUpdateStats updateProductStates(LocalDateTime currentSimulationTime) {
        System.out.println("========================================");
        System.out.println("SIMULATION TIME UPDATE");
        System.out.println("Current simulation time: " + currentSimulationTime);
        System.out.println("========================================");

        SimulationUpdateStats stats = new SimulationUpdateStats();

        // OPTIMIZATION: Load only active products (not DELIVERED) with their orders in one query
        List<Product> activeProducts = productRepository.findByStatusIn(
            Arrays.asList(PackageStatus.PENDING, PackageStatus.IN_TRANSIT, PackageStatus.ARRIVED)
        );

        System.out.println("Active products to check: " + activeProducts.size());

        // OPTIMIZATION: Collect products to update by new status (for batch update)
        Map<PackageStatus, List<Integer>> productUpdates = new HashMap<>();
        productUpdates.put(PackageStatus.IN_TRANSIT, new ArrayList<>());
        productUpdates.put(PackageStatus.ARRIVED, new ArrayList<>());
        productUpdates.put(PackageStatus.DELIVERED, new ArrayList<>());

        for (Product product : activeProducts) {
            PackageStatus oldStatus = product.getStatus();
            PackageStatus newStatus = calculateProductStatus(product, currentSimulationTime);

            if (oldStatus != newStatus) {
                productUpdates.get(newStatus).add(product.getId());
                stats.recordTransition(oldStatus, newStatus);

                System.out.println("Product " + product.getId() + ": " +
                                 oldStatus + " → " + newStatus);
            }
        }

        // OPTIMIZATION: Batch update all products at once (3 queries instead of N)
        for (Map.Entry<PackageStatus, List<Integer>> entry : productUpdates.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                productRepository.batchUpdateStatus(entry.getKey(), entry.getValue());
                System.out.println("Batch updated " + entry.getValue().size() +
                                 " products to " + entry.getKey());
            }
        }

        // Actualizar estados de órdenes
        updateOrderStates();

        System.out.println("\n=== SIMULATION UPDATE COMPLETE ===");
        stats.print();
        System.out.println("==================================\n");

        return stats;
    }

    /**
     * Calcula el estado correcto de un producto basándose en el tiempo de simulación
     */
    private PackageStatus calculateProductStatus(Product product, LocalDateTime currentTime) {
        // Si no tiene vuelos asignados, permanece PENDING
        if (product.getAssignedFlightInstance() == null ||
            product.getAssignedFlightInstance().trim().isEmpty()) {
            return PackageStatus.PENDING;
        }

        // Calcular fecha de llegada del producto
        LocalDateTime arrivalTime = calculateProductArrivalTime(product);

        if (arrivalTime == null) {
            // No se pudo calcular, mantener IN_TRANSIT
            return PackageStatus.IN_TRANSIT;
        }

        // Comparar con tiempo actual
        if (currentTime.isBefore(arrivalTime)) {
            // Aún en tránsito
            return PackageStatus.IN_TRANSIT;
        } else if (currentTime.isAfter(arrivalTime.plusHours(2))) {
            // Pasaron más de 2 horas desde llegada → DELIVERED (cliente recogió)
            return PackageStatus.DELIVERED;
        } else {
            // Llegó pero cliente aún tiene tiempo para recoger
            return PackageStatus.ARRIVED;
        }
    }

    /**
     * Calcula cuándo llega el producto a su destino final
     * Basándose en sus vuelos asignados (ProductFlight)
     */
    private LocalDateTime calculateProductArrivalTime(Product product) {
        List<ProductFlight> productFlights = product.getProductFlights();

        if (productFlights == null || productFlights.isEmpty()) {
            // Intentar parsear desde assigned_flight_instance
            return parseArrivalFromInstanceId(product);
        }

        // Obtener el último vuelo (máximo sequenceOrder)
        ProductFlight lastFlight = productFlights.stream()
            .max((pf1, pf2) -> Integer.compare(pf1.getSequenceOrder(), pf2.getSequenceOrder()))
            .orElse(null);

        if (lastFlight == null || lastFlight.getFlight() == null) {
            return null;
        }

        // Calcular fecha de llegada del último vuelo
        return calculateFlightArrivalTime(lastFlight.getFlight(), product);
    }

    /**
     * Calcula fecha de llegada de un vuelo específico
     */
    private LocalDateTime calculateFlightArrivalTime(Flight flight, Product product) {
        // Parsear assigned_flight_instance para obtener la fecha de salida
        // Formato: FL-{flightId}-DAY-{day}-{HHmm}
        String instanceId = product.getAssignedFlightInstance();

        if (instanceId == null) {
            return null;
        }

        try {
            // Extraer información del instance ID
            String[] parts = instanceId.split("-");
            if (parts.length < 5) {
                return null;
            }

            int day = Integer.parseInt(parts[3]); // DAY number
            String timeStr = parts[4]; // HHmm

            int hour = Integer.parseInt(timeStr.substring(0, 2));
            int minute = timeStr.length() >= 4 ?
                Integer.parseInt(timeStr.substring(2, 4)) : 0;

            // Obtener orden para fecha base
            Order order = product.getOrder();
            if (order == null || order.getCreationDate() == null) {
                return null;
            }

            // Calcular fecha de salida
            LocalDateTime departureDate = order.getCreationDate()
                .toLocalDate()
                .atTime(hour, minute)
                .plusDays(day);

            // Obtener tiempo de transporte del vuelo
            Double transportTimeDays = flight.getTransportTimeDays();
            if (transportTimeDays == null || transportTimeDays == 0) {
                // Fallback: asumir medio día
                transportTimeDays = 0.5;
            }

            // Calcular llegada
            long transportMinutes = (long) (transportTimeDays * 24 * 60);
            return departureDate.plusMinutes(transportMinutes);

        } catch (Exception e) {
            System.err.println("Error calculating arrival time for product " +
                             product.getId() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Parsear fecha de llegada desde assigned_flight_instance
     * Como fallback si no hay ProductFlight
     */
    private LocalDateTime parseArrivalFromInstanceId(Product product) {
        // Este método es un fallback simple
        // Asume que el producto llega 12 horas después de la fecha de creación de orden
        Order order = product.getOrder();
        if (order != null && order.getCreationDate() != null) {
            return order.getCreationDate().plusHours(12);
        }
        return null;
    }

    /**
     * Actualiza estados de órdenes basándose en estados de sus productos
     * OPTIMIZATION: Uses JOIN FETCH to load orders with products in one query (prevents N+1)
     */
    private void updateOrderStates() {
        // OPTIMIZATION: Load only active orders WITH products in ONE query (no N+1)
        List<Order> activeOrders = orderRepository.findByStatusInWithProducts(
            Arrays.asList(PackageStatus.PENDING, PackageStatus.IN_TRANSIT, PackageStatus.ARRIVED)
        );

        System.out.println("Active orders to check: " + activeOrders.size());

        for (Order order : activeOrders) {
            // OPTIMIZATION: Products already loaded via JOIN FETCH - no extra query!
            PackageStatus orderStatus = calculateOrderStatusFromLoadedProducts(order);

            if (order.getStatus() != orderStatus) {
              // Actualizar capacidad de almacenes antes de cambiar el estado
              handleCapacityUpdate(order, order.getStatus(), orderStatus);

                order.setStatus(orderStatus);
                orderService.save(order);
            }
        }
    }

    /**
     * Maneja la actualización de capacidad en los almacenes
     * Regla: Los ORDERS ocupan espacio (1 unidad por orden)
     */
    private void handleCapacityUpdate(Order order, PackageStatus oldStatus, PackageStatus newStatus) {
      try {
        // PENDING -> IN_TRANSIT: Sale del origen
        if (oldStatus == PackageStatus.PENDING && newStatus == PackageStatus.IN_TRANSIT) {
          updateAirportCapacity(order.getOrigin(), -1);
        }
        // IN_TRANSIT -> ARRIVED: Llega al destino
        else if (oldStatus == PackageStatus.IN_TRANSIT && newStatus == PackageStatus.ARRIVED) {
          updateAirportCapacity(order.getDestination(), 1);
        }
        // ARRIVED -> DELIVERED: Sale del destino (entregado)
        else if (oldStatus == PackageStatus.ARRIVED && newStatus == PackageStatus.DELIVERED) {
          updateAirportCapacity(order.getDestination(), -1);
        }
      } catch (Exception e) {
        System.err.println("Error updating warehouse capacity for order " + order.getId() + ": " + e.getMessage());
        // No re-lanzamos para no detener la simulación
      }
    }

    private void updateAirportCapacity(com.system.morapack.dao.morapack_psql.model.City city, int change) {
      if (city == null)
        return;

      List<com.system.morapack.dao.morapack_psql.model.Airport> airports = airportRepository
          .findByCity_Id(city.getId());
      if (airports.isEmpty())
        return;

      // Asumimos el primer aeropuerto de la ciudad
      com.system.morapack.dao.morapack_psql.model.Airport airport = airports.get(0);

      if (airport.getWarehouse() != null) {
        if (change > 0) {
          warehouseService.allocate(airport.getWarehouse().getId(), change);
          System.out.println(
              "Warehouse " + airport.getWarehouse().getId() + " (City " + city.getName() + "): Allocated " + change);
        } else {
          warehouseService.release(airport.getWarehouse().getId(), Math.abs(change));
          System.out.println("Warehouse " + airport.getWarehouse().getId() + " (City " + city.getName() + "): Released "
              + Math.abs(change));
        }
      }
    }

    /**
     * Calcula estado de orden basándose en sus productos ya cargados
     * OPTIMIZATION: Uses already-loaded products from JOIN FETCH (no DB query)
     */
    private PackageStatus calculateOrderStatusFromLoadedProducts(Order order) {
        // OPTIMIZATION: Products already loaded via JOIN FETCH
        List<Product> products = order.getProducts();

        if (products == null || products.isEmpty()) {
            return PackageStatus.PENDING;
        }

        boolean allDelivered = true;
        boolean anyInTransit = false;
        boolean anyArrived = false;

        for (Product product : products) {
            PackageStatus status = product.getStatus();

            if (status != PackageStatus.DELIVERED) {
                allDelivered = false;
            }
            if (status == PackageStatus.IN_TRANSIT) {
                anyInTransit = true;
            }
            if (status == PackageStatus.ARRIVED) {
                anyArrived = true;
            }
        }

        if (allDelivered) {
            return PackageStatus.DELIVERED;
        } else if (anyArrived) {
            return PackageStatus.ARRIVED;
        } else if (anyInTransit) {
            return PackageStatus.IN_TRANSIT;
        } else {
            return PackageStatus.PENDING;
        }
    }

    /**
     * Calcula estado de orden basándose en sus productos (legacy method, fetches from DB)
     * @deprecated Use calculateOrderStatusFromLoadedProducts when products are already loaded
     */
    @Deprecated
    private PackageStatus calculateOrderStatus(Order order) {
        // Obtener productos de la orden (DB query)
        List<Product> products = productService.getProductsByOrder(order.getId());

        if (products == null || products.isEmpty()) {
            return PackageStatus.PENDING;
        }

        boolean allDelivered = true;
        boolean anyInTransit = false;
        boolean anyArrived = false;

        for (Product product : products) {
            PackageStatus status = product.getStatus();

            if (status != PackageStatus.DELIVERED) {
                allDelivered = false;
            }
            if (status == PackageStatus.IN_TRANSIT) {
                anyInTransit = true;
            }
            if (status == PackageStatus.ARRIVED) {
                anyArrived = true;
            }
        }

        if (allDelivered) {
            return PackageStatus.DELIVERED;
        } else if (anyArrived) {
            return PackageStatus.ARRIVED;
        } else if (anyInTransit) {
            return PackageStatus.IN_TRANSIT;
        } else {
            return PackageStatus.PENDING;
        }
    }

    /**
     * Clase para estadísticas de actualización
     */
    public static class SimulationUpdateStats {
        private int pendingToInTransit = 0;
        private int inTransitToArrived = 0;
        private int arrivedToDelivered = 0;
        private int noChange = 0;

        public void recordTransition(PackageStatus from, PackageStatus to) {
            if (from == PackageStatus.PENDING && to == PackageStatus.IN_TRANSIT) {
                pendingToInTransit++;
            } else if (from == PackageStatus.IN_TRANSIT && to == PackageStatus.ARRIVED) {
                inTransitToArrived++;
            } else if (from == PackageStatus.ARRIVED && to == PackageStatus.DELIVERED) {
                arrivedToDelivered++;
            }
        }

        public void print() {
            System.out.println("State transitions:");
            System.out.println("  PENDING → IN_TRANSIT: " + pendingToInTransit);
            System.out.println("  IN_TRANSIT → ARRIVED: " + inTransitToArrived);
            System.out.println("  ARRIVED → DELIVERED: " + arrivedToDelivered);
            System.out.println("Total transitions: " + getTotalTransitions());
        }

        public int getTotalTransitions() {
            return pendingToInTransit + inTransitToArrived + arrivedToDelivered;
        }

        // Getters
        public int getPendingToInTransit() { return pendingToInTransit; }
        public int getInTransitToArrived() { return inTransitToArrived; }
        public int getArrivedToDelivered() { return arrivedToDelivered; }
    }

    /**
     * Calculate capacity statistics with caching to improve performance
     * OPTIMIZATION: Caches results for 5 minutes to avoid expensive aggregations on every call
     */
    public SimulationAPI.CapacityStats calculateCapacityStats() {
        LocalDateTime now = LocalDateTime.now();

        // OPTIMIZATION: Only recalculate every 5 minutes
        if (cachedCapacityStats != null && lastCapacityCalculation != null &&
            lastCapacityCalculation.plusMinutes(5).isAfter(now)) {
            System.out.println("Using cached capacity stats (age: " +
                java.time.Duration.between(lastCapacityCalculation, now).toSeconds() + "s)");
            return cachedCapacityStats;
        }

        System.out.println("Calculating fresh capacity stats...");
        double used = productRepository.sumUsedCapacity();
        double total = flightRepository.sumTotalCapacity();

        cachedCapacityStats = new SimulationAPI.CapacityStats(used, total);
        lastCapacityCalculation = now;

        return cachedCapacityStats;
    }

}
