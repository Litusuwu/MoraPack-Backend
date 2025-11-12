# Solución: Productos solo IN_TRANSIT - Falta Simulación de Tiempo

## 🔍 **El Problema**

Ejecutaste el algoritmo y todos los productos están en estado `IN_TRANSIT` o `PENDING`, pero ninguno llega (`ARRIVED` o `DELIVERED`).

**Causa**: El algoritmo asigna productos a vuelos, pero NO simula el paso del tiempo para actualizar estados cuando los vuelos llegan.

---

## ✅ **La Solución**

Creé dos componentes nuevos:

### 1. **SimulationTimeService** ✅
- Calcula cuándo llega cada producto
- Actualiza estados basándose en tiempo de simulación
- Transiciones: `PENDING → IN_TRANSIT → ARRIVED → DELIVERED`

### 2. **SimulationAPI** ✅
- Endpoint para avanzar el tiempo de simulación
- Actualiza estados automáticamente

---

## 🚀 **Cómo Usarlo**

### **Flujo Completo con Simulación de Tiempo**

#### **Paso 1: Ejecutar Algoritmo (Día 0: 2025-01-02)**

```bash
# Cargar orders y ejecutar algoritmo
curl -X POST "http://localhost:8080/api/algorithm/weekly" \
  -H "Content-Type: application/json" \
  -d '{
    "simulationStartTime": "2025-01-02T00:00:00",
    "simulationDurationDays": 1,
    "useDatabase": true
  }'
```

**Estado actual**: Todos los productos están `IN_TRANSIT`

---

#### **Paso 2: Avanzar Tiempo +8 Horas (2025-01-02 08:00)**

```bash
curl -X POST "http://localhost:8080/api/simulation/update-states" \
  -H "Content-Type: application/json" \
  -d '{
    "currentTime": "2025-01-02T08:00:00"
  }'
```

**Resultado esperado**:
```json
{
  "success": true,
  "currentSimulationTime": "2025-01-02T08:00:00",
  "transitions": {
    "pendingToInTransit": 0,
    "inTransitToArrived": 45,
    "arrivedToDelivered": 0,
    "total": 45
  }
}
```

**Ahora algunos productos están `ARRIVED`** (llegaron a destino)

---

#### **Paso 3: Avanzar Tiempo +12 Horas Más (2025-01-02 20:00)**

```bash
curl -X POST "http://localhost:8080/api/simulation/update-states" \
  -H "Content-Type: application/json" \
  -d '{
    "currentTime": "2025-01-02T20:00:00"
  }'
```

**Resultado esperado**:
```json
{
  "success": true,
  "currentSimulationTime": "2025-01-02T20:00:00",
  "transitions": {
    "pendingToInTransit": 0,
    "inTransitToArrived": 78,
    "arrivedToDelivered": 45,
    "total": 123
  }
}
```

**Ahora**:
- Algunos productos → `DELIVERED` (cliente recogió después de 2 horas)
- Más productos → `ARRIVED` (llegaron recientemente)
- Algunos aún → `IN_TRANSIT` (vuelos largos)

---

#### **Paso 4: Día Siguiente (2025-01-03 00:00)**

```bash
curl -X POST "http://localhost:8080/api/simulation/update-states" \
  -H "Content-Type: application/json" \
  -d '{
    "currentTime": "2025-01-03T00:00:00"
  }'
```

---

#### **Paso 5: Verificar Estados en DB**

```bash
# Ver distribución de estados
psql -h localhost -p 5435 -U postgres -d postgres -c \
  "SELECT status, COUNT(*) as count
   FROM products
   GROUP BY status
   ORDER BY status;"
```

**Resultado esperado**:
```
  status   | count
-----------+-------
 ARRIVED   |    89
 DELIVERED |   156
 IN_TRANSIT|   183
 PENDING   |     0
```

---

## 📊 **Endpoint Alternativo: Avanzar Tiempo**

En lugar de llamar `update-states` con cada hora específica, puedes usar `advance-time`:

```bash
curl -X POST "http://localhost:8080/api/simulation/advance-time" \
  -H "Content-Type: application/json" \
  -d '{
    "currentTime": "2025-01-02T00:00:00",
    "hoursToAdvance": 24
  }'
```

Esto avanza 24 horas automáticamente.

---

## 🔄 **Integración con Frontend**

### **Opción 1: Manual (para testing)**
El frontend llama `POST /api/simulation/update-states` cada vez que quiere avanzar el tiempo.

### **Opción 2: Automática (recomendada)**
El frontend tiene un "reloj de simulación":
1. Cada 30 segundos reales = 30 minutos de simulación
2. Llama automáticamente a `update-states`
3. Refresca visualización

**Pseudocódigo frontend**:
```javascript
let simulationTime = new Date("2025-01-02T00:00:00");
const SIMULATION_SPEED = 60; // 1 segundo real = 1 minuto simulado

setInterval(() => {
  // Avanzar 30 minutos de simulación
  simulationTime = new Date(simulationTime.getTime() + 30 * 60 * 1000);

  // Actualizar estados en backend
  fetch('http://localhost:8080/api/simulation/update-states', {
    method: 'POST',
    body: JSON.stringify({
      currentTime: simulationTime.toISOString()
    })
  });

  // Refrescar visualización
  refreshProductStatus();
}, 30000); // Cada 30 segundos reales
```

---

## 🧪 **Script de Prueba Completo**

