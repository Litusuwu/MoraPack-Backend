package com.system.morapack.bll.adapter;

import com.system.morapack.dao.morapack_psql.model.Airplane;
import com.system.morapack.dao.morapack_psql.model.Airport;
import com.system.morapack.dao.morapack_psql.model.Flight;
import com.system.morapack.dao.morapack_psql.service.AirplaneService;
import com.system.morapack.dao.morapack_psql.service.AirportService;
import com.system.morapack.dao.morapack_psql.service.FlightService;
import com.system.morapack.schemas.FlightSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class FlightAdapter {

  private final FlightService flightService;
  private final AirportService airportService;
  private final AirplaneService airplaneService;

  private FlightSchema mapToSchema(Flight flight) {
    return FlightSchema.builder()
        .id(flight.getId())
        .code(flight.getCode())
        .routeType(flight.getRouteType())
        .maxCapacity(flight.getMaxCapacity())
        .transportTimeDays(flight.getTransportTimeDays())
        .dailyFrequency(flight.getDailyFrequency())
        .status(flight.getStatus())
        .createdAt(flight.getCreatedAt())
        .airplaneId(flight.getAirplane() != null ? flight.getAirplane().getId() : null)
        .originAirportId(flight.getOriginAirport() != null ? flight.getOriginAirport().getId() : null)
        .originAirportCode(flight.getOriginAirport() != null ? flight.getOriginAirport().getCodeIATA() : null)
        .destinationAirportId(flight.getDestinationAirport() != null ? flight.getDestinationAirport().getId() : null)
        .destinationAirportCode(flight.getDestinationAirport() != null ? flight.getDestinationAirport().getCodeIATA() : null)
        .build();
  }

  private Flight mapToEntity(FlightSchema schema) {
    Flight.FlightBuilder builder = Flight.builder()
        .id(schema.getId())
        .code(schema.getCode())
        .routeType(schema.getRouteType())
        .maxCapacity(schema.getMaxCapacity())
        .transportTimeDays(schema.getTransportTimeDays())
        .dailyFrequency(schema.getDailyFrequency())
        .status(schema.getStatus())
        .createdAt(schema.getCreatedAt());

    if (schema.getAirplaneId() != null) {
      Airplane airplane = airplaneService.get(schema.getAirplaneId());
      builder.airplane(airplane);
    }

    if (schema.getOriginAirportId() != null) {
      Airport origin = airportService.getAirport(schema.getOriginAirportId());
      builder.originAirport(origin);
    }

    if (schema.getDestinationAirportId() != null) {
      Airport destination = airportService.getAirport(schema.getDestinationAirportId());
      builder.destinationAirport(destination);
    }

    return builder.build();
  }

  public FlightSchema getFlight(Integer id) {
    Flight flight = flightService.get(id);
    return mapToSchema(flight);
  }

  public List<FlightSchema> fetchFlights(List<Integer> ids) {
    return flightService.fetch(ids).stream()
        .map(this::mapToSchema)
        .collect(Collectors.toList());
  }

  public List<FlightSchema> getByAirplane(Integer airplaneId) {
    return flightService.getByAirplane(airplaneId).stream()
        .map(this::mapToSchema)
        .collect(Collectors.toList());
  }

  public List<FlightSchema> getByOrigin(Integer originAirportId) {
    return flightService.getByOrigin(originAirportId).stream()
        .map(this::mapToSchema)
        .collect(Collectors.toList());
  }

  public List<FlightSchema> getByDestination(Integer destinationAirportId) {
    return flightService.getByDestination(destinationAirportId).stream()
        .map(this::mapToSchema)
        .collect(Collectors.toList());
  }

  public List<FlightSchema> getByStatus(String status) {
    return flightService.getByStatus(status).stream()
        .map(this::mapToSchema)
        .collect(Collectors.toList());
  }

  public List<FlightSchema> getByCapacityAtLeast(Integer capacity) {
    return flightService.getByCapacityAtLeast(capacity).stream()
        .map(this::mapToSchema)
        .collect(Collectors.toList());
  }

  public List<FlightSchema> getByTransportTimeRange(Double min, Double max) {
    return flightService.getByTransportTimeRange(min, max).stream()
        .map(this::mapToSchema)
        .collect(Collectors.toList());
  }

  public List<FlightSchema> getByDailyFrequencyRange(Integer min, Integer max) {
    return flightService.getByDailyFrequencyRange(min, max).stream()
        .map(this::mapToSchema)
        .collect(Collectors.toList());
  }

  public List<FlightSchema> getByCreatedAtRange(LocalDateTime start, LocalDateTime end) {
    return flightService.getByCreatedAtRange(start, end).stream()
        .map(this::mapToSchema)
        .collect(Collectors.toList());
  }

  public FlightSchema createFlight(FlightSchema schema) {
    Flight entity = mapToEntity(schema);
    return mapToSchema(flightService.create(entity));
  }

  public List<FlightSchema> bulkCreateFlights(List<FlightSchema> schemas) {
    List<Flight> entities = schemas.stream()
        .map(this::mapToEntity)
        .collect(Collectors.toList());
    return flightService.bulkCreate(entities).stream()
        .map(this::mapToSchema)
        .collect(Collectors.toList());
  }

  public FlightSchema updateFlight(Integer id, FlightSchema updates) {
    Flight entityUpdates = mapToEntity(updates);
    return mapToSchema(flightService.update(id, entityUpdates));
  }

  public void deleteFlight(Integer id) {
    flightService.delete(id);
  }

  public void bulkDeleteFlights(List<Integer> ids) {
    flightService.bulkDelete(ids);
  }

  public long countAllFlights() {
    return flightService.countAll();
  }

  public long countFlightsByStatus(String status) {
    return flightService.countByStatus(status);
  }
}
