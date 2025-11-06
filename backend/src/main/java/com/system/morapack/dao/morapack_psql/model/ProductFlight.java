package com.system.morapack.dao.morapack_psql.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Junction entity representing the many-to-many relationship between Products and Flights
 * Supports multi-hop routes by tracking the sequence order of flights
 *
 * Example:
 * Product traveling LIM → AQP → CUZ would have two ProductFlight records:
 * - ProductFlight(product_id=1, flight_id=5, sequence_order=1) for LIM-AQP
 * - ProductFlight(product_id=1, flight_id=8, sequence_order=2) for AQP-CUZ
 */
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "product_flights")
public class ProductFlight {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "flight_id", nullable = false)
    private Flight flight;

    /**
     * Order of this flight in the product's route
     * For single-hop: always 1
     * For multi-hop: 1, 2, 3, etc. in sequence
     */
    @Column(name = "sequence_order", nullable = false)
    private Integer sequenceOrder;

    @Column(name = "created_at", nullable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;
}
