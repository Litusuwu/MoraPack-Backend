-- Manual SQL to add flight instance support
-- Run this if you prefer manual database changes instead of Hibernate auto-update

-- Add the assigned_flight_instance column
ALTER TABLE products
ADD COLUMN IF NOT EXISTS assigned_flight_instance VARCHAR(100);

-- Create index for fast lookups during re-runs
CREATE INDEX IF NOT EXISTS idx_products_flight_instance
ON products(assigned_flight_instance)
WHERE assigned_flight_instance IS NOT NULL;

-- Verify the column was added
SELECT column_name, data_type, character_maximum_length
FROM information_schema.columns
WHERE table_name = 'products'
  AND column_name = 'assigned_flight_instance';
