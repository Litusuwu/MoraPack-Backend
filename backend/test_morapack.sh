#!/bin/bash
# test_morapack.sh - Script para probar el sistema completo de MoraPack

BASE_URL="http://localhost:8080"

# Colores
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}=========================================="
echo "MORAPACK FLIGHT INSTANCE TEST"
echo -e "==========================================${NC}"

# Función para verificar respuesta
check_response() {
    if [ $1 -eq 200 ] || [ $1 -eq 201 ]; then
        echo -e "${GREEN}✅ SUCCESS (HTTP $1)${NC}"
        return 0
    else
        echo -e "${RED}❌ FAILED (HTTP $1)${NC}"
        return 1
    fi
}

# Función para mostrar paso
show_step() {
    echo ""
    echo -e "${YELLOW}$1${NC}"
}

# 1. Verificar backend
show_step "1️⃣  Verificando que el backend está corriendo..."
STATUS=$(curl -s -o /dev/null -w "%{http_code}" $BASE_URL/actuator/health 2>/dev/null || echo "000")
if [ "$STATUS" = "000" ]; then
    echo -e "${RED}❌ Backend NO está corriendo en $BASE_URL${NC}"
    echo "Ejecuta: mvn spring-boot:run"
    exit 1
fi
check_response $STATUS

# 2. Cargar airports
# show_step "2️⃣  Cargando airports..."
# RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/data-import/airports" -H "Content-Type: application/json")
# STATUS=$(echo "$RESPONSE" | tail -n1)
# BODY=$(echo "$RESPONSE" | head -n-1)
# check_response $STATUS
# echo "$BODY"

# # 3. Cargar flights
# show_step "3️⃣  Cargando flights..."
# RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/data-import/flights" -H "Content-Type: application/json")
# STATUS=$(echo "$RESPONSE" | tail -n1)
# BODY=$(echo "$RESPONSE" | head -n-1)
# check_response $STATUS
# echo "$BODY"

# 4. Cargar orders (1 semana: 2025-01-02 a 2025-01-08)
show_step "4️⃣  Cargando orders (2025-01-02 a 2025-01-08)..."
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/data/load-orders" \
  -H "Content-Type: application/json" \
  -d '{
    "startDate": "2025-01-02T00:00:00",
    "endDate": "2025-01-08T23:59:59"
  }')

STATUS=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | head -n-1)

if check_response $STATUS; then
    echo "$BODY" | jq '.' 2>/dev/null || echo "$BODY"
else
    echo "$BODY"
    echo -e "${YELLOW}⚠️  Si el endpoint no existe, verifica que DataLoadAPI esté implementado${NC}"
fi

# 5. Ejecutar algoritmo WEEKLY
show_step "5️⃣  Ejecutando algoritmo WEEKLY (esto tomará 30-90 minutos)..."
echo -e "${BLUE}Inicio: $(date)${NC}"
echo -e "${YELLOW}⏳ Espera... esto puede tomar mucho tiempo${NC}"

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

echo -e "${BLUE}Fin: $(date)${NC}"
echo -e "${BLUE}Duración: $((DURATION / 60)) minutos $((DURATION % 60)) segundos${NC}"

if check_response $STATUS; then
    echo "$BODY" | jq '.' 2>/dev/null || echo "$BODY"
else
    echo "$BODY"
fi

# 6. Verificar productos en base de datos
show_step "6️⃣  Verificando productos en base de datos..."

# Intentar conectar a PostgreSQL
PRODUCT_COUNT=$(psql -h localhost -p 5435 -U postgres -d postgres -t -c "SELECT COUNT(*) FROM products WHERE assigned_flight_instance IS NOT NULL;" 2>/dev/null | xargs)

