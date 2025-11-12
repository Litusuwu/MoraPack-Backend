# Secuencia de Testing - FlightInstance Implementation

## 📋 **Secuencia Completa de Pruebas**

### **Pre-requisito: Backend corriendo**
```bash
# Verificar que el backend está corriendo
curl -s http://localhost:8080/actuator/health 2>/dev/null || echo "Backend NOT running on port 8080"
```

---

## **Paso 1: Cargar Airports** ✅ (Ya lo hiciste)

```bash
curl -X POST http://localhost:8080/api/data-import/airports \
  -H "Content-Type: application/json" \
  -w "\nStatus: %{http_code}\n"
```

**Resultado esperado**:
```json
{
  "message": "Airports imported successfully",
  "count": 50
}
Status: 200
```

---

## **Paso 2: Cargar Flights** ✅ (Ya lo hiciste)

```bash
curl -X POST http://localhost:8080/api/data-import/flights \
  -H "Content-Type: application/json" \
  -w "\nStatus: %{http_code}\n"
```

**Resultado esperado**:
```json
{
  "message": "Flights imported successfully",
  "count": 100
}
Status: 200
```

---

## **Paso 3: Cargar Orders (1 semana: 2025-01-02 a 2025-01-08)**

```bash
curl -X POST "http://localhost:8080/api/data/load-orders" \
  -H "Content-Type: application/json" \
  -d '{
    "startDate": "2025-01-02T00:00:00",
    "endDate": "2025-01-08T23:59:59"
  }' \
  -w "\nStatus: %{http_code}\n" \
  | jq '.'
```

**Resultado esperado**:
```json
{
  "message": "Orders loaded successfully",
  "ordersLoaded": 1500,
  "startDate": "2025-01-02T00:00:00",
  "endDate": "2025-01-08T23:59:59",
  "filesProcessed": [
    "_pedidos_SPIM_",
    "_pedidos_EBCI_",
    "_pedidos_UBBK_",
    "..."
  ]
}
Status: 200
```

**Si no tienes el endpoint todavía**, chequea con:
```bash
curl -s http://localhost:8080/api/data/load-orders 2>&1 | grep -q "404" && echo "❌ Endpoint NO existe" || echo "✅ Endpoint existe"
```

---

## **Paso 4: Ejecutar Algoritmo Weekly (7 días)**

### **Opción A: Usando endpoint /weekly** (Recomendado)

```bash
curl -X POST "http://localhost:8080/api/algorithm/weekly" \
  -H "Content-Type: application/json" \
  -d '{
    "simulationStartTime": "2025-01-02T00:00:00",
    "simulationDurationDays": 7,
    "useDatabase": true
  }' \
  -w "\nStatus: %{http_code}\nTime: %{time_total}s\n" \
  | jq '.'
```

### **Opción B: Usando endpoint /execute** (Legacy)

```bash
curl -X POST "http://localhost:8080/api/algorithm/execute" \
  -H "Content-Type: application/json" \
  -d '{
    "simulationStartTime": "2025-01-02T00:00:00",
    "simulationEndTime": "2025-01-09T00:00:00",
    "useDatabase": true
  }' \
  -w "\nStatus: %{http_code}\nTime: %{time_total}s\n" \
  | jq '.'
```

**Resultado esperado** (toma 30-90 minutos):
```json
{
  "success": true,
  "executionTime": "45 minutes 23 seconds",
  "simulationStartTime": "2025-01-02T00:00:00",
  "simulationEndTime": "2025-01-09T00:00:00",
  "totalOrders": 1500,
  "assignedOrders": 1450,
  "unassignedOrders": 50,
  "totalProducts": 45000,
  "assignedProducts": 43500,
  "unassignedProducts": 1500,
  "flightInstancesUsed": 245,
  "totalFlightInstances": 700
}
Status: 200
Time: 2723.45s
```

---

## **Paso 5: Verificar Resultados en Base de Datos**

### **5.1: Ver productos asignados**

```bash
# Contar productos asignados
curl -s "http://localhost:8080/api/products?status=IN_TRANSIT" | jq '.length'

# Ver primeros 5 productos con flight instance
curl -s "http://localhost:8080/api/products?limit=5" | jq '.[] | {
  id: .id,
  orderId: .orderId,
  status: .status,
  assignedFlightInstance: .assignedFlightInstance,
  assignedFlight: .assignedFlight
}'
```

**Resultado esperado**:
```json
{
  "id": 1,
  "orderId": 123,
  "status": "IN_TRANSIT",
  "assignedFlightInstance": "FL-45-DAY-0-2000",
  "assignedFlight": "SPIM-SPZO"
}
{
  "id": 2,
  "orderId": 123,
  "status": "IN_TRANSIT",
  "assignedFlightInstance": "FL-45-DAY-0-2000",
  "assignedFlight": "SPIM-SPZO"
}
...
```

