-- Table to store humidity readings
CREATE TABLE IF NOT EXISTS humidity_readings (
    id SERIAL PRIMARY KEY,
    timestamp TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    humidity INTEGER NOT NULL,
    temp_supply DECIMAL(4,1),
    fan_speed_level INTEGER,
    fan_rpm INTEGER
);

-- Index for time-based queries
CREATE INDEX IF NOT EXISTS idx_humidity_timestamp ON humidity_readings(timestamp);
