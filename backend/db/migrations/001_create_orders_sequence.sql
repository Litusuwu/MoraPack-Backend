-- Migration: Create sequence for orders table
-- Purpose: Enable Hibernate batch insert optimization
-- Date: 2025-11-06
--
-- This sequence is required for SEQUENCE-based ID generation which supports batching.
-- IDENTITY generation does NOT support batching (each insert must fetch the ID immediately).

-- Create the sequence for orders table
-- START WITH: Set to current max ID + 1 to avoid conflicts
-- INCREMENT BY: Match Hibernate's allocationSize (50)
-- OWNED BY: Link sequence to orders.id column

DO $$
DECLARE
    max_id INTEGER;
BEGIN
    -- Get current max ID from orders table (if table has data)
    SELECT COALESCE(MAX(id), 0) INTO max_id FROM orders;

    -- Drop sequence if it exists
    DROP SEQUENCE IF EXISTS orders_id_seq CASCADE;

    -- Create new sequence starting from max_id + 1
    EXECUTE format('CREATE SEQUENCE orders_id_seq START WITH %s INCREMENT BY 50', max_id + 1);

    -- Set the sequence as owned by orders.id column
    ALTER SEQUENCE orders_id_seq OWNED BY orders.id;

    -- Grant usage to application user (if needed)
    -- GRANT USAGE, SELECT ON SEQUENCE orders_id_seq TO your_app_user;

    RAISE NOTICE 'Sequence orders_id_seq created successfully starting at %', max_id + 1;
END $$;

-- Verify the sequence was created
SELECT
    sequence_name,
    start_value,
    increment_by,
    last_value
FROM information_schema.sequences
WHERE sequence_name = 'orders_id_seq';

-- Verify sequence ownership
SELECT
    pg_get_serial_sequence('orders', 'id') as sequence_name;
