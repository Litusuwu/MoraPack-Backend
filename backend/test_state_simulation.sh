#!/bin/bash

# Script para probar la simulación de estados avanzando el tiempo de 10 en 10 minutos
# Similar a la lógica de experiment/run_simulations.py

BASE_URL="http://localhost:8080"
SIMULATION_START="2025-01-02T00:00:00"

echo "=========================================="
echo "TEST: Simulación de Estados con Avance de Tiempo"
echo "=========================================="
echo ""

# Función para avanzar tiempo y actualizar estados
advance_and_check() {
    local current_time=$1
    local iteration=$2

    echo "----------------------------------------"
    echo "Iteración $iteration: $current_time"
    echo "----------------------------------------"

    # Actualizar estados
    response=$(curl -s -X POST "$BASE_URL/api/simulation/update-states" \
        -H "Content-Type: application/json" \
        -d "{\"currentTime\": \"$current_time\"}")

    echo "Respuesta del servidor:"
    echo "$response" | jq '.'

    # Verificar estados en BD
    echo ""
    echo "Estados actuales en BD:"
    docker exec -i morapack-postgres psql -U postgres -d postgres -c \
        "SELECT status, COUNT(*) as count FROM products GROUP BY status ORDER BY status;"

    echo ""
}

# Calcular tiempos de simulación (avanzar 10 minutos cada vez)
# Vamos a simular 3 horas (18 iteraciones de 10 minutos)
echo "Inicio de simulación: $SIMULATION_START"
echo "Avanzando 10 minutos por iteración..."
echo ""

# Convertir fecha base a timestamp para cálculos
if [[ "$OSTYPE" == "darwin"* ]]; then
    # macOS
    base_timestamp=$(date -j -f "%Y-%m-%dT%H:%M:%S" "$SIMULATION_START" "+%s")
else
    # Linux
    base_timestamp=$(date -d "$SIMULATION_START" "+%s")
fi

# Simular avance de tiempo
for i in {0..18}; do
    # Calcular nuevo timestamp (sumar i * 10 minutos)
    minutes_to_add=$((i * 10))
    new_timestamp=$((base_timestamp + minutes_to_add * 60))

    # Convertir de vuelta a formato ISO
    if [[ "$OSTYPE" == "darwin"* ]]; then
        current_time=$(date -j -f "%s" "$new_timestamp" "+%Y-%m-%dT%H:%M:%S")
    else
        current_time=$(date -d "@$new_timestamp" "+%Y-%m-%dT%H:%M:%S")
    fi

    advance_and_check "$current_time" "$i"

    # Pausa de 1 segundo entre iteraciones
    sleep 1
done

echo ""
echo "=========================================="
echo "Simulación completada"
echo "=========================================="
echo ""

# Resumen final
echo "Resumen Final de Estados:"
docker exec -i morapack-postgres psql -U postgres -d postgres -c \
    "SELECT status, COUNT(*) as count,
     ROUND(COUNT(*) * 100.0 / SUM(COUNT(*)) OVER (), 2) as percentage
     FROM products
     GROUP BY status
     ORDER BY status;"

echo ""
echo "Productos de ejemplo con sus tiempos calculados:"
docker exec -i morapack-postgres psql -U postgres -d postgres -c \
    "SELECT id, status, assigned_flight_instance, order_id
     FROM products
     WHERE assigned_flight_instance IS NOT NULL
     LIMIT 10;"
