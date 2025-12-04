-- Migración para eliminar el estado ASSIGNED
-- Este script actualiza todos los registros con estado 'ASSIGNED' a 'PENDING'
-- Fecha: 2025-12-03

-- Actualizar productos con estado ASSIGNED a PENDING
UPDATE products
SET status = 'PENDING'
WHERE status = 'ASSIGNED';

-- Actualizar órdenes con estado ASSIGNED a PENDING
UPDATE orders
SET status = 'PENDING'
WHERE status = 'ASSIGNED';

-- Mostrar resultados
SELECT 'Products migrated:', COUNT(*) FROM products WHERE status = 'PENDING';
SELECT 'Orders migrated:', COUNT(*) FROM orders WHERE status = 'PENDING';

-- Verificar que no queden registros con ASSIGNED
SELECT 'Remaining ASSIGNED products:', COUNT(*) FROM products WHERE status = 'ASSIGNED';
SELECT 'Remaining ASSIGNED orders:', COUNT(*) FROM orders WHERE status = 'ASSIGNED';