if [ -n "$PRODUCT_COUNT" ]; then
    echo -e "${GREEN}Productos con flight instance asignado: $PRODUCT_COUNT${NC}"

    # Mostrar distribución por día
    echo ""
    echo "Distribución por día:"
    psql -h localhost -p 5435 -U postgres -d postgres -c "
    SELECT
      SUBSTRING(assigned_flight_instance FROM 'DAY-(\d+)') as day,
      COUNT(*) as products
    FROM products
    WHERE assigned_flight_instance IS NOT NULL
    GROUP BY SUBSTRING(assigned_flight_instance FROM 'DAY-(\d+)')
    ORDER BY day;
    " 2>/dev/null
else
    echo -e "${YELLOW}⚠️  No se pudo conectar a PostgreSQL o no hay productos asignados${NC}"
    echo "Verifica la conexión: psql -h localhost -p 5435 -U postgres"
fi

# 7. Ejecutar RE-RUN
show_step "7️⃣  Ejecutando RE-RUN (debe ser RÁPIDO - menos de 1 minuto)..."
echo -e "${YELLOW}Ejecutando el mismo algoritmo de nuevo para verificar re-run support...${NC}"

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

echo -e "${BLUE}Duración del re-run: $DURATION segundos${NC}"

if check_response $STATUS; then
    if [ $DURATION -lt 60 ]; then
        echo -e "${GREEN}✅ Re-run fue rápido (< 60s) - ¡Funciona!${NC}"
    else
        echo -e "${YELLOW}⚠️  Re-run tomó más de 60s - puede que no esté cargando asignaciones existentes${NC}"
    fi
    echo "$BODY" | jq '.' 2>/dev/null || echo "$BODY"
fi

# 8. Verificar que NO duplicó productos
if [ -n "$PRODUCT_COUNT" ]; then
    show_step "8️⃣  Verificando que NO duplicó productos..."

    NEW_PRODUCT_COUNT=$(psql -h localhost -p 5435 -U postgres -d postgres -t -c "SELECT COUNT(*) FROM products WHERE assigned_flight_instance IS NOT NULL;" 2>/dev/null | xargs)

    echo "Productos antes del re-run: $PRODUCT_COUNT"
    echo "Productos después del re-run: $NEW_PRODUCT_COUNT"

    if [ "$PRODUCT_COUNT" = "$NEW_PRODUCT_COUNT" ]; then
        echo -e "${GREEN}✅ NO duplicó productos - ¡Re-run funciona correctamente!${NC}"
    else
        echo -e "${RED}❌ DUPLICÓ productos - hay un problema con el re-run${NC}"
    fi
fi

# 9. Probar algoritmo DAILY incremental (opcional)
show_step "9️⃣  ¿Quieres probar el algoritmo DAILY incremental? (s/n)"
read -t 5 -p "Responde en 5 segundos (default: n): " TEST_DAILY
TEST_DAILY=${TEST_DAILY:-n}

if [ "$TEST_DAILY" = "s" ] || [ "$TEST_DAILY" = "S" ]; then
    echo ""
    echo -e "${BLUE}Ejecutando algoritmo DAILY (ventana de 8 horas)...${NC}"

    RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/algorithm/daily" \
      -H "Content-Type: application/json" \
      -d '{
        "simulationStartTime": "2025-01-02T00:00:00",
        "simulationDurationHours": 8,
        "useDatabase": true
      }')

    STATUS=$(echo "$RESPONSE" | tail -n1)
    BODY=$(echo "$RESPONSE" | head -n-1)

    if check_response $STATUS; then
        echo "$BODY" | jq '.' 2>/dev/null || echo "$BODY"
    fi
fi

# Resumen final
echo ""
echo -e "${BLUE}=========================================="
echo "TEST COMPLETO"
echo -e "==========================================${NC}"
echo ""
echo "Revisa los logs del backend para más detalles:"
echo "  - Busca: 'EXPANDING FLIGHTS FOR SIMULATION'"
echo "  - Busca: 'PREFILL'"
echo "  - Busca: 'FlightInstanceManager initialized'"
echo ""
echo "Consultas SQL útiles:"
echo "  psql -h localhost -p 5435 -U postgres -d postgres"
echo "  SELECT * FROM products WHERE assigned_flight_instance IS NOT NULL LIMIT 10;"
echo ""
