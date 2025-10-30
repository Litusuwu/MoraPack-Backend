package com.system.morapack.bll.controller;

import com.system.morapack.bll.adapter.FlightAdapter;
import com.system.morapack.schemas.FlightSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

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
}
