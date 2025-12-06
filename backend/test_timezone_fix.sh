#!/bin/bash

# Test rápido para verificar que el timezone fix funciona

BASE_URL="http://localhost:8080"

echo "=========================================="
echo "TEST: Verificación de Timezone Fix"
echo "=========================================="
echo ""

# 1. Verificar timezone de PostgreSQL
echo "1. Timezone de PostgreSQL:"
docker exec -i morapack-postgres psql -U postgres -d postgres -c "SHOW timezone;"
echo ""

# 2. Verificar que las órdenes tienen timestamps correctos
echo "2. Primeras 5 órdenes (deben empezar a las 01:xx, no 06:xx):"
docker exec -i morapack-postgres psql -U postgres -d postgres -c \
    "SELECT id, creation_date FROM orders ORDER BY creation_date LIMIT 5;"
echo ""

# 3. Distribución de horas (debe empezar desde hora 0, no hora 5)
echo "3. Distribución de órdenes por hora:"
docker exec -i morapack-postgres psql -U postgres -d postgres -c \
    "SELECT EXTRACT(HOUR FROM creation_date) as hour, COUNT(*)
     FROM orders
     GROUP BY hour
     ORDER BY hour
     LIMIT 5;"
echo ""

# 4. Verificar orden específica del archivo
echo "4. Verificar si existe la orden del archivo '_pedidos_EBCI_.txt':"
echo "   Archivo: 000000001-20250102-01-38-EBCI-006-0007729"
echo "   Debería tener creation_date cerca de 2025-01-02 01:38:00"
docker exec -i morapack-postgres psql -U postgres -d postgres -c \
    "SELECT id, creation_date, name
     FROM orders
     WHERE creation_date BETWEEN '2025-01-02 01:00:00' AND '2025-01-02 02:00:00'
     LIMIT 5;"
echo ""

echo "=========================================="
echo "✅ Si las horas empiezan desde 0-1-2 (no 5-6-7), el fix funcionó"
echo "=========================================="
