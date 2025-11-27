package com.system.morapack.dao.morapack_psql.repository;

import com.system.morapack.dao.morapack_psql.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import org.springframework.data.repository.query.Param;

@Repository
public interface ProductRepository extends JpaRepository<Product, Integer> {
  List<Product> findByNameContainingIgnoreCase(String name);
  List<Product> findByOrder_Id(Integer orderId);
  List<Product> findByIdIn(List<Integer> ids);

  @Query("SELECT COUNT(p) FROM Product p WHERE p.assignedFlightInstance LIKE CONCAT(:flightCode, '%')")
  long countByFlightCode(@Param("flightCode") String flightCode);

  @Transactional
  void deleteByOrder_Id(Integer orderId);

  @Transactional
  void deleteByOrder_IdIn(List<Integer> orderIds);

  boolean existsByName(String name);
  @Modifying
  @Query("DELETE FROM Product p WHERE p.id IN :ids")
  void deleteAllByIdIn(List<Integer> ids);

  @Query("SELECT COALESCE(SUM(p.weight), 0) FROM Product p WHERE p.assignedFlightInstance IS NOT NULL")
  double sumUsedCapacity();


}