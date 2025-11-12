#!/bin/bash
# TEST_SIMULATION.sh - Prueba completa del sistema de simulación de tiempo

BASE_URL="http://localhost:8080"
DB_HOST="localhost"
DB_PORT="5435"
DB_USER="postgres"
DB_NAME="postgres"

# Colores
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}=========================================="
echo "TEST: SIMULACIÓN DE TIEMPO"
echo -e "==========================================${NC}"

# Verificar que backend está corriendo
echo -e "${YELLOW}Verificando backend...${NC}"
STATUS=$(curl -s -o /dev/null -w "%{http_code}" $BASE_URL/actuator/health 2>/dev/null || echo "000")
if [ "$STATUS" != "200" ]; then
    echo -e "${RED}❌ Backend no está corriendo en $BASE_URL${NC}"
    echo "Ejecuta: mvn spring-boot:run"
    exit 1
fi
echo -e "${GREEN}✅ Backend corriendo${NC}"

# ==========================================
# PASO 1: Cargar orders (1 día de prueba)
# ==========================================
echo ""
echo -e "${YELLOW}1️⃣  Cargando orders (2025-01-02 - 1 día)...${NC}"
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/data/load-orders" \
  -H "Content-Type: application/json" \
  -d '{
    "startDate": "2025-01-02T00:00:00",
    "endDate": "2025-01-02T23:59:59"
  }')

STATUS=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | head -n-1)

if [ "$STATUS" = "200" ] || [ "$STATUS" = "201" ]; then
    echo -e "${GREEN}✅ Orders cargadas${NC}"
    echo "$BODY" | jq '.' 2>/dev/null || echo "$BODY"
else
    echo -e "${RED}❌ Error cargando orders (HTTP $STATUS)${NC}"
    echo "$BODY"
    exit 1
fi

# ==========================================
# PASO 2: Ejecutar algoritmo WEEKLY (1 día)
# ==========================================
echo ""
echo -e "${YELLOW}2️⃣  Ejecutando algoritmo (1 día - más rápido para testing)...${NC}"
echo -e "${BLUE}Inicio: $(date)${NC}"

START_TIME=$(date +%s)

RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/algorithm/weekly" \
  -H "Content-Type: application/json" \
  -d '{
    "simulationStartTime": "2025-01-02T00:00:00",
    "simulationDurationDays": 1,
    "useDatabase": true
  }')

END_TIME=$(date +%s)
DURATION=$((END_TIME - START_TIME))

STATUS=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | head -n-1)

echo -e "${BLUE}Fin: $(date)${NC}"
echo -e "${BLUE}Duración: $((DURATION / 60)) min $((DURATION % 60)) seg${NC}"

if [ "$STATUS" != "200" ] && [ "$STATUS" != "201" ]; then
    echo -e "${RED}❌ Error ejecutando algoritmo (HTTP $STATUS)${NC}"
    echo "$BODY"
    exit 1
fi

echo -e "${GREEN}✅ Algoritmo ejecutado${NC}"
echo "$BODY" | jq '.assignedProducts, .unassignedProducts' 2>/dev/null || echo "$BODY"

# ==========================================
# PASO 3: Verificar estados iniciales
# ==========================================
echo ""
echo -e "${YELLOW}3️⃣  Verificando estados iniciales en DB...${NC}"

psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -c "
SELECT
  status,
  COUNT(*) as count
FROM products
GROUP BY status
ORDER BY status;
" 2>/dev/null

INITIAL_IN_TRANSIT=$(psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -t -c "SELECT COUNT(*) FROM products WHERE status = 'IN_TRANSIT';" 2>/dev/null | xargs)

echo ""
echo -e "${BLUE}Productos IN_TRANSIT inicialmente: $INITIAL_IN_TRANSIT${NC}"

if [ -z "$INITIAL_IN_TRANSIT" ] || [ "$INITIAL_IN_TRANSIT" = "0" ]; then
    echo -e "${YELLOW}⚠️  No hay productos IN_TRANSIT. Verifica que el algoritmo asignó vuelos.${NC}"
fi

# ==========================================
# PASO 4: Avanzar tiempo +8 horas
# ==========================================
echo ""
echo -e "${YELLOW}4️⃣  Avanzando tiempo +8 horas (2025-01-02 08:00)...${NC}"

RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/simulation/update-states" \
  -H "Content-Type: application/json" \
  -d '{
    "currentTime": "2025-01-02T08:00:00"
  }')

STATUS=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | head -n-1)

