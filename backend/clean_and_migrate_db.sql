-- Script para limpiar la BD y migrar estados ASSIGNED a PENDING
-- Fecha: 2025-12-03

-- 1. PRIMERO: Migrar estados ASSIGNED a PENDING (si existen registros)
UPDATE products SET status = 'PENDING' WHERE status = 'ASSIGNED';
UPDATE orders SET status = 'PENDING' WHERE status = 'ASSIGNED';

SELECT 'Migración completada: ASSIGNED → PENDING' as mensaje;

-- 2. SEGUNDO: Limpiar base de datos (orden correcto por foreign keys)
-- Eliminar en orden: product_flights → products → orders → flights → warehouses → airports → cities

-- Eliminar product_flights (tabla intermedia)
DELETE FROM product_flights;
SELECT 'Eliminados product_flights' as mensaje;

-- Eliminar productos
DELETE FROM products;
SELECT 'Eliminados products' as mensaje;

-- Eliminar órdenes
DELETE FROM orders;
SELECT 'Eliminadas orders' as mensaje;

-- Eliminar flight_instances
DELETE FROM flight_instances;
SELECT 'Eliminados flight_instances' as mensaje;

-- Eliminar vuelos
DELETE FROM flights;
SELECT 'Eliminados flights' as mensaje;

-- Eliminar warehouse_occupancy_snapshots
DELETE FROM warehouse_occupancy_snapshots;
SELECT 'Eliminados warehouse_occupancy_snapshots' as mensaje;

-- Eliminar almacenes
DELETE FROM warehouses;
SELECT 'Eliminados warehouses' as mensaje;

-- Eliminar aeropuertos
DELETE FROM airports;
SELECT 'Eliminados airports' as mensaje;

-- Eliminar ciudades
DELETE FROM cities;
SELECT 'Eliminadas cities' as mensaje;

-- 3. Resetear secuencias (opcional, para que los IDs empiecen desde 1)
ALTER SEQUENCE IF EXISTS cities_id_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS airports_id_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS warehouses_id_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS flights_id_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS orders_id_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS products_id_seq RESTART WITH 1;

SELECT '✅ Base de datos limpiada y migrada exitosamente' as resultado;
