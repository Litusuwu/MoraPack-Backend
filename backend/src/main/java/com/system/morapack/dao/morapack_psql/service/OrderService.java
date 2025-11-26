package com.system.morapack.dao.morapack_psql.service;

import com.system.morapack.dao.morapack_psql.model.Order;
import com.system.morapack.dao.morapack_psql.repository.OrderRepository;
import com.system.morapack.schemas.PackageStatus;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
@Service
@RequiredArgsConstructor
public class OrderService {

  private final OrderRepository orderRepository;

  public Order getOrder(Integer id) {
    return orderRepository.findById(id)
        .orElseThrow(() -> new EntityNotFoundException("Order not found with id: " + id));
  }

  public Order getOrderByName(String name) {
    return orderRepository.findFirstByName(name)
        .orElseThrow(() -> new EntityNotFoundException("Order not found with name: " + name));
  }

  public List<Order> fetchOrders(List<Integer> ids) {
    if (ids == null || ids.isEmpty()) {
      return orderRepository.findAll();
    }
    return orderRepository.findByIdIn(ids);
  }

  public Order createOrder(Order order) {
    if (orderRepository.existsByName(order.getName())) {
      throw new IllegalArgumentException("Order already exists: " + order.getName());
    }
    return orderRepository.save(order);
  }

  /**
   * Bulk insert orders WITHOUT duplicate check (use when DB was just cleared)
   * Much faster for large inserts after clearAllOrders()
   */
  public List<Order> bulkCreateOrdersNoDuplicateCheck(List<Order> orders) {
    if (orders.isEmpty()) {
      return new ArrayList<>();
    }
    System.out.println("Direct batch inserting " + orders.size() + " orders (no duplicate check)...");
    return orderRepository.saveAll(orders);
  }

  // NOTE: No @Transactional - called from within a transaction
  public List<Order> bulkCreateOrders(List<Order> orders) {
    if (orders.isEmpty()) {
      return new ArrayList<>();
    }
    
    // OPTIMIZED: Check for existing orders in batch, but split to avoid PostgreSQL parameter limit
    // PostgreSQL max parameters: 65535, using chunks of 50000 to be safe
    final int CHUNK_SIZE = 50000;
    
    Set<String> orderNames = orders.stream()
        .map(Order::getName)
        .collect(java.util.stream.Collectors.toSet());
    
    Set<String> existingNames = new java.util.HashSet<>();
    List<String> namesList = new ArrayList<>(orderNames);
    
    // Split into chunks to avoid "PreparedStatement can have at most 65,535 parameters"
    for (int i = 0; i < namesList.size(); i += CHUNK_SIZE) {
      int end = Math.min(i + CHUNK_SIZE, namesList.size());
      List<String> chunk = namesList.subList(i, end);
      
      List<Order> existingInChunk = orderRepository.findByNameIn(chunk);
      existingNames.addAll(
          existingInChunk.stream()
              .map(Order::getName)
              .collect(java.util.stream.Collectors.toSet())
      );
    }
    
    // Filter out duplicates
    List<Order> uniqueOrders = orders.stream()
        .filter(order -> !existingNames.contains(order.getName()))
        .collect(java.util.stream.Collectors.toList());
    
    if (uniqueOrders.isEmpty()) {
      System.out.println("WARNING: All " + orders.size() + " orders already exist, skipping insert");
      return new ArrayList<>();
    }
    
    int duplicates = orders.size() - uniqueOrders.size();
    if (duplicates > 0) {
      System.out.println("Filtered out " + duplicates + " duplicate orders");
    }
    
    System.out.println("Batch inserting " + uniqueOrders.size() + " orders using JPA saveAll...");
    
    // Use saveAll (Hibernate will batch if configured properly)
    return orderRepository.saveAll(uniqueOrders);
  }

  public Order save(Order order) {
    return orderRepository.save(order);
  }

  public Order updateOrder(Integer id, Order updates) {
    Order order = getOrder(id);

    if (updates.getName() != null)
      order.setName(updates.getName());
    if (updates.getDeliveryDate() != null)
      order.setDeliveryDate(updates.getDeliveryDate());
    if (updates.getPickupTimeHours() != null)
      order.setPickupTimeHours(updates.getPickupTimeHours());
    if (updates.getStatus() != null)
      order.setStatus(updates.getStatus());
    if (updates.getOrigin() != null)
      order.setOrigin(updates.getOrigin());
    if (updates.getDestination() != null)
      order.setDestination(updates.getDestination());
    if (updates.getCustomer() != null)
      order.setCustomer(updates.getCustomer());

    return orderRepository.save(order);
  }

  public Order updateStatus(Integer id, PackageStatus status) {
    Order order = getOrder(id);
    order.setStatus(status);
    return orderRepository.save(order);
  }

  public void deleteOrder(Integer id) {
    if (!orderRepository.existsById(id)) {
      throw new EntityNotFoundException("Order not found with id: " + id);
    }
    orderRepository.deleteById(id);
  }

  @Transactional
  public void bulkDeleteOrders(List<Integer> ids) {
    orderRepository.deleteAllByIdIn(ids);
  }

  @Transactional
  public void deleteAll() {
    orderRepository.deleteAll();
  }

  public List<Order> getOrdersByDeliveryDateRange(LocalDateTime start, LocalDateTime end) {
    return orderRepository.findByDeliveryDateBetween(start, end);
  }

  public List<Order> getOrdersByStatus(PackageStatus status) {
    return orderRepository.findByStatus(status);
  }

  @Transactional
  public void clearOrders() {
    orderRepository.deleteAll();
  }

}
