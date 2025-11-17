package com.system.morapack.dao.morapack_psql.service;

import com.system.morapack.dao.morapack_psql.model.Flight;
import com.system.morapack.dao.morapack_psql.repository.FlightRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class FlightService {

  private final FlightRepository repository;

  public Flight get(Integer id) {
    return repository.findById(id)
        .orElseThrow(() -> new EntityNotFoundException("FlightSchema not found with id: " + id));
  }

  public List<Flight> fetch(List<Integer> ids) {
    if (ids == null || ids.isEmpty()) return repository.findAll();
    return repository.findByIdIn(ids);
  }

  public Flight create(Flight f) {
    if (f.getCreatedAt() == null) f.setCreatedAt(LocalDateTime.now());
    validate(f);
    if (repository.existsByCode(f.getCode()))
      throw new IllegalArgumentException("FlightSchema code already exists: " + f.getCode());
    return repository.save(f);
  }

  public List<Flight> bulkCreate(List<Flight> list) {
    list.forEach(f -> { if (f.getCreatedAt() == null) f.setCreatedAt(LocalDateTime.now()); validate(f); });
    return repository.saveAll(list);
  }

  @Transactional
  public Flight update(Integer id, Flight patch) {
    Flight f = get(id);

    if (patch.getCode() != null) f.setCode(patch.getCode());
    if (patch.getRouteType() != null) f.setRouteType(patch.getRouteType());
    if (patch.getMaxCapacity() != null) f.setMaxCapacity(patch.getMaxCapacity());
    if (patch.getTransportTimeDays() != null) f.setTransportTimeDays(patch.getTransportTimeDays());
    if (patch.getDailyFrequency() != null) f.setDailyFrequency(patch.getDailyFrequency());
    if (patch.getStatus() != null) f.setStatus(patch.getStatus());
    if (patch.getCreatedAt() != null) f.setCreatedAt(patch.getCreatedAt());
    if (patch.getAirplane() != null) f.setAirplane(patch.getAirplane());
    if (patch.getOriginAirport() != null) f.setOriginAirport(patch.getOriginAirport());
    if (patch.getDestinationAirport() != null) f.setDestinationAirport(patch.getDestinationAirport());

    validate(f);

    repository.findByCode(f.getCode())
        .filter(existing -> !existing.getId().equals(f.getId()))
        .ifPresent(existing -> { throw new IllegalArgumentException("FlightSchema code already exists: " + f.getCode()); });

    return repository.save(f);
  }

  public void delete(Integer id) {
    if (!repository.existsById(id))
      throw new EntityNotFoundException("FlightSchema not found with id: " + id);
    repository.deleteById(id);
  }

  @Transactional
  public void bulkDelete(List<Integer> ids) {
    repository.deleteAllByIdIn(ids);
  }

  public Flight getFlightByCode(String code) {
    return repository.findByCode(code)
        .orElse(null);
  }

  // Queries
  public List<Flight> getByAirplane(Integer airplaneId) { return repository.findByAirplane_Id(airplaneId); }
  public List<Flight> getByOrigin(Integer originAirportId) { return repository.findByOriginAirport_Id(originAirportId); }
  public List<Flight> getByDestination(Integer destinationAirportId) { return repository.findByDestinationAirport_Id(destinationAirportId); }
  public List<Flight> getByStatus(String status) { return repository.findByStatus(status); }
  public List<Flight> getByCapacityAtLeast(Integer capacity) { return repository.findByMaxCapacityGreaterThanEqual(capacity); }
  public List<Flight> getByTransportTimeRange(Double min, Double max) { return repository.findByTransportTimeDaysBetween(min, max); }
  public List<Flight> getByDailyFrequencyRange(Integer min, Integer max) { return repository.findByDailyFrequencyBetween(min, max); }
  public List<Flight> getByCreatedAtRange(LocalDateTime start, LocalDateTime end) { return repository.findByCreatedAtBetween(start, end); }

  // Count methods
  public long countAll() { return repository.count(); }
  public long countByStatus(String status) { return repository.countByStatus(status); }

  // Rules
  private void validate(Flight f) {
    if (f.getCode() == null || f.getCode().isBlank()) throw new IllegalArgumentException("code required");
    if (f.getRouteType() == null || f.getRouteType().isBlank()) throw new IllegalArgumentException("routeType required");
    if (f.getMaxCapacity() == null || f.getMaxCapacity() < 0) throw new IllegalArgumentException("maxCapacity invalid");
    if (f.getTransportTimeDays() == null || f.getTransportTimeDays() < 0) throw new IllegalArgumentException("transportTimeDays invalid");
    if (f.getDailyFrequency() == null || f.getDailyFrequency() <= 0) throw new IllegalArgumentException("dailyFrequency must be > 0");
    if (f.getStatus() == null || f.getStatus().isBlank()) throw new IllegalArgumentException("status required");
    if (f.getCreatedAt() == null) throw new IllegalArgumentException("createdAt required");
    if (f.getAirplane() == null) throw new IllegalArgumentException("airplane required");
    if (f.getOriginAirport() == null) throw new IllegalArgumentException("originAirport required");
    if (f.getDestinationAirport() == null) throw new IllegalArgumentException("destinationAirport required");
    if (f.getOriginAirport() != null && f.getDestinationAirport() != null
        && f.getOriginAirport().getId() != null && f.getDestinationAirport().getId() != null
        && f.getOriginAirport().getId().equals(f.getDestinationAirport().getId()))
      throw new IllegalArgumentException("origin and destination must differ");
  }
}
