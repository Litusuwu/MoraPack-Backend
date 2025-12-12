-- Reset simulation state
UPDATE products SET status = 'PENDING', assigned_flight = NULL, assigned_flight_instance = NULL;
UPDATE orders SET status = 'PENDING';
DELETE FROM flight_instances;
DELETE FROM product_flights;
DELETE FROM warehouse_occupancy_snapshots;
