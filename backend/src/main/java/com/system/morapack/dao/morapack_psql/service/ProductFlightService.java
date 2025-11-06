package com.system.morapack.dao.morapack_psql.service;

import com.system.morapack.dao.morapack_psql.model.ProductFlight;
import com.system.morapack.dao.morapack_psql.repository.ProductFlightRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service for querying ProductFlight junction table
 *
 * Note: ProductFlights are typically created via cascade when saving Products,
 * but this service provides query capabilities for analytics and reporting.
 */
@Service
@RequiredArgsConstructor
public class ProductFlightService {

    private final ProductFlightRepository repository;

    /**
     * Get all flights assigned to a product in sequence order
     */
    public List<ProductFlight> getFlightsForProduct(Integer productId) {
        return repository.findByProduct_IdOrderBySequenceOrderAsc(productId);
    }

    /**
     * Get all products using a specific flight
     */
    public List<ProductFlight> getProductsUsingFlight(Integer flightId) {
        return repository.findByFlight_Id(flightId);
    }

    /**
     * Get all flights for multiple products
     */
    public List<ProductFlight> getFlightsForProducts(List<Integer> productIds) {
        return repository.findByProduct_IdInOrderByProduct_IdAscSequenceOrderAsc(productIds);
    }

    /**
     * Count how many products are assigned to a specific flight
     */
    public long countProductsOnFlight(Integer flightId) {
        return repository.countByFlight_Id(flightId);
    }

    /**
     * Get IDs of all products that use multi-hop routes (more than one flight)
     */
    public List<Integer> getMultiHopProductIds() {
        return repository.findMultiHopProductIds();
    }
}