```bash
#!/bin/bash
# test_simulation_time.sh

echo "=========================================="
echo "TEST: SIMULATION TIME"
echo "=========================================="

# 1. Ejecutar algoritmo (día 2025-01-02)
echo ""
echo "1️⃣  Ejecutando algoritmo..."
curl -s -X POST "http://localhost:8080/api/algorithm/weekly" \
  -H "Content-Type: application/json" \
  -d '{
    "simulationStartTime": "2025-01-02T00:00:00",
    "simulationDurationDays": 1,
    "useDatabase": true
  }' | jq '.'

# 2. Verificar estados iniciales
echo ""
echo "2️⃣  Estados iniciales:"
psql -h localhost -p 5435 -U postgres -d postgres -c \
  "SELECT status, COUNT(*) FROM products GROUP BY status;"

# 3. Avanzar tiempo +8 horas
echo ""
echo "3️⃣  Avanzando tiempo a 08:00..."
curl -s -X POST "http://localhost:8080/api/simulation/update-states" \
  -H "Content-Type: application/json" \
  -d '{
    "currentTime": "2025-01-02T08:00:00"
  }' | jq '.'

psql -h localhost -p 5435 -U postgres -d postgres -c \
  "SELECT status, COUNT(*) FROM products GROUP BY status;"

# 4. Avanzar tiempo +12 horas más
echo ""
echo "4️⃣  Avanzando tiempo a 20:00..."
curl -s -X POST "http://localhost:8080/api/simulation/update-states" \
  -H "Content-Type: application/json" \
  -d '{
    "currentTime": "2025-01-02T20:00:00"
  }' | jq '.'

psql -h localhost -p 5435 -U postgres -d postgres -c \
  "SELECT status, COUNT(*) FROM products GROUP BY status;"

# 5. Día siguiente
echo ""
echo "5️⃣  Avanzando al día siguiente..."
curl -s -X POST "http://localhost:8080/api/simulation/update-states" \
  -H "Content-Type: application/json" \
  -d '{
    "currentTime": "2025-01-03T12:00:00"
  }' | jq '.'

psql -h localhost -p 5435 -U postgres -d postgres -c \
  "SELECT status, COUNT(*) FROM products GROUP BY status;"

echo ""
echo "=========================================="
echo "TEST COMPLETO"
echo "=========================================="
```

**Ejecutar**:
```bash
chmod +x test_simulation_time.sh
./test_simulation_time.sh
```

---

## 🔧 **Cómo Funciona Internamente**

### **SimulationTimeService**

Para cada producto:
1. Lee `assigned_flight_instance` (ej: `"FL-45-DAY-0-2000"`)
2. Calcula fecha de salida: `2025-01-02 20:00`
3. Obtiene tiempo de transporte del vuelo: `6 horas`
4. Calcula llegada: `2025-01-03 02:00`
5. Compara con tiempo actual de simulación:
   - Si `currentTime < arrivalTime` → `IN_TRANSIT`
   - Si `currentTime >= arrivalTime` → `ARRIVED`
   - Si `currentTime >= arrivalTime + 2h` → `DELIVERED`

### **Estados**
- `PENDING` → Producto sin vuelo asignado
- `IN_TRANSIT` → En camino (antes de llegar)
- `ARRIVED` → Llegó al destino (< 2 horas)
- `DELIVERED` → Cliente recogió (> 2 horas desde llegada)

---

## 📈 **Verificar que Funciona**

### **Ver productos que llegaron**:
```bash
psql -h localhost -p 5435 -U postgres -d postgres -c \
  "SELECT
    id,
    status,
    assigned_flight_instance,
    (SELECT order_date FROM orders WHERE id = products.order_id) as order_date
   FROM products
   WHERE status = 'ARRIVED'
   LIMIT 10;"
```

### **Ver timeline de entregas**:
```bash
psql -h localhost -p 5435 -U postgres -d postgres -c \
  "SELECT
    DATE_TRUNC('hour', o.order_date) as time_bucket,
    p.status,
    COUNT(*) as count
   FROM products p
   JOIN orders o ON o.id = p.order_id
   GROUP BY DATE_TRUNC('hour', o.order_date), p.status
   ORDER BY time_bucket, p.status;"
```

---

## 🆘 **Troubleshooting**

### **Problema**: Todos siguen IN_TRANSIT después de update-states
**Solución**: Verifica que `assigned_flight_instance` tiene el formato correcto:
```bash
psql -h localhost -p 5435 -U postgres -d postgres -c \
  "SELECT DISTINCT assigned_flight_instance FROM products WHERE assigned_flight_instance IS NOT NULL LIMIT 5;"
```
Debe ser: `FL-{id}-DAY-{day}-{HHmm}`

### **Problema**: Error "NullPointerException"
**Solución**: Verifica que los productos tienen `order_id` asignado:
```bash
psql -h localhost -p 5435 -U postgres -d postgres -c \
  "SELECT COUNT(*) FROM products WHERE order_id IS NULL;"
```

---

## 📚 **Archivos Creados**

1. **SimulationTimeService.java** - Lógica de actualización de estados
2. **SimulationAPI.java** - Endpoints REST
3. **SOLUCION_ESTADOS.md** - Esta documentación

---

**¡Listo!** Ahora puedes simular el paso del tiempo y ver cómo los productos llegan y son entregados. 🚀

**Comandos rápidos**:
```bash
# Ejecutar algoritmo
curl -X POST "http://localhost:8080/api/algorithm/weekly" \
  -H "Content-Type: application/json" \
  -d '{"simulationStartTime": "2025-01-02T00:00:00", "simulationDurationDays": 1, "useDatabase": true}'

# Avanzar 12 horas
curl -X POST "http://localhost:8080/api/simulation/update-states" \
  -H "Content-Type: application/json" \
  -d '{"currentTime": "2025-01-02T12:00:00"}'

# Ver estados
psql -h localhost -p 5435 -U postgres -d postgres -c "SELECT status, COUNT(*) FROM products GROUP BY status;"
```
