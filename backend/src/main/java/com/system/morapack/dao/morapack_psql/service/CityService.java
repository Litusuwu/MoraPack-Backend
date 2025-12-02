package com.system.morapack.dao.morapack_psql.service;

import com.system.morapack.dao.morapack_psql.model.City;
import com.system.morapack.dao.morapack_psql.repository.CityRepository;
import com.system.morapack.schemas.Continent;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CityService {

  private final CityRepository cityRepository;

  public City getCity(Integer id) {
    return cityRepository.findById(id)
        .orElseThrow(() -> new EntityNotFoundException("CitySchema not found with id: " + id));
  }

  public City getCityByName(String name) {
    return cityRepository.findByName(name)
        .orElse(null); // Return null if not found (to match getFlightByCode behavior)
  }

  public List<City> fetchCities(List<Integer> ids) {
    if (ids == null || ids.isEmpty()) return cityRepository.findAll();
    return cityRepository.findByIdIn(ids);
  }

  public City createCity(City city) {
    // Usa nombre+continente para evitar duplicados razonables.
    if (cityRepository.existsByNameAndContinent(city.getName(), city.getContinent())) {
      throw new IllegalArgumentException("CitySchema already exists: " + city.getName() + " in " + city.getContinent());
    }
    return cityRepository.save(city);
  }

  public List<City> bulkCreateCities(List<City> cities) {
    return cityRepository.saveAll(cities);
  }

  public City updateCity(Integer id, City updates) {
    City city = getCity(id);

    if (updates.getName() != null) city.setName(updates.getName());
    if (updates.getContinent() != null) city.setContinent(updates.getContinent());

    return cityRepository.save(city);
  }

  public void deleteCity(Integer id) {
    if (!cityRepository.existsById(id)) {
      throw new EntityNotFoundException("CitySchema not found with id: " + id);
    }
    cityRepository.deleteById(id);
  }

  @Transactional
  public void bulkDeleteCities(List<Integer> ids) {
    cityRepository.deleteAllByIdIn(ids);
  }

  public List<City> getByContinent(Continent continent) {
    return cityRepository.findByContinent(continent);
  }
}
