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

  /**
   * Add a new urgent order during simulation
   * POST /api/orders/urgent
   * 
   * This endpoint creates a new order with high priority during an active simulation.
   * The order will have status PENDING and requires replanning.
   * 
   * Request body example:
   * {
   *   "name": "Urgent Order",
   *   "originCityId": 1,
   *   "destinationCityId": 5,
   *   "deliveryDate": "2025-01-05T18:00:00",
   *   "customerId": 12345,
   *   "pickupTimeHours": 2.0
   * }
   * 
   * Response includes the created order and indicates that replanning is needed.
   */
  @PostMapping("/urgent")
  public ResponseEntity<OrderCreationResponse> createUrgentOrder(@RequestBody OrderSchema order) {
    System.out.println("===========================================");
    System.out.println("URGENT ORDER CREATION DURING SIMULATION");
    System.out.println("Destination: " + order.getDestinationCityName());
    System.out.println("Delivery Date: " + order.getDeliveryDate());
    System.out.println("===========================================");
    
    // Set high priority and PENDING status
    order.setStatus(PackageStatus.PENDING);
    order.setPriority(10.0); // High priority
    
    // Create the order
    OrderSchema createdOrder = orderController.createOrder(order);
    
    System.out.println("Urgent order created with ID: " + createdOrder.getId());
    
    return ResponseEntity.ok(
        OrderCreationResponse.builder()
            .success(true)
            .message("Urgent order created successfully - replanning required")
            .order(createdOrder)
            .requiresReplanning(true)
            .build()
    );
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

  /**
   * Response DTO for order creation during simulation
   */
  @lombok.Data
  @lombok.Builder
  @lombok.NoArgsConstructor
  @lombok.AllArgsConstructor
  static class OrderCreationResponse {
    private Boolean success;
    private String message;
    private OrderSchema order;
    private Boolean requiresReplanning;
  }
}
