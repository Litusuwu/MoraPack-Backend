#!/bin/bash

# Script to diagnose warehouse capacity state at different points in the simulation

echo "==============================================="
echo "WAREHOUSE CAPACITY DIAGNOSTIC"
echo "==============================================="

echo ""
echo "1. INITIAL STATE (After loading data, before algorithm):"
echo "------------------------------------------------"
psql -h localhost -p 5435 -U postgres -d postgres -c "
  SELECT
    id,
    name,
    is_main_warehouse,
    max_capacity,
    used_capacity,
    CASE
      WHEN is_main_warehouse THEN 'UNLIMITED'
      ELSE CONCAT(ROUND((used_capacity::float / max_capacity::float * 100), 1), '%')
    END as usage_pct
  FROM warehouses
  WHERE used_capacity > 0 OR is_main_warehouse = true
  ORDER BY is_main_warehouse DESC, used_capacity DESC
  LIMIT 20;
"

echo ""
echo "2. PRODUCT STATUS BREAKDOWN:"
echo "------------------------------------------------"
psql -h localhost -p 5435 -U postgres -d postgres -c "
  SELECT
    status,
    COUNT(*) as count,
    ROUND((COUNT(*)::float / SUM(COUNT(*)) OVER () * 100), 1) as percentage
  FROM products
  GROUP BY status
  ORDER BY count DESC;
"

echo ""
echo "3. ORDERS BY STATUS:"
echo "------------------------------------------------"
psql -h localhost -p 5435 -U postgres -d postgres -c "
  SELECT
    status,
    COUNT(*) as count
  FROM orders
  GROUP BY status
  ORDER BY count DESC;
"

echo ""
echo "4. WAREHOUSES WITH CAPACITY CHANGES:"
echo "------------------------------------------------"
echo "Showing all warehouses with used_capacity > 0:"
psql -h localhost -p 5435 -U postgres -d postgres -c "
  SELECT
    w.id,
    w.name,
    w.is_main_warehouse,
    w.used_capacity,
    w.max_capacity,
    c.name as city_name
  FROM warehouses w
  JOIN airports a ON a.warehouse_id = w.id
  JOIN cities c ON a.city_id = c.id
  WHERE w.used_capacity > 0
  ORDER BY w.used_capacity DESC;
"

echo ""
echo "5. SIMULATION TIME CHECK:"
echo "------------------------------------------------"
echo "Checking if there are products with assigned flights:"
psql -h localhost -p 5435 -U postgres -d postgres -c "
  SELECT
    status,
    COUNT(*) as count,
    COUNT(CASE WHEN assigned_flight_instance IS NOT NULL THEN 1 END) as with_flight_instance
  FROM products
  GROUP BY status
  ORDER BY count DESC;
"

echo ""
echo "==============================================="
echo "DIAGNOSTIC COMPLETE"
echo "==============================================="
