package com.system.morapack.bll.adapter;

import com.system.morapack.dao.morapack_psql.model.Airport;
import com.system.morapack.dao.morapack_psql.model.City;
import com.system.morapack.dao.morapack_psql.model.Warehouse;
import com.system.morapack.dao.morapack_psql.service.AirportService;
import com.system.morapack.dao.morapack_psql.service.CityService;
import com.system.morapack.dao.morapack_psql.service.WarehouseService;
import com.system.morapack.schemas.AirportSchema;
import com.system.morapack.schemas.AirportState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class AirportAdapter {

  private final AirportService airportService;
  private final CityService cityService;
  private final WarehouseService warehouseService;
  private final CityAdapter cityAdapter;
  private final WarehouseAdapter warehouseAdapter;

  private AirportSchema mapToSchema(Airport airport) {
    AirportSchema.AirportSchemaBuilder builder = AirportSchema.builder()
        .id(airport.getId())
        .codeIATA(airport.getCodeIATA())
        .alias(airport.getAlias())
        .timezoneUTC(airport.getTimezoneUTC())
        .latitude(airport.getLatitude())
        .longitude(airport.getLongitude())
        .cityId(airport.getCity() != null ? airport.getCity().getId() : null)
        .cityName(airport.getCity() != null ? airport.getCity().getName() : null)
        .state(airport.getState())
        .warehouseId(airport.getWarehouse() != null ? airport.getWarehouse().getId() : null);

    // Include full CitySchema for legacy/algorithm compatibility
    if (airport.getCity() != null) {
      builder.citySchema(cityAdapter.getCity(airport.getCity().getId()));
    }

    // Include full WarehouseSchema with capacity data
    if (airport.getWarehouse() != null) {
      builder.warehouse(warehouseAdapter.getWarehouse(airport.getWarehouse().getId()));
    }

    return builder.build();
  }

  private Airport mapToEntity(AirportSchema schema) {
    Airport.AirportBuilder builder = Airport.builder()
        .id(schema.getId())
        .codeIATA(schema.getCodeIATA())
        .alias(schema.getAlias())
        .timezoneUTC(schema.getTimezoneUTC())
        .latitude(schema.getLatitude())
        .longitude(schema.getLongitude())
        .state(schema.getState());

    if (schema.getCityId() != null) {
      City city = cityService.getCity(schema.getCityId());
      builder.city(city);
    }

    if (schema.getWarehouseId() != null) {
      Warehouse warehouse = warehouseService.getWarehouse(schema.getWarehouseId());
      builder.warehouse(warehouse);
    }

    return builder.build();
  }

  public AirportSchema getAirport(Integer id) {
    Airport airport = airportService.getAirport(id);
    return mapToSchema(airport);
  }

  public List<AirportSchema> fetchAirports(List<Integer> ids) {
    return airportService.fetchAirports(ids).stream()
        .map(this::mapToSchema)
        .collect(Collectors.toList());
  }

  public AirportSchema getByCode(String codeIATA) {
    Airport airport = airportService.getByCode(codeIATA);
    return mapToSchema(airport);
  }

  public List<AirportSchema> getByCity(Integer cityId) {
    return airportService.getByCity(cityId).stream()
        .map(this::mapToSchema)
        .collect(Collectors.toList());
  }

  public List<AirportSchema> getByState(AirportState state) {
    return airportService.getByState(state).stream()
        .map(this::mapToSchema)
        .collect(Collectors.toList());
  }

  public List<AirportSchema> getByTimezoneRange(Integer minTz, Integer maxTz) {
    return airportService.getByTimezoneRange(minTz, maxTz).stream()
        .map(this::mapToSchema)
        .collect(Collectors.toList());
  }

  public AirportSchema createAirport(AirportSchema schema) {
    Airport entity = mapToEntity(schema);
    return mapToSchema(airportService.createAirport(entity));
  }

  public List<AirportSchema> bulkCreateAirports(List<AirportSchema> schemas) {
    List<Airport> entities = schemas.stream()
        .map(this::mapToEntity)
        .collect(Collectors.toList());
    return airportService.bulkCreateAirports(entities).stream()
        .map(this::mapToSchema)
        .collect(Collectors.toList());
  }

  public AirportSchema updateAirport(Integer id, AirportSchema updates) {
    Airport entityUpdates = mapToEntity(updates);
    return mapToSchema(airportService.updateAirport(id, entityUpdates));
  }

  public void deleteAirport(Integer id) {
    airportService.deleteAirport(id);
  }

  public void bulkDeleteAirports(List<Integer> ids) {
    airportService.bulkDeleteAirports(ids);
  }
}
