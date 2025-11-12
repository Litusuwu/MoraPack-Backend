package com.system.morapack.dao.morapack_psql.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import com.system.morapack.schemas.PackageStatus;

import jakarta.persistence.*;
import lombok.*;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "products")
public class Product {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false)
  private Integer id;

  @Column(name = "name", nullable = false)
  private String name;

  @Column(name = "weight", nullable = false)
  private Double weight;

  @Column(name = "volume", nullable = false)
  private Double volume;

  @Column(name = "creation_date", nullable = false)
  @CreationTimestamp
  private LocalDateTime creationDate;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "order_id", nullable = false)
  private Order order;

  @Enumerated(EnumType.STRING)
  @Column(name = "status")
  private PackageStatus status;

  @Column(name = "assigned_flight", length = 1000)
  private String assignedFlight;

  /**
   * NEW: Specific flight instance assigned to this product (for multi-day simulations)
   * Format: "FL-{flightId}-DAY-{day}-{HHmm}" (e.g., "FL-123-DAY-1-0800")
   * This allows tracking which SPECIFIC departure the product is assigned to
   */
  @Column(name = "assigned_flight_instance", length = 100)
  private String assignedFlightInstance;

  /**
   * List of flights assigned to this product in sequence order
   * For direct flights: 1 entry
   * For multi-hop routes: multiple entries in order (e.g., LIM-AQP, AQP-CUZ)
   */
  @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("sequenceOrder ASC")
  @Builder.Default
  private List<ProductFlight> productFlights = new ArrayList<>();
}