if [ "$STATUS" = "200" ] || [ "$STATUS" = "201" ]; then
    echo -e "${GREEN}✅ Tiempo avanzado a 08:00${NC}"
    echo "$BODY" | jq '.transitions' 2>/dev/null || echo "$BODY"
else
    echo -e "${RED}❌ Error avanzando tiempo (HTTP $STATUS)${NC}"
    echo "$BODY"
fi

# Ver estados
echo ""
echo "Estados después de 8 horas:"
psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -c "
SELECT
  status,
  COUNT(*) as count
FROM products
GROUP BY status
ORDER BY status;
" 2>/dev/null

# ==========================================
# PASO 5: Avanzar tiempo +12 horas más
# ==========================================
echo ""
echo -e "${YELLOW}5️⃣  Avanzando tiempo +12 horas más (2025-01-02 20:00)...${NC}"

RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/simulation/update-states" \
  -H "Content-Type: application/json" \
  -d '{
    "currentTime": "2025-01-02T20:00:00"
  }')

STATUS=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | head -n-1)

if [ "$STATUS" = "200" ] || [ "$STATUS" = "201" ]; then
    echo -e "${GREEN}✅ Tiempo avanzado a 20:00${NC}"
    echo "$BODY" | jq '.transitions' 2>/dev/null || echo "$BODY"
else
    echo -e "${RED}❌ Error avanzando tiempo (HTTP $STATUS)${NC}"
    echo "$BODY"
fi

# Ver estados
echo ""
echo "Estados después de 20 horas:"
psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -c "
SELECT
  status,
  COUNT(*) as count
FROM products
GROUP BY status
ORDER BY status;
" 2>/dev/null

# ==========================================
# PASO 6: Día siguiente completo
# ==========================================
echo ""
echo -e "${YELLOW}6️⃣  Avanzando al día siguiente (2025-01-03 12:00)...${NC}"

RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/simulation/update-states" \
  -H "Content-Type: application/json" \
  -d '{
    "currentTime": "2025-01-03T12:00:00"
  }')

STATUS=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | head -n-1)

if [ "$STATUS" = "200" ] || [ "$STATUS" = "201" ]; then
    echo -e "${GREEN}✅ Tiempo avanzado a día siguiente${NC}"
    echo "$BODY" | jq '.transitions' 2>/dev/null || echo "$BODY"
else
    echo -e "${RED}❌ Error avanzando tiempo (HTTP $STATUS)${NC}"
    echo "$BODY"
fi

# Ver estados finales
echo ""
echo "Estados después de 36 horas:"
psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -c "
SELECT
  status,
  COUNT(*) as count
FROM products
GROUP BY status
ORDER BY status;
" 2>/dev/null

# ==========================================
# RESUMEN
# ==========================================
echo ""
echo -e "${BLUE}=========================================="
echo "RESUMEN"
echo -e "==========================================${NC}"

ARRIVED=$(psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -t -c "SELECT COUNT(*) FROM products WHERE status = 'ARRIVED';" 2>/dev/null | xargs)
DELIVERED=$(psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -t -c "SELECT COUNT(*) FROM products WHERE status = 'DELIVERED';" 2>/dev/null | xargs)
IN_TRANSIT=$(psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -t -c "SELECT COUNT(*) FROM products WHERE status = 'IN_TRANSIT';" 2>/dev/null | xargs)

echo ""
if [ -n "$ARRIVED" ] && [ "$ARRIVED" -gt "0" ]; then
    echo -e "${GREEN}✅ Productos ARRIVED: $ARRIVED${NC}"
else
    echo -e "${YELLOW}⚠️  Productos ARRIVED: 0${NC}"
fi

if [ -n "$DELIVERED" ] && [ "$DELIVERED" -gt "0" ]; then
    echo -e "${GREEN}✅ Productos DELIVERED: $DELIVERED${NC}"
else
    echo -e "${YELLOW}⚠️  Productos DELIVERED: 0${NC}"
fi

echo -e "${BLUE}Productos IN_TRANSIT: $IN_TRANSIT${NC}"

echo ""
echo -e "${BLUE}=========================================="
echo "TEST COMPLETO"
echo -e "==========================================${NC}"

# Verificar que funcionó
if [ -n "$ARRIVED" ] && [ "$ARRIVED" -gt "0" ]; then
    echo -e "${GREEN}✅ La simulación de tiempo funciona correctamente${NC}"
    exit 0
else
    echo -e "${YELLOW}⚠️  No se detectaron transiciones de estado. Verifica los logs del backend.${NC}"
    exit 1
fi
