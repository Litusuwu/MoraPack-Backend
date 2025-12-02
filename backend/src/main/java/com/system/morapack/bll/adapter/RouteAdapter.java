package com.system.morapack.bll.adapter;

import com.system.morapack.dao.morapack_psql.model.*;
import com.system.morapack.dao.morapack_psql.service.*;
import com.system.morapack.schemas.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class RouteAdapter {

  private final RouteService routeService;
  private final CityService cityService;
  private final FlightService flightService;
  private final OrderService orderService;

  // === Mapping ===
  private RouteSchema toSchema(Route r) {
    RouteSchema s = new RouteSchema();
    s.setId(r.getId());
    s.setTotalTime(r.getTotalTime());
    s.setTotalCost(r.getTotalCost());

    // Cities
    CitySchema o = new CitySchema();
    if (r.getOriginCity() != null) {
      o.setId(r.getOriginCity().getId());
      o.setName(r.getOriginCity().getName());
      o.setContinent(r.getOriginCity().getContinent());
      s.setOriginCitySchema(o);
    }
    CitySchema d = new CitySchema();
    if (r.getDestinationCity() != null) {
      d.setId(r.getDestinationCity().getId());
      d.setName(r.getDestinationCity().getName());
      d.setContinent(r.getDestinationCity().getContinent());
      s.setDestinationCitySchema(d);
    }

    // Flights -> FlightSchema minimal por id
    if (r.getFlights() != null) {
      List<FlightSchema> fs = r.getFlights().stream().map(f -> {
        FlightSchema fx = new FlightSchema();
        fx.setId(f.getId());
        return fx;
      }).toList();
      s.setFlightSchemas(fs);
    }

    // Orders -> OrderSchema minimal por id
    if (r.getPackages() != null) {
      List<OrderSchema> os = r.getPackages().stream().map(p -> {
        OrderSchema ox = new OrderSchema();
        ox.setId(p.getId());
        return ox;
      }).toList();
      s.setOrderSchemas(os);
    }

    return s;
  }

  private Route toEntity(RouteSchema s) {
    Route.RouteBuilder b = Route.builder()
        .id(s.getId())
        .totalTime(s.getTotalTime())
        .totalCost(s.getTotalCost());

    // FIX: Cities - use NAME instead of ID (schema IDs don't match DB IDs)
    if (s.getOriginCitySchema() != null && s.getOriginCitySchema().getName() != null) {
      City originCity = cityService.getCityByName(s.getOriginCitySchema().getName());
      if (originCity != null) {
        b.originCity(originCity);
      }
    }
    if (s.getDestinationCitySchema() != null && s.getDestinationCitySchema().getName() != null) {
      City destinationCity = cityService.getCityByName(s.getDestinationCitySchema().getName());
      if (destinationCity != null) {
        b.destinationCity(destinationCity);
      }
    }

    // FIX: Flights - use CODE instead of ID (schema IDs don't match DB IDs)
    if (s.getFlightSchemas() != null) {
      b.flights(s.getFlightSchemas().stream()
        .map(FlightSchema::getCode)
        .distinct()
        .map(flightService::getFlightByCode)
        .filter(java.util.Objects::nonNull) // Filter out nulls if flight not found
        .toList());
    }

    // Orders: usar ids de OrderSchema
    if (s.getOrderSchemas() != null) {
      b.packages(s.getOrderSchemas().stream()
          .map(OrderSchema::getId)
          .distinct()
          .map(orderService::getOrder)
          .toList());
    }

    return b.build();
  }

  // === Facade ===
  public RouteSchema getRoute(Integer id) { return toSchema(routeService.getRoute(id)); }

  public List<RouteSchema> fetchRoutes(List<Integer> ids) {
    return routeService.fetchRoutes(ids).stream().map(this::toSchema).toList();
  }

  public List<RouteSchema> getByOrigin(Integer originCityId) {
    return routeService.getByOrigin(originCityId).stream().map(this::toSchema).toList();
  }

  public List<RouteSchema> getByDestination(Integer destinationCityId) {
    return routeService.getByDestination(destinationCityId).stream().map(this::toSchema).toList();
  }

  public List<RouteSchema> getBySolution(Integer solutionId) {
    return routeService.getBySolution(solutionId).stream().map(this::toSchema).toList();
  }

  public List<RouteSchema> getByFlight(Integer flightId) {
    return routeService.getByFlight(flightId).stream().map(this::toSchema).toList();
  }

  public List<RouteSchema> getByPackage(Integer packageId) {
    return routeService.getByPackage(packageId).stream().map(this::toSchema).toList();
  }

  public List<RouteSchema> getByTimeRange(Double min, Double max) {
    return routeService.getByTimeRange(min, max).stream().map(this::toSchema).toList();
  }

  public List<RouteSchema> getByCostRange(Double min, Double max) {
    return routeService.getByCostRange(min, max).stream().map(this::toSchema).toList();
  }

  public RouteSchema createRoute(RouteSchema schema) {
    return toSchema(routeService.createRoute(toEntity(schema)));
  }

  public List<RouteSchema> bulkCreateRoutes(List<RouteSchema> schemas) {
    var entities = schemas.stream().map(this::toEntity).toList();
    return routeService.bulkCreateRoutes(entities).stream().map(this::toSchema).toList();
  }

  public RouteSchema updateRoute(Integer id, RouteSchema updates) {
    return toSchema(routeService.updateRoute(id, toEntity(updates)));
  }

  public void deleteRoute(Integer id) { routeService.deleteRoute(id); }

  public void bulkDeleteRoutes(List<Integer> ids) { routeService.bulkDeleteRoutes(ids); }
}