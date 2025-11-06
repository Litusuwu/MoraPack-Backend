package com.system.morapack.dao.morapack_psql.repository;

import com.system.morapack.dao.morapack_psql.model.ProductFlight;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductFlightRepository extends JpaRepository<ProductFlight, Integer> {

    /**
     * Find all product flights for a specific product, ordered by sequence
     */
    List<ProductFlight> findByProduct_IdOrderBySequenceOrderAsc(Integer productId);

    /**
     * Find all products using a specific flight
     */
    List<ProductFlight> findByFlight_Id(Integer flightId);

    /**
     * Find all product flights for multiple products
     */
    List<ProductFlight> findByProduct_IdInOrderByProduct_IdAscSequenceOrderAsc(List<Integer> productIds);

    /**
     * Count how many products are assigned to a specific flight
     */
    long countByFlight_Id(Integer flightId);

    /**
     * Get all multi-hop products (products with more than one flight)
     */
    @Query("SELECT pf.product.id FROM ProductFlight pf " +
           "GROUP BY pf.product.id " +
           "HAVING COUNT(pf) > 1")
    List<Integer> findMultiHopProductIds();
}
