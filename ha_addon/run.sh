#!/bin/bash

echo "Starting Genvex Monitor Add-on..."

CONFIG_PATH=/data/options.json

# Check if config exists
if [ -f "$CONFIG_PATH" ]; then
    export GENVEX_IP=$(jq --raw-output '.genvex_ip' $CONFIG_PATH)
    export GENVEX_EMAIL=$(jq --raw-output '.genvex_email' $CONFIG_PATH)
    export POLL_INTERVAL=$(jq --raw-output '.poll_interval' $CONFIG_PATH)
    export BOOST_ENABLED=$(jq --raw-output '.boost_enabled' $CONFIG_PATH)
    export HUMIDITY_RISE_THRESHOLD=$(jq --raw-output '.humidity_rise_threshold' $CONFIG_PATH)
    export BOOST_SPEED=$(jq --raw-output '.boost_speed' $CONFIG_PATH)
    export NORMAL_SPEED=$(jq --raw-output '.normal_speed' $CONFIG_PATH)
    export BOOST_DURATION_MINUTES=$(jq --raw-output '.boost_duration_minutes' $CONFIG_PATH)
    export HUMIDITY_HIGH_THRESHOLD=$(jq --raw-output '.humidity_high_threshold' $CONFIG_PATH)
    export HUMIDITY_LOW_THRESHOLD=$(jq --raw-output '.humidity_low_threshold' $CONFIG_PATH)
    export NIGHT_START=$(jq --raw-output '.night_start' $CONFIG_PATH)
    export NIGHT_END=$(jq --raw-output '.night_end' $CONFIG_PATH)
else
    echo "Warning: $CONFIG_PATH not found. Using environment variables or defaults."
fi

echo "Configuration:"
echo "  Genvex IP: $GENVEX_IP"
echo "  Poll Interval: $POLL_INTERVAL s"
echo "  Boost Enabled: $BOOST_ENABLED"

# Start the Java application
exec java -Djava.net.preferIPv4Stack=true -jar /app/app.jar
