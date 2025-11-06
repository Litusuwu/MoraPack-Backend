-- Migration: Create product_flights junction table for many-to-many relationship
-- Purpose: Support multi-hop flight routes for products
--
-- Example:
-- Product shipping LIM → AQP → CUZ would have:
-- Row 1: product_id=1, flight_id=5, sequence_order=1 (LIM-AQP)
-- Row 2: product_id=1, flight_id=8, sequence_order=2 (AQP-CUZ)

-- Create product_flights table
CREATE TABLE IF NOT EXISTS product_flights (
    id SERIAL PRIMARY KEY,
    product_id INTEGER NOT NULL,
    flight_id INTEGER NOT NULL,
    sequence_order INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Foreign key constraints
    CONSTRAINT fk_product_flights_product
        FOREIGN KEY (product_id)
        REFERENCES products(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_product_flights_flight
        FOREIGN KEY (flight_id)
        REFERENCES flights(id)
        ON DELETE RESTRICT,

    -- Ensure sequence order is positive
    CONSTRAINT chk_sequence_order_positive
        CHECK (sequence_order > 0),

    -- Unique constraint to prevent duplicate (product, flight, sequence) combinations
    CONSTRAINT uq_product_flight_sequence
        UNIQUE (product_id, flight_id, sequence_order)
);

-- Create indexes for better query performance
CREATE INDEX idx_product_flights_product_id ON product_flights(product_id);
CREATE INDEX idx_product_flights_flight_id ON product_flights(flight_id);
CREATE INDEX idx_product_flights_sequence ON product_flights(product_id, sequence_order);

-- Add comment for documentation
COMMENT ON TABLE product_flights IS 'Junction table linking products to their assigned flights with sequence order for multi-hop routes';
COMMENT ON COLUMN product_flights.sequence_order IS 'Order of flight in the route: 1 for first flight, 2 for second, etc.';
