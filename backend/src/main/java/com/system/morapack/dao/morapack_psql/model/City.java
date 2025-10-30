package com.system.morapack.dao.morapack_psql.model;

import com.system.morapack.schemas.Continent;
import jakarta.persistence.*;
import lombok.*;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "cities")
public class City {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false)
  private Integer id;

  @Column(name = "name", nullable = false, length = 120)
  private String name;

  @Column(name = "country", length = 120)
  private String country;

  @Enumerated(EnumType.STRING)
  @Column(name = "continent", nullable = false, length = 32)
  private Continent continent;
}
