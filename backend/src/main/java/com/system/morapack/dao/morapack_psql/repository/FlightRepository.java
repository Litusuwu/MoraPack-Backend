package com.system.morapack.dao.morapack_psql.repository;

import com.system.morapack.dao.morapack_psql.model.Flight;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface FlightRepository extends JpaRepository<Flight, Integer> {

  List<Flight> findByIdIn(List<Integer> ids);

  Optional<Flight> findByCode(String code);
  boolean existsByCode(String code);

  List<Flight> findByAirplane_Id(Integer airplaneId);
  List<Flight> findByOriginAirport_Id(Integer originAirportId);
  List<Flight> findByDestinationAirport_Id(Integer destinationAirportId);

  List<Flight> findByStatus(String status);
  long countByStatus(String status);

  List<Flight> findByMaxCapacityGreaterThanEqual(Integer capacity);
  List<Flight> findByTransportTimeDaysBetween(Double min, Double max);
  List<Flight> findByDailyFrequencyBetween(Integer min, Integer max);
  List<Flight> findByCreatedAtBetween(LocalDateTime start, LocalDateTime end);

  @Modifying
  @Query("DELETE FROM Flight f WHERE f.id IN :ids")
  void deleteAllByIdIn(List<Integer> ids);
}