### **5.2: Verificar distribución por día**

```bash
# Query directo a PostgreSQL (si tienes acceso)
psql -h localhost -p 5435 -U postgres -d postgres -c "
SELECT
  SUBSTRING(assigned_flight_instance FROM 'DAY-(\d+)') as day,
  COUNT(*) as products_count
FROM products
WHERE assigned_flight_instance IS NOT NULL
GROUP BY SUBSTRING(assigned_flight_instance FROM 'DAY-(\d+)')
ORDER BY day;
"
```

**Resultado esperado**:
```
 day | products_count
-----+----------------
 0   |           6200
 1   |           6150
 2   |           6300
 3   |           6450
 4   |           6100
 5   |           6200
 6   |           6100
(7 rows)
```

### **5.3: Ver vuelos más usados**

```bash
psql -h localhost -p 5435 -U postgres -d postgres -c "
SELECT
  assigned_flight_instance,
  COUNT(*) as products_assigned
FROM products
WHERE assigned_flight_instance IS NOT NULL
GROUP BY assigned_flight_instance
ORDER BY products_assigned DESC
LIMIT 10;
"
```

---

## **Paso 6: Probar Re-Run (Segunda ejecución)**

### **6.1: Ejecutar algoritmo de nuevo (mismo período)**

```bash
echo "=== SEGUNDA EJECUCIÓN (RE-RUN) ==="
curl -X POST "http://localhost:8080/api/algorithm/weekly" \
  -H "Content-Type: application/json" \
  -d '{
    "simulationStartTime": "2025-01-02T00:00:00",
    "simulationDurationDays": 7,
    "useDatabase": true
  }' \
  -w "\nStatus: %{http_code}\n" \
  | jq '.'
```

**Resultado esperado**:
- Debe terminar MUCHO más rápido (pocos segundos)
- `assignedProducts` debe ser similar a la primera ejecución
- En los logs debe aparecer: `"[PREFILL] Flight instances with pre-assignments: 245"`

### **6.2: Verificar que NO duplicó productos**

```bash
# Contar total de productos
psql -h localhost -p 5435 -U postgres -d postgres -c "
SELECT COUNT(*) as total_products FROM products;
"
```

**Debe ser el mismo número que después de la primera ejecución** (no debe duplicarse)

---

## **Paso 7: Probar Incremental Daily (30 minutos)**

### **7.1: Primera ventana (00:00 - 08:00)**

```bash
curl -X POST "http://localhost:8080/api/algorithm/daily" \
  -H "Content-Type: application/json" \
  -d '{
    "simulationStartTime": "2025-01-02T00:00:00",
    "simulationDurationHours": 8,
    "useDatabase": true
  }' \
  -w "\nStatus: %{http_code}\n" \
  | jq '.'
```

### **7.2: Segunda ventana (08:00 - 16:00) - RE-RUN**

```bash
curl -X POST "http://localhost:8080/api/algorithm/daily" \
  -H "Content-Type: application/json" \
  -d '{
    "simulationStartTime": "2025-01-02T08:00:00",
    "simulationDurationHours": 8,
    "useDatabase": true
  }' \
  -w "\nStatus: %{http_code}\n" \
  | jq '.'
```

**Verifica en logs**:
```
[PREFILL] Flight instance FL-X-DAY-Y-HHMM: 50 products pre-assigned
```

---

## 🧪 **Script de Prueba Completo**

Crea este archivo para ejecutar todo de una vez:

