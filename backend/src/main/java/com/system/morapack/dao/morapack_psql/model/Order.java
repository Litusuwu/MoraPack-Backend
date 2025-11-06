package com.system.morapack.dao.morapack_psql.model;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import com.system.morapack.schemas.PackageStatus;

import jakarta.persistence.*;
import lombok.*;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "orders")
public class Order {
  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "order_seq")
  @SequenceGenerator(name = "order_seq", sequenceName = "orders_id_seq", allocationSize = 50)
  @Column(name = "id", nullable = false)
  private Integer id;

  
  @Column(name = "name", nullable = false)
  private String name;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "origin_city_id", nullable = false)
  private City origin;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "destination_city_id", nullable = false)
  private City destination;

  @Column(name = "delivery_date", nullable = false)
  private LocalDateTime deliveryDate;

  @Builder.Default
  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false)
  private PackageStatus status = PackageStatus.PENDING;

  @Column(name = "pickup_time_hours", nullable = false)
  private Double pickupTimeHours;


  @Column(name = "creation_date", nullable = false)
  private LocalDateTime creationDate;

  @Column(name = "updated_at", nullable = false)
  @UpdateTimestamp
  private LocalDateTime updatedAt;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "customer_id", nullable = false)
  private Customer customer;
}
