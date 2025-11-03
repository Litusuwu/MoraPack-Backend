# Simulation Events API - Dynamic Replanning

Esta documentación describe los nuevos endpoints para manejar eventos dinámicos durante la simulación (cancelación de vuelos, órdenes urgentes, replanificación).

## Índice

1. [Cancelar Vuelo](#1-cancelar-vuelo)
2. [Agregar Orden Urgente](#2-agregar-orden-urgente)
3. [Replanificar Rutas](#3-replanificar-rutas)
4. [Flujo de Trabajo Completo](#4-flujo-de-trabajo-completo)

---

## 1. Cancelar Vuelo

Cancela un vuelo durante una simulación activa. El vuelo se marca como `INACTIVE` y ya no estará disponible para asignaciones futuras.

### Endpoint
```
POST /api/flights/{id}/cancel
```

### Request Body (Opcional)
```json
{
  "reason": "WEATHER",
  "currentSimulationTime": "2025-01-03T14:30:00"
}
```

**Parámetros:**
- `reason` (opcional): Razón de la cancelación (`WEATHER`, `MECHANICAL`, `MANUAL`, etc.)
- `currentSimulationTime` (opcional): Timestamp ISO del tiempo actual de simulación

### Response
```json
{
  "success": true,
  "message": "Flight MP-302 cancelled successfully",
  "flight": {
    "id": 123,
    "code": "MP-302",
    "status": "INACTIVE",
    ...
  },
  "affectedProducts": 0,
  "requiresReplanning": true
}
```

**Códigos de Estado:**
- `200 OK`: Vuelo cancelado exitosamente
- `400 Bad Request`: Vuelo no encontrado o no puede ser cancelado (ya en vuelo o completado)

### Validaciones
- El vuelo no puede estar en estado `COMPLETADO` o `EN_VUELO`
- Solo vuelos con estado `ACTIVE` o `SCHEDULED` pueden ser cancelados

### Ejemplo con cURL
```bash
curl -X POST http://localhost:8080/api/flights/123/cancel \
  -H "Content-Type: application/json" \
  -d '{
    "reason": "WEATHER",
    "currentSimulationTime": "2025-01-03T14:30:00"
  }'
```

---

## 2. Agregar Orden Urgente

Crea una nueva orden con alta prioridad durante una simulación activa. La orden se crea con estado `PENDING` y prioridad 10.0.

### Endpoint
```
POST /api/orders/urgent
```

### Request Body (Requerido)
```json
{
  "name": "Urgent Order #1234",
  "originCityId": 1,
  "destinationCityId": 5,
  "deliveryDate": "2025-01-05T18:00:00",
  "customerId": 12345,
  "pickupTimeHours": 2.0
}
```

**Parámetros:**
- `name` (requerido): Nombre de la orden
- `originCityId` (requerido): ID de la ciudad de origen
- `destinationCityId` (requerido): ID de la ciudad de destino
- `deliveryDate` (requerido): Fecha límite de entrega (formato ISO)
- `customerId` (requerido): ID del cliente
- `pickupTimeHours` (requerido): Horas máximas para recoger (típicamente 2.0)

### Response
```json
{
  "success": true,
  "message": "Urgent order created successfully - replanning required",
  "order": {
    "id": 456,
    "name": "Urgent Order #1234",
    "status": "PENDING",
    "priority": 10.0,
    ...
  },
  "requiresReplanning": true
}
```

**Códigos de Estado:**
- `200 OK`: Orden creada exitosamente

### Ejemplo con cURL
```bash
curl -X POST http://localhost:8080/api/orders/urgent \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Urgent Order #1234",
    "originCityId": 1,
    "destinationCityId": 5,
    "deliveryDate": "2025-01-05T18:00:00",
    "customerId": 12345,
    "pickupTimeHours": 2.0
  }'
```

---

## 3. Replanificar Rutas

Re-ejecuta el algoritmo ALNS usando el estado actual de la base de datos para generar nuevas rutas optimizadas después de eventos (cancelaciones, nuevas órdenes).

### Endpoint
```
POST /api/algorithm/replan
```

### Request Body (Opcional)
```json
{
  "reason": "FLIGHT_CANCELLED",
  "affectedFlightId": 123,
  "currentSimulationTime": "2025-01-03T14:30:00",
  "quickMode": false
}
```

**Parámetros:**
- `reason` (opcional): Razón de la replanificación (`FLIGHT_CANCELLED`, `NEW_ORDER`, `FLIGHT_DELAYED`)
- `affectedFlightId` (opcional): ID del vuelo afectado
- `currentSimulationTime` (opcional): Timestamp ISO del tiempo actual de simulación
- `quickMode` (opcional): Si es `true`, usa menos iteraciones para respuesta más rápida

### Response
```json
{
  "success": true,
  "message": "Replanning completed successfully",
  "executionTimeMs": 45678,
  "algorithmResult": {
    "success": true,
    "algorithmType": "ALNS",
    "totalProducts": 243,
    "assignedOrders": 235,
    "productRoutes": [ ... ],
    "timeline": {
      "events": [ ... ],
      "totalDurationMinutes": 10080
    }
  }
}
```

**Códigos de Estado:**
- `200 OK`: Replanificación exitosa
- `500 Internal Server Error`: Error durante la replanificación

### Características Importantes
- Usa `useDatabase: true` - lee el estado actual de la BD
- Incluye todos los vuelos `ACTIVE` (excluye `INACTIVE`)
- Incluye todas las órdenes `PENDING` y órdenes nuevas
- Genera nueva timeline completa con eventos actualizados

### Ejemplo con cURL
```bash
curl -X POST http://localhost:8080/api/algorithm/replan \
  -H "Content-Type: application/json" \
  -d '{
    "reason": "FLIGHT_CANCELLED",
    "affectedFlightId": 123,
    "currentSimulationTime": "2025-01-03T14:30:00",
    "quickMode": false
  }'
```

---

## 4. Flujo de Trabajo Completo

### Escenario: Usuario cancela un vuelo durante la simulación

```javascript
// Paso 1: Usuario hace clic en "Cancelar Vuelo" en el frontend
const cancelResponse = await fetch('http://localhost:8080/api/flights/123/cancel', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    reason: 'WEATHER',
    currentSimulationTime: getCurrentSimulationTime()
  })
});

const cancelData = await cancelResponse.json();
console.log('Flight cancelled:', cancelData.flight.code);

// Paso 2: Si requiere replanificación, ejecutar ALNS
if (cancelData.requiresReplanning) {
  console.log('Replanning routes...');
  
  const replanResponse = await fetch('http://localhost:8080/api/algorithm/replan', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      reason: 'FLIGHT_CANCELLED',
      affectedFlightId: 123,
      currentSimulationTime: getCurrentSimulationTime()
    })
  });
  
  const replanData = await replanResponse.json();
  console.log('Replanning completed in', replanData.executionTimeMs, 'ms');
  
  // Paso 3: Actualizar la timeline en el frontend
  updateSimulationTimeline(replanData.algorithmResult.timeline);
  
  // Paso 4: Continuar reproducción desde el tiempo actual
  resumeSimulation(getCurrentSimulationTime());
}
```

### Escenario: Agregar orden urgente durante la simulación

```javascript
// Paso 1: Usuario crea orden urgente
const orderResponse = await fetch('http://localhost:8080/api/orders/urgent', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    name: 'Urgent Order #5678',
    originCityId: 1,
    destinationCityId: 5,
    deliveryDate: '2025-01-05T18:00:00',
    customerId: 12345,
    pickupTimeHours: 2.0
  })
});

const orderData = await orderResponse.json();
console.log('Urgent order created:', orderData.order.id);

// Paso 2: Replanificar para incluir la nueva orden
if (orderData.requiresReplanning) {
  const replanResponse = await fetch('http://localhost:8080/api/algorithm/replan', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      reason: 'NEW_ORDER',
      currentSimulationTime: getCurrentSimulationTime()
    })
  });
  
  const replanData = await replanResponse.json();
  updateSimulationTimeline(replanData.algorithmResult.timeline);
  resumeSimulation(getCurrentSimulationTime());
}
```

---

## Notas Importantes

### Rendimiento
- La replanificación puede tardar **30-90 segundos** dependiendo del tamaño del problema
- Considera usar `quickMode: true` para respuestas más rápidas durante demos
- El frontend debería mostrar un loader mientras se replanifica

### Estado de la Base de Datos
- Los endpoints modifican la BD inmediatamente
- ALNS usa el estado actual de la BD cuando `useDatabase: true`
- Vuelos cancelados (`INACTIVE`) no se consideran en futuras planificaciones
- Órdenes con `status: PENDING` siempre se intentan asignar

### Timeline Updates
- Después de replanificar, el frontend debe:
  1. Pausar la simulación actual
  2. Reemplazar la timeline con los nuevos eventos
  3. Reiniciar desde el `currentSimulationTime` o desde el inicio
  4. Actualizar todos los componentes visuales (mapa, estadísticas)

### Validaciones
- Cancelar vuelos completados o en vuelo está **prohibido**
- Órdenes urgentes deben tener fecha de entrega válida (futuro)
- Las ciudades origen/destino deben existir en la BD

---

## Testing

### Test de Cancelación de Vuelo
```bash
# 1. Obtener lista de vuelos activos
curl http://localhost:8080/api/flights?status=ACTIVE

# 2. Cancelar un vuelo
curl -X POST http://localhost:8080/api/flights/1/cancel

# 3. Verificar que el vuelo está INACTIVE
curl http://localhost:8080/api/flights/1
```

### Test de Orden Urgente
```bash
# 1. Crear orden urgente
curl -X POST http://localhost:8080/api/orders/urgent \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Test Order",
    "originCityId": 1,
    "destinationCityId": 2,
    "deliveryDate": "2025-12-31T23:59:59",
    "customerId": 999,
    "pickupTimeHours": 2.0
  }'

# 2. Verificar que la orden existe
curl http://localhost:8080/api/orders?status=PENDING
```

### Test de Replanificación Completa
```bash
# Flujo completo
curl -X POST http://localhost:8080/api/flights/1/cancel
curl -X POST http://localhost:8080/api/algorithm/replan \
  -H "Content-Type: application/json" \
  -d '{"reason": "FLIGHT_CANCELLED", "affectedFlightId": 1}'
```

---

## Integración con Frontend

Ver ejemplos en `morapack-frontend/src/hooks/`:
- `useFlightCancellation.ts` - Hook para cancelar vuelos
- `useUrgentOrder.ts` - Hook para crear órdenes urgentes
- `useReplanSimulation.ts` - Hook para replanificar

---

**Última actualización:** Noviembre 2025

