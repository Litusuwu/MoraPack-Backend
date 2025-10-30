package com.system.morapack.bll.adapter;

import com.system.morapack.dao.morapack_psql.model.City;
import com.system.morapack.dao.morapack_psql.service.CityService;
import com.system.morapack.schemas.CitySchema;
import com.system.morapack.schemas.Continent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class CityAdapter {

  private final CityService cityService;

  private CitySchema mapToSchema(City city) {
    return CitySchema.builder()
        .id(city.getId())
        .name(city.getName())
        .country(city.getCountry())
        .continent(city.getContinent())
        .build();
  }

  private City mapToEntity(CitySchema schema) {
    return City.builder()
        .id(schema.getId())
        .name(schema.getName())
        .country(schema.getCountry())
        .continent(schema.getContinent())
        .build();
  }

  public CitySchema getCity(Integer id) {
    return mapToSchema(cityService.getCity(id));
  }

  public List<CitySchema> fetchCities(List<Integer> ids) {
    return cityService.fetchCities(ids).stream()
        .map(this::mapToSchema)
        .collect(Collectors.toList());
  }

  public CitySchema getByName(String name) {
    // Si tienes CityService.getByName(), úsalo. Mientras tanto, filtramos.
    return cityService.fetchCities(null).stream()
        .filter(c -> c.getName().equals(name))
        .findFirst()
        .map(this::mapToSchema)
        .orElse(null);
  }

  public List<CitySchema> getByContinent(Continent continent) {
    return cityService.getByContinent(continent).stream()
        .map(this::mapToSchema)
        .collect(Collectors.toList());
  }

  public CitySchema createCity(CitySchema schema) {
    return mapToSchema(cityService.createCity(mapToEntity(schema)));
  }

  public List<CitySchema> bulkCreateCities(List<CitySchema> schemas) {
    var entities = schemas.stream().map(this::mapToEntity).collect(Collectors.toList());
    return cityService.bulkCreateCities(entities).stream()
        .map(this::mapToSchema)
        .collect(Collectors.toList());
  }

  public CitySchema updateCity(Integer id, CitySchema updates) {
    return mapToSchema(cityService.updateCity(id, mapToEntity(updates)));
  }

  public void deleteCity(Integer id) {
    cityService.deleteCity(id);
  }

  public void bulkDeleteCities(List<Integer> ids) {
    cityService.bulkDeleteCities(ids);
  }
}