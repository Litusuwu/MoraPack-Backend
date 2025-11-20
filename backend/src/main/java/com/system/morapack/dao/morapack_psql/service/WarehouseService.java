package com.system.morapack.dao.morapack_psql.service;

import com.system.morapack.dao.morapack_psql.model.Warehouse;
import com.system.morapack.dao.morapack_psql.repository.WarehouseRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class WarehouseService {

  private final WarehouseRepository warehouseRepository;

  public Warehouse getWarehouse(Integer id) {
    return warehouseRepository.findById(id)
        .orElseThrow(() -> new EntityNotFoundException("Warehouse not found with id: " + id));
  }

  public List<Warehouse> fetchWarehouses(List<Integer> ids) {
    if (ids == null || ids.isEmpty()) return warehouseRepository.findAll();
    return warehouseRepository.findByIdIn(ids);
  }

  public Warehouse createWarehouse(Warehouse w) {
    validate(w);
    if (warehouseRepository.existsByName(w.getName())) {
      throw new IllegalArgumentException("Warehouse already exists: " + w.getName());
    }
    return warehouseRepository.save(w);
  }

  public List<Warehouse> bulkCreateWarehouses(List<Warehouse> list) {
    list.forEach(this::validate);
    return warehouseRepository.saveAll(list);
  }

  @Transactional
  public Warehouse updateWarehouse(Integer id, Warehouse patch) {
    Warehouse w = getWarehouse(id);

    if (patch.getName() != null) w.setName(patch.getName());
    if (patch.getIsMainWarehouse() != null) w.setIsMainWarehouse(patch.getIsMainWarehouse());
    if (patch.getMaxCapacity() != null) w.setMaxCapacity(patch.getMaxCapacity());
    if (patch.getUsedCapacity() != null) w.setUsedCapacity(patch.getUsedCapacity());
    // airport es lado inverso; se administra desde AirportSchema. No reasignar aquí.

    validate(w);
    return warehouseRepository.save(w);
  }

  public void deleteWarehouse(Integer id) {
    if (!warehouseRepository.existsById(id)) {
      throw new EntityNotFoundException("Warehouse not found with id: " + id);
    }
    warehouseRepository.deleteById(id);
  }

  @Transactional
  public void bulkDeleteWarehouses(List<Integer> ids) {
    warehouseRepository.deleteAllByIdIn(ids);
  }

  public Warehouse getByAirport(Integer airportId) {
    return warehouseRepository.findByAirport_Id(airportId);
  }

  public List<Warehouse> getMainWarehouses() {
    return warehouseRepository.findByIsMainWarehouse(true);
  }

  public List<Warehouse> getWithCapacityAtLeast(Integer minCapacity) {
    return warehouseRepository.findByMaxCapacityGreaterThanEqual(minCapacity);
  }

  public List<Warehouse> getWithUsedCapacityAtMost(Integer maxUsed) {
    return warehouseRepository.findByUsedCapacityLessThanEqual(maxUsed);
  }

  // Operaciones de capacidad
  @Transactional
  public Warehouse allocate(Integer id, int amount) {
    if (amount <= 0) throw new IllegalArgumentException("amount must be > 0");
    Warehouse w = getWarehouse(id);
    int newUsed = w.getUsedCapacity() + amount;

    // Check capacity ONLY if it's NOT a main warehouse
    if (!Boolean.TRUE.equals(w.getIsMainWarehouse())) {
      if (newUsed > w.getMaxCapacity())
        throw new IllegalArgumentException("capacity exceeded");
      }

    w.setUsedCapacity(newUsed);
    return warehouseRepository.save(w);
  }

  @Transactional
  public Warehouse release(Integer id, int amount) {
    if (amount <= 0) throw new IllegalArgumentException("amount must be > 0");
    Warehouse w = getWarehouse(id);
    int newUsed = w.getUsedCapacity() - amount;
    if (newUsed < 0) newUsed = 0;
    w.setUsedCapacity(newUsed);
    return warehouseRepository.save(w);
  }

  // Reglas
  private void validate(Warehouse w) {
    if (w.getName() == null || w.getName().isBlank())
      throw new IllegalArgumentException("name requerido");
    if (w.getMaxCapacity() == null || w.getMaxCapacity() < 0)
      throw new IllegalArgumentException("maxCapacity inválido");
    if (w.getUsedCapacity() == null || w.getUsedCapacity() < 0)
      throw new IllegalArgumentException("usedCapacity inválido");
    if (w.getIsMainWarehouse() == null)
      throw new IllegalArgumentException("isMainWarehouse requerido");

    // Check capacity ONLY if it's NOT a main warehouse
    if (!Boolean.TRUE.equals(w.getIsMainWarehouse())) {
      if (w.getUsedCapacity() > w.getMaxCapacity())
        throw new IllegalArgumentException("usedCapacity no puede exceder maxCapacity");
      }
  }
}
