package com.system.morapack.dao.morapack_psql.repository;

import com.system.morapack.dao.morapack_psql.model.Order;
import com.system.morapack.schemas.PackageStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Integer> {

  Optional<Order> findFirstByName(String name);
  List<Order> findByName(String name);
  List<Order> findByCustomer_Id(Integer customerId);
  List<Order> findByStatus(PackageStatus status);
  List<Order> findByDeliveryDateBetween(LocalDateTime start, LocalDateTime end);
  List<Order> findByIdIn(List<Integer> ids);
  boolean existsByName(String name);

  @Modifying
  @Query("DELETE FROM Order o WHERE o.id IN :ids")
  void deleteAllByIdIn(List<Integer> ids);
}
