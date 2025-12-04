#!/bin/bash

# Script para ejecutar la migración de ASSIGNED a PENDING
# Uso: ./run_migration.sh

echo "=========================================="
echo "Migración: ASSIGNED → PENDING"
echo "=========================================="
echo ""

# Verificar si Docker Compose está corriendo
if ! docker compose ps | grep -q "db.*Up"; then
    echo "❌ Error: La base de datos no está corriendo."
    echo "Por favor inicia la base de datos con: docker compose up -d"
    exit 1
fi

echo "✅ Base de datos está corriendo"
echo ""

# Ejecutar migración
echo "Ejecutando migración..."
docker compose exec -T db psql -U postgres -d postgres -f - < migrate_assigned_to_pending.sql

echo ""
echo "✅ Migración completada"
echo ""
echo "Ahora puedes reiniciar la aplicación Spring Boot."
