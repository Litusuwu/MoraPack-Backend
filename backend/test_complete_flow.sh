#!/bin/bash

# Script completo para probar el flujo de simulación de estados
# 1. Limpia BD
# 2. Carga datos
# 3. Ejecuta algoritmo
# 4. Simula avance de tiempo

BASE_URL="http://localhost:8080"
SIMULATION_START="2025-01-02T00:00:00"

echo "=========================================="
echo "TEST COMPLETO: Flujo de Simulación"
echo "=========================================="
echo ""

# Verificar que el backend esté corriendo
echo "Verificando backend..."
if ! curl -s "$BASE_URL/actuator/health" > /dev/null 2>&1; then
    echo "❌ ERROR: Backend no está corriendo en $BASE_URL"
    echo "Por favor inicia el backend con: mvn spring-boot:run"
    exit 1
fi
echo "✅ Backend está corriendo"
echo ""

# 1. Limpiar base de datos
echo "=========================================="
echo "1. Limpiando base de datos..."
echo "=========================================="
docker exec -i morapack-postgres psql -U postgres -d postgres << EOF
DELETE FROM product_flights;
DELETE FROM products;
DELETE FROM orders;
DELETE FROM flight_instances;
DELETE FROM flights;
DELETE FROM warehouse_occupancy_snapshots;
DELETE FROM warehouses;
DELETE FROM airports;
DELETE FROM cities;
EOF
echo "✅ Base de datos limpiada"
echo ""

# 2. Cargar datos
echo "=========================================="
echo "2. Cargando datos..."
echo "=========================================="

echo "Cargando aeropuertos..."
curl -s -X POST "$BASE_URL/api/data-import/airports" > /dev/null
echo "✅ Aeropuertos cargados"

echo "Cargando vuelos..."
curl -s -X POST "$BASE_URL/api/data-import/flights" > /dev/null
echo "✅ Vuelos cargados"

echo "Cargando órdenes (esto puede tomar un momento)..."
curl -s -X POST "$BASE_URL/api/data/load-orders" \
    -H "Content-Type: application/json" \
    -d "{
        \"startDate\": \"2025-01-02T00:00:00\",
        \"endDate\": \"2025-01-03T23:59:59\"
    }" > /dev/null
echo "✅ Órdenes cargadas"
echo ""

# Verificar datos cargados
echo "Datos cargados:"
docker exec -i morapack-postgres psql -U postgres -d postgres -c \
    "SELECT
        (SELECT COUNT(*) FROM cities) as cities,
        (SELECT COUNT(*) FROM airports) as airports,
        (SELECT COUNT(*) FROM flights) as flights,
        (SELECT COUNT(*) FROM orders) as orders;"
echo ""

# 3. Ejecutar algoritmo
echo "=========================================="
echo "3. Ejecutando algoritmo ALNS..."
echo "=========================================="

algorithm_response=$(curl -s -X POST "$BASE_URL/api/algorithm/daily" \
    -H "Content-Type: application/json" \
    -d "{
        \"simulationStartTime\": \"$SIMULATION_START\",
        \"simulationDurationHours\": 24,
        \"useDatabase\": true
    }")

echo "Resultado del algoritmo:"
echo "$algorithm_response" | jq '.success, .message, .totalOrders, .assignedOrders, .unassignedOrders' 2>/dev/null || echo "$algorithm_response"
echo ""

# Verificar productos creados
echo "Productos creados por el algoritmo:"
docker exec -i morapack-postgres psql -U postgres -d postgres -c \
    "SELECT status, COUNT(*) as count FROM products GROUP BY status ORDER BY status;"
echo ""

# 4. Simular avance de tiempo (cada 10 minutos durante 3 horas)
echo "=========================================="
echo "4. Simulando avance de tiempo..."
echo "=========================================="

# Convertir fecha base a timestamp
if [[ "$OSTYPE" == "darwin"* ]]; then
    base_timestamp=$(date -j -f "%Y-%m-%dT%H:%M:%S" "$SIMULATION_START" "+%s")
else
    base_timestamp=$(date -d "$SIMULATION_START" "+%s")
fi

# Solo hacer 6 iteraciones para no ser demasiado largo (1 hora)
for i in {0..6}; do
    minutes_to_add=$((i * 10))
    new_timestamp=$((base_timestamp + minutes_to_add * 60))

    if [[ "$OSTYPE" == "darwin"* ]]; then
        current_time=$(date -j -f "%s" "$new_timestamp" "+%Y-%m-%dT%H:%M:%S")
    else
        current_time=$(date -d "@$new_timestamp" "+%Y-%m-%dT%H:%M:%S")
    fi

    echo "----------------------------------------"
    echo "Tiempo: $current_time (+ ${minutes_to_add} min)"
    echo "----------------------------------------"

    # Actualizar estados
    response=$(curl -s -X POST "$BASE_URL/api/simulation/update-states" \
        -H "Content-Type: application/json" \
        -d "{\"currentTime\": \"$current_time\"}")

    echo "Transiciones:"
    echo "$response" | jq '.transitions' 2>/dev/null || echo "Error parseando respuesta"

    echo "Estados actuales:"
    docker exec -i morapack-postgres psql -U postgres -d postgres -t -c \
        "SELECT status || ': ' || COUNT(*) FROM products GROUP BY status ORDER BY status;" | tr '\n' ' '
    echo ""
    echo ""

    sleep 1
done

# 5. Saltar al día siguiente para ver productos IN_TRANSIT y ARRIVED
echo "=========================================="
echo "5. Avanzando al día siguiente..."
echo "=========================================="

# Saltar a 2025-01-03 12:00:00 (36 horas después)
next_day="2025-01-03T12:00:00"
echo "Tiempo: $next_day"

response=$(curl -s -X POST "$BASE_URL/api/simulation/update-states" \
    -H "Content-Type: application/json" \
    -d "{\"currentTime\": \"$next_day\"}")

echo "Transiciones:"
echo "$response" | jq '.transitions' 2>/dev/null

echo ""
echo "Estados finales:"
docker exec -i morapack-postgres psql -U postgres -d postgres -c \
    "SELECT status, COUNT(*) as count,
     ROUND(COUNT(*) * 100.0 / SUM(COUNT(*)) OVER (), 2) as percentage
     FROM products
     GROUP BY status
     ORDER BY status;"

echo ""
echo "=========================================="
echo "Test completado"
echo "=========================================="