```bash
#!/bin/bash
# test_morapack.sh

echo "=========================================="
echo "MORAPACK FLIGHT INSTANCE TEST"
echo "=========================================="

BASE_URL="http://localhost:8080"

# Colores
GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Función para verificar respuesta
check_response() {
    if [ $1 -eq 200 ] || [ $1 -eq 201 ]; then
        echo -e "${GREEN}✅ SUCCESS${NC}"
        return 0
    else
        echo -e "${RED}❌ FAILED (HTTP $1)${NC}"
        return 1
    fi
}

echo ""
echo "1️⃣  Verificando backend..."
STATUS=$(curl -s -o /dev/null -w "%{http_code}" $BASE_URL/actuator/health)
check_response $STATUS || exit 1

echo ""
echo "2️⃣  Cargando airports..."
STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST $BASE_URL/api/data-import/airports)
check_response $STATUS

echo ""
echo "3️⃣  Cargando flights..."
STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST $BASE_URL/api/data-import/flights)
check_response $STATUS

echo ""
echo "4️⃣  Cargando orders (2025-01-02 a 2025-01-08)..."
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/data/load-orders" \
  -H "Content-Type: application/json" \
  -d '{
    "startDate": "2025-01-02T00:00:00",
    "endDate": "2025-01-08T23:59:59"
  }')

STATUS=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | head -n-1)

check_response $STATUS
echo "$BODY" | jq '.'

echo ""
echo "5️⃣  Ejecutando algoritmo WEEKLY (esto tomará 30-90 minutos)..."
echo "Inicio: $(date)"

START_TIME=$(date +%s)
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/algorithm/weekly" \
  -H "Content-Type: application/json" \
  -d '{
    "simulationStartTime": "2025-01-02T00:00:00",
    "simulationDurationDays": 7,
    "useDatabase": true
  }')

END_TIME=$(date +%s)
DURATION=$((END_TIME - START_TIME))

STATUS=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | head -n-1)

check_response $STATUS
echo "Duración: $((DURATION / 60)) minutos $((DURATION % 60)) segundos"
echo "$BODY" | jq '.'

echo ""
echo "6️⃣  Verificando productos en base de datos..."
PRODUCT_COUNT=$(psql -h localhost -p 5435 -U postgres -d postgres -t -c "SELECT COUNT(*) FROM products WHERE assigned_flight_instance IS NOT NULL;")
echo "Productos con flight instance asignado: $PRODUCT_COUNT"

echo ""
echo "7️⃣  Ejecutando RE-RUN (debe ser rápido)..."
START_TIME=$(date +%s)
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/algorithm/weekly" \
  -H "Content-Type: application/json" \
  -d '{
    "simulationStartTime": "2025-01-02T00:00:00",
    "simulationDurationDays": 7,
    "useDatabase": true
  }')

END_TIME=$(date +%s)
DURATION=$((END_TIME - START_TIME))

STATUS=$(echo "$RESPONSE" | tail -n1)
check_response $STATUS
echo "Duración: $DURATION segundos (debe ser < 60s)"

echo ""
echo "8️⃣  Verificando que NO duplicó productos..."
NEW_PRODUCT_COUNT=$(psql -h localhost -p 5435 -U postgres -d postgres -t -c "SELECT COUNT(*) FROM products WHERE assigned_flight_instance IS NOT NULL;")
echo "Productos antes: $PRODUCT_COUNT"
echo "Productos después: $NEW_PRODUCT_COUNT"

if [ "$PRODUCT_COUNT" -eq "$NEW_PRODUCT_COUNT" ]; then
    echo -e "${GREEN}✅ NO duplicó productos (correcto)${NC}"
else
    echo -e "${RED}❌ DUPLICÓ productos (incorrecto)${NC}"
fi

echo ""
echo "=========================================="
echo "TEST COMPLETO"
echo "=========================================="
```

**Ejecutar**:
```bash
chmod +x test_morapack.sh
./test_morapack.sh
```

---

## 📊 **Consultas SQL Útiles**

### Ver distribución de productos por día
```sql
SELECT
  SUBSTRING(assigned_flight_instance FROM 'DAY-(\d+)') as day,
  COUNT(*) as products
FROM products
WHERE assigned_flight_instance IS NOT NULL
GROUP BY SUBSTRING(assigned_flight_instance FROM 'DAY-(\d+)')
ORDER BY day;
```

### Ver vuelos con más productos
```sql
SELECT
  assigned_flight_instance,
  COUNT(*) as products,
  ROUND(COUNT(*) * 100.0 / SUM(COUNT(*)) OVER (), 2) as percentage
FROM products
WHERE assigned_flight_instance IS NOT NULL
GROUP BY assigned_flight_instance
ORDER BY products DESC
LIMIT 20;
```

### Ver órdenes y sus productos asignados
```sql
SELECT
  o.name as order_name,
  o.status as order_status,
  COUNT(p.id) as total_products,
  COUNT(p.assigned_flight_instance) as assigned_products,
  STRING_AGG(DISTINCT p.assigned_flight_instance, ', ') as flight_instances
FROM orders o
LEFT JOIN products p ON p.order_id = o.id
GROUP BY o.id, o.name, o.status
LIMIT 20;
```

---

## 🆘 **Troubleshooting**

### Error: "Endpoint not found"
```bash
# Verificar endpoints disponibles
curl -s http://localhost:8080/actuator/mappings | jq '.contexts.application.mappings.dispatcherServlets.dispatcherServlet[].predicate' | grep algorithm
```

### Error: "Column assigned_flight_instance does not exist"
```bash
# Verificar que Hibernate creó la columna
psql -h localhost -p 5435 -U postgres -d postgres -c "\d products"
# Debe mostrar: assigned_flight_instance | character varying(100)
```

### El algoritmo no carga asignaciones existentes
```bash
# Verificar que hay productos con flight instance
psql -h localhost -p 5435 -U postgres -d postgres -c "SELECT COUNT(*) FROM products WHERE assigned_flight_instance IS NOT NULL;"

# Ver logs del backend
tail -f logs/spring.log | grep -i "prefill"
```

---

¡Listo! Usa estos comandos en orden y podrás probar todo el flujo completo. 🚀
