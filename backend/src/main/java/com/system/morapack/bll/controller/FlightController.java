package com.system.morapack.bll.controller;

import com.system.morapack.bll.adapter.FlightAdapter;
import com.system.morapack.bll.dto.AirportLocationDTO;
import com.system.morapack.bll.dto.CityDTO;
import com.system.morapack.bll.dto.FlightInstanceDTO;
import com.system.morapack.dao.morapack_psql.model.Flight;
import com.system.morapack.dao.morapack_psql.model.Product;
import com.system.morapack.dao.morapack_psql.repository.FlightRepository;
import com.system.morapack.dao.morapack_psql.repository.ProductRepository;
import com.system.morapack.schemas.FlightSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class FlightController {

  private final FlightAdapter flightAdapter;

  public FlightSchema getFlight(Integer id) {
    return flightAdapter.getFlight(id);
  }

  public List<FlightSchema> fetchFlights(List<Integer> ids) {
    return flightAdapter.fetchFlights(ids);
  }

  public List<FlightSchema> getByAirplane(Integer airplaneId) {
    return flightAdapter.getByAirplane(airplaneId);
  }

  public List<FlightSchema> getByOrigin(Integer originAirportId) {
    return flightAdapter.getByOrigin(originAirportId);
  }

  public List<FlightSchema> getByDestination(Integer destinationAirportId) {
    return flightAdapter.getByDestination(destinationAirportId);
  }

  public List<FlightSchema> getByStatus(String status) {
    return flightAdapter.getByStatus(status);
  }

  public List<FlightSchema> getByCapacityAtLeast(Integer capacity) {
    return flightAdapter.getByCapacityAtLeast(capacity);
  }

  public List<FlightSchema> getByTransportTimeRange(Double min, Double max) {
    return flightAdapter.getByTransportTimeRange(min, max);
  }

  public List<FlightSchema> getByDailyFrequencyRange(Integer min, Integer max) {
    return flightAdapter.getByDailyFrequencyRange(min, max);
  }

  public List<FlightSchema> getByCreatedAtRange(LocalDateTime start, LocalDateTime end) {
    return flightAdapter.getByCreatedAtRange(start, end);
  }

  public FlightSchema createFlight(FlightSchema request) {
    return flightAdapter.createFlight(request);
  }

  public List<FlightSchema> bulkCreateFlights(List<FlightSchema> requests) {
    return flightAdapter.bulkCreateFlights(requests);
  }

  public FlightSchema updateFlight(Integer id, FlightSchema request) {
    return flightAdapter.updateFlight(id, request);
  }

  public void deleteFlight(Integer id) {
    flightAdapter.deleteFlight(id);
  }

  public void bulkDeleteFlights(List<Integer> ids) {
    flightAdapter.bulkDeleteFlights(ids);
  }

  public long countAllFlights() {
    return flightAdapter.countAllFlights();
  }

  public long countFlightsByStatus(String status) {
    return flightAdapter.countFlightsByStatus(status);
  }

  @Autowired
  private ProductRepository productRepository;

  @Autowired
  private FlightRepository flightRepository;

  public List<FlightInstanceDTO> getFlightInstances(LocalDateTime startDate, LocalDateTime endDate) {

    System.out.println("========================================");
    System.out.println("API: GET FLIGHT INSTANCES");
    System.out.println("Start: " + startDate);
    System.out.println("End: " + endDate);
    System.out.println("========================================");


    List<Product> products = productRepository.findProductsWithFlightInTimeRange(startDate, endDate);

    System.out.println("Products found in range: " + products.size());

    Map<String, FlightInstanceDTO> instanceMap = new HashMap<>();

    for (Product product : products) {
      String flightInstanceCode = product.getAssignedFlightInstance();
      if (flightInstanceCode == null || flightInstanceCode.isEmpty()) continue;

      // Extraer ID del vuelo (ej: "FL-40847-DAY-1-0800" → "40847")
      String[] parts = flightInstanceCode.split("-");
      if (parts.length < 2) {
        System.err.println("WARNING: Invalid flight instance format: " + flightInstanceCode);
        continue;
      }

      Integer flightId;
      try {
        flightId = Integer.parseInt(parts[1]); // "40847"
      } catch (NumberFormatException e) {
        System.err.println("WARNING: Invalid flight ID in: " + flightInstanceCode);
        continue;
      }

      // ✅ Calcular pickupTime correctamente
      Double pickupTimeHours = product.getOrder().getPickupTimeHours();
      if (pickupTimeHours == null) continue;

      LocalDateTime creationDate = product.getOrder().getCreationDate();
      LocalDateTime pickupTime = creationDate.plusHours(pickupTimeHours.longValue());

      String key = flightInstanceCode;

      if (!instanceMap.containsKey(key)) {
        // ✅ Buscar vuelo por ID
        Flight flight = flightRepository.findById(flightId).orElse(null);

        if (flight == null) {
          System.err.println("WARNING: Flight not found for ID: " + flightId);
          continue;
        }

        FlightInstanceDTO instance = new FlightInstanceDTO();
        instance.setId(flightInstanceCode);
        instance.setFlightId(flight.getId());
        instance.setFlightCode(flight.getCode());
        instance.setDepartureTime(pickupTime);
        instance.setArrivalTime(
                pickupTime.plusHours((long) (flight.getTransportTimeDays() * 24))
        );

        // IDs planos (los sigues exponiendo)
        instance.setOriginAirportId(flight.getOriginAirport().getId());
        instance.setDestinationAirportId(flight.getDestinationAirport().getId());

        // ==========================
        //  ORIGIN AIRPORT ANIDADO
        // ==========================
        AirportLocationDTO origin = new AirportLocationDTO();
        origin.setCodeIATA(flight.getOriginAirport().getCodeIATA());

        CityDTO originCity = new CityDTO();
        originCity.setName(flight.getOriginAirport().getCity().getName());
        origin.setCity(originCity);

        origin.setLatitude(
                Double.parseDouble(flight.getOriginAirport().getLatitude())
        );
        origin.setLongitude(
                Double.parseDouble(flight.getOriginAirport().getLongitude())
        );

        // ==============================
        //  DESTINATION AIRPORT ANIDADO
        // ==============================
        AirportLocationDTO destination = new AirportLocationDTO();
        destination.setCodeIATA(flight.getDestinationAirport().getCodeIATA());

        CityDTO destCity = new CityDTO();
        destCity.setName(flight.getDestinationAirport().getCity().getName());
        destination.setCity(destCity);

        destination.setLatitude(
                Double.parseDouble(flight.getDestinationAirport().getLatitude())
        );
        destination.setLongitude(
                Double.parseDouble(flight.getDestinationAirport().getLongitude())
        );

        // Asignar en la instancia
        instance.setOriginAirport(origin);
        instance.setDestinationAirport(destination);

        instance.setAssignedProducts(0);
        instance.setStatus("SCHEDULED");

        instanceMap.put(key, instance);
      }
    }
    System.out.println("Flight instances built: " + instanceMap.size());
    return new ArrayList<>(instanceMap.values());
  }


}
