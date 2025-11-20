package com.system.morapack.api;

import com.system.morapack.bll.controller.OrderController;
import com.system.morapack.schemas.OrderSchema;
import com.system.morapack.schemas.PackageStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderAPI {

  private final OrderController orderController;

  @GetMapping("/{id}")
  public ResponseEntity<OrderSchema> getOrder(@PathVariable Integer id) {
    return ResponseEntity.ok(orderController.getOrder(id));
  }

  @GetMapping
  public ResponseEntity<List<OrderSchema>> getOrders(
      @RequestParam(required = false) List<Integer> ids,
      @RequestParam(required = false) PackageStatus status,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate) {

    if (status != null) {
      return ResponseEntity.ok(orderController.getOrdersByStatus(status));
    }

    if (startDate != null && endDate != null) {
      return ResponseEntity.ok(orderController.getOrdersByDeliveryDateRange(startDate, endDate));
    }

    return ResponseEntity.ok(orderController.fetchOrders(ids));
  }

  @PostMapping
  public ResponseEntity<OrderSchema> createOrder(@RequestBody OrderSchema order) {
    return ResponseEntity.ok(orderController.createOrder(order));
  }

  @PostMapping("/bulk")
  public ResponseEntity<List<OrderSchema>> createOrders(@RequestBody List<OrderSchema> orders) {
    return ResponseEntity.ok(orderController.bulkCreateOrders(orders));
  }

  @PutMapping("/{id}")
  public ResponseEntity<OrderSchema> updateOrder(@PathVariable Integer id, @RequestBody OrderSchema updates) {
    return ResponseEntity.ok(orderController.updateOrder(id, updates));
  }

  @PatchMapping("/{id}/status")
  public ResponseEntity<OrderSchema> updateOrderStatus(
      @PathVariable Integer id,
      @RequestParam PackageStatus status) {
    return ResponseEntity.ok(orderController.updateStatus(id, status));
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> deleteOrder(@PathVariable Integer id) {
    orderController.deleteOrder(id);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/all")
  public ResponseEntity<Void> clearAllOrdersAndProducts() {
    orderController.clearOrdersAndProducts();
    return ResponseEntity.noContent().build();
  }


}
