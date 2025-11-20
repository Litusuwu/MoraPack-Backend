package com.system.morapack.bll.controller;

import com.system.morapack.bll.adapter.OrderAdapter;
import com.system.morapack.schemas.OrderSchema;
import com.system.morapack.schemas.PackageStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderController {

  private final OrderAdapter orderAdapter;

  public OrderSchema getOrder(Integer id) {
    return orderAdapter.getOrder(id);
  }

  public List<OrderSchema> fetchOrders(List<Integer> ids) {
    return orderAdapter.fetchOrders(ids);
  }

  public OrderSchema createOrder(OrderSchema request) {
    return orderAdapter.createOrder(request);
  }

  public List<OrderSchema> bulkCreateOrders(List<OrderSchema> requests) {
    return orderAdapter.bulkCreateOrders(requests);
  }

  public OrderSchema updateOrder(Integer id, OrderSchema request) {
    return orderAdapter.updateOrder(id, request);
  }

  public OrderSchema updateStatus(Integer id, PackageStatus status) {
    return orderAdapter.updateStatus(id, status);
  }

  public void deleteOrder(Integer id) {
    orderAdapter.deleteOrder(id);
  }

  public void clearOrdersAndProducts() {
    orderAdapter.clearOrdersAndProducts();
  }

  public void bulkDeleteOrders(List<Integer> ids) {
    orderAdapter.bulkDeleteOrders(ids);
  }

  public List<OrderSchema> getOrdersByDeliveryDateRange(LocalDateTime start, LocalDateTime end) {
    return orderAdapter.getOrdersByDeliveryDateRange(start, end);
  }

  public List<OrderSchema> getOrdersByStatus(PackageStatus status) {
    return orderAdapter.getOrdersByStatus(status);
  }



}
