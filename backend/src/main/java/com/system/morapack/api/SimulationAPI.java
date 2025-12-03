package com.system.morapack.api;

import com.system.morapack.bll.service.SimulationTimeService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * API para controlar la simulación del tiempo
 *
 * Endpoints:
 * - POST /api/simulation/advance-time - Avanzar el tiempo de simulación
 * - POST /api/simulation/update-states - Actualizar estados de productos
 */
@RestController
@RequestMapping("/api/simulation")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class SimulationAPI {

    private final SimulationTimeService simulationTimeService;

    public static class CapacityStats {
        private double usedCapacity;
        private double totalCapacity;
        private double averageUtilization;

        public CapacityStats(double usedCapacity, double totalCapacity) {
            this.usedCapacity = usedCapacity;
            this.totalCapacity = totalCapacity;
            this.averageUtilization = totalCapacity > 0 ? usedCapacity / totalCapacity : 0;
        }

        public double getUsedCapacity() { return usedCapacity; }
        public double getTotalCapacity() { return totalCapacity; }
        public double getAverageUtilization() { return averageUtilization; }
    }

    /**
     * Actualiza estados de productos basándose en el tiempo de simulación
     *
     * POST /api/simulation/update-states
     *
     * Body:
     * {
     *   "currentTime": "2025-01-03T12:00:00"
     * }
     */
    @PostMapping("/update-states")
    public ResponseEntity<Map<String, Object>> updateStates(
            @RequestBody SimulationTimeRequest request) {

        if (request.getCurrentTime() == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "currentTime is required",
                "example", "{ \"currentTime\": \"2025-01-03T12:00:00\" }"
            ));
        }

        System.out.println("\n========================================");
        System.out.println("API: UPDATE SIMULATION STATES");
        System.out.println("Current time: " + request.getCurrentTime());
        System.out.println("========================================\n");

        // Actualizar estados
        SimulationTimeService.SimulationUpdateStats stats =
            simulationTimeService.updateProductStates(request.getCurrentTime());

        // NUEVO: capacidad promedio en tiempo real
        SimulationAPI.CapacityStats capacityStats =
                simulationTimeService.calculateCapacityStats();



        // Construir respuesta
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("currentSimulationTime", request.getCurrentTime());

        Map<String, Integer> transitions = new HashMap<>();
        transitions.put("pendingToAssigned", stats.getPendingToAssigned());
        transitions.put("pendingToInTransit", stats.getPendingToInTransit());
        transitions.put("assignedToInTransit", stats.getAssignedToInTransit());
        transitions.put("inTransitToArrived", stats.getInTransitToArrived());
        transitions.put("arrivedToDelivered", stats.getArrivedToDelivered());
        transitions.put("total", stats.getTotalTransitions());

        response.put("transitions", transitions);

        response.put("capacityStats", Map.of(
                "usedCapacity", capacityStats.getUsedCapacity(),
                "totalCapacity", capacityStats.getTotalCapacity(),
                "averageUtilization", capacityStats.getAverageUtilization()
        ));

        return ResponseEntity.ok(response);
    }

    /**
     * Avanza el tiempo de simulación por un número de horas
     *
     * POST /api/simulation/advance-time
     *
     * Body:
     * {
     *   "currentTime": "2025-01-02T00:00:00",
     *   "hoursToAdvance": 8
     * }
     */
    @PostMapping("/advance-time")
    public ResponseEntity<Map<String, Object>> advanceTime(
            @RequestBody AdvanceTimeRequest request) {

        if (request.getCurrentTime() == null || request.getHoursToAdvance() == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "currentTime and hoursToAdvance are required"
            ));
        }

        LocalDateTime newTime = request.getCurrentTime()
            .plusHours(request.getHoursToAdvance());

        System.out.println("\n========================================");
        System.out.println("API: ADVANCE SIMULATION TIME");
        System.out.println("From: " + request.getCurrentTime());
        System.out.println("To: " + newTime);
        System.out.println("Hours advanced: " + request.getHoursToAdvance());
        System.out.println("========================================\n");

        // Actualizar estados con el nuevo tiempo
        SimulationTimeService.SimulationUpdateStats stats =
            simulationTimeService.updateProductStates(newTime);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("previousTime", request.getCurrentTime());
        response.put("newTime", newTime);
        response.put("hoursAdvanced", request.getHoursToAdvance());

        Map<String, Integer> transitions = new HashMap<>();
        transitions.put("pendingToAssigned", stats.getPendingToAssigned());
        transitions.put("pendingToInTransit", stats.getPendingToInTransit());
        transitions.put("assignedToInTransit", stats.getAssignedToInTransit());
        transitions.put("inTransitToArrived", stats.getInTransitToArrived());
        transitions.put("arrivedToDelivered", stats.getArrivedToDelivered());
        transitions.put("total", stats.getTotalTransitions());

        response.put("transitions", transitions);

        return ResponseEntity.ok(response);
    }

    /**
     * Obtiene el estado actual de la simulación
     *
     * GET /api/simulation/status
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        // Por ahora solo retorna un placeholder
        // En el futuro podrías guardar el "current simulation time" en Redis o DB

        Map<String, Object> response = new HashMap<>();
        response.put("message", "Simulation time is managed by frontend");
        response.put("recommendation", "Call POST /api/simulation/update-states with currentTime");

        return ResponseEntity.ok(response);
    }

    // ==============================
    // Request DTOs
    // ==============================

    public static class SimulationTimeRequest {
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private LocalDateTime currentTime;

        public LocalDateTime getCurrentTime() {
            return currentTime;
        }

        public void setCurrentTime(LocalDateTime currentTime) {
            this.currentTime = currentTime;
        }
    }

    public static class AdvanceTimeRequest {
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private LocalDateTime currentTime;
        private Integer hoursToAdvance;

        public LocalDateTime getCurrentTime() {
            return currentTime;
        }

        public void setCurrentTime(LocalDateTime currentTime) {
            this.currentTime = currentTime;
        }

        public Integer getHoursToAdvance() {
            return hoursToAdvance;
        }

        public void setHoursToAdvance(Integer hoursToAdvance) {
            this.hoursToAdvance = hoursToAdvance;
        }
    }




}
