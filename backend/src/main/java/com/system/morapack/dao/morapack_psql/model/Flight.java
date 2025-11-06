package com.system.morapack.dao.morapack_psql.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter @Setter @Builder
@AllArgsConstructor @NoArgsConstructor
@Entity
@Table(name = "flights")
public class Flight {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false)
  private Integer id;

  @Column(name = "code", nullable = false, length = 64)
  private String code;

  @Column(name = "route_type", nullable = false, length = 64)
  private String routeType;

  @Column(name = "max_capacity", nullable = false)
  private Integer maxCapacity;

  @Column(name = "transport_time_days", nullable = false)
  private Double transportTimeDays;

  @Column(name = "daily_frequency", nullable = false)
  private Integer dailyFrequency;

  @Column(name = "status", nullable = false, length = 64)
  private String status;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @ManyToOne(fetch = FetchType.LAZY, optional = true)
  @JoinColumn(name = "airplane_id", nullable = true)
  private Airplane airplane;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "origin_airport_id", nullable = false)
  private Airport originAirport;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "destination_airport_id", nullable = false)
  private Airport destinationAirport;

  @OneToMany(mappedBy = "assignedFlight", cascade = CascadeType.ALL, orphanRemoval = false)
  @Builder.Default
  private List<Order> orders = new ArrayList<>();
}
