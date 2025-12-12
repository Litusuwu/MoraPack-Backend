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
  List<Order> findByNameIn(List<String> names);
  List<Order> findByCustomer_Id(Integer customerId);
  List<Order> findByStatus(PackageStatus status);
  List<Order> findByDeliveryDateBetween(LocalDateTime start, LocalDateTime end);
  List<Order> findByIdIn(List<Integer> ids);
  boolean existsByName(String name);

  @Modifying
  @Query("DELETE FROM Order o WHERE o.id IN :ids")
  void deleteAllByIdIn(List<Integer> ids);

  // Performance optimization: Load orders with products in one query (prevents N+1)
  @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.products WHERE o.status IN :statuses")
  List<Order> findByStatusInWithProducts(@org.springframework.data.repository.query.Param("statuses") List<PackageStatus> statuses);

  // Find oldest order by creation date (for simulation start date calculation)
  Optional<Order> findTopByOrderByCreationDateAsc();

  // Native delete all - doesn't load entities into memory (prevents OOM)
  @Modifying
  @Query(value = "DELETE FROM orders", nativeQuery = true)
  void deleteAllNative();
  
  // Batch update status for multiple orders at once (prevents deadlocks)
  @Modifying
  @Query("UPDATE Order o SET o.status = :status, o.updatedAt = CURRENT_TIMESTAMP WHERE o.id IN :ids")
  void batchUpdateStatus(@org.springframework.data.repository.query.Param("status") PackageStatus status, 
                         @org.springframework.data.repository.query.Param("ids") List<Integer> ids);
}
